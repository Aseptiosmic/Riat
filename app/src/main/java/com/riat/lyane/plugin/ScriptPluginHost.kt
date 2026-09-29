package com.riat.lyane.plugin

import com.riat.lyane.core.LyLog
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.File

/**
 * Rhino (saf Java JS motoru) üzerinde çalışan script eklentisi.
 *
 * Güvenlik modeli:
 *  - Script yalnızca `Lyane` köprüsüyle dış dünyaya erişir
 *  - `Lyane` köprüsü her çağrıda izin denetler
 *  - Java sınıflarına doğrudan erişim kapatılır (ClassShutter)
 */
class ScriptPluginHost(
    val manifest: PluginManifest,
    private val scriptFile: File,
    private val bridge: PluginBridge
) {
    private val lock = Any()
    private var scope: Scriptable? = null
    @Volatile var failed = false
        private set

    private fun ensureScope(): Scriptable? {
        scope?.let { return it }
        if (failed || !scriptFile.isFile) return null
        return synchronized(lock) {
            scope?.let { return it }
            val cx = Context.enter()
            try {
                cx.optimizationLevel = -1 // yorumlamalı mod: Android'de en güvenli
                cx.setClassShutter(ClassShutter { name ->
                    // Standart JS ve java temel tipleri dışında her şey kapalı
                    name.startsWith("org.mozilla.javascript.") ||
                        name == "java.lang.String" || name == "java.lang.Object" ||
                        name == "java.lang.Double" || name == "java.lang.Integer" ||
                        name == "java.lang.Boolean" || name == "java.lang.Long" ||
                        name == "java.lang.Character" || name == "java.lang.Number" ||
                        name == "com.riat.lyane.plugin.PluginBridge"
                })
                val s = cx.initStandardObjects()
                ScriptableObject.putProperty(s, "Lyane", Context.javaToJS(bridge, s))
                cx.evaluateString(s, scriptFile.readText(), scriptFile.name, 1, null)
                scope = s
                LyLog.i(TAG, "Script eklentisi yüklendi: ${manifest.id}")
                s
            } catch (e: Exception) {
                failed = true
                LyLog.e(TAG, "Script eklentisi yüklenemedi: ${manifest.id}", e)
                bridge.logError("Yükleme hatası: ${e.message}")
                null
            } finally {
                Context.exit()
            }
        }
    }

    /** Scriptteki fonksiyonu çağırır. Yoksa null döner. */
    fun call(functionName: String, args: Array<Any?>): Any? {
        val s = ensureScope() ?: return null
        return synchronized(lock) {
            val cx = Context.enter()
            try {
                val f = s.get(functionName, s)
                if (f === Scriptable.NOT_FOUND || f !is Function) return@synchronized null
                val jsArgs = args.map { Context.javaToJS(it, s) }.toTypedArray()
                val r = f.call(cx, s, s, jsArgs)
                when (r) {
                    is String, is Number, is Boolean -> r
                    is org.mozilla.javascript.NativeObject -> nativeObjectToMap(r)
                    else -> null
                }
            } catch (e: Exception) {
                LyLog.w(TAG, "Eklenti çağrısı hatası: $functionName (${manifest.id})", e)
                bridge.logError("$functionName hatası: ${e.message}")
                null
            } finally {
                Context.exit()
            }
        }
    }

    /** JS nesnesini (ör. {output: "..."}) Map<String,String>'e çevirir. */
    private fun nativeObjectToMap(obj: org.mozilla.javascript.NativeObject): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (id in obj.allIds) {
            val v = when (id) {
                is String -> obj.get(id, obj)
                is Int -> obj.get(id, obj)
                is Number -> obj.get(id.toInt(), obj)
                else -> obj.get(id.toString(), obj)
            }
            map[id.toString()] = when (v) {
                is String -> v
                is Number, is Boolean -> v.toString()
                is org.mozilla.javascript.Undefined, null -> ""
                else -> v?.toString() ?: ""
            }
        }
        return map
    }

    fun unload() {
        synchronized(lock) { scope = null }
    }

    companion object { private const val TAG = "ScriptPlugin" }
}

/**
 * Script eklentilerine sunulan izinli köprü (`Lyane` nesnesi).
 * Rhino bu sınıfı NativeJavaObject ile sarar.
 *
 * Dikkat: yöntem imzaları basit tutulmalı (String/boolean parametreler)
 * ki Rhino yansıtması sorunsuz çalışsın.
 */
class PluginBridge(
    val pluginId: String,
    private val permissions: Set<String>,
    private val granted: (String) -> Boolean,
    private val storageDir: File,
    private val logSink: (String) -> Unit,
    private val toast: (String) -> Unit,
    private val modelsProvider: () -> Array<String>,
    val appVersion: String
) {

    private fun can(p: String) = p in permissions && granted(p)

    fun hasPermission(p: String) = can(p)

    fun log(message: String) {
        logSink(message)
    }

    fun logError(message: String) {
        logSink("HATA: $message")
    }

    fun toast(message: String) {
        if (can(PluginPermissions.UI)) toast(message)
    }

    // ── Kalıcı anahtar/değer (yalnızca bu eklentiye ait) ────────────────
    fun storageSet(key: String, value: String) {
        if (!can(PluginPermissions.STORAGE)) return
        runCatching {
            val f = File(storageDir, "kv.json")
            val map = (if (f.isFile) readKv(f) else LinkedHashMap()).toMutableMap()
            map[sanitizeKey(key)] = value
            f.parentFile?.mkdirs()
            f.writeText(kvToJson(map))
        }
    }

    fun storageGet(key: String): String? {
        if (!can(PluginPermissions.STORAGE)) return null
        return runCatching {
            val f = File(storageDir, "kv.json")
            if (!f.isFile) return@runCatching null
            readKv(f)[sanitizeKey(key)]
        }.getOrNull()
    }

    // ── Dosya alanı (yalnızca bu eklentiye ait) ─────────────────────────
    fun writeFile(name: String, content: String): Boolean {
        if (!can(PluginPermissions.FILES)) return false
        return runCatching {
            val f = safeFile(name) ?: return@runCatching false
            f.parentFile?.mkdirs()
            f.writeText(content)
            true
        }.getOrDefault(false)
    }

    fun readFile(name: String): String? {
        if (!can(PluginPermissions.FILES)) return null
        return runCatching {
            safeFile(name)?.takeIf { it.isFile }?.readText()
        }.getOrNull()
    }

    // ── Kurulu modeller ─────────────────────────────────────────────────
    fun listModels(): Array<String> {
        if (!can(PluginPermissions.MODELS)) return emptyArray()
        return modelsProvider()
    }

    fun version(): String = appVersion

    // ── yardımcılar ─────────────────────────────────────────────────────
    private fun safeFile(name: String): File? {
        val cleaned = name.replace('\\', '/')
        if (cleaned.contains("..")) return null
        val f = File(storageDir, "files/$cleaned")
        return if (f.canonicalPath.startsWith(storageDir.canonicalPath)) f else null
    }

    private fun sanitizeKey(k: String): String = k.take(120).replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun readKv(f: File): Map<String, String> {
        val text = f.readText()
        val map = LinkedHashMap<String, String>()
        // basit JSON okuma; kotlinx.serialization JVM dışı yöntemle uğraşmadan
        Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(text).forEach {
            map[it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")] =
                it.groupValues[2].replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return map
    }

    private fun kvToJson(map: Map<String, String>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(",")
            first = false
            sb.append("\"").append(k.replace("\\", "\\\\").replace("\"", "\\\""))
                .append("\":\"").append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"")
        }
        sb.append("}")
        return sb.toString()
    }
}
