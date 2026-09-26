package app.ytune.lyrics

import android.content.Context
import android.util.Log
import app.ytune.BuildConfig
import app.ytune.data.AppJson
import app.ytune.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

sealed interface LyricsResult {
    data class Found(val lyrics: Lyrics) : LyricsResult
    data object Instrumental : LyricsResult
    data object Missing : LyricsResult
}

/**
 * Lyrics from LRCLIB (lrclib.net, free and open, no account): synced (LRC) when it has them,
 * plain otherwise. Results, including "not found", are kept on disk per song, so downloaded
 * songs have their lyrics offline too.
 */
class LyricsRepo(context: Context, private val http: OkHttpClient, scope: CoroutineScope) {

    private val dir = File(context.filesDir, "lyrics").also { it.mkdirs() }
    private val memory = ConcurrentHashMap<String, LyricsResult>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val prefetchQueue = Channel<Track>(Channel.UNLIMITED)

    init {
        // Saved songs get their lyrics in the background, one at a time.
        scope.launch(Dispatchers.IO) {
            for (track in prefetchQueue) runCatching { get(track) }
        }
    }

    /** Throws [IOException] when it couldn't ask (offline…); [LyricsResult.Missing] means asked, none. */
    suspend fun get(track: Track): LyricsResult {
        memory[track.id]?.let { return it }
        return locks.getOrPut(track.id) { Mutex() }.withLock {
            memory[track.id] ?: withContext(Dispatchers.IO) {
                val result = readDisk(track.id) ?: fetch(track).also { writeDisk(track.id, it) }
                memory[track.id] = result
                result
            }
        }
    }

    fun prefetch(tracks: Collection<Track>) {
        tracks.filter { !memory.containsKey(it.id) && !file(it.id).exists() }.forEach { prefetchQueue.trySend(it) }
    }

    // ------------------------------------------------------------------ LRCLIB

    @Serializable
    private data class LrcLibTrack(
        val trackName: String? = null,
        val artistName: String? = null,
        val duration: Double = 0.0,
        val instrumental: Boolean = false,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
    )

    private fun fetch(track: Track): LyricsResult {
        val queries = LyricsQuery.candidates(track.title, track.artist)
        var asked = false
        for (q in queries.take(3)) {
            val results = search(q.artist.takeIf { it.isNotBlank() }, q.title) ?: continue
            asked = true
            pick(results, track)?.let { return it }
        }
        // Last try: free-text search on the best guess.
        queries.firstOrNull()?.let { q ->
            search(null, null, keywords = "${q.artist} ${q.title}".trim())?.let { results ->
                asked = true
                pick(results, track)?.let { return it }
            }
        }
        if (!asked) throw IOException("Couldn't reach the lyrics service")
        return LyricsResult.Missing
    }

    private fun pick(results: List<LrcLibTrack>, track: Track): LyricsResult? {
        val candidates = results.map {
            LyricsQuery.Candidate(it.duration, it.syncedLyrics, it.plainLyrics, it.instrumental)
        }
        val picked = LyricsQuery.pick(candidates, track.durationSec) ?: return null
        return when {
            picked.instrumental -> LyricsResult.Instrumental
            picked.lyrics != null && picked.lyrics.lines.isNotEmpty() -> LyricsResult.Found(picked.lyrics)
            else -> null
        }
    }

    /** Null when the request failed (network); an empty list when there were no matches. */
    private fun search(artist: String?, title: String?, keywords: String? = null): List<LrcLibTrack>? {
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder().apply {
            if (keywords != null) addQueryParameter("q", keywords)
            title?.let { addQueryParameter("track_name", it) }
            artist?.let { addQueryParameter("artist_name", it) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "YTune/${BuildConfig.VERSION_NAME} (https://github.com/${BuildConfig.UPDATE_REPO})")
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                AppJson.decodeFromString(ListSerializer(LrcLibTrack.serializer()), body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "lyrics search failed", e)
            null
        }
    }

    // ------------------------------------------------------------------ disk cache

    @Serializable
    private data class Stored(
        val synced: Boolean = false,
        val lines: List<StoredLine> = emptyList(),
        val instrumental: Boolean = false,
        val missing: Boolean = false,
        val savedAt: Long = System.currentTimeMillis(),
    )

    @Serializable
    private data class StoredLine(val t: Long, val text: String, val words: List<Long> = emptyList())

    private fun file(id: String) = File(dir, "$id.json")

    private fun readDisk(id: String): LyricsResult? = runCatching {
        val f = file(id)
        if (!f.exists()) return null
        val s = AppJson.decodeFromString(Stored.serializer(), f.readText())
        when {
            // Not found yet: ask again after a few days, the database keeps growing.
            s.missing -> if (System.currentTimeMillis() - s.savedAt > MISSING_TTL_MS) null else LyricsResult.Missing
            s.instrumental -> LyricsResult.Instrumental
            else -> LyricsResult.Found(
                Lyrics(
                    lines = s.lines.map { l ->
                        LyricLine(l.t, l.text, l.words.chunked(3).map { (t, a, b) -> LyricWord(t, a.toInt(), b.toInt()) })
                    },
                    synced = s.synced,
                )
            )
        }
    }.getOrNull()

    private fun writeDisk(id: String, result: LyricsResult) {
        val stored = when (result) {
            LyricsResult.Missing -> Stored(missing = true)
            LyricsResult.Instrumental -> Stored(instrumental = true)
            is LyricsResult.Found -> Stored(
                synced = result.lyrics.synced,
                lines = result.lyrics.lines.map { l ->
                    StoredLine(l.timeMs, l.text, l.words.flatMap { listOf(it.timeMs, it.start.toLong(), it.end.toLong()) })
                },
            )
        }
        runCatching { file(id).writeText(AppJson.encodeToString(Stored.serializer(), stored)) }
    }

    private companion object {
        const val TAG = "Lyrics"
        const val MISSING_TTL_MS = 3L * 24 * 3600 * 1000
    }
}
