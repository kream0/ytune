package app.ytune.yt

import android.net.Uri
import app.ytune.data.AudioQuality
import app.ytune.data.PlaylistRef
import app.ytune.data.Track
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class SearchFilter(val label: String, val contentFilter: String) {
    SONGS("SONGS", YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
    VIDEOS("VIDEOS", YoutubeSearchQueryHandlerFactory.VIDEOS),
    PLAYLISTS("PLAYLISTS", YoutubeSearchQueryHandlerFactory.PLAYLISTS),
    ALBUMS("ALBUMS", YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS),
}

sealed interface ResultItem {
    val key: String
}

data class SongResult(val track: Track) : ResultItem {
    override val key: String get() = "s:${track.id}"
}

data class PlaylistResult(val ref: PlaylistRef) : ResultItem {
    override val key: String get() = "p:${ref.url}"
}

/** A playable, already de-obfuscated audio URL for one video. */
data class ResolvedStream(
    val videoId: String,
    val url: String,
    val itag: Int,
    val mimeType: String,
    val extension: String,
    val bitrate: Int,
    val contentLength: Long,
    val userAgent: String,
    val expiresAt: Long,
)

sealed interface YtLink {
    data class Video(val id: String) : YtLink
    data class Playlist(val url: String) : YtLink
}

/**
 * Everything YouTube, built on NewPipeExtractor. All functions are blocking: call them
 * from a background dispatcher/thread.
 */
object YouTube {

    private val service get() = ServiceList.YouTube
    private val streamCache = ConcurrentHashMap<String, ResolvedStream>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val videoIdRegex =
        Regex("""(?:v=|/shorts/|youtu\.be/|/embed/|/live/|/v/)([A-Za-z0-9_-]{11})""")

    fun init(client: OkHttpClient) {
        val locale = Locale.getDefault()
        val country = locale.country.takeIf { it.length == 2 } ?: "US"
        NewPipe.init(NewPipeHttp(client), Localization.fromLocale(locale), ContentCountry(country))
    }

    // ---------------------------------------------------------------- search

    fun search(query: String, filter: SearchFilter): SearchPager =
        SearchPager(service.getSearchExtractor(query, listOf(filter.contentFilter), ""), filter)

    fun suggestions(query: String): List<String> =
        service.suggestionExtractor.suggestionList(query).take(8)


    // ---------------------------------------------------------------- playlists

    fun playlist(url: String): PlaylistPager = PlaylistPager(service.getPlaylistExtractor(url), url)


    /** Loads a whole playlist (capped, since YouTube mixes are endless). */
    fun loadWholePlaylist(url: String, limit: Int = 1000): Pair<PlaylistRef, List<Track>> {
        val pager = playlist(url)
        val all = LinkedHashMap<String, Track>()
        while (pager.hasMore && all.size < limit) {
            val before = all.size
            pager.loadNext().forEach { all.putIfAbsent(it.id, it) }
            if (all.size == before && !pager.hasMore) break
        }
        return pager.header to all.values.take(limit)
    }

    // ---------------------------------------------------------------- streams

    fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    fun invalidate(videoId: String) {
        streamCache.keys.filter { it.startsWith("$videoId|") }.forEach { streamCache.remove(it) }
    }

    /** Resolves (and caches until shortly before expiry) the best audio stream for [videoId]. */
    fun resolve(videoId: String, quality: AudioQuality, forceRefresh: Boolean = false): ResolvedStream {
        val key = "$videoId|${quality.name}"
        if (!forceRefresh) {
            streamCache[key]?.takeIf { it.expiresAt - System.currentTimeMillis() > 10 * 60_000 }?.let { return it }
        }
        val lock = locks.getOrPut(key) { Any() }
        synchronized(lock) {
            if (!forceRefresh) {
                streamCache[key]?.takeIf { it.expiresAt - System.currentTimeMillis() > 10 * 60_000 }?.let { return it }
            }
            return fetch(videoId, quality).first.also { streamCache[key] = it }
        }
    }

    /** Fetches metadata + stream in one go (used for pasted links). */
    fun fetchTrack(videoId: String, quality: AudioQuality): Track {
        val (stream, track) = fetch(videoId, quality)
        streamCache["$videoId|${quality.name}"] = stream
        return track
    }

    private fun fetch(videoId: String, quality: AudioQuality): Pair<ResolvedStream, Track> {
        val extractor = try {
            service.getStreamExtractor(watchUrl(videoId)).also { it.fetchPage() }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: e.javaClass.simpleName, e)
        }

        val candidates = extractor.audioStreams.filter {
            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl
        }
        if (candidates.isEmpty()) throw IOException("No downloadable audio stream for this video")
        val pick = choose(candidates, quality)

        val url = pick.content
        val userAgent = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
            YoutubeParsingHelper.getVisionOsUserAgent(null)
        } else {
            NewPipeHttp.USER_AGENT
        }
        val expires = runCatching { Uri.parse(url).getQueryParameter("expire")?.toLong()?.times(1000) }
            .getOrNull() ?: (System.currentTimeMillis() + 5 * 3600_000L)

        val stream = ResolvedStream(
            videoId = videoId,
            url = url,
            itag = pick.itag,
            mimeType = pick.format?.mimeType ?: "audio/webm",
            extension = pick.format?.suffix ?: "webm",
            bitrate = pick.averageBitrate.takeIf { it > 0 } ?: pick.bitrate,
            contentLength = pick.itagItem?.contentLength ?: -1L,
            userAgent = userAgent,
            expiresAt = expires,
        )
        val track = Track(
            id = videoId,
            title = runCatching { extractor.name }.getOrNull() ?: videoId,
            artist = runCatching { extractor.uploaderName }.getOrNull().orEmpty().removeSuffix(" - Topic"),
            durationSec = runCatching { extractor.length }.getOrDefault(0L),
            thumbnail = runCatching { extractor.thumbnails.bestUrl() }.getOrNull(),
        )
        return stream to track
    }

    private fun choose(streams: List<AudioStream>, quality: AudioQuality): AudioStream {
        val original = streams.filter {
            val type = it.audioTrackType
            type == null || type == AudioTrackType.ORIGINAL
        }.ifEmpty { streams }
        val pool = original.filter { it.itagItem?.isDrc() != true }.ifEmpty { original }

        fun AudioStream.rate() = averageBitrate.takeIf { it > 0 } ?: bitrate
        fun AudioStream.isOpus() =
            format == MediaFormat.WEBMA_OPUS || format == MediaFormat.WEBMA || format == MediaFormat.OPUS

        return when (quality) {
            AudioQuality.BEST -> pool.filter { it.isOpus() }.maxByOrNull { it.rate() }
                ?: pool.maxBy { it.rate() }
            AudioQuality.COMPATIBLE -> pool.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.rate() }
                ?: pool.maxBy { it.rate() }
            AudioQuality.SAVER -> pool.minBy { it.rate() }
        }
    }

    // ---------------------------------------------------------------- links

    fun parseLink(text: String): YtLink? {
        val url = Regex("""https?://\S+""").find(text)?.value ?: return null
        if (!url.contains("youtu")) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val list = uri?.getQueryParameter("list")
        val id = videoIdOf(url)
        return when {
            url.contains("/playlist") && list != null -> YtLink.Playlist(url)
            id != null -> YtLink.Video(id)
            list != null -> YtLink.Playlist("https://www.youtube.com/playlist?list=$list")
            else -> null
        }
    }

    fun videoIdOf(url: String): String? =
        videoIdRegex.find(url)?.groupValues?.get(1)
            ?: runCatching { service.streamLHFactory.getId(url) }.getOrNull()

}

class SearchPager internal constructor(
    private val extractor: SearchExtractor,
    private val filter: SearchFilter,
) {
    private var started = false
    private var next: Page? = null
    val hasMore: Boolean get() = !started || next != null

    fun loadNext(): List<ResultItem> {
        val page = if (!started) {
            extractor.fetchPage()
            started = true
            extractor.initialPage
        } else {
            val p = next ?: return emptyList()
            extractor.getPage(p)
        }
        next = if (page.hasNextPage()) page.nextPage else null
        return page.items.mapNotNull { it.toResult(filter == SearchFilter.ALBUMS) }
    }
}

class PlaylistPager internal constructor(
    private val extractor: PlaylistExtractor,
    private val url: String,
) {
    private var started = false
    private var next: Page? = null
    var header: PlaylistRef = PlaylistRef(url = url, title = "Playlist")
        private set
    val hasMore: Boolean get() = !started || next != null

    fun loadNext(): List<Track> {
        val page = if (!started) {
            extractor.fetchPage()
            started = true
            header = PlaylistRef(
                url = url,
                title = runCatching { extractor.name }.getOrNull() ?: "Playlist",
                uploader = runCatching { extractor.uploaderName }.getOrNull().orEmpty(),
                thumbnail = runCatching { extractor.thumbnails.bestUrl() }.getOrNull(),
                count = runCatching { extractor.streamCount }.getOrDefault(-1L),
            )
            extractor.initialPage
        } else {
            val p = next ?: return emptyList()
            extractor.getPage(p)
        }
        next = if (page.hasNextPage()) page.nextPage else null
        val tracks = page.items.mapNotNull { it.toTrack() }
        if (header.thumbnail == null) {
            header = header.copy(thumbnail = tracks.firstOrNull()?.thumbnail)
        }
        return tracks
    }
}

// ---------------------------------------------------------------- mapping

internal fun StreamInfoItem.toTrack(): Track? {
    val type = streamType
    if (type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM) return null
    val id = YouTube.videoIdOf(url) ?: return null
    return Track(
        id = id,
        title = name ?: id,
        artist = uploaderName.orEmpty().removeSuffix(" - Topic"),
        durationSec = duration.coerceAtLeast(0),
        thumbnail = thumbnails.bestUrl(),
    )
}

internal fun InfoItem.toResult(isAlbum: Boolean): ResultItem? = when (this) {
    is StreamInfoItem -> toTrack()?.let { SongResult(it) }
    is PlaylistInfoItem -> PlaylistResult(
        PlaylistRef(
            url = url,
            title = name ?: "Playlist",
            uploader = uploaderName.orEmpty().removeSuffix(" - Topic"),
            thumbnail = thumbnails.bestUrl(),
            count = streamCount,
            isAlbum = isAlbum,
        )
    )
    else -> null
}

fun List<Image>.bestUrl(maxHeight: Int = 720): String? {
    if (isEmpty()) return null
    return filter { it.height in 1..maxHeight }.maxByOrNull { it.height }?.url
        ?: maxByOrNull { it.height }?.url
        ?: first().url
}
