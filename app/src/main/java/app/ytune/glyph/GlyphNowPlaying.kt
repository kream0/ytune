package app.ytune.glyph

import android.content.Context
import android.os.SystemClock
import app.ytune.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * While music plays, scrolls "title · artist" across the Glyph Matrix on the back of the
 * phone (app channel). Stops and clears the matrix on pause. Owned by the playback service.
 */
class GlyphNowPlaying(context: Context, private val scope: CoroutineScope) {
    private val session = GlyphSession(context, toyMode = false)
    private val animator = GlyphAnimator(
        renderer = MatrixRenderer(GlyphSupport.matrixSize),
        fallback = Raster::columns,
        clock = SystemClock::elapsedRealtime,
    )
    private var watcher: Job? = null
    private var loop: Job? = null

    fun start() {
        if (watcher != null) return
        watcher = scope.launch {
            combine(Graph.nowPlaying, Graph.settings.state) { info, settings ->
                settings.glyphMatrix && info?.isPlaying == true
            }.distinctUntilChanged().collect { show -> if (show) startLoop() else stopLoop() }
        }
    }

    private fun startLoop() {
        if (loop?.isActive == true) return
        session.open()
        loop = scope.launch {
            animator.run(
                source = { Graph.nowPlaying.value },
                output = { if (!GlyphTest.running.value) session.show(it) },
            )
        }
    }

    private fun stopLoop() {
        loop?.cancel()
        loop = null
        session.clear()
    }

    fun release() {
        watcher?.cancel()
        watcher = null
        stopLoop()
        session.close()
    }
}
