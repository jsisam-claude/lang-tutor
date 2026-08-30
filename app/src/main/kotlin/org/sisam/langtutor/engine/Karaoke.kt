package org.sisam.langtutor.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which word Tuki is saying RIGHT NOW — the live end of the karaoke feature.
 *
 * A side-channel StateFlow rather than a new TtsEvent, deliberately: word
 * positions are discovered inside the player's blocking drain loop (a plain
 * thread polling the playback head every 20 ms), where a suspend `emit` has
 * no business, while a StateFlow write is legal from any thread and the UI
 * already knows how to collect one. Rooms that show the spoken text key on
 * [Position.utterance] matching their own line, so a stale position from an
 * earlier utterance can never highlight the wrong screen.
 *
 * App-wide singleton for the same reason [TurnLatency] is: there is exactly
 * one voice sounding at a time, whichever engine or room drives it.
 */
object Karaoke {

    /** [charStart]..[charEnd] (exclusive) of the word being spoken, as
     *  offsets into [utterance] — the exact text handed to the voice. */
    data class Position(val utterance: String, val charStart: Int, val charEnd: Int)

    private val _position = MutableStateFlow<Position?>(null)
    val position: StateFlow<Position?> = _position

    fun set(utterance: String, charStart: Int, charEnd: Int) {
        _position.value = Position(utterance, charStart, charEnd)
    }

    /** The voice went quiet (utterance finished, barged, or stopped). */
    fun clear() {
        _position.value = null
    }
}
