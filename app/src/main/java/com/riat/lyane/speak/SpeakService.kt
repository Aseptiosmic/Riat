package com.riat.lyane.speak

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

/** Seslendirme sürerken bildirimde kalır; ekran kapalıyken üretim devam eder. */
class SpeakService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        startAsForeground(getString(R.string.speak_service_notif), "")
        lifecycleScope.launch {
            container.speakController.state.collectLatest { st ->
                when (st.phase) {
                    com.riat.lyane.speak.SpeakController.Phase.IDLE,
                    com.riat.lyane.speak.SpeakController.Phase.FINISHED,
                    com.riat.lyane.speak.SpeakController.Phase.FAILED -> {
                        update(getString(R.string.speak_service_notif), "", done = true)
                        stopSelf()
                    }
                    else -> {
                        val text = "Cümle ${st.sentenceIndex + 1}/$st.sentenceCount · ${"%.1f".format(st.xRealtime)}x gerçek zaman"
                        update(getString(R.string.speak_service_notif), text, done = false)
                    }
                }
            }
        }
    }

    private fun update(title: String, text: String, done: Boolean) {
        val pi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(!done)
            .setOnlyAlertOnce(true)
            .setSilent(true)
        val notif: Notification = builder.build()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startAsForeground(title: String, text: String) = update(title, text, done = false)

    private val container get() = (application as com.riat.lyane.LyaneApp).container

    companion object {
        const val CHANNEL_ID = "lyane_speak"
        const val NOTIF_ID = 21

        fun createChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val ch = android.app.NotificationChannel(
                CHANNEL_ID, context.getString(R.string.channel_speak), android.app.NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }
}
