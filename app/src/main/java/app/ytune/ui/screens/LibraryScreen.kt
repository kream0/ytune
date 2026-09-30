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
import androidx.compose.runtime.LaunchedEffect
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
import app.ytune.tr
import app.ytune.trCount
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
import app.ytune.ui.components.SelectionBar
import app.ytune.ui.components.rememberSelection
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
    // One selection per page; switching pages drops it.
    val selection = rememberSelection<String>(app.libraryPage)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            ScreenHeader(tr("LIBRARY", "BIBLIOTHÈQUE")) {
                Text(
                    formatBytes(Graph.library.totalBytes(library)),
                    style = Type.label,
                    color = P.textDim,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Segmented(
                options = listOf(
                    tr("SONGS ${downloaded.size}", "TITRES ${downloaded.size}"),
                    "PLAYLISTS ${library.playlists.size}",
                    tr("QUEUE ${taskList.count { it.isActive }}", "FILE ${taskList.count { it.isActive }}"),
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
                            tr("OFFLINE: 0", "HORS LIGNE : 0"),
                            tr(
                                "Download songs from search, or switch on STREAM + DL and your library fills up as you listen.",
                                "Téléchargez des titres depuis la recherche, ou activez STREAM + DL pour remplir votre bibliothèque au fil de vos écoutes.",
                            ),
                        )
                    } else {
                        val tracks = downloaded.map { it.first }
                        LaunchedEffect(tracks) { selection.retain(tracks.map { it.id }) }
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = if (selection.active) 80.dp else 12.dp)) {
                            item(key = "actions") {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    PillButton(tr("Play all", "Tout lire"), { Graph.player.playAll(tracks) }, icon = Ic.PlaylistPlay, style = PillStyle.Filled)
                                    PillButton(tr("Shuffle", "Aléatoire"), { Graph.player.playAll(tracks, shuffle = true) }, icon = Ic.Shuffle)
                                }
                            }
                            items(downloaded, key = { it.first.id }) { (track, audio) ->
                                TrackRow(
                                    track = track,
                                    badge = dl.badge(track.id),
                                    artwork = Graph.library.artworkFor(track),
                                    isCurrent = player.current?.id == track.id,
                                    isPlaying = player.isPlaying,
                                    selected = selection.rowState(track.id),
                                    onLongClick = { selection.toggle(track.id) },
                                    onClick = {
                                        if (selection.active) selection.toggle(track.id)
                                        else Graph.player.playAll(tracks, tracks.indexOf(track))
                                    },
                                    onMore = {
                                        sheets(
                                            Actions.trackSheet(track).let { spec ->
                                                spec.copy(subtitle = "${track.artist} · ${formatBytes(audio.sizeBytes)} · ${audio.bitrate / 1000} ${tr("kbps", "kbit/s")}")
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
                        EmptyState(
                            tr("NO PLAYLISTS", "AUCUNE PLAYLIST"),
                            tr(
                                "Make your own, or open a YouTube playlist or album and tap the bookmark to keep it here.",
                                "Créez la vôtre, ou ouvrez une playlist ou un album YouTube et touchez le signet pour l'ajouter ici.",
                            ),
                            action = { PillButton(tr("New playlist", "Nouvelle playlist"), { sheets(Actions.newPlaylistSheet()) }, icon = Ic.Add, style = PillStyle.Filled) },
                        )
                    } else {
                        LaunchedEffect(library.playlists) { selection.retain(library.playlists.map { it.ref.url }) }
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = if (selection.active) 80.dp else 12.dp)) {
                            item(key = "actions") {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    PillButton(tr("New playlist", "Nouvelle playlist"), { sheets(Actions.newPlaylistSheet()) }, icon = Ic.Add, style = PillStyle.Filled)
                                }
                            }
                            items(library.playlists, key = { it.ref.url }) { saved ->
                                val offline = saved.trackIds.count { it in library.audio }
                                PlaylistRow(
                                    ref = saved.ref.copy(count = saved.trackIds.size.toLong()),
                                    extra = tr("$offline OFFLINE", "$offline HORS LIGNE"),
                                    selected = selection.rowState(saved.ref.url),
                                    onLongClick = { selection.toggle(saved.ref.url) },
                                    onClick = {
                                        if (selection.active) selection.toggle(saved.ref.url) else app.openPlaylist(saved.ref)
                                    },
                                    onAddAll = {
                                        val tracks = Graph.library.tracksOf(saved)
                                        Graph.player.enqueue(tracks)
                                    },
                                    onMore = { sheets(Actions.playlistSheet(saved.ref) { app.openPlaylist(saved.ref) }) },
                                )
                            }
                        }
                    }
                }

                LibraryPage.DOWNLOADS -> {
                    if (taskList.isEmpty()) {
                        EmptyState(
                            tr("QUEUE EMPTY", "FILE VIDE"),
                            tr("Downloads you start show up here with their progress.", "Les téléchargements lancés s'affichent ici avec leur progression."),
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                            item(key = "actions") {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    PillButton(tr("Clear finished", "Effacer les terminés"), { Graph.downloads.clearFinished() }, icon = Ic.Check)
                                    if (taskList.any { it.isActive }) {
                                        PillButton(tr("Cancel all", "Tout annuler"), { Graph.downloads.cancelAll() }, icon = Ic.Close)
                                    }
                                }
                            }
                            items(taskList, key = { it.track.id }) { task ->
                                DownloadRow(task, onMore = {
                                    sheets(
                                        Actions.trackSheet(
                                            task.track,
                                            extra = listOf(
                                                SheetAction(tr("Remove from list", "Retirer de la liste"), Ic.Close) { Graph.downloads.remove(task.track.id) },
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

        when (app.libraryPage) {
            LibraryPage.SONGS -> {
                val tracks = downloaded.map { it.first }
                val chosen = { tracks.filter { it.id in selection } }
                SelectionBar(
                    selection = selection,
                    total = tracks.size,
                    onSelectAll = { selection.toggleAll(tracks.map { it.id }) },
                    onAddToPlaylist = { sheets(Actions.addToPlaylistSheet(chosen()) { selection.clear() }) },
                    onMore = { sheets(Actions.selectionSheet(chosen()) { selection.clear() }) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            LibraryPage.PLAYLISTS -> {
                val chosen = { library.playlists.filter { it.ref.url in selection } }
                // Several playlists act like all their songs together, plus deleting the playlists.
                val songs = { chosen().flatMap { Graph.library.tracksOf(it, library) }.distinctBy { it.id } }
                SelectionBar(
                    selection = selection,
                    total = library.playlists.size,
                    onSelectAll = { selection.toggleAll(library.playlists.map { it.ref.url }) },
                    onAddToPlaylist = { sheets(Actions.addToPlaylistSheet(songs()) { selection.clear() }) },
                    onMore = {
                        val refs = chosen().map { it.ref }
                        val all = songs()
                        sheets(
                            Actions.selectionSheet(
                                all,
                                title = if (refs.size == 1) {
                                    refs[0].title
                                } else {
                                    trCount(refs.size, "playlist selected", "playlists selected", "playlist sélectionnée", "playlists sélectionnées")
                                },
                                subtitle = Actions.tracksLabel(all.size),
                                extra = listOf(
                                    SheetAction(
                                        if (refs.size == 1) {
                                            tr("Delete playlist", "Supprimer la playlist")
                                        } else {
                                            tr("Delete ${refs.size} playlists", "Supprimer ${refs.size} playlists")
                                        },
                                        Ic.Delete,
                                        destructive = true,
                                        next = { Actions.deletePlaylistsSheet(refs) { selection.clear() } },
                                    ),
                                ),
                            ) { selection.clear() }
                        )
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            LibraryPage.DOWNLOADS -> Unit
        }
    }
}

@Composable
private fun DownloadRow(task: DlTask, onMore: () -> Unit) {
    val status = when (task.status) {
        DlStatus.RUNNING -> if (task.total > 0) {
            "${(task.progress * 100).toInt()}%  ·  ${formatBytes(task.bytes)} / ${formatBytes(task.total)}"
        } else {
            tr("DOWNLOADING  ·  ${formatBytes(task.bytes)}", "TÉLÉCHARGEMENT  ·  ${formatBytes(task.bytes)}")
        }
        DlStatus.QUEUED -> if (task.auto) tr("QUEUED  ·  STREAM + DL", "EN ATTENTE  ·  STREAM + DL") else tr("QUEUED", "EN ATTENTE")
        DlStatus.WAITING_NETWORK -> if (Graph.settings.current.wifiOnly) {
            tr("WAITING FOR WI-FI", "EN ATTENTE DU WI-FI")
        } else {
            tr("WAITING FOR NETWORK", "EN ATTENTE DU RÉSEAU")
        }
        DlStatus.DONE -> tr("SAVED  ·  ${formatBytes(task.total)}", "TÉLÉCHARGÉ  ·  ${formatBytes(task.total)}")
        DlStatus.FAILED -> tr("FAILED  ·  ${task.error ?: "unknown error"}", "ÉCHEC  ·  ${task.error ?: "erreur inconnue"}")
        DlStatus.CANCELED -> tr("CANCELED", "ANNULÉ")
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
                IconBtn(Ic.Close, { Graph.downloads.cancel(task.track.id) }, tint = P.textDim, contentDescription = tr("Cancel", "Annuler"))
            }
            DlStatus.FAILED, DlStatus.CANCELED -> {
                IconBtn(Ic.Refresh, { Graph.downloads.retry(task.track.id) }, tint = P.text, contentDescription = tr("Retry", "Réessayer"))
            }
            DlStatus.DONE -> {
                IconBtn(Ic.PlaylistPlay, { Graph.player.playNow(task.track) }, tint = P.text, contentDescription = tr("Play", "Lire"))
            }
        }
        IconBtn(Ic.More, onMore, tint = P.textDim, contentDescription = tr("More", "Plus d'options"))
    }
}
