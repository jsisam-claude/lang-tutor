package org.sisam.langtutor.tutor.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.sisam.langtutor.llm.ChatMessage
import org.sisam.langtutor.llm.LlmEngine
import org.sisam.langtutor.llm.LlmEvent
import org.sisam.langtutor.llm.LlmModelSpec
import org.sisam.langtutor.llm.LlmRequest
import org.sisam.langtutor.llm.Role
import org.sisam.langtutor.safety.BlocklistSafetyFilter
import org.sisam.langtutor.speech.HebrewText
import org.sisam.langtutor.safety.SafetyFilter
import org.sisam.langtutor.tutor.ReplyBudget
import org.sisam.langtutor.speech.SentenceChunker
import org.sisam.langtutor.speech.TtsEngine
import org.sisam.langtutor.speech.TtsEvent
import org.sisam.langtutor.speech.TutorLanguage

/** Who is talking in the room. */
enum class ChatSpeaker { CHILD, TUKI }

/**
 * One line in the room. [hebrew] is the Hebrew MEANING of [text] when the
 * learner asked for it and the model produced something usable — null
 * otherwise, and the bubble simply shows without it.
 */
data class ChatEntry(
    val speaker: ChatSpeaker,
    val text: String,
    val hebrew: String? = null,
    /**
     * True while a meaning row is still COMING for this bubble — the tier can
     * write Hebrew and the reply is mid-stream, so [hebrew] is null only
     * because the model has not finished. The UI needs the difference: a
     * bubble that will never have a meaning row can offer picture icons under
     * its words instead, but showing them for a second and then replacing
     * them when the Hebrew lands is worse than never showing them.
     */
    val meaningPending: Boolean = false,
)

/**
 * "Just chat" — a freeform room: the learner and Tuki, one reply per message.
 *
 * It used to be a three-way room with a second parrot, Kiki, reacting to both
 * the learner and Tuki. That cost two generations and two synthesis passes per
 * learner message — roughly double the wait on a thermally clamped phone — and
 * bought a character the learner never speaks to. One parrot, one reply.
 *
 * The removal also un-broke the KV cache. Each reply used to carry a
 * per-speaker "reply as X" SYSTEM message, which the engine folds into the
 * conversation's system text; alternating it between Tuki and Kiki changed
 * that text every turn, so no conversation could ever be reused and every
 * reply re-prefilled the whole history. With one speaker the instruction is
 * constant and lives in [ROOM_PROMPT], and the history we send —
 * [sentHistory], the raw exchanges exactly as the engine recorded them — is a
 * plain growing suffix, which is exactly the shape `ConvoReuse` will reuse.
 *
 * Freeform does NOT mean unfiltered: every SENTENCE passes the same safety
 * filter as lesson replies before it is shown or spoken, and a blocked reply
 * drops the engine's cached context exactly like the lesson path does.
 *
 * Pure JVM; speech comes through the [TtsEngine] interface so the app can
 * hand in whichever voice the parent picked.
 */
class ChatRoom(
    private val llm: LlmEngine,
    private val safety: SafetyFilter = BlocklistSafetyFilter(),
    /**
     * The room's voice. A real engine streams: the first sentence is audible
     * while the rest of the reply is still decoding (see [respondStreaming]).
     */
    private val tts: TtsEngine = SilentVoice,
    /**
     * Whether to ask for a Hebrew translation of each reply.
     *
     * Read per turn so the Parent Zone switch takes effect without leaving
     * the room. It costs no extra generation — the translation rides along in
     * the same reply behind [HEBREW_MARKER] — but it does cost a few tokens,
     * so a learner who does not want it does not pay for it.
     */
    private val wantsHebrew: () -> Boolean = { false },
    /**
     * Thermal headroom forecast (1.0 = throttling threshold; NaN unknown),
     * read per turn. A throttled phone gets shorter replies — the one lever
     * that shortens decode and synthesis together ([ReplyBudget]).
     */
    private val thermalHeadroom: () -> Float = { Float.NaN },
) {

    private val _messages = MutableStateFlow<List<ChatEntry>>(emptyList())
    val messages: StateFlow<List<ChatEntry>> = _messages

    /** Set while Tuki is generating — drives the "typing…" bubble. */
    private val _typing = MutableStateFlow<ChatSpeaker?>(null)
    val typing: StateFlow<ChatSpeaker?> = _typing

    /** Set while Tuki is audibly speaking — drives the avatar animation. */
    private val _speaking = MutableStateFlow<ChatSpeaker?>(null)
    val speaking: StateFlow<ChatSpeaker?> = _speaking

    /**
     * True from the child's message landing until the reply has finished
     * PLAYING. Observable because the UI must gate input on it: [send] drops
     * input while busy, and `typing` alone ends too early — it clears when
     * generation ends, well before the audio does, so a message sent during
     * Tuki's speech was silently lost after the input field already cleared.
     */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    suspend fun start() {
        sentHistory = emptyList()
        llm.load(LlmModelSpec(modelId = "chat"))
    }

    /**
     * Leave the room.
     *
     * The engine itself is NOT unloaded. Every room gets the SAME container-owned
     * engine, and a GPU load costs ~27 s on a Pixel 9 — 23 s of it compiling
     * OpenCL kernels — so dropping it on the way out of one room made the walk
     * back in cost half a minute. Releasing it is the container's job, on the
     * two signals that actually mean "done": the app going to background past
     * its grace period, and a system trim once the process is cached.
     */
    fun shutdown() = Unit

    suspend fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || _busy.value) return
        _busy.value = true
        try {
            _messages.value += ChatEntry(ChatSpeaker.CHILD, clean)
            respondStreaming(clean)
        } finally {
            _busy.value = false
            _typing.value = null
            _speaking.value = null
        }
    }

    /** What one turn produced: the spoken line, and its meaning if asked for. */
    internal data class Reply(val english: String, val hebrew: String?)

    /** Thrown to stop LLM collection the moment the reply must end early. */
    private class StopStreaming : CancellationException("stream stopped early")

    /**
     * Streamed reply, the lesson room's shape: tokens → sentence chunks →
     * per-sentence safety → the voice, first sentence alone. The room used to
     * collect the WHOLE generation before showing or saying anything, which
     * put the entire decode (many seconds throttled) between the learner's
     * message and any response; now the bubble grows sentence by sentence and
     * the first sentence is audible while the rest is still decoding.
     *
     * The `HE:` translation trails the reply by construction — the model
     * writes it last — so the English streams out first and the Hebrew row
     * attaches to the finished bubble when it arrives and survives the vet.
     * Everything before the marker is the spoken half; the marker itself and
     * anything after it never reach the voice or the visible text.
     *
     * Safety moves WITH the audio, exactly like the lesson room: a sentence
     * passes the filter before it is queued or shown, and a blocked sentence
     * stops generation, cuts the audio, swaps the whole bubble for the
     * scripted fallback and drops the poisoned engine context.
     */
    private suspend fun respondStreaming(userText: String) {
        val hebrewWanted = runCatching { wantsHebrew() }.getOrDefault(false)
        val request = buildRequest(userText, hebrewWanted)
        _typing.value = ChatSpeaker.TUKI
        var raw = ""
        var sentUpTo = 0
        var blocked = false
        var failed = false
        var bubbleAt = -1
        val sentences = Channel<String>(Channel.UNLIMITED)

        // The English half of what has arrived so far, and whether it is
        // complete. The marker freezes it: once `HE:` appears, no more English
        // is coming and the tail sentence no longer needs to wait for
        // stability.
        fun english(): Pair<String, Boolean> {
            val at = raw.indexOf(HEBREW_MARKER)
            return if (at >= 0) raw.substring(0, at) to true else raw to false
        }

        fun showUpTo(chars: Int) {
            val text = sanitize(raw.take(chars))
            if (text.isEmpty()) return
            val list = _messages.value
            if (bubbleAt < 0) {
                bubbleAt = list.size
                // meaningPending while the reply streams and this tier writes
                // Hebrew: the meaning row is coming, so the UI must not fill
                // the gap with something it will have to take away.
                _messages.value = list + ChatEntry(ChatSpeaker.TUKI, text, meaningPending = hebrewWanted)
                _typing.value = null
            } else {
                _messages.value = list.toMutableList().also {
                    it[bubbleAt] = it[bubbleAt].copy(text = text)
                }
            }
        }

        suspend fun flushCompleteSentences(finalFlush: Boolean) {
            val (visible, markerSeen) = english()
            for (c in SentenceChunker.split(visible)) {
                if (c.start < sentUpTo) continue
                // The buffer's tail chunk waits for more tokens — the next
                // token can dissolve its boundary — unless nothing more can
                // arrive for the English half.
                if (c.end >= visible.length && !(finalFlush || markerSeen)) break
                // The model sometimes signs its lines; the voice and the
                // bubble both get the undressed text.
                val heard = if (c.start == 0) sanitize(c.text) else c.text
                if (heard.isNotEmpty()) {
                    if (!safety.check(heard).allowed) {
                        blocked = true
                        return
                    }
                    sentences.send(heard)
                }
                sentUpTo = c.end
                showUpTo(sentUpTo)
            }
        }

        coroutineScope {
            val speaking = launch {
                try {
                    tts.speakStream(sentences.consumeAsFlow(), TutorLanguage.ENGLISH).collect { event ->
                        if (event is TtsEvent.Started) _speaking.value = ChatSpeaker.TUKI
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A voice failure must not kill the room — the words are
                    // already on screen, which is the part that cannot be lost.
                } finally {
                    _speaking.value = null
                }
            }
            try {
                try {
                    llm.generate(request).collect { event ->
                        when (event) {
                            is LlmEvent.Token -> {
                                raw += event.text
                                flushCompleteSentences(finalFlush = false)
                                if (blocked) throw StopStreaming()
                            }
                            // Adopt the engine's full text only when it
                            // preserves what was already sent to the voice.
                            is LlmEvent.Done ->
                                if (event.fullText.startsWith(raw.take(sentUpTo))) raw = event.fullText
                        }
                    }
                    if (!blocked) flushCompleteSentences(finalFlush = true)
                } catch (_: StopStreaming) {
                    // generation cancelled mid-reply; handled below
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failed generation must not kill the room — the parrot
                    // just says something safe and the conversation moves on.
                    failed = true
                }
                sentences.close()

                val shown = sanitize(english().first).trim()
                // Belt-and-braces parity with the lesson room: every spoken
                // character already passed the per-sentence gate; a failure on
                // the whole is a filter or chunker bug, but still cleaned up.
                if (!blocked && !failed && shown.isNotEmpty() && !safety.check(shown).allowed) {
                    blocked = true
                }

                if (blocked || failed || shown.isEmpty()) {
                    speaking.cancel()
                    runCatching { tts.stop() }
                    runCatching { speaking.join() }
                    // The cached conversation holds what the model actually
                    // said; the ledger records what the child was given.
                    llm.invalidateContext()
                    val list = _messages.value
                    val entry = ChatEntry(ChatSpeaker.TUKI, FALLBACK)
                    _messages.value = if (bubbleAt < 0) list + entry else {
                        list.toMutableList().also { it[bubbleAt] = entry }
                    }
                    recordExchange(userText, FALLBACK)
                    voice(FALLBACK)
                } else {
                    // Final dress: the full vetted English, plus the meaning
                    // row if it was asked for and survives the gauntlet.
                    val hebrew = if (hebrewWanted) vetHebrew(split(raw).hebrew) else null
                    val list = _messages.value
                    _messages.value = list.toMutableList().also {
                        // The wait is over either way: the row arrived, or
                        // the model never wrote one and never will.
                        it[bubbleAt] = it[bubbleAt].copy(
                            text = shown, hebrew = hebrew, meaningPending = false,
                        )
                    }
                    // The ledger keeps the RAW reply — translation line and
                    // all — because that is what the engine's conversation
                    // recorded, and the next request must repeat it verbatim
                    // for the KV cache to survive (see the lesson room's
                    // sentHistory for the full story).
                    recordExchange(userText, raw)
                    speaking.join()
                }
            } catch (e: Exception) {
                sentences.close()
                speaking.cancel()
                runCatching { tts.stop() }
                throw e
            }
        }
    }

    /**
     * Pull the translation off the end of the reply.
     *
     * One generation, not two. Splitting a marker off a single reply costs a
     * handful of tokens; a second call would cost a whole second decode per
     * turn, which is the thing this room just stopped doing when the second
     * parrot was removed.
     */
    private fun split(raw: String): Reply {
        val at = raw.indexOf(HEBREW_MARKER)
        if (at < 0) return Reply(raw, null)
        return Reply(
            english = raw.substring(0, at).trim(),
            // Only the first line after the marker: the model sometimes keeps
            // talking, and a paragraph of commentary is not a translation.
            hebrew = raw.substring(at + HEBREW_MARKER.length).trim().lineSequence()
                .firstOrNull { it.isNotBlank() }?.trim(),
        )
    }

    /**
     * The gauntlet for the Hebrew half.
     *
     * Model Hebrew is the weakest thing this app asks for — our own eval put
     * Gemma below the quality gate overall, passing only on translation
     * specifically (translate-scaffold 4.60 against a 4.0 bar), which is why
     * this one category is unlocked and the rest are not. Even so a wrong or
     * empty line must degrade to NO line, never to a confident mistranslation:
     * a learner cannot check this row, so it shows only when it is at least
     * structurally sound.
     */
    private fun vetHebrew(candidate: String?): String? {
        var t = candidate?.trim() ?: return null
        // The model dresses translations up. Measured on the shipping E4B:
        // every reply came back as `התרגום לעברית הוא: **...**` — a preamble
        // plus markdown emphasis, neither of which is part of the sentence and
        // both of which would render literally in a bubble.
        PREAMBLES.forEach { p -> if (t.startsWith(p)) t = t.removePrefix(p).trim() }
        t = t.removeSurrounding("**").trim()
        t = t.trim('"', '\u201c', '\u201d', '*').trim()
        if (t.isEmpty() || t.length > MAX_HEBREW_CHARS) return null
        // It must actually be Hebrew, and mostly Hebrew — a reply that leaked
        // English into the translation slot is a failed translation.
        if (!HebrewText.contains(t)) return null
        val letters = t.count { it.isLetter() }
        val hebrewLetters = t.count { it in '\u0590'..'\u05ff' }
        if (letters == 0 || hebrewLetters * 2 < letters) return null
        if (!safety.check(t).allowed) return null
        return t
    }

    /** Batch speech for the scripted fallback — short, likely cached. */
    private suspend fun voice(text: String) {
        _speaking.value = ChatSpeaker.TUKI
        try {
            runCatching { tts.speak(text, TutorLanguage.ENGLISH).collect { } }
        } finally {
            _speaking.value = null
        }
    }

    /** The prompt names him, so he sometimes signs his lines. */
    private fun sanitize(raw: String): String {
        var t = raw.trim()
        for (name in listOf("Tuki:", "TUKI:")) {
            t = t.removePrefix(name).trim()
        }
        return t
    }

    /**
     * What the MODEL has processed, exactly as sent and received. The bubbles
     * cannot serve as request history: the engine records the RAW reply — the
     * `HE:` translation line, the "Tuki:" tic — while the bubble shows the
     * undressed English, and `ConvoReuse` demands the request window repeat
     * the recorded text verbatim or the whole conversation is re-prefilled.
     * With the translation on, that mismatch made EVERY turn rebuild. Same
     * fix, same reasons as the lesson room's `sentHistory`.
     */
    private var sentHistory: List<ChatMessage> = emptyList()

    private fun recordExchange(userText: String, replyText: String) {
        if (replyText.isBlank()) return
        sentHistory = (
            sentHistory +
                ChatMessage(Role.USER, userText) +
                ChatMessage(Role.ASSISTANT, replyText)
            ).takeLast(HISTORY_ENTRIES)
    }

    private fun buildRequest(userText: String, hebrewWanted: Boolean): LlmRequest {
        // Only the SPOKEN half shrinks with heat: the translation is silent,
        // so it costs decode but no synthesis, and clipping it would break the
        // one row the learner cannot check halfway through.
        val replyTokens = ReplyBudget.scaled(
            REPLY_TOKENS,
            runCatching { thermalHeadroom() }.getOrDefault(Float.NaN),
        )
        return LlmRequest(
            // Appended, not swapped: the whole point of one stable prompt is
            // that the conversation can be reused, and this text only changes
            // when the learner changes the setting.
            systemPrompt = if (hebrewWanted) ROOM_PROMPT + "\n\n" + HEBREW_INSTRUCTION else ROOM_PROMPT,
            messages = sentHistory.takeLast(HISTORY_ENTRIES) + ChatMessage(Role.USER, userText),
            maxTokens = if (hebrewWanted) replyTokens + HEBREW_TOKENS else replyTokens,
        )
    }

    companion object {
        const val FALLBACK = "Let's talk about something fun instead!"

        /** One stable system prompt, reused for the life of the room. The
         *  "open with a few words" line is a latency lever, not a style note:
         *  the first sentence is the unit that gates audio, and a three-word
         *  opener is on the speaker seconds before a long one would be. */
        val ROOM_PROMPT = """
            You are Tuki, a friendly parrot chatting in English with a
            Hebrew-speaking English learner. You are warm, encouraging and
            curious. Open each reply with a very short phrase of a few words,
            like "Oh, fun!". Reply to the learner's last message in one or two
            short, simple sentences, and usually end with a question that
            keeps the chat going. Never discuss unsafe, scary, or grown-up
            subjects.
        """.trimIndent()

        /** What the model writes before the translation. ASCII, so it cannot
         *  collide with either language's text. */
        const val HEBREW_MARKER = "HE:"

        val HEBREW_INSTRUCTION = """
            After your reply, on a new line, write exactly "HE:" followed by a
            natural Hebrew translation of what you just said. Translate the
            meaning, not word by word. Write nothing after that line.
        """.trimIndent()

        const val REPLY_TOKENS = 64

        /** Headroom for the translation so it cannot truncate the reply. */
        const val HEBREW_TOKENS = 64

        /** A translation of a two-sentence reply; anything longer is the model
         *  having started a new thought rather than translating. */
        const val MAX_HEBREW_CHARS = 300

        /** How the model announces a translation instead of just giving one. */
        val PREAMBLES = listOf(
            "\u05d4\u05ea\u05e8\u05d2\u05d5\u05dd \u05dc\u05e2\u05d1\u05e8\u05d9\u05ea \u05d4\u05d5\u05d0:", // "the Hebrew translation is:"
            "\u05d4\u05ea\u05e8\u05d2\u05d5\u05dd \u05d4\u05d5\u05d0:",                     // "the translation is:"
            "\u05ea\u05e8\u05d2\u05d5\u05dd:",                                // "translation:"
            "Translation:",
        )

        const val HISTORY_ENTRIES = 18

        /** The default voice: emits the lifecycle and says nothing, so a room
         *  built without speech still runs full turns (tests, previews). */
        private object SilentVoice : TtsEngine {
            override fun speak(text: String, language: TutorLanguage, speed: Float) =
                flowOf(TtsEvent.Started, TtsEvent.Completed)

            override suspend fun stop() = Unit
        }
    }
}
