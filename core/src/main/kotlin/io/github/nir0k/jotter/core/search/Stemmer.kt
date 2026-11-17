package io.github.nir0k.jotter.core.search

import java.text.Normalizer
import java.util.Locale
import org.tartarus.snowball.SnowballStemmer
import org.tartarus.snowball.ext.englishStemmer
import org.tartarus.snowball.ext.russianStemmer

/** A part of a text, in UTF-16 code units. */
data class TextSpan(val start: Int, val length: Int) {
    /** The position just after the span. */
    val end: Int get() = start + length
}

/** Tells whether a code point belongs to a word: letters, numbers and combining marks. */
private fun isWordCharacter(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
    Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
    Character.MODIFIER_LETTER, Character.OTHER_LETTER,
    Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
    Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK,
    -> true
    else -> false
}

/**
 * Splits a text into words: runs of letters, digits and combining marks. Everything else (spaces,
 * punctuation, symbols) separates words, so `e-mail` is two words.
 *
 * @return The words in their order.
 */
fun splitWords(text: String): List<TextSpan> {
    val words = mutableListOf<TextSpan>()
    var start = -1
    var i = 0
    while (i < text.length) {
        val codePoint = text.codePointAt(i)
        if (isWordCharacter(codePoint)) {
            if (start < 0) start = i
        } else if (start >= 0) {
            words += TextSpan(start, i - start)
            start = -1
        }
        i += Character.charCount(codePoint)
    }
    if (start >= 0) words += TextSpan(start, text.length - start)
    return words
}

private const val CombiningBreve = '̆'

/**
 * Lowercases a word and drops its diacritics. The breve of `й` is kept, as Russian treats it as a
 * letter of its own; the diaeresis of `ё` goes, so it compares as `е`.
 */
private fun normalized(word: String): String {
    val decomposed = Normalizer.normalize(word.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    val result = StringBuilder(decomposed.length)
    for (c in decomposed) {
        if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) {
            if (c == CombiningBreve && result.endsWith('и')) result.append(c)
            continue
        }
        result.append(c)
    }
    return Normalizer.normalize(result, Normalizer.Form.NFC)
}

private const val Consonants = "бвгджзйклмнпрстфхцчшщ"

/**
 * Joins Russian word forms that Snowball leaves apart: it keeps the fleeting vowel of forms such
 * as «заметок» and «отец», which the other forms lose («заметки», «отца»). An `о` or `е` between
 * a consonant and a final `к` or `ц` is dropped from every stem, so all forms meet.
 */
private fun withoutFleetingVowel(stem: String): String {
    val n = stem.length
    return if (n >= 4 && (stem[n - 1] == 'к' || stem[n - 1] == 'ц') && (stem[n - 2] == 'о' || stem[n - 2] == 'е') &&
        stem[n - 3] in Consonants
    ) {
        stem.removeRange(n - 2, n - 1)
    } else {
        stem
    }
}

/**
 * Stemmer reduces words to the terms search compares: case and diacritics are dropped, `ё`
 * becomes `е` while `й` stays a letter of its own, and Russian and English words are cut to
 * their stems by the Snowball stemmers, chosen by the word's script.
 *
 * The index, the query and the highlights use the same reduction, so they agree on every word
 * form. A stemmer is not thread-safe: each thread uses its own.
 */
class Stemmer {
    private val russian: SnowballStemmer = russianStemmer()
    private val english: SnowballStemmer = englishStemmer()

    /**
     * Returns the search term of one word, as [splitWords] finds it.
     *
     * @return The normalised stem; a word in another script is only normalised.
     */
    fun stem(word: String): String {
        val plain = normalized(word)
        var latin = false
        for (codePoint in plain.codePoints()) {
            when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.CYRILLIC -> return withoutFleetingVowel(stemWith(russian, plain))
                Character.UnicodeScript.LATIN -> latin = true
                else -> Unit
            }
        }
        return if (latin) stemWith(english, plain) else plain
    }

    /** Returns the terms of every word of a text in their order. */
    fun stems(text: String): List<String> = splitWords(text).map { stem(text.substring(it.start, it.end)) }

    /**
     * Describes the stemmers' behaviour on sample words. An index built with other stems cannot
     * remove its old entries correctly, so it is rebuilt when this changes. The sample is the
     * desktop's, so both clients describe the same stems alike.
     */
    fun fingerprint(): String =
        stems("Заметками заметок ёлками йогурта notes running happily généralement").joinToString(" ")

    private fun stemWith(stemmer: SnowballStemmer, word: String): String {
        stemmer.setCurrent(word)
        stemmer.stem()
        return stemmer.getCurrent()
    }
}
