package org.sisam.langtutor.speech

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that stops the next mispronunciation shipping.
 *
 * Every word the app speaks should resolve from the dictionary, from the
 * exceptions file or from a regular rule — never from [RuleG2p], which guesses
 * from spelling and is why the tutor introduced itself as "Taki" for months.
 * A guess is silent: nothing logs, nothing fails, and it is only caught by
 * someone listening on a device.
 *
 * A new theme that adds an unlisted word fails here with the word named, and
 * the fix is one line in kokoro/pronunciation-exceptions.tsv.
 */
class PronunciationCoverageTest {

    private val phonemizer = KokoroPhonemizer.load()

    /** Every English string the shipped content will read aloud. */
    private fun spokenCorpus(): List<String> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }
        val resources = File(root, "core/content/src/main/resources")
        val out = mutableListOf<String>()
        // Deliberately a text scrape rather than the content models: this test
        // must see EVERY authored English line, including any a future format
        // adds, without being taught about each new file type.
        resources.walkTopDown().filter { it.extension == "json" }.forEach { file ->
            EN_FIELD.findAll(file.readText()).forEach { out += unescape(it.groupValues[1]) }
        }
        return out
    }

    private fun unescape(s: String) = s
        .replace("\\u2019", "’")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    @Test
    fun `every word the app speaks is looked up, never guessed`() {
        val guessed = sortedSetOf<String>()
        for (line in spokenCorpus() + SPOKEN_UI) {
            for (word in WORD.findAll(line).map { it.value }) {
                if (!phonemizer.isKnown(word)) guessed += word.lowercase()
            }
        }
        assertTrue(
            "these words are guessed from spelling; add them to " +
                "kokoro/pronunciation-exceptions.tsv: $guessed",
            guessed.isEmpty(),
        )
    }

    private companion object {
        val EN_FIELD = Regex("\"en\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val WORD = Regex("[A-Za-z][A-Za-z'’-]*")

        /** Lines the app says that live in code rather than content. */
        val SPOKEN_UI = listOf(
            "Repeat after me!",
            "Almost! Listen again.",
            "I didn't hear you. Try again!",
            "Good try! Let's do the next one.",
            "Great job!", "Well done!", "You said it!", "Perfect!",
            "Hello! I'm Tuki.",
        )
    }
}
