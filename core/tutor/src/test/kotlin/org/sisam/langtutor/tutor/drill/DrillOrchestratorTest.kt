package org.sisam.langtutor.tutor.drill

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sisam.langtutor.profile.InMemoryProfileStore
import org.sisam.langtutor.speech.AsrResult
import org.sisam.langtutor.speech.FakeAsrEngine
import org.sisam.langtutor.speech.FakePronunciationScorer
import org.sisam.langtutor.speech.FakeTtsEngine
import org.sisam.langtutor.speech.RecognitionHint

@OptIn(ExperimentalCoroutinesApi::class)
class DrillOrchestratorTest {

    private class Fixture(scope: TestScope) {
        val asr = FakeAsrEngine()
        val tts = FakeTtsEngine()
        val profile = InMemoryProfileStore()
        val events = mutableListOf<DrillEvent>()
        val drill = DrillOrchestrator(
            asr = asr,
            tts = tts,
            scorer = FakePronunciationScorer(),
            profile = profile,
            scope = scope,
        )
        val collector = scope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            drill.events.collect { events += it }
        }
    }

    private val ball = DrillItem("I see a red ball!", DrillLevel.LONG)
    private val bear = DrillItem("The bear is blue!", DrillLevel.SHORT)

    /** Press, let the mic actually open (as a held finger does), release. */
    private fun TestScope.attempt(f: Fixture, transcript: String, confidence: Float = 0.9f) {
        f.asr.enqueue(AsrResult(transcript = transcript, confidence = confidence))
        f.drill.onMicPressed()
        advanceUntilIdle()
        f.drill.onMicReleased()
    }

    @Test
    fun `a correct repetition celebrates, pays XP, and advances`() = runTest {
        val f = Fixture(this)
        f.drill.startRound(listOf(ball, bear))
        advanceUntilIdle()
        // Intro plus the first line, then the mic is open.
        assertEquals(listOf(DrillOrchestrator.INTRO, ball.text), f.tts.spoken.map { it.text })
        assertTrue(f.drill.state.value is DrillState.AwaitingChild)

        attempt(f, "i see a red ball")
        advanceUntilIdle()

        assertEquals(listOf(DrillEvent.Correct(tries = 1)), f.events)
        assertEquals(DrillOrchestrator.XP_PER_CORRECT, f.profile.current().xp)
        // Praise spoken, then the NEXT item — the round moved on by itself.
        val awaiting = f.drill.state.value as DrillState.AwaitingChild
        assertEquals(bear, awaiting.item)
        // The mic was hinted with the exact target, not a whole lesson.
        val hint = f.asr.recordedHints.single()
        assertTrue(hint is RecognitionHint.ConstrainedVocab && ball.text in hint.phrases)
        f.collector.cancel()
    }

    @Test
    fun `a miss gets the line again, slower`() = runTest {
        val f = Fixture(this)
        f.drill.startRound(listOf(ball))
        advanceUntilIdle()

        attempt(f, "banana")
        advanceUntilIdle()

        val awaiting = f.drill.state.value as DrillState.AwaitingChild
        assertEquals(1, awaiting.triesUsed)
        assertEquals(ball, awaiting.item)
        // "Almost!" then the line at the slow-clear speed.
        val lastTwo = f.tts.spoken.takeLast(2)
        assertEquals(DrillOrchestrator.ALMOST, lastTwo[0].text)
        assertEquals(ball.text, lastTwo[1].text)
        assertEquals(DrillOrchestrator.SLOW_SPEED, lastTwo[1].speed)
        f.collector.cancel()
    }

    @Test
    fun `the third miss advances with encouragement, never a dead end`() = runTest {
        val f = Fixture(this)
        f.drill.startRound(listOf(ball, bear))
        advanceUntilIdle()

        repeat(DrillOrchestrator.MAX_TRIES) {
            attempt(f, "banana")
            advanceUntilIdle()
        }

        assertTrue(DrillEvent.Nearly in f.events)
        assertTrue(f.tts.spoken.any { it.text == DrillOrchestrator.GOOD_TRY })
        // No XP for a miss, but the round moved to the next item.
        assertEquals(0, f.profile.current().xp)
        assertEquals(bear, (f.drill.state.value as DrillState.AwaitingChild).item)
        f.collector.cancel()
    }

    @Test
    fun `silence costs nothing`() = runTest {
        val f = Fixture(this)
        f.drill.startRound(listOf(ball))
        advanceUntilIdle()

        attempt(f, "   ")
        advanceUntilIdle()

        assertEquals(listOf<DrillEvent>(DrillEvent.TooQuiet), f.events)
        val awaiting = f.drill.state.value as DrillState.AwaitingChild
        assertEquals(0, awaiting.triesUsed)
        assertTrue(f.tts.spoken.any { it.text == DrillOrchestrator.DIDNT_HEAR })
        f.collector.cancel()
    }

    @Test
    fun `the round ends with its score, and rounds are distinguishable`() = runTest {
        val f = Fixture(this)
        f.drill.startRound(listOf(ball))
        advanceUntilIdle()
        attempt(f, "I see a red ball")
        advanceUntilIdle()
        assertEquals(DrillState.RoundDone(correct = 1, total = 1, round = 1), f.drill.state.value)

        // Same items, same score — a different round. The UI keys its one-shot
        // celebration on the state, so equal states would swallow the second.
        f.drill.startRound(listOf(ball))
        advanceUntilIdle()
        attempt(f, "I see a red ball")
        advanceUntilIdle()
        assertEquals(DrillState.RoundDone(correct = 1, total = 1, round = 2), f.drill.state.value)
        f.collector.cancel()
    }

    @Test
    fun `personality lines take the flavor voice, teaching lines never do`() = runTest {
        // The whole parrot-voice contract in one test: praise and
        // encouragement go to the flavored engine; the intro, the target line,
        // its slow recast, and "Almost!" stay on the clean teaching voice.
        val teaching = FakeTtsEngine()
        val parrot = FakeTtsEngine()
        val drill = DrillOrchestrator(
            asr = FakeAsrEngine().also {
                it.enqueue(AsrResult("banana", 0.9f))
                it.enqueue(AsrResult("i see a red ball", 0.9f))
            },
            tts = teaching,
            scorer = FakePronunciationScorer(),
            profile = InMemoryProfileStore(),
            scope = this,
            flavorTts = parrot,
        )
        drill.startRound(listOf(ball))
        advanceUntilIdle()
        drill.onMicPressed(); advanceUntilIdle(); drill.onMicReleased() // miss
        advanceUntilIdle()
        drill.onMicPressed(); advanceUntilIdle(); drill.onMicReleased() // correct
        advanceUntilIdle()

        assertEquals(
            listOf(DrillOrchestrator.INTRO, ball.text, DrillOrchestrator.ALMOST, ball.text),
            teaching.spoken.map { it.text },
        )
        assertEquals(1, parrot.spoken.size)
        assertTrue(parrot.spoken.single().text in DrillOrchestrator.PRAISES)
    }

    @Test
    fun `the mic is closed while Tuki is talking and after the round`() = runTest {
        val f = Fixture(this)
        f.drill.onMicPressed() // Idle: ignored
        advanceUntilIdle()
        assertEquals(0, f.asr.startCalls)

        f.drill.startRound(listOf(ball))
        advanceUntilIdle()
        attempt(f, "I see a red ball")
        advanceUntilIdle()
        f.drill.onMicPressed() // RoundDone: ignored
        advanceUntilIdle()
        assertEquals(1, f.asr.startCalls)
        f.collector.cancel()
    }

    // --- early close on a target match (docs/latency.md) ------------------

    @Test
    fun `a speculative match ends the turn before the button lifts`() = runTest {
        val f = Fixture(this)
        f.drill.startRound(listOf(ball))
        advanceUntilIdle()

        f.asr.enqueue(AsrResult(transcript = "i see a red ball", confidence = 0.9f))
        f.drill.onMicPressed()
        advanceUntilIdle()
        // The soft-endpoint guess already matches the target: the turn must
        // end NOW, with the finger still down.
        f.asr.emitSpeculative("i see a red ball")
        advanceUntilIdle()

        assertEquals(listOf(DrillEvent.Correct(tries = 1)), f.events)
        assertEquals(1, f.asr.stopCalls)
        // The eventual release lands after the turn already ended — a no-op.
        f.drill.onMicReleased()
        advanceUntilIdle()
        assertEquals(1, f.asr.stopCalls)
        f.collector.cancel()
    }

    @Test
    fun `a wrong guess closes nothing`() = runTest {
        val f = Fixture(this)
        f.drill.startRound(listOf(ball))
        advanceUntilIdle()

        f.asr.enqueue(AsrResult(transcript = "i see a red ball", confidence = 0.9f))
        f.drill.onMicPressed()
        advanceUntilIdle()
        f.asr.emitSpeculative("banana")
        advanceUntilIdle()

        // Still listening: a guess that does not match must not cut a child
        // off mid-sentence.
        assertTrue(f.drill.state.value is DrillState.Listening)
        f.drill.onMicReleased()
        advanceUntilIdle()
        assertEquals(listOf(DrillEvent.Correct(tries = 1)), f.events)
        f.collector.cancel()
    }
}
