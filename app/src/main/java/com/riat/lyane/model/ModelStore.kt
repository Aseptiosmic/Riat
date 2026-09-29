package com.riat.lyane.model

import android.content.Context
import com.riat.lyane.core.LyLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Kurulu modellerin deposu: `filesDir/models/<dirName>/lyane.json`
 * Her model dizini kendi tanımını taşır; bu sayede Lyane Drop ile gönderilen
 * modeller alıcıda ek kuruluma gerek duymadan çalışır.
 */
class ModelStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _installed = MutableStateFlow<List<InstalledModel>>(emptyList())
    val installed: StateFlow<List<InstalledModel>> = _installed.asStateFlow()

    val modelsDir: File get() = File(context.filesDir, "models").apply { mkdirs() }
    val cacheDir: File get() = File(context.cacheDir, "model-downloads").apply { mkdirs() }

    fun scan() {
        val found = mutableListOf<InstalledModel>()
        modelsDir.listFiles { f -> f.isDirectory }?.forEach { dir ->
            val meta = File(dir, META)
            if (meta.isFile) {
                runCatching {
                    found += json.decodeFromString(InstalledModel.serializer(), meta.readText())
                }.onFailure { LyLog.w(TAG, "Bozuk model meta: ${dir.name}", it) }
            }
        }
        _installed.value = found.sortedBy { it.name }
        LyLog.i(TAG, "Kurulu model: ${found.size}")
    }

    fun dirOf(m: InstalledModel): File = File(modelsDir, m.dirName)

    fun freeSpaceBytes(): Long = modelsDir.usableSpace

    fun usedSpaceBytes(): Long =
        _installed.value.sumOf { it.sizeBytes }

    fun byId(id: String): InstalledModel? = _installed.value.firstOrNull { it.id == id }

    fun byTask(task: String): List<InstalledModel> = _installed.value.filter { it.task == task }

    /**
     * Çıkarma tamamlanmış bir model dizinini kaydeder.
     * @param dirName models/ altındaki hedef dizin adı
     */
    fun register(spec: ModelSpec, dirName: String, source: String): InstalledModel {
        val dir = File(modelsDir, dirName)
        require(dir.isDirectory) { "Model dizini yok: $dirName" }
        val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val model = InstalledModel(
            spec = spec.copy(sizeBytes = size),
            installedAtMs = System.currentTimeMillis(),
            sizeBytes = size,
            dirName = dirName,
            source = source
        )
        File(dir, META).writeText(json.encodeToString(model))
        scan()
        LyLog.i(TAG, "Model kaydedildi: ${spec.id} (${size} bayt, $source)")
        return model
    }

    fun delete(id: String): Boolean {
        val m = byId(id) ?: return false
        val dir = dirOf(m)
        dir.deleteRecursively()
        scan()
        LyLog.i(TAG, "Model silindi: ${id}")
        return true
    }

    /**
     * Çıkarılmış bir model dizinini içe aktarır.
     * Dizinde `lyane.json` varsa (Lyane Drop ile gelen modeller) tanım doğrudan
     * kullanılır; yoksa motor türü otomatik algılanır.
     */
    fun importFromDir(dir: File, name: String): Result<InstalledModel> = runCatching {
        val meta = File(dir, META)
        val spec: ModelSpec = if (meta.isFile) {
            json.decodeFromString(InstalledModel.serializer(), meta.readText()).spec
        } else {
            ModelAutoDetect.detect(dir, name)
                ?: error("Model türü algılanamadı. Katalogdan indirmeyi ya da özel katalog kullanmayı deneyin.")
        }
        val dirName = uniqueDirName(spec.id)
        val target = File(modelsDir, dirName)
        dir.copyRecursively(target, overwrite = true)
        register(spec, dirName, "import")
    }

    fun uniqueDirName(base: String): String {
        val safe = base.replace(Regex("[^A-Za-z0-9._-]"), "_")
        var candidate = safe
        var i = 1
        while (File(modelsDir, candidate).exists()) {
            candidate = "${safe}_$i"
            i++
        }
        return candidate
    }

    companion object {
        private const val TAG = "ModelStore"
        const val META = "lyane.json"
    }
}
