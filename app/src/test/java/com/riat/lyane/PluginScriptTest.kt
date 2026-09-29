package com.riat.lyane

import com.riat.lyane.plugin.PluginBridge
import com.riat.lyane.plugin.ScriptPluginHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Gömülü örnek eklenti (Türkçe Sayı Okuyucu) gerçek Rhino motoruyla
 * çalıştırılır — cihazda birebir aynı kod yoludur.
 */
class PluginScriptTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun host(): ScriptPluginHost {
        val assets = File("src/main/assets/plugins/sample-tr-number")
        val manifest = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(
                com.riat.lyane.plugin.PluginManifest.serializer(),
                assets.resolve("plugin.json").readText()
            )
        val bridge = PluginBridge(
            pluginId = manifest.id,
            permissions = manifest.permissions.toSet(),
            granted = { true },
            storageDir = tmp.newFolder(),
            logSink = { },
            toast = { },
            modelsProvider = { emptyArray() },
            appVersion = "test"
        )
        return ScriptPluginHost(manifest, assets.resolve("main.js"), bridge)
    }

    @Test
    fun `manifest dogru bildirilmis`() {
        val m = host().manifest
        assertEquals("lyane.sample.tr-sayi", m.id)
        assertEquals("script", m.type)
        assertTrue("tts.preprocess" in m.hooks)
        assertTrue(m.tools.isNotEmpty())
    }

    @Test
    fun `sayilar turkce okunusa cevrilir`() {
        val h = host()
        val out = h.call("onTtsPreprocess", arrayOf("Toplam 1250 TL", "tts-model")) as? String
        assertNotNull(out)
        assertTrue(out!!.contains("bin iki yüz elli"))
    }

    @Test
    fun `binlik ayracli sayilar cevrilir`() {
        val h = host()
        val out = h.call("onTtsPreprocess", arrayOf("1.250 TL tutarında", "m")) as? String
        assertNotNull(out)
        assertTrue(out!!.contains("bin iki yüz elli"))
        assertTrue(!out.contains("1.250"))
    }

    @Test
    fun `sifir ve kucuk sayilar`() {
        val h = host()
        val out = h.call("onTtsPreprocess", arrayOf("0 ve 7", "m")) as? String
        assertNotNull(out)
        assertTrue(out!!.contains("sıfır"))
        assertTrue(out.contains("yedi"))
    }

    @Test
    fun `arac eylemi map dondurur`() {
        val h = host()
        val result = h.call(
            "onToolAction",
            arrayOf("sayi-cevirici", "convert", mapOf("input" to "3 saat 20 dakika"))
        )
        assertTrue(result is Map<*, *>)
        val map = result as Map<*, *>
        assertTrue(map.containsKey("output"))
        val out = map["output"] as? String ?: ""
        assertTrue(out.contains("üç"))
        assertTrue(out.contains("yirmi"))
    }

    @Test
    fun `olmayan fonksiyon null dondurur`() {
        val h = host()
        assertEquals(null, h.call("yokBoyleBirFonksiyon", emptyArray()))
    }
}
