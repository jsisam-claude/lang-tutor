package org.sisam.langtutor.speech

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Kotlin GOP implementation to the Python reference on REAL model
 * output: the fixtures hold actual wav2vec2 posteriors for our own voice saying
 * "red" correctly and as "wed" (the classic Hebrew-L1 substitution), together
 * with the reference's alignment and scores.
 */
class GopScorerTest {

    private class Fixture(
        val name: String,
        val phones: List<String>,
        /** [frames][1 + phones] — blank column first, matching `cols`. */
        val matrix: Array<FloatArray>,
        val frameMax: FloatArray,
        val expected: List<Pair<String, Float>>,
        val expectedFrames: List<Int>,
    )

    private fun fixtures(): List<Fixture> {
        val text = checkNotNull(javaClass.classLoader.getResourceAsStream("gop/fixtures.json"))
            .bufferedReader(Charsets.UTF_8).readText()
        return Json.parseToJsonElement(text).jsonArray.map { el ->
            val o = el.jsonObject
            val matrix = o.getValue("matrix").jsonArray.map { row ->
                row.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
            }.toTypedArray()
            Fixture(
                name = o.getValue("name").jsonPrimitive.content,
                phones = o.getValue("phones").jsonArray.map { it.jsonPrimitive.content },
                matrix = matrix,
                frameMax = o.getValue("frameMax").jsonArray.map { it.jsonPrimitive.float }.toFloatArray(),
                expected = o.getValue("expected").jsonArray.map {
                    it.jsonObject.getValue("phone").jsonPrimitive.content to
                        it.jsonObject.getValue("gop").jsonPrimitive.float
                },
                expectedFrames = o.getValue("expected").jsonArray.map {
                    it.jsonObject.getValue("frames").jsonPrimitive.int
                },
            )
        }
    }

    /**
     * The fixtures store only the columns GOP needs (blank + targets) plus the
     * true per-frame max; expand that into a dense matrix whose argmax equals
     * the real model's, so the scorer runs its real code path.
     */
    private fun dense(f: Fixture): Pair<Array<FloatArray>, IntArray> {
        val vocab = f.phones.size + 2 // blank + targets + one "other" column
        val other = f.phones.size + 1
        val rows = Array(f.matrix.size) { t ->
            FloatArray(vocab) { -60f }.also { row ->
                for (c in f.matrix[t].indices) row[c] = f.matrix[t][c]
                // The winning phone may be outside the target set (that's exactly
                // what a substitution looks like) — park the true max there.
                row[other] = f.frameMax[t]
            }
        }
        val ids = IntArray(f.phones.size) { it + 1 }
        return rows to ids
    }

    @Test
    fun `scores match the reference on real model posteriors`() {
        val all = fixtures()
        assertTrue("fixtures missing", all.size >= 2)
        for (f in all) {
            val (rows, ids) = dense(f)
            val scored = GopScorer.score(rows, ids, f.phones, blankId = 0)
            assertEquals(f.phones.size, scored.size)
            scored.forEachIndexed { i, s ->
                assertEquals("phoneme ${f.name}[$i]", f.expected[i].first, s.phoneme)
                assertEquals(
                    "gop ${f.name}/${s.phoneme}",
                    f.expected[i].second.toDouble(), s.gop.toDouble(), 0.02,
                )
                assertEquals("frames ${f.name}/${s.phoneme}", f.expectedFrames[i], s.frames)
            }
        }
    }

    @Test
    fun `a real substitution is caught and correct speech is not`() {
        val byName = fixtures().associateBy { it.name }
        val good = byName.getValue("red-good")
        val wrong = byName.getValue("red-wrong")

        val goodScores = dense(good).let { (r, ids) -> GopScorer.score(r, ids, good.phones, 0) }
        val wrongScores = dense(wrong).let { (r, ids) -> GopScorer.score(r, ids, wrong.phones, 0) }

        // Every sound of the correct recording passes…
        assertTrue(
            "correct speech flagged: $goodScores",
            goodScores.all { it.verdict == GopScorer.Verdict.GOOD },
        )
        // …and in "wed" exactly the /ɹ/ is called out, not the rest of the word.
        val r = wrongScores.first { it.phoneme == "ɹ" }
        assertEquals(GopScorer.Verdict.WRONG, r.verdict)
        assertTrue("expected a large penalty, got ${r.gop}", r.gop < -3f)
        assertTrue(
            "unrelated sounds should stay fine: $wrongScores",
            wrongScores.filter { it.phoneme != "ɹ" }.all { it.verdict == GopScorer.Verdict.GOOD },
        )
        assertTrue(GopScorer.overall(goodScores) > GopScorer.overall(wrongScores))
    }

    @Test
    fun `forced alignment is monotonic and covers every phoneme`() {
        for (f in fixtures()) {
            val (rows, ids) = dense(f)
            val spans = GopScorer.forcedAlign(rows, ids, blankId = 0)
            assertEquals(ids.size, spans.size)
            var last = -1
            spans.forEachIndexed { i, frames ->
                assertTrue("phoneme $i unaligned in ${f.name}", frames.isNotEmpty())
                assertTrue("alignment went backwards in ${f.name}", frames.first() > last)
                last = frames.last()
            }
        }
    }

    @Test
    fun `expected phonemes map from our own lesson phonemizer`() {
        // The tutor knows "red" as misaki IPA; it must become scorable ids.
        val expected = EspeakPhonemes.expectedFrom("ɹˈɛd")
        assertEquals(listOf("ɹ", "ɛ", "d"), expected.map { it.label })
        assertTrue(expected.all { it.id > 0 })
        // Stress marks and spaces carry no segment to score.
        assertEquals(
            EspeakPhonemes.expectedFrom("ɹɛd").map { it.label },
            EspeakPhonemes.expectedFrom("ˈɹ ɛd!").map { it.label },
        )
        // A misaki vowel resolves to the model's spelling — the LONG one for
        // THOUGHT. This line used to assert "ɔ", which the model does not
        // emit for "ball": the test had frozen the bug in place, and the
        // KDoc's own example was unreachable.
        assertEquals(listOf("b", "ɔː", "l"), EspeakPhonemes.expectedFrom("bˈɔl").map { it.label })
    }

    @Test
    fun `a flawless attempt reaches the top of the scale`() {
        // GOP is at most 0 and 0 IS flawless, so the curve has to reach 1.0
        // there. The old sigmoid topped out at 0.9002, and the star row is
        // (overall * 5).toInt() — so "5 of 5 stars" could not be produced by
        // any input, and a child the model agreed with on every phone was
        // told 4 of 5.
        val perfect = listOf(
            GopScorer.Scored("ɹ", 0f, 5, GopScorer.Verdict.GOOD),
            GopScorer.Scored("ɛ", 0f, 5, GopScorer.Verdict.GOOD),
            GopScorer.Scored("d", 0f, 5, GopScorer.Verdict.GOOD),
        )
        assertEquals(1f, GopScorer.overall(perfect), 1e-6f)
        assertEquals(5, (GopScorer.overall(perfect) * 5).toInt())
    }

    @Test
    fun `the score curve falls the way its documentation says`() {
        fun one(gop: Float) =
            GopScorer.overall(listOf(GopScorer.Scored("x", gop, 1, GopScorer.Verdict.GOOD)))
        assertEquals(1f, one(0f), 1e-6f)
        assertEquals(0.5f, one(-3f), 0.01f)
        assertEquals(0.2f, one(-7f), 0.01f)
        // A sound that never aligned must not drag the mean up.
        assertTrue(one(GopScorer.NOT_ALIGNED) < 1e-6f)
    }

    @Test
    fun `a clip too short to carry the target is not scored as all wrong`() {
        // CTC needs a frame per symbol. Below that every state is unreachable,
        // the backtrace self-loops on a blank and every phone comes back
        // NOT_ALIGNED — a child who said the first word of a long line and
        // lifted the button was told every sound was wrong, including the ones
        // they got right. No feedback is the honest answer.
        val frames = Array(4) { FloatArray(5) { -0.1f } }
        val scored = GopScorer.score(
            logProbs = frames,
            targetIds = intArrayOf(1, 2, 3, 1, 2, 3),
            targetLabels = listOf("a", "b", "c", "d", "e", "f"),
            blankId = 0,
        )
        assertTrue("expected no feedback, got $scored", scored.isEmpty())
    }

    @Test
    fun `a clip that is long enough still scores`() {
        val frames = Array(12) { FloatArray(5) { -0.1f } }
        val scored = GopScorer.score(
            logProbs = frames,
            targetIds = intArrayOf(1, 2, 3),
            targetLabels = listOf("a", "b", "c"),
            blankId = 0,
        )
        assertEquals(3, scored.size)
    }
}
