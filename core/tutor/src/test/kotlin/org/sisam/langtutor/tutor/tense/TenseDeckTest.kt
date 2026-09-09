package org.sisam.langtutor.tutor.tense

import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sisam.langtutor.content.PhraseSentence
import org.sisam.langtutor.content.ResourcePhrasebankRepository
import org.sisam.langtutor.tutor.cloze.ClozeDeck

/**
 * The deck over the REAL bank — the drill-deck doctrine: a content batch that
 * quietly breaks a rule fails here, not on a device.
 */
class TenseDeckTest {

    private companion object {
        val sentences: List<PhraseSentence> = runBlocking { ResourcePhrasebankRepository().sentences() }
        val deck = TenseDeck(sentences)

        fun byId(id: String) = sentences.first { it.id == id }
        fun itemFor(id: String, stage: TenseStage): TenseItem =
            deck.items(stage).firstOrNull { it.sentence.id == id } ?: error("$id is not an item at $stage")
    }

    @Test
    fun `the time word comes out and the sentence still reads`() {
        val item = itemFor("doc-l3-001", TenseStage.TIME)
        assertEquals("We visited the doctor.", item.shown)
        assertEquals("yesterday", item.hidden)
        assertEquals("We visited the doctor yesterday.", item.restored)
        assertEquals(TenseStop.YESTERDAY, item.stop)
        assertEquals(TenseCue.INFLECTION, item.cue)
        assertEquals(listOf("visited"), item.verb.map { item.words[it] })
    }

    @Test
    fun `a two-word time phrase comes out whole`() {
        val item = itemFor("doc-l3-006", TenseStage.TIME)
        assertEquals("I got a sticker.", item.shown)
        assertEquals("this morning", item.hidden)
    }

    @Test
    fun `a line that never carried a time word is an item with nothing hidden`() {
        val item = itemFor("doc-l3-002", TenseStage.TIME)
        assertEquals("The doctor listened to my heart.", item.shown)
        assertEquals(null, item.hidden)
        assertEquals(TenseStop.YESTERDAY, item.stop)
    }

    @Test
    fun `the reveal points at the auxiliary that carries the future`() {
        val item = itemFor("bir-l4-008", TenseStage.TIME)
        assertEquals("Dad will hang the decorations.", item.shown)
        assertEquals("tomorrow", item.hidden)
        assertEquals(TenseStop.TOMORROW, item.stop)
        assertEquals(listOf("will", "hang"), item.verb.map { item.words[it] })
    }

    @Test
    fun `going to is marked from the be to the verb`() {
        val item = itemFor("doc-l4-007", TenseStage.TIME)
        assertEquals(listOf("is", "going", "to", "look"), item.verb.map { item.words[it] })
        assertEquals(TenseCue.AUXILIARY, item.cue)
    }

    @Test
    fun `a be-only line is admitted but marked as a copula`() {
        val item = itemFor("doc-l1-002", TenseStage.TIME)
        assertEquals(TenseStop.TODAY, item.stop)
        assertEquals(TenseCue.COPULA, item.cue)
    }

    @Test
    fun `a sentence that straddles two stops is not an item`() {
        val straddler = PhraseSentence(
            id = "t-1", level = 4, tense = "future-simple", frame = "reported-speech",
            en = "She said she will come.", he = "היא אמרה שהיא תבוא.",
        )
        assertTrue(TenseDeck(listOf(straddler)).items(TenseStage.TIME).isEmpty())
    }

    @Test
    fun `a time word the deck cannot lift out of the middle is not an item`() {
        val middle = PhraseSentence(
            id = "t-2", level = 3, tense = "past-simple", frame = "X-Ved",
            en = "The doctor yesterday listened to my heart.", he = "הרופא אתמול הקשיב ללב שלי.",
        )
        assertTrue(TenseDeck(listOf(middle)).items(TenseStage.TIME).isEmpty())
    }

    @Test
    fun `no shown sentence still carries the word that gives its stop away`() {
        var checked = 0
        for (stage in TenseStage.entries) {
            val strip = if (stage == TenseStage.TIME) TenseDeck.CLOCK_WORDS else TenseDeck.CLOCK_WORDS + TenseDeck.ASPECT_WORDS
            for (level in 1..7) {
                for (item in deck.round(TenseSource.All, stage, level, Random(level), size = 40)) {
                    val keys = item.words.map { ClozeDeck.key(it) }
                    for (k in keys) {
                        assertFalse("${item.sentence.id} shows '$k': ${item.shown}", k in strip)
                    }
                    checked++
                }
            }
        }
        assertTrue("nothing measured", checked > 100)
    }

    @Test
    fun `every item can point at the form it is asking about`() {
        for (stage in TenseStage.entries) {
            for (level in 1..7) {
                for (item in deck.round(TenseSource.All, stage, level, Random(level), size = 40)) {
                    assertTrue("${item.sentence.id} marks nothing", item.verb.isNotEmpty())
                    assertTrue(
                        "${item.sentence.id} marks a word that is not there",
                        item.verb.all { it in item.words.indices },
                    )
                }
            }
        }
    }

    @Test
    fun `a round is spread over the stops, not stacked on one`() {
        for (level in 1..7) {
            for (stage in TenseStage.entries) {
                val open = deck.stops(TenseSource.All, stage, level)
                val round = deck.round(TenseSource.All, stage, level, Random(level * 7))
                if (round.size < TenseDeck.ROUND_SIZE) continue
                val counts = round.groupingBy { it.stop }.eachCount()
                assertEquals("L$level $stage round is $counts", open.size, counts.size)
                assertTrue(
                    "L$level $stage round is $counts",
                    counts.values.max() - counts.values.min() <= 1,
                )
            }
        }
    }

    @Test
    fun `a round never leans on be`() {
        for (level in 1..7) {
            for (stage in TenseStage.entries) {
                val round = deck.round(TenseSource.All, stage, level, Random(level))
                val copulas = round.count { it.cue == TenseCue.COPULA }
                assertTrue("L$level $stage has $copulas copulas", copulas <= TenseDeck.MAX_COPULA)
            }
        }
    }

    /**
     * Not an assertion so much as a report: which Levels can actually run the
     * room today, and where the bank runs out. Printed so a content batch
     * moves the number where anyone can see it.
     */
    @Test
    fun `census - what the bank can serve, by level and stop`() {
        val out = StringBuilder("\n")
        for (stage in TenseStage.entries) {
            out.append("== $stage\n")
            for (level in 1..7) {
                val met = deck.items(stage).filter { it.sentence.level <= level }
                val pool = stage.stops.associateWith { stop -> met.count { it.stop == stop } }
                val cues = met.groupingBy { it.cue }.eachCount()
                val round = deck.round(TenseSource.All, stage, level, Random(level))
                out.append(
                    "  L$level  pool=${pool.values.sum().toString().padStart(4)}  " +
                        stage.stops.joinToString("  ") { "${it.name.lowercase()}=${pool.getValue(it)}" } +
                        "   cues=$cues  stops=${deck.stops(TenseSource.All, stage, level).size}" +
                        "  round=${round.size}\n",
                )
            }
        }
        println(out)
    }

    /**
     * The gap the room is blocked on, kept where it cannot be forgotten: how
     * many present-simple items drill a form rather than a placement. Today is
     * the column a Hebrew speaker gets wrong, and it is the one the bank is
     * thinnest at (task #71).
     */
    @Test
    fun `census - how much of Today is more than the copula`() {
        val today = deck.items(TenseStage.TIME).filter { it.stop == TenseStop.TODAY }
        val byCue = today.groupingBy { it.cue }.eachCount()
        val simple = today.filter { it.sentence.tense == "present-simple" }
        println(
            "\nTODAY: ${today.size} items, cues=$byCue\n" +
                "  present-simple ${simple.size}, of which lexical ${simple.count { it.cue != TenseCue.COPULA }}\n" +
                "  sample: " + simple.filter { it.cue != TenseCue.COPULA }.take(6).joinToString("; ") { it.shown },
        )
    }
}
