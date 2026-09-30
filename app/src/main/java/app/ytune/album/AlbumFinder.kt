package app.ytune.album

import android.util.Log
import app.ytune.data.AppJson
import app.ytune.data.PlaylistRef
import app.ytune.data.Track
import app.ytune.lyrics.SameSong
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

    /** Releases named like the album that couldn't be confirmed: the user picks. */
    data class Choose(val album: AlbumMatch.Album, val refs: List<PlaylistRef>) : AlbumResult

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

        val result = onYouTube(album, track) ?: fullAlbumVideo(album) ?: AlbumResult.NotOnYouTube(album)
        if (result !is AlbumResult.Choose) cache[track.id] = result
        return result
    }

    /**
     * Among YouTube Music albums named like [album], the one that contains [track] (same video,
     * or the same song by title and artist). Falls back to a title + artist match, then to
     * letting the user choose among the lookalikes.
     */
    private fun onYouTube(album: AlbumMatch.Album, track: Track): AlbumResult? {
        val refs = listOf("${album.artist} ${album.title}", album.title)
            .flatMap { q ->
                runCatching { YouTube.search(q, SearchFilter.ALBUMS).loadNext() }.getOrDefault(emptyList())
            }
            .filterIsInstance<PlaylistResult>()
            .map { it.ref.copy(isAlbum = true) }
            .distinctBy { it.url }
        val candidates = AlbumMatch.candidates(refs.map { AlbumMatch.Listing(it.title, it.uploader, it.url) }, album)
        if (candidates.isEmpty()) return null
        fun ref(c: AlbumMatch.Candidate) = refs.first { it.url == c.listing.url }

        candidates.take(MAX_CHECKED).firstOrNull { contains(it.listing.url, track) }
            ?.let { return AlbumResult.OnYouTube(album, ref(it)) }
        candidates.firstOrNull { it.byArtist }?.let { return AlbumResult.OnYouTube(album, ref(it)) }
        return AlbumResult.Choose(album, candidates.take(MAX_CHOICES).map(::ref))
    }

    /** Whether the album at [url] has [track] in it (first page is the whole album in practice). */
    private fun contains(url: String, track: Track): Boolean {
        val tracks = runCatching { YouTube.playlist(url).loadNext() }.getOrElse { return false }
        val song = SameSong().apply { add(track.title, track.artist) }
        return tracks.any { it.id == track.id || (it.title to it.artist) in song }
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
        /** Lookalike albums opened to look for the song. */
        const val MAX_CHECKED = 4
        const val MAX_CHOICES = 6
    }
}
