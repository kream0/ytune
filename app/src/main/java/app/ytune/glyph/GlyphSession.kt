package app.ytune.glyph

import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.nothing.ketchum.GlyphMatrixManager

/**
 * Thin, crash-proof wrapper around [GlyphMatrixManager].
 *
 * [toyMode] = true when running as a Glyph Toy (the system bound us via the Glyph Button);
 * otherwise frames go through the "app" channel, which ranks below toys and notifications.
 */
class GlyphSession(context: Context, private val toyMode: Boolean) {
    private val appContext = context.applicationContext
    private var manager: GlyphMatrixManager? = null
    private var connected = false
    private var pending: IntArray? = null

    private val callback = object : GlyphMatrixManager.Callback {
        override fun onServiceConnected(name: ComponentName?) {
            val m = manager ?: return
            runCatching { GlyphSupport.deviceId()?.let { m.register(it) } }
                .onFailure { Log.w(TAG, "register failed", it) }
            if (toyMode) runCatching { m.setGlyphMatrixTimeout(false) }
            connected = true
            pending?.let { show(it) }
            pending = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connected = false
        }
    }

    fun open() {
        if (manager != null) return
        manager = runCatching { GlyphMatrixManager.getInstance(appContext) }.getOrNull()
        runCatching { manager?.init(callback) }.onFailure { Log.w(TAG, "init failed", it) }
    }

    fun show(frame: IntArray) {
        if (!connected) {
            pending = frame
            open()
            return
        }
        val m = manager ?: return
        runCatching { if (toyMode) m.setMatrixFrame(frame) else m.setAppMatrixFrame(frame) }
            .onFailure { Log.w(TAG, "frame rejected", it) }
    }

    fun clear() {
        pending = null
        val m = manager ?: return
        if (!connected) return
        runCatching { if (toyMode) m.turnOff() else m.closeAppMatrix() }
    }

    fun close() {
        clear()
        runCatching { manager?.unInit() }
        manager = null
        connected = false
    }

    private companion object {
        const val TAG = "GlyphSession"
    }
}
