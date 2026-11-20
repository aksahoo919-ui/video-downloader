package com.easyvideodownloader

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/** Keeps downloads running while the app is in the background, with a progress notification. */
class DownloadService : Service() {

    companion object {
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_AUDIO = "audio"
        private const val EXTRA_CHOICE = "choice"
        private const val ACTION_CANCEL = "com.easyvideodownloader.CANCEL"
        private const val CHANNEL = "downloads"
        private const val PROGRESS_ID = 1
        private const val DONE_ID = 2

        fun start(context: Context, urls: ArrayList<String>, audio: Boolean, choiceIndex: Int) {
            val intent = Intent(context, DownloadService::class.java)
                .putStringArrayListExtra(EXTRA_URLS, urls)
                .putExtra(EXTRA_AUDIO, audio)
                .putExtra(EXTRA_CHOICE, choiceIndex)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var worker: Thread? = null
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Engine.cancel()
            return START_NOT_STICKY
        }
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Downloads")
                .build()
        )
        ServiceCompat.startForeground(
            this, PROGRESS_ID, progressNotification(Engine.state.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (worker != null) return START_NOT_STICKY

        val urls = intent?.getStringArrayListExtra(EXTRA_URLS)
        if (urls.isNullOrEmpty()) {
            Engine.abortStart("Please paste a video link first (step 1).")
            stopSelf()
            return START_NOT_STICKY
        }
        val audio = intent?.getBooleanExtra(EXTRA_AUDIO, false) ?: false
        val choices = Choices.forMode(audio)
        val choice = choices.getOrElse(intent?.getIntExtra(EXTRA_CHOICE, 0) ?: 0) { choices[0] }

        scope.launch {
            Engine.state.collect { s ->
                val now = System.currentTimeMillis()
                if (s.busy && now - lastNotified > 700) {
                    lastNotified = now
                    notifySafely(PROGRESS_ID, progressNotification(s))
                }
            }
        }
        worker = thread(name = "downloads") {
            try {
                Engine.runBatch(urls, audio, choice)
            } finally {
                Handler(Looper.getMainLooper()).post { finish() }
            }
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val s = Engine.state.value
        if (s.tone != Tone.MUTED) {
            notifySafely(
                DONE_ID,
                NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_download)
                    .setContentTitle(if (s.tone == Tone.SUCCESS) "Download finished" else "Download problem")
                    .setContentText(s.status)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(s.status))
                    .setContentIntent(openAppIntent())
                    .setAutoCancel(true)
                    .build()
            )
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun progressNotification(s: UiState): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(s.status.ifBlank { "Downloading..." })
            .setContentText(s.detail)
            .setProgress(100, s.progress ?: 0, s.progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Stop", cancel)
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notifySafely(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (e: SecurityException) {
            // Notifications are switched off; the download carries on regardless.
        }
    }
}
