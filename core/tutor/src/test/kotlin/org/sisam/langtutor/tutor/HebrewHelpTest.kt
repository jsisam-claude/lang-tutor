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
import org.sisam.langtutor.speech.FakeAsrEngine
import org.sisam.langtutor.speech.FakePronunciationScorer
import org.sisam.langtutor.speech.FakeTtsEngine

/**
 * The Hebrew escape hatch (docs/learner-levels.md) and the level config that
 * gates it. Two independent gates guard one instruction, so both are asserted
 * separately — a regression that opened either one would ship Hebrew the eval
 * rejected, or a scaffold at the immersion levels that exist to not have one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HebrewHelpTest {

    private fun fixture(
        scope: TestScope,
        level: Int = 2,
        tierSpeaksHebrew: Boolean = true,
    ): Pair<TutorOrchestrator, FakeLlmEngine> {
        val llm = FakeLlmEngine()
        val orchestrator = TutorOrchestrator(
            llm = llm,
            asr = FakeAsrEngine(),
            tts = FakeTtsEngine(),
            scorer = FakePronunciationScorer(),
            content = ResourceContentRepository(),
            profile = InMemoryProfileStore(LearnerProfile(learnerLevel = level)),
            policy = ScriptedDialoguePolicy(),
            scope = scope,
            tierSpeaksHebrew = { tierSpeaksHebrew },
        )
        return orchestrator to llm
    }

    /** A Level 2 unit: no early-unit reply floor in play. */
    private val READING_UNIT = "unit-007"

    /** A Level 1 unit: the early-unit floor and ceremony content. */
    private val YOUNG_UNIT = "unit-001"

    /** The guidance now rides INSIDE the user turn (the KV-leak fix — see
     *  KvReuseTest); the last message is where every instruction lives. */
    private fun lastUserText(llm: FakeLlmEngine, index: Int = 0): String =
        llm.calls[index].messages.last().text

    @Test
    fun `base tier never offers Hebrew, whatever the level`() = runTest {
        val (tutor, _) = fixture(this, level = 2, tierSpeaksHebrew = false)
        tutor.startSession(READING_UNIT, TutorMode.TEXT)
        advanceUntilIdle()
        assertFalse(tutor.hebrewHelpOffered.value)
    }

    @Test
    fun `Level 1 is offered Hebrew, because proficiency is not an age`() = runTest {
        // The old pre-reader veto was an age assumption: a Level 1 ADULT
        // reads Hebrew fine. A learner who cannot still HEARS the reply when
        // a Hebrew voice is installed (TtsRouter routes it automatically).
        val (tutor, _) = fixture(this, level = 1, tierSpeaksHebrew = true)
        tutor.startSession(YOUNG_UNIT, TutorMode.TEXT)
        advanceUntilIdle()
        assertTrue(tutor.hebrewHelpOffered.value)
    }

    @Test
    fun `offered through Level 5, withheld at 6 and 7`() = runTest {
        for (level in 1..7) {
            val (tutor, _) = fixture(this, level = level, tierSpeaksHebrew = true)
            tutor.startSession(READING_UNIT, TutorMode.TEXT)
            advanceUntilIdle()
            assertEquals(
                "level $level",
                level <= 5,
                tutor.hebrewHelpOffered.value,
            )
        }
    }

    @Test
    fun `tapping it injects the instruction and its own token budget`() = runTest {
        val (tutor, llm) = fixture(this)
        tutor.startSession(READING_UNIT, TutorMode.TEXT)
        advanceUntilIdle()

        tutor.onHebrewHelpRequested()
        advanceUntilIdle()

        assertEquals(1, llm.calls.size)
        assertEquals(
            TutorOrchestrator.guideWrap(
                TutorOrchestrator.HEBREW_HELP_INSTRUCTION,
                TutorOrchestrator.HEBREW_HELP_REQUEST,
            ),
            lastUserText(llm),
        )
        // A bilingual turn carries two scripts; the ordinary budget clips it.
        assertEquals(TutorOrchestrator.HEBREW_REPLY_TOKENS, llm.calls.single().maxTokens)
    }

    @Test
    fun `asking for Hebrew enters the transcript as a real learner turn`() = runTest {
        // Not cosmetic. LiteRtLmEngine sends the LAST message of a request as
        // the user turn, so an empty utterance handed the model Tuki's own
        // previous reply as the child's words. The guide is wrapped into that
        // same turn now, but the child's request must still END it.
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
        // The user turn the engine sees carries the request as its final
        // words — never the instruction alone, never Tuki's own last line.
        assertTrue(
            llm.calls.single().messages.last().text
                .endsWith(TutorOrchestrator.HEBREW_HELP_REQUEST),
        )
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
        assertTrue(last.text.endsWith(TutorOrchestrator.HEBREW_HELP_REQUEST))
        assertEquals(Role.USER, last.role)
    }

    @Test
    fun `asking for help earns no XP, so it cannot farm stickers`() = runTest {
        val store = InMemoryProfileStore(LearnerProfile(learnerLevel = 2))
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
            profile = InMemoryProfileStore(LearnerProfile(learnerLevel = 2)),
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

        assertTrue(lastUserText(llm).contains(TutorOrchestrator.HEBREW_HELP_INSTRUCTION))
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

        assertFalse(lastUserText(llm).contains(TutorOrchestrator.HEBREW_HELP_INSTRUCTION))
    }

    @Test
    fun `typing English never triggers it`() = runTest {
        val (tutor, llm) = fixture(this)
        tutor.startSession(READING_UNIT, TutorMode.TEXT)
        advanceUntilIdle()

        tutor.onTextSubmitted("I see a red ball")
        advanceUntilIdle()

        assertFalse(lastUserText(llm).contains(TutorOrchestrator.HEBREW_HELP_INSTRUCTION))
    }

    @Test
    fun `the level sets the reply budget and the persona`() = runTest {
        // A Level 2 unit, so the early-unit floor is not in play here.
        val (tutor, llm) = fixture(this, level = 5)
        tutor.startSession(READING_UNIT, TutorMode.TEXT)
        advanceUntilIdle()

        tutor.onTextSubmitted("I see a red ball")
        advanceUntilIdle()

        val request = llm.calls.single()
        assertEquals(LevelConfig.of(5).replyTokens, request.maxTokens)
        assertTrue(
            "the level persona should ride along with the shared prompt",
            request.systemPrompt.contains(LevelConfig.of(5).personaSuffix),
        )
        assertTrue(request.systemPrompt.contains("You are Tuki"))
    }

    @Test
    fun `a Level 1 unit floors the budget however high the level is`() = runTest {
        // Pedagogy, not just decode time: early-level content is one short
        // sentence and a question whatever the profile says — unit-001 is a
        // Level 1 unit.
        val (tutor, llm) = fixture(this, level = 5)
        tutor.startSession(YOUNG_UNIT, TutorMode.TEXT)
        advanceUntilIdle()
        tutor.onTextSubmitted("I see a red ball")
        advanceUntilIdle()

        assertEquals(TutorOrchestrator.EARLY_UNIT_REPLY_TOKENS, llm.calls.single().maxTokens)
    }
}
