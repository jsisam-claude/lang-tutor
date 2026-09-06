package org.sisam.langtutor.tutor.story

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.sisam.langtutor.content.Story
import org.sisam.langtutor.speech.TtsEngine
import org.sisam.langtutor.speech.TutorLanguage

sealed interface StoryState {
    data object Idle : StoryState

    /** [page] of [total]; [speaking] moves the beak while the page is read. */
    data class Reading(
        val story: Story,
        val page: Int,
        val speaking: Boolean,
    ) : StoryState {
        val total: Int get() = story.pages.size
        val first: Boolean get() = page == 0
        val last: Boolean get() = page == story.pages.lastIndex
    }

    data class Done(val story: Story) : StoryState
}

/**
 * The story room (docs/short-stories.md): pages, read at the learner's pace.
 *
 * Nothing here is scored and nothing is timed. This is the one room that
 * asks the learner for nothing at all — it exists so that everything the
 * other rooms drill has somewhere to be met in running prose, which is the
 * only place vocabulary ever actually settles.
 *
 * A page is read aloud when the learner arrives at it, and again whenever
 * they tap it. Turning a page while a line is still being read cuts the line
 * rather than queueing behind it: the learner has moved on, and the room
 * should sound like it noticed.
 */
class StoryReader(
    private val tts: TtsEngine,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<StoryState>(StoryState.Idle)
    val state: StateFlow<StoryState> = _state

    /** Bumped on every page turn, so a read that was overtaken knows to stop
     *  writing state that belongs to a page nobody is on any more. */
    private var epoch = 0

    fun open(story: Story) {
        if (story.pages.isEmpty()) return
        epoch++
        _state.value = StoryState.Reading(story, 0, speaking = false)
        readCurrent()
    }

    fun next() {
        val s = _state.value as? StoryState.Reading ?: return
        epoch++
        if (s.last) {
            _state.value = StoryState.Done(s.story)
            stopSpeaking()
        } else {
            _state.value = s.copy(page = s.page + 1, speaking = false)
            readCurrent()
        }
    }

    fun back() {
        val s = _state.value as? StoryState.Reading ?: return
        if (s.first) return
        epoch++
        _state.value = s.copy(page = s.page - 1, speaking = false)
        readCurrent()
    }

    /** Tapping the page reads it again — free, unlimited, never scored. */
    fun readAgain() = readCurrent()

    /** Back to the first page, for a story worth a second time through. */
    fun again() {
        val story = (_state.value as? StoryState.Done)?.story ?: return
        open(story)
    }

    private fun readCurrent() {
        val s = _state.value as? StoryState.Reading ?: return
        val mine = epoch
        val text = s.story.pages[s.page].en
        scope.launch {
            // Cut whatever is still being said: the learner turned the page,
            // and a room that finished the old line first would be talking
            // about something no longer on screen.
            runCatching { tts.stop() }
            if (epoch != mine) return@launch
            setSpeaking(mine, true)
            runCatching { tts.speak(text, TutorLanguage.ENGLISH).collect { } }
            setSpeaking(mine, false)
        }
    }

    private fun setSpeaking(mine: Int, value: Boolean) {
        if (epoch != mine) return
        val s = _state.value as? StoryState.Reading ?: return
        _state.value = s.copy(speaking = value)
    }

    /** Stop talking, keep the page. The composition can leave for a rotation
     *  or a detour and come back to exactly where the reader was. */
    fun silence() {
        epoch++
        val s = _state.value
        if (s is StoryState.Reading) _state.value = s.copy(speaking = false)
        stopSpeaking()
    }

    /** Terminal, for ViewModel.onCleared: the owning scope is already
     *  cancelled by then, so the stop runs on a detached one. */
    fun shutdown() {
        epoch++
        _state.value = StoryState.Idle
        stopSpeaking()
    }

    private fun stopSpeaking() {
        CoroutineScope(Dispatchers.Default).launch { runCatching { tts.stop() } }
    }
}
