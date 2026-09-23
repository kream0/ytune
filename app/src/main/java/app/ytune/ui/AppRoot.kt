package app.ytune.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ytune.Graph
import app.ytune.data.PlaylistRef
import app.ytune.playback.PlayerUiState
import app.ytune.ui.components.Artwork
import app.ytune.ui.components.DlSnapshot
import app.ytune.ui.components.DotGlyphs
import app.ytune.ui.components.DotIcon
import app.ytune.ui.components.DotProgressBar
import app.ytune.ui.components.LocalDl
import app.ytune.ui.components.LocalSheets
import app.ytune.ui.components.SheetHost
import app.ytune.ui.components.SheetSpec
import app.ytune.ui.screens.LibraryScreen
import app.ytune.ui.screens.NowPlayingScreen
import app.ytune.ui.screens.PlaylistScreen
import app.ytune.ui.screens.SearchScreen
import app.ytune.ui.screens.SettingsScreen
import app.ytune.ui.theme.P
import app.ytune.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

@Composable
fun AppRoot(app: AppViewModel) {
    val player by Graph.player.state.collectAsStateWithLifecycle()
    val library by Graph.library.data.collectAsStateWithLifecycle()
    val tasks by Graph.downloads.tasks.collectAsStateWithLifecycle()
    val dl = remember(library.audio, tasks) { DlSnapshot(library.audio.keys, tasks) }
    var sheet by remember { mutableStateOf<SheetSpec?>(null) }
    val openSheet: (SheetSpec) -> Unit = remember { { sheet = it } }

    CompositionLocalProvider(LocalDl provides dl, LocalSheets provides openSheet) {
        Box(Modifier.fillMaxSize().background(P.background)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when (app.tab) {
                        Tab.SEARCH -> SearchScreen(onOpenPlaylist = app::openPlaylist)
                        Tab.LIBRARY -> LibraryScreen(app)
                        Tab.SETTINGS -> SettingsScreen()
                    }

                    var lastPlaylist by remember { mutableStateOf<PlaylistRef?>(null) }
                    app.playlist?.let { lastPlaylist = it }
                    AnimatedVisibility(
                        visible = app.playlist != null,
                        enter = slideInHorizontally { it / 3 } + fadeIn(),
                        exit = slideOutHorizontally { it / 3 } + fadeOut(),
                    ) {
                        lastPlaylist?.let { ref -> PlaylistScreen(ref, onBack = { app.playlist = null }) }
                    }
                }
                if (player.current != null) {
                    MiniPlayer(player, onOpen = { app.nowPlaying = true })
                }
                BottomNav(
                    tab = app.tab,
                    activeDownloads = tasks.values.count { it.isActive },
                    onTab = app::selectTab,
                )
            }

            AnimatedVisibility(
                visible = app.nowPlaying,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                NowPlayingScreen(onClose = { app.nowPlaying = false })
            }

            ToastHost(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 6.dp))
            SheetHost(sheet) { sheet = null }
        }
    }

    BackHandler(enabled = app.playlist != null && !app.nowPlaying && sheet == null) { app.playlist = null }
}

@Composable
private fun MiniPlayer(state: PlayerUiState, onOpen: () -> Unit) {
    val track = state.current ?: return
    val progress by Graph.player.progress.collectAsStateWithLifecycle()
    val fraction = if (state.durationMs > 0) progress.positionMs.toFloat() / state.durationMs else 0f
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(P.surfaceHigh)
            .clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(Graph.library.artworkFor(track), Modifier.size(44.dp), corner = 12.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(track.title, style = Type.title, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    track.artist.uppercase(),
                    style = Type.label,
                    color = P.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable { Graph.player.togglePlay() },
                contentAlignment = Alignment.Center,
            ) {
                DotIcon(
                    if (state.playWhenReady) DotGlyphs.PAUSE else DotGlyphs.PLAY,
                    P.text,
                    Modifier.size(18.dp),
                )
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable { Graph.player.next() },
                contentAlignment = Alignment.Center,
            ) {
                DotIcon(DotGlyphs.NEXT, P.textDim, Modifier.size(16.dp))
            }
        }
        DotProgressBar(
            progress = fraction,
            modifier = Modifier.padding(horizontal = 14.dp),
            height = 14.dp,
            spacing = 5.dp,
            radius = 1.2.dp,
            showHead = false,
        )
    }
}

@Composable
private fun BottomNav(tab: Tab, activeDownloads: Int, onTab: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { t ->
            val selected = t == tab
            Column(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onTab(t) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (selected) P.accent else Color.Transparent),
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.label, style = Type.labelBold, color = if (selected) P.text else P.textFaint)
                    if (t == Tab.LIBRARY && activeDownloads > 0) {
                        Spacer(Modifier.width(5.dp))
                        Text("$activeDownloads", style = Type.labelBold, color = P.accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToastHost(modifier: Modifier = Modifier) {
    var message by remember { mutableStateOf<String?>(null) }
    var last by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        Graph.messages.collectLatest {
            last = it
            message = it
            delay(2600)
            message = null
        }
    }
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Row(
            Modifier
                .padding(horizontal = 24.dp)
                .clip(CircleShape)
                .background(if (P.isDark) Color(0xFF1E1E1E) else Color(0xFF111111))
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(P.accent))
            Spacer(Modifier.width(10.dp))
            Text(last, style = Type.label, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

