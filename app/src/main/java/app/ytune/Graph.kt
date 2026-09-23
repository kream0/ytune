package app.ytune

import android.app.Application
import app.ytune.data.Library
import app.ytune.data.Settings
import app.ytune.download.DownloadManager
import app.ytune.glyph.NowPlayingInfo
import app.ytune.playback.PlayerConnection
import app.ytune.playback.QueueStore
import app.ytune.update.Updater
import app.ytune.yt.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Minimal service locator: the app is small enough not to need a DI framework. */
object Graph {
    lateinit var app: Application
        private set

    /** Process-wide scope; state bookkeeping runs on Main, IO work switches dispatchers. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val settings: Settings by lazy { Settings(app) }
    val library: Library by lazy { Library(app, scope) }
    val downloads: DownloadManager by lazy { DownloadManager(app, library, settings, http, scope) }
    val player: PlayerConnection by lazy { PlayerConnection(app) }
    val queueStore: QueueStore by lazy { QueueStore(app) }
    val updater: Updater by lazy { Updater(app, http, settings, scope) }

    /** Published by the playback service; read by the Glyph Matrix renderers. */
    val nowPlaying = MutableStateFlow<NowPlayingInfo?>(null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun toast(message: String) {
        _messages.tryEmit(message)
    }

    fun init(application: Application) {
        app = application
        YouTube.init(http)
    }
}
