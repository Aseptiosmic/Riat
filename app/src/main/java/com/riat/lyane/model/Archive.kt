package com.riat.lyane.model

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Model arşivlerini (tar.bz2 / tar.gz / tar / zip) güvenli biçimde açar.
 *
 * Yol geçişi (path traversal) saldırılarına karşı her girdinin hedefi
 * doğrulanır. Arşivdeki tek kök dizini otomatik düzleştirir: böylece
 * `kokoro-en-v0_19/model.onnx` → `models/<id>/model.onnx` olur.
 */
object Archive {

    /**
     * @param formatHint katalogdaki "format" değeri (tar.bz2 | tar.gz | tar |
     *        zip | file). Boşsa uzantıya, o da tanımsızsa dosyanın ilk
     *        baytlarındaki imzaya bakılır. İndirme sırasında dosya adı
     *        "<id>.part" olduğundan ipucu/imza olmadan tür bulunamaz.
     */
    fun extract(archive: File, targetDir: File, formatHint: String = "", onFile: (Int) -> Unit = {}): Int {
        targetDir.mkdirs()
        val count = when (detectKind(archive, formatHint)) {
            Kind.TAR_BZ2 -> extractTar(wrapBzip2(archive), targetDir, onFile)
            Kind.TAR_GZ -> extractTar(wrapGzip(archive), targetDir, onFile)
            Kind.TAR -> extractTar(archive.inputStream().buffered(), targetDir, onFile)
            Kind.ZIP -> extractZip(archive, targetDir, onFile)
            Kind.SINGLE -> {
                val f = File(targetDir, archive.name)
                archive.copyTo(f, overwrite = true)
                1
            }
            Kind.UNKNOWN -> error("Desteklenmeyen arşiv türü: ${archive.name}")
        }
        flatten(targetDir)
        return count
    }

    private enum class Kind { TAR_BZ2, TAR_GZ, TAR, ZIP, SINGLE, UNKNOWN }

    /** Tür sırası: biçim ipucu → uzantı → içerik imzası. */
    private fun detectKind(archive: File, hint: String): Kind {
        when (hint) {
            "tar.bz2", "tbz2" -> return Kind.TAR_BZ2
            "tar.gz", "tgz" -> return Kind.TAR_GZ
            "tar" -> return Kind.TAR
            "zip" -> return Kind.ZIP
            "file", "onnx", "ort" -> return Kind.SINGLE
        }
        val name = archive.name.lowercase()
        if (name.endsWith(".tar.bz2") || name.endsWith(".tbz2")) return Kind.TAR_BZ2
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) return Kind.TAR_GZ
        if (name.endsWith(".tar")) return Kind.TAR
        if (name.endsWith(".zip")) return Kind.ZIP
        if (name.endsWith(".onnx") || name.endsWith(".ort")) return Kind.SINGLE
        // İçerik imzası: .part gibi anlamsız adlarda da güvenilir
        runCatching {
            java.io.RandomAccessFile(archive, "r").use { raf ->
                val head = ByteArray(3)
                raf.readFully(head)
                if (head[0] == 'B'.code.toByte() && head[1] == 'Z'.code.toByte() && head[2] == 'h'.code.toByte())
                    return Kind.TAR_BZ2
                if (head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()) return Kind.TAR_GZ
                if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) return Kind.ZIP
                val magic = ByteArray(5)
                raf.seek(257)
                if (raf.read(magic) == 5 && String(magic) == "ustar") return Kind.TAR
            }
        }
        return Kind.UNKNOWN
    }

    private fun wrapBzip2(f: File): InputStream =
        BZip2CompressorInputStream(f.inputStream().buffered(256 * 1024)).let { java.io.BufferedInputStream(it, 256 * 1024) }

    private fun wrapGzip(f: File): InputStream =
        GzipCompressorInputStream(f.inputStream().buffered(256 * 1024)).let { java.io.BufferedInputStream(it, 256 * 1024) }

    private fun safeTarget(root: File, entryName: String): File {
        val rootPath = root.canonicalPath + File.separator
        val out = File(root, entryName)
        val outPath = out.canonicalPath
        if (outPath != root.canonicalPath && !outPath.startsWith(rootPath)) {
            error("Güvensiz arşiv girdisi: $entryName")
        }
        return out
    }

    private fun extractTar(input: InputStream, targetDir: File, onFile: (Int) -> Unit): Int {
        var n = 0
        TarArchiveInputStream(input, 8192).use { tar ->
            while (true) {
                val entry: TarArchiveEntry = tar.nextTarEntry ?: break
                val out = safeTarget(targetDir, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { tar.copyTo(it) }
                    n++
                    if (n % 25 == 0) onFile(n)
                }
            }
        }
        onFile(n)
        return n
    }

    private fun extractZip(archive: File, targetDir: File, onFile: (Int) -> Unit): Int {
        var n = 0
        ZipFile.builder().setFile(archive).setUseUnicodeExtraFields(true).get().use { zip ->
            val entries = zip.entries
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                val out = safeTarget(targetDir, e.name)
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zip.getInputStream(e).use { input ->
                        FileOutputStream(out).use { input.copyTo(it) }
                    }
                    n++
                    if (n % 25 == 0) onFile(n)
                }
            }
        }
        onFile(n)
        return n
    }

    /** Arşiv tek kök dizinliyse içeriğini bir seviye yukarı taşır. */
    private fun flatten(dir: File) {
        var current = dir
        var guard = 0
        while (guard++ < 4) {
            val entries = current.listFiles() ?: return
            if (entries.size == 1 && entries[0].isDirectory) {
                val only = entries[0]
                only.listFiles()?.forEach { it.renameToSafe(File(current, it.name)) }
                only.deleteRecursively()
            } else break
        }
    }

    private fun File.renameToSafe(dest: File): Boolean =
        if (exists()) renameTo(dest) || runCatching {
            copyTo(dest, overwrite = true); delete()
        }.getOrDefault(false) else true
}
