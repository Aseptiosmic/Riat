package com.riat.lyane

import android.app.Application
import android.content.Context
import com.riat.lyane.core.LyLog
import com.riat.lyane.drop.DropManager
import com.riat.lyane.engine.Benchmarker
import com.riat.lyane.engine.EngineManager
import com.riat.lyane.library.LibraryRepository
import com.riat.lyane.listen.ListenService
import com.riat.lyane.model.ModelDownloadManager
import com.riat.lyane.model.ModelDownloadService
import com.riat.lyane.model.ModelRegistry
import com.riat.lyane.model.ModelStore
import com.riat.lyane.plugin.PluginManager
import com.riat.lyane.settings.SettingsRepository
import com.riat.lyane.speak.SpeakController
import com.riat.lyane.speak.SpeakService
import com.riat.lyane.listen.ListenController

/**
 * Lyane — her şeyi yerel çalıştıran ses motoru.
 *
 * Bağımlılık zinciri elle kurulur (hafif ve şeffaf):
 *   Settings → Registry/Store → Engines → Controllers → Drop
 */
class LyaneApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.init()
        createChannels()
        LyLog.i(TAG, "Lyane başladı · ${com.riat.lyane.core.Device.summary()}")
    }

    private fun createChannels() {
        ModelDownloadService.createChannel(this)
        ListenService.createChannel(this)
        SpeakService.createChannel(this)
    }

    companion object {
        private const val TAG = "LyaneApp"
    }
}

class AppContainer(private val context: Context) {
    val settings = SettingsRepository(context)
    val registry = ModelRegistry(context)
    val store = ModelStore(context)
    val library = LibraryRepository(context)
    val plugins = PluginManager(context, settings, store)
    val engines = EngineManager(context, store, settings)
    val downloadManager = ModelDownloadManager(context, registry, store, settings)
    val speakController = SpeakController(context, engines, store, plugins, library)
    val listenController = ListenController(context, engines, store, plugins, library, downloadManager)
    val drop = DropManager(context, settings, store, plugins, library, registry)
    val benchmarker = Benchmarker(engines, store)

    fun init() {
        registry.load()
        store.scan()
        library.load()
        plugins.scan()
    }
}
