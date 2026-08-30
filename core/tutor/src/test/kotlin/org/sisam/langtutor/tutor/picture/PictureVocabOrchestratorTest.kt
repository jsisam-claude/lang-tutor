package org.sisam.langtutor.tutor.picture

import kotlin.random.Random
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
import org.sisam.langtutor.speech.FakeTtsEngine

@OptIn(ExperimentalCoroutinesApi::class)
class PictureVocabOrchestratorTest {

    private class Fixture(scope: TestScope) {
        val tts = FakeTtsEngine()
        val profile = InMemoryProfileStore()
        val events = mutableListOf<PictureEvent>()
        val room = PictureVocabOrchestrator(tts, profile, scope)
        val collector = scope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            room.events.collect { events += it }
        }
    }

    private val cards = listOf(
        PictureCard("cat", "חתול", "🐱"),
        PictureCard("dog", "כלב", "🐶"),
        PictureCard("fish", "דג", "🐟"),
    )

    @Test
    fun `teaches every card, saying each word, then asks`() = runTest {
        val f = Fixture(this)
        f.room.startRound(cards, Random(1))
        advanceUntilIdle()
        assertEquals(listOf("cat"), f.tts.spoken.map { it.text })

        f.room.onNext()
        advanceUntilIdle()
        f.room.onNext()
        advanceUntilIdle()
        assertEquals(listOf("cat", "dog", "fish"), f.tts.spoken.map { it.text })

        f.room.onNext()
        advanceUntilIdle()
        val asking = f.room.state.value as PictureState.Asking
        // The question is spoken, naming the target.
        val question = f.tts.spoken.last().text
        assertEquals(PictureVocabOrchestrator.question(asking.cards[asking.targetIndex].word), question)
        f.collector.cancel()
    }

    @Test
    fun `tapping the card on stage repeats its word, free`() = runTest {
        val f = Fixture(this)
        f.room.startRound(cards, Random(1))
        advanceUntilIdle()
        f.room.onCardTapped(0)
        advanceUntilIdle()
        assertEquals(listOf("cat", "cat"), f.tts.spoken.map { it.text })
        assertTrue(f.events.isEmpty())
        f.collector.cancel()
    }

    @Test
    fun `a wrong tap is a warm retry, and the right one still wins`() = runTest {
        val f = Fixture(this)
        f.room.startRound(cards, Random(1))
        advanceUntilIdle()
        repeat(3) { f.room.onNext(); advanceUntilIdle() }

        val asking = f.room.state.value as PictureState.Asking
        val wrong = (asking.cards.indices - asking.targetIndex).first()
        f.room.onAnswerPicked(wrong)
        advanceUntilIdle()
        assertEquals(listOf<PictureEvent>(PictureEvent.Wrong), f.events)
        val after = f.room.state.value as PictureState.Asking
        assertTrue(wrong in after.wrongTaps)

        f.room.onAnswerPicked(after.targetIndex)
        advanceUntilIdle()
        // Correct, but not first try — and it still advances the round.
        assertEquals(PictureEvent.Correct(firstTry = false), f.events.last())
        f.collector.cancel()
    }

    @Test
    fun `first-try answers are what the score counts`() = runTest {
        val f = Fixture(this)
        f.room.startRound(cards, Random(1))
        advanceUntilIdle()
        repeat(3) { f.room.onNext(); advanceUntilIdle() }

        repeat(3) {
            val asking = f.room.state.value as PictureState.Asking
            f.room.onAnswerPicked(asking.targetIndex)
            advanceUntilIdle()
        }
        val done = f.room.state.value as PictureState.Done
        assertEquals(3, done.firstTry)
        assertEquals(3, done.total)
        assertEquals(3 * PictureVocabOrchestrator.XP_PER_CORRECT, f.profile.current().xp)
        f.collector.cancel()
    }
}
