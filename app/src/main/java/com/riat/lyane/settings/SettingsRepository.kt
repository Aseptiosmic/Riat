package com.riat.lyane.settings

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.riat.lyane.core.Device
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lyane_settings")

/** Uygulama genelinde kalıcı ayarlar. Hepsi cihazda saklanır. */
data class AppSettings(
    val deviceName: String = "Lyane",
    val theme: String = "system",
    val defaultTtsModelId: String = "",
    val defaultAsrModelId: String = "",
    val ttsThreads: Int = Device.ttsThreads(),
    val asrThreads: Int = Device.asrThreads(),
    val wifiOnlyDownloads: Boolean = true,
    val keepScreenOn: Boolean = true,
    val hotwordsEnabled: Boolean = true,
    val enabledPlugins: Set<String> = emptySet(),
    val approvedPluginPermissions: Set<String> = emptySet(), // "pluginId:izin" kümesi
    val benchmarkKeepResults: Boolean = true
)

class SettingsRepository(private val context: Context) {

    private object K {
        val deviceName = stringPreferencesKey("device_name")
        val theme = stringPreferencesKey("theme")
        val defaultTts = stringPreferencesKey("default_tts_model")
        val defaultAsr = stringPreferencesKey("default_asr_model")
        val ttsThreads = intPreferencesKey("tts_threads")
        val asrThreads = intPreferencesKey("asr_threads")
        val wifiOnly = booleanPreferencesKey("wifi_only_downloads")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val hotwords = booleanPreferencesKey("hotwords_enabled")
        val enabledPlugins = stringSetPreferencesKey("enabled_plugins")
        val approvedPerms = stringSetPreferencesKey("approved_plugin_perms")
        val benchmarkKeep = booleanPreferencesKey("benchmark_keep")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            deviceName = p[K.deviceName] ?: defaultDeviceName(),
            theme = p[K.theme] ?: "system",
            defaultTtsModelId = p[K.defaultTts] ?: "",
            defaultAsrModelId = p[K.defaultAsr] ?: "",
            ttsThreads = p[K.ttsThreads] ?: Device.ttsThreads(),
            asrThreads = p[K.asrThreads] ?: Device.asrThreads(),
            wifiOnlyDownloads = p[K.wifiOnly] ?: true,
            keepScreenOn = p[K.keepScreenOn] ?: true,
            hotwordsEnabled = p[K.hotwords] ?: true,
            enabledPlugins = p[K.enabledPlugins] ?: emptySet(),
            approvedPluginPermissions = p[K.approvedPerms] ?: emptySet(),
            benchmarkKeepResults = p[K.benchmarkKeep] ?: true
        )
    }

    fun defaultDeviceName(): String {
        val model = Build.MODEL?.take(18)?.trim().orEmpty().ifBlank { "Cihaz" }
        return "Lyane · $model"
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setDeviceName(v: String) = context.dataStore.edit { it[K.deviceName] = v }
    suspend fun setTheme(v: String) = context.dataStore.edit { it[K.theme] = v }
    suspend fun setDefaultTts(v: String) = context.dataStore.edit { it[K.defaultTts] = v }
    suspend fun setDefaultAsr(v: String) = context.dataStore.edit { it[K.defaultAsr] = v }
    suspend fun setTtsThreads(v: Int) = context.dataStore.edit { it[K.ttsThreads] = v }
    suspend fun setAsrThreads(v: Int) = context.dataStore.edit { it[K.asrThreads] = v }
    suspend fun setWifiOnly(v: Boolean) = context.dataStore.edit { it[K.wifiOnly] = v }
    suspend fun setKeepScreenOn(v: Boolean) = context.dataStore.edit { it[K.keepScreenOn] = v }
    suspend fun setHotwords(v: Boolean) = context.dataStore.edit { it[K.hotwords] = v }
    suspend fun setBenchmarkKeep(v: Boolean) = context.dataStore.edit { it[K.benchmarkKeep] = v }

    suspend fun setPluginEnabled(id: String, enabled: Boolean) {
        context.dataStore.edit { p ->
            val cur = p[K.enabledPlugins] ?: emptySet()
            p[K.enabledPlugins] = if (enabled) cur + id else cur - id
        }
    }

    suspend fun approvePluginPermission(pluginId: String, permission: String, grant: Boolean) {
        context.dataStore.edit { p ->
            val cur = p[K.approvedPerms] ?: emptySet()
            val key = "$pluginId:$permission"
            p[K.approvedPerms] = if (grant) cur + key else cur - key
        }
    }

    suspend fun forgetPlugin(pluginId: String) {
        context.dataStore.edit { p ->
            val cur = p[K.approvedPerms] ?: emptySet()
            p[K.approvedPerms] = cur.filterNot { it.startsWith("$pluginId:") }.toSet()
            val plugins = p[K.enabledPlugins] ?: emptySet()
            p[K.enabledPlugins] = plugins - pluginId
        }
    }
}
