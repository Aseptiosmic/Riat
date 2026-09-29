package com.riat.lyane.plugin

import android.content.Context
import android.widget.Toast
import com.riat.lyane.core.LyLog
import com.riat.lyane.model.ModelStore
import com.riat.lyane.settings.SettingsRepository
import dalvik.system.DexClassLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

/**
 * Eklenti yöneticisi: kurma, kaldırma, izinler ve kancalar.
 *
 * Eklenti kaynakları:
 *  - .lyplugin dosyası (ZIP) → dosya seçici ya da Lyane Drop
 *  - Uygulama ile gelen örnek eklentiler (varlıklardan kurulum)
 *
 * Eklentiler sandbox'ta yaşar: kendi dizinlerinde dosya/kv saklayabilir,
 * bildirim gösterebilir, TTS/STT metin kancalarına takılabilir ve kendi
 * araç arayüzlerini tanımlayabilir. Ağ erişimi yoktur.
 */
class PluginManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val modelStore: ModelStore
) {

    data class InstalledPlugin(
        val manifest: PluginManifest,
        val dir: File,
        val installedAtMs: Long,
        val enabled: Boolean,
        val origin: String
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _plugins = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val plugins: StateFlow<List<InstalledPlugin>> = _plugins.asStateFlow()

    private val _logs = MutableStateFlow<Map<String, MutableList<String>>>(emptyMap())
    val logs: StateFlow<Map<String, List<String>>> = _logs

    private val scope = CoroutineScope(Dispatchers.Default + kotlinx.coroutines.Job())

    /** Onaylanmış izinler önbelleği: "pluginId:izin" kümesi. */
    @Volatile private var grantedPerms: Set<String> = emptySet()

    // çalışma zamanı örnekleri
    private val scriptHosts = HashMap<String, ScriptPluginHost>()
    private val dexInstances = HashMap<String, LyanePlugin>()
    private val hostLock = Any()

    val pluginsDir: File get() = File(context.filesDir, "plugins").apply { mkdirs() }
    fun dataDir(id: String): File = File(context.filesDir, "plugin-data/$id").apply { mkdirs() }
    fun dexOptDir(id: String): File = File(context.codeCacheDir, "plugins/$id").apply { mkdirs() }

    // ── Yükleme / tarama ────────────────────────────────────────────────

    fun scan() {
        val enabledIds = runBlocking { settings.current().enabledPlugins }
        val found = mutableListOf<InstalledPlugin>()
        pluginsDir.listFiles { f -> f.isDirectory }?.forEach { dir ->
            val mf = File(dir, "plugin.json")
            if (!mf.isFile) return@forEach
            runCatching {
                val m = json.decodeFromString(PluginManifest.serializer(), mf.readText())
                found += InstalledPlugin(
                    manifest = m,
                    dir = dir,
                    installedAtMs = mf.lastModified(),
                    enabled = m.id in enabledIds,
                    origin = File(dir, "origin.txt").takeIf { it.isFile }?.readText()?.trim() ?: "bilinmiyor"
                )
            }.onFailure { LyLog.w(TAG, "Bozuk eklenti: ${dir.name}", it) }
        }
        _plugins.value = found.sortedBy { it.manifest.name }
        // devre dışı bırakılanların çalışma zamanı örneklerini boşalt
        synchronized(hostLock) {
            for ((id, host) in scriptHosts.toList()) {
                if (found.none { it.manifest.id == id && it.enabled }) {
                    host.unload()
                    scriptHosts.remove(id)
                }
            }
            for ((id, inst) in dexInstances.toList()) {
                if (found.none { it.manifest.id == id && it.enabled }) {
                    runCatching { inst.onUnload() }
                    dexInstances.remove(id)
                }
            }
        }
        LyLog.i(TAG, "Eklentiler tarandı: ${found.size} adet, ${found.count { it.enabled }} etkin")
    }

    // ── Kurma ───────────────────────────────────────────────────────────

    /**
     * .lyplugin (ZIP) dosyasından kurar.
     * @param origin kaynağı açıklayan kısa metin ("dosya", "lyane-drop:Ad")
     */
    fun install(zipFile: File, origin: String): Result<InstalledPlugin> = runCatching {
        ZipFile(zipFile).use { zip ->
            val mfEntry = zip.getEntry("plugin.json") ?: error("plugin.json yok — bu bir Lyane eklentisi mi?")
            val manifest = json.decodeFromString(
                PluginManifest.serializer(),
                zip.getInputStream(mfEntry).bufferedReader().use { it.readText() }
            )
            require(manifest.id.matches(Regex("[A-Za-z0-9._-]{3,64}"))) { "Geçersiz eklenti kimliği" }
            require(manifest.api == 1) { "Desteklenmeyen eklenti API sürümü: ${manifest.api}" }
            val type = manifest.type
            require(type == "script" || type == "dex" || type == "pack") { "Bilinmeyen eklenti türü: $type" }
            if (type == "script") require(manifest.entry.isNotBlank()) { "script eklentisi entry alanı gerektirir" }
            if (type == "dex") require(manifest.mainClass.isNotBlank()) { "dex eklentisi mainClass alanı gerektirir" }

            // mevcut kurulumu kaldır
            uninstall(manifest.id)

            val dir = File(pluginsDir, manifest.id)
            dir.mkdirs()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val out = File(dir, e.name.replace('\\', '/'))
                if (!out.canonicalPath.startsWith(dir.canonicalPath)) error("Güvensiz eklenti yolu: ${e.name}")
                out.parentFile?.mkdirs()
                zip.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            File(dir, "origin.txt").writeText(origin)
            scan()
            // yeni kurulanı otomatik etkinleştir
            scope.launch { settings.setPluginEnabled(manifest.id, true) }
            // yeniden tara (etkinlik)
            scan()
            _plugins.value.first { it.manifest.id == manifest.id }
        }
    }

    /** Uygulama varlıklarındaki örnek eklentiyi kurar. */
    fun installBundledSample(): Result<InstalledPlugin> = runCatching {
        val staging = File(context.cacheDir, "sample-plugin").apply { deleteRecursively(); mkdirs() }
        listOf("plugin.json", "main.js").forEach { name ->
            context.assets.open("plugins/sample-tr-number/$name").use { input ->
                File(staging, name).outputStream().use { input.copyTo(it) }
            }
        }
        val zip = File(context.cacheDir, "lyane-sample.lyplugin")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
            staging.listFiles()?.forEach { f ->
                zos.putNextEntry(java.util.zip.ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        install(zip, "gömülü örnek")
    }

    fun uninstall(id: String) {
        synchronized(hostLock) {
            scriptHosts.remove(id)?.unload()
            dexInstances.remove(id)?.let { runCatching { it.onUnload() } }
        }
        File(pluginsDir, id).deleteRecursively()
        dataDir(id).deleteRecursively()
        dexOptDir(id).deleteRecursively()
        scope.launch { settings.forgetPlugin(id) }
        scan()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        scope.launch {
            settings.setPluginEnabled(id, enabled)
            scan()
        }
    }

    suspend fun isPermissionGranted(pluginId: String, permission: String): Boolean =
        "$pluginId:$permission" in settings.current().approvedPluginPermissions

    fun grantPermission(pluginId: String, permission: String, grant: Boolean) {
        scope.launch {
            settings.approvePluginPermission(pluginId, permission, grant)
        }
    }

    // ── Kancalar ────────────────────────────────────────────────────────

    private fun enabled(): List<InstalledPlugin> = _plugins.value.filter { it.enabled }

    /** TTS ön işlemesi: metni etkin eklentilerden sırayla geçirir. */
    fun processTtsText(text: String, modelId: String): String {
        var result = text
        for (p in enabled()) {
            if (PluginHooks.TTS_PREPROCESS !in p.manifest.hooks) continue
            if (!runBlocking { isPermissionGranted(p.manifest.id, PluginPermissions.TTS_TEXT) }) continue
            val out = when (p.manifest.type) {
                "script" -> scriptHost(p)?.call("onTtsPreprocess", arrayOf(result, modelId)) as? String
                "dex" -> dexInstance(p)?.onTtsPreprocess(result, modelId)
                else -> null
            }
            if (out != null && out.isNotBlank()) result = out
        }
        return result
    }

    /** STT son işlemesi. */
    fun processAsrText(text: String, modelId: String): String {
        var result = text
        for (p in enabled()) {
            if (PluginHooks.ASR_POSTPROCESS !in p.manifest.hooks) continue
            if (!runBlocking { isPermissionGranted(p.manifest.id, PluginPermissions.ASR_TEXT) }) continue
            val out = when (p.manifest.type) {
                "script" -> scriptHost(p)?.call("onAsrPostprocess", arrayOf(result, modelId)) as? String
                "dex" -> dexInstance(p)?.onAsrPostprocess(result, modelId)
                else -> null
            }
            if (out != null && out.isNotBlank()) result = out
        }
        return result
    }

    /** Etkin eklentilerin araçları (Araçlar ekranında görünür). */
    fun allTools(): List<Pair<InstalledPlugin, PluginTool>> =
        enabled().flatMap { p -> p.manifest.tools.map { p to it } }

    /** Araç butonuna basıldığında çağrılır. */
    fun runToolAction(pluginId: String, toolId: String, actionId: String, state: Map<String, String>): Map<String, String> {
        val p = enabled().firstOrNull { it.manifest.id == pluginId } ?: return emptyMap()
        return when (p.manifest.type) {
            "script" -> {
                val host = scriptHost(p) ?: return emptyMap()
                @Suppress("UNCHECKED_CAST")
                host.call("onToolAction", arrayOf(toolId, actionId, state)) as? Map<String, String>
                    ?: emptyMap()
            }
            "dex" -> dexInstance(p)?.onToolAction(toolId, actionId, state) ?: emptyMap()
            else -> emptyMap()
        } ?: emptyMap()
    }

    // ── Çalışma zamanı örnekleri ────────────────────────────────────────

    private fun scriptHost(p: InstalledPlugin): ScriptPluginHost? {
        synchronized(hostLock) {
            scriptHosts[p.manifest.id]?.let { return it }
            val bridge = PluginBridge(
                pluginId = p.manifest.id,
                permissions = p.manifest.permissions.toSet(),
                granted = { perm -> runBlocking { isPermissionGranted(p.manifest.id, perm) } },
                storageDir = dataDir(p.manifest.id),
                logSink = { msg -> pushLog(p.manifest.id, msg) },
                toast = { msg -> scope.launch(Dispatchers.Main) { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } },
                modelsProvider = { modelModelsArray() },
                appVersion = "1.0.0"
            )
            val host = ScriptPluginHost(p.manifest, File(p.dir, p.manifest.entry), bridge)
            scriptHosts[p.manifest.id] = host
            return host
        }
    }

    private fun modelModelsArray(): Array<String> =
        modelStore.installed.value.map { "${it.id}|${it.name}|${it.task}" }.toTypedArray()

    private fun dexInstance(p: InstalledPlugin): LyanePlugin? {
        synchronized(hostLock) {
            dexInstances[p.manifest.id]?.let { return it }
            val dexFile = File(p.dir, "plugin.dex")
            if (!dexFile.isFile) return null
            return runCatching {
                val loader = DexClassLoader(
                    dexFile.absolutePath,
                    dexOptDir(p.manifest.id).absolutePath,
                    null,
                    javaClass.classLoader
                )
                val cls = loader.loadClass(p.manifest.mainClass)
                val inst = cls.getDeclaredConstructor().newInstance() as LyanePlugin
                val ctx = PluginContext(
                    pluginId = p.manifest.id,
                    storageDir = dataDir(p.manifest.id),
                    logger = { pushLog(p.manifest.id, it) },
                    appVersion = "1.0.0"
                )
                if (!inst.onLoad(ctx)) {
                    pushLog(p.manifest.id, "onLoad false döndürdü; eklenti devre dışı")
                    return null
                }
                dexInstances[p.manifest.id] = inst
                inst
            }.onFailure {
                LyLog.e(TAG, "DEX eklentisi yüklenemedi: ${p.manifest.id}", it)
            }.getOrNull()
        }
    }

    // ── Günlükler ───────────────────────────────────────────────────────

    private fun pushLog(pluginId: String, message: String) {
        LyLog.d("Plugin/$pluginId", message)
        synchronized(_logs) {
            val map = _logs.value.toMutableMap()
            val list = map.getOrDefault(pluginId, mutableListOf())
            if (list is MutableList<String>) {
                list.add(0, "${System.currentTimeMillis() % 1_000_000_000}: $message")
                while (list.size > 200) list.removeAt(list.size - 1)
                map[pluginId] = list
                _logs.value = map
            }
        }
    }

    fun logsOf(pluginId: String): List<String> = _logs.value[pluginId] ?: emptyList()

    companion object { private const val TAG = "PluginManager" }
}
