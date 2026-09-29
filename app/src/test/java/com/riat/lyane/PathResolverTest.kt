package com.riat.lyane

import com.riat.lyane.model.EnginePaths
import com.riat.lyane.model.ModelSpec
import com.riat.lyane.model.PathResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PathResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun model(
        engine: String,
        paths: EnginePaths,
        options: Map<String, String> = emptyMap()
    ) = ModelSpec(
        id = "test", name = "test", task = "tts", engine = engine, paths = paths, options = options
    )

    @Test
    fun `birebir ad bulunur`() {
        val dir = tmp.newFolder("m1")
        dir.resolve("model.int8.onnx").writeText("x")
        dir.resolve("tokens.txt").writeText("x")
        dir.resolve("voices.bin").writeText("x")
        val spec = model(
            "kokoro",
            EnginePaths(
                model = listOf("model.int8.onnx", "model.onnx"),
                tokens = listOf("tokens.txt"),
                voices = listOf("voices.bin")
            )
        )
        val r = PathResolver.resolve(dir, spec)
        assertEquals("model.int8.onnx", r.files["model"]?.name)
        assertTrue(PathResolver.missingCritical(spec, r).isEmpty())
    }

    @Test
    fun `int8 tercih edilir`() {
        val dir = tmp.newFolder("m2")
        dir.resolve("encoder.onnx").writeText("a")
        dir.resolve("encoder.int8.onnx").writeText("b")
        val spec = model(
            "whisper",
            EnginePaths(encoder = listOf("*-encoder*.onnx", "*.onnx")),
            options = mapOf("quantized" to "true")
        )
        val r = PathResolver.resolve(dir, spec)
        assertEquals("encoder.int8.onnx", r.files["encoder"]?.name)
    }

    @Test
    fun `glob eşleşmesi alt dizinde de çalışır`() {
        val dir = tmp.newFolder("m3")
        dir.resolve("kokoro-v0_19").mkdirs()
        dir.resolve("kokoro-v0_19").resolve("model.onnx").writeText("x")
        val spec = model("kokoro", EnginePaths(model = listOf("model.onnx", "*.onnx")))
        val r = PathResolver.resolve(dir, spec)
        assertNotNull(r.files["model"])
        assertEquals("model.onnx", r.files["model"]?.name)
    }

    @Test
    fun `eslesen yoksa null doner ve eksik raporlanir`() {
        val dir = tmp.newFolder("m4")
        dir.resolve("baska.onnx").writeText("x")
        val spec = model("whisper", EnginePaths(encoder = listOf("enc.onnx")))
        val r = PathResolver.resolve(dir, spec)
        assertNull(r.files["encoder"])
        assertTrue(PathResolver.missingCritical(spec, r).contains("encoder"))
    }

    @Test
    fun `dataDir bulunur`() {
        val dir = tmp.newFolder("m5")
        dir.resolve("espeak-ng-data").mkdirs()
        dir.resolve("espeak-ng-data").resolve("lang").writeText("x")
        dir.resolve("model.onnx").writeText("x")
        dir.resolve("tokens.txt").writeText("x")
        val spec = model(
            "vits",
            EnginePaths(model = listOf("model.onnx"), tokens = listOf("tokens.txt"), dataDir = listOf("espeak-ng-data"))
        )
        val r = PathResolver.resolve(dir, spec)
        assertNotNull(r.dataDirs["espeak-ng-data"])
    }
}
