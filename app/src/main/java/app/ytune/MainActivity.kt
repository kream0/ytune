package app.ytune

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import app.ytune.ui.AppRoot
import app.ytune.ui.AppViewModel
import app.ytune.ui.theme.YTuneTheme

class MainActivity : ComponentActivity() {

    private val appViewModel: AppViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            YTuneTheme {
                AppRoot(appViewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Graph.player.connect()
        Graph.updater.checkIfDue()
    }

    override fun onStop() {
        super.onStop()
        Graph.player.disconnect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_OPEN_PLAYER -> appViewModel.nowPlaying = true
            ACTION_OPEN_DOWNLOADS -> appViewModel.openDownloads()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(appViewModel::handleSharedText)
            ACTION_INSTALL_STATUS -> Graph.updater.onInstallStatus(this, intent)
        }
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "app.ytune.OPEN_PLAYER"
        const val ACTION_OPEN_DOWNLOADS = "app.ytune.OPEN_DOWNLOADS"
        const val ACTION_INSTALL_STATUS = "app.ytune.INSTALL_STATUS"
    }
}
