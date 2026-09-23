package app.ytune.ui.components

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import app.ytune.Graph
import app.ytune.glyph.GlyphAnimator
import app.ytune.glyph.MatrixRenderer
import app.ytune.glyph.NowPlayingInfo
import app.ytune.glyph.Raster
import app.ytune.ui.theme.P

/** On-screen replica of the Glyph Matrix, driven by the exact same renderer as the LEDs. */
@Composable
fun GlyphMatrixPreview(matrix: Int, modifier: Modifier = Modifier, dotColor: Color = P.text, offColor: Color = P.dotOff) {
    val renderer = remember(matrix) { MatrixRenderer(matrix) }
    var frame by remember(matrix) { mutableStateOf(IntArray(matrix * matrix)) }
    val sample = remember {
        NowPlayingInfo(
            id = "sample",
            title = "Nothing playing",
            artist = "YTune",
            isPlaying = true,
            positionMs = 0,
            durationMs = 180_000,
            sampledAt = SystemClock.elapsedRealtime(),
        )
    }
    LaunchedEffect(renderer) {
        GlyphAnimator(renderer, Raster::columns, SystemClock::elapsedRealtime).run(
            source = { Graph.nowPlaying.value ?: sample },
            output = { frame = it },
            scrollWhenPaused = true,
        )
    }
    Canvas(modifier.aspectRatio(1f)) {
        val cell = size.minDimension / matrix
        val r = cell * 0.36f
        val current = frame
        for (i in current.indices) {
            if (!renderer.visible[i]) continue
            val v = current[i]
            val color = if (v > 0) dotColor.copy(alpha = 0.2f + 0.8f * v / 255f) else offColor
            drawCircle(color, r, Offset((i % matrix) * cell + cell / 2, (i / matrix) * cell + cell / 2))
        }
    }
}
