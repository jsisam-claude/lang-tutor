package org.sisam.langtutor.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An accent is a rewrite of the phoneme string, so it is testable without a
 * speaker. Three invariants, one per consumer of that string, plus the rule
 * that decides which accents may exist at all.
 */
class PhonologyTest {

    private val phonemizer = KokoroPhonemizer.load()

    private val LINES = listOf(
        "Take care of my gold.",
        "The old boat goes home over the water.",
        "Away and boil your head.",
        "Three thin things.",
        "I bought a small ball.",
        "Peter picks pink peppers.",
        "My brother heard a bird in the garden.",
        "She sells seashells by the seashore.",
    )

    private val accents = Phonology.entries.filter { it != Phonology.GENERAL_AMERICAN }

    @Test
    fun `general american is the identity`() {
        val ipa = phonemizer.phonemizeToIpa("Take care of my gold.")
        assertEquals(ipa, Phonology.GENERAL_AMERICAN.applyTo(ipa))
    }

    @Test
    fun `invariant one - the voice can say every symbol an accent emits`() {
        // encode() drops what it does not recognise SILENTLY, so a bad symbol
        // would not fail: it would delete a sound from the middle of a word.
        for (accent in accents) {
            for (line in LINES) {
                val ipa = accent.applyTo(phonemizer.phonemizeToIpa(line))
                assertEquals(
                    "$accent on \"$line\" leaves Kokoro's vocabulary",
                    emptySet<Char>(),
                    phonemizer.unsupported(ipa),
                )
            }
        }
    }

    @Test
    fun `invariant two - the coach can score every symbol an accent emits`() {
        // If the coach cannot score a phone the voice just said, the child is
        // graded against a shorter sentence than they heard. Checked on the
        // emitted alphabet rather than on a count, because an accent that
        // splits one symbol into two — a vowel plus a separate r, which is
        // what every non-rhotic accent here does — legitimately lengthens the
        // phone list.
        for (accent in accents) {
            for (c in accent.emits) {
                assertEquals(
                    "$accent emits '$c', which the coach cannot score",
                    1,
                    EspeakPhonemes.expectedFrom(c.toString()).size,
                )
            }
        }
    }

    @Test
    fun `an accent the coach follows keeps its phone alignment`() {
        // Only the native accents reach the coach, and those are 1:1 by
        // construction, so the expected phone list must line up exactly with
        // what it lined up with before.
        for (accent in accents.filter { it.scope == Phonology.Scope.EVERYWHERE }) {
            for (line in LINES) {
                val plain = phonemizer.phonemizeToIpa(line)
                assertEquals(
                    "$accent on \"$line\" costs the coach a phone",
                    EspeakPhonemes.expectedFrom(plain).size,
                    EspeakPhonemes.expectedFrom(accent.applyTo(plain)).size,
                )
            }
        }
    }

    @Test
    fun `invariant three - the Hebrew gloss can render every symbol an accent emits`() {
        // The one that shipped broken: SCOTTISH emitted e, o, ɾ and ɒ, none
        // of which the gloss knew, so "red" was glossed אֶד and "bird" came
        // out identical to "bed". Checking the emitted alphabet directly
        // catches it for every accent, including ones not yet written.
        for (accent in accents) {
            val lost = accent.emits.filterNot { HebrewTransliteration.renders(it) }
            assertTrue("$accent emits symbols the gloss drops: $lost", lost.isEmpty())
        }
    }

    @Test
    fun `no accent destroys an English contrast`() {
        // The rule that decides which accents may exist here. A rewrite that
        // merges think with sink or ship with sheep is accurate description
        // and a terrible teacher — worse in this app than anywhere, because
        // Hebrew shares those gaps, so it would model the learner's own error
        // back at them.
        val pairs = listOf(
            "think" to "sink", "three" to "tree", "thin" to "tin",
            "they" to "day", "breathe" to "breed",
            "ship" to "sheep", "bit" to "beat", "live" to "leave",
            "full" to "fool", "pull" to "pool",
            "bad" to "bed", "man" to "men",
            "vet" to "wet", "very" to "wary",
            "pat" to "bat", "zoo" to "sue",
            "bird" to "bared", "her" to "hair", "were" to "wear",
            "cat" to "cut", "hot" to "hat",
        )
        for (accent in accents) {
            for ((a, b) in pairs) {
                val x = accent.applyTo(phonemizer.phonemizeToIpa(a))
                val y = accent.applyTo(phonemizer.phonemizeToIpa(b))
                assertTrue("$accent merges $a with $b (both \"$x\")", x != y)
            }
        }
    }

    @Test
    fun `every accent actually changes something`() {
        val ipa = phonemizer.phonemizeToIpa("My brother heard a bird take the gold home.")
        for (accent in accents) {
            assertTrue("$accent is a no-op", accent.applyTo(ipa) != ipa)
        }
    }

    @Test
    fun `a second-language accent never moves what is taught`() {
        // A native accent is a legitimate model of English and the coach and
        // gloss follow it. An L2 accent describes someone still learning, so
        // the target must not move under it.
        assertEquals(Phonology.Scope.EVERYWHERE, Phonology.SCOTTISH.scope)
        assertEquals(Phonology.Scope.EVERYWHERE, Phonology.IRISH.scope)
        for (accent in listOf(
            Phonology.ITALIAN, Phonology.FRENCH, Phonology.SPANISH,
            Phonology.HEBREW, Phonology.ARABIC, Phonology.MANDARIN,
        )) {
            assertEquals("$accent must not drive the coach", Phonology.Scope.VOICE_ONLY, accent.scope)
        }
    }

    @Test
    fun `rules run in one pass, so no rule can eat another's output`() {
        for (accent in accents) {
            val inputs = accent.rules.map { it.from }
            val outputs = accent.rules.map { it.to }
            val collisions = outputs.filter { out -> inputs.any { it == out } }
            assertTrue("$accent has a cascading pair: $collisions", collisions.isEmpty())
            // And longest-match must be real, not an accident of list order.
            val two = accent.rules.filter { it.from.length > 1 }
            for (rule in two) {
                val shadow = accent.rules.firstOrNull { it !== rule && rule.from.startsWith(it.from) }
                if (shadow != null) {
                    val probe = accent.applyTo(rule.from)
                    assertEquals("$accent: ${rule.from} lost to ${shadow.from}", rule.to, probe)
                }
            }
        }
    }

    @Test
    fun `the token count never moves under a one-to-one accent`() {
        // The style row is indexed by token count and the karaoke timings are
        // shares of it. An accent whose rules are all 1:1 must not shift them.
        for (accent in accents.filter { a -> a.rules.all { it.from.length == it.to.length } }) {
            for (line in LINES) {
                assertEquals(
                    "$accent moved the token count of \"$line\"",
                    phonemizer.phonemize(line).size,
                    phonemizer.phonemize(line, accent).size,
                )
            }
        }
    }

    @Test
    fun `a front end with no accents ignores the request rather than failing`() {
        val plain = KokoroFrontEnd { intArrayOf(1, 2, 3) }
        assertTrue(plain.phonemize("anything", Phonology.SCOTTISH).contentEquals(intArrayOf(1, 2, 3)))
    }
}
