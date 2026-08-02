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
        // A misaki diphthong resolves to the model's spelling.
        assertEquals(listOf("b", "ɔ", "l"), EspeakPhonemes.expectedFrom("bˈɔl").map { it.label })
    }
}
