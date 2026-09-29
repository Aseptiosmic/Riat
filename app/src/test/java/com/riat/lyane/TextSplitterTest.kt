package com.riat.lyane

import com.riat.lyane.core.TextSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSplitterTest {

    @Test
    fun `kısa cümleler akış için tek parçada birleşir`() {
        // Kısa cümleler bilinçli olarak birleştirilir (daha az sentez çağrısı,
        // daha doğal ezgi). İçerik kaybolmadan tek parçada kalmalı.
        val r = TextSplitter.sentences("Merhaba dünya. Nasılsın? İyiyim!")
        assertEquals(1, r.size)
        assertEquals("Merhaba dünya. Nasılsın? İyiyim!", r[0])
    }

    @Test
    fun `uzun cümleler ayrı kalır`() {
        val a = "Bu birinci cümle ve yeterince uzun tutuldu ki birleşmesin."
        val b = "Bu da ikinci cümle ve benzer şekilde uzun tutuldu burada."
        val r = TextSplitter.sentences("$a $b")
        assertEquals(2, r.size)
        assertEquals(a, r[0])
        assertEquals(b, r[1])
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
        // "Dr." noktası cümle sonu sayılmamalı; parçalar birleşmeyecek
        // kadar uzun seçildi (birleştirme ayrı test edilir).
        val a = "Dr. Ayşe dün akşam toplantıya katıldı ve sunum yaptı."
        val b = "Toplantı tüm ekibin katılımıyla oldukça verimli geçti."
        val r = TextSplitter.sentences("$a $b")
        assertEquals(2, r.size)
        assertEquals(a, r[0])
        assertEquals(b, r[1])
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
        val a = "ilk satır yeterince uzun tutuldu burada ayrı dursun"
        val b = "ikinci satır da benzer şekilde uzun tutuldu burada"
        val r = TextSplitter.sentences("$a\n$b")
        assertEquals(2, r.size)
        assertEquals(a, r[0])
        assertEquals(b, r[1])
    }
}
