package org.sisam.langtutor.tutor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.sisam.langtutor.content.Activity
import org.sisam.langtutor.content.AgeBand
import org.sisam.langtutor.content.ContentRepository
import org.sisam.langtutor.content.CurriculumUnit
import org.sisam.langtutor.llm.ChatMessage
import org.sisam.langtutor.llm.LlmEngine
import org.sisam.langtutor.llm.LlmEvent
import org.sisam.langtutor.llm.LlmModelSpec
import org.sisam.langtutor.llm.LlmRequest
import org.sisam.langtutor.llm.Role
import org.sisam.langtutor.profile.LearnerProfileStore
import org.sisam.langtutor.profile.LearnerTrack
import org.sisam.langtutor.safety.BlocklistSafetyFilter
import org.sisam.langtutor.safety.SafetyFilter
import org.sisam.langtutor.speech.AsrEngine
import org.sisam.langtutor.speech.HebrewText
import org.sisam.langtutor.speech.AudioClip
import org.sisam.langtutor.speech.PronunciationScore
import org.sisam.langtutor.speech.PronunciationScorer
import org.sisam.langtutor.speech.RecognitionHint
import org.sisam.langtutor.speech.SentenceChunker
import org.sisam.langtutor.speech.TtsEngine
import org.sisam.langtutor.speech.TtsEvent
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
    /**
     * Whether the LOADED model is good enough to explain in Hebrew. Evaluated
     * after [startSession] loads the engine, because the tier is only decided
     * then. E2B failed the Hebrew eval gate (4.03, meta-AI flag) where E4B
     * passed at 4.45 — shipping E2B's Hebrew would ship what the eval
     * rejected, so the button simply does not appear on that tier.
     */
    private val tierSpeaksHebrew: () -> Boolean = { false },
    /**
     * Whether a Hebrew VOICE is installed. This is the gate that lets the
     * youngest learners in: written Hebrew is useless to a child who cannot
     * read it, but spoken Hebrew is exactly what they need, and it was only
     * ever withheld because there was no voice to say it with.
     */
    private val canSpeakHebrew: () -> Boolean = { false },
) {

    private val _state = MutableStateFlow<TutorTurnState>(TutorTurnState.Idle)
    val state: StateFlow<TutorTurnState> = _state

    private val _transcript = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcript: StateFlow<List<TranscriptEntry>> = _transcript

    private var currentUnit: CurriculumUnit? = null
    private var turnActive = false

    /** The track's config bundle for this session, read once at session start. */
    private var track: TrackConfig = TrackConfig.of(LearnerTrack.BEGINNER)

    private val _hebrewHelpOffered = MutableStateFlow(false)

    /**
     * Whether to show the "explain in Hebrew" control. Two independent gates:
     * the loaded model must be the tier that passed the Hebrew eval, and the
     * learner's track must be one written Hebrew actually helps — a pre-reader
     * cannot read Hebrew either, so for them the button would be decoration.
     */
    val hebrewHelpOffered: StateFlow<Boolean> = _hebrewHelpOffered

    private val _turnsCompleted = MutableStateFlow(0)

    /**
     * How many practice turns this SESSION has completed — the signal the
     * reward loop celebrates.
     *
     * Deliberately not "watch the profile's XP": the profile is exposed as a
     * plain Flow, so a screen collecting it with a placeholder initial value
     * sees `xp = 0` before it sees the real number, and reads the difference
     * as a fresh gain. This starts at 0 because the session did, and it only
     * ever moves when a turn actually finishes.
     */
    val turnsCompleted: StateFlow<Int> = _turnsCompleted

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
        sentHistory = emptyList()
        track = TrackConfig.of(profile.current().track)
        llm.load(LlmModelSpec(modelId = "tutor-default"))
        currentUnit = content.loadUnit(unitId)
        // Only meaningful AFTER the load: which tier actually came up is what
        // decides whether Hebrew is trustworthy this session.
        //
        // Two ways to qualify. Either the learner READS Hebrew — a track that
        // wants text, and not a 4-6 unit, because the unit's age band knows
        // something the default BEGINNER track does not — or we can SAY it,
        // in which case being unable to read is not a reason to withhold it.
        val readsHebrew = track.hebrewTextUseful && currentUnit?.ageBand != AgeBand.AGES_4_6
        _hebrewHelpOffered.value = tierSpeaksHebrew() && (readsHebrew || canSpeakHebrew())
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
            // For a streamed reply, hushing alone left the mic dead for the
            // rest of the decode (every later tap landed right back here);
            // the flag makes the token loop end the turn at the next event.
            bargeRequested = true
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
        val trimmed = text.trim()
        // A learner who TYPED Hebrew has told us plainly that English is not
        // landing. That is a deterministic signal, unlike guessing confusion
        // from ASR confidence, so it triggers the Hebrew explanation directly.
        val forced = if (_hebrewHelpOffered.value && HebrewText.contains(trimmed)) {
            TutorMove.RespondViaLlm(HEBREW_HELP_INSTRUCTION)
        } else {
            null
        }
        handleChildUtterance(trimmed, confidence = 1.0f, forcedMove = forced)
    }

    /**
     * The learner tapped "explain in Hebrew". One turn-instruction, injected
     * through the same [DialoguePolicy] plumbing every other move uses — not a
     * prompt rewrite, not a second engine, not a mode the session stays in.
     * The next turn is ordinary English again.
     *
     * The tap DOES enter the transcript as a learner turn. An earlier version
     * kept it out, on the theory that a button press is not something the
     * learner said — but the engine sends the LAST message of a request as the
     * user turn, so an empty utterance meant the model was handed Tuki's own
     * previous reply as the child's words (and, on the very first tap, the
     * instruction itself). A visible "I asked for Hebrew" line is both honest
     * and the only shape that keeps the conversation history coherent for
     * every turn after it.
     *
     * It does not count as practice, though — no XP and no coins. Asking for
     * help is not the work, and XP is what drives the sticker milestone.
     */
    suspend fun onHebrewHelpRequested() {
        if (turnActive || !_hebrewHelpOffered.value) return
        val current = _state.value
        if (current !is TutorTurnState.AwaitingChild && current !is TutorTurnState.Failed) return
        handleChildUtterance(
            utterance = HEBREW_HELP_REQUEST,
            confidence = 1.0f,
            forcedMove = TutorMove.RespondViaLlm(HEBREW_HELP_INSTRUCTION),
            countsAsPractice = false,
        )
    }

    suspend fun endSession() {
        currentUnit = null
        sentHistory = emptyList()
        _state.value = TutorTurnState.Idle
    }

    /**
     * Release for ViewModel.onCleared(): drop the session, keep the model.
     *
     * The engine itself is NOT unloaded. Every room gets the SAME container-owned
     * engine, and a GPU load costs ~27 s on a Pixel 9 — 23 s of it compiling
     * OpenCL kernels — so dropping it on the way out of one room made the walk
     * back in cost half a minute. Releasing it is the container's job, on the
     * two signals that actually mean "done": the app going to background past
     * its grace period, and a system trim once the process is cached.
     */
    fun shutdown() {
        currentUnit = null
        sentHistory = emptyList()
        _state.value = TutorTurnState.Idle
    }

    private suspend fun handleChildUtterance(
        utterance: String,
        confidence: Float,
        audio: AudioClip? = null,
        forcedMove: TutorMove? = null,
        countsAsPractice: Boolean = true,
    ) {
        turnActive = true
        try {
            // A silent turn (blank decode) must not leave an empty child bubble
            // in the transcript — it would also ship to the LLM as an empty
            // USER message in every later request's history.
            if (utterance.isNotBlank()) {
                _transcript.value += TranscriptEntry(Speaker.CHILD, utterance, confidence)
            }

            val chosen = forcedMove ?: policy.nextMove(TurnContext(utterance, confidence, currentUnit))
            when (val move = chosen) {
                is TutorMove.AskRepeat -> {
                    // The policy just refused to trust this transcript (low
                    // confidence / blank) — scoring pronunciation against a
                    // phrase matched from an UNTRUSTED transcript painted red
                    // marks for sounds the child may never have said.
                    speak(move.prompt)
                    _state.value = TutorTurnState.AwaitingChild(move.prompt)
                }

                is TutorMove.RespondViaLlm -> {
                    scorePronunciation(audio, utterance)
                    _state.value = TutorTurnState.Thinking("")
                    respondStreaming(buildRequest(utterance, move.instruction))
                    if (countsAsPractice) {
                        profile.update { it.copy(xp = it.xp + XP_PER_TURN) }
                        _turnsCompleted.value += 1
                    }
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

    /** Set by a mic tap during [TutorTurnState.Speaking]; the streaming token
     *  loop converts it into a prompt end of turn instead of decoding on. */
    @Volatile private var bargeRequested = false

    /**
     * Where the streamed reply is cut, in characters, for the CURRENT turn.
     *
     * This used to be the safety filter's flat 400-char cap, which silently
     * undid every budget above ~96 tokens: an Exam-track reply (128) or a
     * bilingual Hebrew explanation (160) runs past 400 characters as a matter
     * of course, so the tail was dropped at a sentence boundary and the
     * English half of "Hebrew first, then continue in English" never arrived.
     * Derived from the turn's own token budget instead, and never tighter than
     * the filter's cap.
     */
    private var replyCharBudget = BlocklistSafetyFilter.MAX_REPLY_CHARS

    /** Why a streamed reply stopped before its natural end. */
    private enum class StopReason {
        /** A sentence (or the whole reply) failed the safety filter. */
        BLOCKED,

        /** The child tapped the mic — hush and give the turn back NOW. */
        BARGED,

        /** Clean but endless: cut at a sentence boundary, keep the audio. */
        TRUNCATED,
    }

    /** Thrown to stop LLM collection the moment the reply must end early. */
    private class StopStreaming : kotlinx.coroutines.CancellationException("stream stopped early")

    /**
     * Streamed reply: Tuki starts SPEAKING at the first sentence boundary while
     * the model is still decoding the rest. On a CPU-decode phone the old
     * collect-everything-then-speak path put the model's whole decode time
     * (many seconds for a 96-token reply) between the child finishing and any
     * audio; streaming removes all of it except the first sentence's decode.
     *
     * Safety moves WITH the audio: each sentence passes the filter BEFORE it is
     * queued for synthesis, because a filter that runs after the reply was
     * already heard protects nobody. A blocked sentence stops generation, cuts
     * any audio mid-word, swaps in the scripted fallback and drops the
     * poisoned engine context. A reply that merely runs past the length cap is
     * NOT a safety event: it is cut at a sentence boundary, the audio already
     * playing finishes, and the transcript records exactly what was heard.
     */
    private suspend fun respondStreaming(request: LlmRequest) {
        bargeRequested = false
        // What the engine will record as this turn's user message — the ledger
        // must repeat it verbatim or the next turn cannot reuse the KV cache.
        val sentUserText = request.messages.last().text
        var reply = ""
        var stop: StopReason? = null
        var sentUpTo = 0
        var sentAny = false
        var ttsStarted = false
        val sentences = Channel<String>(Channel.UNLIMITED)
        // A bare launch would hand a TTS failure to the scope's (absent)
        // exception handler and kill the process; catching it here and
        // rethrowing after join() routes it into handleChildUtterance's
        // catch → Failed state, exactly like the pre-streaming speak() did.
        var ttsError: Exception? = null
        val speaking = scope.launch {
            try {
                tts.speakStream(sentences.consumeAsFlow(), TutorLanguage.ENGLISH).collect { event ->
                    // Real audio signal: engines with no incremental path (the
                    // interface-default speakStream, i.e. platform TTS) emit
                    // Started only when playback actually begins — after the
                    // decode — so "Speaking" is never shown over silence.
                    if (event is TtsEvent.Started) {
                        ttsStarted = true
                        // Promote immediately instead of waiting for the next
                        // token: audio is audible NOW, and barge-in keys on
                        // the Speaking state.
                        val current = _state.value
                        if (sentAny && current is TutorTurnState.Thinking) {
                            _state.value = TutorTurnState.Speaking(current.partialReply)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ttsError = e
            }
        }

        suspend fun flushCompleteSentences(finalFlush: Boolean) {
            for (c in SentenceChunker.split(reply)) {
                if (c.start < sentUpTo) continue
                // Only a chunk with text AFTER it is stable: the chunker also
                // marks an ender at the very end of the buffer as a boundary,
                // but the next token can dissolve it ("You have 3" + "." +
                // "5 apples!"), and a dissolved boundary shifts offsets so the
                // merged sentence would be skipped forever. The buffer's tail
                // chunk therefore waits for more tokens (or the final flush).
                if (c.end >= reply.length && !finalFlush) break
                val verdict = safety.check(c.text)
                if (!verdict.allowed) {
                    stop = if (verdict.reason == BlocklistSafetyFilter.REASON_TOO_LONG) {
                        StopReason.TRUNCATED
                    } else {
                        StopReason.BLOCKED
                    }
                    break
                }
                sentences.send(c.text)
                sentUpTo = c.end
                sentAny = true
                if (sentUpTo >= replyCharBudget) {
                    // Spoken enough — a child should never sit through a
                    // monologue. Later sentences are dropped, this one plays.
                    stop = StopReason.TRUNCATED
                    break
                }
            }
        }

        try {
            try {
                llm.generate(request).collect { event ->
                    when (event) {
                        is LlmEvent.Token -> {
                            reply += event.text
                            if (bargeRequested) {
                                stop = StopReason.BARGED
                                throw StopStreaming()
                            }
                            flushCompleteSentences(finalFlush = false)
                            if (stop != null) throw StopStreaming()
                            // Once audio is rolling the child-visible truth is
                            // "Tuki is talking", and the barge-in branch in
                            // onMicPressed keys on Speaking — staying in
                            // Thinking made the mic tap dead for the whole
                            // decode while sentences were audibly playing.
                            _state.value = if (sentAny && ttsStarted) {
                                TutorTurnState.Speaking(reply)
                            } else {
                                TutorTurnState.Thinking(reply)
                            }
                        }
                        // Engines are expected to build fullText from the same
                        // deltas; adopt it only when it PRESERVES what was
                        // already sent to the voice — an engine that trims or
                        // rewrites would shift chunk offsets under sentUpTo and
                        // re-speak or skip text at the final flush.
                        is LlmEvent.Done ->
                            if (event.fullText.startsWith(reply.take(sentUpTo))) reply = event.fullText
                    }
                }
                if (stop == null && bargeRequested) stop = StopReason.BARGED
                if (stop == null) flushCompleteSentences(finalFlush = true)
            } catch (_: StopStreaming) {
                // generation cancelled mid-reply; handled below
            }

            // Belt-and-braces: every spoken character already passed the
            // per-sentence gate, and a multi-word blocklist term cannot
            // straddle a sentence boundary (terms join words with literal
            // spaces; sentences are split on ender+space). The one rule with
            // genuinely whole-reply scope — the length cap — is enforced at a
            // sentence boundary by the flush loop above. So a content failure
            // HERE means a filter or chunker bug: the audio has played, but
            // the transcript and the engine context still get cleaned up.
            if (stop == null) {
                val whole = safety.check(reply)
                if (!whole.allowed && whole.reason != BlocklistSafetyFilter.REASON_TOO_LONG) {
                    stop = StopReason.BLOCKED
                }
            }
            // A truncation before ANYTHING was spoken (a single endless
            // sentence) must still say something — take the fallback path.
            if (stop == StopReason.TRUNCATED && sentUpTo == 0) stop = StopReason.BLOCKED

            when (stop) {
                StopReason.BLOCKED -> {
                    sentences.close()
                    speaking.cancel()
                    // stop() flips the player's interrupted flag so a synthesis
                    // already in flight discards its audio; join() then waits
                    // it out. Without the join, speak(fallback) resets that
                    // flag and the stale sentence of the REJECTED reply plays
                    // over the fallback (shared player, non-cancellable synth).
                    runCatching { tts.stop() }
                    runCatching { speaking.join() }
                    // The engine's cached conversation holds what the MODEL
                    // said — the rejected text — and must not condition later
                    // turns.
                    llm.invalidateContext()
                    reply = SAFE_FALLBACK_REPLY
                    _transcript.value += TranscriptEntry(Speaker.TUTOR, reply)
                    // Ledger gets what the child HEARD, not what the model
                    // said: the engine context is dirty and the next turn
                    // rebuilds from this history, which must not resurrect a
                    // rejected reply.
                    recordExchange(sentUserText, reply)
                    speak(reply)
                }

                StopReason.BARGED -> {
                    sentences.close()
                    speaking.cancel()
                    runCatching { tts.stop() }
                    runCatching { speaking.join() }
                    // The cache holds the model's fuller reply; the child heard
                    // a prefix. Drop it so later turns build on what was heard.
                    llm.invalidateContext()
                    val heard = reply.take(sentUpTo).trim()
                    if (heard.isNotEmpty()) {
                        _transcript.value += TranscriptEntry(Speaker.TUTOR, heard)
                    }
                    // An empty prefix skips the whole exchange — for history
                    // purposes the turn never happened (recordExchange drops
                    // blank replies).
                    recordExchange(sentUserText, heard)
                }

                StopReason.TRUNCATED -> {
                    sentences.close()
                    llm.invalidateContext()
                    val spokenText = reply.take(sentUpTo).trim()
                    _transcript.value += TranscriptEntry(Speaker.TUTOR, spokenText)
                    recordExchange(sentUserText, spokenText)
                    _state.value = TutorTurnState.Speaking(spokenText)
                    speaking.join()
                    ttsError?.let { throw it }
                }

                null -> {
                    sentences.close()
                    _transcript.value += TranscriptEntry(Speaker.TUTOR, reply)
                    // The clean path: reply text identical to what the engine
                    // recorded, so the live conversation is reusable next turn.
                    recordExchange(sentUserText, reply)
                    _state.value = TutorTurnState.Speaking(reply)
                    speaking.join()
                    ttsError?.let { throw it }
                }
            }
        } catch (e: Exception) {
            sentences.close()
            speaking.cancel()
            runCatching { tts.stop() }
            runCatching { speaking.join() }
            throw e
        }
    }

    /**
     * Score the attempt when the lesson asked the child to say a SPECIFIC
     * phrase — that's the only case with a known correct pronunciation to
     * compare against. Free conversation is never marked. Failures here must
     * never break the turn: feedback is a bonus, the conversation is the point.
     */
    private suspend fun scorePronunciation(audio: AudioClip?, utterance: String) {
        val clip = audio ?: return
        // Score against the lesson phrase the child ACTUALLY attempted (best
        // transcript match, TargetPicker) — not the unit's first phrase, which
        // marked children red for sounds they never said.
        val target = TargetPicker.pick(utterance, currentUnit) ?: return
        runCatching { scorer.score(clip, target, TutorLanguage.ENGLISH) }
            .onSuccess { if (it.phonemes.isNotEmpty()) _pronunciation.value = it }
            .onFailure { println("TutorOrchestrator: pronunciation scoring failed: ${it.message}") }
    }

    private suspend fun speak(text: String) {
        _state.value = TutorTurnState.Speaking(text)
        tts.speak(text, TutorLanguage.ENGLISH).collect { }
    }

    /**
     * What the MODEL has actually processed, exactly as sent — the request
     * history the engine's KV-reuse check (`ConvoReuse`) can prove is a
     * continuation.
     *
     * The transcript cannot serve here, and using it was this room's KV leak:
     * the per-turn guidance went out as a leading SYSTEM message, the engine
     * folds those into the conversation's system text, and `ConvoReuse`
     * requires that text to be identical — so any change of guidance (a
     * Hebrew-help tap, and the turn after it) re-prefilled the entire
     * conversation. The same defect was removed from the chat room with the
     * second parrot (docs/latency.md).
     *
     * Now the guidance rides INSIDE each user turn ([guideWrap]) and the
     * system text never changes. The engine records the wrapped text, so the
     * next request must repeat it verbatim — which the raw transcript cannot.
     * This ledger holds the wrapped user turns and the replies as recorded
     * (the CUT text on a truncated or barged turn, the fallback on a blocked
     * one). Scripted turns never enter it, so an AskRepeat between LLM turns
     * no longer forces a rebuild either — the transcript-based window used to
     * gain entries the conversation had never seen.
     */
    private var sentHistory: List<ChatMessage> = emptyList()

    /** One clean exchange for the ledger, trimmed so it cannot grow without
     *  bound; the request window is smaller still. */
    private fun recordExchange(userText: String, replyText: String) {
        if (replyText.isBlank()) return
        sentHistory = (
            sentHistory +
                ChatMessage(Role.USER, userText) +
                ChatMessage(Role.ASSISTANT, replyText)
            ).takeLast(LEDGER_ENTRIES)
    }

    /**
     * Conversation memory: the request carries the last [HISTORY_TURNS]
     * ledger entries plus the new turn, so Tuki remembers names, topics, and
     * its own questions across turns. Short kid turns keep this well inside
     * the model's 4k context, and because the window is always a suffix of
     * [sentHistory], the engine reuses its live conversation and prefills ONE
     * message instead of the whole history.
     */
    private fun buildRequest(utterance: String, instruction: String): LlmRequest {
        // Whole-reply cut, in step with the token budget — see [replyCharBudget].
        replyCharBudget = (replyTokensFor(instruction) * EST_CHARS_PER_TOKEN)
            .coerceAtLeast(BlocklistSafetyFilter.MAX_REPLY_CHARS)
        return LlmRequest(
            systemPrompt = SYSTEM_PROMPT + "\n" + track.personaSuffix,
            messages = sentHistory.takeLast(HISTORY_TURNS) +
                ChatMessage(Role.USER, guideWrap(instruction, utterance)),
            // Reply budget from the track, floored to the age band: a 4-6 unit
            // gets one short sentence and a question whichever track is set —
            // half the tokens is half the decode time AND better pedagogy
            // (pre-readers lose the thread in long replies). Turn time scales
            // almost linearly with this number, so a Hebrew explanation, which
            // genuinely needs two clauses in two scripts, gets its own budget.
            maxTokens = replyTokensFor(instruction),
        )
    }

    private fun replyTokensFor(instruction: String): Int = when {
        instruction == HEBREW_HELP_INSTRUCTION -> HEBREW_REPLY_TOKENS
        currentUnit?.ageBand == AgeBand.AGES_4_6 -> minOf(track.replyTokens, YOUNG_REPLY_TOKENS)
        else -> track.replyTokens
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

        /** Ledger cap: comfortably more than the request window ever reads. */
        const val LEDGER_ENTRIES = HISTORY_TURNS * 3

        const val SAFE_FALLBACK_REPLY = "Let's get back to our lesson! Can you say the word again?"

        /**
         * The per-turn guidance, carried INSIDE the user turn.
         *
         * Not a SYSTEM message: the engine folds leading SYSTEM messages into
         * the conversation's system text, and a system text that changes with
         * the guidance forces a full re-prefill of the conversation every time
         * the move changes (see [sentHistory]). Bracketed so the model reads
         * it as stage direction rather than the child speaking; on a rebuild
         * the same wrapped lines appear in history, an honest record of what
         * each turn was asked to do.
         */
        fun guideWrap(instruction: String, utterance: String): String =
            if (instruction.isBlank()) utterance
            else "[Lesson guide: $instruction]\n$utterance"

        /**
         * The whole Hebrew feature, in one line. Deliberately asks for Hebrew
         * FIRST and English after, so the learner reads the thing they got
         * stuck on before being handed more English, and deliberately says
         * "briefly" — an unbounded bilingual answer is two monologues.
         */
        const val HEBREW_HELP_INSTRUCTION =
            "The learner needs help in Hebrew. Explain your last point briefly in " +
                "written Hebrew (two short sentences at most), then continue in English."

        /** A bilingual turn carries two scripts; the ordinary budget clips it. */
        const val HEBREW_REPLY_TOKENS = 160

        /**
         * What the learner's "explain in Hebrew" tap puts in the transcript.
         * It is the button's own label, so the conversation reads back the way
         * it happened. Not a resource string: this module is pure JVM, and the
         * phrase is Hebrew in both app locales anyway.
         */
        const val HEBREW_HELP_REQUEST = "הסבר בעברית"

        /** Age-band floor on the reply budget, whatever the track asks for. */
        const val YOUNG_REPLY_TOKENS = 48

        /** Rough Gemma ratio, used only to size the whole-reply cut. */
        const val EST_CHARS_PER_TOKEN = 4


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
