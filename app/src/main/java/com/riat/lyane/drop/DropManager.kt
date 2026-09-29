package com.riat.lyane.drop

import android.content.Context
import com.riat.lyane.core.LyLog
import com.riat.lyane.library.LibraryRepository
import com.riat.lyane.model.ModelRegistry
import com.riat.lyane.model.ModelStore
import com.riat.lyane.plugin.PluginManager
import com.riat.lyane.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Lyane Drop: aynı Wi-Fi ağına bağlı iki cihaz arasında, yalnızca uygulama
 * içinden geçen veri aktarımı. Modeller, eklentiler, sesler, transkriptler ve
 * kataloglar cihazdan cihaza taşınabilir. Başka hiçbir yol yoktur: bulut yok,
 * sunucu yok, üçüncü taraf uygulama yok.
 */
class DropManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val store: ModelStore,
    private val plugins: PluginManager,
    private val library: LibraryRepository,
    private val registry: ModelRegistry
) : DropServer.ItemProvider {

    enum class ClientPhase { IDLE, DISCOVERING, PIN, BROWSING, TRANSFERRING }

    data class TransferInfo(
        val name: String,
        val received: Long,
        val total: Long,
        val sending: Boolean
    )

    data class HostState(
        val running: Boolean = false,
        val pin: String = "",
        val port: Int = 0,
        val error: String = "",
        val log: List<String> = emptyList()
    )

    data class ClientState(
        val phase: ClientPhase = ClientPhase.IDLE,
        val peers: List<DropDiscovery.Peer> = emptyList(),
        val target: DropDiscovery.Peer? = null,
        val targetName: String = "",
        val token: String = "",
        val items: List<DropServer.DropItem> = emptyList(),
        val transfer: TransferInfo? = null,
        val message: String = "",
        val error: String = ""
    )

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private val _hostState = MutableStateFlow(HostState())
    val hostState: StateFlow<HostState> = _hostState.asStateFlow()

    private val _clientState = MutableStateFlow(ClientState())
    val clientState: StateFlow<ClientState> = _clientState.asStateFlow()

    private val discovery = DropDiscovery(context)
    private val client = DropClient()
    private var server: DropServer? = null
    private var serverPort = 0

    val dropCacheDir: File get() = File(context.cacheDir, "drop-cache").apply { mkdirs() }

    // ── Sunucu tarafı ────────────────────────────────────────────────────

    fun startHosting() {
        scope.launch {
            val deviceName = settings.current().deviceName
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val s = DropServer(context, 0, this@DropManager)
                    s.startServer().getOrThrow()
                    s
                }
            }
            result.onSuccess { s ->
                server = s
                serverPort = s.listeningPort
                _hostState.value = HostState(running = true, port = serverPort, pin = s.currentPin())
                discovery.advertise(serverPort, deviceName)
                pushHostLog("Sunucu açıldı (bağlantı noktası $serverPort)")
            }.onFailure { e ->
                _hostState.value = HostState(error = e.message ?: "Sunucu başlatılamadı")
            }
        }
    }

    fun stopHosting() {
        discovery.stopAdvertise()
        server?.stopServer()
        server = null
        _hostState.value = HostState()
        pushHostLog("Sunucu kapatıldı")
    }

    private fun pushHostLog(msg: String) {
        _hostState.value = _hostState.value.copy(
            log = (listOf(msg) + _hostState.value.log).take(20)
        )
    }

    private fun SettingsRepository.currentValueSafe(): com.riat.lyane.settings.AppSettings =
        kotlinx.coroutines.runBlocking { current() }

    // ItemProvider ───────────────────────────────────────────────────────

    override fun items(): List<DropServer.DropItem> {
        val out = ArrayList<DropServer.DropItem>()
        store.installed.value.forEach { m ->
            out += DropServer.DropItem(
                id = "model:${m.id}", kind = "model", name = m.name,
                sizeBytes = m.sizeBytes, detail = "${m.spec.task} · ${m.spec.languages.joinToString(",")}"
            )
        }
        plugins.plugins.value.filter { it.manifest.type != "pack" }.forEach { p ->
            out += DropServer.DropItem(
                id = "plugin:${p.manifest.id}", kind = "plugin",
                name = p.manifest.name, sizeBytes = p.dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                detail = "v${p.manifest.version}"
            )
        }
        library.items.value.forEach { it ->
            val f = library.fileOf(it)
            out += DropServer.DropItem(
                id = "${if (it.kind == "audio") "audio" else "transcript"}:${it.id}",
                kind = if (it.kind == "audio") "audio" else "transcript",
                name = it.title,
                sizeBytes = f?.length() ?: it.text.length.toLong(),
                detail = if (it.kind == "audio") "ses" else "transkript"
            )
        }
        registry.customCatalogs.value.forEach { name ->
            val f = File(registry.catalogsDir(), name)
            out += DropServer.DropItem(
                id = "catalog:$name", kind = "catalog", name = name,
                sizeBytes = f.length(), detail = "model kataloğu"
            )
        }
        return out
    }

    override fun prepareModelZip(itemId: String): File? {
        val modelId = itemId.removePrefix("model:")
        val m = store.byId(modelId) ?: return null
        val zip = File(dropCacheDir, "model-${m.id}.zip")
        zipDir(store.dirOf(m), zip)
        return zip
    }

    override fun preparePluginZip(itemId: String): File? {
        val id = itemId.removePrefix("plugin:")
        val p = plugins.plugins.value.firstOrNull { it.manifest.id == id } ?: return null
        val zip = File(dropCacheDir, "plugin-${id}.zip")
        zipDir(p.dir, zip)
        return zip
    }

    override fun rawFile(itemId: String): File? {
        return when {
            itemId.startsWith("audio:") -> {
                val item = library.items.value.firstOrNull { it.id == itemId.removePrefix("audio:") } ?: return null
                library.fileOf(item)
            }
            itemId.startsWith("transcript:") -> {
                val item = library.items.value.firstOrNull { it.id == itemId.removePrefix("transcript:") } ?: return null
                val f = File(dropCacheDir, "transcript-${item.id}.json")
                f.writeText(json.encodeToString(LibraryRepository.Item.serializer(), item))
                f
            }
            itemId.startsWith("catalog:") -> File(registry.catalogsDir(), itemId.removePrefix("catalog:"))
            else -> null
        }
    }

    override fun acceptPush(kind: String, name: String, data: File): String {
        return when (kind) {
            "model" -> {
                val staging = File(context.cacheDir, "drop-model-${System.currentTimeMillis()}")
                staging.mkdirs()
                ZipFile(data).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (e.isDirectory) continue
                        val out = File(staging, e.name.replace('\\', '/'))
                        if (!out.canonicalPath.startsWith(staging.canonicalPath)) continue
                        out.parentFile?.mkdirs()
                        zip.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                    }
                }
                val result = store.importFromDir(staging, name)
                staging.deleteRecursively()
                result.fold({ "Model kuruldu: ${it.name}" }, { "Model alınamadı: ${it.message}" })
            }
            "plugin" -> {
                plugins.install(data, "lyane-drop").fold(
                    { "Eklenti kuruldu: ${it.manifest.name}" },
                    { "Eklenti alınamadı: ${it.message}" }
                )
            }
            "audio" -> {
                val tmp = File(context.cacheDir, "drop-audio.wav")
                data.copyTo(tmp, overwrite = true)
                val ok = kotlinx.coroutines.runBlocking { library.importWav(tmp, name) }
                tmp.delete()
                if (ok != null) "Ses alındı: ${ok.title}" else "Ses alınamadı"
            }
            "catalog" -> {
                val content = data.readText()
                registry.importCatalog(name, content).fold(
                    { "$it model eklendi" },
                    { "Katalog alınamadı: ${it.message}" }
                )
            }
            else -> "Bilinmeyen tür"
        }
    }

    override fun deviceName(): String = settings.currentValueSafe().deviceName

    // ── İstemci tarafı ──────────────────────────────────────────────────

    fun startDiscovering() {
        _clientState.value = ClientState(phase = ClientPhase.DISCOVERING)
        discovery.discover(
            onPeer = { peer ->
                val cur = _clientState.value
                if (cur.phase == ClientPhase.DISCOVERING) {
                    if (cur.peers.none { it.host == peer.host && it.port == peer.port }) {
                        _clientState.value = cur.copy(peers = cur.peers + peer)
                    }
                }
            },
            onError = { e ->
                _clientState.value = _clientState.value.copy(error = e)
            }
        )
    }

    fun stopDiscovering() {
        discovery.stopDiscover()
    }

    /** Keşfedilen cihaza bağlan; PIN gerekli. */
    fun beginPair(peer: DropDiscovery.Peer) {
        _clientState.value = _clientState.value.copy(
            phase = ClientPhase.PIN, target = peer, targetName = peer.name, peers = emptyList()
        )
    }

    fun submitPin(pin: String) {
        val peer = _clientState.value.target ?: return
        scope.launch {
            client.pair(peer.host, peer.port, pin)
                .onSuccess { token ->
                    val items = client.list(peer.host, peer.port, token).getOrDefault(emptyList())
                    _clientState.value = _clientState.value.copy(
                        phase = ClientPhase.BROWSING, token = token, items = items, error = ""
                    )
                }
                .onFailure { e ->
                    _clientState.value = _clientState.value.copy(
                        error = e.message ?: "PIN reddedildi"
                    )
                }
        }
    }

    fun refreshList() {
        val peer = _clientState.value.target ?: return
        val token = _clientState.value.token
        scope.launch {
            client.list(peer.host, peer.port, token)
                .onSuccess { items ->
                    _clientState.value = _clientState.value.copy(items = items)
                }
        }
    }

    /** Bir ögeyi karşıdan indir ve kur. */
    fun downloadItem(itemId: String) {
        val peer = _clientState.value.target ?: return
        val token = _clientState.value.token
        val item = _clientState.value.items.firstOrNull { it.id == itemId } ?: return
        scope.launch {
            val dest = File(dropCacheDir, "recv-${item.id.replace(Regex("[^A-Za-z0-9._-]"), "_")}.bin")
            _clientState.value = _clientState.value.copy(
                phase = ClientPhase.TRANSFERRING,
                transfer = TransferInfo(item.name, 0, item.sizeBytes, sending = false)
            )
            client.download(peer.host, peer.port, token, itemId, dest,
                onProgress = { r, t ->
                    _clientState.value = _clientState.value.copy(
                        transfer = TransferInfo(item.name, r, if (t > 0) t else item.sizeBytes, sending = false)
                    )
                }
            ).onSuccess { file ->
                val msg = when (item.kind) {
                    "model", "plugin", "catalog" -> acceptPush(item.kind, item.name, file)
                    "audio" -> acceptPush("audio", item.name, file)
                    "transcript" -> importTranscript(file)
                    else -> "Bilinmeyen tür"
                }
                file.delete()
                _clientState.value = _clientState.value.copy(
                    phase = ClientPhase.BROWSING, transfer = null, message = msg
                )
            }.onFailure { e ->
                dest.delete()
                _clientState.value = _clientState.value.copy(
                    phase = ClientPhase.BROWSING, transfer = null,
                    error = "Aktarım hatası: ${e.message}"
                )
            }
        }
    }

    private fun importTranscript(file: File): String {
        return runCatching {
            val item = json.decodeFromString(LibraryRepository.Item.serializer(), file.readText())
            kotlinx.coroutines.runBlocking {
                library.addTranscript(item.title, item.text, item.segments, item.modelId, item.durationMs)
            }
            "Transkript alındı: ${item.title}"
        }.getOrElse { "Transkript alınamadı: ${it.message}" }
    }

    fun resetClient() {
        stopDiscovering()
        _clientState.value = ClientState()
    }

    fun clearMessage() {
        _clientState.value = _clientState.value.copy(message = "", error = "")
    }

    // ── Zip yardımcıları ────────────────────────────────────────────────

    private fun zipDir(dir: File, dest: File) {
        dest.parentFile?.mkdirs()
        if (dest.isFile) dest.delete()
        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            zos.setLevel(java.util.zip.Deflater.BEST_SPEED)
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                val entryName = f.relativeTo(dir).invariantSeparatorsPath
                zos.putNextEntry(ZipEntry(entryName))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}
