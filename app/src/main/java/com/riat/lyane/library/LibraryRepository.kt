package com.riat.lyane.library

import android.content.Context
import com.riat.lyane.core.LyLog
import com.riat.lyane.engine.WavIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Yerel kütüphane: üretilen sesler, transkriptler ve dinleme oturumları.
 * Her şey `filesDir/library/` altında saklanır; hiçbir bulut katmanı yoktur.
 */
class LibraryRepository(private val context: Context) {

    @Serializable
    data class Segment(val startSec: Double, val endSec: Double, val text: String)

    @Serializable
    data class Item(
        val id: String,
        val kind: String,          // audio | transcript
        val title: String,
        val createdAtMs: Long,
        val durationMs: Long = 0,
        val text: String = "",
        val fileName: String = "", // library/audio/ altında göreli ad
        val modelId: String = "",
        val segments: List<Segment> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    val audioDir: File get() = File(context.filesDir, "library/audio").apply { mkdirs() }
    private val indexFile: File get() = File(context.filesDir, "library/index.json")

    fun load() {
        _items.value = if (indexFile.isFile) {
            runCatching {
                json.decodeFromString(ListSerializer(Item.serializer()), indexFile.readText())
            }.getOrElse {
                LyLog.w(TAG, "Kütüphane indeksi okunamadı", it); emptyList()
            }.sortedByDescending { it.createdAtMs }
        } else emptyList()
    }

    private fun persist() {
        indexFile.parentFile?.mkdirs()
        indexFile.writeText(json.encodeToString(_items.value))
    }

    suspend fun addAudio(
        title: String,
        samples: FloatArray,
        sampleRate: Int,
        text: String,
        modelId: String
    ): Item = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString().take(8)
        val file = File(audioDir, "$id.wav")
        WavIo.write(file, samples, sampleRate)
        val item = Item(
            id = id,
            kind = "audio",
            title = title.ifBlank { "Seslendirme" },
            createdAtMs = System.currentTimeMillis(),
            durationMs = samples.size * 1000L / sampleRate.coerceAtLeast(1),
            text = text,
            fileName = "audio/$id.wav",
            modelId = modelId
        )
        _items.value = (listOf(item) + _items.value)
        persist()
        item
    }

    suspend fun addTranscript(
        title: String,
        text: String,
        segments: List<Segment>,
        modelId: String,
        durationMs: Long
    ): Item = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString().take(8)
        val item = Item(
            id = id,
            kind = "transcript",
            title = title.ifBlank { "Çeviriyazı" },
            createdAtMs = System.currentTimeMillis(),
            durationMs = durationMs,
            text = text,
            segments = segments,
            modelId = modelId
        )
        _items.value = (listOf(item) + _items.value)
        persist()
        item
    }

    fun fileOf(item: Item): File? =
        if (item.fileName.isBlank()) null
        else File(context.filesDir, "library/${item.fileName}").takeIf { it.isFile }

    /** Kütüphaneye dışarıdan WAV alır (Drop ya da dosya seçici). */
    suspend fun importWav(file: File, title: String): Item? = withContext(Dispatchers.IO) {
        runCatching {
            val data = WavIo.read(file)
            val id = UUID.randomUUID().toString().take(8)
            val dest = File(audioDir, "$id.wav")
            file.copyTo(dest, overwrite = true)
            val item = Item(
                id = id, kind = "audio",
                title = title.ifBlank { file.nameWithoutExtension },
                createdAtMs = System.currentTimeMillis(),
                durationMs = data.samples.size * 1000L / data.sampleRate,
                fileName = "audio/$id.wav"
            )
            _items.value = (listOf(item) + _items.value)
            persist()
            item
        }.getOrNull()
    }

    fun delete(id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        fileOf(item)?.delete()
        _items.value = _items.value.filterNot { it.id == id }
        persist()
    }

    fun rename(id: String, title: String) {
        _items.value = _items.value.map { if (it.id == id) it.copy(title = title) else it }
        persist()
    }

    fun usedBytes(): Long = audioDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        private const val TAG = "Library"
    }
}
