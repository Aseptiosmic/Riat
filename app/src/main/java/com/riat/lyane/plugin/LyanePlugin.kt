package com.riat.lyane.plugin

import java.io.File

/**
 * DEX eklentileri için genel arayüz.
 *
 * Eklenti geliştiricileri bu arayüzü uygulayan bir sınıf derler, `plugin.dex`
 * olarak paketler ve `plugin.json` içinde `mainClass` alanına sınıf adını
 * yazar. Lyane eklentiyi DexClassLoader ile yükler — Android'in izin verdiği
 * ölçüde uygulamanın kendisi kadar güçlüdür.
 *
 * Tüm eklentiler (JS veya DEX) cihaz dışına veri gönderemez; Lyane'ye ağ
 * erişimi veren herhangi bir köprü yoktur.
 */
interface LyanePlugin {

    /**
     * Eklenti yüklendiğinde çağrılır.
     * @return false dönerse eklenti devre dışı bırakılır.
     */
    fun onLoad(ctx: PluginContext): Boolean

    /** Eklenti kaldırılmadan/boşaltılmadan önce çağrılır. */
    fun onUnload()

    /**
     * TTS'e gönderilmeden önce metin üzerine çalışır.
     * @return dönüştürülmüş metin; null dönerse orijinal metin kullanılır.
     */
    fun onTtsPreprocess(text: String, modelId: String?): String? = null

    /**
     * STT sonucu kullanıcıya gösterilmeden önce çalışır.
     * @return dönüştürülmüş metin; null dönerse orijinal kullanılır.
     */
    fun onAsrPostprocess(text: String, modelId: String?): String? = null

    /**
     * Eklenti aracının butonuna basıldığında çağrılır.
     * @param state arayüzdeki alanların o anki değerleri
     * @return güncellenecek alanlar (id → yeni değer); null ise değişiklik yok
     */
    fun onToolAction(toolId: String, actionId: String, state: Map<String, String>): Map<String, String>? = null
}

/** Eklentilere sunulan, izinlerle sınırlı bağlam. */
class PluginContext(
    val pluginId: String,
    val storageDir: File,
    val logger: (String) -> Unit,
    val appVersion: String
)
