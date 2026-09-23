package app.ytune.glyph

import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.nothing.ketchum.GlyphMatrixManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the Glyph service is doing for us, shown in Settings so a dark matrix can be explained. */
data class GlyphStatus(
    /** Nothing's Glyph service is installed and visible to us (null = not looked up yet). */
    val serviceFound: Boolean? = null,
    /** We asked to bind and are waiting for (or holding) the connection. */
    val linking: Boolean = false,
    val connected: Boolean = false,
    /** Result of register(): false means the service refused YTune. */
    val registered: Boolean? = null,
    val appFrames: Int = 0,
    val toyFrames: Int = 0,
    val toyBound: Boolean = false,
    val lastToyEvent: String? = null,
    val error: String? = null,
)

/**
 * The one binding to Nothing's Glyph service. The SDK's [GlyphMatrixManager] is a process-wide
 * singleton with a single callback and ServiceConnection, so the app channel, the Glyph Toy and
 * the Settings test all share this link rather than each calling init() / unInit() on it
 * (a second init() never gets onServiceConnected, and one unInit() cuts everyone off).
 * Main thread only.
 */
object GlyphLink {
    private const val TAG = "GlyphLink"

    private val _status = MutableStateFlow(GlyphStatus())
    val status: StateFlow<GlyphStatus> = _status.asStateFlow()

    private var manager: GlyphMatrixManager? = null
    private val owners = mutableMapOf<Any, Boolean>() // owner -> toy mode

    private val callback = object : GlyphMatrixManager.Callback {
        override fun onServiceConnected(name: ComponentName?) {
            val m = manager ?: return
            val ok = GlyphSupport.deviceId()?.let { id ->
                runCatching { m.register(id) }.onFailure { Log.w(TAG, "register failed", it) }.getOrNull()
            }
            _status.update { it.copy(connected = true, registered = ok) }
            if (owners.containsValue(true)) keepToyAwake(m)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _status.update { it.copy(connected = false) }
        }
    }

    /** The manager, once the service is connected; frames sent before that would be lost. */
    val ready: GlyphMatrixManager? get() = manager?.takeIf { _status.value.connected }

    fun acquire(context: Context, owner: Any, toyMode: Boolean) {
        owners[owner] = toyMode
        if (toyMode) _status.update { it.copy(toyBound = true) }
        manager?.let { m ->
            if (toyMode && _status.value.connected) keepToyAwake(m)
            return
        }
        val app = context.applicationContext
        _status.update { it.copy(serviceFound = GlyphSupport.serviceInstalled(app), linking = true, error = null) }
        val m = runCatching { GlyphMatrixManager.getInstance(app) }
            .onFailure { fail("SDK unavailable: ${it.message}") }
            .getOrNull() ?: return
        manager = m
        runCatching { m.init(callback) }.onFailure { fail("Couldn't bind the Glyph service: ${it.message}") }
    }

    fun release(owner: Any) {
        val toyMode = owners.remove(owner) ?: return
        if (toyMode && !owners.containsValue(true)) _status.update { it.copy(toyBound = false) }
        if (owners.isNotEmpty()) return
        runCatching { manager?.unInit() }
        manager = null
        _status.update { it.copy(linking = false, connected = false) }
    }

    fun frameSent(toyMode: Boolean) = _status.update {
        val s = it.copy(error = null)
        if (toyMode) s.copy(toyFrames = s.toyFrames + 1) else s.copy(appFrames = s.appFrames + 1)
    }

    fun toyEvent(event: String) = _status.update { it.copy(lastToyEvent = event) }

    fun fail(message: String) {
        Log.w(TAG, message)
        _status.update { it.copy(error = message) }
    }

    /** Toys time out on their own after a while; ours should stay up while it's selected. */
    private fun keepToyAwake(m: GlyphMatrixManager) {
        runCatching { m.setGlyphMatrixTimeout(false) }.onFailure { Log.w(TAG, "timeout", it) }
    }
}
