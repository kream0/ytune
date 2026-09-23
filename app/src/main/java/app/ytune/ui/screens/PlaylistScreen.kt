package app.ytune.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.ytune.Graph
import app.ytune.data.PlaylistRef
import app.ytune.data.Track
import app.ytune.data.formatDuration
import app.ytune.ui.Actions
import app.ytune.ui.components.Artwork
import app.ytune.ui.components.DotLoader
import app.ytune.ui.components.IconBtn
import app.ytune.ui.components.Ic
import app.ytune.ui.components.LocalDl
import app.ytune.ui.components.LocalSheets
import app.ytune.ui.components.PillButton
import app.ytune.ui.components.PillStyle
import app.ytune.ui.components.SectionLabel
import app.ytune.ui.components.TrackRow
import app.ytune.ui.theme.P
import app.ytune.ui.theme.Type
import app.ytune.yt.YouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaylistUi(
    val ref: PlaylistRef,
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val offline: Boolean = false,
)

class PlaylistViewModel(private val initial: PlaylistRef) : ViewModel() {
    private val _ui = MutableStateFlow(PlaylistUi(initial))
    val ui: StateFlow<PlaylistUi> = _ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        _ui.value = PlaylistUi(initial, loading = true)
        viewModelScope.launch {
            try {
                val pager = withContext(Dispatchers.IO) { YouTube.playlist(initial.url) }
                val all = LinkedHashMap<String, Track>()
                while (pager.hasMore && all.size < MAX_TRACKS) {
                    val page = withContext(Dispatchers.IO) { pager.loadNext() }
                    page.forEach { all.putIfAbsent(it.id, it) }
                    val header = pager.header
                    _ui.value = PlaylistUi(
                        ref = header.copy(
                            isAlbum = initial.isAlbum,
                            thumbnail = header.thumbnail ?: initial.thumbnail,
                            title = header.title.takeUnless { it == "Playlist" } ?: initial.title,
                        ),
                        tracks = all.values.toList(),
                        loading = pager.hasMore && all.size < MAX_TRACKS && page.isNotEmpty(),
                    )
                    if (page.isEmpty()) break
                }
                _ui.value = _ui.value.copy(loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val saved = Graph.library.savedPlaylist(initial.url)
                _ui.value = if (saved != null) {
                    PlaylistUi(saved.ref, Graph.library.tracksOf(saved), loading = false, offline = true)
                } else {
                    _ui.value.copy(loading = false, error = e.message ?: "Couldn't load playlist")
                }
            }
        }
    }

    companion object {
        const val MAX_TRACKS = 1000
    }
}

@Composable
fun PlaylistScreen(ref: PlaylistRef, onBack: () -> Unit) {
    val vm: PlaylistViewModel = viewModel(key = "playlist:${ref.url}") { PlaylistViewModel(ref) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val player by Graph.player.state.collectAsStateWithLifecycle()
    val library by Graph.library.data.collectAsStateWithLifecycle()
    val sheets = LocalSheets.current
    val dl = LocalDl.current
    val saved = library.playlists.any { it.ref.url == ref.url }
    val downloadedCount = ui.tracks.count { it.id in library.audio }
    val totalSec = ui.tracks.sumOf { it.durationSec }

    LazyColumn(
        Modifier.fillMaxSize().background(P.background).statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item(key = "top") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(Ic.Back, onBack, contentDescription = "Back")
                Spacer(Modifier.weight(1f))
                SectionLabel(if (ui.ref.isAlbum) "Album" else "Playlist")
                Spacer(Modifier.weight(1f))
                IconBtn(
                    if (saved) Ic.Bookmark else Ic.BookmarkBorder,
                    onClick = {
                        if (saved) {
                            Graph.library.removePlaylist(ref.url)
                            Graph.toast("Removed from library")
                        } else if (ui.tracks.isNotEmpty()) {
                            Graph.library.savePlaylist(ui.ref, ui.tracks)
                            Graph.toast("Saved to library")
                        }
                    },
                    tint = if (saved) P.accent else P.text,
                    contentDescription = "Save to library",
                )
            }
        }

        item(key = "hero") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Artwork(ui.ref.thumbnail, Modifier.size(210.dp), corner = 24.dp)
                Spacer(Modifier.height(18.dp))
                Text(ui.ref.title, style = Type.titleLarge, color = P.text, textAlign = TextAlign.Center)
                if (ui.ref.uploader.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(ui.ref.uploader.uppercase(), style = Type.label, color = P.textDim)
                }
                Spacer(Modifier.height(8.dp))
                val stats = buildList {
                    add("${ui.tracks.size}${if (ui.loading) "+" else ""} TRACKS")
                    if (totalSec > 0) add(formatDuration(totalSec))
                    if (downloadedCount > 0) add("$downloadedCount OFFLINE")
                    if (ui.offline) add("SAVED COPY")
                }
                Text(stats.joinToString("  ·  "), style = Type.label, color = P.textFaint)
            }
        }

        item(key = "actions") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                val enabled = ui.tracks.isNotEmpty()
                PillButton("Play", { Graph.player.playAll(ui.tracks) }, icon = Ic.PlaylistPlay, style = PillStyle.Accent, enabled = enabled)
                PillButton("Shuffle", { Graph.player.playAll(ui.tracks, shuffle = true) }, icon = Ic.Shuffle, enabled = enabled)
                IconBtn(Ic.PlaylistAdd, {
                    if (enabled) {
                        Graph.player.enqueue(ui.tracks)
                        Graph.toast("Added ${ui.tracks.size} tracks to the queue")
                    }
                }, bordered = true, size = 44.dp, contentDescription = "Add all to queue")
                IconBtn(Ic.Download, {
                    if (enabled) {
                        Graph.library.savePlaylist(ui.ref, ui.tracks)
                        Actions.download(ui.tracks)
                    }
                }, bordered = true, size = 44.dp, contentDescription = "Download all")
            }
        }

        if (ui.tracks.isEmpty() && ui.loading) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { DotLoader() }
            }
        }

        ui.error?.let { error ->
            item(key = "error") {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, style = Type.body, color = P.textDim, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    PillButton("Retry", { vm.load() }, icon = Ic.Refresh)
                }
            }
        }

        itemsIndexed(ui.tracks, key = { _, t -> t.id }) { index, track ->
            TrackRow(
                track = track,
                badge = dl.badge(track.id),
                artwork = Graph.library.artworkFor(track),
                isCurrent = player.current?.id == track.id,
                isPlaying = player.isPlaying,
                onClick = { Graph.player.playAll(ui.tracks, index) },
                onMore = { sheets(Actions.trackSheet(track)) },
            )
        }

        if (ui.tracks.isNotEmpty() && ui.loading) {
            item(key = "more") {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DotLoader()
                    Spacer(Modifier.size(12.dp))
                    Text("LOADING ${ui.tracks.size}…", style = Type.label, color = P.textDim)
                }
            }
        }
    }
}
