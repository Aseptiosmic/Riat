package com.riat.lyane.speak

import android.content.Context
import android.content.Intent
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.riat.lyane.core.LyLog
import com.riat.lyane.core.TextSplitter
import com.riat.lyane.engine.EngineManager
import com.riat.lyane.engine.PcmPlayer
import com.riat.lyane.library.LibraryRepository
import com.riat.lyane.model.InstalledModel
import com.riat.lyane.model.ModelStore
import com.riat.lyane.plugin.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Konuşma (TTS) hattı: metin → eklenti ön işlemesi → cümle cümle sentez →
 * akış halinde çalma → (isteğe bağlı) WAV kaydı.
 *
 * `generateWithCallback` sayesinde ilk cümle üretilir üretilmez çalmaya
 * başlanır; kalan cümleler arka planda üretilmeye devam eder.
 */
class SpeakController(
    private val context: Context,
    private val engines: EngineManager,
    private val store: ModelStore,
    private val plugins: PluginManager,
    private val library: LibraryRepository
) {

    enum class Phase { IDLE, PREPARING, SPEAKING, PAUSED, FINISHED, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val modelId: String = "",
        val sid: Int = 0,
        val speed: Float = 1.0f,
        val sentenceIndex: Int = 0,
        val sentenceCount: Int = 0,
        val currentSentence: String = "",
        val generatedSec: Double = 0.0,
        val processingSec: Double = 0.0,
        val playbackHeadMs: Long = 0,
        val savedItemId: String = "",
        val error: String = "",
        val pluginApplied: Boolean = false
    ) {
        /** Gerçek zamanın kaç katı hızlı üretildiği. */
        val xRealtime: Double get() = if (processingSec > 0) generatedSec / processingSec else 0.0
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var job: Job? = null
    private var player: PcmPlayer? = null

    private val stopFlag = AtomicBoolean(false)
    private val pauseFlag = AtomicBoolean(false)
    private val skipFlag = AtomicBoolean(false)

    /** Uzun metni seslendirmeye başlar. */
    fun start(text: String, modelId: String, sid: Int, speed: Float, saveToLibrary: Boolean) {
        if (_state.value.phase == Phase.PREPARING || _state.value.phase == Phase.SPEAKING) return
        stopFlag.set(false); pauseFlag.set(false); skipFlag.set(false)
        _state.value = State(phase = Phase.PREPARING, modelId = modelId, sid = sid, speed = speed)
        ensureService()
        job = scope.launch {
            runCatching {
                speakInternal(text, modelId, sid, speed, saveToLibrary)
            }.onFailure { e ->
                LyLog.e(TAG, "Seslendirme hatası", e)
                player?.stopAndFlush()
                _state.value = _state.value.copy(phase = Phase.FAILED, error = e.message ?: "Bilinmeyen hata")
            }
        }
    }

    private suspend fun speakInternal(text: String, modelId: String, sid: Int, speed: Float, save: Boolean) {
        val model: InstalledModel = store.byId(modelId) ?: error("Model bulunamadı: $modelId")
        val session = engines.tts(model)

        // eklenti ön işlemesi
        val processed = plugins.processTtsText(text, modelId)
        val sentences = TextSplitter.sentences(processed)
        if (sentences.isEmpty()) error("Seslendirilecek metin yok")

        player?.release()
        player = PcmPlayer(session.sampleRate).also { it.start() }
        _state.value = _state.value.copy(
            phase = Phase.SPEAKING,
            sentenceCount = sentences.size,
            sentenceIndex = 0,
            pluginApplied = processed != text
        )

        val collected = ArrayList<Float>(16000 * 30)
        var generatedTotalSec = 0.0
        var processingTotalSec = 0.0
        var completed = 0

        var idx = 0
        while (idx < sentences.size) {
            val sentence = sentences[idx]
            _state.value = _state.value.copy(sentenceIndex = idx, currentSentence = sentence)
            val chunkBuf = ArrayList<Float>(16000 * 12)

            val t0 = System.nanoTime()
            var aborted = false
            val audio: GeneratedAudio = session.tts.generateWithCallback(
                text = sentence,
                sid = sid,
                speed = speed
            ) { samples ->
                if (stopFlag.get()) {
                    aborted = true
                    player?.stopAndFlush()
                    0
                } else if (skipFlag.get()) {
                    skipFlag.set(false)
                    aborted = true
                    0
                } else if (pauseFlag.get()) {
                    aborted = true
                    player?.pause()
                    0
                } else {
                    player?.write(samples)
                    chunkBuf.addAll(samples.toList())
                    _state.value = _state.value.copy(playbackHeadMs = player?.headPositionMs() ?: 0)
                    1
                }
            }
            val dt = (System.nanoTime() - t0) / 1e9
            processingTotalSec += dt
            val samples = if (aborted && audio.samples.isEmpty()) chunkBuf.toFloatArray() else audio.samples
            generatedTotalSec += samples.size.toDouble() / session.sampleRate.coerceAtLeast(1)
            _state.value = _state.value.copy(generatedSec = generatedTotalSec, processingSec = processingTotalSec)

            if (stopFlag.get()) break

            if (pauseFlag.get()) {
                // kısmi cümleyi bırakıp duraklat; devam aynı cümleden yeniden
                _state.value = _state.value.copy(phase = Phase.PAUSED)
                waitForResume()
                if (stopFlag.get()) break
                _state.value = _state.value.copy(phase = Phase.SPEAKING)
                continue // aynı cümleyi yeniden üret
            }

            if (aborted && samples.isNotEmpty()) {
                // atlama: bu cümleyi say, sonrakine geç
            }

            collected.addAll(samples.toList())
            completed++
            idx++
        }

        player?.let { p ->
            // son parçaların çalınmasını bekle
            val tailMs = (samplesRemainingMs(collected, session.sampleRate)) + 250
            val waitJob = scope.launch {
                val t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < tailMs && !stopFlag.get()) {
                    _state.value = _state.value.copy(playbackHeadMs = p.headPositionMs())
                    Thread.sleep(120)
                }
            }
            withContext(Dispatchers.Default) { waitJob.join() }
        }
        player?.stopAndFlush()

        val finalState = _state.value
        if (save && completed > 0 && !stopFlag.get()) {
            val item = library.addAudio(
                title = sentences.firstOrNull()?.take(40) ?: "Seslendirme",
                samples = collected.toFloatArray(),
                sampleRate = session.sampleRate,
                text = processed,
                modelId = modelId
            )
            _state.value = _state.value.copy(savedItemId = item.id)
        }
        _state.value = _state.value.copy(
            phase = Phase.FINISHED,
            sentenceIndex = finalState.sentenceCount,
            currentSentence = ""
        )
        LyLog.i(TAG, "Seslendirme bitti: $completed cümle, ${"%.2f".format(generatedTotalSec)} sn ses")
    }

    private fun samplesRemainingMs(all: List<Float>, sampleRate: Int): Long {
        val writtenMs = player?.headPositionMs() ?: 0L
        val totalMs = all.size * 1000L / sampleRate.coerceAtLeast(1)
        return (totalMs - writtenMs).coerceAtLeast(0)
    }

    private suspend fun waitForResume() {
        while (pauseFlag.get() && !stopFlag.get()) {
            kotlinx.coroutines.delay(120)
        }
    }

    fun pause() {
        if (_state.value.phase == Phase.SPEAKING) pauseFlag.set(true)
    }

    fun resume() {
        if (_state.value.phase == Phase.PAUSED) {
            pauseFlag.set(false)
            player?.resume()
        }
    }

    fun skip() {
        skipFlag.set(true)
    }

    fun stop() {
        stopFlag.set(true); pauseFlag.set(false)
        player?.stopAndFlush()
    }

    fun shutdown() {
        stop()
        player?.release()
        player = null
    }

    private fun ensureService() {
        runCatching { context.startForegroundService(Intent(context, SpeakService::class.java)) }
    }

    companion object { private const val TAG = "SpeakController" }
}
