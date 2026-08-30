package org.sisam.langtutor.tutor.drill

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.sisam.langtutor.profile.LearnerProfileStore
import org.sisam.langtutor.speech.AsrEngine
import org.sisam.langtutor.speech.AsrResult
import org.sisam.langtutor.speech.PronunciationScore
import org.sisam.langtutor.speech.PronunciationScorer
import org.sisam.langtutor.speech.RecognitionHint
import org.sisam.langtutor.speech.TtsEngine
import org.sisam.langtutor.speech.TutorLanguage

/** Where the drill is, carried whole so the UI never assembles it from parts. */
sealed interface DrillState {
    data object Idle : DrillState

    /** Everything that happens DURING an item shares these fields. */
    sealed interface Active : DrillState {
        val item: DrillItem
        val index: Int
        val total: Int
    }

    /** Tuki is saying the line (or an encouragement) — mic is closed. */
    data class Prompting(override val item: DrillItem, override val index: Int, override val total: Int) : Active

    data class AwaitingChild(
        override val item: DrillItem,
        override val index: Int,
        override val total: Int,
        val triesUsed: Int,
    ) : Active

    data class Listening(override val item: DrillItem, override val index: Int, override val total: Int) : Active

    data class Judging(override val item: DrillItem, override val index: Int, override val total: Int) : Active

    /** [round] distinguishes consecutive rounds with identical scores, so the
     *  UI's one-shot celebration keys on something that actually changes. */
    data class RoundDone(val correct: Int, val total: Int, val round: Int) : DrillState
}

/** One-shot happenings the UI turns into celebrations. */
sealed interface DrillEvent {
    data class Correct(val tries: Int) : DrillEvent

    /** Out of tries — advanced with encouragement, not a pass. */
    data object Nearly : DrillEvent

    /** Blank transcript — the attempt did not count. */
    data object TooQuiet : DrillEvent
}

/**
 * "Repeat after me": Tuki says a line, the learner says it back, a correct
 * repetition celebrates and advances. The whole room runs on TTS + ASR + the
 * pronunciation coach — there is NO language model anywhere in it, which is
 * the point: it is the room that is instant on every device, cold start
 * included, whatever tier the LLM policy picked or failed to pick.
 *
 * The retry ladder is the pedagogy: a miss gets the line again SLOWER (the
 * slow-clear speed the TTS contract reserves for exactly this), and the third
 * miss advances with encouragement — never a dead end, because a drill a
 * child cannot escape from teaches them to avoid the room, not the word. A
 * silent attempt costs nothing: "I didn't hear you" is not a judgement.
 *
 * Correctness is [WordMatch] over the transcript. The per-sound colours from
 * the coach are SHOWN alongside but never gate progress — they are calibrated
 * on synthesized speech, and failing a child for having a child's voice would
 * be worse than no coach at all.
 */
class DrillOrchestrator(
    private val asr: AsrEngine,
    private val tts: TtsEngine,
    private val scorer: PronunciationScorer,
    private val profile: LearnerProfileStore,
    private val scope: CoroutineScope,
    /**
     * The voice for PERSONALITY lines — praise and encouragement — which the
     * app points at the parrot-flavored view of the same engine. Defaults to
     * the teaching voice, and the split is deliberate and strict: the lines a
     * child copies ([prompt]) or follows as instructions always use [tts]
     * untouched, because a character voice on the model sentence would teach
     * worse English. The character lives only where nobody is learning
     * phonetics from it.
     */
    flavorTts: TtsEngine? = null,
) {

    private val personality: TtsEngine = flavorTts ?: tts

    private val _state = MutableStateFlow<DrillState>(DrillState.Idle)
    val state: StateFlow<DrillState> = _state

    private val _events = MutableSharedFlow<DrillEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<DrillEvent> = _events

    private val _pronunciation = MutableStateFlow<PronunciationScore?>(null)

    /** Per-sound colours for the LAST attempt; cleared when a new one starts. */
    val pronunciation: StateFlow<PronunciationScore?> = _pronunciation

    private val _lastMissedWords = MutableStateFlow<Set<Int>>(emptySet())

    /**
     * Which words of the CURRENT item the last judged attempt missed
     * ([WordMatch.missedWordIndexes]) — the UI marks them on the target line
     * so a retry has somewhere to aim. Kept through the retry (that is when
     * it helps) and cleared when the item changes.
     */
    val lastMissedWords: StateFlow<Set<Int>> = _lastMissedWords

    private var items: List<DrillItem> = emptyList()
    private var index = 0
    private var tries = 0
    private var correct = 0
    private var rounds = 0
    private var turnActive = false

    suspend fun startRound(round: List<DrillItem>) {
        if (round.isEmpty()) return
        items = round
        index = 0
        tries = 0
        correct = 0
        rounds++
        _pronunciation.value = null
        _lastMissedWords.value = emptySet()
        speak(INTRO)
        prompt()
    }

    private suspend fun prompt(slow: Boolean = false, prefix: String? = null) {
        val item = items[index]
        _state.value = DrillState.Prompting(item, index, items.size)
        prefix?.let { speak(it) }
        speak(item.text, speed = if (slow) SLOW_SPEED else 1f)
        _state.value = DrillState.AwaitingChild(item, index, items.size, tries)
    }

    /** Watches [AsrEngine.speculative] during a capture; cancelled with it. */
    private var earlyClose: Job? = null

    fun onMicPressed() {
        val current = _state.value
        if (turnActive || current !is DrillState.AwaitingChild) return
        scope.launch {
            _pronunciation.value = null
            _state.value = DrillState.Listening(current.item, current.index, current.total)
            asr.startCapture(RecognitionHint.ConstrainedVocab(listOf(current.item.text)))
            // EARLY CLOSE (docs/latency.md): this room KNOWS the expected
            // answer, so the moment a speculative transcript already matches
            // it, the turn is over — no waiting out the VAD hangover, no
            // waiting for the finger to lift. The engine only surfaces
            // guesses that cover everything said, and stopCapture() adopts
            // the same speculation, so the judged text is the matched text.
            earlyClose?.cancel()
            earlyClose = scope.launch {
                asr.speculative.collect { guess ->
                    if (WordMatch.matches(current.item.text, guess)) finishAttempt()
                }
            }
        }
    }

    fun onMicReleased() = finishAttempt()

    /**
     * End the capture and judge — from the button lifting or from an early
     * close, whichever comes first. Both paths run on [scope]'s dispatcher,
     * so the state check makes the second arrival a no-op.
     */
    private fun finishAttempt() {
        val current = _state.value
        if (current !is DrillState.Listening) return
        _state.value = DrillState.Judging(current.item, current.index, current.total)
        scope.launch {
            earlyClose?.cancel()
            earlyClose = null
            val result = asr.stopCapture()
            handleAttempt(current, result)
        }
    }

    private suspend fun handleAttempt(at: DrillState.Active, result: AsrResult) {
        turnActive = true
        try {
            if (result.transcript.isBlank()) {
                // Not an error and not a try: silence judged as failure would
                // punish a shy first attempt.
                _events.emit(DrillEvent.TooQuiet)
                speak(DIDNT_HEAR)
                _state.value = DrillState.AwaitingChild(at.item, at.index, at.total, tries)
                return
            }
            scorePronunciation(result, at.item.text)
            _lastMissedWords.value = WordMatch.missedWordIndexes(at.item.text, result.transcript)
            if (WordMatch.matches(at.item.text, result.transcript)) {
                correct++
                _events.emit(DrillEvent.Correct(tries + 1))
                profile.update { it.copy(xp = it.xp + XP_PER_CORRECT) }
                cheer(PRAISES[at.index % PRAISES.size])
                advance()
            } else {
                tries++
                if (tries >= MAX_TRIES) {
                    _events.emit(DrillEvent.Nearly)
                    cheer(GOOD_TRY)
                    advance()
                } else {
                    prompt(slow = true, prefix = ALMOST)
                }
            }
        } catch (e: Exception) {
            // Same doctrine as the tutor: log to logcat via System.out (pure
            // JVM module) and recover to a state the child can act from.
            println("DrillOrchestrator: attempt failed: ${e.javaClass.simpleName}: ${e.message}")
            _state.value = DrillState.AwaitingChild(at.item, at.index, at.total, tries)
        } finally {
            turnActive = false
        }
    }

    private suspend fun advance() {
        index++
        tries = 0
        _lastMissedWords.value = emptySet()
        if (index >= items.size) {
            _state.value = DrillState.RoundDone(correct, items.size, rounds)
        } else {
            prompt()
        }
    }

    private suspend fun scorePronunciation(result: AsrResult, target: String) {
        val clip = result.audio ?: return
        runCatching { scorer.score(clip, target, TutorLanguage.ENGLISH) }
            .onSuccess { if (it.phonemes.isNotEmpty()) _pronunciation.value = it }
            .onFailure { println("DrillOrchestrator: pronunciation scoring failed: ${it.message}") }
    }

    private suspend fun speak(text: String, speed: Float = 1f) {
        tts.speak(text, TutorLanguage.ENGLISH, speed).collect { }
    }

    /** Personality lines only — see the [personality] doc for the rule. */
    private suspend fun cheer(text: String) {
        personality.speak(text, TutorLanguage.ENGLISH, 1f).collect { }
    }

    /** Non-suspend release for ViewModel.onCleared() — same shape as the
     *  tutor's: the owning scope is already cancelled by then. */
    fun shutdown() {
        _state.value = DrillState.Idle
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { tts.stop() }
            runCatching { personality.stop() }
            // A capture the learner walked out on must not hold the mic.
            runCatching { asr.stopCapture() }
        }
    }

    companion object {
        /** Same rate as a conversation turn — a said sentence is a turn. */
        const val XP_PER_CORRECT = 5
        const val MAX_TRIES = 3

        /** The TTS contract's slow-clear mode. */
        const val SLOW_SPEED = 0.75f

        const val INTRO = "Repeat after me!"
        const val ALMOST = "Almost! Listen again."
        const val DIDNT_HEAR = "I didn't hear you. Try again!"
        const val GOOD_TRY = "Good try! Let's do the next one."
        val PRAISES = listOf("Great job!", "Well done!", "You said it!", "Perfect!")
    }
}
