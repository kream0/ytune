package app.ytune.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.ytune.data.Track

/**
 * Queue items carry only a `ytune://track/<videoId>` URI. The real stream URL (or the local
 * file, once downloaded) is resolved lazily when the player opens the item, so a queue of
 * hundreds of tracks costs nothing up front and never holds expired URLs.
 */
object MediaItems {
    const val SCHEME = "ytune"
    private const val EXTRA_DURATION = "ytune.duration"
    private const val EXTRA_THUMB = "ytune.thumb"

    private const val PARAM_DISK = "disk"

    fun uriFor(id: String): Uri = Uri.parse("$SCHEME://track/$id")

    /**
     * Same track, different URI: swapping an item to this makes the player re-open it (and the
     * resolver then picks the downloaded file). The resolver ignores the query.
     */
    fun diskUriFor(id: String): Uri = Uri.parse("$SCHEME://track/$id?$PARAM_DISK=1")

    fun isDiskReload(item: MediaItem): Boolean =
        item.localConfiguration?.uri?.getQueryParameter(PARAM_DISK) != null

    fun build(track: Track, artwork: String? = track.thumbnail): MediaItem {
        val extras = Bundle().apply {
            putLong(EXTRA_DURATION, track.durationSec)
            putString(EXTRA_THUMB, track.thumbnail)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setArtworkUri(artwork?.let { Uri.parse(it) })
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uriFor(track.id))
            .setMediaMetadata(metadata)
            .build()
    }

    /** Controllers strip URIs when handing items to the session; put ours back. */
    fun withUri(item: MediaItem): MediaItem = item.buildUpon().setUri(uriFor(item.mediaId)).build()

    fun toTrack(item: MediaItem): Track {
        val m = item.mediaMetadata
        return Track(
            id = item.mediaId,
            title = m.title?.toString() ?: item.mediaId,
            artist = m.artist?.toString().orEmpty(),
            durationSec = m.extras?.getLong(EXTRA_DURATION) ?: 0L,
            thumbnail = m.extras?.getString(EXTRA_THUMB) ?: m.artworkUri?.toString(),
        )
    }
}
