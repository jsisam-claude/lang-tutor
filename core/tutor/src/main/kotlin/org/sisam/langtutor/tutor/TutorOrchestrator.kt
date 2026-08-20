package org.sisam.langtutor.tutor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import org.sisam.langtutor.safety.BlocklistSafetyFilter
import org.sisam.langtutor.safety.SafetyFilter
import org.sisam.langtutor.speech.AsrEngine
import org.sisam.langtutor.speech.AudioClip
import org.sisam.langtutor.speech.PronunciationScore
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
    private val scorer: PronunciationScorer,
    private val content: ContentRepository,
    private val profile: LearnerProfileStore,
    private val policy: DialoguePolicy,
    private val scope: CoroutineScope,
    private val safety: SafetyFilter = BlocklistSafetyFilter(),
) {

    private val _state = MutableStateFlow<TutorTurnState>(TutorTurnState.Idle)
    val state: StateFlow<TutorTurnState> = _state

    private val _transcript = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcript: StateFlow<List<TranscriptEntry>> = _transcript

    private var currentUnit: CurriculumUnit? = null
    private var turnActive = false

    private val _pronunciation = MutableStateFlow<PronunciationScore?>(null)

    /**
     * Per-sound feedback for the last spoken attempt at a lesson phrase, or
     * null when the turn wasn't a scorable attempt. Cleared when a new turn
     * starts so the UI never shows stale marks.
     */
    val pronunciation: StateFlow<PronunciationScore?> = _pronunciation

    /**
     * Hands-free listening: the mic opens and the bundled VAD decides when the
     * child stopped talking. Off by default — only offered when the ASR engine
     * actually supports it ([AsrEngine.supportsHandsFree]).
     */
    var handsFree: Boolean = false
        set(value) {
            field = value && asr.supportsHandsFree
        }

    val handsFreeAvailable: Boolean get() = asr.supportsHandsFree

    suspend fun startSession(unitId: String, @Suppress("UNUSED_PARAMETER") mode: TutorMode) {
        // Model load can be slow on first run; hold Preparing so the UI shows a
        // waiting state and input stays gated (a turn may only start from
        // AwaitingChild) — this prevents generate()-before-load() on the real engine.
        _state.value = TutorTurnState.Preparing
        llm.load(LlmModelSpec(modelId = "tutor-default"))
        currentUnit = content.loadUnit(unitId)
        val firstPrompt = currentUnit?.activities
            ?.filterIsInstance<Activity.RepeatAfterMe>()
            ?.firstOrNull()?.phrase
        _state.value = TutorTurnState.AwaitingChild(firstPrompt)
    }

    fun onMicPressed() {
        // Barge-in: tapping the mic while Tuki is talking hushes the voice —
        // a child should never have to wait out a long reply. The interrupted
        // speak() completes immediately, the turn ends in AwaitingChild, and
        // the next press starts listening as usual.
        if (_state.value is TutorTurnState.Speaking) {
            scope.launch { tts.stop() }
            return
        }
        if (turnActive) return
        // A turn may begin when the tutor awaits the child, or after a failed
        // turn (so Failed isn't a dead end — the child can just try again).
        // Still blocked: Preparing (model loading) and Idle (no session).
        val current = _state.value
        if (current !is TutorTurnState.AwaitingChild && current !is TutorTurnState.Failed) return
        scope.launch {
            _pronunciation.value = null
            _state.value = TutorTurnState.Listening
            asr.startCapture(lessonHint())
            if (handsFree) {
                // The engine's VAD ends the turn on its own — the child just
                // talks and stops. A cancelled/superseded turn is covered by
                // the state check inside finishListening().
                asr.awaitEndpoint()
                finishListening()
            }
        }
    }

    fun onMicReleased() {
        // In hands-free mode the endpoint detector owns the end of the turn;
        // a stray release must not cut the child off mid-word.
        if (handsFree) return
        if (_state.value != TutorTurnState.Listening) return
        scope.launch { finishListening() }
    }

    private suspend fun finishListening() {
        if (_state.value != TutorTurnState.Listening) return
        _state.value = TutorTurnState.Transcribing
        val result = asr.stopCapture()
        handleChildUtterance(result.transcript, result.confidence, result.audio)
    }

    suspend fun onTextSubmitted(text: String) {
        // Block while the model is still loading (Preparing) — same reason as the mic.
        if (turnActive || text.isBlank() || _state.value is TutorTurnState.Preparing) return
        handleChildUtterance(text.trim(), confidence = 1.0f)
    }

    suspend fun endSession() {
        llm.unload()
        currentUnit = null
        _state.value = TutorTurnState.Idle
    }

    /**
     * Non-suspend release for ViewModel.onCleared(): by the time onCleared runs
     * the owning [scope] is already cancelled, so the multi-GB engine unload
     * happens on an independent one-shot scope. Without this the model was
     * NEVER unloaded — engines accumulated across screen exits (worst on 8 GB
     * devices) and the documented load/unload thermal budget was fiction.
     */
    fun shutdown() {
        currentUnit = null
        _state.value = TutorTurnState.Idle
        CoroutineScope(Dispatchers.Default).launch { llm.unload() }
    }

    private suspend fun handleChildUtterance(
        utterance: String,
        confidence: Float,
        audio: AudioClip? = null,
    ) {
        turnActive = true
        try {
            _transcript.value += TranscriptEntry(Speaker.CHILD, utterance, confidence)
            scorePronunciation(audio)

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
                    // Output filter: a blocked reply is replaced, never shown.
                    if (!safety.check(reply).allowed) {
                        reply = SAFE_FALLBACK_REPLY
                        // The engine may cache the conversation across turns, and
                        // that cache holds the text the MODEL generated — the
                        // rejected one. Swapping it here only fixes what the child
                        // sees; without this the blocked reply keeps conditioning
                        // every later turn of the session.
                        llm.invalidateContext()
                    }
                    _transcript.value += TranscriptEntry(Speaker.TUTOR, reply)
                    speak(reply)
                    profile.update { it.copy(xp = it.xp + XP_PER_TURN) }
                    _state.value = TutorTurnState.AwaitingChild(null)
                }
            }
        } catch (e: Exception) {
            // println lands in logcat (System.out) — this module is pure JVM and
            // has no android.util.Log; a silent Failed state made device
            // debugging needlessly blind.
            println("TutorOrchestrator: turn failed: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            _state.value = TutorTurnState.Failed("${e.javaClass.simpleName}: ${e.message ?: "turn failed"}")
        } finally {
            turnActive = false
        }
    }

    /**
     * Score the attempt when the lesson asked the child to say a SPECIFIC
     * phrase — that's the only case with a known correct pronunciation to
     * compare against. Free conversation is never marked. Failures here must
     * never break the turn: feedback is a bonus, the conversation is the point.
     */
    private suspend fun scorePronunciation(audio: AudioClip?) {
        val clip = audio ?: return
        val target = currentUnit?.activities
            ?.filterIsInstance<Activity.RepeatAfterMe>()
            ?.firstOrNull()?.phrase ?: return
        runCatching { scorer.score(clip, target, TutorLanguage.ENGLISH) }
            .onSuccess { if (it.phonemes.isNotEmpty()) _pronunciation.value = it }
            .onFailure { println("TutorOrchestrator: pronunciation scoring failed: ${it.message}") }
    }

    private suspend fun speak(text: String) {
        _state.value = TutorTurnState.Speaking(text)
        tts.speak(text, TutorLanguage.ENGLISH).collect { }
    }

    /**
     * Conversation memory: the request carries the last [HISTORY_TURNS]
     * transcript entries (the current child utterance is already the newest),
     * so Tuki remembers names, topics, and its own questions across turns —
     * previously each turn was sent in isolation and the tutor had amnesia.
     * Short kid turns keep this well inside the model's 4k context.
     */
    private fun buildRequest(@Suppress("UNUSED_PARAMETER") utterance: String, instruction: String): LlmRequest {
        val history = _transcript.value.takeLast(HISTORY_TURNS).map { entry ->
            ChatMessage(
                role = if (entry.speaker == Speaker.CHILD) Role.USER else Role.ASSISTANT,
                text = entry.text,
            )
        }
        return LlmRequest(
            systemPrompt = SYSTEM_PROMPT,
            messages = listOf(ChatMessage(Role.SYSTEM, instruction)) + history,
            maxTokens = 96,
        )
    }

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
        const val HISTORY_TURNS = 10
        const val SAFE_FALLBACK_REPLY = "Let's get back to our lesson! Can you say the word again?"

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
