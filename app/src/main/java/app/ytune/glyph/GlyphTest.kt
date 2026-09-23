package app.ytune.glyph

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Settings › Glyph Matrix › Test: a few seconds of marquee through the app channel, so the whole
 * path (service, registration, frames) can be checked without playing anything. Now-playing
 * frames hold off meanwhile.
 */
object GlyphTest {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    suspend fun run(context: Context, durationMs: Long = 8_000L) {
        if (_running.value || !GlyphSupport.isSupported) return
        _running.value = true
        val session = GlyphSession(context, toyMode = false)
        val animator = GlyphAnimator(
            renderer = MatrixRenderer(GlyphSupport.matrixSize),
            fallback = Raster::columns,
            clock = SystemClock::elapsedRealtime,
        )
        try {
            withTimeoutOrNull(durationMs) {
                animator.run(
                    source = { null },
                    output = { session.show(it) },
                    idleLabel = "YTUNE  ·  GLYPH TEST",
                    scrollWhenPaused = true,
                )
            }
        } finally {
            session.close()
            _running.value = false
        }
    }
}
