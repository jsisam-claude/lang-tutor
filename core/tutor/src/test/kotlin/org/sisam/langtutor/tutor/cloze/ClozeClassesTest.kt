package org.sisam.langtutor.tutor.cloze

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sisam.langtutor.content.ResourcePhrasebankRepository
import org.sisam.langtutor.tutor.cloze.ClozeClasses.Kind
import org.sisam.langtutor.tutor.cloze.ClozeClasses.Role
import org.sisam.langtutor.tutor.cloze.ClozeClasses.Shape

/**
 * The gate on the one authored table in the fill-the-gap room. The table is
 * small and hand-written, so every list is walked: a word that drifts into
 * two classes without a positional rule, a same-meaning group naming a
 * non-member, an article rule the bank contradicts — each fails here by name.
 */
class ClozeClassesTest {

    private val members = ClozeClasses.MEMBERS

    @Test
    fun `every class can field three distractors`() {
        for ((kind, words) in members) {
            // The determiner classes pool together into one slot.
            val pooled = if (kind in ClozeClasses.DETERMINERS) {
                ClozeClasses.DETERMINERS.flatMap { members.getValue(it) }
            } else {
                words
            }
            assertTrue("$kind has only ${pooled.size} members", pooled.size >= 4)
        }
    }

    @Test
    fun `members are lowercase, distinct within a class, and every overlap has a rule`() {
        val seen = mutableMapOf<String, MutableSet<Kind>>()
        for ((kind, words) in members) {
            assertEquals("$kind repeats a word", words.size, words.toSet().size)
            for (w in words) {
                assertEquals("$kind/$w is not lowercase", w.lowercase(), w)
                seen.getOrPut(w) { mutableSetOf() }.add(kind)
            }
        }
        val overlapping = seen.filterValues { it.size > 1 }.keys
        assertTrue(
            "words in two classes without a positional rule: ${overlapping - ClozeClasses.RESOLVED_BY_POSITION}",
            overlapping.all { it in ClozeClasses.RESOLVED_BY_POSITION },
        )
        assertTrue("NEVER overlaps a class", (ClozeClasses.NEVER intersect seen.keys).isEmpty())
    }

    @Test
    fun `the rule sets only name table members`() {
        val all = members.values.flatten().toSet()
        for (group in ClozeClasses.SAME_MEANING) {
            assertTrue("same-meaning group $group is not a pair", group.size >= 2)
            assertTrue("same-meaning group $group is not lowercase", group.all { it == it.lowercase() })
            // A closed-class member pairs only with members; an open pair is free.
            if (group.any { it in all }) assertTrue("same-meaning group $group mixes classes", group.all { it in all })
        }
        for (family in ClozeClasses.IRREGULAR_FAMILIES) {
            assertTrue("family $family is not a family", family.size >= 2)
            assertTrue(family.all { it == it.lowercase() })
        }
        assertEquals(
            "a form in two families",
            ClozeClasses.IRREGULAR_FAMILIES.sumOf { it.size },
            ClozeClasses.IRREGULAR_FAMILIES.flatten().toSet().size,
        )
        assertTrue((ClozeClasses.SINGULAR_ONLY - all).isEmpty())
        assertTrue((ClozeClasses.PLURAL_ONLY - all).isEmpty())
        for ((verb, subjects) in ClozeClasses.AGREEMENT) {
            assertTrue("$verb is not a NEVER verb form", verb in ClozeClasses.NEVER)
            assertTrue("$verb admits a non-subject", subjects.all { it in members.getValue(Kind.SUBJECT) })
        }
        assertTrue((ClozeClasses.NEVER_DISTRACTOR - all - ClozeClasses.NEVER - ClozeClasses.RESOLVED_BY_POSITION).isEmpty())
    }

    @Test
    fun `articleFor agrees with every article in the bank`() {
        // 449 tokens, 30 of them "an hour": the rule plus AN_WORDS must
        // reproduce all of them, or "a hour" ships as the right answer.
        val sentences = runBlocking { ResourcePhrasebankRepository().sentences() }
        var checked = 0
        for (s in sentences) {
            val words = ClozeDeck.words(s.en)
            for (i in words.indices) {
                val k = ClozeDeck.key(words[i])
                if (k != "a" && k != "an") continue
                val next = words.getOrNull(i + 1)?.let { ClozeDeck.key(it) }
                assertEquals("${s.id}: '${words[i]} ${words.getOrNull(i + 1)}'", k, ClozeClasses.articleFor(next))
                checked++
            }
        }
        assertTrue("expected hundreds of articles, saw $checked", checked > 400)
    }

    @Test
    fun `plural covers the pack's own words`() {
        assertEquals("butterflies", ClozeClasses.plural("butterfly"))
        assertEquals("foxes", ClozeClasses.plural("fox"))
        assertEquals("halves", ClozeClasses.plural("half"))
        assertEquals("mice", ClozeClasses.plural("mouse"))
        assertEquals("sheep", ClozeClasses.plural("sheep"))
        assertEquals("fish", ClozeClasses.plural("fish"))
        assertEquals("monkeys", ClozeClasses.plural("monkey"))
        assertEquals("dogs", ClozeClasses.plural("dog"))
    }

    @Test
    fun `sameStem sees regular inflection and not irregular pairs`() {
        assertTrue(ClozeClasses.sameStem("warm", "warmed"))
        assertTrue(ClozeClasses.sameStem("cat", "cats"))
        assertTrue(ClozeClasses.sameStem("play", "playing"))
        assertTrue(ClozeClasses.sameStem("carry", "carries"))
        // The owner's own example of a FAIR pair, decided by the Hebrew
        // tense: an irregular family, known by the authored table.
        assertTrue(ClozeClasses.sameStem("saw", "see"))
        assertTrue(ClozeClasses.isIrregularNonBase("saw"))
        assertFalse(ClozeClasses.isIrregularNonBase("see"))
        assertFalse(ClozeClasses.sameStem("warm", "cold"))
    }

    private fun roles(en: String, he: String = ""): List<Role> {
        val words = ClozeDeck.words(en)
        val keys = words.map { ClozeDeck.key(it).let { k -> if (k == "an") "a" else k } }
        val shapes = words.mapIndexed { i, w ->
            ClozeClasses.shapeOf(keys[i], w.first().isUpperCase(), i == 0)
        }
        return keys.indices.map { ClozeClasses.roleOf(keys, shapes, it, he) }
    }

    @Test
    fun `roleOf resolves the known ambiguities by position`() {
        assertEquals(Role.Closed(Kind.POSSESSIVE), roles("I like her hat.")[2])
        assertEquals(Role.Closed(Kind.OBJECT), roles("I like her.")[2])
        assertEquals(Role.Closed(Kind.QUESTION), roles("When is dinner?")[0])
        assertEquals(Role.Closed(Kind.CONJUNCTION), roles("We eat when Dad calls.")[2])
        // Trailing adverb use: nothing else could stand there.
        assertEquals(Role.Never, roles("I have never slept in a tent before.")[7])
        assertEquals(Role.Closed(Kind.TIME_PREP), roles("We eat before school.")[2])
        assertEquals(Role.Closed(Kind.CONJUNCTION), roles("We eat before we play.")[2])
        // Infinitive marker, not a place.
        assertEquals(Role.Never, roles("I want to play.")[2])
        assertEquals(Role.Closed(Kind.PLACE_PREP), roles("We walked to the park.")[2])
        // Contractions and names are never gaps.
        assertEquals(Role.Never, roles("I can't find my hat.")[1])
        assertEquals(Role.Never, roles("I saw Noa today.")[2])
        // A determiner before a word it determines; a pronoun otherwise.
        assertEquals(Role.Closed(Kind.DEMONSTRATIVE), roles("This hat is mine.")[0])
        assertEquals(Role.Never, roles("This is my hat.")[0])
        assertEquals(Role.Closed(Kind.QUANTIFIER), roles("It rained all night.")[2])
        // `that` only when the Hebrew says demonstrative.
        assertEquals(Role.Closed(Kind.DEMONSTRATIVE), roles("I want that ball.", "אני רוצה את הכדור ההוא.")[2])
        assertEquals(Role.Never, roles("I want that ball.", "אני רוצה את הכדור.")[2])
        assertEquals(Role.Never, roles("Dad said that dinner was ready.", "אבא אמר שארוחת הערב מוכנה.")[2])
        // Subject versus object pronoun.
        assertEquals(Role.Closed(Kind.SUBJECT), roles("Are you taking pictures?")[1])
        assertEquals(Role.Closed(Kind.SUBJECT), roles("You must wash your hands.")[0])
        assertEquals(Role.Closed(Kind.OBJECT), roles("I can see you.")[3])
        // Open words and a BE form.
        assertEquals(Role.Open, roles("The bread is warm.")[3])
        assertEquals(Role.Never, roles("The bread is warm.")[2])
        // "like" is the verb after a subject and the preposition elsewhere.
        assertEquals(Role.Open, roles("I like my city.")[1])
        assertEquals(Role.Never, roles("It looks like rain.")[2])
        // The infinitive marker before an -er verb and before "do".
        assertEquals(Role.Never, roles("I want to order soup.")[2])
        assertEquals(Role.Never, roles("We are going to do it.")[3])
        // A watering can is not a modal.
        assertEquals(Role.Never, roles("My watering can is green.")[2])
        assertEquals(Role.Closed(Kind.MODAL), roles("I can swim.")[1])
        // A subject after "before" or "did".
        assertEquals(Role.Closed(Kind.SUBJECT), roles("Did you ask before you took it?")[4])
        assertEquals(Role.Never, roles("Count to twenty.")[2])
    }

    @Test
    fun `shape is a suffix rule with a floor on the stem`() {
        assertEquals(Shape.ING, ClozeClasses.shapeOf("playing", false, false))
        assertEquals(Shape.ED, ClozeClasses.shapeOf("walked", false, false))
        assertEquals(Shape.S, ClozeClasses.shapeOf("cats", false, false))
        assertEquals(Shape.BASE, ClozeClasses.shapeOf("bus", false, false))
        assertEquals(Shape.BASE, ClozeClasses.shapeOf("red", false, false))
        assertEquals(Shape.BASE, ClozeClasses.shapeOf("bed", false, false))
        assertEquals(Shape.ER, ClozeClasses.shapeOf("faster", false, false))
        assertEquals(Shape.EST, ClozeClasses.shapeOf("fastest", false, false))
        assertEquals(Shape.PROPER, ClozeClasses.shapeOf("noa", true, false))
        assertEquals(Shape.BASE, ClozeClasses.shapeOf("the", true, true))
    }
}
