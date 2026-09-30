import app.ytune.lyrics.LrcLib
import app.ytune.lyrics.LyricsQuery
import app.ytune.yt.MusicAlbums
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
    try { block() } catch (e: Throwable) { println("!! ${e.javaClass.simpleName}: ${e.message}"); e.printStackTrace(System.out) }
}

fun get(url: String): String = http.newCall(
    Request.Builder().url(url).header("User-Agent", "YTune-probe (https://github.com/kream0/ytune)").build()
).execute().use { it.body!!.string() }

fun lrclib(artist: String?, title: String?, keywords: String? = null): List<LyricsQuery.Candidate> {
    val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder().apply {
        keywords?.let { addQueryParameter("q", it) }
        title?.let { addQueryParameter("track_name", it) }
        artist?.takeIf { it.isNotBlank() }?.let { addQueryParameter("artist_name", it) }
    }.build().toString()
    val arr = JSONArray(get(url))
    val objs = (0 until arr.length()).map { arr.getJSONObject(it) }
    println("    lrclib artist=[$artist] title=[$title] q=[$keywords] -> ${objs.size} results" +
        objs.take(3).joinToString("") { "\n      ${it.optString("trackName")} | ${it.optString("artistName")} | ${it.optDouble("duration")} | synced=${!it.isNull("syncedLyrics")}" })
    return objs.map {
        LyricsQuery.Candidate(
            it.optDouble("duration", 0.0),
            if (it.isNull("syncedLyrics")) null else it.getString("syncedLyrics"),
            if (it.isNull("plainLyrics")) null else it.getString("plainLyrics"),
            it.optBoolean("instrumental"),
        )
    }
}

/** Mirrors LyricsRepo.fetch, with the Track built the way the app builds it from a list item. */
fun lyricsFor(name: String, uploader: String?, duration: Long) {
    val title = name
    val artist = uploader.orEmpty().removeSuffix(" - Topic")
    val durationSec = duration.coerceAtLeast(0)
    println("  track: title=[$title] artist=[$artist] durationSec=$durationSec")
    val queries = LyricsQuery.candidates(title, artist)
    println("  candidates: $queries")
    for (q in queries.take(3)) {
        val picked = LyricsQuery.pick(lrclib(q.artist.takeIf { it.isNotBlank() }, q.title), durationSec)
        if (picked != null) { println("  => FOUND via $q synced=${picked.lyrics?.synced} lines=${picked.lyrics?.lines?.size}"); return }
    }
    queries.firstOrNull()?.let { q ->
        val picked = LyricsQuery.pick(lrclib(null, null, "${q.artist} ${q.title}".trim()), durationSec)
        if (picked != null) { println("  => FOUND via q=$q synced=${picked.lyrics?.synced}"); return }
    }
    println("  => MISSING")
}


/** Old app parsing failed the whole answer when any entry had a null duration / instrumental. */
fun strictFails(raw: String): Boolean {
    val arr = JSONArray(raw)
    return (0 until arr.length()).any { i ->
        val o = arr.optJSONObject(i) ?: return@any true
        (o.has("duration") && (o.isNull("duration") || o.opt("duration") !is Number)) ||
            (o.has("instrumental") && (o.isNull("instrumental") || o.opt("instrumental") !is Boolean))
    }
}

var httpErrors = 0

fun rawSearch(artist: String?, title: String?, keywords: String? = null): String? {
    val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder().apply {
        keywords?.let { addQueryParameter("q", it) }
        title?.let { addQueryParameter("track_name", it) }
        artist?.let { addQueryParameter("artist_name", it) }
    }.build()
    http.newCall(Request.Builder().url(url).header("User-Agent", "YTune-probe (https://github.com/kream0/ytune)").build()).execute().use {
        if (!it.isSuccessful) { httpErrors++; println("    HTTP ${it.code} ${it.header("Retry-After") ?: ""}"); return null }
        return it.body!!.string()
    }
}

/** Old vs new outcome for one track, from the same LRCLIB answers. */
fun compare(title: String, artist: String, durationSec: Long): Pair<String, String> {
    val queries = LyricsQuery.candidates(title, artist)
    val steps = queries.take(3).map { q -> { rawSearch(q.artist.takeIf { it.isNotBlank() }, q.title) } } +
        listOfNotNull(queries.firstOrNull()?.let { q -> { rawSearch(null, null, "${q.artist} ${q.title}".trim()) } })
    var old: String? = null; var new: String? = null
    var oldAsked = false; var newAsked = false
    for (step in steps) {
        if (old != null && new != null) break
        val raw = step() ?: continue
        val found = LyricsQuery.pick(LrcLib.parse(raw), durationSec)?.let { if (it.instrumental) "INSTRUMENTAL" else "FOUND(synced=${it.lyrics?.synced})" }
        if (new == null) { newAsked = true; if (found != null) new = found }
        if (old == null && !strictFails(raw)) { oldAsked = true; if (found != null) old = found }
    }
    return (old ?: if (oldAsked) "MISSING" else "NO CONNECTION") to (new ?: if (newAsked) "MISSING" else "NO CONNECTION")
}

fun compareAlbum(url: String) {
    val p = yt.getPlaylistExtractor(url).also { it.fetchPage() }
    var oldOk = 0; var newOk = 0; var n = 0
    p.initialPage.items.forEach {
        val (o, nw) = compare(it.name, it.uploaderName.orEmpty().removeSuffix(" - Topic"), it.duration.coerceAtLeast(0))
        n++; if (o.startsWith("FOUND") || o == "INSTRUMENTAL") oldOk++; if (nw.startsWith("FOUND") || nw == "INSTRUMENTAL") newOk++
        println("  ${it.name} | ${it.uploaderName} | ${it.duration}s  old=$o  new=$nw")
    }
    println("  => ${p.name}: old app $oldOk/$n, new $newOk/$n, http errors so far $httpErrors")
}

fun items(url: String, lyricsOf: (StreamInfoItem) -> Boolean) {
    val p = yt.getPlaylistExtractor(url).also { it.fetchPage() }
    println("playlist: ${p.name} | uploader=[${runCatching { p.uploaderName }.getOrElse { "ERR ${it.message}" }}] | count=${runCatching { p.streamCount }.getOrNull()}")
    val page = p.initialPage
    println("items: ${page.items.size}, hasNext=${page.hasNextPage()}, errors=${page.errors.map { it.message }}")
    page.items.take(6).forEach {
        println("item: [${it.name}] | uploader=[${it.uploaderName}] | ${it.duration}s | type=${it.streamType} | ${it.url}")
    }
    page.items.filter(lyricsOf).take(2).forEach { lyricsFor(it.name, it.uploaderName, it.duration) }
}

fun dumpIds(json: String) {
    // Where do album results keep their ids? Print every playlistId / browseId with its path.
    fun walk(e: Any?, path: String) {
        when (e) {
            is JSONObject -> e.keys().forEach { k ->
                val v = e.get(k)
                if ((k == "playlistId" || k == "browseId") && v is String) println("  $path.$k = $v") else walk(v, "$path.$k")
            }
            is JSONArray -> for (i in 0 until e.length()) walk(e.get(i), "$path[$i]")
        }
    }
    walk(JSONObject(json), "")
}

fun main() {
    NewPipe.init(NewPipeHttp(http), Localization("fr", "FR"), ContentCountry("FR"))
    var moreLife = ""
    var scorpion = ""

    section("Our album search: Drake More Life") {
        val raw = MusicAlbums.searchJson("Drake More Life")
        val albums = MusicAlbums.parseSearch(raw)
        albums.forEach { println("album: ${it.title} | ${it.artist} | ${it.kind} | ${it.year} | pl=${it.playlistId} | browse=${it.browseId}") }
        println("-- resolved:")
        val t0 = System.currentTimeMillis()
        MusicAlbums.search("Drake More Life").forEach { println("  ${it.title} -> ${it.url}") }
        println("-- search + resolve took ${System.currentTimeMillis() - t0} ms")
        moreLife = MusicAlbums.search("Drake More Life").firstOrNull { it.title == "More Life" }?.url.orEmpty()
        if (moreLife.isEmpty()) dumpIds(raw)
    }

    section("Our album search: More Life") {
        MusicAlbums.search("More Life").forEach { println("album: ${it.title} | ${it.artist} | ${it.kind} | ${it.year} | ${it.url}") }
    }

    section("Our album search: Drake Scorpion") {
        val a = MusicAlbums.search("Drake Scorpion")
        a.forEach { println("album: ${it.title} | ${it.artist} | ${it.kind} | ${it.year} | ${it.url}") }
        scorpion = a.firstOrNull { it.title == "Scorpion" }?.url.orEmpty()
    }

    section("NewPipe album search: Drake More Life (for comparison)") {
        val s = yt.getSearchExtractor("Drake More Life", listOf(F.MUSIC_ALBUMS), "").also { it.fetchPage() }
        s.initialPage.items.filterIsInstance<PlaylistInfoItem>().forEach { println("album: ${it.name} | ${it.uploaderName} | ${it.url}") }
        println("errors: ${s.initialPage.errors.map { it.message }}")
    }

    section("Album items: More Life $moreLife") {
        items(moreLife) { it.name.contains("Fake Love", true) || it.name.contains("Passionfruit", true) }
    }

    section("Album items: Scorpion $scorpion") {
        items(scorpion) { it.name.contains("God's Plan", true) || it.name.contains("Nonstop", true) }
    }

    section("User playlists: More Life Drake") {
        val s = yt.getSearchExtractor("More Life Drake", listOf(F.PLAYLISTS), "").also { it.fetchPage() }
        val lists = s.initialPage.items.filterIsInstance<PlaylistInfoItem>()
        lists.take(4).forEach { println("playlist: ${it.name} | [${it.uploaderName}] | count=${it.streamCount} | ${it.url}") }
        lists.firstOrNull()?.let { items(it.url) { i -> i.name.contains("Fake Love", true) || i.name.contains("Passionfruit", true) } }
    }

    section("Old vs new lyrics, whole album: More Life") { compareAlbum(moreLife) }
    section("Old vs new lyrics, whole album: Scorpion") { compareAlbum(scorpion) }
    section("Old vs new lyrics, song search results") {
        val s = yt.getSearchExtractor("Drake", listOf(F.MUSIC_SONGS), "").also { it.fetchPage() }
        s.initialPage.items.filterIsInstance<StreamInfoItem>().take(10).forEach {
            val (o, nw) = compare(it.name, it.uploaderName.orEmpty().removeSuffix(" - Topic"), it.duration)
            println("  ${it.name} | ${it.uploaderName} | ${it.duration}s  old=$o  new=$nw")
        }
    }

    section("Lyrics for the song search item (baseline)") {
        val s = yt.getSearchExtractor("Drake Fake Love", listOf(F.MUSIC_SONGS), "").also { it.fetchPage() }
        val song = s.initialPage.items.filterIsInstance<StreamInfoItem>().first()
        lyricsFor(song.name, song.uploaderName, song.duration)
    }
}
