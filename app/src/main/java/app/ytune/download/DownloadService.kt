package app.ytune.download

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.ytune.Graph
import app.ytune.MainActivity
import app.ytune.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Keeps the process alive (and shows progress) while downloads run with the app in the
 * background. The actual work lives in [DownloadManager].
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) {
            Graph.downloads.cancelAll()
        }
        goForeground(buildNotification(Graph.downloads.tasks.value))

        if (observer == null) {
            observer = scope.launch {
                Graph.downloads.tasks.conflate().collect { tasks ->
                    if (tasks.values.none { it.isActive }) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(tasks))
                        delay(700)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 caps dataSync services at 6h/day; wind down gracefully.
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun buildNotification(tasks: Map<String, DlTask>): Notification {
        val active = tasks.values.filter { it.isActive }
        val running = active.filter { it.status == DlStatus.RUNNING }
        val waiting = active.isNotEmpty() && active.all { it.status == DlStatus.WAITING_NETWORK }
        val current = running.firstOrNull()
        val title = when {
            active.isEmpty() -> "Downloads finished"
            waiting -> "Waiting for network · ${active.size} queued"
            else -> "Downloading · ${active.size} left"
        }
        val openApp = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_DOWNLOADS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancelAll = PendingIntent.getService(
            this, 2,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(current?.let { "${it.track.title} — ${(it.progress * 100).toInt()}%" })
            .setProgress(100, ((current?.progress ?: 0f) * 100).toInt(), current == null || current.total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp)
            .addAction(0, "Cancel all", cancelAll)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_CANCEL_ALL = "app.ytune.CANCEL_ALL_DOWNLOADS"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (e: Exception) {
                // Background start not allowed (Android 12+). Downloads still run while the
                // process is alive, e.g. while music is playing.
                Log.w("DownloadService", "Could not start foreground service", e)
            }
        }
    }
}
