package org.sisam.langtutor.tutor.tense

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.sisam.langtutor.profile.LearnerProfileStore
import org.sisam.langtutor.profile.Skill
import org.sisam.langtutor.profile.SkillTracker
import org.sisam.langtutor.speech.TtsEngine
import org.sisam.langtutor.speech.TutorLanguage
import org.sisam.langtutor.tutor.drill.DrillDeck
import org.sisam.langtutor.tutor.picture.PictureVocabOrchestrator

/** How an item ended. With three destinations the third tap is forced, so an
 *  item found by exhaustion is SHOWN — the sentence is filed, the line is
 *  read, and nothing is paid for running out of options. */
enum class TenseOutcome { FIRST_TRY, FOUND, SHOWN }

sealed interface TenseState {
    data object Idle : TenseState

    /**
     * [item] is on screen and the timeline is open.
     *
     * [stops] is the round's whole destination set, not the set the remaining
     * items happen to use: a timeline that shrank to the stops still to come
     * would hand back the elimination this room's shape was chosen to remove.
     * [placed] accumulates across items — it is the payoff kept from the
     * sorting idea, and it is what the finished round is read across.
     */
    data class Asking(
        val item: TenseItem,
        val stops: List<TenseStop>,
        val asked: Int,
        val total: Int,
        val wrongTaps: Set<TenseStop>,
        val placed: Map<TenseStop, List<String>>,
        val speaking: Boolean,
    ) : TenseState

    /** The sentence is filed; [speaking] is true while Tuki reads the whole
     *  line — with its time word back in — so the beak can move. */
    data class Revealed(
        val item: TenseItem,
        val stops: List<TenseStop>,
        val asked: Int,
        val total: Int,
        val outcome: TenseOutcome,
        val wrongTaps: Set<TenseStop>,
        val placed: Map<TenseStop, List<String>>,
        val speaking: Boolean,
    ) : TenseState

    data class Done(
        val firstTry: Int,
        val found: Int,
        val total: Int,
        val stops: List<TenseStop>,
        val placed: Map<TenseStop, List<String>>,
    ) : TenseState
}

sealed interface TenseEvent {
    data class Resolved(val outcome: TenseOutcome) : TenseEvent
    data object Wrong : TenseEvent
    data class RoundDone(val total: Int) : TenseEvent
}

/**
 * The tense room (docs/tense-room.md): one sentence, three tap destinations
 * laid out as a timeline, and the time word lifted out so the verb is the only
 * cue left.
 *
 * It is the fill-the-gap room's shape — a tap game with no microphone, a warm
 * retry instead of a dead end, the same ledger — over a different question.
 * Two things differ, and both follow from what the room is for.
 *
 * It READS THE SENTENCE ALOUD while asking. The fill-the-gap room cannot,
 * because its line contains the answer; here nothing is hidden from the ear,
 * and hearing the form is the input the room exists to give.
 *
 * A WRONG TAP HANDS BACK THE HIDDEN WORD. When the bank line carried a time
 * word, the deck took it out; giving it back is the explanation and the answer
 * key at once, and it needs no grammar lecture. When there was no time word,
 * the reveal marks the verb instead — [TenseItem.verb] — which is the same
 * lesson pointed at from the other end.
 *
 * Speech is serialised the way every room serialises it: one plain `busy`
 * flag, every entry point launched on the injected scope, and a tap during
 * speech dropped rather than queued.
 */
class TenseOrchestrator(
    private val tts: TtsEngine,
    private val profile: LearnerProfileStore,
    private val scope: CoroutineScope,
    /** Where an answer becomes evidence about a skill (docs/knowledge-tracing.md). */
    private val tracker: SkillTracker = SkillTracker(),
) {

    private val _state = MutableStateFlow<TenseState>(TenseState.Idle)
    val state: StateFlow<TenseState> = _state

    private val _events = MutableSharedFlow<TenseEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<TenseEvent> = _events

    private var items: List<TenseItem> = emptyList()
    private var stops: List<TenseStop> = emptyList()
    private var placed: Map<TenseStop, List<String>> = emptyMap()
    private var firstTry = 0
    private var found = 0
    private var busy = false

    @Volatile private var silenced = false

    /**
     * An empty round is Done, never Idle: the drill's rule, so a topic with
     * nothing to place shows a message rather than a spinner.
     *
     * [openStops] comes from `TenseDeck.stops` and is the timeline the room
     * draws. Fewer than two and there is no question to ask, so that is an
     * empty round too — the caller is expected to have asked `offers()` first,
     * and this is the backstop.
     */
    suspend fun startRound(round: List<TenseItem>, openStops: List<TenseStop>) {
        items = round
        stops = openStops
        placed = emptyMap()
        firstTry = 0
        found = 0
        silenced = false
        if (round.isEmpty() || openStops.size < TenseDeck.MIN_STOPS) {
            _state.value = TenseState.Done(0, 0, 0, openStops, emptyMap())
            return
        }
        _state.value = TenseState.Asking(round[0], openStops, 0, round.size, emptySet(), emptyMap(), speaking = true)
        // The destinations are live from the first frame, so the intro holds
        // the same busy flag a tap would: two lines on one player interleave.
        busy = true
        try {
            speak(INTRO)
            speak(round[0].shown)
        } finally {
            busy = false
            stopSpeaking(round[0])
        }
    }

    fun onStopPicked(stop: TenseStop) {
        val s = _state.value as? TenseState.Asking ?: return
        if (busy || stop in s.wrongTaps || stop !in s.stops) return
        scope.launch {
            busy = true
            try {
                if (stop == s.item.stop) {
                    val outcome = if (s.wrongTaps.isEmpty()) TenseOutcome.FIRST_TRY else TenseOutcome.FOUND
                    resolve(s, outcome, s.wrongTaps)
                } else {
                    val wrong = s.wrongTaps + stop
                    _events.emit(TenseEvent.Wrong)
                    if (wrong.size >= s.stops.size - 1) {
                        // One destination left: the answer is no longer a choice.
                        resolve(s, TenseOutcome.SHOWN, wrong)
                    } else {
                        // The hint is speech like any other, so the beak moves
                        // for it: this is the one line in the room that hands
                        // over the answer key, and a still parrot reads as a
                        // freeze.
                        _state.value = s.copy(wrongTaps = wrong, speaking = true)
                        // The hidden time word IS the hint, so a line that
                        // never carried one falls back to the plain nudge.
                        speak(s.item.hidden?.let { HIDDEN_IS.format(it) } ?: TRY_AGAIN)
                        stopSpeaking(s.item)
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    private suspend fun resolve(s: TenseState.Asking, outcome: TenseOutcome, wrongTaps: Set<TenseStop>) {
        when (outcome) {
            TenseOutcome.FIRST_TRY -> firstTry++
            TenseOutcome.FOUND -> found++
            TenseOutcome.SHOWN -> Unit
        }
        val xp = when (outcome) {
            TenseOutcome.FIRST_TRY -> XP_FIRST_TRY
            TenseOutcome.FOUND -> XP_FOUND
            TenseOutcome.SHOWN -> 0
        }
        // The sentence goes onto its own pile whatever the route: every item
        // ends filed, and the sorted timeline is what the round is for.
        placed = placed + (s.item.stop to (placed[s.item.stop].orEmpty() + s.item.shown))
        val skills = skillsOf(s.item)
        // A failed write must not take the item down with it: the tally is
        // already counted and the learner is owed the line.
        runCatching {
            profile.update {
                tracker.observe(
                    if (xp > 0) it.copy(xp = it.xp + xp) else it,
                    skills,
                    correct = outcome == TenseOutcome.FIRST_TRY,
                )
            }
        }
        _events.emit(TenseEvent.Resolved(outcome))
        _state.value = TenseState.Revealed(s.item, s.stops, s.asked, s.total, outcome, wrongTaps, placed, speaking = true)
        speak(if (outcome == TenseOutcome.SHOWN) SHOWN_LINE else PRAISES[s.asked % PRAISES.size])
        // The bank's own line, time word and all: what the learner was shown
        // was a sentence with a hole in it, and this is the whole of it.
        speak(s.item.restored)
        stopSpeaking(s.item)
    }

    /** Explicit advance: the learner decides when they have finished reading
     *  the filed line. */
    fun onNext() {
        val s = _state.value as? TenseState.Revealed ?: return
        if (busy) return
        val next = s.asked + 1
        if (next < items.size) {
            val item = items[next]
            _state.value = TenseState.Asking(item, stops, next, items.size, emptySet(), placed, speaking = true)
            scope.launch {
                busy = true
                try {
                    speak(item.shown)
                } finally {
                    busy = false
                    stopSpeaking(item)
                }
            }
        } else {
            _state.value = TenseState.Done(firstTry, found, items.size, stops, placed)
            scope.launch { _events.emit(TenseEvent.RoundDone(items.size)) }
        }
    }

    /**
     * Tapping the sentence repeats it — free, never scored.
     *
     * Unlike the fill-the-gap room this works while ASKING as well, and reads
     * `shown`, the line with its time word still out. Hearing the form is the
     * input the room gives; hearing it again is not a hint.
     */
    fun onSentenceTapped() {
        val s = _state.value
        val item = when (s) {
            is TenseState.Asking -> s.item
            is TenseState.Revealed -> s.item
            else -> return
        }
        if (busy) return
        val line = if (s is TenseState.Revealed) item.restored else item.shown
        scope.launch {
            busy = true
            try {
                _state.value = when (s) {
                    is TenseState.Asking -> s.copy(speaking = true)
                    is TenseState.Revealed -> s.copy(speaking = true)
                    else -> return@launch
                }
                speak(line)
            } finally {
                busy = false
                stopSpeaking(item)
            }
        }
    }

    /** Only a room still on this item gets its beak told to close: speech may
     *  have been stopped by a navigation, or the round already advanced. */
    private fun stopSpeaking(item: TenseItem) {
        when (val now = _state.value) {
            is TenseState.Asking -> if (now.item == item) _state.value = now.copy(speaking = false)
            is TenseState.Revealed -> if (now.item == item) _state.value = now.copy(speaking = false)
            else -> Unit
        }
    }

    /**
     * Stop talking; keep the round exactly where it is.
     *
     * A room outlives its composition: a rotation, a sticker detour, or a chip
     * that keys a different room all leave this one retained and silent.
     * Cutting the voice there is right, and going [TenseState.Idle] there is
     * not — nothing restarts a round for a retained room, so the learner would
     * come back to an empty timeline with no control to recover.
     */
    fun silence() {
        silenced = true
        CoroutineScope(Dispatchers.Default).launch { runCatching { tts.stop() } }
    }

    /**
     * Give the voice back to a room that was silenced and is being looked at
     * again.
     *
     * [silence] is called on every disposal — a rotation, a sticker detour, a
     * chip that keys another room — and it used to be cleared only by
     * [startRound]. A retained room re-entered mid-round therefore stayed mute
     * for the rest of it, which in this room means it stops reading the
     * sentence aloud: the one thing it exists to do.
     */
    fun resume() {
        silenced = false
    }

    /** Non-suspend release for ViewModel.onCleared(): the owning scope is
     *  already cancelled by then, so the stop runs on a detached one. Terminal,
     *  unlike [silence] — the room is being destroyed. */
    fun shutdown() {
        silenced = true
        _state.value = TenseState.Idle
        CoroutineScope(Dispatchers.Default).launch { runCatching { tts.stop() } }
    }

    /**
     * Placing a sentence is evidence about its topic, its grammar pattern and
     * — uniquely to this room — its TENSE, which is the thing being asked
     * about. The tense id is the room's whole point, so it is written here
     * rather than waiting for the other rooms to learn to report it.
     */
    private fun skillsOf(item: TenseItem): List<String> = buildList {
        addAll(DrillDeck.skillsOf(item.sentence))
        if (item.sentence.tense.isNotBlank()) add(Skill.tense(item.sentence.tense))
    }

    private suspend fun speak(text: String) {
        if (silenced) return
        runCatching { tts.speak(text, TutorLanguage.ENGLISH).collect { } }
    }

    companion object {
        const val XP_FIRST_TRY = 5
        const val XP_FOUND = 2
        const val INTRO = "When did it happen?"
        const val SHOWN_LINE = "Here it is."

        /** The word the deck took out, handed back on a wrong tap. */
        const val HIDDEN_IS = "We hid a word. It was: %s."
        const val TRY_AGAIN = PictureVocabOrchestrator.TRY_AGAIN
        val PRAISES: List<String> = PictureVocabOrchestrator.PRAISES
    }
}
