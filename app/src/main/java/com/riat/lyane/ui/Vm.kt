package com.riat.lyane.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.riat.lyane.LyaneApp
import com.riat.lyane.core.LyLog
import com.riat.lyane.core.TextSplitter
import com.riat.lyane.engine.AudioDecoder
import com.riat.lyane.engine.EngineManager
import com.riat.lyane.engine.PcmPlayer
import com.riat.lyane.engine.WavIo
import com.riat.lyane.library.LibraryRepository
import com.riat.lyane.listen.ListenController
import com.riat.lyane.model.InstalledModel
import com.riat.lyane.model.ModelDownloadManager
import com.riat.lyane.model.ModelRegistry
import com.riat.lyane.model.ModelSpec
import com.riat.lyane.model.ModelStore
import com.riat.lyane.model.TaskType
import com.riat.lyane.plugin.PluginManager
import com.riat.lyane.settings.AppSettings
import com.riat.lyane.settings.SettingsRepository
import com.riat.lyane.speak.SpeakController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Context.app: LyaneApp get() = applicationContext as LyaneApp

/** Modeller ekranı: katalog + kurulu + indirme durumları. */
class ModelsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = getApplication<Application>().app.container

    val catalog: StateFlow<List<ModelSpec>> = c.registry.catalog
    val installed: StateFlow<List<InstalledModel>> = c.store.installed
    val downloadStates: StateFlow<Map<String, ModelDownloadManager.State>> = c.downloadManager.states
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** İçe aktarma durumu (model arşivi ya da katalog JSON). */
    val importStatus = MutableStateFlow("")

    fun download(spec: ModelSpec) = c.downloadManager.enqueue(spec)
    fun pause(id: String) = c.downloadManager.pause(id)
    fun resume(id: String) = c.downloadManager.resume(id)
    fun cancel(id: String) = c.downloadManager.cancel(id)
    fun delete(id: String) = c.store.delete(id)
    fun setDefaultTts(id: String) = viewModelScope.launch { c.settings.setDefaultTts(id) }
    fun setDefaultAsr(id: String) = viewModelScope.launch { c.settings.setDefaultAsr(id) }
    fun store(): ModelStore = c.store

    /** Harici model arşivi içe aktarma (.tar.bz2/.zip/.onnx). */
    fun importArchive(uri: Uri) {
        importStatus.value = "Kopyalanıyor…"
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val resolver = getApplication<Application>().contentResolver
                val name = queryDisplayName(getApplication(), uri) ?: "model"
                val staging = File(c.store.cacheDir, "import-${System.currentTimeMillis()}")
                staging.mkdirs()
                val local = File(staging, name)
                resolver.openInputStream(uri)?.use { input ->
                    local.outputStream().use { input.copyTo(it) }
                } ?: error("Dosya okunamadı")
                importStatus.value = "Arşiv açılıyor…"
                val dir = File(staging, "extracted")
                com.riat.lyane.model.Archive.extract(local, dir)
                local.delete()
                importStatus.value = "Model algılanıyor…"
                val model = c.store.importFromDir(dir, name.substringBeforeLast('.'))
                    .getOrThrow()
                importStatus.value = "Tamam: ${model.name}"
                LyLog.i("ModelsVM", "Model içe aktarıldı: ${model.name}")
            }.onFailure {
                importStatus.value = "Hata: ${it.message}"
                LyLog.w("ModelsVM", "İçe aktarma hatası", it)
            }
        }
    }

    /** Özel katalog JSON içe aktarma. */
    fun importCatalog(uri: Uri) {
        importStatus.value = "Okunuyor…"
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() } ?: error("Dosya okunamadı")
                val name = queryDisplayName(getApplication(), uri) ?: "katalog.json"
                val count = c.registry.importCatalog(name, text).getOrThrow()
                importStatus.value = "Katalog eklendi: $count model"
            }.onFailure { importStatus.value = "Hata: ${it.message}" }
        }
    }
}

fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
}.getOrNull()

/** Konuş (TTS) ekranı. */
class SpeakViewModel(app: Application) : AndroidViewModel(app) {
    private val c = getApplication<Application>().app.container

    val ttsModels: StateFlow<List<InstalledModel>> = c.store.installed
    val state: StateFlow<SpeakController.State> = c.speakController.state
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun start(text: String, modelId: String, sid: Int, speed: Float, save: Boolean) =
        c.speakController.start(text, modelId, sid, speed, save)

    fun pause() = c.speakController.pause()
    fun resume() = c.speakController.resume()
    fun stop() = c.speakController.stop()
    fun skip() = c.speakController.skip()
}

/** Dinle (canlı STT) ekranı. */
class ListenViewModel(app: Application) : AndroidViewModel(app) {
    private val c = getApplication<Application>().app.container

    val asrModels: StateFlow<List<InstalledModel>> = c.store.installed
    val state: StateFlow<ListenController.State> = c.listenController.state
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun modelsForListening(): List<InstalledModel> =
        c.store.installed.value.filter { it.task == TaskType.ASR_LIVE || it.task == TaskType.ASR }

    fun start(modelId: String) = c.listenController.start(modelId)
    fun pause() = c.listenController.pause()
    fun resume() = c.listenController.resume()
    fun stop() = c.listenController.stop()
    fun clear() = c.listenController.clearText()

    /** Whisper dil seçimi: oturum açıksa anında uygulanır. */
    fun setWhisperLanguage(lang: String, task: String) {
        val st = state.value
        if (st.modelId.isBlank()) return
        c.engines.updateWhisperOptions(st.modelId, lang, task)
    }
}

/** Dosya çeviriyazma ekranı. */
class FileTranscribeViewModel(app: Application) : AndroidViewModel(app) {
    private val c = getApplication<Application>().app.container

    enum class Phase { IDLE, PICKED, DECODING, TRANSCRIBING, DONE, FAILED }

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val uri: Uri? = null,
        val fileName: String = "",
        val modelId: String = "",
        val language: String = "auto",
        val task: String = "transcribe",
        val useVad: Boolean = true,
        val progress: Int = 0,
        val progressMax: Int = 0,
        val segments: List<LibraryRepository.Segment> = emptyList(),
        val text: String = "",
        val error: String = "",
        val savedItemId: String = ""
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val asrModels: StateFlow<List<InstalledModel>> = c.store.installed

    fun pick(uri: Uri?, name: String?) {
        _ui.value = _ui.value.copy(
            phase = if (uri != null) Phase.PICKED else Phase.IDLE,
            uri = uri, fileName = name ?: "", segments = emptyList(), text = "", savedItemId = ""
        )
    }

    fun setModel(id: String) { _ui.value = _ui.value.copy(modelId = id) }
    fun setLanguage(lang: String) { _ui.value = _ui.value.copy(language = lang) }
    fun setTask(task: String) { _ui.value = _ui.value.copy(task = task) }
    fun setVad(v: Boolean) { _ui.value = _ui.value.copy(useVad = v) }

    fun run() {
        val st = _ui.value
        val uri = st.uri ?: return
        val modelId = st.modelId.ifBlank {
            c.store.installed.value.firstOrNull { it.task == TaskType.ASR }?.id ?: run {
                _ui.value = st.copy(phase = Phase.FAILED, error = "Önce bir STT modeli indirin")
                return
            }
        }
        _ui.value = st.copy(phase = Phase.DECODING, modelId = modelId, error = "", progress = 0)
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                val decoded = AudioDecoder.decode(getApplication(), uri)
                _ui.value = _ui.value.copy(phase = Phase.TRANSCRIBING, progressMax = 1, progress = 0)

                val session = c.engines.asr(c.store.byId(modelId)!!)
                if (session is EngineManager.AsrSession.Offline) {
                    session.updateWhisper(if (st.language == "auto") "" else st.language, st.task, null, com.riat.lyane.core.Device.asrThreads())
                }

                val segments = ArrayList<LibraryRepository.Segment>()
                if (session is EngineManager.AsrSession.Offline) {
                    if (st.useVad) {
                        val vadReady = c.downloadManager.ensureVad()
                        if (!vadReady) {
                            _ui.value = _ui.value.copy(phase = Phase.FAILED, error = "VAD bileşeni indirilemedi (internet?)")
                            return@launch
                        }
                        val vad = c.engines.vad()
                        _ui.value = _ui.value.copy(progressMax = 100, progress = 0)
                        // tüm sesi VAD'den geçir; parçaları sırayla çöz
                        val all = decoded.samples
                        var cursor = 0
                        vad.reset()
                        val window = 1600
                        var processed = 0
                        while (cursor < all.size) {
                            val end = minOf(cursor + window, all.size)
                            vad.accept(all.copyOfRange(cursor, end))
                            for (seg in vad.segments()) {
                                val startSec = processed + seg.start / 16000.0
                                val segSec = seg.samples.size / 16000.0
                                val text = session.decode(seg.samples, 16000)
                                if (text.isNotBlank()) {
                                    segments += LibraryRepository.Segment(startSec, startSec + segSec, text)
                                }
                            }
                            processed += (end - cursor)
                            cursor = end
                            _ui.value = _ui.value.copy(progress = (processed * 100 / all.size).toInt())
                        }
                        vad.flush()
                        for (seg in vad.segments()) {
                            val startSec = processed + seg.start / 16000.0
                            val segSec = seg.samples.size / 16000.0
                            val text = session.decode(seg.samples, 16000)
                            if (text.isNotBlank()) segments += LibraryRepository.Segment(startSec, startSec + segSec, text)
                        }
                    } else {
                        val text = session.decode(decoded.samples, decoded.sampleRate)
                        if (text.isNotBlank()) {
                            segments += LibraryRepository.Segment(0.0, decoded.durationSec, text)
                        }
                    }
                }
                val fullText = segments.joinToString("\n") { it.text.trim() }
                val item = c.library.addTranscript(
                    title = st.fileName.ifBlank { "Çeviriyazı" },
                    text = fullText,
                    segments = segments,
                    modelId = modelId,
                    durationMs = (decoded.durationSec * 1000).toLong()
                )
                _ui.value = _ui.value.copy(
                    phase = Phase.DONE, segments = segments, text = fullText, progress = 100, savedItemId = item.id
                )
            }.onFailure {
                LyLog.e("FileVM", "Çeviriyazma hatası", it)
                _ui.value = _ui.value.copy(phase = Phase.FAILED, error = it.message ?: "Bilinmeyen hata")
            }
        }
    }
}

/** Kütüphane ekranı. */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val c = getApplication<Application>().app.container

    val items: StateFlow<List<LibraryRepository.Item>> = c.library.items

    private var player: PcmPlayer? = null
    val playingId = MutableStateFlow("")

    fun delete(id: String) = c.library.delete(id)
    fun rename(id: String, title: String) = c.library.rename(id, title)

    fun togglePlay(item: LibraryRepository.Item) {
        viewModelScope.launch(Dispatchers.IO) {
            val f = c.library.fileOf(item) ?: return@launch
            runCatching {
                if (playingId.value == item.id) {
                    player?.stopAndFlush()
                    player?.release()
                    player = null
                    playingId.value = ""
                } else {
                    player?.release()
                    val wav = WavIo.read(f)
                    player = PcmPlayer(wav.sampleRate).also { p ->
                        p.start()
                        Thread {
                            p.write(wav.samples)
                            if (playingId.value == item.id) {
                                playingId.value = ""
                            }
                        }.start()
                    }
                    playingId.value = item.id
                }
            }
        }
    }

    fun fileOf(item: LibraryRepository.Item): File? = c.library.fileOf(item)

    fun importWav(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val name = queryDisplayName(getApplication(), uri) ?: "import.wav"
                val tmp = File(c.library.audioDir, "import-tmp.wav")
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                c.library.importWav(tmp, name.substringBeforeLast('.'))
                tmp.delete()
            }
        }
    }

    override fun onCleared() {
        player?.release()
        player = null
    }
}

/** Eklentiler ekranı. */
class PluginsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = getApplication<Application>().app.container

    val plugins: StateFlow<List<PluginManager.InstalledPlugin>> = c.plugins.plugins
    val logs: StateFlow<Map<String, List<String>>> = c.plugins.logs
    val installStatus = MutableStateFlow("")

    fun setEnabled(id: String, enabled: Boolean) = c.plugins.setEnabled(id, enabled)
    fun uninstall(id: String) = c.plugins.uninstall(id)

    fun installFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = File(getApplication<Application>().cacheDir, "install.lyplugin")
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                } ?: error("Dosya okunamadı")
                val p = c.plugins.install(tmp, "dosya").getOrThrow()
                installStatus.value = "Kuruldu: ${p.manifest.name}"
                tmp.delete()
            }.onFailure { installStatus.value = "Hata: ${it.message}" }
        }
    }

    fun installSample() {
        viewModelScope.launch(Dispatchers.IO) {
            c.plugins.installBundledSample().fold(
                { installStatus.value = "Örnek eklenti kuruldu: ${it.manifest.name}" },
                { installStatus.value = "Hata: ${it.message}" }
            )
        }
    }

    fun grant(pluginId: String, permission: String, grant: Boolean) =
        c.plugins.grantPermission(pluginId, permission, grant)

    suspend fun isGranted(pluginId: String, permission: String): Boolean =
        c.plugins.isPermissionGranted(pluginId, permission)
}

/** Ayarlar ekranı. */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = getApplication<Application>().app.container

    val settings: StateFlow<AppSettings> =
        c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun setTheme(v: String) = viewModelScope.launch { c.settings.setTheme(v) }
    fun setDeviceName(v: String) = viewModelScope.launch { c.settings.setDeviceName(v) }
    fun setTtsThreads(v: Int) = viewModelScope.launch { c.settings.setTtsThreads(v) }
    fun setAsrThreads(v: Int) = viewModelScope.launch { c.settings.setAsrThreads(v) }
    fun setWifiOnly(v: Boolean) = viewModelScope.launch { c.settings.setWifiOnly(v) }
    fun setKeepScreenOn(v: Boolean) = viewModelScope.launch { c.settings.setKeepScreenOn(v) }
    fun setHotwords(v: Boolean) = viewModelScope.launch { c.settings.setHotwords(v) }

    fun storageStats(): Triple<Long, Long, Long> = Triple(
        c.store.usedSpaceBytes(), c.library.usedBytes(), c.store.freeSpaceBytes()
    )

    fun deleteAllModels(onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            c.store.installed.value.map { it.id }.forEach { c.store.delete(it) }
            onDone()
        }
    }
}

/** Ana sayfa. */
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val c = getApplication<Application>().app.container

    val installed: StateFlow<List<InstalledModel>> = c.store.installed
    val downloads: StateFlow<Map<String, ModelDownloadManager.State>> = c.downloadManager.states
    val speakState: StateFlow<SpeakController.State> = c.speakController.state
    val listenState: StateFlow<ListenController.State> = c.listenController.state
    val libraryItems: StateFlow<List<LibraryRepository.Item>> = c.library.items
    val plugins: StateFlow<List<PluginManager.InstalledPlugin>> = c.plugins.plugins
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val deviceSummary: String = com.riat.lyane.core.Device.summary()

    fun store(): ModelStore = c.store
    fun registry(): ModelRegistry = c.registry
    fun launch(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.Default) { block() }
    }
    fun totalDownloadedBytes(): Long =
        downloads.value.values.sumOf { (it as? ModelDownloadManager.State.Downloading)?.downloaded ?: 0L }
}
