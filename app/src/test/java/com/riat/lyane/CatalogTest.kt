package com.riat.lyane

import com.riat.lyane.model.CatalogFile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Gömülü model kataloğunun geçerliliği: her girdi indirilebilir bir URL,
 * motor ve kritik yol tanımlarına sahip olmalı.
 */
class CatalogTest {

    private val catalog: CatalogFile by lazy {
        val f = File("src/main/assets/catalog/models.json")
        Json { ignoreUnknownKeys = true }.decodeFromString(CatalogFile.serializer(), f.readText())
    }

    @Test
    fun `katalog okunur ve bos degildir`() {
        assertTrue(catalog.models.size >= 10)
    }

    @Test
    fun `kimlikler benzersizdir`() {
        val ids = catalog.models.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `tum url'ler https ve bilinen dagitim noktalaridir`() {
        catalog.models.forEach { m ->
            assertTrue("bozuk url: ${m.id}", m.url.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/"))
        }
    }

    @Test
    fun `gorev tipleri gecerlidir`() {
        val valid = setOf("tts", "asr", "asr_live", "vad")
        catalog.models.forEach { assertTrue(it.task in valid) }
    }

    @Test
    fun `her model kritik yol adaylarina sahiptir`() {
        catalog.models.forEach { m ->
            when (m.engine) {
                "vits", "sensevoice" -> assertTrue(m.paths.model.isNotEmpty())
                "kokoro", "kitten" -> {
                    assertTrue(m.paths.model.isNotEmpty())
                    assertTrue(m.paths.voices.isNotEmpty())
                }
                "whisper" -> {
                    assertTrue(m.paths.encoder.isNotEmpty())
                    assertTrue(m.paths.decoder.isNotEmpty())
                    assertTrue(m.paths.tokens.isNotEmpty())
                }
                "moonshine" -> {
                    assertTrue(m.paths.encoder.isNotEmpty())
                    assertTrue(m.paths.mergedDecoder.isNotEmpty() || m.paths.uncachedDecoder.isNotEmpty())
                }
                "transducer" -> {
                    assertTrue(m.paths.encoder.isNotEmpty())
                    assertTrue(m.paths.decoder.isNotEmpty())
                    assertTrue(m.paths.joiner.isNotEmpty())
                }
                "matcha" -> assertTrue(m.paths.acousticModel.isNotEmpty() && m.paths.vocoder.isNotEmpty())
                "supertonic" -> assertTrue(m.paths.durationPredictor.isNotEmpty())
                "silero" -> assertTrue(m.paths.model.isNotEmpty())
            }
        }
    }

    @Test
    fun `katalogda turkce sesler ve whisper vardir`() {
        assertTrue(catalog.models.any { it.engine == "vits" && "tr" in it.languages })
        assertTrue(catalog.models.any { it.engine == "whisper" && "tr" in it.languages })
        assertTrue(catalog.models.any { it.task == "asr_live" })
    }

    @Test
    fun `vad bileşeni katalogda`() {
        assertTrue(catalog.models.any { it.task == "vad" })
    }

    @Test
    fun `boyutlar makul aralikta`() {
        catalog.models.forEach { m ->
            assertTrue("şüpheli boyut: ${m.id} ${m.sizeBytes}", m.sizeBytes in 1..2_000_000_000L)
        }
        assertFalse(catalog.models.any { it.sizeBytes == 0L })
    }
}
