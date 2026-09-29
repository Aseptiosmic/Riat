package com.riat.lyane.drop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Lyane Drop istemcisi: aynı Wi-Fi ağındaki Lyane sunucusuna bağlanır,
 * PIN ile eşleşir ve ögeleri alır/gönderir.
 */
class DropClient {

    class DropHttpException(message: String, val code: Int) : Exception(message)

    data class RemoteInfo(val name: String, val api: Int)

    private fun open(url: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 60_000
        conn.requestMethod = method
        return conn
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }

    suspend fun fetchInfo(host: String, port: Int): Result<RemoteInfo> = withContext(Dispatchers.IO) {
        runCatching {
            open("http://$host:$port/info", "GET").use { conn ->
                val code = conn.responseCode
                if (code != 200) throw DropHttpException("Bilgi alınamadı ($code)", code)
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val name = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: "Lyane"
                val api = Regex("\"api\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                RemoteInfo(name, api)
            }
        }
    }

    suspend fun pair(host: String, port: Int, pin: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = """{"pin":"$pin"}"""
            open("http://$host:$port/pair", "POST").use { conn ->
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(body.toByteArray().size)
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code != 200) throw DropHttpException(text.ifBlank { "PIN reddedildi ($code)" }, code)
                Regex("\"token\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)
                    ?: throw DropHttpException("Sunucu token döndürmedi", code)
            }
        }
    }

    suspend fun list(host: String, port: Int, token: String): Result<List<DropServer.DropItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                open("http://$host:$port/api/list?token=$token", "GET").use { conn ->
                    val code = conn.responseCode
                    if (code != 200) throw DropHttpException("Liste alınamadı ($code)", code)
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    parseItems(text)
                }
            }
        }

    private fun parseItems(text: String): List<DropServer.DropItem> {
        val out = ArrayList<DropServer.DropItem>()
        // Basit JSON listesi ayrıştırma (kotlinx.serialization ile de olurdu;
        // bağımsız kalsın diye elle yazıldı ve test edilebilir tutuldu)
        Regex("\\{[^{}]*\"id\"[^{}]*\\}").findAll(text).forEach { m ->
            val obj = m.value
            fun str(key: String) = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(obj)?.groupValues?.get(1) ?: ""
            fun num(key: String) = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(obj)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            out += DropServer.DropItem(
                id = str("id"), kind = str("kind"), name = str("name"),
                sizeBytes = num("sizeBytes"), detail = str("detail")
            )
        }
        return out.filter { it.id.isNotBlank() }
    }

    /** Bir ögeyi dosyaya indirir; ilerleme ve iptal destekli. */
    suspend fun download(
        host: String, port: Int, token: String, itemId: String, dest: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            var conn: HttpURLConnection? = null
            try {
                conn = open("http://$host:$port/api/item/$itemId?token=$token", "GET")
                val code = conn.responseCode
                if (code != 200 && code != 206) throw DropHttpException("İndirilemedi ($code)", code)
                val total = conn.contentLengthLong
                var received = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(dest).use { out ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            if (isCancelled()) throw DropHttpException("iptal", -1)
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            received += n
                            onProgress(received, if (total > 0) total else -1L)
                        }
                        out.fd.sync()
                    }
                }
                dest
            } finally {
                conn?.disconnect()
            }
        }
    }

    /** Dosyayı parça parça gönderir (4 MB). */
    suspend fun push(
        host: String, port: Int, token: String, kind: String, name: String, file: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val uploadId = UUID.randomUUID().toString().take(12)
            val total = file.length()
            var sent = 0L
            val chunk = 4 * 1024 * 1024
            file.inputStream().use { input ->
                val buf = ByteArray(chunk)
                var seq = 0
                while (true) {
                    if (isCancelled()) throw DropHttpException("iptal", -1)
                    val n = input.read(buf)
                    if (n < 0) break
                    val last = input.available() == 0
                    val part = if (n == buf.size) buf else buf.copyOf(n)
                    var conn: HttpURLConnection? = null
                    try {
                        conn = open(
                            "http://$host:$port/api/push?token=$token&uploadId=$uploadId&seq=$seq&last=${if (last) 1 else 0}&kind=$kind&name=${urlEncode(name)}",
                            "POST"
                        )
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/octet-stream")
                        conn.setFixedLengthStreamingMode(part.size)
                        conn.outputStream.use { it.write(part) }
                        val code = conn.responseCode
                        if (code != 200) {
                            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                            throw DropHttpException(err.ifBlank { "Gönderme hatası ($code)" }, code)
                        }
                    } finally {
                        conn?.disconnect()
                    }
                    sent += n
                    onProgress(sent, total)
                    seq++
                }
            }
            "tamam"
        }
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")
}
