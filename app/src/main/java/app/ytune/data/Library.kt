package app.ytune.data

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/**
 * Offline library: downloaded audio files, known track metadata and saved playlists.
 * Persisted as a single JSON file; all state is exposed as a [StateFlow].
 */
class Library(context: Context, private val scope: CoroutineScope) {

    private val store = JsonFile(File(context.filesDir, "library.json"), LibraryData.serializer()) { LibraryData() }
    private val _data = MutableStateFlow(store.read())
    val data: StateFlow<LibraryData> = _data.asStateFlow()

    /** App-specific music folder (no storage permission needed). */
    val musicDir: File = (context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        ?: File(context.filesDir, "music")).also { it.mkdirs() }
    val artDir: File = File(musicDir, "artwork").also { it.mkdirs() }

    private val saveLock = Mutex()

    init {
        // Drop entries whose files were removed behind our back.
        scope.launch(Dispatchers.IO) {
            val missing = _data.value.audio.values.filter { !File(it.path).exists() }.map { it.trackId }
            if (missing.isNotEmpty()) update { d -> d.copy(audio = d.audio - missing.toSet()) }
        }
    }

    fun localAudio(trackId: String): LocalAudio? =
        _data.value.audio[trackId]?.takeIf { File(it.path).exists() }

    fun isDownloaded(trackId: String): Boolean = _data.value.audio.containsKey(trackId)

    fun track(trackId: String): Track? = _data.value.tracks[trackId]

    fun artworkFor(track: Track): String? =
        _data.value.audio[track.id]?.artPath?.takeIf { File(it).exists() }?.let { "file://$it" }
            ?: track.thumbnail

    fun downloadedTracks(d: LibraryData = _data.value): List<Pair<Track, LocalAudio>> =
        d.audio.values
            .sortedByDescending { it.addedAt }
            .mapNotNull { a -> d.tracks[a.trackId]?.let { it to a } }

    fun totalBytes(d: LibraryData = _data.value): Long = d.audio.values.sumOf { it.sizeBytes }

    fun remember(tracks: Collection<Track>) {
        if (tracks.isEmpty()) return
        update { d -> d.copy(tracks = d.tracks + tracks.associateBy { it.id }) }
    }

    fun addDownloaded(track: Track, audio: LocalAudio) {
        update { d -> d.copy(tracks = d.tracks + (track.id to track), audio = d.audio + (track.id to audio)) }
    }

    fun deleteDownload(trackId: String) {
        val audio = _data.value.audio[trackId] ?: return
        update { d -> d.copy(audio = d.audio - trackId) }
        scope.launch(Dispatchers.IO) {
            File(audio.path).delete()
            audio.artPath?.let { File(it).delete() }
        }
    }

    fun deleteAllDownloads() {
        val all = _data.value.audio.keys.toList()
        all.forEach(::deleteDownload)
    }

    fun savePlaylist(ref: PlaylistRef, tracks: List<Track>) {
        update { d ->
            d.copy(
                tracks = d.tracks + tracks.associateBy { it.id },
                playlists = listOf(SavedPlaylist(ref.copy(count = tracks.size.toLong()), tracks.map { it.id })) +
                    d.playlists.filterNot { it.ref.url == ref.url },
            )
        }
    }

    fun removePlaylist(url: String) {
        update { d -> d.copy(playlists = d.playlists.filterNot { it.ref.url == url }) }
    }

    fun removePlaylists(urls: Set<String>) {
        update { d -> d.copy(playlists = d.playlists.filterNot { it.ref.url in urls }) }
    }

    // ------------------------------------------------------------------ your own playlists

    fun localPlaylists(d: LibraryData = _data.value): List<SavedPlaylist> = d.playlists.filter { it.ref.isLocal }

    fun createPlaylist(name: String, tracks: List<Track> = emptyList()): PlaylistRef {
        val unique = tracks.distinctBy { it.id }
        val ref = PlaylistRef(url = LOCAL_PLAYLIST_PREFIX + UUID.randomUUID(), title = name.trim())
        update { d ->
            val known = d.tracks + unique.associateBy { it.id }
            d.copy(
                tracks = known,
                playlists = listOf(SavedPlaylist(ref, emptyList()).withTrackIds(unique.map { it.id }, known)) + d.playlists,
            )
        }
        return ref
    }

    /** Appends [tracks] that aren't in the playlist yet; returns how many were added. */
    fun addToPlaylist(url: String, tracks: List<Track>): Int {
        var added = 0
        update { d ->
            val known = d.tracks + tracks.associateBy { it.id }
            d.copy(
                tracks = known,
                playlists = d.playlists.map { p ->
                    if (p.ref.url != url) return@map p
                    val new = tracks.map { it.id }.distinct().filterNot { it in p.trackIds }
                    added = new.size
                    p.withTrackIds(p.trackIds + new, known)
                },
            )
        }
        return added
    }

    fun removeFromPlaylist(url: String, trackIds: Set<String>) = editPlaylist(url) { it.filterNot { id -> id in trackIds } }

    fun movePlaylistTrack(url: String, from: Int, to: Int) = editPlaylist(url) { ids ->
        if (from !in ids.indices || to !in ids.indices) ids
        else ids.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun renamePlaylist(url: String, name: String) {
        update { d ->
            d.copy(playlists = d.playlists.map { if (it.ref.url == url) it.copy(ref = it.ref.copy(title = name.trim())) else it })
        }
    }

    private fun editPlaylist(url: String, edit: (List<String>) -> List<String>) {
        update { d ->
            d.copy(playlists = d.playlists.map { if (it.ref.url == url) it.withTrackIds(edit(it.trackIds), d.tracks) else it })
        }
    }

    /** New track list; the count and cover (first track's) follow it. */
    private fun SavedPlaylist.withTrackIds(ids: List<String>, tracks: Map<String, Track>) = copy(
        trackIds = ids,
        ref = ref.copy(count = ids.size.toLong(), thumbnail = ids.firstNotNullOfOrNull { tracks[it]?.thumbnail }),
    )

    fun savedPlaylist(url: String): SavedPlaylist? = _data.value.playlists.firstOrNull { it.ref.url == url }

    fun tracksOf(playlist: SavedPlaylist, d: LibraryData = _data.value): List<Track> =
        playlist.trackIds.mapNotNull { d.tracks[it] }

    private fun update(transform: (LibraryData) -> LibraryData) {
        _data.update(transform)
        scope.launch(Dispatchers.IO) {
            delay(250)
            saveLock.withLock { store.write(_data.value) }
        }
    }
}
