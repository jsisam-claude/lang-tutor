package org.sisam.langtutor.speech

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VadGateTest {

    /** Real Silero probabilities for a clip of silence → speech → silence. */
    private class Golden(val probs: FloatArray, val truthStart: Int, val truthEnd: Int)

    private fun golden(): Golden {
        val text = checkNotNull(javaClass.classLoader.getResourceAsStream("vad/silero-probs.json"))
            .bufferedReader(Charsets.UTF_8).readText()
        val o = Json.parseToJsonElement(text).jsonObject
        val truth = o.getValue("energyTruth").jsonArray.map { it.jsonPrimitive.int }
        return Golden(
            probs = o.getValue("probs").jsonArray.map { it.jsonPrimitive.float }.toFloatArray(),
            truthStart = truth[0],
            truthEnd = truth[1],
        )
    }

    private fun run(gate: VadGate, probs: FloatArray): List<VadGate.Event> =
        probs.toList().mapNotNull { gate.accept(it) }

    @Test
    fun `endpoints real speech within a frame or two of the energy envelope`() {
        val g = golden()
        val events = run(VadGate(), g.probs)

        assertTrue("no speech detected", events.any { it is VadGate.Event.SpeechStart })
        val end = events.filterIsInstance<VadGate.Event.SpeechEnd>().single()
        assertEquals(VadGate.EndReason.SILENCE, end.reason)

        // Onset must be tight — a late gate clips the child's first sound.
        val onsetErrorMs = (end.startFrame - g.truthStart) * 32
        assertTrue("onset off by ${onsetErrorMs}ms", onsetErrorMs in -100..150)
        // The tail may run past the last energy frame (trailing consonants),
        // but must not overshoot by more than the hangover.
        val offsetErrorMs = (end.endFrame - g.truthEnd) * 32
        assertTrue("offset off by ${offsetErrorMs}ms", offsetErrorMs in -100..800)
    }

    @Test
    fun `pauses inside speech do not split the turn`() {
        // 10 speech frames, a 300ms gap (a child thinking), 10 more, then quiet.
        val probs = FloatArray(10) { 0.9f } + FloatArray(9) { 0.05f } +
            FloatArray(10) { 0.9f } + FloatArray(40) { 0.02f }
        val events = run(VadGate(), probs)
        assertEquals(1, events.count { it is VadGate.Event.SpeechStart })
        val end = events.filterIsInstance<VadGate.Event.SpeechEnd>().single()
        assertEquals(0, end.startFrame)
        assertTrue("turn was cut at the pause: ${end.endFrame}", end.endFrame >= 29)
    }

    @Test
    fun `a short blip is ignored and the gate re-arms`() {
        // 2 frames of noise (a knock), long silence, then a real utterance.
        val probs = FloatArray(2) { 0.9f } + FloatArray(30) { 0.01f } +
            FloatArray(20) { 0.9f } + FloatArray(30) { 0.01f }
        val events = run(VadGate(), probs)
        val end = events.filterIsInstance<VadGate.Event.SpeechEnd>().single()
        assertEquals(VadGate.EndReason.SILENCE, end.reason)
        assertTrue("blip was taken for the turn: ${end.startFrame}", end.startFrame >= 32)
    }

    @Test
    fun `silence alone ends the turn instead of listening forever`() {
        val gate = VadGate(VadGate.Config(noSpeechTimeoutMs = 1_000))
        val events = run(gate, FloatArray(200) { 0.01f })
        val end = events.filterIsInstance<VadGate.Event.SpeechEnd>().single()
        assertEquals(VadGate.EndReason.NO_SPEECH, end.reason)
    }

    @Test
    fun `a noisy room cannot record forever`() {
        val gate = VadGate(VadGate.Config(maxUtteranceMs = 1_000))
        val events = run(gate, FloatArray(200) { 0.95f })
        val end = events.filterIsInstance<VadGate.Event.SpeechEnd>().single()
        assertEquals(VadGate.EndReason.MAX_LENGTH, end.reason)
    }

    @Test
    fun `nothing is emitted after the turn ends until reset`() {
        val gate = VadGate()
        val probs = FloatArray(10) { 0.9f } + FloatArray(40) { 0.01f }
        run(gate, probs)
        assertNull(gate.accept(0.99f))
        gate.reset()
        assertNotNull(gate.accept(0.99f))
    }
}
