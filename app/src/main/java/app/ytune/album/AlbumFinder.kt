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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** One place the album can be played from: a YouTube Music album, or a playlist of it. */
data class AlbumOption(
    val ref: PlaylistRef,
    val isAlbum: Boolean,
    /** Number of tracks, when known. */
    val tracks: Int?,
    /** Confirmed to contain the song we came from. */
    val hasSong: Boolean,
)

sealed interface AlbumResult {
    /** Where the album can be played, best first (the album with the song in it on top). */
    data class Options(val album: AlbumMatch.Album, val options: List<AlbumOption>) : AlbumResult

    /** Not released on YouTube, but uploaded as one long "full album" video. */
    data class FullVideo(val album: AlbumMatch.Album, val video: Track) : AlbumResult

    /** We know the album, but found nothing for it on YouTube. */
    data class NotOnYouTube(val album: AlbumMatch.Album) : AlbumResult

    /** Couldn't tell which album the song is from. */
    data object Unknown : AlbumResult
}

/**
 * "Go to album". Which album a song is on comes from the song itself when possible (YouTube
 * Music's auto-generated uploads name the release in their description), otherwise from the
 * iTunes catalogue (free, no account), which also tells an album from a single, EP or
 * compilation. Then YouTube Music albums and YouTube playlists named after "album + artist" are
 * gathered for the user to pick from: albums first, each opened to count its tracks and check
 * that the song is in it, then playlists about the album.
 */
class AlbumFinder(private val http: OkHttpClient) {
    private val cache = ConcurrentHashMap<String, AlbumResult>()

    /** Throws [IOException] when YouTube and the catalogue both couldn't be reached. */
    suspend fun find(track: Track): AlbumResult = withContext(Dispatchers.IO) {
        cache[track.id]?.let { return@withContext it }
        val song = AlbumMatch.songOf(track.title, track.artist)
        var reached = false

        val fromYouTube = runCatching { YouTube.description(track.id) }
            .onSuccess { reached = true }
            .getOrNull()
            ?.let(AlbumMatch::albumFromTopicDescription)
            ?.let { AlbumMatch.Album(it, song.artist, AlbumMatch.Kind.ALBUM) }
        val album = fromYouTube ?: catalogRelease(song.title, song.artist, track.durationSec)?.also { reached = true }
        if (album == null) {
            if (!reached) throw IOException("Couldn't reach YouTube")
            return@withContext AlbumResult.Unknown.also { cache[track.id] = it }
        }

        val options = options(album, track)
        val result = if (options.isNotEmpty()) {
            AlbumResult.Options(album, options)
        } else {
            fullAlbumVideo(album) ?: AlbumResult.NotOnYouTube(album)
        }
        cache[track.id] = result
        result
    }

    private suspend fun options(album: AlbumMatch.Album, track: Track): List<AlbumOption> = coroutineScope {
        val playlistsJob = async { search("${album.title} ${album.artist}", SearchFilter.PLAYLISTS) }
        val albumRefs = (search("${album.artist} ${album.title}", SearchFilter.ALBUMS) + search(album.title, SearchFilter.ALBUMS))
            .map { it.copy(isAlbum = true) }
            .distinctBy { it.url }
        val candidates = AlbumMatch.candidates(albumRefs.map { AlbumMatch.Listing(it.title, it.uploader, it.url) }, album)
            .take(MAX_ALBUMS)
        // Open each lookalike album (in parallel) to count its tracks and look for the song.
        val albums = candidates.map { c ->
            async {
                val ref = albumRefs.first { it.url == c.listing.url }
                val tracks = runCatching { YouTube.playlist(ref.url).loadNext() }.getOrNull()
                c to AlbumOption(ref, isAlbum = true, tracks = tracks?.size, hasSong = tracks?.let { contains(it, track) } == true)
            }
        }.awaitAll()
            .sortedWith(compareBy({ !it.second.hasSong }, { !it.first.exactTitle }, { !it.first.byArtist }))
            .map { it.second }
        val albumUrls = albums.map { it.ref.url }.toSet()
        val playlists = playlistsJob.await()
            .filter { it.url !in albumUrls && AlbumMatch.playlistAbout(it.title, it.uploader, album) }
            .take(MAX_PLAYLISTS)
            .map { AlbumOption(it, isAlbum = false, tracks = it.count.takeIf { n -> n >= 0 }?.toInt(), hasSong = false) }
        albums + playlists
    }

    private fun search(query: String, filter: SearchFilter): List<PlaylistRef> =
        runCatching { YouTube.search(query, filter).loadNext() }
            .getOrDefault(emptyList())
            .filterIsInstance<PlaylistResult>()
            .map { it.ref }

    /** Whether [tracks] has [track]: the same video, or the same song by title and artist. */
    private fun contains(tracks: List<Track>, track: Track): Boolean {
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
        /** Lookalike albums opened to count tracks and look for the song. */
        const val MAX_ALBUMS = 5
        const val MAX_PLAYLISTS = 6
    }
}
