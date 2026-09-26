package app.ytune.ui.screens

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import app.ytune.glyph.GlyphLink
import app.ytune.glyph.GlyphStatus
import app.ytune.glyph.GlyphSupport
import app.ytune.glyph.GlyphTest
import app.ytune.playback.StreamCache
import app.ytune.ui.components.GlyphMatrixPreview
import app.ytune.ui.components.PillStyle
import app.ytune.update.UpdateState
import java.text.DateFormat
import java.util.Date
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
            Line("Autoplay suggestions", "When the queue runs out, keep playing YouTube's suggestions for the last song. Works when streaming and with STREAM + DL") {
                NothingSwitch(settings.autoplay, { on -> Graph.settings.update { it.copy(autoplay = on) } })
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

        Section("Glyph Matrix") {
            GlyphSection(settings.glyphMatrix)
        }

        Section("Updates") {
            UpdatesSection(settings.autoUpdate)
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
                    "Fonts: Doto, Space Grotesk, Space Mono (OFL) · Glyph Matrix SDK © Nothing",
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

@Composable
private fun GlyphSection(enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val matrix = GlyphSupport.matrixSize.takeIf { it > 0 } ?: 25
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        GlyphMatrixPreview(matrix, Modifier.size(150.dp))
    }
    if (!GlyphSupport.isSupported) {
        Text(
            "Preview only: the Glyph Matrix is on Nothing Phone (3) and Phone (4a) Pro. " +
                "This phone: ${Build.MANUFACTURER} ${Build.MODEL}.",
            style = Type.label,
            color = P.textDim,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        return
    }
    val status by GlyphLink.status.collectAsStateWithLifecycle()
    val testing by GlyphTest.running.collectAsStateWithLifecycle()

    Line("Scroll the title on the Glyph Matrix", "While music plays, the back of the phone shows title · artist and a progress bar") {
        NothingSwitch(enabled, { on -> Graph.settings.update { it.copy(glyphMatrix = on) } })
    }
    if (GlyphSupport.hasGlyphTouch) {
        Line("YTune Glyph Toy", "Add it to the Glyph Button carousel · long-press the Glyph Button to play / pause") {
            PillButton("Add", {
                if (!GlyphSupport.openToysManager(context)) {
                    Graph.toast("Open Settings › Glyph Interface › Glyph Toys and add YTune")
                }
            })
        }
    } else {
        Line(
            "Always-on Glyph Toy",
            "Also show it with the phone face down: Settings › Glyph Interface › Flip to Glyph › " +
                "Always-on Glyph Toy › YTune",
        ) {
            PillButton("Open", {
                if (!GlyphSupport.openToysManager(context)) {
                    runCatching {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    Graph.toast("Glyph Interface › Flip to Glyph › Always-on Glyph Toy › YTune")
                }
            })
        }
    }
    Line("Test the matrix", if (testing) "Look at the back of the phone…" else "Scrolls a test message for 8 seconds") {
        PillButton(if (testing) "Testing" else "Test", { scope.launch { GlyphTest.run(context) } }, enabled = !testing)
    }
    GlyphDiagnostics(status)
}

/** What the Glyph service told us, so a dark matrix can be explained (and reported). */
@Composable
private fun GlyphDiagnostics(s: GlyphStatus) {
    fun mark(ok: Boolean?) = when (ok) {
        true -> "OK"
        false -> "NO"
        null -> "–"
    }
    val (hint, problem) = when {
        s.error != null -> s.error to true
        s.serviceFound == false ->
            "Nothing's Glyph service isn't reachable. Update the phone (Settings › System › System update)." to true
        s.registered == false ->
            "The Glyph service refused YTune. Check that Glyph Interface is on and the phone is up to date." to true
        s.connected && s.appFrames + s.toyFrames > 0 ->
            "Connected and sending. If the matrix stays dark, check that Glyph Interface is on; " +
                "notifications and other Glyph effects take priority over apps." to false
        s.connected -> "Connected." to false
        s.linking -> "Waiting for Nothing's Glyph service to answer…" to false
        else -> "Tap Test, or play something, to connect." to false
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(
            "${GlyphSupport.deviceName} · ${GlyphSupport.model} · Android ${Build.VERSION.RELEASE}\n" +
                "Service ${mark(s.serviceFound)} · link ${mark(s.connected)} · access ${mark(s.registered)}\n" +
                "Frames: app ${s.appFrames} · toy ${s.toyFrames}" +
                (if (s.toyBound) " · toy active" else "") +
                (s.lastToyEvent?.let { " · last event $it" } ?: ""),
            style = Type.label,
            color = P.textFaint,
        )
        Spacer(Modifier.height(6.dp))
        Text(hint, style = Type.label, color = if (problem) P.saveRed else P.textDim)
    }
}

@Composable
private fun UpdatesSection(autoUpdate: Boolean) {
    val context = LocalContext.current
    val state by Graph.updater.state.collectAsStateWithLifecycle()
    val last = Graph.updater.lastChecked
    val status = when (val s = state) {
        UpdateState.Idle, UpdateState.UpToDate ->
            if (last > 0) "Up to date · checked ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))}"
            else "Not checked yet"
        UpdateState.Checking -> "Checking…"
        is UpdateState.Available -> "v${s.remote.versionName} available"
        is UpdateState.Downloading -> "Downloading v${s.remote.versionName} · ${(s.progress * 100).toInt()}%"
        is UpdateState.Ready -> "v${s.remote.versionName} ready to install"
        is UpdateState.Failed -> "Update check failed: ${s.message}"
    }
    Line("YTune ${Graph.updater.currentVersion}", status) {
        when (val s = state) {
            is UpdateState.Ready -> PillButton("Install", { (context as? Activity)?.let { Graph.updater.install(it) } }, style = PillStyle.Accent)
            is UpdateState.Available -> PillButton("Get", { Graph.updater.startDownload() })
            UpdateState.Checking, is UpdateState.Downloading -> Unit
            else -> PillButton("Check", { Graph.updater.check(manual = true) })
        }
    }
    Line("Auto-download updates", "When a new release is published, download it in the background, then ask before installing") {
        NothingSwitch(autoUpdate, { on -> Graph.settings.update { it.copy(autoUpdate = on) } })
    }
}
