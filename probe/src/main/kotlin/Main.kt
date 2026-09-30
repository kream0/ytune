import app.ytune.album.AlbumMatch
import app.ytune.lyrics.LyricsQuery
import app.ytune.yt.NewPipeHttp
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory as F
import org.schabi.newpipe.extractor.stream.StreamInfoItem

val http = OkHttpClient()
val yt = ServiceList.YouTube

fun section(name: String, block: () -> Unit) {
    println("\n==== $name")
    try { block() } catch (e: Throwable) { println("!! ${e.javaClass.simpleName}: ${e.message}") }
}

fun get(url: String): String = http.newCall(
    Request.Builder().url(url).header("User-Agent", "YTune-probe (https://github.com/kream0/ytune)").build()
).execute().use { it.body!!.string() }

fun lrclib(artist: String?, title: String, duration: Long) {
    val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder().apply {
        addQueryParameter("track_name", title)
        artist?.takeIf { it.isNotBlank() }?.let { addQueryParameter("artist_name", it) }
    }.build().toString()
    val arr = JSONArray(get(url))
    println("  lrclib artist=[$artist] title=[$title] -> ${arr.length()} results")
    val cands = (0 until arr.length()).map { arr.getJSONObject(it) }
    cands.take(5).forEach {
        println("    ${it.optString("trackName")} | ${it.optString("artistName")} | ${it.optDouble("duration")} | synced=${!it.isNull("syncedLyrics")} plain=${!it.isNull("plainLyrics")}")
    }
    val picked = LyricsQuery.pick(
        cands.map {
            LyricsQuery.Candidate(
                it.optDouble("duration", 0.0),
                if (it.isNull("syncedLyrics")) null else it.getString("syncedLyrics"),
                if (it.isNull("plainLyrics")) null else it.getString("plainLyrics"),
                it.optBoolean("instrumental"),
            )
        },
        duration,
    )
    println("  -> pick(duration=$duration): ${picked?.let { "synced=${it.lyrics?.synced} lines=${it.lyrics?.lines?.size} instrumental=${it.instrumental}" } ?: "NONE"}")
}

fun lyricsFor(title: String, uploader: String, duration: Long) {
    val cands = LyricsQuery.candidates(title, uploader.removeSuffix(" - Topic"))
    println("  candidates: $cands")
    cands.take(3).forEach { q -> lrclib(q.artist, q.title, duration) }
}

fun main() {
    NewPipe.init(NewPipeHttp(http), Localization("fr", "FR"), ContentCountry("FR"))
    var songUrl = ""
    var songItem: StreamInfoItem? = null
    var albumUrl = ""

    section("YT Music songs: Drake Fake Love") {
        val s = yt.getSearchExtractor("Drake Fake Love", listOf(F.MUSIC_SONGS), "").also { it.fetchPage() }
        val page = s.initialPage
        page.items.filterIsInstance<StreamInfoItem>().take(4).forEach {
            println("song: ${it.name} | ${it.uploaderName} | ${it.duration}s | ${it.url}")
        }
        println("errors: ${page.errors.map { it.message }}")
        songItem = page.items.filterIsInstance<StreamInfoItem>().first()
        songUrl = songItem!!.url
    }

    section("YT Music albums: Drake More Life") {
        val s = yt.getSearchExtractor("Drake More Life", listOf(F.MUSIC_ALBUMS), "").also { it.fetchPage() }
        val page = s.initialPage
        page.items.filterIsInstance<PlaylistInfoItem>().take(8).forEach {
            println("album: ${it.name} | uploader=[${it.uploaderName}] | count=${it.streamCount} | ${it.url}")
        }
        println("errors: ${page.errors.map { it.message }}")
        albumUrl = page.items.filterIsInstance<PlaylistInfoItem>().firstOrNull { it.name == "More Life" }?.url.orEmpty()
    }

    section("Album playlist items: $albumUrl") {
        val p = yt.getPlaylistExtractor(albumUrl).also { it.fetchPage() }
        println("playlist: ${p.name} | uploader=[${runCatching { p.uploaderName }.getOrNull()}] | count=${runCatching { p.streamCount }.getOrNull()}")
        val page = p.initialPage
        println("items: ${page.items.size}, hasNext=${page.hasNextPage()}")
        page.items.take(8).forEach {
            println("item: ${it.name} | uploader=[${it.uploaderName}] | ${it.duration}s | type=${it.streamType} | ${it.url}")
        }
        println("errors: ${page.errors.map { it.message }}")
        val fake = page.items.firstOrNull { it.name.contains("Fake Love", ignoreCase = true) }
        println("album 'Fake Love' item: ${fake?.name} | [${fake?.uploaderName}] | ${fake?.duration}")
        if (fake != null) {
            println("-- lyrics for the album item")
            lyricsFor(fake.name, fake.uploaderName.orEmpty(), fake.duration)
        }
    }

    section("Lyrics for the search item") {
        val s = songItem!!
        lyricsFor(s.name, s.uploaderName.orEmpty(), s.duration)
    }

    section("YouTube playlists: More Life Drake") {
        val s = yt.getSearchExtractor("More Life Drake", listOf(F.PLAYLISTS), "").also { it.fetchPage() }
        s.initialPage.items.filterIsInstance<PlaylistInfoItem>().take(8).forEach {
            println("playlist: ${it.name} | [${it.uploaderName}] | count=${it.streamCount} | ${it.url}")
        }
    }

    section("Song page description: $songUrl") {
        val e = yt.getStreamExtractor(songUrl).also { it.fetchPage() }
        println("uploader=[${e.uploaderName}] name=[${e.name}] length=${e.length}")
        val d = e.description.content
        println("description(type=${e.description.type}): ${d.take(500).replace("\n", "⏎")}")
        println("topic album => ${AlbumMatch.albumFromTopicDescription(d)}")
    }

    section("iTunes: Drake Fake Love") {
        val url = "https://itunes.apple.com/search?term=Drake+Fake+Love&entity=song&limit=10&country=FR"
        val res = JSONObject(get(url)).getJSONArray("results")
        (0 until res.length()).map { res.getJSONObject(it) }.forEach {
            println("itunes: ${it.optString("trackName")} | ${it.optString("artistName")} | ${it.optString("collectionName")} | coll.artist=${it.optString("collectionArtistName")} | tracks=${it.optInt("trackCount")} discs=${it.optInt("discCount")}")
        }
    }
}
