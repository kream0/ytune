package app.ytune.glyph

import android.content.Context
import android.os.SystemClock

/**
 * One producer of Glyph Matrix frames on the shared [GlyphLink].
 *
 * [toyMode] = true when running as a Glyph Toy (the system bound us via the Glyph Button or as
 * the always-on toy); otherwise frames go through the "app" channel, which ranks below toys and
 * notifications. Identical frames are only re-sent every couple of seconds, so a still picture
 * costs almost nothing.
 */
class GlyphSession(context: Context, private val toyMode: Boolean) {
    private val appContext = context.applicationContext
    private var open = false
    private var last: IntArray? = null
    private var sent: IntArray? = null
    private var sentAt = 0L
    private var shown = false

    fun open() {
        if (open) return
        open = true
        GlyphLink.acquire(appContext, this, toyMode)
    }

    /** Frames arriving before the service connects are held; the next one goes out once it does. */
    fun show(frame: IntArray, force: Boolean = false) {
        last = frame
        if (!open) open()
        val m = GlyphLink.ready ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && frame.contentEquals(sent) && now - sentAt < RESEND_MS) return
        runCatching { if (toyMode) m.setMatrixFrame(frame) else m.setAppMatrixFrame(frame) }
            .onSuccess {
                sent = frame
                sentAt = now
                shown = true
                GlyphLink.frameSent(toyMode)
            }
            .onFailure { GlyphLink.fail("Frame rejected: ${it.message}") }
    }

    /** Re-sends the current picture (e.g. when the always-on toy is woken up). */
    fun refresh() {
        last?.let { show(it, force = true) }
    }

    fun clear() {
        last = null
        sent = null
        if (!shown) return
        shown = false
        val m = GlyphLink.ready ?: return
        runCatching { if (toyMode) m.turnOff() else m.closeAppMatrix() }
    }

    fun close() {
        clear()
        if (open) GlyphLink.release(this)
        open = false
    }

    private companion object {
        const val RESEND_MS = 2_000L
    }
}
