package org.sisam.langtutor.tutor.picture

import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.sisam.langtutor.profile.LearnerProfileStore
import org.sisam.langtutor.speech.TtsEngine
import org.sisam.langtutor.speech.TutorLanguage

/** One picture card: the word, its authored Hebrew, and its art. */
data class PictureCard(
    val word: String,
    val hebrew: String?,
    /** Emoji (or future art id) keyed on the WORD — see the asset note in
     *  docs/picture-vocabulary.md. */
    val emoji: String,
)

sealed interface PictureState {
    data object Idle : PictureState

    /** Cards are being introduced one by one; [index] is on stage. */
    data class Teaching(val cards: List<PictureCard>, val index: Int) : PictureState

    /** "Where is the X?" — [targetIndex] into [cards]; wrong taps accumulate
     *  so the UI can dim them, and a retry is always allowed. */
    data class Asking(
        val cards: List<PictureCard>,
        val targetIndex: Int,
        val wrongTaps: Set<Int>,
        val asked: Int,
        val total: Int,
    ) : PictureState

    data class Done(val firstTry: Int, val total: Int) : PictureState
}

sealed interface PictureEvent {
    data class Correct(val firstTry: Boolean) : PictureEvent
    data object Wrong : PictureEvent
}

/**
 * The picture vocabulary room (docs/picture-vocabulary.md): teach-then-check,
 * the oldest and best-evidenced shape in vocabulary teaching, and the one
 * thing the app did not have — everything else asks the learner to PRODUCE
 * English; this asks them to RECOGNISE it, which comes first.
 *
 * Present three to five cards: the art appears, Tuki says the word, tapping
 * the card repeats it — unlimited, free, never scored. Then check: Tuki asks
 * "Where is the X?" and the learner taps among the cards just taught. The
 * check is AUDIO-driven recognition, deliberately: no reading of any script
 * is required, so it works for a learner with no English production at all.
 *
 * No language model anywhere in it: authored words, synthesized speech, and
 * the small closed set is exactly what SynthCache makes instant. A wrong tap
 * gets a warm retry, never a dead end; first-try answers are what the score
 * counts, but every question ends in the child finding the right card.
 */
class PictureVocabOrchestrator(
    private val tts: TtsEngine,
    private val profile: LearnerProfileStore,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<PictureState>(PictureState.Idle)
    val state: StateFlow<PictureState> = _state

    private val _events = MutableSharedFlow<PictureEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<PictureEvent> = _events

    private var order: List<Int> = emptyList()
    private var firstTry = 0
    private var busy = false

    suspend fun startRound(cards: List<PictureCard>, random: Random = Random.Default) {
        if (cards.isEmpty()) return
        order = cards.indices.shuffled(random)
        firstTry = 0
        _state.value = PictureState.Teaching(cards, 0)
        speak(cards[0].word)
    }

    /** Tapping the card on stage repeats its word — free, never scored. */
    fun onCardTapped(index: Int) {
        val s = _state.value
        val word = when (s) {
            is PictureState.Teaching -> s.cards.getOrNull(index)?.word
            is PictureState.Asking -> null // the check answers through onAnswerPicked
            else -> null
        } ?: return
        scope.launch { speak(word) }
    }

    /** Advance the presentation; after the last card the check begins. */
    fun onNext() {
        val s = _state.value as? PictureState.Teaching ?: return
        scope.launch {
            if (s.index + 1 < s.cards.size) {
                _state.value = PictureState.Teaching(s.cards, s.index + 1)
                speak(s.cards[s.index + 1].word)
            } else {
                ask(s.cards, asked = 0)
            }
        }
    }

    fun onAnswerPicked(index: Int) {
        val s = _state.value as? PictureState.Asking ?: return
        if (busy) return
        scope.launch {
            busy = true
            try {
                if (index == s.targetIndex) {
                    val clean = s.wrongTaps.isEmpty()
                    if (clean) firstTry++
                    _events.emit(PictureEvent.Correct(firstTry = clean))
                    profile.update { it.copy(xp = it.xp + XP_PER_CORRECT) }
                    speak(PRAISES[s.asked % PRAISES.size])
                    if (s.asked + 1 < s.total) {
                        ask(s.cards, s.asked + 1)
                    } else {
                        _state.value = PictureState.Done(firstTry, s.total)
                    }
                } else {
                    _events.emit(PictureEvent.Wrong)
                    _state.value = s.copy(wrongTaps = s.wrongTaps + index)
                    // Warm retry: repeat the question word, never "wrong".
                    speak(TRY_AGAIN)
                    speak(question(s.cards[s.targetIndex].word))
                }
            } finally {
                busy = false
            }
        }
    }

    private suspend fun ask(cards: List<PictureCard>, asked: Int) {
        val target = order[asked]
        _state.value = PictureState.Asking(cards, target, emptySet(), asked, cards.size)
        speak(question(cards[target].word))
    }

    fun shutdown() {
        _state.value = PictureState.Idle
        scope.launch { runCatching { tts.stop() } }
    }

    private suspend fun speak(text: String) {
        runCatching { tts.speak(text, TutorLanguage.ENGLISH).collect { } }
    }

    companion object {
        const val XP_PER_CORRECT = 5
        fun question(word: String) = "Where is the $word?"
        const val TRY_AGAIN = "Almost! Look again."
        val PRAISES = listOf("Yes! You found it!", "That's it!", "Well done!", "Perfect!")
    }
}
