package org.sisam.langtutor.tutor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sisam.langtutor.content.ResourceContentRepository
import org.sisam.langtutor.llm.FakeLlmEngine
import org.sisam.langtutor.llm.Role
import org.sisam.langtutor.profile.InMemoryProfileStore
import org.sisam.langtutor.profile.LearnerProfile
import org.sisam.langtutor.profile.LearnerTrack
import org.sisam.langtutor.speech.FakeAsrEngine
import org.sisam.langtutor.speech.FakePronunciationScorer
import org.sisam.langtutor.speech.FakeTtsEngine

/**
 * The Hebrew escape hatch (docs/learner-tracks.md) and the track config that
 * gates it. Two independent gates guard one instruction, so both are asserted
 * separately — a regression that opened either one would ship Hebrew the eval
 * rejected, or a button that does nothing for a child who cannot read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HebrewHelpTest {

    private fun fixture(
        scope: TestScope,
        track: LearnerTrack = LearnerTrack.BEGINNER,
        tierSpeaksHebrew: Boolean = true,
    ): Pair<TutorOrchestrator, FakeLlmEngine> {
        val llm = FakeLlmEngine()
        val orchestrator = TutorOrchestrator(
            llm = llm,
            asr = FakeAsrEngine(),
            tts = FakeTtsEngine(),
            scorer = FakePronunciationScorer(),
            content = ResourceContentRepository(),
            profile = InMemoryProfileStore(LearnerProfile(track = track)),
            policy = ScriptedDialoguePolicy(),
            scope = scope,
            tierSpeaksHebrew = { tierSpeaksHebrew },
        )
        return orchestrator to llm
    }

    private fun systemInstruction(llm: FakeLlmEngine, index: Int = 0): String =
        llm.calls[index].messages.first { it.role == Role.SYSTEM }.text

    @Test
    fun `base tier never offers Hebrew, whatever the track`() = runTest {
        val (tutor, _) = fixture(this, track = LearnerTrack.BEGINNER, tierSpeaksHebrew = false)
        tutor.startSession("unit-001", TutorMode.TEXT)
        advanceUntilIdle()
        assertFalse(tutor.hebrewHelpOffered.value)
    }

    @Test
    fun `a pre-reader is never offered Hebrew TEXT, even on the capable tier`() = runTest {
        // Hebrew writing is worthless to a child who cannot read Hebrew either;
        // their path is pre-recorded spoken Hebrew, not this button.
        val (tutor, _) = fixture(this, track = LearnerTrack.PRE_READER, tierSpeaksHebrew = true)
        tutor.startSession("unit-001", TutorMode.TEXT)
        advanceUntilIdle()
        assertFalse(tutor.hebrewHelpOffered.value)
    }

    @Test
    fun `capable tier plus a reading track offers it`() = runTest {
        val (tutor, _) = fixture(this, track = LearnerTrack.BEGINNER, tierSpeaksHebrew = true)
        tutor.startSession("unit-001", TutorMode.TEXT)
        advanceUntilIdle()
        assertTrue(tutor.hebrewHelpOffered.value)
    }

    @Test
    fun `tapping it injects the instruction and its own token budget`() = runTest {
        val (tutor, llm) = fixture(this)
        tutor.startSession("unit-001", TutorMode.TEXT)
        advanceUntilIdle()

        tutor.onHebrewHelpRequested()
        advanceUntilIdle()

        assertEquals(1, llm.calls.size)
        assertEquals(TutorOrchestrator.HEBREW_HELP_INSTRUCTION, systemInstruction(llm))
        // A bilingual turn carries two scripts; the ordinary budget clips it.
        assertEquals(TutorOrchestrator.HEBREW_REPLY_TOKENS, llm.calls.single().maxTokens)
    }

    @Test
    fun `asking for Hebrew adds no phantom child turn to the transcript`() = runTest {
        // The tap is a request to re-explain, not something the learner said.
        val (tutor, _) = fixture(this)
        tutor.startSession("unit-001", TutorMode.TEXT)
        advanceUntilIdle()

        tutor.onHebrewHelpRequested()
        advanceUntilIdle()

        assertEquals(listOf(Speaker.TUTOR), tutor.transcript.value.map { it.speaker })
    }

    @Test
    fun `typing Hebrew triggers the explanation without any button`() = runTest {
        // A learner who types Hebrew has told us plainly that English is not
        // landing — a deterministic signal, unlike guessing from ASR confidence.
        val (tutor, llm) = fixture(this)
        tutor.startSession("unit-001", TutorMode.TEXT)
        advanceUntilIdle()

        tutor.onTextSubmitted("לא הבנתי")
        advanceUntilIdle()

        assertEquals(TutorOrchestrator.HEBREW_HELP_INSTRUCTION, systemInstruction(llm))
        // The learner's own words still belong in the transcript.
        assertEquals("לא הבנתי", tutor.transcript.value.first().text)
    }

    @Test
    fun `typing Hebrew on the base tier falls through to the ordinary lesson move`() = runTest {
        val (tutor, llm) = fixture(this, tierSpeaksHebrew = false)
        tutor.startSession("unit-001", TutorMode.TEXT)
        advanceUntilIdle()

        tutor.onTextSubmitted("לא הבנתי")
        advanceUntilIdle()

        assertFalse(systemInstruction(llm) == TutorOrchestrator.HEBREW_HELP_INSTRUCTION)
    }

    @Test
    fun `typing English never triggers it`() = runTest {
        val (tutor, llm) = fixture(this)
        tutor.startSession("unit-001", TutorMode.TEXT)
        advanceUntilIdle()

        tutor.onTextSubmitted("I see a red ball")
        advanceUntilIdle()

        assertFalse(systemInstruction(llm) == TutorOrchestrator.HEBREW_HELP_INSTRUCTION)
    }

    @Test
    fun `the track sets the reply budget and the persona`() = runTest {
        // unit-007 is a 5-8 unit, so the age floor is not in play here.
        val (tutor, llm) = fixture(this, track = LearnerTrack.EXAM)
        tutor.startSession("unit-007", TutorMode.TEXT)
        advanceUntilIdle()

        tutor.onTextSubmitted("I see a red ball")
        advanceUntilIdle()

        val request = llm.calls.single()
        assertEquals(TrackConfig.of(LearnerTrack.EXAM).replyTokens, request.maxTokens)
        assertTrue(
            "the exam persona should ride along with the shared prompt",
            request.systemPrompt.contains(TrackConfig.of(LearnerTrack.EXAM).personaSuffix),
        )
        assertTrue(request.systemPrompt.contains("You are Tuki"))
    }

    @Test
    fun `an age-4-6 unit floors the budget however talkative the track is`() = runTest {
        // Pedagogy, not just decode time: a young child loses the thread in a
        // long reply, and unit-001 is a 4-6 unit whatever the profile says.
        val (tutor, llm) = fixture(this, track = LearnerTrack.EXAM)
        tutor.startSession("unit-001", TutorMode.TEXT)
        advanceUntilIdle()
        tutor.onTextSubmitted("I see a red ball")
        advanceUntilIdle()

        assertEquals(48, llm.calls.single().maxTokens)
    }
}
