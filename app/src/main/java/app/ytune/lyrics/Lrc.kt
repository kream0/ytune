package app.ytune.lyrics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A word (or syllable) of an "enhanced" LRC line: when it starts and which characters it covers. */
data class LyricWord(val timeMs: Long, val start: Int, val end: Int)

/** One lyric line. [words] is only filled when the lyrics have per-word timing. */
data class LyricLine(val timeMs: Long, val text: String, val words: List<LyricWord> = emptyList()) {
    /** An empty line in synced lyrics: an instrumental break. */
    val isBreak: Boolean get() = text.isBlank()
}

/** Parsed lyrics. [synced] = lines carry timestamps; otherwise it's plain text shown as-is. */
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean) {

    /** The line being sung at [positionMs], or -1 before the first one. */
    fun indexAt(positionMs: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /**
     * How far through line [index] the singing is at [positionMs], as a fraction of its
     * characters (0..1). With word timing, per word. Without it (most lyrics only time each
     * line), the line is spread over the time it actually lasts and swept at the pace of its
     * syllables, not its letters: Arabic, written without its short vowels, has far fewer
     * letters per sung second than English. When the line has room, its last word is held
     * longer, as singers do. (Tuned on the word timings of real recordings, Arabic, English
     * and mixed: the highlight is then about 0.8 s from the voice on average, where a steady
     * sweep per letter was 1.1 s off.)
     */
    fun progress(index: Int, positionMs: Long): Float {
        val line = lines.getOrNull(index) ?: return 0f
        if (line.text.isEmpty()) return 1f
        val next = lines.getOrNull(index + 1)?.timeMs
        if (line.words.isNotEmpty()) {
            val w = line.words.indexOfLast { it.timeMs <= positionMs }
            if (w < 0) return 0f
            val word = line.words[w]
            val wordEnd = line.words.getOrNull(w + 1)?.timeMs
                ?: next?.let { min(it, word.timeMs + 1_500) }
                ?: (word.timeMs + 800)
            val within = if (wordEnd > word.timeMs) {
                ((positionMs - word.timeMs).toFloat() / (wordEnd - word.timeMs)).coerceIn(0f, 1f)
            } else {
                1f
            }
            return ((word.start + (word.end - word.start) * within) / line.text.length).coerceIn(0f, 1f)
        }
        val pace = pace(index)
        if (pace.durationMs <= 0) return 1f
        val sung = ((positionMs - line.timeMs).toFloat() / pace.durationMs).coerceIn(0f, 1f)
        return pace.charsAt(sung) / line.text.length
    }

    private val paces = arrayOfNulls<Pace>(lines.size)

    private fun pace(index: Int): Pace = paces[index] ?: run {
        val line = lines[index]
        val gap = lines.getOrNull(index + 1)?.let { it.timeMs - line.timeMs }
        val syllables = Syllables.cumulative(line.text).last()
        // A packed line is sung straight through; one with room to spare usually ends on a
        // held note.
        val room = if (gap == null || syllables <= 0f) {
            1f
        } else {
            ((gap / syllables - PACKED_MS_PER_SYLLABLE) / (ROOMY_MS_PER_SYLLABLE - PACKED_MS_PER_SYLLABLE)).coerceIn(0f, 1f)
        }
        val cumulative = Syllables.cumulative(line.text, heldOnLastWord = HELD_SYLLABLES * room)
        // Sung up to (nearly) the next line or instrumental break, unless that's later than
        // even slow singing would take (an untimed pause in between).
        val longest = max(MIN_LINE_MS, (cumulative.last() * MAX_MS_PER_SYLLABLE).toLong())
        val duration = gap?.let { min((it * SUNG_SHARE_OF_GAP).toLong(), longest) } ?: longest
        Pace(duration, cumulative).also { paces[index] = it }
    }

    /**
     * How a line is swept: [cumulative] holds, for each character boundary, the share of the
     * line's syllables sung by then (0 at the start, the total at the end).
     */
    private class Pace(val durationMs: Long, private val cumulative: FloatArray) {
        /** Characters sung (fractional) once [sung] (0..1) of the line's time has passed. */
        fun charsAt(sung: Float): Float {
            val total = cumulative.last()
            if (total <= 0f) return sung * (cumulative.size - 1)
            val target = sung * total
            var lo = 0
            var hi = cumulative.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (cumulative[mid] <= target) lo = mid else hi = mid
            }
            val span = cumulative[hi] - cumulative[lo]
            return if (span <= 0f) hi.toFloat() else lo + (target - cumulative[lo]) / span
        }
    }

    companion object {
        private const val SUNG_SHARE_OF_GAP = 0.95
        /** Slower than this, the time is a pause rather than singing. */
        private const val MAX_MS_PER_SYLLABLE = 600f
        private const val MIN_LINE_MS = 900L
        /** How much longer the last word lasts in a line with room to spare. */
        private const val HELD_SYLLABLES = 2f
        private const val PACKED_MS_PER_SYLLABLE = 250f
        private const val ROOMY_MS_PER_SYLLABLE = 500f

        fun plain(text: String) = Lyrics(
            lines = text.lines().map { LyricLine(0, it.trim()) }.dropWhile { it.isBreak }.dropLastWhile { it.isBreak },
            synced = false,
        )
    }
}

/** LRC files: `[mm:ss.xx] line`, several stamps per line, `[offset:±ms]`, `<mm:ss.xx>` word stamps. */
object Lrc {
    private val lineStamp = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val wordStamp = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    fun parse(lrc: String): Lyrics {
        // A positive offset makes the lyrics come earlier.
        val offset = offsetTag.find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val out = mutableListOf<LyricLine>()
        for (raw in lrc.lineSequence()) {
            var rest = raw.trim()
            val stamps = mutableListOf<Long>()
            while (true) {
                val m = lineStamp.find(rest) ?: break
                stamps += toMs(m)
                rest = rest.substring(m.range.last + 1)
            }
            if (stamps.isEmpty()) continue // metadata ([ar:…]) or junk
            val (text, words) = splitWords(rest)
            val first = stamps.first()
            for (t in stamps) {
                val shift = t - first
                out += LyricLine(
                    timeMs = max(0, t - offset),
                    text = text,
                    words = words.map { it.copy(timeMs = max(0, it.timeMs + shift - offset)) },
                )
            }
        }
        out.sortBy { it.timeMs }
        // Collapse runs of empty lines and drop a leading one.
        val lines = out.filterIndexed { i, line -> !(line.isBreak && (i == 0 || out[i - 1].isBreak)) }
        return Lyrics(lines, synced = true)
    }

    private fun splitWords(text: String): Pair<String, List<LyricWord>> {
        val stamps = wordStamp.findAll(text).toList()
        if (stamps.isEmpty()) return text.trim() to emptyList()
        val clean = StringBuilder()
        val words = mutableListOf<LyricWord>()
        val lead = text.substring(0, stamps.first().range.first)
        clean.append(lead.trimStart())
        stamps.forEachIndexed { i, m ->
            val segEnd = stamps.getOrNull(i + 1)?.range?.first ?: text.length
            var seg = text.substring(m.range.last + 1, segEnd)
            if (clean.isEmpty()) seg = seg.trimStart()
            val start = clean.length
            clean.append(seg)
            if (seg.isNotBlank()) words += LyricWord(toMs(m), start, clean.length)
        }
        val trimmed = clean.toString().trimEnd()
        return trimmed to words.map { it.copy(end = min(it.end, trimmed.length)) }
    }

    private fun toMs(m: MatchResult): Long {
        val (min, sec, frac) = m.destructured
        val ms = if (frac.isEmpty()) 0 else frac.padEnd(3, '0').take(3).toInt()
        return min.toLong() * 60_000 + sec.toLong() * 1_000 + ms
    }
}

/** Turning a YouTube title / channel into something a lyrics database can find. */
object LyricsQuery {

    data class Query(val artist: String, val title: String)

    private val noise = Regex(
        """official|video|audio|lyrics?|letra|paroles|visuali[sz]er|clip|\bhd\b|\bhq\b|\b4k\b|remaster|\bmv\b|m/v|explicit|""" +
            """color coded|full album|topic|prod\.|""" + """\bft\.?\b|feat""",
        RegexOption.IGNORE_CASE,
    )
    private val brackets = Regex("""\s*[(\[【「][^)\]】」]*[)\]】」]""")
    private val featTail = Regex("""\s+(?:ft\.?|feat\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val separators = listOf(" - ", " – ", " — ", " ~ ")
    /** "Title | Channel promo" / "Title // Label": everything after is noise. */
    private val tails = listOf(" | ", " // ", " ｜ ")

    /** Most likely (artist, title) pairs first. */
    fun candidates(title: String, channel: String): List<Query> {
        val artist = cleanArtist(channel)
        val t = tails.fold(title.replace('“', '"').replace('”', '"')) { acc, s -> acc.substringBefore(s) }.trim()
        val out = mutableListOf<Query>()
        val sep = separators.firstOrNull { t.contains(it) }
        if (sep != null) {
            // "Artist - Title (Official Video)"
            val left = cleanArtist(t.substringBefore(sep))
            val right = separators.fold(t.substringAfter(sep)) { acc, s -> acc.substringBefore(s) }
            out += Query(left, cleanTitle(right))
            out += Query(left, bare(right))
            if (artist.isNotBlank()) out += Query(artist, cleanTitle(right))
        } else {
            out += Query(artist, cleanTitle(t))
            out += Query(artist, bare(t))
        }
        return out.filter { it.title.isNotBlank() }.distinct()
    }

    /** Drops "(Official Video)"-style brackets but keeps meaningful ones like "(Remix)". */
    fun cleanTitle(title: String): String =
        brackets.replace(title) { m -> if (noise.containsMatchIn(m.value)) "" else m.value }
            .replace(featTail, "")
            .replace("\"", "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()

    /** No brackets and no featured artists at all. */
    fun bare(title: String): String = cleanTitle(brackets.replace(title, ""))

    fun cleanArtist(channel: String): String =
        channel.trim()
            .removeSuffix(" - Topic")
            // "TheWeekndVEVO" -> "The Weeknd"
            .let { if (it.endsWith("vevo", ignoreCase = true)) it.dropLast(4).replace(Regex("(?<=[a-z])(?=[A-Z])"), " ") else it }
            .replace(Regex("""(?i)\s+(official|officiel)(\s+(channel|music))?$"""), "")
            .replace(featTail, "")
            .replace(Regex("""\s*[,&x×]\s+.*$"""), "") // first of several artists
            .trim()

    /** A result from the lyrics database, reduced to what matters for picking one. */
    data class Candidate(
        val durationSec: Double,
        val synced: String?,
        val plain: String?,
        val instrumental: Boolean,
    )

    /**
     * Best match for a song of [durationSec] (0 = unknown). Synced lyrics must be within a few
     * seconds of the song's length (otherwise it's another version, or a video with an intro,
     * and the timing would be off); failing that, plain lyrics of the closest one.
     */
    fun pick(results: List<Candidate>, durationSec: Long): Picked? {
        fun diff(c: Candidate) =
            if (durationSec > 0 && c.durationSec > 0) abs(c.durationSec - durationSec) else 0.0

        results.filter { !it.synced.isNullOrBlank() && diff(it) <= SYNC_TOLERANCE_SEC }
            .minByOrNull(::diff)
            ?.let { return Picked(Lrc.parse(it.synced!!), instrumental = false) }
        results.filter { it.instrumental && diff(it) <= SYNC_TOLERANCE_SEC }
            .firstOrNull()
            ?.let { return Picked(null, instrumental = true) }
        val close = results.filter { diff(it) <= LOOSE_TOLERANCE_SEC }.sortedBy(::diff)
        close.firstNotNullOfOrNull { c ->
            c.plain?.takeIf { it.isNotBlank() }?.let(Lyrics::plain)
                ?: c.synced?.takeIf { it.isNotBlank() }?.let { s -> Lrc.parse(s).copy(synced = false) }
        }?.let { return Picked(it, instrumental = false) }
        return null
    }

    data class Picked(val lyrics: Lyrics?, val instrumental: Boolean)

    private const val SYNC_TOLERANCE_SEC = 8.0
    private const val LOOSE_TOLERANCE_SEC = 60.0
}
