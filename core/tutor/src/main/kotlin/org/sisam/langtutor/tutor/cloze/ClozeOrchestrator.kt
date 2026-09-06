package org.sisam.langtutor.tutor.cloze

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

/** How an item ended. With four options the fourth tap is forced, so an
 *  item found by exhaustion is SHOWN — the gap fills, the line is read,
 *  and nothing is paid for guessing. */
enum class ClozeOutcome { FIRST_TRY, FOUND, SHOWN }

sealed interface ClozeState {
    data object Idle : ClozeState

    /** [item] is on screen with its gap open; [wrongTaps] are option indexes
     *  already tried, so the room can mark and disable them. */
    data class Asking(
        val item: ClozeItem,
        val asked: Int,
        val total: Int,
        val wrongTaps: Set<Int>,
    ) : ClozeState

    /** The gap is filled; [speaking] is true while Tuki reads the whole
     *  line, so the beak can move. */
    data class Revealed(
        val item: ClozeItem,
        val asked: Int,
        val total: Int,
        val outcome: ClozeOutcome,
        val wrongTaps: Set<Int>,
        val speaking: Boolean,
    ) : ClozeState

    data class Done(val firstTry: Int, val found: Int, val total: Int) : ClozeState
}

sealed interface ClozeEvent {
    data class Resolved(val outcome: ClozeOutcome) : ClozeEvent
    data object Wrong : ClozeEvent
    data class RoundDone(val total: Int) : ClozeEvent
}

/**
 * The fill-the-gap room (docs/fill-the-gap.md): the picture room's shape —
 * a tap game with no microphone — over sentences instead of cards.
 *
 * Read the Hebrew, read the English with its gap, tap the word. Nothing
 * that contains the gap is ever spoken before it is filled: the sentence
 * IS the answer. Once it is filled, for whatever reason, Tuki reads the
 * whole line — the completed sentence the learner just built is the reward,
 * and hearing it is the only time the room speaks English at length.
 *
 * A wrong tap is a warm retry, never a dead end, exactly as in the picture
 * room; every item ends with the gap filled. What differs is the ledger:
 * first try earns full XP and a star, a later find earns a little and a
 * flake, and the third wrong tap — which leaves one option standing — earns
 * nothing, because paying for exhaustion would teach tapping, not reading.
 *
 * Speech is serialised the way every room serialises it: one plain `busy`
 * flag, every entry point launched on the injected scope, and a tap during
 * speech dropped rather than queued.
 */
class ClozeOrchestrator(
    private val tts: TtsEngine,
    private val profile: LearnerProfileStore,
    private val scope: CoroutineScope,
    /** Where an answer becomes evidence about a skill (docs/knowledge-tracing.md). */
    private val tracker: SkillTracker = SkillTracker(),
) {

    private val _state = MutableStateFlow<ClozeState>(ClozeState.Idle)
    val state: StateFlow<ClozeState> = _state

    private val _events = MutableSharedFlow<ClozeEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ClozeEvent> = _events

    private var items: List<ClozeItem> = emptyList()
    private var firstTry = 0
    private var found = 0
    private var busy = false

    @Volatile private var silenced = false

    /** An empty round is Done, never Idle: the drill's rule, so a topic with
     *  nothing to serve shows a message rather than a spinner. */
    suspend fun startRound(round: List<ClozeItem>) {
        items = round
        firstTry = 0
        found = 0
        silenced = false
        if (round.isEmpty()) {
            _state.value = ClozeState.Done(0, 0, 0)
            return
        }
        _state.value = ClozeState.Asking(round[0], 0, round.size, emptySet())
        // The options are live from the first frame, so the intro holds the
        // same busy flag a tap would: two lines on one player interleave.
        busy = true
        try {
            speak(INTRO)
        } finally {
            busy = false
        }
    }

    fun onOptionPicked(index: Int) {
        val s = _state.value as? ClozeState.Asking ?: return
        if (busy || index in s.wrongTaps || index !in s.item.options.indices) return
        scope.launch {
            busy = true
            try {
                if (index == s.item.answer) {
                    val outcome = if (s.wrongTaps.isEmpty()) ClozeOutcome.FIRST_TRY else ClozeOutcome.FOUND
                    resolve(s, outcome, s.wrongTaps)
                } else {
                    val wrong = s.wrongTaps + index
                    _events.emit(ClozeEvent.Wrong)
                    if (wrong.size >= s.item.options.size - 1) {
                        // One option left: the answer is no longer a choice.
                        resolve(s, ClozeOutcome.SHOWN, wrong)
                    } else {
                        _state.value = s.copy(wrongTaps = wrong)
                        speak(TRY_AGAIN)
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    private suspend fun resolve(s: ClozeState.Asking, outcome: ClozeOutcome, wrongTaps: Set<Int>) {
        when (outcome) {
            ClozeOutcome.FIRST_TRY -> firstTry++
            ClozeOutcome.FOUND -> found++
            ClozeOutcome.SHOWN -> Unit
        }
        val xp = when (outcome) {
            ClozeOutcome.FIRST_TRY -> XP_FIRST_TRY
            ClozeOutcome.FOUND -> XP_FOUND
            ClozeOutcome.SHOWN -> 0
        }
        // Reading it right first time is the evidence; a word found by
        // elimination, or filled in by the room, is evidence of the other
        // thing. Both are recorded — a room that only counted successes
        // would call every learner an expert.
        val skills = skillsOf(s.item)
        // A failed write must not take the item down with it: the tally is
        // already counted and the learner is owed the line.
        runCatching {
            profile.update {
                tracker.observe(
                    if (xp > 0) it.copy(xp = it.xp + xp) else it,
                    skills,
                    correct = outcome == ClozeOutcome.FIRST_TRY,
                )
            }
        }
        _events.emit(ClozeEvent.Resolved(outcome))
        _state.value = ClozeState.Revealed(s.item, s.asked, s.total, outcome, wrongTaps, speaking = true)
        speak(if (outcome == ClozeOutcome.SHOWN) SHOWN_LINE else PRAISES[s.asked % PRAISES.size])
        speak(s.item.sentence.en)
        // Speech may have been stopped by a navigation; only a room still in
        // this item's Revealed state gets its beak told to close.
        (_state.value as? ClozeState.Revealed)?.let { r -> if (r.item == s.item) _state.value = r.copy(speaking = false) }
    }

    /** Explicit advance: the learner decides when they have finished reading
     *  the completed line. */
    fun onNext() {
        val s = _state.value as? ClozeState.Revealed ?: return
        if (busy) return
        val next = s.asked + 1
        if (next < items.size) {
            _state.value = ClozeState.Asking(items[next], next, items.size, emptySet())
        } else {
            _state.value = ClozeState.Done(firstTry, found, items.size)
            scope.launch { _events.emit(ClozeEvent.RoundDone(items.size)) }
        }
    }

    /** Tapping the completed line repeats it — free, never scored. In Asking
     *  it does nothing: the line contains the answer. */
    fun onSentenceTapped() {
        val s = _state.value as? ClozeState.Revealed ?: return
        if (busy) return
        scope.launch {
            busy = true
            try {
                _state.value = s.copy(speaking = true)
                speak(s.item.sentence.en)
                (_state.value as? ClozeState.Revealed)?.let { r -> if (r.item == s.item) _state.value = r.copy(speaking = false) }
            } finally {
                busy = false
            }
        }
    }

    /**
     * Stop talking; keep the round exactly where it is.
     *
     * A room outlives its composition: a rotation, a sticker detour, or a
     * chip that keys a different room all leave this one retained and
     * silent. Cutting the voice there is right, and going [ClozeState.Idle]
     * there is not — nothing restarts a round for a retained room, so the
     * learner would come back to an empty pane with no control to recover.
     * The flag also drops whatever this turn was about to say next: a
     * resolve interrupted mid-praise must not go on to read the whole line
     * over another room's opening.
     */
    fun silence() {
        silenced = true
        CoroutineScope(Dispatchers.Default).launch { runCatching { tts.stop() } }
    }

    /** Non-suspend release for ViewModel.onCleared(): the owning scope is
     *  already cancelled by then, so the stop runs on a detached one, exactly
     *  as the picture room's does. Terminal, unlike [silence] — the room is
     *  being destroyed, so there is nothing left to come back to. */
    fun shutdown() {
        silenced = true
        _state.value = ClozeState.Idle
        CoroutineScope(Dispatchers.Default).launch { runCatching { tts.stop() } }
    }

    /** A gap is evidence about the line's topic and grammar, and — when the
     *  gap hid a picture word — about that word too. */
    private fun skillsOf(item: ClozeItem): List<String> = buildList {
        addAll(DrillDeck.skillsOf(item.sentence))
        item.packWord?.let { add(Skill.word(it.en)) }
    }

    private suspend fun speak(text: String) {
        if (silenced) return
        runCatching { tts.speak(text, TutorLanguage.ENGLISH).collect { } }
    }

    companion object {
        const val XP_FIRST_TRY = 5
        const val XP_FOUND = 2
        const val INTRO = "Which word is missing?"
        const val SHOWN_LINE = "Here it is."
        const val TRY_AGAIN = PictureVocabOrchestrator.TRY_AGAIN
        val PRAISES: List<String> = PictureVocabOrchestrator.PRAISES
    }
}
