package org.sisam.langtutor.tutor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.sisam.langtutor.content.ResourceContentRepository
import org.sisam.langtutor.llm.FakeLlmEngine
import org.sisam.langtutor.profile.InMemoryProfileStore
import org.sisam.langtutor.profile.LearnerProfile
import org.sisam.langtutor.profile.LearnerTrack
import org.sisam.langtutor.speech.FakeAsrEngine
import org.sisam.langtutor.speech.FakePronunciationScorer
import org.sisam.langtutor.speech.FakeTtsEngine

@OptIn(ExperimentalCoroutinesApi::class)
class ReplyBudgetTest {

    @Test
    fun `cool and unknown leave the budget alone`() {
        assertEquals(96, ReplyBudget.scaled(96, 0.5f))
        assertEquals(96, ReplyBudget.scaled(96, 0.79f))
        // NaN means "the platform declined to answer" — guessing hot would
        // quietly shorten every reply on devices that simply do not report.
        assertEquals(96, ReplyBudget.scaled(96, Float.NaN))
    }

    @Test
    fun `warm trims, hot halves`() {
        assertEquals(72, ReplyBudget.scaled(96, 0.85f))
        assertEquals(48, ReplyBudget.scaled(96, 1.0f))
        assertEquals(48, ReplyBudget.scaled(96, 1.3f))
    }

    @Test
    fun `never below a usable turn`() {
        // Half of 48 would be 24 — too small for praise plus a question.
        assertEquals(ReplyBudget.FLOOR_TOKENS, ReplyBudget.scaled(48, 1.2f))
        // A budget already below the floor is not INCREASED by scaling.
        assertEquals(24, ReplyBudget.scaled(24, 1.2f))
    }

    @Test
    fun `a throttled phone shortens the lesson reply end to end`() = runTest {
        // The wire-up, not just the arithmetic: the orchestrator must read the
        // injected headroom per turn and hand the shrunken budget to the LLM.
        val llm = FakeLlmEngine()
        var headroom = 0.5f
        val tutor = TutorOrchestrator(
            llm = llm,
            asr = FakeAsrEngine(),
            tts = FakeTtsEngine(),
            scorer = FakePronunciationScorer(),
            content = ResourceContentRepository(),
            profile = InMemoryProfileStore(LearnerProfile(track = LearnerTrack.BEGINNER)),
            policy = ScriptedDialoguePolicy(),
            scope = this,
            thermalHeadroom = { headroom },
        )
        tutor.startSession("unit-007", TutorMode.TEXT)
        advanceUntilIdle()

        tutor.onTextSubmitted("I see a red ball")
        advanceUntilIdle()
        val cool = llm.calls.last().maxTokens

        headroom = 1.1f
        tutor.onTextSubmitted("I see a blue ball")
        advanceUntilIdle()
        val hot = llm.calls.last().maxTokens

        assertEquals(TrackConfig.of(LearnerTrack.BEGINNER).replyTokens, cool)
        assertEquals(ReplyBudget.scaled(cool, 1.1f), hot)
    }
}
