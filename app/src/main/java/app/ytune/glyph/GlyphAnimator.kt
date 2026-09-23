package app.ytune.glyph

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Drives the marquee: re-lays the text when the track changes, advances one dot per frame,
 * rests briefly at the start of each loop, and hands every rendered frame to [output].
 */
class GlyphAnimator(
    private val renderer: MatrixRenderer,
    private val fallback: ((String) -> IntArray?)? = null,
    private val clock: () -> Long,
) {
    suspend fun run(
        source: () -> NowPlayingInfo?,
        output: (IntArray) -> Unit,
        idleLabel: String = "YTUNE",
        scrollWhenPaused: Boolean = false,
    ) {
        var key: String? = null
        var strip = DotStrip(IntArray(0))
        var scroll = 0
        var rest = 0
        var hold = 0
        var tick = 0
        while (currentCoroutineContext().isActive) {
            val info = source()
            val label = info?.label ?: idleLabel
            val newKey = (info?.id ?: "") + "|" + label
            if (newKey != key) {
                key = newKey
                strip = DotText.layout(label, fallback)
                rest = renderer.loopLength(strip) - LEAD
                scroll = rest
                hold = HOLD_FRAMES
            }
            val playing = info?.isPlaying == true
            val progress = info?.let {
                if (it.durationMs > 0) it.positionAt(clock()).toFloat() / it.durationMs else 0f
            } ?: 0f

            output(renderer.render(strip, scroll, progress, playing, tick))
            tick++

            if (renderer.needsScroll(strip) && (playing || scrollWhenPaused)) {
                if (hold > 0) {
                    hold--
                } else {
                    scroll = (scroll + 1) % renderer.loopLength(strip)
                    if (scroll == rest) hold = HOLD_FRAMES
                }
            }
            delay(FRAME_MS)
        }
    }

    companion object {
        const val FRAME_MS = 70L
        private const val HOLD_FRAMES = 20
        /** Text rests two dots in from the left edge, clear of the round matrix's rim. */
        private const val LEAD = 2
    }
}
