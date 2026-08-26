package org.sisam.langtutor.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyFilterTest {

    private val filter = BlocklistSafetyFilter()

    @Test
    fun `normal tutor replies pass`() {
        assertTrue(filter.check("Great job, Noa! What color is the ball?").allowed)
        assertTrue(filter.check("You played with your dog! What's his name?").allowed)
    }

    @Test
    fun `word boundaries prevent false positives`() {
        assertTrue(filter.check("That's a great skill! You are skilled.").allowed)
        assertTrue(filter.check("The dice landed on six.").allowed)
        assertTrue(filter.check("Let's study hard!").allowed)
    }

    @Test
    fun `blocked terms are caught case-insensitively`() {
        assertEquals("blocked-term", filter.check("Let's play with the GUN!").reason)
        assertEquals("blocked-term", filter.check("Keep this a secret from your parents").reason)
        assertEquals("blocked-term", filter.check("You are stupid").reason)
    }

    @Test
    fun `structural checks catch urls meta-talk length and emptiness`() {
        assertEquals("contains-url", filter.check("Visit www.example.com now!").reason)
        assertEquals("meta-ai-talk", filter.check("As an AI language model I cannot").reason)
        assertEquals("too-long", filter.check("word ".repeat(120)).reason)
        assertFalse(filter.check("   ").allowed)
    }

    @Test
    fun `hebrew insults and violence are blocked`() {
        val f = BlocklistSafetyFilter()
        listOf(
            "אתה טיפש",                     // "you are stupid"
            "יש לו אקדח",                   // "he has a gun"
            "אני שונא אותך",                // "I hate you"
            "זה סוד מההורים שלך",           // "it's a secret from your parents"
        ).forEach { line ->
            assertFalse("should block: $line", f.check(line).allowed)
        }
    }

    @Test
    fun `everyday hebrew praise passes`() {
        val f = BlocklistSafetyFilter()
        listOf(
            "כל הכבוד! אמרת את זה מצוין",   // "well done! you said it great"
            "אני מת על זה",                 // slang: "I love it" — must NOT block
            "בוא ננסה שוב יחד",             // "let's try again together"
        ).forEach { line ->
            assertTrue("should allow: $line", f.check(line).allowed)
        }
    }

    @Test
    fun `hebrew word boundaries do not block substrings`() {
        val f = BlocklistSafetyFilter()
        // "הרגשה" (feeling) contains "הרג" (killing) as a prefix — the
        // word boundary must keep it allowed.
        assertTrue(f.check("איזו הרגשה טובה!").allowed)
    }

    @Test
    fun `niqqud and cantillation marks do not defeat the blocklist`() {
        val filter = BlocklistSafetyFilter()
        // Shin dot after the final letter used to kill the trailing word
        // boundary; marks inside the word used to break the literal match.
        assertFalse(filter.check("\u05d0\u05ea\u05d4 \u05d8\u05d9\u05e4\u05e9\u05c1").allowed) // אתה טיפשׁ
        assertFalse(filter.check("\u05d3\u05b8\u05dd").allowed) // דָם (pointed "blood")
        // Pointed text drops the yod (ktiv haser) — the variant must block too.
        assertFalse(filter.check("\u05d0\u05ea\u05d4 \u05d8\u05b4\u05e4\u05b5\u05bc\u05e9\u05c1").allowed) // אתה טִפֵּשׁ
    }

    @Test
    fun `pointed everyday hebrew still passes`() {
        val filter = BlocklistSafetyFilter()
        assertTrue(filter.check("\u05db\u05b8\u05bc\u05dc \u05d4\u05b7\u05db\u05b8\u05bc\u05d1\u05d5\u05b9\u05d3").allowed) // כָּל הַכָּבוֹד
    }

    @Test
    fun `the blocklist pattern uses no inline flags — Android's regex is ICU and rejects them`() {
        // Android's java.util.regex is ICU-backed: it has Unicode character
        // classes always on and throws PatternSyntaxException on "(?U)".
        // Desktop JVM accepts it, so a unit test is the ONLY place this can
        // be caught before the app crashes on device. Guard the whole class
        // of bug, not just the one flag that bit us.
        val pattern = BlocklistSafetyFilter().patternForTest()
        val inlineFlags = Regex("""\(\?[a-zA-Z]+[):]""").findAll(pattern).map { it.value }.toList()
        assertTrue("pattern carries inline flags $inlineFlags: $pattern", inlineFlags.isEmpty())
    }
}
