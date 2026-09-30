package app.ytune.ui

import app.ytune.Graph
import app.ytune.album.AlbumResult
import app.ytune.data.PlaylistRef
import app.ytune.data.Track
import app.ytune.data.formatDuration
import app.ytune.data.isLocal
import app.ytune.tr
import app.ytune.trCount
import app.ytune.ui.components.Ic
import app.ytune.ui.components.NameEntry
import app.ytune.ui.components.SheetAction
import app.ytune.ui.components.SheetSpec
import app.ytune.yt.YouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** User-level actions shared by several screens. */
object Actions {

    fun download(tracks: List<Track>) {
        val added = Graph.downloads.enqueue(tracks)
        Graph.toast(
            when {
                added == 0 -> tr("Already downloaded or queued", "Déjà téléchargé ou en attente")
                added == 1 -> tr("Downloading 1 track", "Téléchargement d'un titre")
                else -> tr("Downloading $added tracks", "Téléchargement de $added titres")
            }
        )
    }

    fun playVideo(videoId: String) {
        Graph.toast(tr("Loading…", "Chargement…"))
        Graph.scope.launch {
            val track = runCatching {
                withContext(Dispatchers.IO) { YouTube.fetchTrack(videoId, Graph.settings.current.quality) }
            }.getOrElse {
                Graph.toast(tr("Couldn't load video: ${it.message}", "Impossible de charger la vidéo : ${it.message}"))
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
            if (tracks.isEmpty()) Graph.toast(tr("That playlist is empty", "Cette playlist est vide")) else block(saved.ref, tracks)
            return
        }
        Graph.toast(tr("Loading “${ref.title}”…", "Chargement de « ${ref.title} »…"))
        Graph.scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { YouTube.loadWholePlaylist(ref.url) } }
            val (header, tracks) = result.getOrNull()
                ?: Graph.library.savedPlaylist(ref.url)?.let { it.ref to Graph.library.tracksOf(it) }
                ?: run {
                    Graph.toast(
                        tr(
                            "Couldn't load playlist: ${result.exceptionOrNull()?.message}",
                            "Impossible de charger la playlist : ${result.exceptionOrNull()?.message}",
                        )
                    )
                    return@launch
                }
            if (tracks.isEmpty()) {
                Graph.toast(tr("That playlist is empty", "Cette playlist est vide"))
                return@launch
            }
            block(header.copy(isAlbum = ref.isAlbum, thumbnail = header.thumbnail ?: ref.thumbnail), tracks)
        }
    }

    fun playPlaylist(ref: PlaylistRef, shuffle: Boolean = false) = withPlaylist(ref) { _, tracks ->
        Graph.player.playAll(tracks, 0, shuffle)
        val count = tracksLabel(tracks.size)
        Graph.toast(if (shuffle) tr("Shuffling $count", "Lecture aléatoire de $count") else tr("Playing $count", "Lecture de $count"))
    }

    fun queuePlaylist(ref: PlaylistRef) = withPlaylist(ref) { _, tracks ->
        Graph.player.enqueue(tracks)
    }

    fun downloadPlaylist(ref: PlaylistRef) = withPlaylist(ref) { header, tracks ->
        if (!ref.isLocal) Graph.library.savePlaylist(header, tracks)
        download(tracks)
    }

    fun savePlaylist(ref: PlaylistRef) = withPlaylist(ref) { header, tracks ->
        Graph.library.savePlaylist(header, tracks)
        Graph.toast(
            tr(
                "Saved to library",
                if (ref.isAlbum) "Album enregistré dans la bibliothèque" else "Playlist enregistrée dans la bibliothèque",
            )
        )
    }

    fun trackSheet(track: Track, extra: List<SheetAction> = emptyList()): SheetSpec {
        val downloaded = Graph.library.isDownloaded(track.id)
        val actions = buildList {
            add(SheetAction(tr("Play now", "Lire maintenant"), Ic.PlaylistPlay) { Graph.player.playNow(track) })
            add(SheetAction(tr("Add next in queue", "Lire ensuite"), Ic.PlayNext) {
                if (Graph.player.state.value.current?.id == track.id) {
                    Graph.toast(tr("That's the song playing now", "Ce titre est déjà en cours de lecture"))
                } else {
                    Graph.player.playNext(listOf(track))
                    Graph.toast(tr("Plays after the current song", "Sera lu après le titre en cours"))
                }
            })
            add(SheetAction(tr("Add to queue", "Ajouter à la file"), Ic.Queue) {
                Graph.player.enqueue(listOf(track))
            })
            add(
                SheetAction(
                    tr("Add to playlist", "Ajouter à une playlist"),
                    Ic.PlaylistAdd,
                    next = { addToPlaylistSheet(listOf(track)) },
                )
            )
            add(SheetAction(tr("Go to album", "Aller à l'album"), Ic.Album) { goToAlbum(track) })
            if (downloaded) {
                add(SheetAction(tr("Delete download", "Supprimer le téléchargement"), Ic.Delete, destructive = true) {
                    Graph.library.deleteDownload(track.id)
                    Graph.toast(tr("Download deleted", "Téléchargement supprimé"))
                })
            } else {
                add(SheetAction(tr("Download", "Télécharger"), Ic.Download) { download(listOf(track)) })
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
                SheetAction(tr("Open", "Ouvrir"), Ic.PlaylistPlay, onClick = onOpen),
                SheetAction(tr("Play all", "Tout lire"), Ic.PlaylistPlay) { playPlaylist(ref) },
                SheetAction(tr("Shuffle play", "Lecture aléatoire"), Ic.Shuffle) { playPlaylist(ref, shuffle = true) },
                SheetAction(tr("Add all to queue", "Tout ajouter à la file"), Ic.PlaylistAdd) { queuePlaylist(ref) },
                SheetAction(tr("Download all", "Tout télécharger"), Ic.Download) { downloadPlaylist(ref) },
                if (saved) {
                    SheetAction(tr("Remove from library", "Retirer de la bibliothèque"), Ic.Delete, destructive = true) {
                        Graph.library.removePlaylist(ref.url)
                        Graph.toast(
                            tr(
                                "Removed from library",
                                if (ref.isAlbum) "Album retiré de la bibliothèque" else "Playlist retirée de la bibliothèque",
                            )
                        )
                    }
                } else {
                    SheetAction(tr("Save to library", "Enregistrer dans la bibliothèque"), Ic.BookmarkBorder) { savePlaylist(ref) }
                },
            ),
        )
    }

    // ------------------------------------------------------------------ go to album

    /**
     * Finds the album [track] is on and opens it, like Spotify's "Go to album". When YouTube only
     * has it as one long video, offers that instead.
     */
    fun goToAlbum(track: Track) {
        Graph.toast(tr("Looking for the album…", "Recherche de l'album…"))
        Graph.scope.launch {
            val result = try {
                Graph.albums.find(track)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Graph.toast(
                    tr(
                        "Couldn't look up the album (${e.message ?: "offline?"})",
                        "Impossible de rechercher l'album (${e.message ?: "hors ligne ?"})",
                    )
                )
                return@launch
            }
            when (result) {
                is AlbumResult.Options ->
                    if (result.options.size == 1) Nav.openPlaylist(result.options[0].ref)
                    else Nav.showSheet(albumChoices(result))
                is AlbumResult.FullVideo -> {
                    val video = result.video
                    Nav.showSheet(
                        SheetSpec(
                            title = result.album.title,
                            subtitle = tr(
                                "${result.album.artist} · not on YouTube as an album · one ${formatDuration(video.durationSec)} video",
                                "${result.album.artist} · pas en album sur YouTube · une vidéo de ${formatDuration(video.durationSec)}",
                            ),
                            actions = listOf(
                                SheetAction(tr("Play the full album", "Lire l'album complet"), Ic.PlaylistPlay) {
                                    Graph.player.playNow(video)
                                },
                                SheetAction(tr("Add to queue", "Ajouter à la file"), Ic.Queue) { Graph.player.enqueue(listOf(video)) },
                                SheetAction(tr("Download", "Télécharger"), Ic.Download) { download(listOf(video)) },
                            ),
                        )
                    )
                }
                is AlbumResult.NotOnYouTube -> Graph.toast(
                    tr("“${result.album.title}” isn't on YouTube", "« ${result.album.title} » n'est pas sur YouTube")
                )
                AlbumResult.Unknown -> Graph.toast(
                    tr("Couldn't tell which album this song is from", "Impossible de savoir de quel album vient ce titre")
                )
            }
        }
    }

    /**
     * Pick where to play the album: YouTube Music albums first (✓ = the song is in it), then
     * playlists of it, each with its number of tracks.
     */
    private fun albumChoices(result: AlbumResult.Options): SheetSpec = SheetSpec(
        title = result.album.title,
        subtitle = listOfNotNull(
            result.album.artist,
            result.album.tracks?.let {
                tr("${tracksLabel(it)} on the album (listed first)", "${tracksLabel(it)} sur l'album (en tête de liste)")
            },
            tr("✓ = has this song", "✓ = contient ce titre"),
        ).joinToString(" · "),
        actions = result.options.map { o ->
            val parts = buildList {
                add(o.ref.title + if (o.hasSong) "  ✓" else "")
                if (o.ref.uploader.isNotBlank()) add(o.ref.uploader)
                add(if (o.isAlbum) "Album" else "Playlist")
                o.tracks?.let { add(tracksLabel(it)) }
            }
            SheetAction(parts.joinToString("  ·  "), if (o.isAlbum) Ic.Album else Ic.PlaylistPlay) {
                Nav.openPlaylist(o.ref)
            }
        },
    )

    // ------------------------------------------------------------------ your own playlists

    fun tracksLabel(n: Int) = trCount(n, "track", "tracks", "titre", "titres")

    /** Menu for one of your playlists; [onOpen] is null when it's already open. */
    fun myPlaylistSheet(ref: PlaylistRef, onOpen: (() -> Unit)?): SheetSpec = SheetSpec(
        title = ref.title,
        subtitle = tr("My playlist", "Ma playlist") + " · ${tracksLabel(ref.count.coerceAtLeast(0).toInt())}",
        actions = listOfNotNull(
            onOpen?.let { SheetAction(tr("Open", "Ouvrir"), Ic.PlaylistPlay, onClick = it) },
            SheetAction(tr("Play all", "Tout lire"), Ic.PlaylistPlay) { playPlaylist(ref) },
            SheetAction(tr("Shuffle play", "Lecture aléatoire"), Ic.Shuffle) { playPlaylist(ref, shuffle = true) },
            SheetAction(tr("Add all to queue", "Tout ajouter à la file"), Ic.PlaylistAdd) { queuePlaylist(ref) },
            SheetAction(tr("Download all", "Tout télécharger"), Ic.Download) { downloadPlaylist(ref) },
            SheetAction(tr("Rename", "Renommer"), Ic.Edit, next = { renamePlaylistSheet(ref) }),
            SheetAction(
                tr("Delete playlist", "Supprimer la playlist"),
                Ic.Delete,
                destructive = true,
                next = { deletePlaylistsSheet(listOf(ref)) },
            ),
        ),
    )

    /** Pick one of your playlists (or make a new one) for [tracks]; [onDone] runs once they're in. */
    fun addToPlaylistSheet(
        tracks: List<Track>,
        title: String = tr("Add to playlist", "Ajouter à une playlist"),
        exclude: String? = null,
        onDone: (PlaylistRef) -> Unit = {},
    ): SheetSpec {
        val targets = Graph.library.localPlaylists().filter { it.ref.url != exclude }
        return SheetSpec(
            title = title,
            subtitle = tracksLabel(tracks.size),
            actions = buildList {
                add(SheetAction(tr("New playlist", "Nouvelle playlist"), Ic.Add, next = { newPlaylistSheet(tracks, onDone) }))
                targets.forEach { p ->
                    add(
                        SheetAction("${p.ref.title}  ·  ${p.trackIds.size}", Ic.PlaylistPlay) {
                            val added = Graph.library.addToPlaylist(p.ref.url, tracks)
                            Graph.toast(
                                when (added) {
                                    0 -> tr("Already in “${p.ref.title}”", "Déjà dans « ${p.ref.title} »")
                                    else -> tr(
                                        "Added ${tracksLabel(added)} to “${p.ref.title}”",
                                        if (added == 1) "1 titre ajouté à « ${p.ref.title} »"
                                        else "$added titres ajoutés à « ${p.ref.title} »",
                                    )
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
        title = tr("New playlist", "Nouvelle playlist"),
        subtitle = if (tracks.isEmpty()) null else tracksLabel(tracks.size),
        content = {
            NameEntry(
                initial = "",
                placeholder = tr("Playlist name", "Nom de la playlist"),
                confirm = tr("Create", "Créer"),
            ) { name ->
                val ref = Graph.library.createPlaylist(name, tracks)
                val created = tr("Created “$name”", "Playlist « $name » créée")
                Graph.toast(if (tracks.isEmpty()) created else "$created · ${tracksLabel(tracks.distinctBy { it.id }.size)}")
                onDone(ref)
            }
        },
    )

    fun renamePlaylistSheet(ref: PlaylistRef): SheetSpec = SheetSpec(
        title = tr("Rename playlist", "Renommer la playlist"),
        content = {
            NameEntry(
                initial = ref.title,
                placeholder = tr("Playlist name", "Nom de la playlist"),
                confirm = tr("Save", "Enregistrer"),
            ) { name ->
                Graph.library.renamePlaylist(ref.url, name)
            }
        },
    )

    fun deletePlaylistsSheet(refs: List<PlaylistRef>, onDone: () -> Unit = {}): SheetSpec {
        val mine = refs.count { it.isLocal }
        return confirmSheet(
            title = if (refs.size == 1) {
                tr("Delete “${refs[0].title}”?", "Supprimer « ${refs[0].title} » ?")
            } else {
                tr("Delete ${refs.size} playlists?", "Supprimer ${refs.size} playlists ?")
            },
            subtitle = if (mine > 0) {
                tr("Songs you downloaded stay in your library", "Les titres téléchargés restent dans votre bibliothèque")
            } else {
                tr("They can be saved again from search", "Vous pourrez les réenregistrer depuis la recherche")
            },
            confirm = tr("Delete", "Supprimer"),
        ) {
            Graph.library.removePlaylists(refs.map { it.url }.toSet())
            Graph.toast(
                if (refs.size == 1) tr("Playlist deleted", "Playlist supprimée")
                else tr("${refs.size} playlists deleted", "${refs.size} playlists supprimées")
            )
            onDone()
        }
    }

    fun confirmSheet(title: String, subtitle: String?, confirm: String, onConfirm: () -> Unit): SheetSpec = SheetSpec(
        title = title,
        subtitle = subtitle,
        actions = listOf(
            SheetAction(confirm, Ic.Delete, destructive = true, onClick = onConfirm),
            SheetAction(tr("Cancel", "Annuler"), Ic.Close),
        ),
    )

    // ------------------------------------------------------------------ multi-select

    /**
     * The shared menu for several selected songs, wherever they were picked. [extra] adds the
     * list's own actions (remove from playlist / queue…); [onDone] ends the selection.
     */
    fun selectionSheet(
        tracks: List<Track>,
        title: String = trCount(tracks.size, "track selected", "tracks selected", "titre sélectionné", "titres sélectionnés"),
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
                add(SheetAction(tr("Play", "Lire"), Ic.PlaylistPlay) {
                    Graph.player.playAll(tracks)
                    onDone()
                })
                add(SheetAction(tr("Add next in queue", "Lire ensuite"), Ic.PlayNext) {
                    Graph.player.playNext(tracks)
                    Graph.toast(
                        tr(
                            "${tracksLabel(tracks.size).replaceFirstChar { it.uppercase() }} after the current song",
                            "Après le titre en cours : ${tracksLabel(tracks.size)}",
                        )
                    )
                    onDone()
                })
                add(SheetAction(tr("Add to queue", "Ajouter à la file"), Ic.Queue) {
                    Graph.player.enqueue(tracks)
                    onDone()
                })
                add(
                    SheetAction(
                        tr("Add to playlist", "Ajouter à une playlist"),
                        Ic.PlaylistAdd,
                        next = { addToPlaylistSheet(tracks) { onDone() } },
                    )
                )
                if (downloaded.size < tracks.size) {
                    add(SheetAction(tr("Download", "Télécharger"), Ic.Download) {
                        download(tracks)
                        onDone()
                    })
                }
                addAll(extra)
                if (downloaded.isNotEmpty()) {
                    add(
                        SheetAction(
                            if (downloaded.size == 1) {
                                tr("Delete download", "Supprimer le téléchargement")
                            } else {
                                tr("Delete ${downloaded.size} downloads", "Supprimer ${downloaded.size} téléchargements")
                            },
                            Ic.Delete,
                            destructive = true,
                            next = {
                                confirmSheet(
                                    title = if (downloaded.size == 1) {
                                        tr("Delete this download?", "Supprimer ce téléchargement ?")
                                    } else {
                                        tr("Delete ${downloaded.size} downloads?", "Supprimer ${downloaded.size} téléchargements ?")
                                    },
                                    subtitle = tr(
                                        "They stay in your playlists and can still stream",
                                        "Les titres restent dans vos playlists, lisibles en streaming",
                                    ),
                                    confirm = tr("Delete", "Supprimer"),
                                ) {
                                    downloaded.forEach { Graph.library.deleteDownload(it.id) }
                                    Graph.toast(
                                        if (downloaded.size == 1) tr("Download deleted", "Téléchargement supprimé")
                                        else tr("${downloaded.size} downloads deleted", "${downloaded.size} téléchargements supprimés")
                                    )
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
