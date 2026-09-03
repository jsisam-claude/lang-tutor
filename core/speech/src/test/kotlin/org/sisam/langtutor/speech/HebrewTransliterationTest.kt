package org.sisam.langtutor.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

/**
 * The gloss is read by children who cannot check it, so the sounds are pinned
 * by name rather than by eyeballing the output: each expectation below spells
 * out which Hebrew letter must carry which English sound.
 */
class HebrewTransliterationTest {

    // Named code points, never pasted literals: a nikud in source is invisible
    // in most editors, and two visually identical strings can differ in mark
    // order — which is exactly the bug these tests exist to catch.
    private val alef = "\u05d0"
    private val bet = "\u05d1"
    private val gimel = "\u05d2"
    private val dalet = "\u05d3"
    private val vav = "\u05d5"
    private val zayin = "\u05d6"
    private val tet = "\u05d8"
    private val yod = "\u05d9"
    private val lamed = "\u05dc"
    private val mem = "\u05de"
    private val finalMem = "\u05dd"
    private val nun = "\u05e0"
    private val finalNun = "\u05df"
    private val samekh = "\u05e1"
    private val pe = "\u05e4"
    private val finalPe = "\u05e3"
    private val tsadi = "\u05e6"
    private val qof = "\u05e7"
    private val resh = "\u05e8"
    private val shin = "\u05e9"
    private val tav = "\u05ea"

    private val sheva = "\u05b0"
    private val hiriq = "\u05b4"
    private val tsere = "\u05b5"
    private val segol = "\u05b6"
    private val patah = "\u05b7"
    private val holam = "\u05b9"
    private val dagesh = "\u05bc"
    private val shinDot = "\u05c1"
    private val geresh = "\u05f3"

    @Test
    fun `a closed syllable points the consonant and keeps the coda bare`() {
        // cat = k æ t -> qof + segol, then tet. The vowel point sits on the
        // qof, which is the whole reason consonants are held before written.
        assertEquals(qof + segol + tet, HebrewTransliteration.ofIpa("k\u02c8\u00e6t"))
    }

    @Test
    fun `marks come out in canonical order, so NFC is a no-op`() {
        // The trap: dagesh is combining class 21 and the vowel points are
        // 10-19, so canonical order is letter, VOWEL, dagesh — the reverse of
        // how the marks are usually described. Emitting them the other way
        // renders identically and compares unequal, which would break the
        // moment anything caches or searches this text.
        val words = listOf("k\u00e6t", "b\u0254l", "\u02c8\u028cp", "\u0283\u02c8\u026ap", "\u00f0\u0259")
        for (ipa in words) {
            val out = HebrewTransliteration.ofIpa(ipa)
            assertEquals(
                "not canonically ordered for $ipa",
                Normalizer.normalize(out, Normalizer.Form.NFC),
                out,
            )
        }
        // And specifically: the vowel precedes the dagesh.
        assertEquals(bet + segol + dagesh + dalet, HebrewTransliteration.ofIpa("b\u025b d".replace(" ", "")))
    }

    @Test
    fun `stress marks are dropped, not rendered`() {
        assertEquals(
            HebrewTransliteration.ofIpa("k\u00e6t"),
            HebrewTransliteration.ofIpa("k\u02c8\u00e6t"),
        )
        assertEquals(
            HebrewTransliteration.ofIpa("k\u00e6t"),
            HebrewTransliteration.ofIpa("k\u02cc\u00e6t"),
        )
    }

    @Test
    fun `a word starting with a vowel gets an alef to sit on`() {
        // apple = æ p ə l. Nothing precedes the æ, so it needs a carrier —
        // a bare nikud with no letter is not text.
        val apple = HebrewTransliteration.ofIpa("\u02c8\u00e6p\u0259l")
        assertTrue("expected a leading alef in $apple", apple.startsWith(alef))
        assertEquals(alef + segol + pe + sheva + dagesh + lamed, apple)
    }

    @Test
    fun `b and v are different letters, and so are v and w`() {
        // The distinction custom blurs and a pronunciation aid must not:
        // "very" and "wery" is a mistake Hebrew speakers actually make.
        val b = HebrewTransliteration.ofIpa("bi")   // "be"
        val v = HebrewTransliteration.ofIpa("vi")   // "vee"
        val w = HebrewTransliteration.ofIpa("wi")   // "we"
        assertEquals(bet + hiriq + dagesh + yod, b)
        assertEquals(bet + hiriq + yod, v)
        assertEquals(vav + hiriq + yod, w)
        assertTrue(b != v && v != w)
    }

    @Test
    fun `the sounds Hebrew lacks are marked with a geresh`() {
        // th (both voicings), ch, j and zh have no plain Hebrew letter. An
        // unmarked near-miss would teach the wrong sound. Note the geresh does
        // NOT sit against its letter when the letter carries a vowel — the
        // point comes between them — so these check both parts, not a pair.
        val think = HebrewTransliteration.ofIpa("\u03b8\u026a\u014bk")   // think
        assertTrue(think.startsWith(tav + hiriq + geresh))
        assertEquals(dalet + sheva + geresh, HebrewTransliteration.ofIpa("\u00f0\u0259"))  // the
        val chair = HebrewTransliteration.ofIpa("\u02a7\u025b\u0279")     // chair
        assertEquals(tsadi + segol + geresh + resh, chair)
        val jump = HebrewTransliteration.ofIpa("\u02a4\u028cmp")           // jump
        assertTrue(jump.startsWith(gimel + patah + geresh))
        // With no vowel at all the geresh follows the bare letter.
        assertEquals(zayin + geresh, HebrewTransliteration.ofIpa("\u0292"))
    }

    @Test
    fun `a geresh comes after the vowel point, never between letter and point`() {
        // Geresh is combining class 0 — a starter. Put it before the nikud and
        // the nikud attaches to the GERESH instead of to the letter.
        assertEquals(dalet + sheva + geresh, HebrewTransliteration.ofIpa("\u00f0\u0259"))
    }

    @Test
    fun `sh takes the shin dot`() {
        val ship = HebrewTransliteration.ofIpa("\u0283\u02c8\u026ap")
        assertEquals(shin + hiriq + shinDot + pe + dagesh, ship)
    }

    @Test
    fun `word-final mem nun and pe take their final forms`() {
        assertTrue(HebrewTransliteration.ofIpa("h\u02c8\u00e6m").endsWith(finalMem))
        assertTrue(HebrewTransliteration.ofIpa("s\u02c8\u028cn").endsWith(finalNun))
        assertTrue(HebrewTransliteration.ofIpa("\u0254f").endsWith(finalPe))
    }

    @Test
    fun `a final p keeps its dagesh and its ordinary shape`() {
        // The /p/ letter is pe PLUS a dagesh, and Hebrew does not write a
        // final pe with one. The rule must fire only on bare letters.
        val up = HebrewTransliteration.ofIpa("\u02c8\u028cp")
        assertEquals(alef + patah + pe + dagesh, up)
        assertFalse(up.contains(finalPe))
    }

    @Test
    fun `a mem inside a word keeps its ordinary form`() {
        // The final form applies at the END of a word, not to the letter.
        val jump = HebrewTransliteration.ofIpa("\u02a4\u02c8\u028cmp")
        assertTrue("mem should not be final inside $jump", jump.contains(mem))
        assertFalse(jump.contains(finalMem))
    }

    @Test
    fun `consonant clusters are written unpointed, as Hebrew writes them`() {
        // stop = s t ɑ p. The s has no vowel of its own, so it stands bare.
        assertEquals(
            samekh + tet + patah + pe + dagesh,
            HebrewTransliteration.ofIpa("st\u02c8\u0251p"),
        )
    }

    @Test
    fun `er is one segol plus resh however the phonemizer spells it`() {
        // Stressed ER arrives as the two symbols ɜɹ, unstressed as the single
        // ɚ. Both must land on the same letters, or one sound would look like
        // two different ones across a lesson.
        assertEquals(bet + segol + dagesh + resh + dalet, HebrewTransliteration.ofIpa("b\u02c8\u025c\u0279d"))
        assertTrue(HebrewTransliteration.ofIpa("b\u02c8\u028ct\u025a").endsWith(segol + resh))
    }

    @Test
    fun `unknown symbols are dropped rather than guessed`() {
        // A symbol with no Hebrew equivalent must not invent one, and the rest
        // of the word still has to render.
        assertEquals(
            HebrewTransliteration.ofIpa("k\u00e6t"),
            HebrewTransliteration.ofIpa("k\u00e6\u2205t"),
        )
    }

    @Test
    fun `empty input gives empty output`() {
        assertEquals("", HebrewTransliteration.ofIpa(""))
    }

    // --- alignment ---------------------------------------------------------

    @Test
    fun `every english word gets exactly one hebrew column`() {
        val phonemizer = KokoroPhonemizer.load()
        val gloss = HebrewTransliteration.gloss("There is a lion", phonemizer)
        assertEquals(listOf("There", "is", "a", "lion"), gloss.map { it.english })
        assertTrue("no column may be empty", gloss.all { it.hebrew.isNotEmpty() })
    }

    @Test
    fun `punctuation does not merge two words into one column`() {
        // A gloss must keep one column per English word however the line is
        // punctuated; per-word phonemization is what guarantees it, rather than
        // splitting a whole-line IPA string on its spaces.
        val phonemizer = KokoroPhonemizer.load()
        val gloss = HebrewTransliteration.gloss("Hi, there!", phonemizer)
        assertEquals(listOf("Hi,", "there!"), gloss.map { it.english })
        assertTrue(gloss.all { it.hebrew.isNotEmpty() })
    }

    @Test
    fun `the gloss says the same thing the voice says`() {
        // Both read the same phoneme string, so they cannot drift apart. If
        // this ever fails, the letters and the audio disagree — which is worse
        // than having no letters at all.
        val phonemizer = KokoroPhonemizer.load()
        val spoken = phonemizer.phonemizeToIpa("cat")
        assertEquals(
            HebrewTransliteration.ofIpa(spoken),
            HebrewTransliteration.gloss("cat", phonemizer).single().hebrew,
        )
    }

    @Test
    fun `the gloss follows the voice into an accent`() {
        // The gloss is a pronunciation hint. If the voice says a line with a
        // burr and the Hebrew letters under it still spell the American
        // vowels, the child is being shown one thing and hearing another.
        val phonemizer = KokoroPhonemizer.load()
        val spoken = Phonology.SCOTTISH.applyTo(phonemizer.phonemizeToIpa("gold"))
        assertEquals(
            HebrewTransliteration.ofIpa(spoken),
            HebrewTransliteration.gloss("gold", phonemizer, Phonology.SCOTTISH).single().hebrew,
        )
        assertTrue(
            "the accent has to reach the letters at all",
            HebrewTransliteration.gloss("gold", phonemizer, Phonology.SCOTTISH).single().hebrew !=
                HebrewTransliteration.gloss("gold", phonemizer).single().hebrew,
        )
    }

    @Test
    fun `words the dictionary does not know still get a gloss`() {
        // Children's names go through RuleG2p, and a name with no gloss is
        // exactly the word a child most needs help saying.
        val phonemizer = KokoroPhonemizer.load()
        val gloss = HebrewTransliteration.gloss("Yael", phonemizer)
        assertTrue("expected a gloss for an out-of-dictionary name", gloss.single().hebrew.isNotEmpty())
    }

    @Test
    fun `every phoneme the phonemizer can emit has a hebrew spelling`() {
        // The real completeness check: push the whole reachable inventory
        // through and assert nothing silently vanishes. A missing entry shows
        // up in the app as a word with a letter quietly absent, which nobody
        // reviewing Hebrew output would necessarily catch.
        val phonemizer = KokoroPhonemizer.load()
        val words = listOf(
            "father", "cup", "cat", "bed", "about", "sit", "see", "ball",
            "book", "blue", "bird", "butter", "day", "my", "now", "go", "boy",
            "big", "very", "we", "put", "off", "do", "the", "top", "think",
            "get", "jump", "key", "hat", "look", "man", "no", "sing", "red",
            "so", "ship", "zoo", "measure", "chair", "yes",
        )
        val stress = setOf('\u02c8', '\u02cc')
        for (word in words) {
            val ipa = phonemizer.phonemizeToIpa(word)
            val hebrew = HebrewTransliteration.ofIpa(ipa)
            val sounds = ipa.count { it !in stress }
            assertTrue("no gloss for \"$word\" (ipa=$ipa)", hebrew.isNotEmpty())
            assertTrue(
                "\"$word\" has $sounds sounds but only ${hebrew.length} chars (ipa=$ipa, out=$hebrew)",
                hebrew.length >= sounds,
            )
            assertEquals(
                "\"$word\" is not canonically ordered",
                Normalizer.normalize(hebrew, Normalizer.Form.NFC),
                hebrew,
            )
        }
    }
}
