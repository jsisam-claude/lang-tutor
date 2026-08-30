package org.sisam.langtutor.tutor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sisam.langtutor.profile.LearnerProfile
import org.sisam.langtutor.profile.LearnerTrack

class LevelConfigTest {

    @Test
    fun `budgets rise with level and the ladder is monotonic`() {
        val tokens = (1..7).map { LevelConfig.of(it).replyTokens }
        assertEquals(48, tokens.first())
        assertEquals(128, tokens.last())
        assertTrue(tokens.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun `scaffolds fade with proficiency, in the documented order`() {
        // Transliteration goes first (after 3), then the meaning row (after
        // 4), then Hebrew help itself (after 5) — each later level is a
        // subset of the scaffolds below it.
        for (level in 1..7) {
            val c = LevelConfig.of(level)
            assertEquals("translit at $level", level <= 3, c.transliterationByDefault)
            assertEquals("translation at $level", level <= 4, c.translationByDefault)
            assertEquals("hebrew help at $level", level <= 5, c.hebrewTextUseful)
            if (c.transliterationByDefault) assertTrue(c.translationByDefault)
            if (c.translationByDefault) assertTrue(c.hebrewTextUseful)
        }
    }

    @Test
    fun `out-of-range levels clamp instead of crashing`() {
        assertEquals(1, LevelConfig.of(0).level)
        assertEquals(1, LevelConfig.of(-3).level)
        assertEquals(7, LevelConfig.of(99).level)
    }

    @Test
    fun `no persona mentions an age`() {
        // The whole point of levels: proficiency, never age. A regression
        // here re-ships the assumption the refactor removed.
        for (level in 1..7) {
            val persona = LevelConfig.of(level).personaSuffix.lowercase()
            for (banned in listOf("child", "kid", "year-old", "adult", "teenager", "young")) {
                assertFalse("level $level persona says '$banned'", banned in persona)
            }
        }
    }

    @Test
    fun `a legacy profile lands on the level its track pointed to`() {
        assertEquals(1, LearnerProfile(track = LearnerTrack.PRE_READER).effectiveLevel)
        assertEquals(2, LearnerProfile(track = LearnerTrack.BEGINNER).effectiveLevel)
        assertEquals(4, LearnerProfile(track = LearnerTrack.EXAM).effectiveLevel)
        assertEquals(5, LearnerProfile(track = LearnerTrack.IMPROVER).effectiveLevel)
        // A chosen level always wins over the legacy track.
        assertEquals(
            7,
            LearnerProfile(track = LearnerTrack.PRE_READER, learnerLevel = 7).effectiveLevel,
        )
    }
}
