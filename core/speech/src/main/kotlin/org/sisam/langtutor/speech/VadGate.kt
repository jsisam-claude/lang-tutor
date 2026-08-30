package org.sisam.langtutor.speech

/**
 * Endpointing state machine for hands-free listening: turns a stream of
 * per-frame speech probabilities (from the bundled Silero VAD) into "the child
 * started talking" / "the child finished" events, so a 5-year-old never has to
 * hold a button.
 *
 * Hysteresis + hangover, because raw per-frame probability dips inside normal
 * speech — stop consonants, breaths and the gaps between words all read as
 * silence for a few frames. Measured on real model output (see the golden
 * test): speech frames average ~0.70 with dips near 0, silence stays under
 * 0.25. So we open on a confident frame, close only after [hangoverMs] of
 * continuous quiet, and ignore blips shorter than [minSpeechMs].
 *
 * Pure JVM and frame-clocked (no wall clock) so it is exactly reproducible in
 * tests; the caller feeds frames of [Config.windowSamples] at [Config.sampleRate].
 */
class VadGate(private val config: Config = Config()) {

    data class Config(
        val sampleRate: Int = 16_000,
        /** Silero works on fixed 512-sample frames at 16 kHz (32 ms); the
         *  bundled v6 export also wants 64 context samples prepended — see
         *  SileroVad. */
        val windowSamples: Int = 512,
        /** Open the gate at/above this probability. */
        val startThreshold: Float = 0.5f,
        /** Only below this does a frame count as quiet (hysteresis band). */
        val endThreshold: Float = 0.35f,
        /**
         * Continuous quiet that ends an utterance. Calibrated for LOW-
         * PROFICIENCY L2 speakers, whose within-utterance hesitations run
         * 600–1,500 ms — do not shrink this for snappiness; that reversal is
         * recorded in docs/latency.md. Responsiveness comes from
         * [softHangoverMs] instead.
         */
        val hangoverMs: Int = 700,
        /**
         * Quiet after which a TENTATIVE endpoint fires ([Event.SpeechSoftEnd])
         * while listening continues. The caller may start transcribing
         * speculatively; if speech resumes before [hangoverMs] closes the
         * turn, the speculation is simply wasted work — nothing was cut, and
         * the learner never notices. Aggressive on purpose: a false soft
         * endpoint costs CPU, never words.
         */
        val softHangoverMs: Int = 250,
        /** Shorter bursts are coughs/knocks, not turns. */
        val minSpeechMs: Int = 150,
        /** Safety stop so a noisy room can't record forever. */
        val maxUtteranceMs: Int = 15_000,
        /** Quiet this long with no speech at all ends the turn (child said nothing). */
        val noSpeechTimeoutMs: Int = 10_000,
    ) {
        val frameMs: Int get() = windowSamples * 1000 / sampleRate
    }

    sealed interface Event {
        /** First confident speech frame — UI can show "I'm listening". */
        data object SpeechStart : Event

        /**
         * The child has been quiet for [Config.softHangoverMs] — probably
         * done, not certainly. Fired at most once per quiet stretch, only for
         * speech long enough to be a turn, and it ends nothing: the gate
         * keeps listening and a resumed word re-arms it for the next stretch.
         */
        data class SpeechSoftEnd(val startFrame: Int, val endFrame: Int) : Event

        /** Utterance finished; [startFrame]/[endFrame] bound the speech. */
        data class SpeechEnd(val startFrame: Int, val endFrame: Int, val reason: EndReason) : Event
    }

    enum class EndReason { SILENCE, MAX_LENGTH, NO_SPEECH }

    private var frame = 0
    private var speaking = false
    private var startFrame = 0
    private var quietFrames = 0
    private var finished = false
    private var softFired = false

    private val hangoverFrames get() = (config.hangoverMs / config.frameMs).coerceAtLeast(1)
    private val softHangoverFrames get() = (config.softHangoverMs / config.frameMs).coerceAtLeast(1)
    private val minSpeechFrames get() = (config.minSpeechMs / config.frameMs).coerceAtLeast(1)
    private val maxFrames get() = config.maxUtteranceMs / config.frameMs
    private val noSpeechFrames get() = config.noSpeechTimeoutMs / config.frameMs

    /** Feed one frame's speech probability; returns an event when one occurs. */
    fun accept(probability: Float): Event? {
        if (finished) return null
        val index = frame++
        if (!speaking) {
            if (probability >= config.startThreshold) {
                speaking = true
                startFrame = index
                quietFrames = 0
                return Event.SpeechStart
            }
            // Nothing said at all for a long while: give the turn back.
            if (index >= noSpeechFrames) {
                finished = true
                return Event.SpeechEnd(index, index, EndReason.NO_SPEECH)
            }
            return null
        }

        if (index - startFrame >= maxFrames) {
            finished = true
            return Event.SpeechEnd(startFrame, index, EndReason.MAX_LENGTH)
        }
        if (probability < config.endThreshold) {
            quietFrames++
            if (quietFrames >= hangoverFrames) {
                val end = index - quietFrames + 1
                return if (end - startFrame >= minSpeechFrames) {
                    finished = true
                    Event.SpeechEnd(startFrame, end, EndReason.SILENCE)
                } else {
                    // Too short to be a turn — a door slam, not a child. Re-arm.
                    speaking = false
                    quietFrames = 0
                    softFired = false
                    null
                }
            }
            if (!softFired && quietFrames >= softHangoverFrames) {
                val end = index - quietFrames + 1
                // The same too-short guard as the firm endpoint: speculating
                // on a door slam wastes exactly the work this exists to save.
                if (end - startFrame >= minSpeechFrames) {
                    softFired = true
                    return Event.SpeechSoftEnd(startFrame, end)
                }
            }
        } else {
            quietFrames = 0
            softFired = false
        }
        return null
    }

    /** Frame index → sample offset, for slicing the captured audio. */
    fun frameToSample(frameIndex: Int): Int = frameIndex * config.windowSamples

    fun reset() {
        frame = 0
        speaking = false
        startFrame = 0
        quietFrames = 0
        finished = false
        softFired = false
    }
}
