package com.riat.lyane.engine

import android.content.Context
import com.riat.lyane.core.LyLog
import com.riat.lyane.model.InstalledModel
import com.riat.lyane.model.ModelStore
import com.riat.lyane.model.TaskType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Model kıyaslaması: yükleme süresi ve gerçek zaman çarpanı (RTF).
 * Sonuçlar yalnızca cihazda saklanır (filesDir/benchmarks.json).
 */
class Benchmarker(
    private val engines: EngineManager,
    private val store: ModelStore
) {

    @Serializable
    data class Result(
        val modelId: String,
        val modelName: String,
        val task: String,
        val loadMs: Long,
        val processMs: Double,
        val audioSec: Double,
        val xRealtime: Double,
        val atMs: Long = System.currentTimeMillis()
    ) {
        val rtf: Double get() = if (audioSec > 0) processMs / 1000.0 / audioSec else 0.0
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file: File get() = File(store.modelsDir.parentFile, "benchmarks.json")

    fun history(): List<Result> =
        if (file.isFile) runCatching {
            json.decodeFromString(ListSerializer(Result.serializer()), file.readText())
        }.getOrDefault(emptyList()) else emptyList()

    private fun save(r: Result) {
        val list = (history() + r).sortedByDescending { it.atMs }.take(30)
        file.writeText(json.encodeToString(list))
    }

    /** TTS modelini ölçer. */
    suspend fun runTts(model: InstalledModel, text: String): Result {
        val t0 = System.nanoTime()
        val session = engines.tts(model)
        val loadMs = (System.nanoTime() - t0) / 1_000_000

        // ısınma turu
        runCatching { session.tts.generate(text.take(40), sid = 0, speed = 1.0f) }

        val t1 = System.nanoTime()
        val audio = session.tts.generate(text, sid = 0, speed = 1.0f)
        val processMs = (System.nanoTime() - t1) / 1e6
        val audioSec = audio.samples.size.toDouble() / session.sampleRate.coerceAtLeast(1)
        val x = if (processMs > 0) audioSec / (processMs / 1000.0) else 0.0
        val r = Result(model.id, model.name, "tts", loadMs, processMs, audioSec, x)
        save(r)
        LyLog.i(TAG, "Benchmark TTS ${model.name}: load=${loadMs}ms rtf=%.3f".format(r.rtf))
        return r
    }

    /** ASR modelini bir ses örneğiyle ölçer. */
    suspend fun runAsr(model: InstalledModel, samples: FloatArray, sampleRate: Int): Result {
        val t0 = System.nanoTime()
        val session = engines.asr(model)
        val loadMs = (System.nanoTime() - t0) / 1_000_000
        val audioSec = samples.size.toDouble() / sampleRate.coerceAtLeast(1)

        // ısınma
        if (samples.size > 16000) {
            runCatching {
                when (session) {
                    is EngineManager.AsrSession.Offline ->
                        session.decode(samples.copyOfRange(0, 16000), sampleRate)
                    is EngineManager.AsrSession.Live -> Unit
                }
            }
        }
        val t1 = System.nanoTime()
        when (session) {
            is EngineManager.AsrSession.Offline -> session.decode(samples, sampleRate)
            is EngineManager.AsrSession.Live -> Unit
        }
        val processMs = (System.nanoTime() - t1) / 1e6
        val x = if (processMs > 0) audioSec / (processMs / 1000.0) else 0.0
        val r = Result(model.id, model.name, model.task, loadMs, processMs, audioSec, x)
        save(r)
        LyLog.i(TAG, "Benchmark ASR ${model.name}: load=${loadMs}ms rtf=%.3f".format(r.rtf))
        return r
    }

    fun clear() {
        file.delete()
    }

    companion object { private const val TAG = "Benchmarker" }
}
