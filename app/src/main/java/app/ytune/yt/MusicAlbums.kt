package app.ytune.yt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * YouTube Music album search, parsed here rather than by NewPipe: NewPipe drops an album when
 * its playlist id isn't at the one place it looks (Drake's "More Life" never came back), so the
 * id is taken from wherever the result carries it, or from the album's page as a last resort.
 */
object MusicAlbums {

    /** An album result. [kind]: "Album", "Single", "EP"… as YouTube Music labels it. */
    data class Album(
        val title: String,
        val artist: String,
        val kind: String?,
        val year: String?,
        val playlistId: String?,
        val browseId: String?,
        val thumbnail: String?,
    ) {
        val url: String? get() = playlistId?.let { "https://music.youtube.com/playlist?list=$it" }
    }

    /** Albums matching [query], each with a playable playlist id when one could be found. */
    fun search(query: String): List<Album> =
        parseSearch(searchJson(query)).map { a ->
            if (a.playlistId != null || a.browseId == null) a
            else a.copy(playlistId = runCatching { playlistIdOf(a.browseId) }.getOrNull())
        }

    /** The raw search response. */
    fun searchJson(query: String): String = post("search", query = query, params = ALBUMS_PARAMS)

    /** The album's playlist id, from its page ("MPREb_…" browse id). */
    fun playlistIdOf(browseId: String): String? =
        playlistIdIn(Json.parseToJsonElement(post("browse", browseId = browseId)))

    fun parseSearch(json: String): List<Album> {
        val root = Json.parseToJsonElement(json)
        val items = mutableListOf<JsonObject>()
        root.collect("musicResponsiveListItemRenderer", items)
        return items.mapNotNull(::albumOf).distinctBy { it.playlistId ?: it.browseId ?: it.title }
    }

    private fun albumOf(item: JsonObject): Album? {
        val columns = (item["flexColumns"] as? JsonArray)?.map {
            it.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.runs().orEmpty()
        }.orEmpty()
        val title = columns.getOrNull(0)?.joinToString("") { it.text }?.trim().orEmpty()
        if (title.isEmpty()) return null
        // "Album • Drake • 2017" (an artist may be several runs: "A, B & C").
        val subtitle = columns.getOrNull(1).orEmpty()
        val parts = subtitle.joinToString("") { it.text }.split(" • ").map { it.trim() }
        val kind = parts.firstOrNull()?.takeIf { it in KINDS }
        val artistRuns = subtitle.filter { it.pageType == "MUSIC_PAGE_TYPE_ARTIST" }
        val artist = if (artistRuns.isNotEmpty()) {
            artistRuns.joinToString(", ") { it.text }
        } else {
            parts.drop(if (kind != null) 1 else 0).firstOrNull { !it.isYear() }.orEmpty()
        }
        val year = parts.lastOrNull()?.takeIf { it.isYear() }
        val browseId = item.strings("browseId").firstOrNull { it.startsWith("MPRE") }
        return Album(
            title = title,
            artist = artist,
            kind = kind,
            year = year,
            playlistId = playlistIdIn(item),
            browseId = browseId,
            thumbnail = item.obj("thumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail")
                ?.let { (it["thumbnails"] as? JsonArray)?.lastOrNull() as? JsonObject }
                ?.str("url"),
        )
    }

    /** The album's own playlist ("OLAK5uy_…") among the ids in [e], else the first one. */
    private fun playlistIdIn(e: JsonElement): String? {
        val ids = e.strings("playlistId") + e.strings("audioPlaylistId")
        return ids.firstOrNull { it.startsWith("OLAK5uy_") } ?: ids.firstOrNull { !it.startsWith("RD") }
    }

    // ------------------------------------------------------------------ request

    private fun post(endpoint: String, query: String? = null, params: String? = null, browseId: String? = null): String {
        val version = YoutubeParsingHelper.getYoutubeMusicClientVersion()
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", version)
                    put("hl", "en-GB")
                    put("gl", NewPipe.getPreferredContentCountry().countryCode)
                    put("platform", "DESKTOP")
                    put("utcOffsetMinutes", 0)
                }
                putJsonObject("request") {
                    putJsonArray("internalExperimentFlags") {}
                    put("useSsl", true)
                }
                putJsonObject("user") { put("lockedSafetyMode", false) }
            }
            query?.let { put("query", it) }
            params?.let { put("params", it) }
            browseId?.let { put("browseId", it) }
        }
        val response = NewPipe.getDownloader().postWithContentTypeJson(
            "https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false",
            YoutubeParsingHelper.getYoutubeMusicHeaders(),
            body.toString().toByteArray(),
        )
        return YoutubeParsingHelper.getValidJsonResponseBody(response)
    }

    // ------------------------------------------------------------------ JSON helpers

    private class Run(val text: String, val pageType: String?)

    private fun JsonObject.runs(): List<Run> = (this["runs"] as? JsonArray).orEmpty().mapNotNull { r ->
        val o = r as? JsonObject ?: return@mapNotNull null
        val pageType = o.obj("navigationEndpoint")?.obj("browseEndpoint")
            ?.obj("browseEndpointContextSupportedConfigs")
            ?.obj("browseEndpointContextMusicConfig")?.str("pageType")
        Run(o.str("text").orEmpty(), pageType)
    }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    /** Every object under [key], anywhere in the tree (not looking inside the found ones). */
    private fun JsonElement.collect(key: String, out: MutableList<JsonObject>) {
        when (this) {
            is JsonObject -> for ((k, v) in this) {
                if (k == key && v is JsonObject) out += v else v.collect(key, out)
            }
            is JsonArray -> forEach { it.collect(key, out) }
            else -> Unit
        }
    }

    /** Every string under [key], anywhere in the tree, in document order. */
    private fun JsonElement.strings(key: String): List<String> {
        val out = mutableListOf<String>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> for ((k, v) in e) {
                    if (k == key && v is JsonPrimitive && v.isString) out += v.content else walk(v)
                }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(this)
        return out
    }

    private fun String.isYear() = length == 4 && all { it.isDigit() }

    private val KINDS = setOf("Album", "Single", "EP", "Audiobook")

    /** NewPipe's "albums" filter for the YouTube Music search. */
    private const val ALBUMS_PARAMS = "Eg-KAQwIABAAGAEgACgAMABqChAEEAUQAxAKEAk%3D"
}
