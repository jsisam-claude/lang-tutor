package org.sisam.langtutor.tutor.tense

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
class TenseOrchestratorTest {

    private class Fixture(scope: TestScope) {
        val tts = FakeTtsEngine()
        val profile = InMemoryProfileStore()
        val events = mutableListOf<TenseEvent>()
        val room = TenseOrchestrator(tts, profile, scope)
        val collector = scope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            room.events.collect { events += it }
        }
        fun spoken() = tts.spoken.map { it.text }
    }

    private val stops = listOf(TenseStop.YESTERDAY, TenseStop.TODAY, TenseStop.TOMORROW)

    private fun item(
        id: String,
        tense: String,
        stop: TenseStop,
        shown: String,
        hidden: String?,
        restored: String = shown,
        verb: List<Int> = listOf(1),
        cue: TenseCue = TenseCue.INFLECTION,
    ) = TenseItem(
        sentence = PhraseSentence(id, 3, tense, "test", restored, "עברית", theme = "test"),
        stage = TenseStage.TIME,
        stop = stop,
        shown = shown,
        hidden = hidden,
        verb = verb,
        cue = cue,
    )

    private val items = listOf(
        item("a", "past-simple", TenseStop.YESTERDAY, "We visited the doctor.", "yesterday", "We visited the doctor yesterday."),
        item("b", "present-progressive", TenseStop.TODAY, "The nurse is looking in my ears.", null, verb = listOf(2, 3), cue = TenseCue.AUXILIARY),
    )

    @Test
    fun `starting a round says the intro and reads the line with its time word out`() = runTest {
        val f = Fixture(this)
        f.room.startRound(items, stops)
        advanceUntilIdle()
        assertEquals(listOf(TenseOrchestrator.INTRO, "We visited the doctor."), f.spoken())
        val s = f.room.state.value as TenseState.Asking
        assertEquals(items[0], s.item)
        assertEquals(stops, s.stops)
        assertEquals(0, s.asked)
        assertFalse(s.speaking)
        f.collector.cancel()
    }

    @Test
    fun `a first-try tap praises, reads the whole line back and pays full xp`() = runTest {
        val f = Fixture(this)
        f.room.startRound(items, stops)
        advanceUntilIdle()
        f.room.onStopPicked(TenseStop.YESTERDAY)
        advanceTimeBy(10)
        assertTrue((f.room.state.value as TenseState.Revealed).speaking)
        advanceUntilIdle()
        assertEquals(listOf(TenseEvent.Resolved(TenseOutcome.FIRST_TRY)), f.events)
        // The restored line, not the shown one: the ear gets the whole sentence.
        assertEquals(
            listOf(
                TenseOrchestrator.INTRO, "We visited the doctor.",
                TenseOrchestrator.PRAISES[0], "We visited the doctor yesterday.",
            ),
            f.spoken(),
        )
        assertEquals(TenseOrchestrator.XP_FIRST_TRY, profileXp(f))
        f.collector.cancel()
    }

    @Test
    fun `a wrong tap hands back the word the deck hid`() = runTest {
        val f = Fixture(this)
        f.room.startRound(items, stops)
        advanceUntilIdle()
        f.room.onStopPicked(TenseStop.TOMORROW)
        advanceUntilIdle()
        val s = f.room.state.value as TenseState.Asking
        assertEquals(setOf(TenseStop.TOMORROW), s.wrongTaps)
        assertEquals(TenseOrchestrator.HIDDEN_IS.format("yesterday"), f.spoken().last())
        assertEquals(listOf(TenseEvent.Wrong), f.events)
        f.collector.cancel()
    }

    @Test
    fun `a line that hid nothing falls back to the plain nudge`() = runTest {
        val f = Fixture(this)
        f.room.startRound(listOf(items[1]), stops)
        advanceUntilIdle()
        f.room.onStopPicked(TenseStop.YESTERDAY)
        advanceUntilIdle()
        assertEquals(TenseOrchestrator.TRY_AGAIN, f.spoken().last())
        f.collector.cancel()
    }

    @Test
    fun `the last standing destination is not a choice, so it pays nothing`() = runTest {
        val f = Fixture(this)
        f.room.startRound(items, stops)
        advanceUntilIdle()
        f.room.onStopPicked(TenseStop.TODAY)
        advanceUntilIdle()
        f.room.onStopPicked(TenseStop.TOMORROW)
        advanceUntilIdle()
        val s = f.room.state.value as TenseState.Revealed
        assertEquals(TenseOutcome.SHOWN, s.outcome)
        assertEquals(0, profileXp(f))
        assertEquals(TenseOrchestrator.SHOWN_LINE, f.spoken()[f.spoken().size - 2])
        f.collector.cancel()
    }

    @Test
    fun `a filed sentence piles up under its own destination and stays there`() = runTest {
        val f = Fixture(this)
        f.room.startRound(items, stops)
        advanceUntilIdle()
        f.room.onStopPicked(TenseStop.YESTERDAY)
        advanceUntilIdle()
        assertEquals(
            mapOf(TenseStop.YESTERDAY to listOf("We visited the doctor.")),
            (f.room.state.value as TenseState.Revealed).placed,
        )
        f.room.onNext()
        advanceUntilIdle()
        val asking = f.room.state.value as TenseState.Asking
        // Carried into the next item: the timeline fills as the round goes.
        assertEquals(mapOf(TenseStop.YESTERDAY to listOf("We visited the doctor.")), asking.placed)
        f.room.onStopPicked(TenseStop.TODAY)
        advanceUntilIdle()
        f.room.onNext()
        advanceUntilIdle()
        val done = f.room.state.value as TenseState.Done
        assertEquals(2, done.total)
        assertEquals(2, done.firstTry)
        assertEquals(
            mapOf(
                TenseStop.YESTERDAY to listOf("We visited the doctor."),
                TenseStop.TODAY to listOf("The nurse is looking in my ears."),
            ),
            done.placed,
        )
        assertEquals(stops, done.stops)
        f.collector.cancel()
    }

    @Test
    fun `the whole round's destinations travel, not just the ones still to come`() = runTest {
        val f = Fixture(this)
        // A round that happens to hold only Yesterday items still shows three
        // destinations: a timeline that shrank would give the answer away.
        f.room.startRound(listOf(items[0]), stops)
        advanceUntilIdle()
        assertEquals(stops, (f.room.state.value as TenseState.Asking).stops)
        f.collector.cancel()
    }

    @Test
    fun `placing a sentence is evidence about its tense`() = runTest {
        val f = Fixture(this)
        f.room.startRound(items, stops)
        advanceUntilIdle()
        f.room.onStopPicked(TenseStop.YESTERDAY)
        advanceUntilIdle()
        val skills = f.profile.current().skills.keys
        assertTrue(Skill.tense("past-simple") in skills)
        assertTrue(Skill.theme("test") in skills)
        f.collector.cancel()
    }

    @Test
    fun `an empty round is Done, never Idle`() = runTest {
        val f = Fixture(this)
        f.room.startRound(emptyList(), stops)
        advanceUntilIdle()
        assertEquals(TenseState.Done(0, 0, 0, stops, emptyMap()), f.room.state.value)
        f.collector.cancel()
    }

    @Test
    fun `one destination is no question, so the round does not start`() = runTest {
        val f = Fixture(this)
        f.room.startRound(items, listOf(TenseStop.TODAY))
        advanceUntilIdle()
        assertTrue(f.room.state.value is TenseState.Done)
        assertEquals(emptyList<String>(), f.spoken())
        f.collector.cancel()
    }

    @Test
    fun `tapping the sentence while asking repeats it without the time word`() = runTest {
        val f = Fixture(this)
        f.room.startRound(items, stops)
        advanceUntilIdle()
        f.room.onSentenceTapped()
        advanceUntilIdle()
        assertEquals("We visited the doctor.", f.spoken().last())
        // Free: no tap is spent and nothing is scored.
        assertTrue(f.room.state.value is TenseState.Asking)
        assertEquals(0, profileXp(f))
        f.collector.cancel()
    }

    @Test
    fun `a tap during speech is dropped, not queued`() = runTest {
        val f = Fixture(this)
        launch { f.room.startRound(items, stops) }
        advanceTimeBy(5)
        f.room.onStopPicked(TenseStop.YESTERDAY)
        advanceUntilIdle()
        // The intro was still speaking, so the tap did nothing.
        assertTrue(f.room.state.value is TenseState.Asking)
        f.collector.cancel()
    }

    @Test
    fun `silence keeps the round where it is, shutdown ends it`() = runTest {
        val f = Fixture(this)
        f.room.startRound(items, stops)
        advanceUntilIdle()
        f.room.silence()
        advanceUntilIdle()
        assertTrue(f.room.state.value is TenseState.Asking)
        f.room.shutdown()
        advanceUntilIdle()
        assertEquals(TenseState.Idle, f.room.state.value)
        f.collector.cancel()
    }

    private suspend fun profileXp(f: Fixture) = f.profile.current().xp
}
