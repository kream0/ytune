package app.ytune.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

sealed interface LyricsResult {
    data class Found(val lyrics: Lyrics) : LyricsResult
    data object Instrumental : LyricsResult
    data object Missing : LyricsResult
}

/**
 * LRCLIB (lrclib.net, free and open, no account). Answers are read leniently: some entries have
 * no duration (null) or other gaps, and one of them mustn't sink the whole answer.
 */
class LrcLib(
    private val http: OkHttpClient,
    private val userAgent: String,
    private val onError: (String, Throwable?) -> Unit = { _, _ -> },
) {

    /** Throws [IOException] when it couldn't ask (offline…); [LyricsResult.Missing] means asked, none. */
    fun lyrics(title: String, artist: String, durationSec: Long): LyricsResult {
        val queries = LyricsQuery.candidates(title, artist)
        var asked = false
        for (q in queries.take(3)) {
            val results = search(q.artist.takeIf { it.isNotBlank() }, q.title) ?: continue
            asked = true
            pick(results, durationSec)?.let { return it }
        }
        // Last try: free-text search on the best guess.
        queries.firstOrNull()?.let { q ->
            search(null, null, keywords = "${q.artist} ${q.title}".trim())?.let { results ->
                asked = true
                pick(results, durationSec)?.let { return it }
            }
        }
        if (!asked) throw IOException("Couldn't reach the lyrics service")
        return LyricsResult.Missing
    }

    private fun pick(results: List<LyricsQuery.Candidate>, durationSec: Long): LyricsResult? {
        val picked = LyricsQuery.pick(results, durationSec) ?: return null
        return when {
            picked.instrumental -> LyricsResult.Instrumental
            picked.lyrics != null && picked.lyrics.lines.isNotEmpty() -> LyricsResult.Found(picked.lyrics)
            else -> null
        }
    }

    /** Null when the request failed; an empty list when there were no matches. */
    fun search(artist: String?, title: String?, keywords: String? = null): List<LyricsQuery.Candidate>? {
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder().apply {
            if (keywords != null) addQueryParameter("q", keywords)
            title?.let { addQueryParameter("track_name", it) }
            artist?.let { addQueryParameter("artist_name", it) }
        }.build()
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        repeat(MAX_TRIES) { attempt ->
            try {
                http.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> return parse(response.body?.string() ?: return null)
                        // Busy: it says how long to wait (a second, usually).
                        response.code == 429 || response.code == 503 -> {
                            val wait = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, MAX_WAIT_SEC) ?: 1
                            onError("lyrics search: HTTP ${response.code}, retry in ${wait}s", null)
                            if (attempt < MAX_TRIES - 1) Thread.sleep(wait * 1000)
                        }
                        else -> {
                            onError("lyrics search: HTTP ${response.code}", null)
                            return null
                        }
                    }
                }
            } catch (e: IOException) {
                onError("lyrics search failed", e)
                return null
            } catch (e: RuntimeException) {
                onError("lyrics answer unreadable", e) // not JSON
                return null
            }
        }
        return null
    }

    companion object {
        private const val MAX_TRIES = 3
        private const val MAX_WAIT_SEC = 5L

        /** An LRCLIB search answer; entries that aren't objects are skipped, missing fields default. */
        fun parse(json: String): List<LyricsQuery.Candidate> =
            (Json.parseToJsonElement(json) as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                fun value(key: String) = o[key] as? JsonPrimitive
                LyricsQuery.Candidate(
                    durationSec = value("duration")?.doubleOrNull ?: 0.0,
                    synced = value("syncedLyrics")?.takeIf { it.isString }?.contentOrNull,
                    plain = value("plainLyrics")?.takeIf { it.isString }?.contentOrNull,
                    instrumental = value("instrumental")?.booleanOrNull ?: false,
                )
            }
    }
}
