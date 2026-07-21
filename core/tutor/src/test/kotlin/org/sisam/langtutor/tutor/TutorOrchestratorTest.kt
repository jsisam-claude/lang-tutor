package org.sisam.langtutor.tutor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sisam.langtutor.content.ResourceContentRepository
import org.sisam.langtutor.llm.FakeLlmEngine
import org.sisam.langtutor.profile.InMemoryProfileStore
import org.sisam.langtutor.speech.AsrResult
import org.sisam.langtutor.speech.FakeAsrEngine
import org.sisam.langtutor.speech.FakePronunciationScorer
import org.sisam.langtutor.speech.FakeTtsEngine
import org.sisam.langtutor.speech.RecognitionHint

@OptIn(ExperimentalCoroutinesApi::class)
class TutorOrchestratorTest {

    private class Fixture(scope: TestScope, llmScript: List<String>? = null) {
        val llm = if (llmScript != null) FakeLlmEngine(llmScript) else FakeLlmEngine()
        val asr = FakeAsrEngine()
        val tts = FakeTtsEngine()
        val profile = InMemoryProfileStore()
        val orchestrator = TutorOrchestrator(
            llm = llm,
            asr = asr,
            tts = tts,
            scorer = FakePronunciationScorer(),
            content = ResourceContentRepository(),
            profile = profile,
            policy = ScriptedDialoguePolicy(),
            scope = scope,
        )
    }

    @Test
    fun `speech turn happy path walks the full state machine`() = runTest {
        val fixture = Fixture(this)
        val states = mutableListOf<TutorTurnState>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.orchestrator.state.collect { states += it }
        }

        fixture.orchestrator.startSession("unit-001", TutorMode.SPEECH)
        fixture.orchestrator.onMicPressed()
        advanceUntilIdle()
        fixture.orchestrator.onMicReleased()
        advanceUntilIdle()
        collector.cancel()

        assertTrue(states.any { it is TutorTurnState.Listening })
        assertTrue(states.any { it is TutorTurnState.Transcribing })
        assertTrue(states.any { it is TutorTurnState.Thinking && it.partialReply.isNotEmpty() })
        assertTrue(states.any { it is TutorTurnState.Speaking })
        assertTrue(fixture.orchestrator.state.value is TutorTurnState.AwaitingChild)

        val transcript = fixture.orchestrator.transcript.value
        assertEquals(listOf(Speaker.CHILD, Speaker.TUTOR), transcript.map { it.speaker })
        assertEquals("I see a red ball", transcript.first().text)

        assertEquals(TutorOrchestrator.XP_PER_TURN, fixture.profile.current().xp)
        assertEquals(1, fixture.tts.spoken.size)
        val hint = fixture.asr.recordedHints.single()
        assertTrue(hint is RecognitionHint.ConstrainedVocab && "ball" in hint.phrases)
    }

    @Test
    fun `low confidence asks child to repeat without invoking the LLM`() = runTest {
        val fixture = Fixture(this)
        fixture.asr.enqueue(AsrResult(transcript = "mumble", confidence = 0.2f))

        fixture.orchestrator.startSession("unit-001", TutorMode.SPEECH)
        fixture.orchestrator.onMicPressed()
        advanceUntilIdle()
        fixture.orchestrator.onMicReleased()
        advanceUntilIdle()

        assertTrue(fixture.llm.calls.isEmpty())
        assertEquals(1, fixture.tts.spoken.size)
        assertTrue(fixture.tts.spoken.single().text.contains("again"))
        assertTrue(fixture.orchestrator.state.value is TutorTurnState.AwaitingChild)
    }

    @Test
    fun `unsafe LLM reply is replaced by the safe fallback`() = runTest {
        val fixture = Fixture(this, llmScript = listOf("Let's play with the gun outside!"))

        fixture.orchestrator.startSession("unit-001", TutorMode.TEXT)
        fixture.orchestrator.onTextSubmitted("What should we play?")
        advanceUntilIdle()

        val tutorLine = fixture.orchestrator.transcript.value.last()
        assertEquals(Speaker.TUTOR, tutorLine.speaker)
        assertEquals(TutorOrchestrator.SAFE_FALLBACK_REPLY, tutorLine.text)
        assertEquals(TutorOrchestrator.SAFE_FALLBACK_REPLY, fixture.tts.spoken.single().text)
    }

    @Test
    fun `text channel bypasses ASR and runs the same policy`() = runTest {
        val fixture = Fixture(this)

        fixture.orchestrator.startSession("unit-001", TutorMode.TEXT)
        fixture.orchestrator.onTextSubmitted("The ball is red")
        advanceUntilIdle()

        assertEquals(0, fixture.asr.startCalls)
        assertEquals(0, fixture.asr.stopCalls)
        assertEquals(1, fixture.llm.calls.size)
        assertTrue(fixture.orchestrator.state.value is TutorTurnState.AwaitingChild)
        assertEquals(TutorOrchestrator.XP_PER_TURN, fixture.profile.current().xp)
    }
}
