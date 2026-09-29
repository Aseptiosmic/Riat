package com.riat.lyane.drop

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.riat.lyane.core.LyLog
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.UUID

/**
 * Lyane Drop sunucusu: aynı Wi-Fi ağındaki bir Lyane cihazına veri gönderir.
 *
 * Güvenlik / gizlilik kuralları:
 *  - Yalnızca eşleştirme (PIN) sonrası erişim
 *  - İstemcinin IP'si sunucunun Wi-Fi alt ağı içinde olmalı
 *  - Wi-Fi bağlı değilse sunucu hiç başlamaz
 *  - Aktarım HTTP üzerinden yerel ağda; hiçbir veri dışarı çıkmaz
 */
class DropServer(
    private val context: Context,
    private val port: Int,
    private val provider: ItemProvider
) : NanoHTTPD(port) {

    interface ItemProvider {
        /** Paylaşılabilir ögelerin listesi. */
        fun items(): List<DropItem>
        /** Bir modelin zip'ini önceden hazırlar; dosyayı döndürür. */
        fun prepareModelZip(itemId: String): File?
        /** Eklentinin .lyplugin zip'ini hazırlar. */
        fun preparePluginZip(itemId: String): File?
        /** Ses/transkript dosyası. */
        fun rawFile(itemId: String): File?
        /** Gelen yükü işler (model/plugin/audio). */
        fun acceptPush(kind: String, name: String, data: File): String
        fun deviceName(): String
    }

    @Serializable
    data class DropItem(
        val id: String,
        val kind: String,   // model | plugin | audio | transcript | catalog
        val name: String,
        val sizeBytes: Long,
        val detail: String = ""
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    private var token: String = ""
    private var pin: String = ""
    private var pinCreatedAt: Long = 0
    private var pinAttempts: Int = 0
    private var pairedAtMs: Long = 0
    private val uploadDirs = HashMap<String, File>()

    fun currentPin(): String = pin

    // ── Yaşam döngüsü ────────────────────────────────────────────────────

    fun startServer(): Result<String> = runCatching {
        if (!isWifiLike()) error("Wi-Fi ağına bağlı değilsiniz; Lyane Drop yalnızca aynı Wi-Fi ağındaki cihazlarla çalışır")
        localWifiAddress() ?: error("Yerel ağ adresi alınamadı")
        regeneratePin()
        start(SOCKET_READ_TIMEOUT, true)
        LyLog.i(TAG, "Drop sunucusu başladı: 0.0.0.0:$port")
        "0.0.0.0:$port"
    }

    fun stopServer() {
        closeAllConnections()
        stop()
        uploadDirs.values.forEach { it.deleteRecursively() }
        uploadDirs.clear()
        token = ""
        pairedAtMs = 0
        LyLog.i(TAG, "Drop sunucusu durdu")
    }

    private fun regeneratePin() {
        pin = String.format("%06d", random.nextInt(1_000_000))
        pinCreatedAt = System.currentTimeMillis()
        pinAttempts = 0
    }

    private fun pinValid(): Boolean {
        // PIN 10 dakika geçerli
        if (System.currentTimeMillis() - pinCreatedAt > 10 * 60 * 1000) return false
        return true
    }

    // ── Ağ denetimleri ───────────────────────────────────────────────────

    private fun isWifiLike(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun wifiLikeAddresses(): List<Pair<java.net.InetAddress, Int>> {
        val out = ArrayList<Pair<java.net.InetAddress, Int>>()
        val all = NetworkInterface.getNetworkInterfaces() ?: return out
        for (iface in all) {
            if (!iface.isUp || iface.isLoopback || iface.isPointToPoint) continue
            val n = iface.name
            val wifiLike = n.contains("wlan") || n.contains("wifi") || n.contains("ap") || n.contains("swlan")
            if (!wifiLike) continue
            for (addr in iface.interfaceAddresses) {
                val a = addr.address
                if (a is Inet4Address && !a.isLoopbackAddress) {
                    out += a to addr.networkPrefixLength.toInt()
                }
            }
        }
        return out
    }

    private fun localWifiAddress(): java.net.InetAddress? = wifiLikeAddresses().firstOrNull()?.first

    /** İstemci IP'si aynı Wi-Fi alt ağında mı? */
    private fun sameSubnet(remoteIp: String?): Boolean {
        if (remoteIp == null) return false
        val remote = runCatching { java.net.InetAddress.getByName(remoteIp) }.getOrNull() ?: return false
        if (remote !is Inet4Address) return false
        for ((addr, prefix) in wifiLikeAddresses()) {
            val mask = if (prefix in 1..32) (-1L shl (32 - prefix)) and 0xFFFFFFFFL else 0xFFFFFF00L
            val a = ipToLong(addr)
            val b = ipToLong(remote)
            if ((a and mask) == (b and mask)) return true
        }
        return false
    }

    private fun ipToLong(a: java.net.InetAddress): Long {
        val b = a.address
        return ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
            ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)
    }

    // ── HTTP ─────────────────────────────────────────────────────────────

    override fun serve(session: IHTTPSession): Response {
        return try {
            val remote = session.remoteIpAddress
            if (!sameSubnet(remote)) {
                LyLog.w(TAG, "Farklı ağdan bağlantı reddedildi: $remote")
                return forbidden("Yalnızca aynı Wi-Fi ağındaki cihazlar bağlanabilir")
            }
            val path = session.uri ?: "/"
            val method = session.method
            when {
                path == "/info" && method == Method.GET -> info()
                path == "/pair" && method == Method.POST -> pair(session)
                path.startsWith("/api/") -> api(session, path)
                else -> notFound()
            }
        } catch (e: Exception) {
            LyLog.e(TAG, "Sunucu hatası", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "sunucu hatası")
        }
    }

    private fun info(): Response {
        val payload = mapOf(
            "app" to "lyane", "api" to 1, "name" to provider.deviceName(),
            "requiresPin" to true
        )
        return okJson(json.encodeToString(payload))
    }

    private fun pair(session: IHTTPSession): Response {
        if (!pinValid()) regeneratePin()
        val body = readBody(session, limit = 4 * 1024).toString(Charsets.UTF_8)
        val pinSent = runCatching {
            json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), body)["pin"] ?: ""
        }.getOrDefault("")
        if (pinAttempts >= 5) return forbidden("Çok fazla hatalı deneme; sunucuyu yeniden başlatın")
        if (pinSent != pin) {
            pinAttempts++
            return forbidden("PIN hatalı")
        }
        pinAttempts = 0
        token = UUID.randomUUID().toString().replace("-", "")
        pairedAtMs = System.currentTimeMillis()
        LyLog.i(TAG, "Eşleştirme başarılı (istemci onaylandı)")
        return okJson(json.encodeToString(mapOf("token" to token, "name" to provider.deviceName())))
    }

    private fun authorized(session: IHTTPSession): Boolean {
        val t = session.parameters["token"]?.firstOrNull() ?: return false
        if (token.isBlank() || System.currentTimeMillis() - pairedAtMs > 30 * 60 * 1000) return false
        return t == token
    }

    private fun api(session: IHTTPSession, path: String): Response {
        if (!authorized(session)) return forbidden("Eşleştirme gerekli (PIN)")
        return when {
            path == "/api/list" && session.method == Method.GET -> {
                val items = provider.items()
                okJson(json.encodeToString(items))
            }
            path.startsWith("/api/item/") && session.method == Method.GET -> {
                serveItem(session, path.removePrefix("/api/item/"))
            }
            path == "/api/push" && session.method == Method.POST -> {
                acceptPush(session)
            }
            path == "/api/ping" -> okJson("""{"ok":true}""")
            else -> notFound()
        }
    }

    private fun serveItem(session: IHTTPSession, itemId: String): Response {
        val item = provider.items().firstOrNull { it.id == itemId } ?: return notFound()
        val file: File = when (item.kind) {
            "model" -> provider.prepareModelZip(itemId) ?: return notFound()
            "plugin" -> provider.preparePluginZip(itemId) ?: return notFound()
            else -> provider.rawFile(itemId) ?: return notFound()
        }
        if (!file.isFile) return notFound()

        val mime = if (item.kind == "audio" || item.kind == "transcript") "application/octet-stream" else "application/zip"
        val rangeHeader = session.headers["range"]
        if (rangeHeader != null) {
            val m = Regex("bytes=(\\d+)-(\\d*)").find(rangeHeader)
            if (m != null) {
                val start = m.groupValues[1].toLong()
                val end = if (m.groupValues[2].isNotEmpty()) m.groupValues[2].toLong() else file.length() - 1
                val len = (end - start + 1).coerceAtLeast(0)
                val raf = RandomAccessFile(file, "r")
                raf.seek(start)
                val fis: InputStream = object : InputStream() {
                    var remaining = len
                    override fun read(): Int {
                        if (remaining <= 0) return -1
                        val b = raf.read()
                        if (b >= 0) remaining--
                        return b
                    }

                    override fun read(b: ByteArray, off: Int, len2: Int): Int {
                        if (remaining <= 0) return -1
                        val n = raf.read(b, off, minOf(len2.toLong(), remaining).toInt())
                        if (n > 0) remaining -= n
                        return n
                    }

                    override fun close() {
                        raf.close()
                    }
                }
                val resp = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, fis, len)
                resp.addHeader("Content-Range", "bytes $start-$end/${file.length()}")
                resp.addHeader("Accept-Ranges", "bytes")
                return resp
            }
        }
        val resp = newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), file.length())
        resp.addHeader("Accept-Ranges", "bytes")
        return resp
    }

    /** Parça parça yükleme: ?uploadId=&seq=&last=&kind=&name= */
    private fun acceptPush(session: IHTTPSession): Response {
        val params = session.parameters
        val uploadId = params["uploadid"]?.firstOrNull() ?: return badRequest("uploadId yok")
        val seq = params["seq"]?.firstOrNull()?.toIntOrNull() ?: 0
        val last = params["last"]?.firstOrNull() == "1"
        val kind = params["kind"]?.firstOrNull() ?: return badRequest("kind yok")
        val name = params["name"]?.firstOrNull() ?: "gelen"
        require(kind == "model" || kind == "plugin" || kind == "audio" || kind == "catalog") { "geçersiz kind" }

        val dir = synchronized(uploadDirs) {
            uploadDirs.getOrPut(uploadId) {
                File(context.cacheDir, "drop-in/$uploadId").apply { mkdirs() }
            }
        }
        val part = File(dir, "part.bin")
        val body = readBody(session, limit = 16L * 1024 * 1024)
        FileOutputStream(part, seq > 0).use { it.write(body) }

        if (!last) return okJson("""{"seq":$seq}""")

        val message = runCatching { provider.acceptPush(kind, name, part) }
            .getOrElse { "Alınamadı: ${it.message}" }
        synchronized(uploadDirs) { uploadDirs.remove(uploadId) }
        dir.deleteRecursively()
        LyLog.i(TAG, "Gelen: $kind '$name' → $message")
        return okJson("""{"ok":true,"message":""" + "\"" + message.replace("\"", "'") + "\"}")
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────

    private fun readBody(session: IHTTPSession, limit: Long): ByteArray {
        val lenHeader = session.headers["content-length"]?.toLongOrNull() ?: 0L
        val len = lenHeader.coerceAtMost(limit)
        val out = java.io.ByteArrayOutputStream(len.toInt().coerceAtMost(16 * 1024 * 1024))
        val buf = ByteArray(64 * 1024)
        var remaining = len
        val input = session.inputStream
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(remaining.toInt(), buf.size))
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    private fun okJson(payload: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", payload)

    private fun forbidden(msg: String): Response =
        newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain; charset=utf-8", msg)

    private fun badRequest(msg: String): Response =
        newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain; charset=utf-8", msg)

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "yok")

    companion object {
        private const val TAG = "DropServer"
        private const val CHUNK = 512L * 1024
    }
}
