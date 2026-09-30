package app.ytune.album

import android.util.Log
import app.ytune.data.AppJson
import app.ytune.data.PlaylistRef
import app.ytune.data.Track
import app.ytune.yt.PlaylistResult
import app.ytune.yt.SearchFilter
import app.ytune.yt.SongResult
import app.ytune.yt.YouTube
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

sealed interface AlbumResult {
    /** The album (or single / EP) as a YouTube Music release. */
    data class OnYouTube(val album: AlbumMatch.Album, val ref: PlaylistRef) : AlbumResult

    /** Not released on YouTube, but uploaded as one long "full album" video. */
    data class FullVideo(val album: AlbumMatch.Album, val video: Track) : AlbumResult

    /** We know the album, but it isn't on YouTube. */
    data class NotOnYouTube(val album: AlbumMatch.Album) : AlbumResult

    /** Couldn't tell which album the song is from. */
    data object Unknown : AlbumResult
}

/**
 * "Go to album". Which album a song is on comes from the song itself when possible (YouTube
 * Music's auto-generated uploads name the release in their description), otherwise from the
 * iTunes catalogue (free, no account), which also tells an album from a single, EP or
 * compilation. That album is then looked up among YouTube Music albums and only accepted on a
 * title + artist match, so a random playlist never stands in for it. Blocking: call off the
 * main thread.
 */
class AlbumFinder(private val http: OkHttpClient) {
    private val cache = ConcurrentHashMap<String, AlbumResult>()

    /** Throws [IOException] when YouTube and the catalogue both couldn't be reached. */
    fun find(track: Track): AlbumResult {
        cache[track.id]?.let { return it }
        val song = AlbumMatch.songOf(track.title, track.artist)
        var reached = false

        val fromYouTube = runCatching { YouTube.description(track.id) }
            .onSuccess { reached = true }
            .getOrNull()
            ?.let(AlbumMatch::albumFromTopicDescription)
            ?.let { AlbumMatch.Album(it, song.artist, AlbumMatch.Kind.ALBUM) }
        val album = fromYouTube ?: catalogRelease(song.title, song.artist, track.durationSec)?.also { reached = true }
            ?: run {
                if (!reached) throw IOException("Couldn't reach YouTube")
                return AlbumResult.Unknown.also { cache[track.id] = it }
            }

        val result = onYouTube(album) ?: fullAlbumVideo(album) ?: AlbumResult.NotOnYouTube(album)
        cache[track.id] = result
        return result
    }

    private fun onYouTube(album: AlbumMatch.Album): AlbumResult.OnYouTube? {
        val results = runCatching {
            YouTube.search("${album.artist} ${album.title}", SearchFilter.ALBUMS).loadNext()
        }.getOrElse { return null }
        val refs = results.filterIsInstance<PlaylistResult>().map { it.ref }
        val listing = AlbumMatch.pickAlbum(refs.map { AlbumMatch.Listing(it.title, it.uploader, it.url) }, album)
            ?: return null
        val ref = refs.first { it.url == listing.url }
        return AlbumResult.OnYouTube(album, ref.copy(isAlbum = true))
    }

    private fun fullAlbumVideo(album: AlbumMatch.Album): AlbumResult.FullVideo? {
        val results = runCatching {
            YouTube.search("${album.artist} ${album.title} full album", SearchFilter.VIDEOS).loadNext()
        }.getOrElse { return null }
        val video = results.filterIsInstance<SongResult>().map { it.track }
            .firstOrNull { AlbumMatch.isFullAlbumVideo(it.title, it.artist, it.durationSec, album) }
            ?: return null
        return AlbumResult.FullVideo(album, video)
    }

    // ------------------------------------------------------------------ iTunes catalogue

    @Serializable
    private data class ItunesResponse(val results: List<ItunesSong> = emptyList())

    @Serializable
    private data class ItunesSong(
        val wrapperType: String? = null,
        val kind: String? = null,
        val trackName: String? = null,
        val artistName: String? = null,
        val collectionName: String? = null,
        val collectionArtistName: String? = null,
        val trackTimeMillis: Long = 0,
    )

    private fun catalogRelease(title: String, artist: String, durationSec: Long): AlbumMatch.Album? {
        val country = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", "$artist $title")
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "25")
            .addQueryParameter("country", country)
            .build()
        val body = try {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                r.body?.string() ?: return null
            }
        } catch (e: IOException) {
            Log.w(TAG, "catalogue lookup failed", e)
            return null
        }
        val songs = runCatching { AppJson.decodeFromString(ItunesResponse.serializer(), body).results }
            .getOrDefault(emptyList())
            .filter { it.kind == "song" && it.trackName != null && it.artistName != null && it.collectionName != null }
            .map {
                AlbumMatch.CatalogSong(it.trackName!!, it.artistName!!, it.collectionName!!, it.collectionArtistName, it.trackTimeMillis)
            }
        return AlbumMatch.pickRelease(songs, title, artist, durationSec)
    }

    private companion object {
        const val TAG = "Albums"
    }
}
