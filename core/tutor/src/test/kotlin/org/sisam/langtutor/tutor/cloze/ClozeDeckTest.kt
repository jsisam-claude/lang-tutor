package org.sisam.langtutor.tutor.cloze

import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sisam.langtutor.content.PhraseSentence
import org.sisam.langtutor.content.PicturePack
import org.sisam.langtutor.content.ResourcePhrasebankRepository
import org.sisam.langtutor.content.ResourcePicturePackRepository
import org.sisam.langtutor.tutor.cloze.ClozeClasses.Kind

/**
 * The deck over the REAL bank and packs — the drill-deck doctrine: a content
 * batch that quietly breaks a rule fails here, not on a device.
 */
class ClozeDeckTest {

    private companion object {
        val sentences: List<PhraseSentence> = runBlocking { ResourcePhrasebankRepository().sentences() }
        val packs: List<PicturePack> = runBlocking { ResourcePicturePackRepository().packs() }
        val deck = ClozeDeck(sentences, packs)
        val served = sentences.filter { it.theme !in ClozeDeck.EXCLUDED_THEMES }
        val packWords = packs.flatMap { p -> p.words.map { it.en } }.toSet()

        /** Lowest Level each key occurs at, for the "words the learner has met" cap. */
        val firstLevel: Map<String, Int> = buildMap {
            for (s in sentences) for (w in ClozeDeck.words(s.en)) {
                val k = ClozeDeck.key(w).let { if (it == "an") "a" else it }
                merge(k, s.level) { a, b -> minOf(a, b) }
            }
        }

        fun byId(id: String) = sentences.first { it.id == id }
        fun keysOf(s: PhraseSentence) = ClozeDeck.words(s.en).map { ClozeDeck.key(it) }
    }

    @Test
    fun `every slot fields three honest distractors`() {
        var slots = 0
        for (s in served) {
            val keys = keysOf(s)
            for (slot in deck.slots(s)) {
                slots++
                val where = "${s.id} @${slot.index} '${slot.answer}'"
                assertTrue("$where: pool ${slot.pool}", slot.pool.size >= ClozeDeck.MIN_DISTRACTORS)
                assertEquals("$where: repeats", slot.pool.size, slot.pool.toSet().size)
                assertFalse("$where: offers the answer", slot.answer in slot.pool)
                assertTrue("$where: blank out of range", slot.index in keys.indices)
                assertFalse("$where: apostrophe", '\'' in slot.answer)
                for (d in slot.pool + slot.variants) {
                    assertFalse("$where: '$d' is in the sentence", d in keys)
                    assertEquals("$where: '$d' not lowercase", d.lowercase(), d)
                    // The pronoun "one" is glue; the number one is a pack word.
                    if (slot.kind != ClozeKind.PACK) assertFalse("$where: glue word '$d'", d in ClozeClasses.NEVER_DISTRACTOR)
                }
            }
        }
        assertTrue("suspiciously few slots: $slots", slots > 10_000)
    }

    @Test
    fun `word distractors are words the learner has met`() {
        for (s in served) for (slot in deck.slots(s)) {
            if (slot.kind == ClozeKind.PACK) continue
            for (d in slot.pool + slot.variants) {
                val first = firstLevel[d]
                assertNotNull("${s.id}: '$d' is not in the bank", first)
                assertTrue("${s.id} L${s.level}: '$d' first appears at L$first", first!! <= s.level + 1)
            }
        }
    }

    @Test
    fun `pack slots stay inside the pack and agree in number`() {
        val plurals = packWords.associateWith { ClozeClasses.plural(it) }
        for (s in served) for (slot in deck.slots(s)) {
            if (slot.kind != ClozeKind.PACK) continue
            val pack = packs.first { it.id == slot.packId }
            val words = pack.words.map { it.en }.toSet()
            val answerLemma = words.first { it == slot.answer || plurals[it] == slot.answer }
            val answerPlural = slot.answer != answerLemma
            for (d in slot.pool) {
                val lemma = words.firstOrNull { it == d || plurals[it] == d }
                assertNotNull("${s.id}: '$d' is not in pack ${pack.id}", lemma)
                // An invariant plural (sheep, fish) shows its number only
                // through its neighbours, so it is checked by its own rule.
                if (plurals[lemma] != lemma && plurals[answerLemma] != answerLemma) {
                    assertEquals("${s.id}: '$d' disagrees in number with '${slot.answer}'", answerPlural, d != lemma)
                }
            }
            assertFalse("${s.id}: 'one' is never a pack answer", slot.answer == "one")
            assertNotNull(slot.packWord)
        }
        for (pack in listOf("numbers", "shapes", "animals")) {
            assertTrue("$pack has nothing at Level 1", deck.poolSize(ClozeSource.Pack(pack), 1) > 0)
        }
        for (level in 1..7) assertEquals("maths is not offered", 0, deck.poolSize(ClozeSource.Pack("maths"), level))
    }

    @Test
    fun `a pack word out of its sense is not a pack gap`() {
        // The town square is not the shape, and the aquarium is not a fish.
        val square = byId("cty-l1-009")
        assertTrue("הכיכר" in square.he)
        assertTrue(deck.slots(square).none { it.kind == ClozeKind.PACK })
        val tank = byId("pet-l3-009")
        assertTrue("fish tank" in tank.en)
        assertTrue(deck.slots(tank).none { it.kind == ClozeKind.PACK })
    }

    @Test
    fun `a and the face each other only where the Hebrew proves it`() {
        var proven = 0
        for (s in served) {
            val keys = keysOf(s)
            for (slot in deck.slots(s)) {
                if (slot.closedKind == null || slot.answer !in setOf("a", "the")) continue
                val other = if (slot.answer == "a") "the" else "a"
                val prev = keys.getOrNull(slot.index - 1)
                if (prev != null && prev in ClozeClasses.ABSORBING_PREPS) {
                    assertFalse("${s.id}: a/the after '$prev'", other in slot.pool)
                }
                if (s.align == null) assertFalse("${s.id}: a/the without a cue", other in slot.pool)
                if (other in slot.pool) proven++
            }
        }
        assertTrue("the proof path never fires", proven > 100)
        // The bread is warm / הלחם חם: ה proves "the", so "a" is a fair distractor.
        val bread = deck.slots(byId("mkt-l1-006")).first { it.answer == "the" }
        assertTrue("a" in bread.pool)
    }

    @Test
    fun `same-meaning words never face each other`() {
        // warm and hot both gloss to חם through the cues.
        val warm = deck.slots(byId("mkt-l1-006")).first { it.answer == "warm" }
        assertFalse("hot" in warm.pool)
        for (s in served) for (slot in deck.slots(s)) {
            for (d in slot.pool) {
                assertFalse(
                    "${s.id}: '${slot.answer}' against '$d'",
                    ClozeClasses.SAME_MEANING.any { slot.answer in it && d in it },
                )
            }
        }
    }

    @Test
    fun `subject gaps are never decided by agreement`() {
        for (s in served) for (slot in deck.slots(s)) {
            if (slot.closedKind != Kind.SUBJECT) continue
            assertFalse("${s.id}: 'it' is never a subject answer", slot.answer == "it")
        }
        // "Are ___ taking pictures?": the verb BEFORE the gap rules out
        // he/she/it, which leaves only we/they — too few, so no gap at all.
        assertNull(deck.slots(byId("trv-l2-008")).firstOrNull { it.closedKind == Kind.SUBJECT })
        // "___ want to learn English": a base verb rules out he/she/it.
        val want = deck.slots(byId("sch-l2-003")).first { it.closedKind == Kind.SUBJECT }
        assertEquals(setOf("you", "we", "they"), want.pool.toSet())
    }

    @Test
    fun `the owner's examples come out as designed`() {
        // "I saw a bee in the garden." — saw, bee and the article before
        // garden are all gaps; "a" is never offered against that "the".
        val bee = deck.slots(byId("bee-l3-001"))
        val saw = bee.first { it.answer == "saw" }
        assertEquals(ClozeKind.WORD, saw.kind)
        assertTrue("see" in saw.pool)
        val noun = bee.first { it.answer == "bee" }
        assertEquals(ClozeKind.PACK, noun.kind)
        assertEquals("animals", noun.packId)
        val the = bee.first { it.answer == "the" }
        assertFalse("a" in the.pool)
        assertTrue(the.pool.containsAll(listOf("my", "this", "your")))
        // "I see two penguins." — both the number and the animal are pack gaps.
        val zoo = deck.slots(byId("zoo-l1-005"))
        assertEquals("numbers", zoo.first { it.answer == "two" }.packId)
        assertEquals("animals", zoo.first { it.answer == "penguins" }.packId)
    }

    @Test
    fun `a vowel-initial noun after a or an joins the article to the gap`() {
        // "I see an elephant.": the visible "an" would name the first
        // letter, so the options bring their own article.
        val slot = deck.slots(byId("zoo-l1-002")).first { it.answer == "elephant" }
        assertEquals(ClozeKind.PACK, slot.kind)
        assertTrue(slot.joinArticle)
        val item = deck.item(byId("zoo-l1-002"), slot, Random(3))
        assertEquals(2..3, item.span)
        assertEquals("an elephant", item.options[item.answer])
        assertTrue(item.options.all { it.startsWith("a ") || it.startsWith("an ") })
    }

    @Test
    fun `almost every line has a gap and every topic fills a round`() {
        for (level in 1..7) {
            val lines = served.filter { it.level == level }
            val with = lines.count { deck.slots(it).isNotEmpty() }
            assertTrue("L$level: only $with of ${lines.size} lines have a gap", with >= lines.size - 8)
        }
        val themes = served.map { it.theme }.distinct()
        for (theme in themes) for (level in 1..7) {
            assertTrue(
                "$theme at L$level cannot fill a round",
                deck.poolSize(ClozeSource.Theme(theme), level) >= ClozeDeck.ROUND_SIZE,
            )
        }
        for (level in 1..7) assertEquals(0, deck.poolSize(ClozeSource.Theme("idioms"), level))
    }

    @Test
    fun `a round is bounded, distinct, seeded and well-formed`() {
        val round = deck.round(ClozeSource.All, 3, Random(42))
        assertEquals(ClozeDeck.ROUND_SIZE, round.size)
        assertEquals(round.size, round.map { it.sentence.en }.toSet().size)
        assertEquals(round.size, round.map { it.options[it.answer].lowercase() }.toSet().size)
        for (item in round) {
            assertEquals(ClozeDeck.OPTIONS, item.options.size)
            assertEquals(ClozeDeck.OPTIONS, item.options.toSet().size)
            assertTrue(item.answer in item.options.indices)
            assertTrue(item.blank in item.span)
            val word = ClozeDeck.key(item.words[item.blank])
            assertTrue("${item.sentence.id}: ${item.options[item.answer]} != $word", item.options[item.answer].lowercase().endsWith(word))
            assertEquals(item.kind == ClozeKind.PACK, item.packWord != null)
            assertTrue(item.sentence.level in 2..3)
        }
        assertTrue(round.groupingBy { it.kind }.eachCount().values.all { it <= ClozeDeck.MAX_PER_KIND })
        assertEquals(round, deck.round(ClozeSource.All, 3, Random(42)))
        assertTrue(deck.round(ClozeSource.Pack("maths"), 7, Random(1)).isEmpty())
    }

    @Test
    fun `a pack round is that pack's words, bank lines first, then the template`() {
        val round = deck.round(ClozeSource.Pack("animals"), 1, Random(5), size = 12)
        assertTrue(round.all { it.kind == ClozeKind.PACK && it.packWord != null })
        val firstTemplate = round.indexOfFirst { it.sentence.id.startsWith("pack:") }
        if (firstTemplate >= 0) {
            assertTrue(round.drop(firstTemplate).all { it.sentence.id.startsWith("pack:") })
        }
        // Words the bank never uses still get a turn through the template.
        val tiger = deck.round(ClozeSource.Pack("animals"), 1, Random(1), size = 200)
            .firstOrNull { it.sentence.id == "pack:animals:tiger" }
        assertNotNull("tiger never comes up", tiger)
        assertEquals("I see a tiger.", tiger!!.sentence.en)
        val he = packs.first { it.id == "animals" }.words.first { it.en == "tiger" }.he
        assertEquals("אני רואה $he.", tiger.sentence.he)
        // "Three sheep.": an invariant word counted as plural gets plural company.
        val sheep = deck.slots(byId("frm-l1-004")).first { it.answer == "sheep" }
        assertTrue(sheep.pool.all { it.endsWith("s") || it in setOf("fish", "sheep", "mice") })
    }

    @Test
    fun `a revisited line wears a different gap`() {
        val bee = byId("bee-l3-001")
        assertTrue(deck.slots(bee).size >= 2)
        repeat(5) { seed ->
            val first = checkNotNull(deck.itemFor(bee, Random(seed)))
            val again = checkNotNull(deck.itemFor(bee, Random(seed), avoid = first.blank))
            assertTrue("seed $seed: same gap ${first.blank}", again.blank != first.blank)
        }
        // A line with one gap keeps it rather than disappearing.
        val single = sentences.first { deck.slots(it).size == 1 }
        assertNotNull(deck.itemFor(single, Random(1), avoid = deck.slots(single).single().index))
    }

    @Test
    fun `a source key round-trips`() {
        for (source in listOf(ClozeSource.All, ClozeSource.Theme("market"), ClozeSource.Pack("animals"))) {
            assertEquals(source, ClozeSource.parse(source.sessionKey))
        }
        assertEquals(ClozeSource.All, ClozeSource.parse("garbage"))
    }

    @Test
    fun `display spells the article and the capital`() {
        assertEquals("I", ClozeDeck.display("i", false, "see"))
        assertEquals("an", ClozeDeck.display("a", false, "elephant"))
        assertEquals("a", ClozeDeck.display("a", false, "lion"))
        assertEquals("An", ClozeDeck.display("a", true, "hour"))
        assertEquals("The", ClozeDeck.display("the", true, "cat"))
        assertEquals("cat", ClozeDeck.display("cat", false, null))
    }

    @Test
    fun `an empty bank is an empty round, not an error`() {
        val empty = ClozeDeck(emptyList(), packs)
        assertTrue(empty.round(ClozeSource.All, 1, Random(1)).isEmpty())
        assertNull(empty.slots(byId("bee-l3-001")).firstOrNull { it.kind == ClozeKind.WORD })
    }
}
