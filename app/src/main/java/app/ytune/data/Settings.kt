package app.ytune.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StreamMode(val label: String) {
    STREAM("STREAM"),
    STREAM_AND_DOWNLOAD("STREAM + DL"),
}

enum class DownloadStrategy(val label: String, val description: String) {
    PROGRESSIVE(
        "PROGRESSIVE",
        "Downloads the track you're playing plus the next few in the queue, as you listen.",
    ),
    ALL_AT_ONCE(
        "ALL AT ONCE",
        "Every track added to the queue (e.g. a whole playlist) is downloaded right away.",
    ),
}

enum class AudioQuality(val label: String, val description: String) {
    BEST("BEST", "Opus ~160 kbps (WebM)"),
    COMPATIBLE("M4A", "AAC ~128 kbps, plays everywhere"),
    SAVER("SAVER", "Lowest bitrate, saves data"),
}

data class AppSettings(
    val mode: StreamMode = StreamMode.STREAM,
    val strategy: DownloadStrategy = DownloadStrategy.PROGRESSIVE,
    val lookahead: Int = 2,
    val quality: AudioQuality = AudioQuality.BEST,
    val wifiOnly: Boolean = false,
    val parallel: Int = 2,
    val dotArtwork: Boolean = true,
    val searchFilter: String = "SONGS",
    val glyphMatrix: Boolean = true,
    val autoUpdate: Boolean = true,
    /** When the queue runs out, keep going with YouTube's suggestions for the last song. */
    val autoplay: Boolean = true,
    /** Player shows synced lyrics instead of the cover (tap the cover to cycle dots / photo / lyrics). */
    val lyricsView: Boolean = false,
) {
    val downloadWhileStreaming: Boolean get() = mode == StreamMode.STREAM_AND_DOWNLOAD
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("ytune_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    fun update(block: (AppSettings) -> AppSettings) {
        val next = block(_state.value)
        _state.value = next
        save(next)
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            mode = enumOr(prefs.getString("mode", null), d.mode),
            strategy = enumOr(prefs.getString("strategy", null), d.strategy),
            lookahead = prefs.getInt("lookahead", d.lookahead).coerceIn(1, 5),
            quality = enumOr(prefs.getString("quality", null), d.quality),
            wifiOnly = prefs.getBoolean("wifiOnly", d.wifiOnly),
            parallel = prefs.getInt("parallel", d.parallel).coerceIn(1, 4),
            dotArtwork = prefs.getBoolean("dotArtwork", d.dotArtwork),
            searchFilter = prefs.getString("searchFilter", d.searchFilter) ?: d.searchFilter,
            glyphMatrix = prefs.getBoolean("glyphMatrix", d.glyphMatrix),
            autoUpdate = prefs.getBoolean("autoUpdate", d.autoUpdate),
            autoplay = prefs.getBoolean("autoplay", d.autoplay),
            lyricsView = prefs.getBoolean("lyricsView", d.lyricsView),
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putString("mode", s.mode.name)
            .putString("strategy", s.strategy.name)
            .putInt("lookahead", s.lookahead)
            .putString("quality", s.quality.name)
            .putBoolean("wifiOnly", s.wifiOnly)
            .putInt("parallel", s.parallel)
            .putBoolean("dotArtwork", s.dotArtwork)
            .putString("searchFilter", s.searchFilter)
            .putBoolean("glyphMatrix", s.glyphMatrix)
            .putBoolean("autoUpdate", s.autoUpdate)
            .putBoolean("autoplay", s.autoplay)
            .putBoolean("lyricsView", s.lyricsView)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
