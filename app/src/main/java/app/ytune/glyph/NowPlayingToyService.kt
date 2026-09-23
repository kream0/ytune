package app.ytune.glyph

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import app.ytune.Graph
import com.nothing.ketchum.GlyphToy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "YTune" Glyph Toy. Pick it with the Glyph Button on a Phone (3) and the matrix shows the
 * scrolling title, a live equalizer and a progress bar; long-press the Glyph Button to
 * play / pause. Toys outrank the app channel, so this also works over other Glyph content.
 */
class NowPlayingToyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: GlyphSession? = null
    private var loop: Job? = null

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != GlyphToy.MSG_GLYPH_TOY) {
                super.handleMessage(msg)
                return
            }
            when (msg.data?.getString(GlyphToy.MSG_GLYPH_TOY_DATA)) {
                GlyphToy.EVENT_CHANGE -> Graph.player.togglePlay() // long press
            }
        }
    }
    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder? {
        if (GlyphSupport.isSupported && loop == null) {
            val s = GlyphSession(this, toyMode = true).also { it.open() }
            session = s
            val animator = GlyphAnimator(
                renderer = MatrixRenderer(GlyphSupport.matrixSize),
                fallback = Raster::columns,
                clock = SystemClock::elapsedRealtime,
            )
            loop = scope.launch {
                animator.run(
                    source = { Graph.nowPlaying.value },
                    output = s::show,
                    idleLabel = "YTUNE  ·  HOLD TO PLAY",
                    scrollWhenPaused = true,
                )
            }
        }
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        loop?.cancel()
        loop = null
        session?.close()
        session = null
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
