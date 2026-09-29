package com.riat.lyane.model

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
import com.riat.lyane.core.Fmt
import com.riat.lyane.core.LyLog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Model indirmeleri sürerken görünür kalması için ön plan servisi.
 * Asıl iş ModelDownloadManager'da yürür; burada yalnızca bildirim güncellenir.
 */
class ModelDownloadService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        startAsForeground(getString(R.string.download_service_notif), 0)
        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            container().downloadManager.states.collectLatest { states ->
                val active = states.values.filter {
                    it is ModelDownloadManager.State.Queued ||
                        it is ModelDownloadManager.State.Downloading ||
                        it is ModelDownloadManager.State.Extracting ||
                        it is ModelDownloadManager.State.Verifying
                }
                if (active.isEmpty()) {
                    stopSelf()
                    return@collectLatest
                }
                val (title, text, progress) = describe(active)
                startAsForeground(text ?: title, progress)
            }
        }
    }

    private fun describe(active: List<ModelDownloadManager.State>): Triple<String, String?, Int> {
        val dl = active.filterIsInstance<ModelDownloadManager.State.Downloading>()
        return if (dl.isNotEmpty()) {
            val d = dl.first()
            val pct = if (d.total > 0) (d.downloaded * 100 / d.total).toInt() else 0
            Triple(
                getString(R.string.download_service_notif),
                "${Fmt.bytes(d.downloaded)} / ${if (d.total > 0) Fmt.bytes(d.total) else "?"} · ${Fmt.speed(d.speed)}",
                pct
            )
        } else {
            Triple(getString(R.string.download_service_notif), getString(R.string.installing_archive), -1)
        }
    }

    private fun startAsForeground(text: String?, progress: Int) {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.download_service_notif))
            .setContentText(text)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
        if (progress in 0..100) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        val notif: Notification = builder.build()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun container() = (application as com.riat.lyane.LyaneApp).container

    companion object {
        const val CHANNEL_ID = "lyane_downloads"
        const val NOTIF_ID = 20

        fun createChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val ch = android.app.NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_downloads),
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply { description = context.getString(R.string.channel_downloads) }
            nm.createNotificationChannel(ch)
        }
    }
}
