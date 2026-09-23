package app.ytune.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/** A single YouTube video / song. [id] is the 11-char YouTube video id. */
@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String = "",
    val durationSec: Long = 0,
    val thumbnail: String? = null,
)

/** A downloaded audio file that belongs to a [Track]. */
@Serializable
data class LocalAudio(
    val trackId: String,
    val path: String,
    val mimeType: String,
    val sizeBytes: Long,
    val bitrate: Int = 0,
    val artPath: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

/** Lightweight pointer to a YouTube playlist or album. */
@Serializable
data class PlaylistRef(
    val url: String,
    val title: String,
    val uploader: String = "",
    val thumbnail: String? = null,
    val count: Long = -1,
    val isAlbum: Boolean = false,
)

/** Playlists made in the app live in the library under this URL scheme (never sent to YouTube). */
const val LOCAL_PLAYLIST_PREFIX = "ytune://playlist/"

val PlaylistRef.isLocal: Boolean get() = url.startsWith(LOCAL_PLAYLIST_PREFIX)

@Serializable
data class SavedPlaylist(
    val ref: PlaylistRef,
    val trackIds: List<String>,
    val savedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class LibraryData(
    val tracks: Map<String, Track> = emptyMap(),
    val audio: Map<String, LocalAudio> = emptyMap(),
    val playlists: List<SavedPlaylist> = emptyList(),
)

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "--:--"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

/** Clock-style readout for the player; never shows placeholders. */
fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format(Locale.US, "%.2f GB", mb / 1024)
    else String.format(Locale.US, "%.1f MB", mb)
}
