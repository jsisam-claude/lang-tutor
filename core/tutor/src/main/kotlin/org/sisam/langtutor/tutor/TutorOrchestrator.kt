package org.sisam.langtutor.tutor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.sisam.langtutor.content.Activity
import org.sisam.langtutor.content.ContentRepository
import org.sisam.langtutor.content.CurriculumUnit
import org.sisam.langtutor.llm.ChatMessage
import org.sisam.langtutor.llm.LlmEngine
import org.sisam.langtutor.llm.LlmEvent
import org.sisam.langtutor.llm.LlmModelSpec
import org.sisam.langtutor.llm.LlmRequest
import org.sisam.langtutor.llm.Role
import org.sisam.langtutor.profile.LearnerProfileStore
import org.sisam.langtutor.speech.AsrEngine
import org.sisam.langtutor.speech.PronunciationScorer
import org.sisam.langtutor.speech.RecognitionHint
import org.sisam.langtutor.speech.TtsEngine
import org.sisam.langtutor.speech.TutorLanguage

/**
 * Turn-based tutoring state machine — the executable core of the architecture
 * (docs/architecture.md). Dual-channel by design: speech turns come in through
 * [onMicPressed]/[onMicReleased], text turns through [onTextSubmitted]; both
 * converge on the same [DialoguePolicy].
 *
 * Engines are loaded for the session and unloaded at [endSession] (thermal
 * budget: nothing runs between turns).
 */
class TutorOrchestrator(
    private val llm: LlmEngine,
    private val asr: AsrEngine,
    private val tts: TtsEngine,
    @Suppress("unused") private val scorer: PronunciationScorer,
    private val content: ContentRepository,
    private val profile: LearnerProfileStore,
    private val policy: DialoguePolicy,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<TutorTurnState>(TutorTurnState.Idle)
    val state: StateFlow<TutorTurnState> = _state

    private val _transcript = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcript: StateFlow<List<TranscriptEntry>> = _transcript

    private var currentUnit: CurriculumUnit? = null
    private var turnActive = false

    suspend fun startSession(unitId: String, @Suppress("UNUSED_PARAMETER") mode: TutorMode) {
        llm.load(LlmModelSpec(modelId = "tutor-default"))
        currentUnit = content.loadUnit(unitId)
        val firstPrompt = currentUnit?.activities
            ?.filterIsInstance<Activity.RepeatAfterMe>()
            ?.firstOrNull()?.phrase
        _state.value = TutorTurnState.AwaitingChild(firstPrompt)
    }

    fun onMicPressed() {
        if (turnActive) return
        val current = _state.value
        if (current !is TutorTurnState.AwaitingChild && current != TutorTurnState.Idle) return
        scope.launch {
            _state.value = TutorTurnState.Listening
            asr.startCapture(lessonHint())
        }
    }

    fun onMicReleased() {
        if (_state.value != TutorTurnState.Listening) return
        scope.launch {
            _state.value = TutorTurnState.Transcribing
            val result = asr.stopCapture()
            handleChildUtterance(result.transcript, result.confidence)
        }
    }

    suspend fun onTextSubmitted(text: String) {
        if (turnActive || text.isBlank()) return
        handleChildUtterance(text.trim(), confidence = 1.0f)
    }

    suspend fun endSession() {
        llm.unload()
        currentUnit = null
        _state.value = TutorTurnState.Idle
    }

    private suspend fun handleChildUtterance(utterance: String, confidence: Float) {
        turnActive = true
        try {
            _transcript.value += TranscriptEntry(Speaker.CHILD, utterance, confidence)

            when (val move = policy.nextMove(TurnContext(utterance, confidence, currentUnit))) {
                is TutorMove.AskRepeat -> {
                    speak(move.prompt)
                    _state.value = TutorTurnState.AwaitingChild(move.prompt)
                }

                is TutorMove.RespondViaLlm -> {
                    _state.value = TutorTurnState.Thinking("")
                    var reply = ""
                    llm.generate(buildRequest(utterance, move.instruction)).collect { event ->
                        when (event) {
                            is LlmEvent.Token -> {
                                reply += event.text
                                _state.value = TutorTurnState.Thinking(reply)
                            }

                            is LlmEvent.Done -> reply = event.fullText
                        }
                    }
                    _transcript.value += TranscriptEntry(Speaker.TUTOR, reply)
                    speak(reply)
                    profile.update { it.copy(xp = it.xp + XP_PER_TURN) }
                    _state.value = TutorTurnState.AwaitingChild(null)
                }
            }
        } catch (e: Exception) {
            _state.value = TutorTurnState.Failed(e.message ?: "turn failed")
        } finally {
            turnActive = false
        }
    }

    private suspend fun speak(text: String) {
        _state.value = TutorTurnState.Speaking(text)
        tts.speak(text, TutorLanguage.ENGLISH).collect { }
    }

    private fun buildRequest(utterance: String, instruction: String) = LlmRequest(
        systemPrompt = SYSTEM_PROMPT,
        messages = listOf(
            ChatMessage(Role.SYSTEM, instruction),
            ChatMessage(Role.USER, utterance),
        ),
        maxTokens = 96,
    )

    private fun lessonHint(): RecognitionHint {
        val unit = currentUnit ?: return RecognitionHint.None
        val phrases = buildList {
            unit.activities.forEach { activity ->
                when (activity) {
                    is Activity.Vocab -> add(activity.word)
                    is Activity.RepeatAfterMe -> add(activity.phrase)
                    is Activity.QuestionAnswer -> addAll(activity.expectedAnswers)
                }
            }
        }
        return if (phrases.isEmpty()) RecognitionHint.None else RecognitionHint.ConstrainedVocab(phrases)
    }

    companion object {
        const val XP_PER_TURN = 5

        // P1 safety posture: register, brevity, and topic bounds live in the
        // system prompt; an output filter runs downstream (docs/architecture.md).
        val SYSTEM_PROMPT = """
            You are Tuki, a warm, patient English tutor for a young Hebrew-speaking child.
            Use very short sentences and simple words the child already knows.
            Praise effort. Correct mistakes by repeating the sentence correctly, never by
            saying "wrong". Ask exactly one short question per turn. Stay on the lesson
            topic. Never discuss unsafe, scary, or grown-up subjects.
        """.trimIndent()
    }
}
