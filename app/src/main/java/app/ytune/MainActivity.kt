package app.ytune

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ytune.data.ThemeMode
import app.ytune.ui.AppRoot
import app.ytune.ui.AppViewModel
import app.ytune.ui.theme.DarkPalette
import app.ytune.ui.theme.LightPalette
import app.ytune.ui.theme.YTuneTheme

class MainActivity : ComponentActivity() {

    private val appViewModel: AppViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySystemBars(isDark(Graph.settings.current.theme, systemDark()))
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handleIntent(intent)
        val language = Graph.settings.current.language
        setContent {
            val settings by Graph.settings.state.collectAsStateWithLifecycle()
            val dark = isDark(settings.theme, isSystemInDarkTheme())
            LaunchedEffect(dark) { applySystemBars(dark) }
            // UI text is read in the chosen language (see tr()): start over in the new one.
            LaunchedEffect(settings.language) { if (settings.language != language) recreate() }
            YTuneTheme(dark) {
                AppRoot(appViewModel)
            }
        }
    }

    private fun systemDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun isDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.PAPER -> false
    }

    /** Status / navigation bar icons that read on the chosen theme, and a matching window behind it. */
    private fun applySystemBars(dark: Boolean) {
        val style = if (dark) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        window.setBackgroundDrawable(ColorDrawable((if (dark) DarkPalette else LightPalette).background.toArgb()))
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
