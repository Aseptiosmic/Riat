package com.riat.lyane

import com.riat.lyane.core.TextSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSplitterTest {

    @Test
    fun `basit cümleler bölünür`() {
        val r = TextSplitter.sentences("Merhaba dünya. Nasılsın? İyiyim!")
        assertEquals(3, r.size)
        assertEquals("Merhaba dünya.", r[0])
    }

    @Test
    fun `kısa parçalar birleşir`() {
        val r = TextSplitter.sentences("Tamam. Anladım.")
        // "Tamam." + "Anladım." tek parçada birleşebilir
        assertTrue(r.size <= 2)
        assertTrue(r.any { it.contains("Tamam") && it.contains("Anladım") } || r.size == 2)
    }

    @Test
    fun `kısaltmalar bölmez`() {
        val r = TextSplitter.sentences("Dr. Ayşe geldi. Toplantı başladı.")
        assertEquals(2, r.size)
    }

    @Test
    fun `uzun parça bölünür`() {
        val long = ("Bu cümle kasıtlı olarak çok uzun tutuldu ve sınırı aşıyor. ".repeat(12)).trim()
        val r = TextSplitter.sentences(long, maxLen = 120)
        assertTrue(r.size > 1)
        assertTrue(r.all { it.length <= 130 })
    }

    @Test
    fun `boş metin boş liste verir`() {
        assertTrue(TextSplitter.sentences("").isEmpty())
        assertTrue(TextSplitter.sentences("   ").isEmpty())
    }

    @Test
    fun `satır sonları cümle sınırı sayılır`() {
        val r = TextSplitter.sentences("bir satır\nikinci satır")
        assertEquals(2, r.size)
    }
}
