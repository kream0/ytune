package app.ytune.data

import android.content.Context
import app.ytune.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StreamMode(val label: String) {
    STREAM("STREAM"),
    STREAM_AND_DOWNLOAD("STREAM + DL"),
}

enum class DownloadStrategy(
    private val labelEn: String,
    private val labelFr: String,
    private val descriptionEn: String,
    private val descriptionFr: String,
) {
    PROGRESSIVE(
        "PROGRESSIVE",
        "PROGRESSIF",
        "Downloads the track you're playing plus the next few in the queue, as you listen.",
        "Télécharge le titre en cours et les suivants dans la file, au fil de l'écoute.",
    ),
    ALL_AT_ONCE(
        "ALL AT ONCE",
        "TOUT D'UN COUP",
        "Every track added to the queue (e.g. a whole playlist) is downloaded right away.",
        "Chaque titre ajouté à la file (une playlist entière, par exemple) est téléchargé tout de suite.",
    );

    val label: String get() = tr(labelEn, labelFr)
    val description: String get() = tr(descriptionEn, descriptionFr)
}

enum class AudioQuality(
    private val labelEn: String,
    private val labelFr: String,
    private val descriptionEn: String,
    private val descriptionFr: String,
) {
    BEST("BEST", "MEILLEURE", "Opus ~160 kbps (WebM)", "Opus ~160 kbit/s (WebM)"),
    COMPATIBLE("M4A", "M4A", "AAC ~128 kbps, plays everywhere", "AAC ~128 kbit/s, lisible partout"),
    SAVER("SAVER", "ÉCO", "Lowest bitrate, saves data", "Débit minimal, économise les données");

    val label: String get() = tr(labelEn, labelFr)
    val description: String get() = tr(descriptionEn, descriptionFr)
}

/** The app's language; French unless you pick English (Settings → Appearance). */
enum class Language(val label: String) {
    FRENCH("FRANÇAIS"),
    ENGLISH("ENGLISH"),
}

enum class ThemeMode(
    private val labelEn: String,
    private val labelFr: String,
    private val descriptionEn: String,
    private val descriptionFr: String,
) {
    SYSTEM(
        "SYSTEM",
        "SYSTÈME",
        "Follows the phone's dark mode: black when it's on, paper when it's off.",
        "Suit le mode sombre du téléphone : noir s'il est activé, papier sinon.",
    ),
    DARK("DARK", "SOMBRE", "Black, like Nothing OS.", "Noir, comme Nothing OS."),
    PAPER("PAPER", "PAPIER", "Warm paper instead of white, with black ink.", "Un papier chaud plutôt que du blanc, à l'encre noire.");

    val label: String get() = tr(labelEn, labelFr)
    val description: String get() = tr(descriptionEn, descriptionFr)
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
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: Language = Language.FRENCH,
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
            theme = enumOr(prefs.getString("theme", null), d.theme),
            language = enumOr(prefs.getString("language", null), d.language),
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
            .putString("theme", s.theme.name)
            .putString("language", s.language.name)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
