package app.ytune.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.ytune.ui.theme.P
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Dot-matrix glyphs, Nothing Glyph style. 'X' = lit dot. */
object DotGlyphs {
    val PLAY = listOf(
        "X......",
        "XXX....",
        "XXXXX..",
        "XXXXXXX",
        "XXXXX..",
        "XXX....",
        "X......",
    )
    val PAUSE = listOf(
        "XX...XX",
        "XX...XX",
        "XX...XX",
        "XX...XX",
        "XX...XX",
        "XX...XX",
        "XX...XX",
    )
    val NEXT = listOf(
        "X....X",
        "XX...X",
        "XXX..X",
        "XXXX.X",
        "XXX..X",
        "XX...X",
        "X....X",
    )
    val PREV = listOf(
        "X....X",
        "X...XX",
        "X..XXX",
        "X.XXXX",
        "X..XXX",
        "X...XX",
        "X....X",
    )
}

@Composable
fun DotIcon(pattern: List<String>, color: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val rows = pattern.size
        val cols = pattern.maxOf { it.length }
        val cell = min(size.width / cols, size.height / rows)
        val r = cell * 0.4f
        val ox = (size.width - cell * cols) / 2f
        val oy = (size.height - cell * rows) / 2f
        pattern.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                if (c == 'X') {
                    drawCircle(color, r, Offset(ox + x * cell + cell / 2, oy + y * cell + cell / 2))
                }
            }
        }
    }
}

/**
 * Seek bar drawn as a row of dots; the playhead is a red dot. Tap or drag to seek.
 * [secondary] tints the dots ahead of the playhead up to that fraction: how much of the
 * track is saved offline (in the save colour) or, when streaming only, buffered (grey).
 */
@Composable
fun DotProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    secondary: Float = 0f,
    onSeek: ((Float) -> Unit)? = null,
    height: Dp = 22.dp,
    spacing: Dp = 7.dp,
    radius: Dp = 2.dp,
    activeColor: Color = P.text,
    secondaryColor: Color = P.textFaint,
    inactiveColor: Color = P.dotOff,
    headColor: Color = P.accent,
    showHead: Boolean = true,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val shown = (drag ?: progress).coerceIn(0f, 1f)

    val input = if (onSeek != null) {
        Modifier
            .pointerInput(Unit) {
                detectTapGestures { offset -> seek?.invoke((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        drag?.let { f -> seek?.invoke(f) }
                        drag = null
                    },
                    onDragCancel = { drag = null },
                ) { change, _ ->
                    change.consume()
                    drag = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            }
    } else {
        Modifier
    }

    Canvas(modifier.fillMaxWidth().height(height).then(input)) {
        val r = radius.toPx()
        val usable = size.width - 2 * r
        val n = max(2, (usable / spacing.toPx()).toInt() + 1)
        val step = usable / (n - 1)
        val head = (shown * (n - 1)).roundToInt()
        val cy = size.height / 2
        for (i in 0 until n) {
            val f = i.toFloat() / (n - 1)
            val color = when {
                i <= head && f <= shown + 0.0001f -> activeColor
                f <= secondary -> secondaryColor
                else -> inactiveColor
            }
            drawCircle(color, r, Offset(r + i * step, cy))
        }
        if (showHead) {
            drawCircle(headColor, r * 2.1f, Offset(r + head * step, cy))
        }
    }
}

/** Five dots with a travelling highlight. */
@Composable
fun DotLoader(modifier: Modifier = Modifier, color: Color = P.text) {
    val t = rememberInfiniteTransition(label = "loader")
    val pos by t.animateFloat(
        initialValue = -1f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "pos",
    )
    Canvas(modifier.size(width = 56.dp, height = 10.dp)) {
        val cell = size.width / 5
        val r = min(cell, size.height) * 0.32f
        for (i in 0 until 5) {
            val d = abs(pos - i)
            val a = (1f - d / 2f).coerceIn(0.15f, 1f)
            drawCircle(color.copy(alpha = a), r, Offset(i * cell + cell / 2, size.height / 2))
        }
    }
}

/** Tiny animated equalizer: 3 columns of dots. */
@Composable
fun DotEqualizer(playing: Boolean, modifier: Modifier = Modifier.size(18.dp), color: Color = P.accent) {
    val t = rememberInfiniteTransition(label = "eq")
    val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(620, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(1f, 0.3f, infiniteRepeatable(tween(480, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val c by t.animateFloat(0.4f, 0.95f, infiniteRepeatable(tween(760, easing = LinearEasing), RepeatMode.Reverse), label = "c")
    Canvas(modifier) {
        val levels = if (playing) floatArrayOf(a, b, c) else floatArrayOf(0.25f, 0.25f, 0.25f)
        val cols = 3
        val rows = 4
        val cw = size.width / cols
        val ch = size.height / rows
        val r = min(cw, ch) * 0.34f
        for (col in 0 until cols) {
            val lit = (levels[col] * rows).roundToInt().coerceIn(1, rows)
            for (row in 0 until rows) {
                val on = row >= rows - lit
                drawCircle(
                    if (on) color else color.copy(alpha = 0.18f),
                    r,
                    Offset(col * cw + cw / 2, row * ch + ch / 2),
                )
            }
        }
    }
}

/** Ring of dots used as a download progress indicator. */
@Composable
fun DotRing(
    progress: Float,
    modifier: Modifier = Modifier.size(18.dp),
    color: Color = P.text,
    offColor: Color = P.dotOff,
    spinning: Boolean = false,
    dots: Int = 12,
) {
    val spin: State<Float>? = if (spinning) {
        rememberInfiniteTransition(label = "ring")
            .animateFloat(0f, dots.toFloat(), infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "spin")
    } else {
        null
    }
    Canvas(modifier) {
        val radius = size.minDimension / 2
        val dotR = radius * 0.17f
        val ring = radius - dotR
        val lit = (progress * dots).roundToInt()
        val head = (spin?.value ?: 0f).toInt() % dots
        for (i in 0 until dots) {
            val angle = -PI / 2 + 2 * PI * i / dots
            val on = if (spinning) (i == head || i == (head + dots - 1) % dots) else i < lit
            drawCircle(
                if (on) color else offColor,
                dotR,
                center + Offset((cos(angle) * ring).toFloat(), (sin(angle) * ring).toFloat()),
            )
        }
    }
}

/** Faint dot grid used as a placeholder / background texture. */
@Composable
fun DotGrid(modifier: Modifier = Modifier, color: Color = P.dotOff, spacing: Dp = 10.dp, radius: Dp = 1.2.dp) {
    Canvas(modifier) {
        val s = spacing.toPx()
        val r = radius.toPx()
        var y = s / 2
        while (y < size.height) {
            var x = s / 2
            while (x < size.width) {
                drawCircle(color, r, Offset(x, y))
                x += s
            }
            y += s
        }
    }
}

/**
 * Renders album art as a halftone dot matrix, like a Glyph Matrix display: every cell becomes
 * a dot whose size follows the brightness of the picture underneath.
 */
@Composable
fun HalftoneArtwork(
    url: String?,
    modifier: Modifier = Modifier,
    grid: Int = 34,
    dotColor: Color = P.text,
    background: Color = P.background,
    invert: Boolean = !P.isDark,
    breathing: Boolean = false,
) {
    val context = LocalContext.current
    var levels by remember(url) { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(url, grid, invert) {
        levels = null
        if (url != null) levels = withContext(Dispatchers.IO) { loadLevels(context, url, grid, invert) }
    }
    val pulse: State<Float>? = if (breathing) {
        rememberInfiniteTransition(label = "breath").animateFloat(
            initialValue = 0.9f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pulse",
        )
    } else {
        null
    }
    Canvas(modifier) {
        val scale = pulse?.value ?: 1f
        drawRect(background)
        val cell = size.minDimension / grid
        val ox = (size.width - cell * grid) / 2
        val oy = (size.height - cell * grid) / 2
        val data = levels
        for (y in 0 until grid) {
            for (x in 0 until grid) {
                val v = data?.get(y * grid + x) ?: 0.18f
                val r = cell * 0.48f * (0.08f + 0.92f * v) * scale
                if (r < 0.4f) continue
                drawCircle(
                    if (data == null) dotColor.copy(alpha = 0.2f) else dotColor,
                    r,
                    Offset(ox + x * cell + cell / 2, oy + y * cell + cell / 2),
                )
            }
        }
    }
}

private suspend fun loadLevels(context: Context, url: String, grid: Int, invert: Boolean): FloatArray? {
    val request = ImageRequest.Builder(context)
        .data(url)
        .allowHardware(false)
        .size(grid * 6)
        .build()
    val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
    val source = result.drawable.toBitmap()
    val side = min(source.width, source.height)
    if (side <= 0) return null
    val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
    val small = Bitmap.createScaledBitmap(square, grid, grid, true)
    val out = FloatArray(grid * grid)
    var lo = 1f
    var hi = 0f
    for (y in 0 until grid) {
        for (x in 0 until grid) {
            val p = small.getPixel(x, y)
            val l = (0.299f * android.graphics.Color.red(p) +
                0.587f * android.graphics.Color.green(p) +
                0.114f * android.graphics.Color.blue(p)) / 255f
            out[y * grid + x] = l
            lo = min(lo, l)
            hi = max(hi, l)
        }
    }
    // Stretch contrast so dull covers still read well as dots.
    val range = (hi - lo).coerceAtLeast(0.05f)
    for (i in out.indices) {
        val v = ((out[i] - lo) / range).coerceIn(0f, 1f)
        out[i] = if (invert) 1f - v else v
    }
    return out
}
