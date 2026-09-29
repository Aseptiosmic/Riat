package com.riat.lyane.model

import android.content.Context
import com.riat.lyane.core.LyLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Model kataloğu yöneticisi.
 *
 * Kaynaklar (öncelik sırasıyla):
 *  1. `assets/catalog/models.json` — uygulama ile gömülü gelen resmî liste
 *  2. `filesDir/catalogs/*.json`   — kullanıcının içe aktardığı kataloglar
 *     (SAF ile dosyadan ya da Lyane Drop ile başka cihazdan gelir)
 *
 * Katalog listesi hiçbir sunucudan çekilmez; yalnızca bu iki yerel kaynak
 * birleştirilir. Katalogdaki URL'ler model dosyalarının açık kaynak
 * dağıtım noktalarıdır (GitHub Releases / HuggingFace).
 */
class ModelRegistry(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val _catalog = MutableStateFlow<List<ModelSpec>>(emptyList())
    val catalog: StateFlow<List<ModelSpec>> = _catalog.asStateFlow()

    private val _customCatalogs = MutableStateFlow<List<String>>(emptyList())
    val customCatalogs: StateFlow<List<String>> = _customCatalogs.asStateFlow()

    fun catalogsDir(): File = File(context.filesDir, "catalogs").apply { mkdirs() }

    fun load() {
        val result = LinkedHashMap<String, ModelSpec>()
        // 1) gömülü katalog
        runCatching {
            val text = context.assets.open("catalog/models.json").bufferedReader().use { it.readText() }
            val parsed = json.decodeFromString(CatalogFile.serializer(), text)
            parsed.models.forEach { result[it.id] = it }
        }.onFailure {
            LyLog.e(TAG, "Gömülü katalog okunamadı", it)
        }
        // 2) kullanıcı katalogları
        val names = mutableListOf<String>()
        catalogsDir().listFiles { f -> f.isFile && f.extension == "json" }?.sortedBy { it.name }
            ?.forEach { file ->
                runCatching {
                    val parsed = json.decodeFromString(CatalogFile.serializer(), file.readText())
                    parsed.models.forEach { result[it.id] = it }
                    names += file.name
                }.onFailure {
                    LyLog.w(TAG, "Katalog atlandı: ${file.name}", it)
                }
            }
        _customCatalogs.value = names
        _catalog.value = result.values.sortedWith(
            compareByDescending<ModelSpec> { it.recommended }.thenBy { it.task }.thenBy { it.name }
        )
        LyLog.i(TAG, "Katalog yüklendi: ${_catalog.value.size} model (${names.size} özel katalog)")
    }

    /** Yeni bir katalog JSON'u içe aktarır ve kalıcı olarak saklar. */
    fun importCatalog(fileName: String, content: String): Result<Int> = runCatching {
        val parsed = json.decodeFromString(CatalogFile.serializer(), content)
        require(parsed.models.isNotEmpty()) { "Katalogda model yok" }
        val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(catalogsDir(), if (safe.endsWith(".json")) safe else "$safe.json")
        file.writeText(content)
        load()
        parsed.models.size
    }

    fun removeCatalog(fileName: String) {
        File(catalogsDir(), fileName).delete()
        load()
    }

    fun byId(id: String): ModelSpec? = _catalog.value.firstOrNull { it.id == id }

    fun byTask(task: String): List<ModelSpec> = _catalog.value.filter { it.task == task }

    companion object {
        private const val TAG = "ModelRegistry"
    }
}
