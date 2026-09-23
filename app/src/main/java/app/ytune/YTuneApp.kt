package app.ytune

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.ytune.download.DownloadService

class YTuneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        val channel = NotificationChannel(
            DownloadService.CHANNEL_ID,
            getString(R.string.channel_downloads),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.channel_downloads_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
