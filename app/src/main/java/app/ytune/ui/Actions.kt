package app.ytune.ui

import app.ytune.Graph
import app.ytune.data.PlaylistRef
import app.ytune.data.Track
import app.ytune.ui.components.Ic
import app.ytune.ui.components.SheetAction
import app.ytune.ui.components.SheetSpec
import app.ytune.yt.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** User-level actions shared by several screens. */
object Actions {

    fun download(tracks: List<Track>) {
        val added = Graph.downloads.enqueue(tracks)
        Graph.toast(
            when {
                added == 0 -> "Already downloaded or queued"
                added == 1 -> "Downloading 1 track"
                else -> "Downloading $added tracks"
            }
        )
    }

    fun playVideo(videoId: String) {
        Graph.toast("Loading…")
        Graph.scope.launch {
            val track = runCatching {
                withContext(Dispatchers.IO) { YouTube.fetchTrack(videoId, Graph.settings.current.quality) }
            }.getOrElse {
                Graph.toast("Couldn't load video: ${it.message}")
                return@launch
            }
            Graph.player.playNow(track)
        }
    }

    /** Loads a whole playlist (falling back to a saved copy when offline) and hands it to [block]. */
    private fun withPlaylist(ref: PlaylistRef, block: (PlaylistRef, List<Track>) -> Unit) {
        Graph.toast("Loading “${ref.title}”…")
        Graph.scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { YouTube.loadWholePlaylist(ref.url) } }
            val (header, tracks) = result.getOrNull()
                ?: Graph.library.savedPlaylist(ref.url)?.let { it.ref to Graph.library.tracksOf(it) }
                ?: run {
                    Graph.toast("Couldn't load playlist: ${result.exceptionOrNull()?.message}")
                    return@launch
                }
            if (tracks.isEmpty()) {
                Graph.toast("That playlist is empty")
                return@launch
            }
            block(header.copy(isAlbum = ref.isAlbum, thumbnail = header.thumbnail ?: ref.thumbnail), tracks)
        }
    }

    fun playPlaylist(ref: PlaylistRef, shuffle: Boolean = false) = withPlaylist(ref) { _, tracks ->
        Graph.player.playAll(tracks, 0, shuffle)
        Graph.toast("${if (shuffle) "Shuffling" else "Playing"} ${tracks.size} tracks")
    }

    fun queuePlaylist(ref: PlaylistRef) = withPlaylist(ref) { _, tracks ->
        Graph.player.enqueue(tracks)
        Graph.toast("Added ${tracks.size} tracks to the queue")
    }

    fun downloadPlaylist(ref: PlaylistRef) = withPlaylist(ref) { header, tracks ->
        Graph.library.savePlaylist(header, tracks)
        download(tracks)
    }

    fun savePlaylist(ref: PlaylistRef) = withPlaylist(ref) { header, tracks ->
        Graph.library.savePlaylist(header, tracks)
        Graph.toast("Saved to library")
    }

    fun trackSheet(track: Track, extra: List<SheetAction> = emptyList()): SheetSpec {
        val downloaded = Graph.library.isDownloaded(track.id)
        val actions = buildList {
            add(SheetAction("Play now", Ic.PlaylistPlay) { Graph.player.playNow(track) })
            add(SheetAction("Play next", Ic.PlayNext) {
                Graph.player.playNext(listOf(track))
                Graph.toast("Playing next")
            })
            add(SheetAction("Add to queue", Ic.Queue) {
                Graph.player.enqueue(listOf(track))
                Graph.toast("Added to queue")
            })
            if (downloaded) {
                add(SheetAction("Delete download", Ic.Delete, destructive = true) {
                    Graph.library.deleteDownload(track.id)
                    Graph.toast("Download deleted")
                })
            } else {
                add(SheetAction("Download", Ic.Download) { download(listOf(track)) })
            }
            addAll(extra)
        }
        return SheetSpec(title = track.title, subtitle = track.artist, actions = actions)
    }

    fun playlistSheet(ref: PlaylistRef, onOpen: () -> Unit): SheetSpec {
        val saved = Graph.library.savedPlaylist(ref.url) != null
        return SheetSpec(
            title = ref.title,
            subtitle = listOf(if (ref.isAlbum) "Album" else "Playlist", ref.uploader).filter { it.isNotBlank() }.joinToString(" · "),
            actions = listOf(
                SheetAction("Open", Ic.PlaylistPlay, onClick = onOpen),
                SheetAction("Play all", Ic.PlaylistPlay) { playPlaylist(ref) },
                SheetAction("Shuffle play", Ic.Shuffle) { playPlaylist(ref, shuffle = true) },
                SheetAction("Add all to queue", Ic.PlaylistAdd) { queuePlaylist(ref) },
                SheetAction("Download all", Ic.Download) { downloadPlaylist(ref) },
                if (saved) {
                    SheetAction("Remove from library", Ic.Delete, destructive = true) {
                        Graph.library.removePlaylist(ref.url)
                        Graph.toast("Removed from library")
                    }
                } else {
                    SheetAction("Save to library", Ic.BookmarkBorder) { savePlaylist(ref) }
                },
            ),
        )
    }
}
