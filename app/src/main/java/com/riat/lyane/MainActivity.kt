package com.riat.lyane

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.riat.lyane.ui.AppNav
import com.riat.lyane.ui.theme.LyaneTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var themeSetting by mutableStateOf("system")

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val app = application as LyaneApp
        lifecycleScope.launch {
            app.container.settings.settings.collect { themeSetting = it.theme }
        }

        setContent {
            LyaneTheme(themeSetting = themeSetting) {
                AppNav(
                    requestMicPermission = {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        val app = application as? LyaneApp
        if (app != null && isFinishing) {
            app.container.speakController.shutdown()
            app.container.listenController.stop()
            app.container.engines.releaseAll()
            app.container.drop.stopHosting()
        }
        super.onDestroy()
    }
}
