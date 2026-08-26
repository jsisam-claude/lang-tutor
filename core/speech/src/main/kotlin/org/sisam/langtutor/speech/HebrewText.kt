package org.sisam.langtutor.speech

/**
 * Is there Hebrew in this string?
 *
 * One definition, shared: the TTS router uses it to pick a voice, the tutor
 * uses it to decide the learner is asking for a Hebrew explanation, and the
 * transcript uses it to pick a layout direction. Three different answers to
 * "is this Hebrew" would be three different bugs.
 *
 * The range is the full Hebrew block (U+0590–U+05FF), which deliberately
 * includes the cantillation marks and points that sit on letters — a pointed
 * word is still Hebrew.
 */
object HebrewText {

    private val HEBREW_RUN = Regex("[\\u0590-\\u05FF]+")

    fun contains(s: String): Boolean = s.any { it in '֐'..'׿' }

    /** The same string with Hebrew runs removed and whitespace collapsed. */
    fun strip(s: String): String =
        HEBREW_RUN.replace(s, " ").replace(Regex("\\s+"), " ").trim()
}
