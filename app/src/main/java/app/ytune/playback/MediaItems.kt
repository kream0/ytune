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

    fun uriFor(id: String): Uri = Uri.parse("$SCHEME://track/$id")

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
