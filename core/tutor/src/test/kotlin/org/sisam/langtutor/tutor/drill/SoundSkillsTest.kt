package org.sisam.langtutor.tutor.drill

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sisam.langtutor.content.ResourceTwisterRepository
import org.sisam.langtutor.profile.Skill
import org.sisam.langtutor.speech.PhonemeScore
import org.sisam.langtutor.speech.PronunciationScore

/**
 * The reverse index over the authored sound table, checked against the real
 * `twisters.json` — a content edit that renames a sound or its IPA fails
 * here rather than quietly recording evidence about nothing.
 */
class SoundSkillsTest {

    private val sounds = runBlocking { ResourceTwisterRepository().book().sounds }
    private val skills = SoundSkills(sounds)

    private fun score(vararg phones: Pair<String, Float>) =
        PronunciationScore(overall = 0f, phonemes = phones.map { PhonemeScore(it.first, it.second) })

    @Test
    fun `every taught sound is reachable from its own IPA`() {
        for (sound in sounds) {
            for (phone in sound.ipa.split(' ').filter { it.isNotBlank() }) {
                assertEquals("${sound.key} lost its own phone '$phone'", sound.key, skills.soundFor(phone))
            }
        }
        assertTrue("the book is empty", sounds.isNotEmpty())
    }

    @Test
    fun `stress and length marks belong to the syllable, not the sound`() {
        assertEquals("th-voiceless", skills.soundFor("θ"))
        assertEquals("th-voiceless", skills.soundFor("ˈθ"))
        assertEquals("ship-sheep", skills.soundFor("iː"))
        assertEquals("ship-sheep", skills.soundFor("ˌi"))
        assertEquals("p", skills.soundFor("p"))
    }

    @Test
    fun `a phone the app does not teach is not evidence about anything`() {
        // Hebrew has these, so a low score is far likelier to be the aligner
        // than the learner, and recording it would bury the real contrasts.
        assertNull(skills.soundFor("m"))
        assertNull(skills.soundFor("k"))
        assertEquals(emptyMap<String, Boolean>(), skills.observations(score("m" to 0.1f, "k" to 0.2f)))
    }

    @Test
    fun `a sound is judged on most of its instances, not on the worst or the mean`() {
        // Three good and one bad: a listener would say the th went right,
        // and so does the profile. The mean (0.76) would not.
        val mostlyGood = skills.observations(score("θ" to 0.95f, "θ" to 0.9f, "θ" to 0.9f, "θ" to 0.3f))
        assertEquals(mapOf(Skill.sound("th-voiceless") to true), mostlyGood)
        // One good out of three is not a sound going right.
        val mostlyBad = skills.observations(score("θ" to 0.2f, "θ" to 0.3f, "θ" to 0.9f))
        assertEquals(mapOf(Skill.sound("th-voiceless") to false), mostlyBad)
        // An even split counts as said: the learner managed it half the time
        // in one breath, and the coach's own floor is a soft one.
        assertEquals(
            mapOf(Skill.sound("th-voiceless") to true),
            skills.observations(score("θ" to 0.9f, "θ" to 0.3f)),
        )
    }

    @Test
    fun `one attempt is evidence about every sound it touched`() {
        val observed = skills.observations(
            score("ð" to 0.9f, "ɹ" to 0.2f, "w" to 0.85f, "m" to 0.1f),
        )
        assertEquals(
            mapOf(
                Skill.sound("th-voiced") to true,
                Skill.sound("r") to false,
                Skill.sound("w") to true,
            ),
            observed,
        )
    }

    @Test
    fun `the line between good and not is the one the learner is shown`() {
        // PronunciationFeedback colours >= 0.8 green; the profile must not
        // disagree with the colour on the same attempt.
        assertEquals(0.8f, SoundSkills.GOOD, 0.0001f)
        assertEquals(mapOf(Skill.sound("w") to true), skills.observations(score("w" to 0.8f)))
        assertEquals(mapOf(Skill.sound("w") to false), skills.observations(score("w" to 0.79f)))
    }
}
