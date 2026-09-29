package com.riat.lyane

import com.riat.lyane.engine.WavIo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.sin

class WavIoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `yaz-oku gidiş dönüş`() {
        val samples = FloatArray(1600) { sin(it * 0.05f).toFloat() * 0.8f }
        val f = tmp.newFile("test.wav")
        WavIo.write(f, samples, 16000)
        val read = WavIo.read(f)
        assertEquals(16000, read.sampleRate)
        assertEquals(samples.size, read.samples.size)
        // 16-bit nicemleme payı ile karşılaştır
        assertArrayEquals(samples, read.samples, 0.001f)
    }

    @Test
    fun `bos dosya degil`() {
        val f = tmp.newFile("x.wav")
        f.writeText("not a wav")
        var thrown = false
        try {
            WavIo.read(f)
        } catch (e: WavIo.WavException) {
            thrown = true
        }
        assert(thrown)
    }
}

private fun assertArrayEquals(expected: FloatArray, actual: FloatArray, delta: Float) {
    assertEquals(expected.size, actual.size)
    for (i in expected.indices) {
        if (kotlin.math.abs(expected[i] - actual[i]) > delta) {
            throw AssertionError("farklı @$i: ${expected[i]} != ${actual[i]}")
        }
    }
}
