package com.riat.lyane.engine

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.riat.lyane.core.LyLog
import com.riat.lyane.model.InstalledModel
import com.riat.lyane.model.ModelStore
import com.riat.lyane.model.PathResolver
import com.riat.lyane.model.TaskType
import com.riat.lyane.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lyane konuşma motoru yöneticisi.
 *
 * Tek bir yerel çalışma zamanı (sherpa-onnx + ONNX Runtime) hem TTS hem STT
 * modellerini çalıştırır. RAM'i kontrol altında tutmak için aynı anda en fazla
 * bir TTS ve bir ASR oturumu canlı tutulur; model değişince eskisi boşaltılır.
 */
class EngineManager(
    private val context: Context,
    private val store: ModelStore,
    private val settings: SettingsRepository
) {

    // ── Oturum sarmalayıcıları ────────────────────────────────────────────

    /** TTS oturumu: model + yerel OfflineTts örneği. */
    class TtsSession(val model: InstalledModel, val tts: OfflineTts) {
        val sampleRate: Int = tts.sampleRate()
        val numSpeakers: Int = tts.numSpeakers()
        val speakers: List<String> get() = model.spec.speakers
        fun release() = tts.release()
    }

    /** STT oturumu: akış (canlı) veya çevrimdışı. */
    sealed class AsrSession(val model: InstalledModel) {
        abstract fun release()

        /** Whisper / Moonshine / SenseVoice / çevrimdışı transducer. */
        class Offline(
            model: InstalledModel,
            val recognizer: OfflineRecognizer,
            private val rebuildConfig: (String, String, File?, Int) -> OfflineRecognizerConfig
        ) : AsrSession(model) {
            fun decode(samples: FloatArray, sampleRate: Int): String {
                val stream: OfflineStream = recognizer.createStream()
                stream.acceptWaveform(samples, sampleRate)
                recognizer.decode(stream)
                val text = recognizer.getResult(stream).text
                stream.release()
                return text
            }

            /** Whisper dilini/görevini çalışma zamanında değiştirir. */
            fun updateWhisper(language: String, task: String, hotwords: File?, threads: Int) {
                if (model.engine != "whisper") return
                recognizer.setConfig(rebuildConfig(language, task, hotwords, threads))
            }

            override fun release() = recognizer.release()
        }

        /** Canlı (akış) zipformer transducer. */
        class Live(model: InstalledModel, val recognizer: OnlineRecognizer) : AsrSession(model) {
            override fun release() = recognizer.release()
        }
    }

    /** VAD oturumu (silero). */
    class VadSession(private val vad: Vad) {
        fun accept(samples: FloatArray) = vad.acceptWaveform(samples)
        fun hasSpeech(): Boolean = vad.isSpeechDetected()
        fun segments(): List<com.k2fsa.sherpa.onnx.SpeechSegment> {
            val out = ArrayList<com.k2fsa.sherpa.onnx.SpeechSegment>()
            while (!vad.empty()) {
                out += vad.front()
                vad.pop()
            }
            return out
        }
        fun reset() = vad.reset()
        fun flush() = vad.flush()
        fun release() = vad.release()
    }

    // ── Durum ─────────────────────────────────────────────────────────────

    private val mutex = Mutex()
    private var ttsSession: TtsSession? = null
    private var asrSession: AsrSession? = null
    private var vadSession: VadSession? = null

    @Volatile private var cachedAsrThreads: Int = com.riat.lyane.core.Device.asrThreads()
    @Volatile private var cachedHotwordsEnabled: Boolean = true

    /**
     * İstenen TTS modeli için oturum döndürür; gerekirse eski oturumu
     * boşaltıp yeni modeli yükler.
     */
    suspend fun tts(model: InstalledModel): TtsSession = mutex.withLock {
        val cur = ttsSession
        if (cur != null && cur.model.id == model.id) return@withLock cur
        withContext(Dispatchers.Default) {
            cur?.release()
            ttsSession = null
            val threads = settings.current().ttsThreads
            val config = buildTtsConfig(model, threads)
            LyLog.i(TAG, "TTS yükleniyor: ${model.name} ($threads iplik)")
            val t = TtsSession(model, OfflineTts(config = config))
            ttsSession = t
            t
        }
    }

    suspend fun asr(model: InstalledModel): AsrSession = mutex.withLock {
        val cur = asrSession
        if (cur != null && cur.model.id == model.id) return@withLock cur
        withContext(Dispatchers.Default) {
            cur?.release()
            asrSession = null
            val s = settings.current()
            cachedAsrThreads = s.asrThreads
            cachedHotwordsEnabled = s.hotwordsEnabled
            val session = when (model.task) {
                TaskType.ASR_LIVE -> AsrSession.Live(
                    model,
                    OnlineRecognizer(config = buildOnlineConfig(model, s.asrThreads, s.hotwordsEnabled))
                )
                else -> AsrSession.Offline(
                    model,
                    OfflineRecognizer(
                        config = buildOfflineConfig(
                            model = model,
                            whisperLang = whisperLanguage(model),
                            whisperTask = whisperTask(model),
                            hotwords = hotwordsFile(model, s.hotwordsEnabled),
                            threads = s.asrThreads
                        )
                    ),
                    rebuildConfig = { lang, task, hotwords, threads ->
                        buildOfflineConfig(
                            model = model,
                            whisperLang = lang,
                            whisperTask = task,
                            hotwords = hotwords,
                            threads = threads
                        )
                    }
                )
            }
            asrSession = session
            LyLog.i(TAG, "ASR yüklendi: ${model.name}")
            session
        }
    }

    /**
     * Yüklü Whisper oturumunun dilini/görevini anında değiştirir
     * (model yeniden yüklenmez).
     */
    fun updateWhisperOptions(modelId: String, language: String, task: String) {
        val session = asrSession ?: return
        if (session.model.id != modelId) return
        if (session !is AsrSession.Offline) return
        val lang = if (language == "auto") "" else language
        session.updateWhisper(lang, task, hotwordsFile(session.model, cachedHotwordsEnabled), cachedAsrThreads)
    }

    suspend fun vad(): VadSession = mutex.withLock {
        vadSession?.let { return@withLock it }
        withContext(Dispatchers.Default) {
            val model = store.byTask(TaskType.VAD).firstOrNull()
                ?: error("VAD modeli kurulu değil (Modeller → Bileşenler)")
            val dir = store.dirOf(model)
            val onnx = dir.walkTopDown().firstOrNull { it.isFile && it.extension == "onnx" }
                ?: error("VAD model dosyası bulunamadı")
            val cfg = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = onnx.absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 8.0f
                ),
                sampleRate = 16000,
                numThreads = 1,
                provider = "cpu",
                debug = false
            )
            VadSession(Vad(config = cfg)).also { vadSession = it }
        }
    }

    fun releaseAll() {
        runCatching { ttsSession?.release() }
        runCatching { asrSession?.release() }
        runCatching { vadSession?.release() }
        ttsSession = null
        asrSession = null
        vadSession = null
    }

    fun releaseTts() {
        runCatching { ttsSession?.release() }
        ttsSession = null
    }

    // ── Ayar yardımcıları ────────────────────────────────────────────────

    private fun hotwordsFile(model: InstalledModel, enabled: Boolean): File? {
        if (!enabled) return null
        val f = File(store.dirOf(model), "hotwords.txt")
        return if (f.isFile && f.length() > 0) f else null
    }

    private fun whisperLanguage(model: InstalledModel): String {
        val lang = model.spec.options["language"] ?: "auto"
        return if (lang == "auto") "" else lang
    }

    private fun SettingsRepository.currentValueSafe(): com.riat.lyane.settings.AppSettings =
        kotlinx.coroutines.runBlocking { current() }

    private fun whisperTask(model: InstalledModel): String =
        model.spec.options["task"] ?: "transcribe"

    // ── Yapılandırma kurulumu ────────────────────────────────────────────

    private fun resolvedOf(model: InstalledModel): PathResolver.Resolved =
        PathResolver.resolve(store.dirOf(model), model.spec)

    private fun p(r: PathResolver.Resolved, role: String): String =
        r.files[role]?.absolutePath ?: ""

    private fun d(r: PathResolver.Resolved, name: String): String =
        r.dataDirs[name]?.absolutePath ?: ""

    private fun fsts(r: PathResolver.Resolved): String =
        r.ruleFsts.joinToString(",") { it.absolutePath }

    /** Kokoro çok dilli leksikonları: lexicon*.txt hepsi virgülle birleşir. */
    private fun lexicons(r: PathResolver.Resolved): String {
        val files = r.dir.walkTopDown()
            .filter { it.isFile && it.name.startsWith("lexicon") && it.name.endsWith(".txt") }
            .sortedBy { it.name }
            .map { it.absolutePath }
            .toList()
        return files.joinToString(",")
    }

    internal fun buildTtsConfig(model: InstalledModel, threads: Int): OfflineTtsConfig {
        val r = resolvedOf(model)
        val mc = OfflineTtsModelConfig(numThreads = threads, debug = false, provider = "cpu")
        val cfg = OfflineTtsConfig(
            ruleFsts = fsts(r),
            ruleFars = "",
            maxNumSentences = 1,
            silenceScale = 0.2f
        )
        when (model.engine) {
            "vits" -> cfg.model = mc.copy(
                vits = OfflineTtsVitsModelConfig(
                    model = p(r, "model"),
                    lexicon = p(r, "lexicon"),
                    tokens = p(r, "tokens"),
                    dataDir = d(r, "espeak-ng-data")
                )
            )
            "matcha" -> cfg.model = mc.copy(
                matcha = OfflineTtsMatchaModelConfig(
                    acousticModel = p(r, "acousticModel"),
                    vocoder = p(r, "vocoder"),
                    lexicon = p(r, "lexicon"),
                    tokens = p(r, "tokens"),
                    dataDir = d(r, "espeak-ng-data")
                )
            )
            "kokoro" -> cfg.model = mc.copy(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = p(r, "model"),
                    voices = p(r, "voices"),
                    tokens = p(r, "tokens"),
                    dataDir = d(r, "espeak-ng-data"),
                    lexicon = if (p(r, "lexicon").isNotEmpty()) p(r, "lexicon") else lexicons(r)
                )
            )
            "kitten" -> cfg.model = mc.copy(
                kitten = OfflineTtsKittenModelConfig(
                    model = p(r, "model"),
                    voices = p(r, "voices"),
                    tokens = p(r, "tokens"),
                    dataDir = d(r, "espeak-ng-data")
                )
            )
            "supertonic" -> cfg.model = mc.copy(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = p(r, "durationPredictor"),
                    textEncoder = p(r, "textEncoder"),
                    vectorEstimator = p(r, "vectorEstimator"),
                    vocoder = p(r, "vocoder"),
                    ttsJson = p(r, "ttsJson"),
                    unicodeIndexer = p(r, "unicodeIndexer"),
                    voiceStyle = p(r, "voiceStyle")
                )
            )
            else -> error("Desteklenmeyen TTS motoru: ${model.engine}")
        }
        return cfg
    }

    internal fun buildOfflineConfig(
        model: InstalledModel,
        whisperLang: String,
        whisperTask: String,
        hotwords: File?,
        threads: Int
    ): OfflineRecognizerConfig {
        val r = resolvedOf(model)
        val base = OfflineModelConfig(
            tokens = p(r, "tokens"),
            numThreads = threads,
            debug = false,
            provider = "cpu"
        )
        val cfg = OfflineRecognizerConfig()
        when (model.engine) {
            "whisper" -> cfg.modelConfig = base.copy(
                whisper = OfflineWhisperModelConfig(
                    encoder = p(r, "encoder"),
                    decoder = p(r, "decoder"),
                    language = whisperLang,
                    task = whisperTask,
                    tailPaddings = 1000
                ),
                modelType = "whisper"
            )
            "moonshine" -> cfg.modelConfig = base.copy(
                moonshine = OfflineMoonshineModelConfig(
                    preprocessor = p(r, "preprocessor"),
                    encoder = p(r, "encoder"),
                    uncachedDecoder = p(r, "uncachedDecoder"),
                    cachedDecoder = p(r, "cachedDecoder"),
                    mergedDecoder = p(r, "mergedDecoder")
                ),
                modelType = "moonshine"
            )
            "sensevoice" -> cfg.modelConfig = base.copy(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = p(r, "model"),
                    language = model.spec.options["language"] ?: "auto",
                    useInverseTextNormalization = model.spec.options["useItn"] != "false"
                ),
                modelType = "sensevoice"
            )
            "transducer" -> cfg.modelConfig = base.copy(
                transducer = OfflineTransducerModelConfig(
                    encoder = p(r, "encoder"),
                    decoder = p(r, "decoder"),
                    joiner = p(r, "joiner")
                ),
                modelType = "transducer"
            )
            "paraformer" -> cfg.modelConfig = base.copy(
                transducer = OfflineTransducerModelConfig(),
                tokens = p(r, "tokens")
            )
            else -> error("Desteklenmeyen ASR motoru: ${model.engine}")
        }
        hotwords?.let { cfg.hotwordsFile = it.absolutePath }
        return cfg
    }

    internal fun buildOnlineConfig(model: InstalledModel, threads: Int, hotwordsEnabled: Boolean): OnlineRecognizerConfig {
        val r = resolvedOf(model)
        val cfg = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            enableEndpoint = true,
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0.0f),
                rule2 = EndpointRule(true, 1.2f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 20.0f)
            ),
            decodingMethod = "greedy_search"
        )
        cfg.modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = p(r, "encoder"),
                decoder = p(r, "decoder"),
                joiner = p(r, "joiner")
            ),
            tokens = p(r, "tokens"),
            numThreads = threads,
            debug = false,
            provider = "cpu"
        )
        if (hotwordsEnabled) {
            val f = File(store.dirOf(model), "hotwords.txt")
            if (f.isFile && f.length() > 0) cfg.hotwordsFile = f.absolutePath
        }
        return cfg
    }

    companion object {
        private const val TAG = "EngineManager"
    }
}
