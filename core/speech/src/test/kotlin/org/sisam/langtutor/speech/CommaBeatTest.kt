package org.sisam.langtutor.speech

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cut and the join, on synthetic renders. That the beat lands at the
 * right length on real Kokoro output is checked by the measurement harness,
 * not here; this pins where a line is cut and that the join's arithmetic —
 * the seam, the karaoke offsets — is exact.
 */
class CommaBeatTest {

    private val rate = 24_000
    private fun ms(n: Int) = n * rate / 1000

    /** [lead] ms of quiet, [tone] ms of tone, [tail] ms of quiet. */
    private fun render(lead: Int, tone: Int, tail: Int): FloatArray {
        val out = FloatArray(ms(lead) + ms(tone) + ms(tail))
        for (i in ms(lead) until ms(lead) + ms(tone)) out[i] = 0.5f * sin(2 * PI * 220 * i / rate).toFloat()
        return out
    }

    @Test
    fun `a short line is cut after each mark`() {
        assertEquals(listOf(CommaBeat.Piece(0, 9), CommaBeat.Piece(10, 17)), CommaBeat.pieces("One cake, please."))
        assertEquals(
            listOf(CommaBeat.Piece(0, 5), CommaBeat.Piece(6, 11), CommaBeat.Piece(12, 18), CommaBeat.Piece(19, 25)),
            CommaBeat.pieces("This, that, these, those."),
        )
        assertEquals(
            listOf(CommaBeat.Piece(0, 5), CommaBeat.Piece(6, 10), CommaBeat.Piece(11, 17)),
            CommaBeat.pieces("Wait… now, go on."),
        )
        // A mark inside quotes still counts.
        assertEquals(2, CommaBeat.pieces("\"Yes,\" she said.").size)
    }

    @Test
    fun `a line the model already paces is one piece`() {
        assertEquals(listOf(CommaBeat.Piece(0, 41)), CommaBeat.pieces("The king is singing, the ring is ringing."))
    }

    @Test
    fun `no mark, no cut`() {
        assertEquals(listOf(CommaBeat.Piece(0, 7)), CommaBeat.pieces("One two"))
        assertEquals(listOf(CommaBeat.Piece(0, 5)), CommaBeat.pieces("Hello"))
        // A mark on the LAST word ends nothing inside the line.
        assertEquals(1, CommaBeat.pieces("One two,").size)
    }

    @Test
    fun `the seam is the target, counting the quiet already there`() {
        val a = render(0, 300, 40)
        val b = render(60, 300, 0)
        val parts = listOf(
            CommaBeat.Part(a, listOf(KaraokeTiming.Word(0, 4, 0), KaraokeTiming.Word(5, 10, ms(150))), 0),
            CommaBeat.Part(b, listOf(KaraokeTiming.Word(0, 7, 0)), 11),
        )
        val (out, words) = CommaBeat.join(parts, rate)
        val gap = out.size - a.size - b.size
        // 40 ms tail + 60 ms head already quiet: 80 ms more makes the beat.
        assertEquals(ms(CommaBeat.TARGET_MS - 100), gap)
        // Both renders intact, in place.
        for (i in a.indices) assertEquals(a[i], out[i], 0f)
        for (i in b.indices) assertEquals(b[i], out[a.size + gap + i], 0f)
        for (i in a.size until a.size + gap) assertEquals(0f, out[i], 0f)
        // Karaoke: the first piece's words unchanged, the second's moved
        // by the first piece and the gap, characters offset to the line.
        assertEquals(listOf(0, ms(150), a.size + gap), words.map { it.startFrame })
        assertEquals(listOf(0, 5, 11), words.map { it.charStart })
        assertEquals(listOf(4, 10, 18), words.map { it.charEnd })
    }

    @Test
    fun `padding beyond the beat is cut, from the far side of each edge`() {
        // Kokoro's own edges: ~400 ms in front, ~500 behind. Two renders
        // butted together would sit 900 ms apart; the seam becomes the beat.
        val a = render(0, 300, 500)
        val b = render(400, 300, 0)
        val parts = listOf(
            CommaBeat.Part(a, listOf(KaraokeTiming.Word(0, 4, 0)), 0),
            CommaBeat.Part(b, listOf(KaraokeTiming.Word(0, 7, ms(400))), 5),
        )
        val (out, words) = CommaBeat.join(parts, rate)
        assertEquals(a.size + b.size - ms(900) + ms(CommaBeat.TARGET_MS), out.size)
        // The seam: exactly the target of quiet between the two tones.
        val toneAEnd = ms(300)
        val toneBStart = out.size - ms(300)
        assertEquals(ms(CommaBeat.TARGET_MS), toneBStart - toneAEnd)
        for (i in toneAEnd until toneBStart) assertEquals("seam sample $i", 0f, out[i], 0f)
        // Speech untouched on both sides.
        for (i in 0 until toneAEnd) assertEquals(a[i], out[i], 0f)
        for (i in 0 until ms(300)) assertEquals(b[ms(400) + i], out[toneBStart + i], 0f)
        // The second word starts where its tone now starts.
        assertEquals(toneBStart, words[1].startFrame)
        assertEquals(5, words[1].charStart)
    }

    @Test
    fun `a seam exactly the beat is left alone`() {
        val a = render(0, 300, 90)
        val b = render(90, 300, 0)
        val parts = listOf(CommaBeat.Part(a, emptyList(), 0), CommaBeat.Part(b, emptyList(), 0))
        assertEquals(a.size + b.size, CommaBeat.join(parts, rate).first.size)
    }

    @Test
    fun `slower speech gets a longer beat`() {
        val a = render(0, 300, 0)
        val b = render(0, 300, 0)
        val parts = listOf(CommaBeat.Part(a, emptyList(), 0), CommaBeat.Part(b, emptyList(), 0))
        val normal = CommaBeat.join(parts, rate, speed = 1f).first.size
        val slow = CommaBeat.join(parts, rate, speed = 0.8f).first.size
        assertTrue("slow $slow > normal $normal", slow > normal)
        assertEquals(a.size + b.size + ms(CommaBeat.TARGET_MS), normal)
    }

    @Test
    fun `one part is returned as it is`() {
        val a = render(0, 300, 0)
        val timing = listOf(KaraokeTiming.Word(0, 3, 0))
        val (out, words) = CommaBeat.join(listOf(CommaBeat.Part(a, timing, 0)), rate)
        assertTrue(out === a)
        assertTrue(words === timing)
    }
}
