package app.ytune.lyrics

import java.text.Normalizer

/**
 * Recognises the same song across different YouTube uploads: "Artist - Song (Official Video)",
 * "Song" on the artist's Topic channel, "Artist - Song (Lyrics)" on some lyrics channel… It uses
 * the same title clean-up as the lyrics search, then compares titles exactly and artists loosely
 * (one containing the other, e.g. "daftpunk" / "daftpunkofficial"). Remixes, live and other
 * versions keep their "(Remix)" / "(Live)" and so stay distinct; covers differ by artist.
 */
class SameSong {
    private val artistsByTitle = HashMap<String, MutableList<String>>()

    fun add(title: String, artist: String) {
        val (t, a) = key(title, artist)
        if (t.isNotEmpty()) artistsByTitle.getOrPut(t) { mutableListOf() } += a
    }

    operator fun contains(song: Pair<String, String>): Boolean {
        val (t, a) = key(song.first, song.second)
        val artists = artistsByTitle[t] ?: return false
        return artists.any { it.isEmpty() || a.isEmpty() || it.contains(a) || a.contains(it) }
    }

    companion object {
        private val marks = Regex("""\p{Mn}+""")
        private val nonAlnum = Regex("""[^\p{L}\p{N}]+""")

        /** (title, artist), lower-cased, accents and punctuation stripped. */
        fun key(title: String, artist: String): Pair<String, String> {
            val q = LyricsQuery.candidates(title, artist).firstOrNull()
            return norm(q?.title ?: title) to norm(q?.artist ?: artist)
        }

        /** Lower case, no accents, letters and digits only: "Beyoncé – Halo!" → "beyoncehalo". */
        fun norm(s: String): String =
            nonAlnum.replace(marks.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), ""), "")
    }
}
