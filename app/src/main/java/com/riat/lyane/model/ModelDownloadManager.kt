package com.riat.lyane.model

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.riat.lyane.core.LyLog
import com.riat.lyane.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Model indirme kuyruğu: indir → doğrula → arşivi aç → kaydet.
 *
 * İndirmeler kesintiye uğrarsa `.part` dosyası diskte kalır ve kullanıcı
 * "Devam Et" diyerek kaldığı bayttan sürdürebilir. Tüm akış bu sınıfta
 * yönetilir; `ModelDownloadService` yalnızca bildirimi gösterir.
 */
class ModelDownloadManager(
    private val context: Context,
    private val registry: ModelRegistry,
    private val store: ModelStore,
    private val settings: SettingsRepository
) {
    sealed class State {
        data object Queued : State()
        data class Downloading(val downloaded: Long, val total: Long, val speed: Double) : State()
        data class Extracting(val filesDone: Int) : State()
        data object Verifying : State()
        data class Completed(val dirName: String) : State()
        data class Paused(val downloaded: Long, val total: Long) : State()
        data class Failed(val error: String) : State()
        data object Cancelled : State()
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val queue = Channel<ModelSpec>(Channel.UNLIMITED)

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    @Volatile private var pausedIds = mutableSetOf<String>()
    private val inFlight = mutableSetOf<String>()

    private val downloader = HttpDownloader()

    init {
        scope.launch { workerLoop() }
    }

    fun stateOf(id: String): State? = _states.value[id]

    fun partFile(id: String): File = File(store.cacheDir, "$id.part")

    fun enqueue(spec: ModelSpec) {
        val existing = _states.value[spec.id]
        val busy = existing is State.Queued || existing is State.Downloading ||
            existing is State.Extracting || existing is State.Verifying
        if (busy || store.byId(spec.id) != null) return
        update(spec.id) { State.Queued }
        queue.trySend(spec)
        ensureService()
    }

    fun resume(id: String) {
        val spec = registry.byId(id) ?: return
        pausedIds.remove(id)
        val st = _states.value[id]
        if (st is State.Failed || st is State.Paused || st is State.Cancelled) {
            update(id) { State.Queued }
            queue.trySend(spec)
            ensureService()
        }
    }

    fun pause(id: String) {
        pausedIds.add(id)
    }

    fun cancel(id: String) {
        pausedIds.add(id)
        partFile(id).delete()
        File(store.cacheDir, id).deleteRecursively()
        update(id) { State.Cancelled }
    }

    private fun update(id: String, f: (State?) -> State) {
        _states.value = _states.value.toMutableMap().apply { put(id, f(get(id))) }
    }

    private suspend fun workerLoop() {
        for (spec in queue) {
            if (pausedIds.remove(spec.id) && _states.value[spec.id] is State.Cancelled) continue
            try {
                process(spec)
            } catch (pe: HttpDownloader.PauseException) {
                val part = partFile(spec.id)
                update(spec.id) {
                    State.Paused(part.length(), (it as? State.Downloading)?.total ?: -1L)
                }
            } catch (e: Exception) {
                LyLog.e(TAG, "Model kurulamadı: ${spec.id}", e)
                update(spec.id) { State.Failed(e.message ?: "Bilinmeyen hata") }
            }
        }
    }

    private suspend fun process(spec: ModelSpec) {
        // zaten kurulu mu?
        if (store.byId(spec.id) != null) {
            update(spec.id) { State.Completed(store.byId(spec.id)!!.dirName) }
            return
        }
        // Wi-Fi kısıtı
        val s = settings.current()
        if (s.wifiOnlyDownloads && !isOnWifi()) {
            update(spec.id) { State.Failed("Ayarda yalnız Wi-Fi indirme açık; Wi-Fi ağına bağlanın veya ayarı değiştirin") }
            return
        }

        val part = partFile(spec.id)
        if (spec.format != "file" || spec.url.isNotBlank()) {
            update(spec.id) { State.Downloading(part.length(), spec.sizeBytes, 0.0) }
            if (spec.url.isBlank()) {
                update(spec.id) { State.Failed("Model adresi (url) tanımlı değil") }
                return
            }
            downloader.download(
                url = spec.url,
                dest = part,
                onProgress = { p ->
                    update(spec.id) { State.Downloading(p.downloaded, if (p.total > 0) p.total else spec.sizeBytes, p.bytesPerSec) }
                },
                isCancelled = { spec.id in pausedIds }
            )
        }

        update(spec.id) { State.Extracting(0) }
        val staging = File(store.cacheDir, spec.id).apply { deleteRecursively(); mkdirs() }
        val isArchive = spec.format != "file"
        val count = if (isArchive) {
            Archive.extract(part, staging) { n -> update(spec.id) { State.Extracting(n) } }
        } else {
            val target = File(staging, part.name)
            part.copyTo(target, overwrite = true)
            1
        }
        if (count == 0) {
            staging.deleteRecursively()
            update(spec.id) { State.Failed("Arşiv boş görünüyor") }
            return
        }

        update(spec.id) { State.Verifying }
        val resolved = PathResolver.resolve(staging, spec)
        val missing = PathResolver.missingCritical(spec, resolved)
        if (missing.isNotEmpty()) {
            staging.deleteRecursively()
            update(spec.id) {
                State.Failed("Eksik dosyalar: ${missing.joinToString(", ")} — model yapısı tanımla uyuşmuyor")
            }
            return
        }

        // hedefe taşı ve kaydet
        val dirName = store.uniqueDirName(spec.id)
        val target = File(store.modelsDir, dirName)
        staging.renameTo(target)
        part.delete()
        store.register(spec, dirName, "download")
        update(spec.id) { State.Completed(dirName) }
        LyLog.i(TAG, "Model kuruldu: ${spec.name} ($count dosya)")
    }

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun ensureService() {
        runCatching {
            context.startForegroundService(Intent(context, ModelDownloadService::class.java))
        }
    }

    /**
     * Silero VAD kurulu değilse katalogdan indirir (~0.6 MB) ve kurulmasını
     * bekler. Çevrimdışı modellerle canlı dinleme ve dosya bölme bunu ister.
     */
    suspend fun ensureVad(): Boolean {
        if (store.byTask(TaskType.VAD) != null) return true
        val spec = registry.catalog.value.firstOrNull { it.task == TaskType.VAD } ?: return false
        enqueue(spec)
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            if (store.byTask(TaskType.VAD) != null) return true
            when (states.value[spec.id]) {
                is State.Failed, is State.Cancelled -> return false
                else -> Unit
            }
            kotlinx.coroutines.delay(1000)
        }
        return false
    }

    fun hasActiveWork(): Boolean =
        _states.value.values.any {
            it is State.Queued || it is State.Downloading || it is State.Extracting || it is State.Verifying
        }

    fun hasDownloadablePart(id: String): Boolean = partFile(id).length() > 0

    companion object {
        private const val TAG = "ModelDownload"
        private var id: String = "" // kullanılmıyor; koda denk gelinmemeli

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, ModelDownloadService::class.java))
            }
        }
    }
}
