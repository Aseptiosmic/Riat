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

    fun extract(archive: File, targetDir: File, onFile: (Int) -> Unit = {}): Int {
        targetDir.mkdirs()
        val name = archive.name.lowercase()
        val count = when {
            name.endsWith(".tar.bz2") || name.endsWith(".tbz2") -> extractTar(wrapBzip2(archive), targetDir, onFile)
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> extractTar(wrapGzip(archive), targetDir, onFile)
            name.endsWith(".tar") -> extractTar(archive.inputStream().buffered(), targetDir, onFile)
            name.endsWith(".zip") -> extractZip(archive, targetDir, onFile)
            name.endsWith(".onnx") || name.endsWith(".ort") -> {
                val f = File(targetDir, archive.name)
                archive.copyTo(f, overwrite = true)
                1
            }
            else -> error("Desteklenmeyen arşiv türü: ${archive.name}")
        }
        flatten(targetDir)
        return count
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
