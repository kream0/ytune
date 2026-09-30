package app.ytune.album

import app.ytune.lyrics.LyricsQuery
import app.ytune.lyrics.SameSong.Companion.norm
import kotlin.math.abs

/**
 * The pure part of "Go to album": working out which album a song is on, and recognising that
 * album among YouTube search results without settling for a lookalike playlist.
 */
object AlbumMatch {

    enum class Kind { ALBUM, EP, SINGLE, COMPILATION }

    data class Album(val title: String, val artist: String, val kind: Kind)

    // ------------------------------------------------------------------ album name

    /**
     * YouTube Music's auto-generated ("Artist - Topic") uploads carry the release in their
     * description:
     * ```
     * Provided to YouTube by …
     *
     * Song · Artist · Other artist
     *
     * Album
     *
     * ℗ 1997 …
     * ```
     * Returns the album line, or null when the description isn't in that shape.
     */
    fun albumFromTopicDescription(description: String): String? {
        val text = description
            .replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""<[^>]+>"""), "")
        if (!text.contains("Provided to YouTube", ignoreCase = true)) return null
        val lines = text.lines().map { it.trim() }
        val songLine = lines.indexOfFirst { it.contains(" · ") }
        if (songLine < 0) return null
        val album = lines.drop(songLine + 1).firstOrNull { it.isNotEmpty() } ?: return null
        val notAlbum = listOf("℗", "©", "Released on", "Auto-generated", "Provided to YouTube", "Composer", "Producer")
        return album.takeIf { a -> a.length <= 200 && notAlbum.none { a.startsWith(it, ignoreCase = true) } }
    }

    /** A song as the iTunes Search API lists it. */
    data class CatalogSong(
        val track: String,
        val artist: String,
        val collection: String,
        val collectionArtist: String?,
        val durationMs: Long,
    )

    /**
     * The release [title] by [artist] is on, from catalogue search results: the album if there is
     * one (not a compilation), otherwise the EP / single. Length breaks ties (YouTube lengths may
     * include a video intro, so it isn't a hard filter).
     */
    fun pickRelease(results: List<CatalogSong>, title: String, artist: String, durationSec: Long): Album? {
        val wantTitle = norm(bare(title))
        val wantArtist = norm(artist)
        val matching = results.filter { s ->
            norm(bare(s.track)) == wantTitle && artistMatches(norm(s.artist), wantArtist)
        }
        if (matching.isEmpty()) return null
        fun kindOf(s: CatalogSong) = when {
            s.collectionArtist?.equals("Various Artists", ignoreCase = true) == true -> Kind.COMPILATION
            s.collection.endsWith(" - Single") -> Kind.SINGLE
            s.collection.endsWith(" - EP") -> Kind.EP
            else -> Kind.ALBUM
        }
        fun lengthOff(s: CatalogSong) =
            if (durationSec > 0 && s.durationMs > 0) abs(s.durationMs / 1000 - durationSec) else 0L
        val best = matching.minWith(compareBy<CatalogSong>({ kindOf(it).ordinal }, { lengthOff(it) }))
        return Album(
            title = best.collection.removeSuffix(" - Single").removeSuffix(" - EP"),
            artist = best.collectionArtist?.takeUnless { kindOf(best) == Kind.COMPILATION } ?: best.artist,
            kind = kindOf(best),
        )
    }

    // ------------------------------------------------------------------ finding it on YouTube

    /** An album / playlist search result. */
    data class Listing(val title: String, val uploader: String, val url: String)

    /** A search result named like the album; [byArtist] = its listed artist matches too. */
    data class Candidate(val listing: Listing, val byArtist: Boolean, val exactTitle: Boolean)

    /**
     * Search results that could be [album]: same title (or the same with a "Deluxe" /
     * "Remastered" suffix), best first: exact title before editions, then by the artist before
     * others (the artist YouTube lists isn't always parsed right, so they aren't dropped).
     * Whether one really is the album is then checked by looking for the song in it.
     */
    fun candidates(results: List<Listing>, album: Album): List<Candidate> {
        val exact = norm(album.title)
        val base = norm(bare(album.title))
        val artist = norm(album.artist)
        return results.distinctBy { it.url }
            .mapNotNull { l ->
                val isExact = norm(l.title) == exact
                if (!isExact && (base.isEmpty() || norm(bare(l.title)) != base)) return@mapNotNull null
                Candidate(l, artistMatches(norm(l.uploader), artist), isExact)
            }
            .sortedWith(compareBy({ !it.exactTitle }, { !it.byArtist }))
    }

    /** The album that is [album] from metadata alone (title + artist), or null rather than a guess. */
    fun pickAlbum(results: List<Listing>, album: Album): Listing? =
        candidates(results, album).firstOrNull { it.byArtist }?.listing

    /**
     * Whether a (user-made) playlist is about [album]: its title has the album's name, and the
     * artist is in the title or is the uploader. Filters out the unrelated ones a search returns.
     */
    fun playlistAbout(title: String, uploader: String, album: Album): Boolean {
        val t = norm(title)
        val name = norm(bare(album.title))
        if (name.length < 2 || !t.contains(name)) return false
        val artist = norm(album.artist)
        return t.contains(artist) || artistMatches(norm(uploader), artist)
    }

    /**
     * Whether a video is the whole album in one upload ("Artist - Album (Full Album)"): long,
     * named after the album, and by or about the artist.
     */
    fun isFullAlbumVideo(title: String, uploader: String, durationSec: Long, album: Album): Boolean {
        if (durationSec !in FULL_ALBUM_MIN_SEC..FULL_ALBUM_MAX_SEC) return false
        val t = norm(title)
        val name = norm(bare(album.title))
        if (name.length < 2 || !t.contains(name)) return false
        val artist = norm(album.artist)
        return t.contains(artist) || artistMatches(norm(uploader), artist)
    }

    // ------------------------------------------------------------------ helpers

    /** Without bracketed parts: "Homework (Remastered)" → "Homework". */
    fun bare(s: String): String = s.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").trim()

    /** Loose artist match: "daftpunk" vs "daftpunkofficial", "the weeknd" vs "weeknd". */
    private fun artistMatches(a: String, b: String): Boolean =
        a.isNotEmpty() && b.isNotEmpty() && (a.contains(b) || b.contains(a))

    /** The song's most likely (artist, title), cleaned like the lyrics search does. */
    fun songOf(title: String, channel: String): LyricsQuery.Query =
        LyricsQuery.candidates(title, channel).firstOrNull() ?: LyricsQuery.Query(channel, title)

    private const val FULL_ALBUM_MIN_SEC = 15 * 60L
    private const val FULL_ALBUM_MAX_SEC = 3 * 3600L
}
