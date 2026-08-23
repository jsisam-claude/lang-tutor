package org.sisam.langtutor.tutor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
import org.sisam.langtutor.llm.GenerationStats
import org.sisam.langtutor.llm.LlmEngine
import org.sisam.langtutor.llm.LlmEvent
import org.sisam.langtutor.llm.LlmModelSpec
import org.sisam.langtutor.llm.LlmRequest
import org.sisam.langtutor.llm.Role
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
    fun `input is gated while the model is still loading`() = runTest {
        // A model that blocks in load() until released — mimics a slow first load.
        val loadGate = CompletableDeferred<Unit>()
        val gatedLlm = object : LlmEngine {
            var generateCalls = 0
            override suspend fun load(spec: LlmModelSpec) = loadGate.await()
            override fun generate(request: LlmRequest): Flow<LlmEvent> = flow {
                generateCalls++
                emit(LlmEvent.Done("x", GenerationStats(0, 0, 0f)))
            }
            override suspend fun unload() = Unit
        }
        val asr = FakeAsrEngine()
        val orchestrator = TutorOrchestrator(
            llm = gatedLlm,
            asr = asr,
            tts = FakeTtsEngine(),
            scorer = FakePronunciationScorer(),
            content = ResourceContentRepository(),
            profile = InMemoryProfileStore(),
            policy = ScriptedDialoguePolicy(),
            scope = this,
        )
        val session = launch { orchestrator.startSession("unit-001", TutorMode.SPEECH) }
        advanceUntilIdle()
        assertTrue(orchestrator.state.value is TutorTurnState.Preparing)

        // Mic + text during load are ignored: no capture, no generation.
        orchestrator.onMicPressed()
        orchestrator.onTextSubmitted("hello")
        advanceUntilIdle()
        assertEquals(0, asr.startCalls)
        assertEquals(0, gatedLlm.generateCalls)
        assertTrue(orchestrator.state.value is TutorTurnState.Preparing)

        // Once loaded, the session opens for input. join() waits for startSession
        // to finish, including loadUnit's real Dispatchers.IO hop (which
        // advanceUntilIdle would not await).
        loadGate.complete(Unit)
        session.join()
        assertTrue(orchestrator.state.value is TutorTurnState.AwaitingChild)
    }

    @Test
    fun `second turn carries the first turn as conversation history`() = runTest {
        val fixture = Fixture(this)
        fixture.orchestrator.startSession("unit-001", TutorMode.TEXT)
        fixture.orchestrator.onTextSubmitted("My name is Noa")
        advanceUntilIdle()
        fixture.orchestrator.onTextSubmitted("What is my name?")
        advanceUntilIdle()

        assertEquals(2, fixture.llm.calls.size)
        val second = fixture.llm.calls[1].messages
        // History: first child turn + Tuki's first reply are in the request…
        assertTrue(second.any { it.role == Role.USER && it.text == "My name is Noa" })
        assertTrue(second.any { it.role == Role.ASSISTANT })
        // …and the newest message is the current utterance.
        assertEquals("What is my name?", second.last().text)
        assertEquals(Role.USER, second.last().role)
    }

    @Test
    fun `mic press during Speaking hushes the voice instead of being ignored`() = runTest {
        // A TTS whose speak() blocks until released — holds the orchestrator
        // in the Speaking state so the barge-in path is actually reachable.
        val speakGate = CompletableDeferred<Unit>()
        val gatedTts = object : org.sisam.langtutor.speech.TtsEngine {
            var stopCalls = 0
            override fun speak(
                text: String,
                language: org.sisam.langtutor.speech.TutorLanguage,
                speed: Float,
            ): Flow<org.sisam.langtutor.speech.TtsEvent> = flow {
                emit(org.sisam.langtutor.speech.TtsEvent.Started)
                speakGate.await()
                emit(org.sisam.langtutor.speech.TtsEvent.Completed)
            }
            override suspend fun stop() {
                stopCalls++
                speakGate.complete(Unit)
            }
        }
        val orchestrator = TutorOrchestrator(
            llm = FakeLlmEngine(),
            asr = FakeAsrEngine(),
            tts = gatedTts,
            scorer = FakePronunciationScorer(),
            content = ResourceContentRepository(),
            profile = InMemoryProfileStore(),
            policy = ScriptedDialoguePolicy(),
            scope = this,
        )
        orchestrator.startSession("unit-001", TutorMode.TEXT)
        val turn = launch { orchestrator.onTextSubmitted("The ball is red") }
        advanceUntilIdle()
        assertTrue(orchestrator.state.value is TutorTurnState.Speaking)

        orchestrator.onMicPressed() // barge-in: hush, don't ignore
        advanceUntilIdle()
        turn.join()

        assertEquals(1, gatedTts.stopCalls)
        assertTrue(orchestrator.state.value is TutorTurnState.AwaitingChild)
    }

    @Test
    fun `hands-free turn ends on the endpoint without a button release`() = runTest {
        // An ASR that reports hands-free support and only "hears" the end of the
        // utterance when the test releases the gate — like the real VAD does.
        val endpoint = CompletableDeferred<Unit>()
        val handsFreeAsr = object : org.sisam.langtutor.speech.AsrEngine {
            var startCalls = 0
            var stopCalls = 0
            override val supportsHandsFree = true
            override suspend fun startCapture(hint: RecognitionHint) { startCalls++ }
            override suspend fun awaitEndpoint() = endpoint.await()
            override suspend fun stopCapture(): AsrResult {
                stopCalls++
                return AsrResult("I see a red ball", 0.9f)
            }
        }
        val orchestrator = TutorOrchestrator(
            llm = FakeLlmEngine(),
            asr = handsFreeAsr,
            tts = FakeTtsEngine(),
            scorer = FakePronunciationScorer(),
            content = ResourceContentRepository(),
            profile = InMemoryProfileStore(),
            policy = ScriptedDialoguePolicy(),
            scope = this,
        )
        orchestrator.startSession("unit-001", TutorMode.SPEECH)
        orchestrator.handsFree = true

        orchestrator.onMicPressed()
        advanceUntilIdle()
        assertTrue(orchestrator.state.value is TutorTurnState.Listening)
        // A stray release must NOT cut the child off while hands-free.
        orchestrator.onMicReleased()
        advanceUntilIdle()
        assertEquals(0, handsFreeAsr.stopCalls)
        assertTrue(orchestrator.state.value is TutorTurnState.Listening)

        endpoint.complete(Unit) // the VAD decides the child finished
        advanceUntilIdle()
        assertEquals(1, handsFreeAsr.stopCalls)
        assertEquals("I see a red ball", orchestrator.transcript.value.first().text)
        assertTrue(orchestrator.state.value is TutorTurnState.AwaitingChild)
    }

    @Test
    fun `hands-free cannot be enabled on a push-to-talk engine`() = runTest {
        val fixture = Fixture(this) // FakeAsrEngine: no VAD
        assertTrue(!fixture.orchestrator.handsFreeAvailable)
        fixture.orchestrator.handsFree = true
        assertTrue("must not pretend to support hands-free", !fixture.orchestrator.handsFree)
    }

    @Test
    fun `a spoken lesson attempt gets per-sound feedback`() = runTest {
        val fixture = Fixture(this)
        // The fake ASR returns audio for the turn, so the scorer can run.
        fixture.asr.enqueue(
            AsrResult(
                transcript = "I see a red ball",
                confidence = 0.9f,
                audio = org.sisam.langtutor.speech.AudioClip(ShortArray(16_000)),
            ),
        )
        fixture.orchestrator.startSession("unit-001", TutorMode.SPEECH)
        fixture.orchestrator.onMicPressed()
        advanceUntilIdle()
        fixture.orchestrator.onMicReleased()
        advanceUntilIdle()

        val score = fixture.orchestrator.pronunciation.value
        assertTrue("expected per-sound feedback", score != null && score.phonemes.isNotEmpty())
        // …and it is cleared when the next turn starts, never shown stale.
        fixture.orchestrator.onMicPressed()
        advanceUntilIdle()
        assertEquals(null, fixture.orchestrator.pronunciation.value)
    }

    @Test
    fun `typed turns are never scored for pronunciation`() = runTest {
        val fixture = Fixture(this)
        fixture.orchestrator.startSession("unit-001", TutorMode.TEXT)
        fixture.orchestrator.onTextSubmitted("I see a red ball")
        advanceUntilIdle()
        assertEquals(null, fixture.orchestrator.pronunciation.value)
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

    @Test
    fun `tts failure during a streamed reply fails the turn instead of crashing`() = runTest {
        // The streaming path collects TTS in a separate launch; an engine
        // failure there must land in the turn's Failed state, not escape to
        // the scope (which on Android has no handler and kills the process).
        val explodingTts = object : org.sisam.langtutor.speech.TtsEngine {
            override fun speak(
                text: String,
                language: org.sisam.langtutor.speech.TutorLanguage,
                speed: Float,
            ): Flow<org.sisam.langtutor.speech.TtsEvent> = flow {
                emit(org.sisam.langtutor.speech.TtsEvent.Started)
                emit(org.sisam.langtutor.speech.TtsEvent.Completed)
            }
            override fun speakStream(
                chunks: Flow<String>,
                language: org.sisam.langtutor.speech.TutorLanguage,
                speed: Float,
            ): Flow<org.sisam.langtutor.speech.TtsEvent> = flow {
                chunks.collect { throw IllegalStateException("audio device lost") }
            }
            override suspend fun stop() = Unit
        }
        val orchestrator = TutorOrchestrator(
            llm = FakeLlmEngine(),
            asr = FakeAsrEngine(),
            tts = explodingTts,
            scorer = FakePronunciationScorer(),
            content = ResourceContentRepository(),
            profile = InMemoryProfileStore(),
            policy = ScriptedDialoguePolicy(),
            scope = this,
        )
        orchestrator.startSession("unit-001", TutorMode.TEXT)
        orchestrator.onTextSubmitted("The ball is red")
        advanceUntilIdle()

        assertTrue(orchestrator.state.value is TutorTurnState.Failed)
    }

    @Test
    fun `mic press while a streamed reply is playing ends the turn promptly`() = runTest {
        // A streaming-capable TTS (like Kokoro): audio begins at the first
        // flushed sentence, mid-decode. The barge-in tap must hush AND end the
        // turn at the next token — not leave the mic dead until the full
        // decode finishes, and not let the dismissed reply keep playing.
        val decodeGate = CompletableDeferred<Unit>()
        val slowLlm = object : LlmEngine {
            override suspend fun load(spec: LlmModelSpec) = Unit
            override fun generate(request: LlmRequest): Flow<LlmEvent> = flow {
                emit(LlmEvent.Token("Here is a ball. "))
                decodeGate.await()
                emit(LlmEvent.Token("Kick it far! "))
                emit(LlmEvent.Token("Then bring it back!"))
                emit(LlmEvent.Done("Here is a ball. Kick it far! Then bring it back!", GenerationStats(0, 3, 0f)))
            }
            override suspend fun unload() = Unit
        }
        val streamingTts = object : org.sisam.langtutor.speech.TtsEngine {
            val streamed = mutableListOf<String>()
            var stopCalls = 0
            override fun speak(
                text: String,
                language: org.sisam.langtutor.speech.TutorLanguage,
                speed: Float,
            ): Flow<org.sisam.langtutor.speech.TtsEvent> = flow {
                emit(org.sisam.langtutor.speech.TtsEvent.Started)
                emit(org.sisam.langtutor.speech.TtsEvent.Completed)
            }
            override fun speakStream(
                chunks: Flow<String>,
                language: org.sisam.langtutor.speech.TutorLanguage,
                speed: Float,
            ): Flow<org.sisam.langtutor.speech.TtsEvent> = flow {
                emit(org.sisam.langtutor.speech.TtsEvent.Started)
                chunks.collect { streamed += it }
                emit(org.sisam.langtutor.speech.TtsEvent.Completed)
            }
            override suspend fun stop() {
                stopCalls++
            }
        }
        val asr = FakeAsrEngine()
        val orchestrator = TutorOrchestrator(
            llm = slowLlm,
            asr = asr,
            tts = streamingTts,
            scorer = FakePronunciationScorer(),
            content = ResourceContentRepository(),
            profile = InMemoryProfileStore(),
            policy = ScriptedDialoguePolicy(),
            scope = this,
        )
        orchestrator.startSession("unit-001", TutorMode.TEXT)
        val turn = launch { orchestrator.onTextSubmitted("The ball is red") }
        advanceUntilIdle() // first sentence flushed to TTS, decode now gated

        assertTrue(orchestrator.state.value is TutorTurnState.Speaking)
        orchestrator.onMicPressed() // barge-in mid-decode
        advanceUntilIdle()
        assertEquals(1, streamingTts.stopCalls)
        assertEquals(0, asr.startCalls) // the tap hushed; it did not listen

        decodeGate.complete(Unit)
        advanceUntilIdle()
        turn.join()
        // The turn ended at the next token: nothing after the barge was
        // spoken, and the transcript records only what the child heard.
        assertEquals(listOf("Here is a ball."), streamingTts.streamed)
        assertEquals("Here is a ball.", orchestrator.transcript.value.last().text)
        assertTrue(orchestrator.state.value is TutorTurnState.AwaitingChild)
    }

    @Test
    fun `a clean reply over the length cap is cut at a sentence boundary`() = runTest {
        // Every sentence is safe; only the TOTAL trips the cap. The child must
        // keep the audio already playing (no fallback non-sequitur, no cut
        // mid-word) and the transcript must match what was actually heard.
        val sentence = { i: Int -> "This is the very long and very happy sentence number $i " +
            "about a kind and friendly little parrot called Tuki who truly loves " +
            "to teach children fun new English words all day and all night long. " }
        val fullReply = (1..4).joinToString("") { sentence(it) }.trim()
        val longLlm = object : LlmEngine {
            override suspend fun load(spec: LlmModelSpec) = Unit
            override fun generate(request: LlmRequest): Flow<LlmEvent> = flow {
                for (i in 1..4) emit(LlmEvent.Token(sentence(i)))
                emit(LlmEvent.Done(fullReply, GenerationStats(0, 4, 0f)))
            }
            override suspend fun unload() = Unit
        }
        val tts = FakeTtsEngine()
        val orchestrator = TutorOrchestrator(
            llm = longLlm,
            asr = FakeAsrEngine(),
            tts = tts,
            scorer = FakePronunciationScorer(),
            content = ResourceContentRepository(),
            profile = InMemoryProfileStore(),
            policy = ScriptedDialoguePolicy(),
            scope = this,
        )
        orchestrator.startSession("unit-001", TutorMode.TEXT)
        orchestrator.onTextSubmitted("The ball is red")
        advanceUntilIdle()

        val spoken = tts.spoken.single().text
        assertTrue(spoken.contains("number 1"))
        assertTrue(spoken.contains("number 3"))
        assertTrue(!spoken.contains("number 4")) // dropped at the cap
        assertTrue(!spoken.contains(TutorOrchestrator.SAFE_FALLBACK_REPLY))
        assertEquals(spoken, orchestrator.transcript.value.last().text)
        assertTrue(orchestrator.state.value is TutorTurnState.AwaitingChild)
    }

    @Test
    fun `an ender that lands mid-number does not lose the rest of the reply`() = runTest {
        // "You have 3" + "." looks sentence-final until "5 apples!" arrives
        // and dissolves the boundary; the dissolved chunk's shifted offsets
        // used to make the flush skip the rest of the reply forever.
        val decimalLlm = object : LlmEngine {
            override suspend fun load(spec: LlmModelSpec) = Unit
            override fun generate(request: LlmRequest): Flow<LlmEvent> = flow {
                emit(LlmEvent.Token("You have 3"))
                emit(LlmEvent.Token("."))
                emit(LlmEvent.Token("5 apples!"))
                emit(LlmEvent.Done("You have 3.5 apples!", GenerationStats(0, 3, 0f)))
            }
            override suspend fun unload() = Unit
        }
        val tts = FakeTtsEngine()
        val orchestrator = TutorOrchestrator(
            llm = decimalLlm,
            asr = FakeAsrEngine(),
            tts = tts,
            scorer = FakePronunciationScorer(),
            content = ResourceContentRepository(),
            profile = InMemoryProfileStore(),
            policy = ScriptedDialoguePolicy(),
            scope = this,
        )
        orchestrator.startSession("unit-001", TutorMode.TEXT)
        orchestrator.onTextSubmitted("The ball is red")
        advanceUntilIdle()

        assertEquals("You have 3.5 apples!", tts.spoken.single().text)
        assertEquals("You have 3.5 apples!", orchestrator.transcript.value.last().text)
    }
}
