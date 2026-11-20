package com.cutvideo.editor.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.cutvideo.editor.CutVideoApp
import com.cutvideo.editor.MainActivity
import com.cutvideo.editor.R
import com.cutvideo.editor.domain.export.ExportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps an in-progress export alive when the user leaves the app and mirrors
 * the real Transformer progress into a notification. It never produces progress of its own: it only
 * renders the [ExportController] state.
 */
class ExportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Preparing export…", 0, indeterminate = true))

        val controller = (application as CutVideoApp).container.exportController
        observer?.cancel()
        observer = scope.launch {
            controller.state.collectLatest { state ->
                when (state) {
                    is ExportState.Preparing ->
                        notify(buildNotification(state.message, 0, indeterminate = true))
                    is ExportState.Running -> {
                        val percent = (state.progress * 100).toInt().coerceIn(0, 100)
                        notify(buildNotification(state.stage, percent, indeterminate = false))
                    }
                    is ExportState.Success -> {
                        notify(buildNotification(getString(R.string.export_done), 100, indeterminate = false))
                        stopSelf()
                    }
                    is ExportState.Failed -> {
                        notify(buildNotification(getString(R.string.export_failed), 0, indeterminate = false))
                        stopSelf()
                    }
                    ExportState.Cancelled, ExportState.Idle -> stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String, progress: Int, indeterminate: Boolean): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(getString(R.string.export_notification_title))
            .setContentText(text)
            .setContentIntent(intent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (indeterminate) setProgress(0, 0, true) else setProgress(100, progress, false)
            }
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "cutvideo_export"
        private const val NOTIFICATION_ID = 4711

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.export_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.export_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        fun start(context: Context) {
            val intent = Intent(context, ExportService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ExportService::class.java)) }
        }
    }
}
