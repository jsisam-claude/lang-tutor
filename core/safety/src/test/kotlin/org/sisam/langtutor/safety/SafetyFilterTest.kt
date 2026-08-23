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
        // "הרגשה" (feeling) contains "הרג" (killing) as a prefix — the (?U)
        // word boundary must keep it allowed.
        assertTrue(f.check("איזו הרגשה טובה!").allowed)
    }

}
