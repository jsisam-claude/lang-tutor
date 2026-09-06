package org.sisam.langtutor.tutor.cloze

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sisam.langtutor.content.PhraseSentence
import org.sisam.langtutor.profile.InMemoryProfileStore
import org.sisam.langtutor.profile.Skill
import org.sisam.langtutor.speech.FakeTtsEngine

@OptIn(ExperimentalCoroutinesApi::class)
class ClozeOrchestratorTest {

    private class Fixture(scope: TestScope) {
        val tts = FakeTtsEngine()
        val profile = InMemoryProfileStore()
        val events = mutableListOf<ClozeEvent>()
        val room = ClozeOrchestrator(tts, profile, scope)
        val collector = scope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            room.events.collect { events += it }
        }
        fun spoken() = tts.spoken.map { it.text }
    }

    private fun item(id: String, en: String, he: String, blank: Int, options: List<String>, answer: Int) = ClozeItem(
        sentence = PhraseSentence(id, 1, "present-simple", "test", en, he, theme = "test"),
        blank = blank,
        options = options,
        answer = answer,
        kind = ClozeKind.WORD,
    )

    private val items = listOf(
        item("a", "I see a bee.", "אני רואה דבורה.", 3, listOf("cat", "bee", "dog", "cow"), 1),
        item("b", "The bread is warm.", "הלחם חם.", 3, listOf("warm", "cold", "big", "red"), 0),
    )

    @Test
    fun `starting a round says the intro and asks the first item`() = runTest {
        val f = Fixture(this)
        f.room.startRound(items)
        advanceUntilIdle()
        assertEquals(listOf(ClozeOrchestrator.INTRO), f.spoken())
        assertEquals(ClozeState.Asking(items[0], 0, 2, emptySet()), f.room.state.value)
        f.collector.cancel()
    }

    @Test
    fun `a first-try answer praises, reads the whole line and pays full xp`() = runTest {
        val f = Fixture(this)
        f.room.startRound(items)
        advanceUntilIdle()
        f.room.onOptionPicked(1)
        // Mid-speech the beak is moving.
        advanceTimeBy(10)
        assertTrue((f.room.state.value as ClozeState.Revealed).speaking)
        advanceUntilIdle()
        assertEquals(listOf(ClozeEvent.Resolved(ClozeOutcome.FIRST_TRY)), f.events)
        assertEquals(
            listOf(ClozeOrchestrator.INTRO, ClozeOrchestrator.PRAISES[0], "I see a bee."),
            f.spoken(),
        )
        assertEquals(ClozeOrchestrator.XP_FIRST_TRY, f.profile.current().xp)
        // The ledger the doc describes, in numbers rather than by constant.
        assertEquals(5, ClozeOrchestrator.XP_FIRST_TRY)
        assertEquals(2, ClozeOrchestrator.XP_FOUND)
        assertTrue(ClozeOrchestrator.XP_FOUND in 1 until ClozeOrchestrator.XP_FIRST_TRY)
        val s = f.room.state.value as ClozeState.Revealed
        assertEquals(ClozeOutcome.FIRST_TRY, s.outcome)
        assertFalse(s.speaking)
        f.collector.cancel()
    }

    @Test
    fun `a wrong tap is a warm retry and never reads the line`() = runTest {
        val f = Fixture(this)
        f.room.startRound(items)
        advanceUntilIdle()
        f.room.onOptionPicked(0)
        advanceUntilIdle()
        assertEquals(listOf<ClozeEvent>(ClozeEvent.Wrong), f.events)
        assertEquals(setOf(0), (f.room.state.value as ClozeState.Asking).wrongTaps)
        assertEquals(listOf(ClozeOrchestrator.INTRO, ClozeOrchestrator.TRY_AGAIN), f.spoken())
        // The same wrong option again changes nothing.
        f.room.onOptionPicked(0)
        advanceUntilIdle()
        assertEquals(1, f.events.size)
        // Found after a miss: a little xp, the line read.
        f.room.onOptionPicked(1)
        advanceUntilIdle()
        assertEquals(ClozeEvent.Resolved(ClozeOutcome.FOUND), f.events.last())
        assertEquals(ClozeOrchestrator.XP_FOUND, f.profile.current().xp)
        assertEquals("I see a bee.", f.spoken().last())
        f.collector.cancel()
    }

    @Test
    fun `three wrong taps show the answer and pay nothing`() = runTest {
        val f = Fixture(this)
        f.room.startRound(items)
        advanceUntilIdle()
        for (wrong in listOf(0, 2, 3)) {
            f.room.onOptionPicked(wrong)
            advanceUntilIdle()
        }
        val s = f.room.state.value as ClozeState.Revealed
        assertEquals(ClozeOutcome.SHOWN, s.outcome)
        assertEquals(setOf(0, 2, 3), s.wrongTaps)
        assertEquals(0, f.profile.current().xp)
        assertEquals(ClozeEvent.Resolved(ClozeOutcome.SHOWN), f.events.last())
        assertTrue(f.spoken().containsAll(listOf(ClozeOrchestrator.SHOWN_LINE, "I see a bee.")))
        f.collector.cancel()
    }

    @Test
    fun `next walks the round and ends in Done with the tallies`() = runTest {
        val f = Fixture(this)
        f.room.startRound(items)
        advanceUntilIdle()
        f.room.onOptionPicked(1)
        advanceUntilIdle()
        f.room.onNext()
        assertEquals(ClozeState.Asking(items[1], 1, 2, emptySet()), f.room.state.value)
        f.room.onOptionPicked(2)
        advanceUntilIdle()
        f.room.onOptionPicked(0)
        advanceUntilIdle()
        f.room.onNext()
        advanceUntilIdle()
        assertEquals(ClozeState.Done(firstTry = 1, found = 1, total = 2), f.room.state.value)
        assertEquals(ClozeEvent.RoundDone(2), f.events.last())
        f.collector.cancel()
    }

    @Test
    fun `an empty round is Done, not Idle, and says nothing`() = runTest {
        val f = Fixture(this)
        f.room.startRound(emptyList())
        advanceUntilIdle()
        assertEquals(ClozeState.Done(0, 0, 0), f.room.state.value)
        assertTrue(f.spoken().isEmpty())
        f.collector.cancel()
    }

    @Test
    fun `taps during speech are dropped, and the line repeats only once revealed`() = runTest {
        val f = Fixture(this)
        f.room.startRound(items)
        advanceUntilIdle()
        // Nothing to repeat while the gap is open: the line is the answer.
        f.room.onSentenceTapped()
        advanceUntilIdle()
        assertEquals(listOf(ClozeOrchestrator.INTRO), f.spoken())
        f.room.onOptionPicked(1)
        advanceTimeBy(10)
        // Still speaking: a second tap is ignored rather than queued.
        f.room.onOptionPicked(0)
        f.room.onNext()
        advanceUntilIdle()
        assertTrue(f.room.state.value is ClozeState.Revealed)
        assertEquals(1, f.events.size)
        f.room.onSentenceTapped()
        advanceUntilIdle()
        assertEquals("I see a bee.", f.spoken().last())
        assertEquals(2, f.spoken().count { it == "I see a bee." })
        f.collector.cancel()
    }

    @Test
    fun `a tap during the intro is dropped, not spoken over it`() = runTest {
        val f = Fixture(this)
        launch { f.room.startRound(items) }
        advanceTimeBy(10) // mid-intro
        f.room.onOptionPicked(1)
        advanceUntilIdle()
        assertTrue(f.room.state.value is ClozeState.Asking)
        assertTrue(f.events.isEmpty())
        assertEquals(listOf(ClozeOrchestrator.INTRO), f.spoken())
        f.collector.cancel()
    }

    @Test
    fun `a gap is evidence about the line's topic and grammar`() = runTest {
        val f = Fixture(this)
        f.room.startRound(items)
        advanceUntilIdle()
        f.room.onOptionPicked(1) // first try
        advanceUntilIdle()
        val after = f.profile.current().skills
        assertEquals(setOf(Skill.theme("test"), Skill.frame("test")), after.keys)
        assertTrue("a clean answer should read as knowing", after.getValue(Skill.theme("test")).pKnown > 0.1)
        // The next item is found only by elimination, which is evidence of
        // the other thing — the same skills, moving the other way.
        f.room.onNext()
        repeat(3) { i -> f.room.onOptionPicked(listOf(1, 2, 3)[i]); advanceUntilIdle() }
        val shown = f.profile.current().skills.getValue(Skill.theme("test"))
        assertEquals(2, shown.attempts)
        assertTrue("elimination should not read as knowing: ${shown.pKnown}", shown.pKnown < after.getValue(Skill.theme("test")).pKnown)
        f.collector.cancel()
    }

    @Test
    fun `silence cuts the voice but keeps the round to come back to`() = runTest {
        val f = Fixture(this)
        f.room.startRound(items)
        advanceUntilIdle()
        f.room.onOptionPicked(1)
        advanceTimeBy(10) // inside the praise, before the line is read
        f.room.silence()
        advanceUntilIdle()
        // The line that was queued behind the praise is dropped, so it can
        // never land on top of whatever room the learner moved to.
        assertFalse("I see a bee." in f.spoken())
        // And the round survives: a rotation or a sticker detour comes back
        // to the item, not to an empty pane.
        val s = f.room.state.value as ClozeState.Revealed
        assertEquals(items[0], s.item)
        assertEquals(ClozeOutcome.FIRST_TRY, s.outcome)
        // Silence is not deafness: the next round speaks again.
        f.room.startRound(items)
        advanceUntilIdle()
        assertEquals(ClozeOrchestrator.INTRO, f.spoken().last())
        f.collector.cancel()
    }

    @Test
    fun `shutdown goes idle`() = runTest {
        val f = Fixture(this)
        f.room.startRound(items)
        advanceUntilIdle()
        f.room.shutdown()
        assertEquals(ClozeState.Idle, f.room.state.value)
        f.collector.cancel()
    }
}
