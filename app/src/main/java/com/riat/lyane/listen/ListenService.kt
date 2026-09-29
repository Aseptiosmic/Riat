package com.riat.lyane.listen

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.riat.lyane.MainActivity
import com.riat.lyane.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Mikrofon ön plan servisi: dinleme ekran kapalıyken de sürer. */
class ListenService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        lifecycleScope.launch {
            container.listenController.state.collectLatest { st ->
                when (st.phase) {
                    ListenController.Phase.LISTENING -> update(
                        getString(R.string.listen_service_notif),
                        st.finalText.takeLast(60).ifBlank { "…" }
                    )
                    ListenController.Phase.PAUSED -> update(getString(R.string.listen_service_notif), getString(R.string.pause))
                    else -> {
                        stopSelf()
                        return@collectLatest
                    }
                }
            }
        }
    }

    private fun startAsForeground() = update(getString(R.string.listen_service_notif), "…")

    private fun update(title: String, text: String) {
        val pi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private val container get() = (application as com.riat.lyane.LyaneApp).container

    companion object {
        const val CHANNEL_ID = "lyane_listen"
        const val NOTIF_ID = 22

        fun createChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val ch = android.app.NotificationChannel(
                CHANNEL_ID, context.getString(R.string.channel_listen), android.app.NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }
}
