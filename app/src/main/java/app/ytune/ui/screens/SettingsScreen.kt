package app.ytune.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ytune.BuildConfig
import app.ytune.Graph
import app.ytune.data.AudioQuality
import app.ytune.data.DownloadStrategy
import app.ytune.data.StreamMode
import app.ytune.data.formatBytes
import app.ytune.playback.StreamCache
import app.ytune.ui.components.Ic
import app.ytune.ui.components.LocalSheets
import app.ytune.ui.components.NothingSwitch
import app.ytune.ui.components.PillButton
import app.ytune.ui.components.ScreenHeader
import app.ytune.ui.components.SectionLabel
import app.ytune.ui.components.Segmented
import app.ytune.ui.components.SheetAction
import app.ytune.ui.components.SheetSpec
import app.ytune.ui.components.Stepper
import app.ytune.ui.theme.P
import app.ytune.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val library by Graph.library.data.collectAsStateWithLifecycle()
    val sheets = LocalSheets.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheBytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        cacheBytes = withContext(Dispatchers.IO) { runCatching { StreamCache.sizeBytes(context) }.getOrDefault(0L) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader("SETTINGS")

        Section("Playback") {
            Block("Mode", if (settings.downloadWhileStreaming) "Everything you play is also saved for offline." else "Play from YouTube, save nothing automatically.") {
                Segmented(
                    options = StreamMode.entries.map { it.label },
                    selected = settings.mode.ordinal,
                    onSelect = { i -> Graph.settings.update { it.copy(mode = StreamMode.entries[i]) } },
                )
            }
            Block("Download strategy", settings.strategy.description) {
                Segmented(
                    options = DownloadStrategy.entries.map { it.label },
                    selected = settings.strategy.ordinal,
                    onSelect = { i -> Graph.settings.update { it.copy(strategy = DownloadStrategy.entries[i]) } },
                )
            }
            Line("Look-ahead", "Tracks pre-downloaded ahead of the one playing (progressive mode)") {
                Stepper(settings.lookahead, 1..5) { v -> Graph.settings.update { it.copy(lookahead = v) } }
            }
            Block("Audio quality", settings.quality.description) {
                Segmented(
                    options = AudioQuality.entries.map { it.label },
                    selected = settings.quality.ordinal,
                    onSelect = { i -> Graph.settings.update { it.copy(quality = AudioQuality.entries[i]) } },
                )
            }
            Line("Dot-matrix artwork", "Render cover art as dots on the player (tap the art to switch)") {
                NothingSwitch(settings.dotArtwork, { on -> Graph.settings.update { it.copy(dotArtwork = on) } })
            }
        }

        Section("Downloads") {
            Line("Wi-Fi only", "Hold downloads while on mobile data") {
                NothingSwitch(settings.wifiOnly, { on -> Graph.settings.update { it.copy(wifiOnly = on) } })
            }
            Line("Parallel downloads", "How many tracks download at the same time") {
                Stepper(settings.parallel, 1..4) { v -> Graph.settings.update { it.copy(parallel = v) } }
            }
            Line("Offline library", "${library.audio.size} tracks · ${formatBytes(Graph.library.totalBytes(library))}") {
                PillButton("Delete", {
                    sheets(
                        SheetSpec(
                            title = "Delete all downloads?",
                            subtitle = "${library.audio.size} files",
                            actions = listOf(
                                SheetAction("Delete everything", Ic.Delete, destructive = true) {
                                    Graph.downloads.cancelAll()
                                    Graph.library.deleteAllDownloads()
                                    Graph.toast("Downloads deleted")
                                },
                            ),
                        )
                    )
                }, enabled = library.audio.isNotEmpty())
            }
            Line("Stream cache", "${formatBytes(cacheBytes)} of 512 MB · makes replays instant") {
                PillButton("Clear", {
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { StreamCache.clear(context) } }
                        cacheBytes = withContext(Dispatchers.IO) { runCatching { StreamCache.sizeBytes(context) }.getOrDefault(0L) }
                        Graph.toast("Cache cleared")
                    }
                })
            }
        }

        Section("Controls") {
            Text(
                "Playback runs in a media session, so the notification, lock screen and any Bluetooth " +
                    "headset control it. On Nothing Ear: pinch / tap to play-pause, double to skip, " +
                    "triple to go back (whatever you mapped in the Nothing X app). Taking the buds " +
                    "out pauses, and pressing play with the app closed resumes your last queue.",
                style = Type.body,
                color = P.textDim,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        Section("About") {
            Text(
                "YTune ${BuildConfig.VERSION_NAME}\nExtraction: NewPipeExtractor (GPLv3) · Playback: AndroidX Media3\n" +
                    "Fonts: Doto, Space Grotesk, Space Mono (OFL)",
                style = Type.label,
                color = P.textFaint,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(18.dp))
    SectionLabel(title, Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
    Column(Modifier.fillMaxWidth(), content = content)
}

@Composable
private fun Block(title: String, description: String, control: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(title, style = Type.title, color = P.text)
        Spacer(Modifier.height(8.dp))
        control()
        Spacer(Modifier.height(6.dp))
        Text(description, style = Type.label, color = P.textDim)
    }
}

@Composable
private fun Line(title: String, description: String, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.title, color = P.text)
            Spacer(Modifier.height(2.dp))
            Text(description, style = Type.label, color = P.textDim)
        }
        Spacer(Modifier.width(12.dp))
        control()
    }
}
