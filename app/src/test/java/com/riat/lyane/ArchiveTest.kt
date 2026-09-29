package com.riat.lyane

import com.riat.lyane.model.Archive
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * İndirme sırasında arşiv dosyaları "<model-id>.part" adıyla tutulur;
 * Archive bu yüzden uzantı değil, biçim ipucu ve içerik imzasıyla
 * çalışmak zorundadır. Bu testler tam o senaryoyu kapsar.
 */
class ArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun tarBz2(out: File, vararg entries: Pair<String, String>) {
        TarArchiveOutputStream(BZip2CompressorOutputStream(out.outputStream().buffered())).use { tar ->
            entries.forEach { (name, content) ->
                val bytes = content.toByteArray()
                val e = TarArchiveEntry(name)
                e.size = bytes.size.toLong()
                tar.putArchiveEntry(e)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
    }

    private fun tarGz(out: File, vararg entries: Pair<String, String>) {
        TarArchiveOutputStream(GzipCompressorOutputStream(out.outputStream().buffered())).use { tar ->
            entries.forEach { (name, content) ->
                val bytes = content.toByteArray()
                val e = TarArchiveEntry(name)
                e.size = bytes.size.toLong()
                tar.putArchiveEntry(e)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
    }

    private fun zip(out: File, vararg entries: Pair<String, String>) {
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
    }

    @Test
    fun `part uzantili tar bz2 sihirli bayttan taninir`() {
        val f = tmp.newFile("piper-tr.part")
        tarBz2(f, "vits-piper/model.onnx" to "MODEL", "vits-piper/tokens.txt" to "TOKENS")
        val out = tmp.newFolder("out1")
        val n = Archive.extract(f, out)
        assertEquals(2, n)
        // tek kök dizin düzleştirilir
        assertTrue(File(out, "model.onnx").readText() == "MODEL")
        assertTrue(File(out, "tokens.txt").readText() == "TOKENS")
    }

    @Test
    fun `part uzantili tar gz sihirli bayttan taninir`() {
        val f = tmp.newFile("zipformer.part")
        tarGz(f, "encoder.onnx" to "E", "tokens.txt" to "T")
        val out = tmp.newFolder("out2")
        val n = Archive.extract(f, out)
        assertEquals(2, n)
        assertTrue(File(out, "encoder.onnx").readText() == "E")
    }

    @Test
    fun `part uzantili zip sihirli bayttan taninir`() {
        val f = tmp.newFile("kokoro.part")
        zip(f, "model.int8.onnx" to "K", "voices.bin" to "V", "tokens.txt" to "T")
        val out = tmp.newFolder("out3")
        val n = Archive.extract(f, out)
        assertEquals(3, n)
        assertTrue(File(out, "voices.bin").readText() == "V")
    }

    @Test
    fun `format ipucu uzantidan gucludur`() {
        val f = tmp.newFile("model.zip") // uzantı YALAN; içerik tar.bz2
        tarBz2(f, "model.onnx" to "X")
        val out = tmp.newFolder("out4")
        val n = Archive.extract(f, out, formatHint = "tar.bz2")
        assertEquals(1, n)
        assertTrue(File(out, "model.onnx").readText() == "X")
    }

    @Test
    fun `tek dosya ipucu kopyalar`() {
        val f = tmp.newFile("vad.part")
        f.writeText("ONNXDATA")
        val out = tmp.newFolder("out5")
        val n = Archive.extract(f, out, formatHint = "file")
        assertEquals(1, n)
        assertTrue(File(out, "vad.part").readText() == "ONNXDATA")
    }

    @Test
    fun `bilinmeyen icerik net hata verir`() {
        val f = tmp.newFile("garbage.part")
        f.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05))
        val out = tmp.newFolder("out6")
        var thrown = false
        try {
            Archive.extract(f, out)
        } catch (e: IllegalStateException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
