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
    fun `the mark is the verb, not whatever the bank happens to attest`() {
        // Every one of these marked the wrong word before the reveal was
        // audited: the bank attests `water` as a verb ("Dad is watering the
        // plants"), `mom` because a question puts it after a did, and `not`
        // because a negation puts it after a will.
        fun mark(id: String) = itemFor(id, TenseStage.TIME).let { it.verb.map { i -> it.words[i] } }
        assertEquals(TenseCue.COPULA, itemFor("bth-l1-004", TenseStage.TIME).cue)
        assertEquals(listOf("is"), mark("bth-l1-004")) // The water is warm.
        assertEquals(listOf("are"), mark("fst-l1-004")) // The leaves are green.
        assertEquals(listOf("am"), mark("doc-l2-009")) // …because Mom is with me.
    }

    @Test
    fun `a passive reads its tense off the be, not off the participle`() {
        val now = itemFor("doc-l6-008", TenseStage.TIME) // Bandages are kept in a small box.
        assertEquals(TenseCue.AUXILIARY, now.cue)
        assertEquals(listOf("are", "kept"), now.verb.map { now.words[it] })
        val then = itemFor("cty-l6-002", TenseStage.TIME) // The old post office was built…
        assertEquals(listOf("was", "built"), then.verb.map { then.words[it] })
    }

    @Test
    fun `a two-word past marks the verb, not a number that ends in -ed`() {
        val item = itemFor("trv-l3-009", TenseStage.TIME) // We took a hundred photos.
        assertEquals(TenseCue.INFLECTION, item.cue)
        assertEquals(listOf("took"), item.verb.map { item.words[it] })
    }

    @Test
    fun `a progressive takes the be that governs its own participle`() {
        // "I am excited because we are flying." — the first be belongs to the
        // other clause, and marking it spans two verb phrases.
        val item = itemFor("get-l2-007", TenseStage.TIME)
        assertEquals(listOf("are", "flying."), item.verb.map { item.words[it] })
    }

    @Test
    fun `a perfect on a verb whose past and participle match is still a perfect`() {
        // leave/left, find/found, buy/bought are TWO-member families, so the
        // participle is the family's last member, never its third.
        val item = itemFor("get-l6-004", TenseStage.ASPECT)
        assertEquals(TenseStop.BEFORE_THAT, item.stop)
        assertEquals(listOf("had", "left"), item.verb.map { item.words[it] })
    }

    @Test
    fun `a lift that would leave a fragment is refused`() {
        // "Last summer was very hot." — the time phrase IS the subject.
        assertTrue(deck.items(TenseStage.TIME).none { it.sentence.id == "wea-l3-009" })
        // "My knee is better than yesterday." — `than` governs the time word.
        assertTrue(deck.items(TenseStage.TIME).none { it.sentence.id == "doc-l4-010" })
        assertTrue(deck.items(TenseStage.TIME).none { it.sentence.id == "brk-l3-009" })
    }

    @Test
    fun `no mark spans two clauses`() {
        // Every cross-clause mark this ever produced was quiet and wrong: an
        // auxiliary from one verb phrase and a participle from another.
        for (stage in TenseStage.entries) {
            for (item in deck.items(stage)) {
                if (item.verb.size < 2) continue
                val between = (item.verb.first() until item.verb.last())
                assertTrue(
                    "${item.sentence.id} spans a comma: ${item.verb.map { item.words[it] }}",
                    between.none { item.words[it].endsWith(",") },
                )
            }
        }
    }

    @Test
    fun `an inflection mark is never one of the nouns the bank attests as a verb`() {
        // The bank attests every one of these as a verb somewhere — `water`
        // from "Dad is watering the plants", `story` from "go to story hour",
        // `mom` and `dad` because a question puts them after a did — and every
        // one of them was underlined as the form that carries the tense.
        val nouns = setOf(
            "water", "story", "paint", "colour", "color", "mom", "dad", "grandpa", "bees",
            "thanks", "hundred", "smile", "drink", "stop", "practice", "quiet", "warm",
            "leaves", "i", "not", "crowded", "tired",
        )
        var checked = 0
        for (stage in TenseStage.entries) {
            for (item in deck.items(stage)) {
                if (item.cue != TenseCue.INFLECTION) continue
                val mark = ClozeDeck.key(item.words[item.verb.first()])
                assertFalse("${item.sentence.id} marks '$mark': ${item.shown}", mark in nouns)
                checked++
            }
        }
        assertTrue("nothing measured", checked > 500)
    }

    @Test
    fun `a question's tense is on its auxiliary, not on the next clause`() {
        // "Did you ask before you took the tablet?" marked `took` — the verb of
        // the clause after the one being asked about.
        val item = itemFor("cmp-l3-004", TenseStage.TIME)
        assertEquals(TenseCue.AUXILIARY, item.cue)
        assertEquals(listOf("Did"), item.verb.map { item.words[it] })
        // "Did Dad wash the car?" marked `Did Dad` — the subject, not the verb.
        val dad = itemFor("hom-l3-008", TenseStage.TIME)
        assertEquals(listOf("Did", "wash"), dad.verb.map { dad.words[it] })
    }

    @Test
    fun `a passive is recognised by position, not by what the lexicon knows`() {
        // `passed`, `named`, `launched` and `served` are attested nowhere else
        // in the bank as verbs, so a shape test that demanded attestation left
        // twenty passive lines marking the wrong thing or nothing.
        for ((id, mark) in listOf(
            "mkt-l6-011" to listOf("was", "passed"),
            "zoo-l6-005" to listOf("was", "named"),
            "spc-l6-008" to listOf("was", "launched"),
            "hom-l6-012" to listOf("is", "served"),
        )) {
            val item = itemFor(id, TenseStage.TIME)
            assertEquals(id, TenseCue.AUXILIARY, item.cue)
            assertEquals(id, mark, item.verb.map { item.words[it] })
        }
    }

    @Test
    fun `the leftmost verb of the clause wins, whether regular or irregular`() {
        // "We cleaned our table before we left." marked `left` once the
        // irregular scan was moved ahead of the -ed scan.
        val item = itemFor("rst-l3-012", TenseStage.TIME)
        assertEquals(listOf("cleaned"), item.verb.map { item.words[it] })
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
