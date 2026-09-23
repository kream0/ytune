package app.ytune.glyph

import java.text.Normalizer
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A horizontal run of dot columns (bit n = row n), ready to scroll across the matrix. */
class DotStrip(val columns: IntArray) {
    val width: Int get() = columns.size
}

/** What the Glyph Matrix should show right now. */
data class NowPlayingInfo(
    val id: String,
    val title: String,
    val artist: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** SystemClock.elapsedRealtime() when [positionMs] was sampled. */
    val sampledAt: Long,
) {
    fun positionAt(now: Long): Long =
        if (isPlaying) (positionMs + (now - sampledAt)).coerceAtMost(durationMs.coerceAtLeast(0)) else positionMs

    val label: String get() = if (artist.isBlank()) title else "$title  ·  $artist"
}

/** Lays text out with [DotFont]; characters it can't draw go through [fallback] (or are skipped). */
object DotText {
    private const val SPACE_WIDTH = 3
    private val SEPARATOR = intArrayOf(1 shl 4) // a single mid-height dot

    private val replacements = mapOf(
        '‘' to "'", '’' to "'", '“' to "\"", '”' to "\"",
        '–' to "-", '—' to "-", '…' to "...", '×' to "x",
        'ß' to "ss", 'Æ' to "AE", 'æ' to "ae", 'Œ' to "OE", 'œ' to "oe",
        'Ø' to "O", 'ø' to "o", 'Ł' to "L", 'ł' to "l",
    )

    fun layout(text: String, fallback: ((String) -> IntArray?)? = null): DotStrip {
        val cols = ArrayList<Int>(text.length * 6)
        val simplified = simplify(text)
        var i = 0
        while (i < simplified.length) {
            val cp = simplified.codePointAt(i)
            val len = Character.charCount(cp)
            val ch = simplified[i]
            when {
                ch == ' ' -> repeat(SPACE_WIDTH) { cols += 0 }
                ch == '·' -> { cols.addAll(SEPARATOR.toList()); cols += 0 }
                len == 1 && DotFont.glyph(ch) != null -> {
                    cols.addAll(DotFont.glyph(ch)!!.toList())
                    cols += 0
                }
                isDecorative(cp) -> Unit // emoji & co. don't survive 25 px
                else -> fallback?.invoke(simplified.substring(i, i + len))?.let {
                    cols.addAll(it.toList())
                    cols += 0
                }
            }
            i += len
        }
        while (cols.isNotEmpty() && cols.last() == 0) cols.removeAt(cols.lastIndex)
        return DotStrip(cols.toIntArray())
    }

    /** Strips accents (é → e) and maps typographic punctuation to what the dot font has. */
    private fun simplify(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val r = replacements[ch]
            if (r != null) sb.append(r) else sb.append(ch)
        }
        return Normalizer.normalize(sb, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    }

    private fun isDecorative(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.OTHER_SYMBOL.toInt() || type == Character.SURROGATE.toInt() ||
            type == Character.NON_SPACING_MARK.toInt() || type == Character.FORMAT.toInt() ||
            cp == 0xFE0F
    }
}

/**
 * Composes one Glyph Matrix frame (size × size brightness values, row-major, 0..255):
 * a scrolling title in the middle, a tiny equalizer / pause mark on top and a dotted
 * progress bar at the bottom. Pure Kotlin so it also drives the in-app preview.
 */
class MatrixRenderer(val size: Int) {
    private val textTop: Int
    private val progressRow: Int
    private val progressFrom: Int
    private val progressTo: Int
    private val showTopMark: Boolean = size >= 20

    /** Dots that physically exist on the round matrix. */
    val visible: BooleanArray

    init {
        if (size >= 20) {
            textTop = (size - DotFont.HEIGHT) / 2 - 1 // rows 7..16 on a 25×25
            progressRow = size - 5
            progressFrom = 4
            progressTo = size - 5
        } else {
            textTop = 1
            progressRow = size - 1
            progressFrom = size / 2 - 2
            progressTo = size / 2 + 2
        }
        val c = (size - 1) / 2f
        val r = size / 2f + 0.25f
        visible = BooleanArray(size * size) { idx ->
            hypot((idx % size) - c, (idx / size) - c) <= r
        }
    }

    /** Scroll period for a strip: its width plus a gap before it repeats. */
    fun loopLength(strip: DotStrip): Int = strip.width + GAP

    /** True if the text is too wide to sit still in the middle. */
    fun needsScroll(strip: DotStrip): Boolean = strip.width > size - 2

    fun render(
        strip: DotStrip,
        scroll: Int,
        progress: Float,
        playing: Boolean,
        tick: Int,
    ): IntArray {
        val frame = IntArray(size * size)

        // Title
        val scrolling = needsScroll(strip)
        val loop = loopLength(strip)
        val start = if (scrolling) 0 else (size - strip.width) / 2
        for (x in 0 until size) {
            val col = if (scrolling) {
                val i = ((scroll + x) % loop + loop) % loop
                if (i < strip.width) strip.columns[i] else 0
            } else {
                val i = x - start
                if (i in 0 until strip.width) strip.columns[i] else 0
            }
            if (col == 0) continue
            for (row in 0 until DotFont.HEIGHT) {
                if (col and (1 shl row) != 0) set(frame, x, textTop + row, if (playing) 255 else 150)
            }
        }

        // Top mark: 3-bar equalizer while playing, pause bars otherwise
        if (showTopMark) {
            val base = textTop - 3
            if (playing) {
                val levels = intArrayOf(eq(tick, 0), eq(tick, 1), eq(tick, 2))
                for (b in 0..2) for (h in 0 until levels[b]) set(frame, size / 2 - 2 + b * 2, base - h, 200)
            } else {
                for (h in 0..2) {
                    set(frame, size / 2 - 1, base - h, 200)
                    set(frame, size / 2 + 1, base - h, 200)
                }
            }
        }

        // Progress
        val span = progressTo - progressFrom
        if (span > 0) {
            val head = progressFrom + (progress.coerceIn(0f, 1f) * span).roundToInt()
            for (x in progressFrom..progressTo) {
                set(frame, x, progressRow, if (x <= head) 255 else 28)
            }
        }

        for (i in frame.indices) if (!visible[i]) frame[i] = 0
        return frame
    }

    private fun eq(tick: Int, bar: Int): Int {
        val pattern = EQ[bar]
        return pattern[(tick / 3) % pattern.size]
    }

    private fun set(frame: IntArray, x: Int, y: Int, value: Int) {
        if (x in 0 until size && y in 0 until size) frame[y * size + x] = value
    }

    companion object {
        const val GAP = 14
        private val EQ = arrayOf(
            intArrayOf(1, 2, 3, 4, 3, 2, 3, 1),
            intArrayOf(3, 4, 2, 1, 2, 4, 3, 2),
            intArrayOf(2, 1, 3, 2, 4, 3, 1, 3),
        )
    }
}
