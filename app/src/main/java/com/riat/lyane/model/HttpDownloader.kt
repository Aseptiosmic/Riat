package com.riat.lyane.model

import com.riat.lyane.core.LyLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Yeniden başlatılabilir (Range destekli) HTTP indirici.
 * Sunucuya yalnızca model dosyalarının açık kaynak dağıtım adreslerinden
 * bağlanılır; hiçbir Lyane sunucusu yoktur.
 */
class HttpDownloader {

    data class Progress(
        val downloaded: Long,
        val total: Long,
        val bytesPerSec: Double
    )

    class DownloadException(message: String, val httpCode: Int = -1) : IOException(message)

    /**
     * @param onProgress her parça sonunda çağrılır (ana iş parçacığında DEĞİL)
     * @param isCancelled true dönerse indirme duraklatılır (devam edilebilir)
     */
    suspend fun download(
        url: String,
        dest: File,
        onProgress: (Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): Long = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val offset = if (dest.exists()) dest.length() else 0L

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Lyane/1.0 (Android; +https://github.com/Aseptiosmic/Riat)")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }

            val code = conn.responseCode
            when {
                code == 206 -> { /* aralıklı yanıt: kaldığı yerden devam */ }
                code == 200 && offset > 0 -> {
                    // sunucu Range'i yok saydı; baştan başla
                    dest.delete()
                }
                code in 200..299 -> Unit
                code == 404 -> throw DownloadException("Adres bulunamadı (404): model yayından kalkmış olabilir", code)
                code in 500..599 -> throw DownloadException("Sunucu hatası ($code), sonra tekrar deneyin", code)
                else -> throw DownloadException("Beklenmeyen yanıt kodu: $code", code)
            }

            val contentLength = conn.contentLengthLong
            val total = if (contentLength > 0) contentLength + (if (code == 206) offset else 0L) else -1L
            var downloaded = if (code == 206) offset else 0L
            var lastTick = System.currentTimeMillis()
            var lastBytes = downloaded
            var speed = 0.0

            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                val append = code == 206
                java.io.FileOutputStream(dest, append).use { out ->
                    while (true) {
                        if (isCancelled()) throw PauseException()
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastTick > 400) {
                            speed = (downloaded - lastBytes) * 1000.0 / (now - lastTick)
                            lastTick = now
                            lastBytes = downloaded
                            onProgress(Progress(downloaded, total, speed))
                        }
                    }
                    out.fd.sync()
                }
            }
            onProgress(Progress(downloaded, if (total > 0) total else downloaded, speed))
            downloaded
        } catch (pe: PauseException) {
            throw pe
        } catch (e: Exception) {
            // kısmi dosya kalır; kullanıcı "devam et" diyebilir
            LyLog.w(TAG, "İndirme hatası: $url", e)
            throw e
        } finally {
            conn?.disconnect()
        }
    }

    class PauseException : IOException("PAUSED")

    companion object {
        private const val TAG = "HttpDownloader"
    }
}
