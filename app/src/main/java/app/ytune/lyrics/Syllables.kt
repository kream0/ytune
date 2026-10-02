package app.ytune.lyrics

/**
 * A rough count of the syllables sung in a lyric line, per script, used to pace lines that
 * have no word timing:
 * - Latin, Cyrillic, Greek: one per group of vowels ("waiting" → 2).
 * - Arabic, Hebrew: about one per two base letters, since short vowels aren't written and
 *   diacritics (ḥarakāt, niqqud) don't count ("حبيبي", five letters → 3).
 * - CJK, kana, hangul: one per character.
 */
object Syllables {

    /**
     * For each character boundary of [text] (0..length), the syllables sung up to it: a word's
     * syllables are spread evenly over its characters; spaces and punctuation take no time.
     * [heldOnLastWord] syllables' worth of time is added to the last word.
     */
    fun cumulative(text: String, heldOnLastWord: Float = 0f): FloatArray {
        val weights = FloatArray(text.length)
        var lastWord = -1 until -1
        var i = 0
        while (i < text.length) {
            if (!isWordChar(text, i)) {
                i++
                continue
            }
            var end = i
            while (end < text.length && isWordChar(text, end)) end++
            val share = count(text.substring(i, end)) / (end - i)
            for (k in i until end) weights[k] = share
            if (share > 0f) lastWord = i until end
            i = end
        }
        if (heldOnLastWord > 0f && !lastWord.isEmpty()) {
            val extra = heldOnLastWord / (lastWord.last - lastWord.first + 1)
            for (k in lastWord) weights[k] += extra
        }
        val out = FloatArray(text.length + 1)
        for (k in text.indices) out[k + 1] = out[k] + weights[k]
        return out
    }

    /** Syllables in one word (letters, marks and digits, with inner apostrophes). */
    fun count(word: String): Float {
        val letters = word.filter { Character.isLetter(it) && it != TATWEEL }
        if (letters.isEmpty()) return word.count { it.isDigit() }.toFloat()
        val first = letters.first()
        return when {
            isAbjad(first) -> maxOf(1f, Math.round(letters.length * ABJAD_SYLLABLES_PER_LETTER).toFloat())
            isSyllabic(first) -> letters.length.toFloat()
            else -> maxOf(1, vowelGroups(letters.lowercase())).toFloat()
        }
    }

    private fun vowelGroups(word: String): Int {
        var groups = 0
        var inVowel = false
        for (c in word) {
            val vowel = c in VOWELS
            if (vowel && !inVowel) groups++
            inVowel = vowel
        }
        return groups
    }

    private fun isWordChar(text: String, i: Int): Boolean {
        val c = text[i]
        if (Character.isLetterOrDigit(c) || Character.getType(c) == Character.NON_SPACING_MARK.toInt()) return true
        // "I've", "l'amour": an apostrophe between letters belongs to the word.
        return (c == '\'' || c == '’') && i > 0 && i < text.length - 1 &&
            Character.isLetter(text[i - 1]) && Character.isLetter(text[i + 1])
    }

    private fun isAbjad(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.ARABIC ||
            block == Character.UnicodeBlock.ARABIC_SUPPLEMENT ||
            block == Character.UnicodeBlock.ARABIC_EXTENDED_A ||
            block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A ||
            block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B ||
            block == Character.UnicodeBlock.HEBREW
    }

    private fun isSyllabic(c: Char): Boolean {
        val script = Character.UnicodeScript.of(c.code)
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA ||
            script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL
    }

    private const val TATWEEL = 'ـ'
    private const val ABJAD_SYLLABLES_PER_LETTER = 0.55f

    private const val VOWELS = "aeiouyàáâãäåæèéêëìíîïòóôõöøœùúûüýÿ" +
        "аеёиоуыэюяіїєў" + "αεηιουωάέήίόύώ"
}
