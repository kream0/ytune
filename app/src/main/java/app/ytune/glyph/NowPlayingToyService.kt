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
 * "YTune" Glyph Toy: the scrolling title, a progress bar and (on the Phone (3)) a live equalizer.
 *
 * - Phone (3): pick it with the Glyph Button; long-press the button to play / pause. Toys
 *   outrank the app channel, so this also works over other Glyph content.
 * - Phone (4a) Pro: only always-on (AOD) toys exist there. Choose it under Settings › Glyph
 *   Interface › Flip to Glyph › Always-on Glyph Toy; the system then sends EVENT_AOD every minute.
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
            val event = msg.data?.getString(GlyphToy.MSG_GLYPH_TOY_DATA) ?: return
            GlyphLink.toyEvent(event)
            when (event) {
                GlyphToy.EVENT_CHANGE -> Graph.player.togglePlay() // long press
                GlyphToy.EVENT_AOD -> session?.refresh() // always-on wake-up: repaint now
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
            // Without a Glyph Button (4a Pro) there's nothing to hold, and an always-on toy
            // should sit still while the music is paused.
            val touch = GlyphSupport.hasGlyphTouch
            loop = scope.launch {
                animator.run(
                    source = { Graph.nowPlaying.value },
                    output = { s.show(it) },
                    idleLabel = if (touch) "YTUNE  ·  HOLD TO PLAY" else "YTUNE",
                    scrollWhenPaused = touch,
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
