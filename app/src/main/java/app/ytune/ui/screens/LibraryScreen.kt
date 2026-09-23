package app.ytune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ytune.Graph
import app.ytune.data.formatBytes
import app.ytune.download.DlStatus
import app.ytune.download.DlTask
import app.ytune.ui.Actions
import app.ytune.ui.AppViewModel
import app.ytune.ui.LibraryPage
import app.ytune.ui.components.Artwork
import app.ytune.ui.components.DotProgressBar
import app.ytune.ui.components.DotRing
import app.ytune.ui.components.EmptyState
import app.ytune.ui.components.Ic
import app.ytune.ui.components.IconBtn
import app.ytune.ui.components.LocalDl
import app.ytune.ui.components.LocalSheets
import app.ytune.ui.components.PillButton
import app.ytune.ui.components.PillStyle
import app.ytune.ui.components.PlaylistRow
import app.ytune.ui.components.ScreenHeader
import app.ytune.ui.components.Segmented
import app.ytune.ui.components.SheetAction
import app.ytune.ui.components.TrackRow
import app.ytune.ui.theme.P
import app.ytune.ui.theme.Type

@Composable
fun LibraryScreen(app: AppViewModel) {
    val library by Graph.library.data.collectAsStateWithLifecycle()
    val tasks by Graph.downloads.tasks.collectAsStateWithLifecycle()
    val player by Graph.player.state.collectAsStateWithLifecycle()
    val sheets = LocalSheets.current
    val dl = LocalDl.current
    val downloaded = Graph.library.downloadedTracks(library)
    val taskList = tasks.values.toList().asReversed()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("LIBRARY") {
            Text(
                formatBytes(Graph.library.totalBytes(library)),
                style = Type.label,
                color = P.textDim,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Segmented(
            options = listOf(
                "SONGS ${downloaded.size}",
                "PLAYLISTS ${library.playlists.size}",
                "QUEUE ${taskList.count { it.isActive }}",
            ),
            selected = app.libraryPage.ordinal,
            onSelect = { app.libraryPage = LibraryPage.entries[it] },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))

        when (app.libraryPage) {
            LibraryPage.SONGS -> {
                if (downloaded.isEmpty()) {
                    EmptyState(
                        "OFFLINE: 0",
                        "Download songs from search, or switch on STREAM + DL and your library fills up as you listen.",
                    )
                } else {
                    val tracks = downloaded.map { it.first }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                        item(key = "actions") {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                PillButton("Play all", { Graph.player.playAll(tracks) }, icon = Ic.PlaylistPlay, style = PillStyle.Filled)
                                PillButton("Shuffle", { Graph.player.playAll(tracks, shuffle = true) }, icon = Ic.Shuffle)
                            }
                        }
                        items(downloaded, key = { it.first.id }) { (track, audio) ->
                            TrackRow(
                                track = track,
                                badge = dl.badge(track.id),
                                artwork = Graph.library.artworkFor(track),
                                isCurrent = player.current?.id == track.id,
                                isPlaying = player.isPlaying,
                                onClick = { Graph.player.playAll(tracks, tracks.indexOf(track)) },
                                onMore = {
                                    sheets(
                                        Actions.trackSheet(track).let { spec ->
                                            spec.copy(subtitle = "${track.artist} · ${formatBytes(audio.sizeBytes)} · ${audio.bitrate / 1000} kbps")
                                        }
                                    )
                                },
                            )
                        }
                    }
                }
            }

            LibraryPage.PLAYLISTS -> {
                if (library.playlists.isEmpty()) {
                    EmptyState("NO PLAYLISTS", "Open a playlist or album and tap the bookmark to keep it here.")
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                        items(library.playlists, key = { it.ref.url }) { saved ->
                            val offline = saved.trackIds.count { it in library.audio }
                            PlaylistRow(
                                ref = saved.ref.copy(count = saved.trackIds.size.toLong()),
                                extra = "$offline OFFLINE",
                                onClick = { app.openPlaylist(saved.ref) },
                                onAddAll = {
                                    val tracks = Graph.library.tracksOf(saved)
                                    Graph.player.enqueue(tracks)
                                    Graph.toast("Added ${tracks.size} tracks to the queue")
                                },
                                onMore = { sheets(Actions.playlistSheet(saved.ref) { app.openPlaylist(saved.ref) }) },
                            )
                        }
                    }
                }
            }

            LibraryPage.DOWNLOADS -> {
                if (taskList.isEmpty()) {
                    EmptyState("QUEUE EMPTY", "Downloads you start show up here with their progress.")
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                        item(key = "actions") {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                PillButton("Clear finished", { Graph.downloads.clearFinished() }, icon = Ic.Check)
                                if (taskList.any { it.isActive }) {
                                    PillButton("Cancel all", { Graph.downloads.cancelAll() }, icon = Ic.Close)
                                }
                            }
                        }
                        items(taskList, key = { it.track.id }) { task ->
                            DownloadRow(task, onMore = {
                                sheets(
                                    Actions.trackSheet(
                                        task.track,
                                        extra = listOf(
                                            SheetAction("Remove from list", Ic.Close) { Graph.downloads.remove(task.track.id) },
                                        ),
                                    )
                                )
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(task: DlTask, onMore: () -> Unit) {
    val status = when (task.status) {
        DlStatus.RUNNING -> if (task.total > 0) {
            "${(task.progress * 100).toInt()}%  ·  ${formatBytes(task.bytes)} / ${formatBytes(task.total)}"
        } else {
            "DOWNLOADING  ·  ${formatBytes(task.bytes)}"
        }
        DlStatus.QUEUED -> if (task.auto) "QUEUED  ·  STREAM + DL" else "QUEUED"
        DlStatus.WAITING_NETWORK -> if (Graph.settings.current.wifiOnly) "WAITING FOR WI-FI" else "WAITING FOR NETWORK"
        DlStatus.DONE -> "SAVED  ·  ${formatBytes(task.total)}"
        DlStatus.FAILED -> "FAILED  ·  ${task.error ?: "unknown error"}"
        DlStatus.CANCELED -> "CANCELED"
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            Artwork(task.track.thumbnail, Modifier.size(52.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(task.track.title, style = Type.title, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                status.uppercase(),
                style = Type.label,
                color = when (task.status) {
                    DlStatus.FAILED -> P.saveRed
                    DlStatus.DONE -> P.saveGreen
                    DlStatus.RUNNING -> P.saveColor(task.progress)
                    else -> P.textDim
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (task.status == DlStatus.RUNNING) {
                DotProgressBar(
                    progress = task.progress,
                    activeColor = P.saveColor(task.progress),
                    height = 12.dp,
                    spacing = 5.dp,
                    radius = 1.3.dp,
                    showHead = false,
                )
            }
        }
        when (task.status) {
            DlStatus.RUNNING, DlStatus.QUEUED, DlStatus.WAITING_NETWORK -> {
                if (task.status != DlStatus.RUNNING) {
                    DotRing(0f, Modifier.size(16.dp), spinning = true, color = P.saveRed)
                }
                IconBtn(Ic.Close, { Graph.downloads.cancel(task.track.id) }, tint = P.textDim, contentDescription = "Cancel")
            }
            DlStatus.FAILED, DlStatus.CANCELED -> {
                IconBtn(Ic.Refresh, { Graph.downloads.retry(task.track.id) }, tint = P.text, contentDescription = "Retry")
            }
            DlStatus.DONE -> {
                IconBtn(Ic.PlaylistPlay, { Graph.player.playNow(task.track) }, tint = P.text, contentDescription = "Play")
            }
        }
        IconBtn(Ic.More, onMore, tint = P.textDim, contentDescription = "More")
    }
}
