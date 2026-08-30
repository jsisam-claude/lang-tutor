package org.sisam.langtutor.tutor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sisam.langtutor.content.ResourceContentRepository
import org.sisam.langtutor.llm.ConvoReuse
import org.sisam.langtutor.llm.ConvoState
import org.sisam.langtutor.llm.GenerationStats
import org.sisam.langtutor.llm.LlmEngine
import org.sisam.langtutor.llm.LlmEvent
import org.sisam.langtutor.llm.LlmModelSpec
import org.sisam.langtutor.llm.LlmRequest
import org.sisam.langtutor.llm.Role
import org.sisam.langtutor.profile.InMemoryProfileStore
import org.sisam.langtutor.profile.LearnerProfile
import org.sisam.langtutor.profile.LearnerTrack
import org.sisam.langtutor.speech.FakeAsrEngine
import org.sisam.langtutor.speech.FakePronunciationScorer
import org.sisam.langtutor.speech.FakeTtsEngine

/**
 * The lesson room's KV-reuse contract, tested against the REAL reuse rule.
 *
 * The room once sent its per-turn guidance as a leading SYSTEM message. The
 * engine folds those into the conversation's system text, and `ConvoReuse`
 * requires that text to be byte-identical — so every change of guidance (a
 * Hebrew-help tap, and the ordinary turn after it) re-prefilled the entire
 * conversation. The fix moved guidance inside the user turn and gave the
 * orchestrator a ledger of exactly what was sent; these tests hold that shape
 * by driving real turns through [ReuseProbeEngine], which does the same
 * bookkeeping as LiteRtLmEngine and asks `ConvoReuse` the same question.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KvReuseTest {

    /**
     * An engine that mirrors LiteRtLmEngine's conversation bookkeeping —
     * SYSTEM folding, the seen-suffix ledger, `ConvoReuse` — and counts how
     * often a turn could reuse the live conversation versus rebuild it.
     * Replies stream as one delta then a matching Done, so what the
     * orchestrator records equals what the engine would record.
     */
    private class ReuseProbeEngine(
        private val replies: List<String>,
    ) : LlmEngine {
        val calls = mutableListOf<LlmRequest>()
        var rebuilds = 0
        var reuses = 0
        private var state: ConvoState? = null
        private var index = 0

        override suspend fun load(spec: LlmModelSpec) = Unit
        override suspend fun unload() = Unit
        override fun invalidateContext() {
            state = null
        }

        override fun generate(request: LlmRequest): Flow<LlmEvent> = flow {
            calls += request
            // Exactly LiteRtLmEngine's fold: leading SYSTEM messages join the
            // system prompt; everything before the last message is the prior.
            val prior = request.messages.dropLast(1)
            val systemText = (
                listOf(request.systemPrompt) +
                    prior.filter { it.role == Role.SYSTEM }.map { it.text }
                )
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
            val priorTexts = prior.map { it.text }
            val current = state
            if (current != null &&
                ConvoReuse.canReuse(current, systemText, request.temperature, priorTexts)
            ) {
                reuses++
            } else {
                rebuilds++
                state = ConvoState(systemText, request.temperature, priorTexts, 0, dirty = false)
            }
            val reply = replies[index++ % replies.size]
            emit(LlmEvent.Token(reply))
            emit(LlmEvent.Done(reply, GenerationStats(0, 0, 0f)))
            state = state?.let { it.copy(seen = it.seen + request.messages.last().text + reply) }
        }
    }

    private val replies = listOf(
        "Great try! What color is the ball?",
        "זה כדור. A ball! Can you say ball?",
        "Wonderful! Tell me more.",
        "Nice! What else do you see?",
    )

    private fun fixture(
        scope: TestScope,
        policy: DialoguePolicy = ScriptedDialoguePolicy(),
    ): Pair<TutorOrchestrator, ReuseProbeEngine> {
        val llm = ReuseProbeEngine(replies)
        val orchestrator = TutorOrchestrator(
            llm = llm,
            asr = FakeAsrEngine(),
            tts = FakeTtsEngine(),
            scorer = FakePronunciationScorer(),
            content = ResourceContentRepository(),
            profile = InMemoryProfileStore(LearnerProfile(track = LearnerTrack.BEGINNER)),
            policy = policy,
            scope = scope,
            tierSpeaksHebrew = { true },
        )
        return orchestrator to llm
    }

    /** A 5-8 unit, so Hebrew help is on the table. */
    private val UNIT = "unit-007"

    @Test
    fun `changing guidance no longer rebuilds the conversation`() = runTest {
        val (tutor, llm) = fixture(this)
        tutor.startSession(UNIT, TutorMode.TEXT)
        advanceUntilIdle()

        tutor.onTextSubmitted("I see a red ball")
        advanceUntilIdle()
        tutor.onHebrewHelpRequested() // different instruction than the lesson move
        advanceUntilIdle()
        tutor.onTextSubmitted("Now I understand")
        advanceUntilIdle()

        assertEquals(3, llm.calls.size)
        // One build for the first turn; every later turn — including the
        // guidance CHANGES on either side of the Hebrew tap — extends it.
        assertEquals(1, llm.rebuilds)
        assertEquals(2, llm.reuses)
    }

    @Test
    fun `a scripted retry between turns does not poison reuse`() = runTest {
        // AskRepeat appends the child's words to the TRANSCRIPT without any
        // model call. When requests were built from the transcript, the next
        // window held a message the conversation had never seen and the
        // suffix check forced a rebuild. The ledger only records what was
        // actually sent, so the scripted detour is invisible to reuse.
        val policy = object : DialoguePolicy {
            override fun nextMove(context: TurnContext): TutorMove =
                if (context.childUtterance == "mumble") {
                    TutorMove.AskRepeat("Say it nice and slow!")
                } else {
                    ScriptedDialoguePolicy().nextMove(context)
                }
        }
        val (tutor, llm) = fixture(this, policy)
        tutor.startSession(UNIT, TutorMode.TEXT)
        advanceUntilIdle()

        tutor.onTextSubmitted("I see a red ball")
        advanceUntilIdle()
        tutor.onTextSubmitted("mumble") // scripted, no LLM call
        advanceUntilIdle()
        tutor.onTextSubmitted("I see a blue ball")
        advanceUntilIdle()

        assertEquals(2, llm.calls.size)
        assertEquals(1, llm.rebuilds)
        assertEquals(1, llm.reuses)
    }

    @Test
    fun `no lesson request carries a SYSTEM message`() = runTest {
        // The engine folds leading SYSTEM messages into the conversation's
        // system text; anything per-turn in that position IS the leak.
        val (tutor, llm) = fixture(this)
        tutor.startSession(UNIT, TutorMode.TEXT)
        advanceUntilIdle()
        tutor.onTextSubmitted("I see a red ball")
        advanceUntilIdle()
        tutor.onHebrewHelpRequested()
        advanceUntilIdle()

        llm.calls.forEach { request ->
            assertTrue(request.messages.none { it.role == Role.SYSTEM })
        }
    }

    @Test
    fun `the system prompt is identical across turns`() = runTest {
        val (tutor, llm) = fixture(this)
        tutor.startSession(UNIT, TutorMode.TEXT)
        advanceUntilIdle()
        tutor.onTextSubmitted("I see a red ball")
        advanceUntilIdle()
        tutor.onHebrewHelpRequested()
        advanceUntilIdle()

        assertEquals(1, llm.calls.map { it.systemPrompt }.distinct().size)
    }

    @Test
    fun `the guide rides inside the user turn and the words stay last`() = runTest {
        val (tutor, llm) = fixture(this)
        tutor.startSession(UNIT, TutorMode.TEXT)
        advanceUntilIdle()
        tutor.onTextSubmitted("I see a red ball")
        advanceUntilIdle()

        val last = llm.calls.single().messages.last()
        assertEquals(Role.USER, last.role)
        assertTrue("guide missing: ${last.text}", last.text.contains("[Lesson guide:"))
        assertTrue(
            "the child's words must end the turn: ${last.text}",
            last.text.endsWith("I see a red ball"),
        )
    }
}
