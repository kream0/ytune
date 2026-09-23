package app.ytune.ui

import app.ytune.Graph
import app.ytune.data.PlaylistRef
import app.ytune.data.Track
import app.ytune.data.isLocal
import app.ytune.ui.components.Ic
import app.ytune.ui.components.NameEntry
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
        if (ref.isLocal) {
            val saved = Graph.library.savedPlaylist(ref.url) ?: return
            val tracks = Graph.library.tracksOf(saved)
            if (tracks.isEmpty()) Graph.toast("That playlist is empty") else block(saved.ref, tracks)
            return
        }
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
        if (!ref.isLocal) Graph.library.savePlaylist(header, tracks)
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
            add(SheetAction("Add to playlist", Ic.PlaylistAdd, next = { addToPlaylistSheet(listOf(track)) }))
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
        if (ref.isLocal) return myPlaylistSheet(ref, onOpen)
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

    // ------------------------------------------------------------------ your own playlists

    fun tracksLabel(n: Int) = if (n == 1) "1 track" else "$n tracks"

    /** Menu for one of your playlists; [onOpen] is null when it's already open. */
    fun myPlaylistSheet(ref: PlaylistRef, onOpen: (() -> Unit)?): SheetSpec = SheetSpec(
        title = ref.title,
        subtitle = "My playlist · ${tracksLabel(ref.count.coerceAtLeast(0).toInt())}",
        actions = listOfNotNull(
            onOpen?.let { SheetAction("Open", Ic.PlaylistPlay, onClick = it) },
            SheetAction("Play all", Ic.PlaylistPlay) { playPlaylist(ref) },
            SheetAction("Shuffle play", Ic.Shuffle) { playPlaylist(ref, shuffle = true) },
            SheetAction("Add all to queue", Ic.PlaylistAdd) { queuePlaylist(ref) },
            SheetAction("Download all", Ic.Download) { downloadPlaylist(ref) },
            SheetAction("Rename", Ic.Edit, next = { renamePlaylistSheet(ref) }),
            SheetAction("Delete playlist", Ic.Delete, destructive = true, next = { deletePlaylistsSheet(listOf(ref)) }),
        ),
    )

    /** Pick one of your playlists (or make a new one) for [tracks]; [onDone] runs once they're in. */
    fun addToPlaylistSheet(
        tracks: List<Track>,
        title: String = "Add to playlist",
        exclude: String? = null,
        onDone: (PlaylistRef) -> Unit = {},
    ): SheetSpec {
        val targets = Graph.library.localPlaylists().filter { it.ref.url != exclude }
        return SheetSpec(
            title = title,
            subtitle = tracksLabel(tracks.size),
            actions = buildList {
                add(SheetAction("New playlist", Ic.Add, next = { newPlaylistSheet(tracks, onDone) }))
                targets.forEach { p ->
                    add(
                        SheetAction("${p.ref.title}  ·  ${p.trackIds.size}", Ic.PlaylistPlay) {
                            val added = Graph.library.addToPlaylist(p.ref.url, tracks)
                            Graph.toast(
                                when (added) {
                                    0 -> "Already in “${p.ref.title}”"
                                    else -> "Added ${tracksLabel(added)} to “${p.ref.title}”"
                                }
                            )
                            onDone(p.ref)
                        }
                    )
                }
            },
        )
    }

    fun newPlaylistSheet(tracks: List<Track> = emptyList(), onDone: (PlaylistRef) -> Unit = {}): SheetSpec = SheetSpec(
        title = "New playlist",
        subtitle = if (tracks.isEmpty()) null else tracksLabel(tracks.size),
        content = {
            NameEntry(initial = "", placeholder = "Playlist name", confirm = "Create") { name ->
                val ref = Graph.library.createPlaylist(name, tracks)
                Graph.toast(if (tracks.isEmpty()) "Created “$name”" else "Created “$name” · ${tracksLabel(tracks.distinctBy { it.id }.size)}")
                onDone(ref)
            }
        },
    )

    fun renamePlaylistSheet(ref: PlaylistRef): SheetSpec = SheetSpec(
        title = "Rename playlist",
        content = {
            NameEntry(initial = ref.title, placeholder = "Playlist name", confirm = "Save") { name ->
                Graph.library.renamePlaylist(ref.url, name)
            }
        },
    )

    fun deletePlaylistsSheet(refs: List<PlaylistRef>, onDone: () -> Unit = {}): SheetSpec {
        val mine = refs.count { it.isLocal }
        return confirmSheet(
            title = if (refs.size == 1) "Delete “${refs[0].title}”?" else "Delete ${refs.size} playlists?",
            subtitle = if (mine > 0) "Songs you downloaded stay in your library" else "They can be saved again from search",
            confirm = "Delete",
        ) {
            Graph.library.removePlaylists(refs.map { it.url }.toSet())
            Graph.toast(if (refs.size == 1) "Playlist deleted" else "${refs.size} playlists deleted")
            onDone()
        }
    }

    fun confirmSheet(title: String, subtitle: String?, confirm: String, onConfirm: () -> Unit): SheetSpec = SheetSpec(
        title = title,
        subtitle = subtitle,
        actions = listOf(
            SheetAction(confirm, Ic.Delete, destructive = true, onClick = onConfirm),
            SheetAction("Cancel", Ic.Close),
        ),
    )

    // ------------------------------------------------------------------ multi-select

    /**
     * The shared menu for several selected songs, wherever they were picked. [extra] adds the
     * list's own actions (remove from playlist / queue…); [onDone] ends the selection.
     */
    fun selectionSheet(
        tracks: List<Track>,
        title: String = "${tracksLabel(tracks.size)} selected",
        subtitle: String? = null,
        extra: List<SheetAction> = emptyList(),
        onDone: () -> Unit,
    ): SheetSpec {
        val downloaded = tracks.filter { Graph.library.isDownloaded(it.id) }
        return SheetSpec(
            title = title,
            subtitle = subtitle,
            actions = buildList {
                if (tracks.isEmpty()) {
                    addAll(extra)
                    return@buildList
                }
                add(SheetAction("Play", Ic.PlaylistPlay) {
                    Graph.player.playAll(tracks)
                    onDone()
                })
                add(SheetAction("Play next", Ic.PlayNext) {
                    Graph.player.playNext(tracks)
                    Graph.toast("Playing next: ${tracksLabel(tracks.size)}")
                    onDone()
                })
                add(SheetAction("Add to queue", Ic.Queue) {
                    Graph.player.enqueue(tracks)
                    Graph.toast("Added ${tracksLabel(tracks.size)} to the queue")
                    onDone()
                })
                add(SheetAction("Add to playlist", Ic.PlaylistAdd, next = { addToPlaylistSheet(tracks) { onDone() } }))
                if (downloaded.size < tracks.size) {
                    add(SheetAction("Download", Ic.Download) {
                        download(tracks)
                        onDone()
                    })
                }
                addAll(extra)
                if (downloaded.isNotEmpty()) {
                    add(
                        SheetAction(
                            if (downloaded.size == 1) "Delete download" else "Delete ${downloaded.size} downloads",
                            Ic.Delete,
                            destructive = true,
                            next = {
                                confirmSheet(
                                    title = if (downloaded.size == 1) "Delete this download?" else "Delete ${downloaded.size} downloads?",
                                    subtitle = "They stay in your playlists and can still stream",
                                    confirm = "Delete",
                                ) {
                                    downloaded.forEach { Graph.library.deleteDownload(it.id) }
                                    Graph.toast(if (downloaded.size == 1) "Download deleted" else "${downloaded.size} downloads deleted")
                                    onDone()
                                }
                            },
                        )
                    )
                }
            },
        )
    }
}
