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
        canSpeakHebrew: Boolean = false,
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
            canSpeakHebrew = { canSpeakHebrew },
        )
        return orchestrator to llm
    }

    /** A 5-8 unit: old enough that the age band does not veto Hebrew. */
    private val READING_UNIT = "unit-007"

    /** A 4-6 unit: a child who cannot read Hebrew either. */
    private val YOUNG_UNIT = "unit-001"

    private fun systemInstruction(llm: FakeLlmEngine, index: Int = 0): String =
        llm.calls[index].messages.first { it.role == Role.SYSTEM }.text

    @Test
    fun `base tier never offers Hebrew, whatever the track`() = runTest {
        val (tutor, _) = fixture(this, track = LearnerTrack.BEGINNER, tierSpeaksHebrew = false)
        tutor.startSession(READING_UNIT, TutorMode.TEXT)
        advanceUntilIdle()
        assertFalse(tutor.hebrewHelpOffered.value)
    }

    @Test
    fun `a pre-reader is never offered Hebrew TEXT, even on the capable tier`() = runTest {
        // Hebrew writing is worthless to a child who cannot read Hebrew either;
        // their path is pre-recorded spoken Hebrew, not this button.
        val (tutor, _) = fixture(this, track = LearnerTrack.PRE_READER, tierSpeaksHebrew = true)
        tutor.startSession(READING_UNIT, TutorMode.TEXT)
        advanceUntilIdle()
        assertFalse(tutor.hebrewHelpOffered.value)
    }

    @Test
    fun `capable tier plus a reading track offers it`() = runTest {
        val (tutor, _) = fixture(this, track = LearnerTrack.BEGINNER, tierSpeaksHebrew = true)
        tutor.startSession(READING_UNIT, TutorMode.TEXT)
        advanceUntilIdle()
        assertTrue(tutor.hebrewHelpOffered.value)
    }

    @Test
    fun `a Hebrew VOICE lets a pre-reader in, because they can hear it`() = runTest {
        // The text gate exists because a child who cannot read Hebrew gains
        // nothing from Hebrew writing. Spoken Hebrew is the opposite: it is
        // exactly what they need, and it was only ever withheld for want of a
        // voice to say it with.
        val (tutor, _) = fixture(
            this,
            track = LearnerTrack.PRE_READER,
            tierSpeaksHebrew = true,
            canSpeakHebrew = true,
        )
        tutor.startSession(YOUNG_UNIT, TutorMode.TEXT)
        advanceUntilIdle()
        assertTrue(tutor.hebrewHelpOffered.value)
    }

    @Test
    fun `a voice does not override the tier gate`() = runTest {
        // A voice that can SAY anything does not make E2B's Hebrew trustworthy.
        val (tutor, _) = fixture(
            this,
            track = LearnerTrack.BEGINNER,
            tierSpeaksHebrew = false,
            canSpeakHebrew = true,
        )
        tutor.startSession(READING_UNIT, TutorMode.TEXT)
        advanceUntilIdle()
        assertFalse(tutor.hebrewHelpOffered.value)
    }

    @Test
    fun `a 4-6 unit vetoes Hebrew TEXT when there is no voice to say it`() = runTest {
        // The track defaults to BEGINNER, so without this the age band that
        // already governs the reply budget would be ignored by the one feature
        // whose whole premise is "can this learner read?".
        val (tutor, _) = fixture(this, track = LearnerTrack.BEGINNER, tierSpeaksHebrew = true)
        tutor.startSession(YOUNG_UNIT, TutorMode.TEXT)
        advanceUntilIdle()
        assertFalse(tutor.hebrewHelpOffered.value)
    }

    @Test
    fun `tapping it injects the instruction and its own token budget`() = runTest {
        val (tutor, llm) = fixture(this)
        tutor.startSession(READING_UNIT, TutorMode.TEXT)
        advanceUntilIdle()

        tutor.onHebrewHelpRequested()
        advanceUntilIdle()

        assertEquals(1, llm.calls.size)
        assertEquals(TutorOrchestrator.HEBREW_HELP_INSTRUCTION, systemInstruction(llm))
        // A bilingual turn carries two scripts; the ordinary budget clips it.
        assertEquals(TutorOrchestrator.HEBREW_REPLY_TOKENS, llm.calls.single().maxTokens)
    }

    @Test
    fun `asking for Hebrew enters the transcript as a real learner turn`() = runTest {
        // Not cosmetic. LiteRtLmEngine sends the LAST message of a request as
        // the user turn, so an empty utterance handed the model Tuki's own
        // previous reply as the child's words — and, on the very first tap,
        // the instruction itself.
        val (tutor, llm) = fixture(this)
        tutor.startSession(READING_UNIT, TutorMode.TEXT)
        advanceUntilIdle()

        tutor.onHebrewHelpRequested()
        advanceUntilIdle()

        assertEquals(
            listOf(Speaker.CHILD, Speaker.TUTOR),
            tutor.transcript.value.map { it.speaker },
        )
        assertEquals(TutorOrchestrator.HEBREW_HELP_REQUEST, tutor.transcript.value.first().text)
        // The message the engine will treat as the user turn is the request,
        // never the instruction and never Tuki's own last line.
        assertEquals(TutorOrchestrator.HEBREW_HELP_REQUEST, llm.calls.single().messages.last().text)
    }

    @Test
    fun `the request is still the user turn on a tap mid-conversation`() = runTest {
        val (tutor, llm) = fixture(this)
        tutor.startSession(READING_UNIT, TutorMode.TEXT)
        advanceUntilIdle()
        tutor.onTextSubmitted("I see a red ball")
        advanceUntilIdle()

        tutor.onHebrewHelpRequested()
        advanceUntilIdle()

        val last = llm.calls.last().messages.last()
        assertEquals(TutorOrchestrator.HEBREW_HELP_REQUEST, last.text)
        assertEquals(Role.USER, last.role)
    }

    @Test
    fun `asking for help earns no XP, so it cannot farm stickers`() = runTest {
        val store = InMemoryProfileStore(LearnerProfile(track = LearnerTrack.BEGINNER))
        val llm = FakeLlmEngine()
        val tutor = TutorOrchestrator(
            llm = llm,
            asr = FakeAsrEngine(),
            tts = FakeTtsEngine(),
            scorer = FakePronunciationScorer(),
            content = ResourceContentRepository(),
            profile = store,
            policy = ScriptedDialoguePolicy(),
            scope = this,
            tierSpeaksHebrew = { true },
        )
        tutor.startSession(READING_UNIT, TutorMode.TEXT)
        advanceUntilIdle()

        tutor.onHebrewHelpRequested()
        advanceUntilIdle()
        assertEquals(0, store.current().xp)

        // ...but doing the actual work still does.
        tutor.onTextSubmitted("I see a red ball")
        advanceUntilIdle()
        assertEquals(TutorOrchestrator.XP_PER_TURN, store.current().xp)
    }

    @Test
    fun `a bilingual reply is not cut at the old flat 400-character cap`() = runTest {
        // 160 tokens of Hebrew-then-English runs well past 400 characters as a
        // matter of course. The flat cap silently dropped the English half —
        // i.e. the second thing the instruction explicitly asks for.
        val hebrewThenEnglish = "זה אומר שהכדור אדום. " +
            "Now let us try it together in English. " +
            "A ball can be red, blue, or green. " +
            "Can you tell me what colour your ball is at home? " +
            "Take your time and say the whole sentence. " +
            "I am listening carefully to every word you say now. " +
            "Colours are one of the first things we learn together. " +
            "We can practise them again tomorrow if you like that idea. " +
            "There is no hurry at all, so try as many times as you want. " +
            "When you are ready, say it slowly and clearly for me."
        assertTrue("fixture must exceed the old cap", hebrewThenEnglish.length > 400)

        val llm = FakeLlmEngine(listOf(hebrewThenEnglish))
        val tts = FakeTtsEngine()
        val tutor = TutorOrchestrator(
            llm = llm,
            asr = FakeAsrEngine(),
            tts = tts,
            scorer = FakePronunciationScorer(),
            content = ResourceContentRepository(),
            profile = InMemoryProfileStore(LearnerProfile(track = LearnerTrack.BEGINNER)),
            policy = ScriptedDialoguePolicy(),
            scope = this,
            tierSpeaksHebrew = { true },
        )
        tutor.startSession(READING_UNIT, TutorMode.TEXT)
        advanceUntilIdle()

        tutor.onHebrewHelpRequested()
        advanceUntilIdle()

        val reply = tutor.transcript.value.last { it.speaker == Speaker.TUTOR }.text
        assertTrue(
            "reply was cut at ${reply.length} chars: $reply",
            reply.length > 400,
        )
        assertTrue("the English half must survive", reply.contains("what colour your ball"))
    }

    @Test
    fun `typing Hebrew triggers the explanation without any button`() = runTest {
        // A learner who types Hebrew has told us plainly that English is not
        // landing — a deterministic signal, unlike guessing from ASR confidence.
        val (tutor, llm) = fixture(this)
        tutor.startSession(READING_UNIT, TutorMode.TEXT)
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
        tutor.startSession(READING_UNIT, TutorMode.TEXT)
        advanceUntilIdle()

        tutor.onTextSubmitted("לא הבנתי")
        advanceUntilIdle()

        assertFalse(systemInstruction(llm) == TutorOrchestrator.HEBREW_HELP_INSTRUCTION)
    }

    @Test
    fun `typing English never triggers it`() = runTest {
        val (tutor, llm) = fixture(this)
        tutor.startSession(READING_UNIT, TutorMode.TEXT)
        advanceUntilIdle()

        tutor.onTextSubmitted("I see a red ball")
        advanceUntilIdle()

        assertFalse(systemInstruction(llm) == TutorOrchestrator.HEBREW_HELP_INSTRUCTION)
    }

    @Test
    fun `the track sets the reply budget and the persona`() = runTest {
        // A 5-8 unit, so the age floor is not in play here.
        val (tutor, llm) = fixture(this, track = LearnerTrack.EXAM)
        tutor.startSession(READING_UNIT, TutorMode.TEXT)
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
        tutor.startSession(YOUNG_UNIT, TutorMode.TEXT)
        advanceUntilIdle()
        tutor.onTextSubmitted("I see a red ball")
        advanceUntilIdle()

        assertEquals(TutorOrchestrator.YOUNG_REPLY_TOKENS, llm.calls.single().maxTokens)
    }
}
