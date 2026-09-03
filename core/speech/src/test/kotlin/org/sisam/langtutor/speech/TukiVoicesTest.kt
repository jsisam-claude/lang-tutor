package org.sisam.langtutor.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TukiVoicesTest {

    @Test
    fun `a blend sits between its two tables`() {
        val a = floatArrayOf(1f, 1f, 0f)
        val b = floatArrayOf(0f, -1f, 4f)
        val mixed = VoiceBlend.mix(a, b, 0.75f)
        assertEquals(0.75f, mixed[0], 1e-6f)
        assertEquals(0.5f, mixed[1], 1e-6f)
        assertEquals(1f, mixed[2], 1e-6f)
    }

    @Test
    fun `the ends of the mix are the tables themselves`() {
        val a = floatArrayOf(3f, -2f)
        val b = floatArrayOf(-1f, 8f)
        assertTrue(VoiceBlend.mix(a, b, 1f).contentEquals(a))
        assertTrue(VoiceBlend.mix(a, b, 0f).contentEquals(b))
        // A weight outside 0..1 would extrapolate past both voices into
        // whatever lies beyond them; clamping keeps a bad value merely wrong.
        assertTrue(VoiceBlend.mix(a, b, 4f).contentEquals(a))
        assertTrue(VoiceBlend.mix(a, b, -4f).contentEquals(b))
    }

    @Test
    fun `tables of different shapes are refused rather than half-mixed`() {
        val e = runCatching { VoiceBlend.mix(FloatArray(4), FloatArray(5), 0.5f) }.exceptionOrNull()
        assertTrue("expected a shape complaint, got $e", e is IllegalArgumentException)
    }

    @Test
    fun `the captain is built from two voices this build carries`() {
        val captain = TukiVoices.byId(TukiVoices.CAPTAIN_ID)
        assertEquals(TukiVoices.CAPTAIN_ID, captain.id)
        val blend = requireNotNull(captain.blend) { "the captain must be a blend" }
        val shipped = TukiVoices.ALL.filter { it.blend == null }.map { it.id }.toSet()
        assertTrue("${blend.a} is not a shipped voice", blend.a in shipped)
        assertTrue("${blend.b} is not a shipped voice", blend.b in shipped)
        assertEquals(listOf(blend.a, blend.b), captain.sources)
    }

    @Test
    fun `the captain speaks lower and slower than the parrot`() {
        val c = requireNotNull(TukiVoices.byId(TukiVoices.CAPTAIN_ID).character)
        assertTrue("a character voice must be a real change", c.pitch < 1f)
        assertTrue("the parrot goes up, the captain goes down", c.pitch < ParrotEffect.PARROT.pitch)
        assertTrue("unhurried", c.rate < 1f)
        assertTrue("an old voice wavers slower than a bird", c.warbleHz < ParrotEffect.PARROT.warbleHz)
        assertTrue("and no bird trill announces him", !c.flourish)
    }

    @Test
    fun `an ordinary voice brings no character of its own`() {
        // Everything but the characters is Tuki wearing the parrot; a voice
        // that quietly grew its own treatment would change the teaching
        // register of the whole app.
        for (voice in TukiVoices.ALL.filter { it.accent != TukiVoice.Accent.CHARACTER }) {
            assertNull("${voice.id} carries a character", voice.character)
            assertNull("${voice.id} is blended", voice.blend)
            assertEquals(listOf(voice.id), voice.sources)
        }
    }

    @Test
    fun `the default is a shipped voice, not a blend`() {
        assertNull(TukiVoices.byId(null).blend)
        assertEquals(TukiVoices.DEFAULT_ID, TukiVoices.byId("nonsense.bin").id)
    }
}
