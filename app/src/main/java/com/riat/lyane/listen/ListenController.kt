package com.riat.lyane.listen

import android.content.Context
import android.content.Intent
import com.k2fsa.sherpa.onnx.OnlineStream
import com.riat.lyane.core.LyLog
import com.riat.lyane.engine.EngineManager
import com.riat.lyane.engine.MicSource
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

/**
 * Canlı dinleme (STT) denetleyicisi.
 *
 * İki mod:
 *  - Akış modeli (zipformer): gerçek zamanlı kısmi sonuçlar + uç nokta algılama
 *  - Çevrimdışı model (whisper/moonshine/sensevoice): silero VAD ile konuşma
 *    parçalarına bölüp her parçayı ayrı çeviriyazma
 *
 * Mikrofon verisi ASLA cihaz dışına çıkmaz; tanıma tamamen cihazda yapılır.
 */
class ListenController(
    private val context: Context,
    private val engines: EngineManager,
    private val store: ModelStore,
    private val plugins: PluginManager,
    private val library: LibraryRepository,
    private val downloadManager: com.riat.lyane.model.ModelDownloadManager
) {

    enum class Phase { IDLE, PREPARING, LISTENING, PAUSED, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val modelId: String = "",
        val modelLive: Boolean = false,
        val finalText: String = "",
        val partial: String = "",
        val level: Float = 0f,
        val speechActive: Boolean = false,
        val segmentCount: Int = 0,
        val startedAtMs: Long = 0,
        val error: String = "",
        val savedItemId: String = ""
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var mic: MicSource? = null
    private val paused = java.util.concurrent.atomic.AtomicBoolean(false)
    private val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start(modelId: String) {
        if (_state.value.phase == Phase.LISTENING || _state.value.phase == Phase.PREPARING) return
        stopFlag.set(false); paused.set(false)
        _state.value = State(phase = Phase.PREPARING, modelId = modelId, startedAtMs = System.currentTimeMillis())
        ensureService()
        job = CoroutineScope(Dispatchers.IO + Job()).launch {
            runCatching { listenInternal(modelId) }
                .onFailure { e ->
                    LyLog.e(TAG, "Dinleme hatası", e)
                    mic?.stop(); mic = null
                    _state.value = _state.value.copy(phase = Phase.FAILED, error = e.message ?: "Bilinmeyen hata")
                }
        }
    }

    private suspend fun listenInternal(modelId: String) {
        val model: InstalledModel = store.byId(modelId) ?: error("Model bulunamadı: $modelId")
        val session = engines.asr(model)
        if (session is EngineManager.AsrSession.Offline) {
            // VAD bileşeni gerekli; eksikse sessizce indir (~0.6 MB)
            if (store.byTask(com.riat.lyane.model.TaskType.VAD) == null) {
                _state.value = _state.value.copy(error = "VAD bileşeni indiriliyor…")
                downloadManager.ensureVad()
                _state.value = _state.value.copy(error = "")
            }
        }
        mic = MicSource.create() ?: error("Mikrofon başlatılamadı (izin verildi mi?)")
        _state.value = _state.value.copy(
            phase = Phase.LISTENING,
            modelLive = session is EngineManager.AsrSession.Live
        )

        when (session) {
            is EngineManager.AsrSession.Live -> liveLoop(session)
            is EngineManager.AsrSession.Offline -> offlineLoop(session)
        }
    }

    /** Akış modeli: kısmi sonuç + uç nokta. */
    private suspend fun liveLoop(session: EngineManager.AsrSession.Live) {
        val recognizer = session.recognizer
        val stream: OnlineStream = recognizer.createStream()
        while (!stopFlag.get()) {
            if (paused.get()) {
                Thread.sleep(100)
                continue
            }
            val (chunk, level) = mic!!.readChunk()
            if (chunk.isEmpty()) continue
            stream.acceptWaveform(chunk, mic!!.sampleRate)
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            val text = recognizer.getResult(stream).text
            if (text.isNotBlank() && text != _state.value.partial) {
                _state.value = _state.value.copy(partial = text, level = level, speechActive = true)
            } else {
                _state.value = _state.value.copy(level = level, speechActive = text.isNotBlank())
            }
            if (recognizer.isEndpoint(stream)) {
                if (text.isNotBlank()) {
                    appendFinal(text)
                }
                recognizer.reset(stream)
                _state.value = _state.value.copy(partial = "")
            }
        }
        runCatching {
            stream.acceptWaveform(FloatArray(0), mic!!.sampleRate)
            stream.inputFinished()
            stream.release()
        }
        finishLoop()
    }

    /** Çevrimdışı model: VAD parçaları. */
    private suspend fun offlineLoop(session: EngineManager.AsrSession.Offline) {
        val vad = runCatching { engines.vad() }.getOrNull()
        if (vad == null) {
            LyLog.w(TAG, "VAD yok; sabit dilimleme kullanılacak")
        }
        var buffer = FloatArray(0)
        while (!stopFlag.get()) {
            if (paused.get()) {
                Thread.sleep(100)
                continue
            }
            val (chunk, level) = mic!!.readChunk()
            if (chunk.isEmpty()) continue
            if (vad == null) {
                buffer += chunk
                if (buffer.size >= 16000 * 5) {
                    decodeAndAppend(session, buffer)
                    buffer = FloatArray(0)
                }
                _state.value = _state.value.copy(level = level)
            } else {
                vad.accept(chunk)
                val segs = vad.segments()
                if (segs.isNotEmpty()) {
                    _state.value = _state.value.copy(level = level, speechActive = true)
                    for (seg in segs) decodeAndAppend(session, seg.samples)
                } else {
                    _state.value = _state.value.copy(level = level, speechActive = vad.hasSpeech())
                }
            }
        }
        // kalan tampon
        if (buffer.isNotEmpty()) decodeAndAppend(session, buffer)
        runCatching { vad?.reset() }
        finishLoop()
    }

    private fun decodeAndAppend(session: EngineManager.AsrSession.Offline, samples: FloatArray) {
        if (samples.size < 1600) return // 100 ms'den kısa parçaları atla
        val text = runCatching { session.decode(samples, 16000) }.getOrDefault("")
        if (text.isNotBlank()) appendFinal(text)
    }

    private fun appendFinal(raw: String) {
        val processed = plugins.processAsrText(raw, _state.value.modelId)
        val cur = _state.value
        _state.value = cur.copy(
            finalText = if (cur.finalText.isBlank()) processed else cur.finalText + "\n" + processed,
            partial = "",
            segmentCount = cur.segmentCount + 1
        )
    }

    private fun finishLoop() {
        mic?.stop(); mic = null
        val st = _state.value
        _state.value = st.copy(phase = Phase.IDLE, level = 0f, speechActive = false)
        // oturumu otomatik kaydet
        if (st.finalText.isNotBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val item = library.addTranscript(
                        title = "Dinleme · " + java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale("tr")).format(java.util.Date()),
                        text = st.finalText,
                        segments = emptyList(),
                        modelId = st.modelId,
                        durationMs = System.currentTimeMillis() - st.startedAtMs
                    )
                    _state.value = _state.value.copy(savedItemId = item.id)
                }
            }
        }
    }

    fun pause() { paused.set(true) }
    fun resume() { paused.set(false) }

    fun stop() {
        stopFlag.set(true)
    }

    fun clearText() {
        _state.value = _state.value.copy(finalText = "", partial = "", segmentCount = 0, savedItemId = "")
    }

    private fun ensureService() {
        runCatching { context.startForegroundService(Intent(context, ListenService::class.java)) }
    }

    companion object { private const val TAG = "ListenController" }
}
