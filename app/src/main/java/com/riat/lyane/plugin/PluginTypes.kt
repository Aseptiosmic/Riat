package com.riat.lyane.plugin

import kotlinx.serialization.Serializable

/**
 * `.lyplugin` paketinin kökündeki `plugin.json` şeması.
 *
 * Bir eklenti paketi (.lyplugin = ZIP) şunları içerebilir:
 *   plugin.json   — bu manifest (zorunlu)
 *   main.js       — script eklentisinin kodu (type=script)
 *   plugin.dex    — derlenmiş Kotlin/Java eklenti (type=dex)
 *   voices/…      — eklentiyle gelen ek dosyalar (pack içerikleri)
 *
 * Açıklamalar Türkçe yazılabilir; Lyane arayüzü doğrudan gösterir.
 */
@Serializable
data class PluginUiElement(
    /** textfield | textarea | button | text | switch | slider */
    val type: String,
    val id: String = "",
    val label: String = "",
    val multiline: Boolean = false,
    /** button: çağrılacak eylem kimliği (onToolAction'a actionId olarak geçer) */
    val action: String = "",
    /** slider için min/max/adım */
    val min: Float = 0f,
    val max: Float = 100f,
    val step: Float = 1f,
    val initial: String = ""
)

@Serializable
data class PluginTool(
    val id: String,
    val title: String,
    val description: String = "",
    val icon: String = "extension",
    val ui: List<PluginUiElement> = emptyList()
)

@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val author: String = "",
    val description: String = "",
    val descriptionEn: String = "",
    /** Lyane eklenti API sürümü (şu an 1) */
    val api: Int = 1,
    /** script | dex | pack */
    val type: String = "script",
    /** script: js dosyası adı */
    val entry: String = "",
    /** dex: LyanePlugin uygulayan sınıfın tam adı */
    val mainClass: String = "",
    /** ["tts.preprocess","asr.postprocess","tool"] */
    val hooks: List<String> = emptyList(),
    /**
     * İzinler:
     *  tts.text   — seslendirilecek metni okuyup değiştirebilir
     *  asr.text   — çeviriyazı metnini okuyup değiştirebilir
     *  storage    — kendi veri alanında kalıcı anahtar/değer saklayabilir
     *  files      — kendi dosya alanında dosya okuyup yazabilir
     *  ui         — bildirim (toast) gösterebilir, araç arayüzü tanımlayabilir
     *  models     — kurulu model listesini okuyabilir
     */
    val permissions: List<String> = emptyList(),
    val tools: List<PluginTool> = emptyList(),
    /** Gömülü örnek/yardım metni */
    val help: String = ""
)

object PluginPermissions {
    const val TTS_TEXT = "tts.text"
    const val ASR_TEXT = "asr.text"
    const val STORAGE = "storage"
    const val FILES = "files"
    const val UI = "ui"
    const val MODELS = "models"

    fun describe(p: String): String = when (p) {
        TTS_TEXT -> "Seslendirme metnini okuyup değiştirme"
        ASR_TEXT -> "Çeviriyazı metnini okuyup değiştirme"
        STORAGE -> "Eklenti verisi saklama (yalnızca kendi alanı)"
        FILES -> "Eklenti dosyaları okuma/yazma (yalnızca kendi alanı)"
        UI -> "Bildirim gösterme ve araç arayüzü"
        MODELS -> "Kurulu model listesini okuma"
        else -> p
    }
}

object PluginHooks {
    const val TTS_PREPROCESS = "tts.preprocess"
    const val ASR_POSTPROCESS = "asr.postprocess"
    const val TOOL = "tool"
}
