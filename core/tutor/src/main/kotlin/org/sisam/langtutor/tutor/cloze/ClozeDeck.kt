package org.sisam.langtutor.tutor.cloze

import kotlin.random.Random
import org.sisam.langtutor.content.PackWord
import org.sisam.langtutor.content.PhraseSentence
import org.sisam.langtutor.content.PicturePack
import org.sisam.langtutor.tutor.cloze.ClozeClasses.Kind
import org.sisam.langtutor.tutor.cloze.ClozeClasses.Role
import org.sisam.langtutor.tutor.cloze.ClozeClasses.Shape
import org.sisam.langtutor.tutor.drill.DrillDeck

/** Where a round's sentences come from — what the room's ViewModel is keyed on. */
sealed interface ClozeSource {
    val sessionKey: String

    data object All : ClozeSource {
        override val sessionKey: String get() = "cloze:all"
    }

    data class Theme(val id: String) : ClozeSource {
        override val sessionKey: String get() = "cloze:theme:$id"
    }

    data class Pack(val id: String) : ClozeSource {
        override val sessionKey: String get() = "cloze:pack:$id"
    }

    companion object {
        /** Inverse of [sessionKey], for restoring the chip after a rotation. */
        fun parse(key: String): ClozeSource = when {
            key.startsWith("cloze:theme:") -> Theme(key.removePrefix("cloze:theme:"))
            key.startsWith("cloze:pack:") -> Pack(key.removePrefix("cloze:pack:"))
            else -> All
        }
    }
}

/**
 * What kind of word the gap hides. PACK: a picture-pack word, so the icon is
 * a second key and the distractors are the pack itself. WORD: any other
 * open-class word, with distractors drawn from the same context in the bank.
 * CLOSED: a function word from [ClozeClasses], the owner's "the, a, etc".
 */
enum class ClozeKind { PACK, WORD, CLOSED }

/**
 * One admissible gap in a sentence, with every same-class word that could
 * stand in it. [pool] holds at least three keys, never the answer and never
 * a word of the sentence; [preferred] is the subset to draw from first (the
 * answer's own sub-class, so "my" faces "your" before it faces "this");
 * [variants] are inflections of the answer that may fill AT MOST one of the
 * three, and only where the position is verb-like.
 */
data class ClozeSlot(
    val index: Int,
    val kind: ClozeKind,
    val answer: String,
    val pool: List<String>,
    val preferred: List<String> = emptyList(),
    val variants: List<String> = emptyList(),
    val packWord: PackWord? = null,
    val packId: String? = null,
    val closedKind: Kind? = null,
    /**
     * The article before the gap joins it and every option carries its own:
     * "an elephant | a lion, a giraffe, an owl". Only for a noun after a/an
     * whose visible article would otherwise give the first letter away and
     * leave too few same-article distractors.
     */
    val joinArticle: Boolean = false,
)

/** One question as the room shows it. */
data class ClozeItem(
    /** The whole sentence: en, he, cues, level and theme all travel with it. */
    val sentence: PhraseSentence,
    /** Index into [words] of the hidden word. */
    val blank: Int,
    /** Exactly four display forms, shuffled. */
    val options: List<String>,
    /** Index into [options] of the right one. */
    val answer: Int,
    val kind: ClozeKind,
    /** The icon and Hebrew of a PACK gap; null for the other kinds. */
    val packWord: PackWord? = null,
    /** Every word index the gap covers: just [blank], or the article before
     *  it as well when the options carry their own (see [ClozeSlot.joinArticle]). */
    val span: IntRange = blank..blank,
) {
    val words: List<String> get() = ClozeDeck.words(sentence.en)
}

/**
 * Builds fill-the-gap items from content that already exists — the
 * phrasebank and the picture packs — with nothing authored beyond the class
 * table in [ClozeClasses] and the two-line pack template in
 * picture-packs.json (docs/fill-the-gap.md).
 *
 * The one principle everything here serves: the sentence's own Hebrew line is
 * the KEY that makes the answer unique. "I saw a ___ in the garden" has three
 * grammatical answers among lion, bee and cat; with ראיתי דבורה בגינה under
 * it, it has one. Distractors are therefore deliberately SAME-CLASS — the
 * learner must read for meaning, not eliminate by grammar — and every rule
 * below exists to remove the cases where the Hebrew line cannot decide:
 * a pair Hebrew renders with one word, an article the ב/ל prefix swallowed,
 * a subject the verb's agreement gives away.
 *
 * There is no part-of-speech tagger, so an open word's class is its
 * immediate context (the keys to its left and right) plus its morphological
 * shape, and its distractors are the OTHER words the bank attests in exactly
 * that context and shape. Function words come from the authored table; pack
 * words from their pack. Everything is built once from one pass over the
 * bank and is deterministic given the [Random] a round is drawn with.
 */
class ClozeDeck(
    sentences: List<PhraseSentence>,
    packs: List<PicturePack>,
    /** English → single Hebrew word, from content outside the bank (the
     *  curriculum's vocabulary), widening the same-meaning check. */
    extraGlosses: Map<String, String> = emptyMap(),
) {

    private class Token(
        val raw: String,
        val key: String,
        val shape: Shape,
        /** Trailing punctuation ends a clause for context purposes. */
        val clauseEnd: Boolean,
    )

    private class Analysed(val sentence: PhraseSentence, val tokens: List<Token>, val roles: List<Role>)

    /** A sentence and the gaps it admits. */
    class Entry(val sentence: PhraseSentence, val slots: List<ClozeSlot>)

    private data class Context(val left: String, val right: String, val shape: Shape)

    private val packOf: Map<String, Pair<PicturePack, PackWord>> = buildMap {
        for (pack in packs) for (word in pack.words) put(word.en, pack to word)
    }
    private val packById: Map<String, PicturePack> = packs.associateBy { it.id }
    private val numberWords: Set<String> =
        packById[ClozeClasses.NUMBERS_PACK]?.words?.map { it.en }?.toSet().orEmpty()

    private val analysed: List<Analysed> = sentences.map { analyse(it) }
    private val firstLevelKeys: Set<String> = analysed.flatMap { a -> a.tokens.map { it.key } }.toSet()
    private val contextPool: Map<Context, Set<String>>
    /** Words the bank uses as a predicate after a BE form ("is warm"): the
     *  adjectives, as far as a bank with no tagger can tell. */
    private val predicative: Set<String>
    /** Words attested where only a verb stands: after a subject pronoun, a
     *  modal, did/do/does, "to" or "not". */
    private val verbAttested: Set<String>
    /** Verbs attested directly before a determiner, a possessive, a number
     *  or a pack noun — the ones that take an object. */
    private val transitive: Set<String>
    /** Open words attested at the start of a line with a verb after them:
     *  the subjects, as opposed to "Good morning" and "Ice cream". */
    private val subjectAttested: Set<String>
    /** Which forms a verb is attested in: BASE, PAST or PARTICIPLE, read
     *  off the line's tense field and the auxiliary before it. */
    private val verbForms: Map<String, Set<VerbForm>>
    /** Nouns attested after a/an/every/each: the count nouns "much" cannot precede. */
    private val countNouns: Set<String>
    /** Nouns attested after every/last/next: the time nouns "this" turns into an adverbial. */
    private val timeNouns: Set<String>
    /** (modifier, head) pairs seen at least twice: fixed compounds like "teddy bear". */
    private val compounds: Map<Pair<String, String>, Int>
    /**
     * Names, as far as spelling can tell: keys the bank capitalises inside a
     * sentence at least as often as it writes them in lower case. "Dave" and
     * "Mom" qualify — Mom is written lower case exactly once in 3,108 lines
     * against 24 capitalised mid-sentence — while "bee" does not. A name may
     * still be a gap, because the line names it; it never joins another
     * line's pool, where it would be shown in a lower case the bank itself
     * would not write.
     *
     * Computed here rather than in `init`, because the pool-building pass
     * below asks [isOpenWord], which asks this.
     */
    private val nameLike: Set<String> = buildMap<String, IntArray> {
        for (a in analysed) for ((i, t) in a.tokens.withIndex()) {
            val first = t.raw.firstOrNull() ?: continue
            val counts = getOrPut(t.key) { IntArray(2) }
            if (first.isLowerCase()) counts[0]++ else if (i > 0) counts[1]++
        }
    }.filterKeys { it != "i" }.filterValues { it[1] > 0 && it[1] >= it[0] }.keys
    private val firstLevel: Map<String, Int>
    private val glosses: Map<String, Set<String>>
    private val bank: List<Entry>
    private val templates: Map<String, List<Entry>>

    private enum class VerbForm { BASE, PAST, PARTICIPLE }

    init {
        val pools = HashMap<Context, MutableSet<String>>()
        val adjectives = HashSet<String>()
        val verbs = HashSet<String>()
        val objectTakers = HashSet<String>()
        val subjects = HashSet<String>()
        val forms = HashMap<String, MutableSet<VerbForm>>()
        val counted = HashSet<String>()
        val timed = HashSet<String>()
        val bigrams = HashMap<Pair<String, String>, Int>()
        val levels = HashMap<String, Int>()
        val gloss = HashMap<String, MutableSet<String>>()
        for (a in analysed) {
            for ((i, t) in a.tokens.withIndex()) {
                levels.merge(t.key, a.sentence.level) { old, new -> minOf(old, new) }
                val prev = a.tokens.getOrNull(i - 1)?.takeIf { !it.clauseEnd }?.key
                val next = a.tokens.getOrNull(i + 1)?.takeIf { !t.clauseEnd }
                if (isOpenWord(a, i)) {
                    val ctx = context(a, i)
                    pools.getOrPut(ctx) { mutableSetOf() }.add(t.key)
                    if (ctx.left in ClozeClasses.BE_FORMS && ctx.right == END) adjectives += t.key
                    if (ClozeClasses.verbLike(prev)) {
                        verbs += t.key
                        expectedForm(prev, a.sentence.tense)
                            ?.takeIf { !(it == VerbForm.BASE && ClozeClasses.isIrregularNonBase(t.key)) }
                            ?.let { forms.getOrPut(t.key) { mutableSetOf() }.add(it) }
                        if (next != null && (isDeterminerish(next.key) || packLemma(next.key) != null)) objectTakers += t.key
                    }
                    if (prev != null && prev in setOf("a", "every", "each")) counted += t.key
                    if (prev != null && prev in setOf("every", "last", "next")) timed += t.key
                    if (next != null && a.roles[i + 1] == Role.Open && next.shape in setOf(Shape.BASE, Shape.S)) {
                        bigrams.merge(t.key to next.key, 1, Int::plus)
                    }
                }
            }
            // A cue is a gloss when its Hebrew side is ONE word and its
            // English side holds one open word once the function words are
            // set aside: "is warm" → חם, "The bread" → הלחם. "a red flower"
            // → פרח אדום is two Hebrew words and glosses nothing, because a
            // phrase for `red` would never match another line's `red`.
            val he = a.sentence.he.split(' ')
            for (cue in a.sentence.align.orEmpty()) {
                if (cue.en.size != 2 || cue.he.size != 2 || cue.he[0] != cue.he[1]) continue
                val open = (cue.en[0]..cue.en[1]).filter { it in a.tokens.indices && isOpenWord(a, it) }
                if (open.size != 1) continue
                val hw = he.getOrNull(cue.he[0]) ?: continue
                gloss.getOrPut(a.tokens[open[0]].key) { mutableSetOf() }.addAll(glossKeys(hw))
            }
        }
        for ((_, pw) in packOf.values) {
            if (' ' !in pw.he) gloss.getOrPut(pw.en) { mutableSetOf() }.addAll(glossKeys(pw.he))
        }
        for ((en, he) in extraGlosses) {
            if (' ' !in he && he.isNotBlank()) gloss.getOrPut(en.lowercase()) { mutableSetOf() }.addAll(glossKeys(he))
        }
        // A second pass for subjects: a sentence-initial open word followed
        // by something only a verb can be is a subject, not "Good" or "Ice".
        for (a in analysed) {
            val first = a.tokens.firstOrNull() ?: continue
            val second = a.tokens.getOrNull(1) ?: continue
            if (!isOpenWord(a, 0) || first.clauseEnd) continue
            val k = second.key
            if (k in verbs || k in ClozeClasses.BE_FORMS || k in ClozeClasses.AGREEMENT ||
                Kind.MODAL in ClozeClasses.kindsOf(k) || second.shape == Shape.ED || second.shape == Shape.S
            ) subjects += first.key
        }
        contextPool = pools
        predicative = adjectives
        verbAttested = verbs
        transitive = objectTakers
        subjectAttested = subjects
        verbForms = forms
        countNouns = counted
        timeNouns = timed
        compounds = bigrams
        firstLevel = levels
        glosses = gloss
        bank = analysed
            .filter { it.sentence.theme !in EXCLUDED_THEMES }
            .map { Entry(it.sentence, slotsOf(it)) }
        templates = packs.filter { it.cloze != null && it.id != ClozeClasses.MATHS_PACK }
            .associate { pack -> pack.id to templateEntries(pack) }
    }

    // ---- tokens and roles -------------------------------------------------

    private fun analyse(sentence: PhraseSentence): Analysed {
        val raws = words(sentence.en)
        val tokens = raws.mapIndexed { i, raw ->
            val stripped = raw.trimEnd(*TRAILING)
            val k = key(raw)
            Token(
                raw = raw,
                key = if (k == "an") "a" else k,
                shape = ClozeClasses.shapeOf(k, stripped.firstOrNull()?.isUpperCase() == true, i == 0),
                clauseEnd = stripped.length != raw.length,
            )
        }
        val keys = tokens.map { it.key }
        val shapes = tokens.map { it.shape }
        val clauseStarts = tokens.indices.filter { it > 0 && tokens[it - 1].clauseEnd }.toSet()
        val roles = tokens.indices.map { ClozeClasses.roleOf(keys, shapes, it, sentence.he, clauseStarts) }
        return Analysed(sentence, tokens, roles)
    }

    private fun isOpenWord(a: Analysed, i: Int): Boolean {
        val t = a.tokens[i]
        return a.roles[i] == Role.Open && t.shape != Shape.PROPER && '\'' !in t.key && t.key.isNotEmpty() &&
            t.key !in nameLike &&
            // A number is a quantifier in disguise: it never fills, and never
            // offers itself for, a noun or verb gap.
            t.key !in numberWords && t.key !in ClozeClasses.NUMBER_WORDS
    }

    private fun context(a: Analysed, i: Int): Context {
        val left = when {
            i == 0 || a.tokens[i - 1].clauseEnd -> START
            // "to order soup" and "go to bed" must never share a pool.
            a.tokens[i - 1].key == "to" && a.roles[i - 1] == Role.Never -> TO_INF
            else -> a.tokens[i - 1].key
        }
        val right = when {
            i == a.tokens.lastIndex || a.tokens[i].clauseEnd -> END
            a.roles[i + 1] == Role.Open -> OPEN
            else -> a.tokens[i + 1].key
        }
        return Context(left, right, a.tokens[i].shape)
    }

    private fun isDeterminerish(key: String): Boolean =
        ClozeClasses.kindsOf(key).any { it in ClozeClasses.DETERMINERS || it == Kind.QUANTIFIER } ||
            key in numberWords || key in ClozeClasses.NUMBER_WORDS

    /** The verb form a verb-like position asks for, from the auxiliary to
     *  its left and, after a bare subject, the line's tense. */
    private fun expectedForm(left: String?, tense: String): VerbForm? = when {
        left == null -> null
        left in setOf("have", "has", "had") -> VerbForm.PARTICIPLE
        left in setOf("did", "do", "does", "to", "not") || Kind.MODAL in ClozeClasses.kindsOf(left) -> VerbForm.BASE
        Kind.SUBJECT in ClozeClasses.kindsOf(left) ->
            if (tense.startsWith("past") || tense == "conditional-second" || tense == "conditional-third") VerbForm.PAST else VerbForm.BASE
        else -> null
    }

    // ---- slots ------------------------------------------------------------

    fun slots(sentence: PhraseSentence): List<ClozeSlot> = slotsOf(analyse(sentence))

    private fun slotsOf(a: Analysed): List<ClozeSlot> {
        val out = mutableListOf<ClozeSlot>()
        val present = presentKeys(a)
        for (i in a.tokens.indices) {
            val t = a.tokens[i]
            if (t.key.isEmpty() || '\'' in t.key) continue
            // A word that occurs twice in the line can be copied from it;
            // a second "the" is the one exception, since my/this stay live.
            if (t.key !in ARTICLES && a.tokens.count { it.key == t.key } > 1) continue
            val pack = packSlot(a, i, present)?.also { out += it }
            when (val role = a.roles[i]) {
                // A pack word keeps its pack: the icon and the pack's own
                // words are a better class than the context pool could be.
                Role.Open -> if (pack == null) wordSlot(a, i, present)?.let { out += it }
                is Role.Closed -> closedSlot(a, i, role.kind, present)?.let { out += it }
                Role.Never -> Unit
            }
        }
        return out
    }

    /** Every key in the sentence and its crude singular and plural, so a
     *  distractor can never be a word the learner could copy from the line. */
    private fun presentKeys(a: Analysed): Set<String> = buildSet {
        for (t in a.tokens) {
            add(t.key)
            add(lemma(t.key))
            add(ClozeClasses.plural(t.key))
        }
    }

    private fun lemma(key: String): String {
        packLemma(key)?.let { return it }
        return when {
            key.endsWith("ies") && key.length > 4 -> key.dropLast(3) + "y"
            // horses → horse, but boxes → box: the bank says which.
            key.endsWith("es") && key.length > 4 -> if (key.dropLast(1) in firstLevelKeys) key.dropLast(1) else key.dropLast(2)
            key.endsWith("s") && key.length > 3 && !key.endsWith("ss") -> key.dropLast(1)
            else -> key
        }
    }

    private fun packSlot(a: Analysed, i: Int, present: Set<String>): ClozeSlot? {
        val t = a.tokens[i]
        if (t.shape == Shape.PROPER) return null
        val lemma = packLemma(t.key) ?: return null
        val (pack, word) = packOf.getValue(lemma)
        if (lemma == "one" || pack.id == ClozeClasses.MATHS_PACK) return null
        val next = a.tokens.getOrNull(i + 1)
        // A noun followed by another open noun is the modifier of a compound
        // — "fish tank", "goat milk" — and the icon would then lie.
        if (pack.id != ClozeClasses.NUMBERS_PACK && next != null && !t.clauseEnd &&
            a.roles[i + 1] == Role.Open && next.shape in setOf(Shape.BASE, Shape.S) &&
            next.key !in verbAttested && (compounds[t.key to next.key] ?: 0) >= 2
        ) {
            return null
        }
        // A fixed compound ("teddy bear", seen five times) keeps its head:
        // "teddy lion" is not a thing, so the gap is not a meaning choice.
        val prev = a.tokens.getOrNull(i - 1)
        if (prev != null && !prev.clauseEnd && a.roles[i - 1] == Role.Open && prev.shape == Shape.BASE &&
            prev.key !in verbAttested && prev.key !in predicative && (compounds[prev.key to lemma] ?: 0) >= 2
        ) {
            return null
        }
        // "my heart" is the organ, not the shape.
        if (pack.id == "shapes" && prev != null && Kind.POSSESSIVE in ClozeClasses.kindsOf(prev.key)) return null
        // The line's Hebrew must actually contain the pack word: otherwise it
        // is the town square (כיכר) or an idiom, and the position falls back
        // to an ordinary WORD gap with no icon.
        if (!hebrewMentions(a.sentence.he, word)) return null
        val plural = isPluralUse(a, i, lemma) || hebrewPlural(a.sentence.he, word)
        val visibleArticle = visibleArticleBefore(a, i)
        val base = pack.words.asSequence()
            .map { it.en }
            .filter { it != lemma && it !in present && ClozeClasses.plural(it) !in present }
            .filter { !sameMeaning(lemma, it) }
            .filter { pack.id != ClozeClasses.NUMBERS_PACK || it != "one" || next?.shape != Shape.S }
            .map { if (plural) ClozeClasses.plural(it) else it }
            .toList()
        val sameArticle = base.filter { visibleArticle == null || ClozeClasses.articleFor(it) == visibleArticle }
        // "I see an ___": the visible article names the first letter. When
        // the pack cannot field three same-article words, the article joins
        // the gap instead and every option brings its own.
        val join = visibleArticle != null && sameArticle.size < MIN_DISTRACTORS && base.size >= MIN_DISTRACTORS
        val candidates = if (join) base else sameArticle
        if (candidates.size < MIN_DISTRACTORS) return null
        return ClozeSlot(
            index = i, kind = ClozeKind.PACK, answer = t.key, pool = candidates,
            packWord = word, packId = pack.id, joinArticle = join,
        )
    }

    private fun packLemma(key: String): String? {
        if (key in packOf) return key
        return packOf.keys.firstOrNull { ClozeClasses.plural(it) == key }
    }

    /** Plural for an invariant word (sheep, fish) is read off its neighbours;
     *  for everything else the spelling says. */
    private fun isPluralUse(a: Analysed, i: Int, lemma: String): Boolean {
        val key = a.tokens[i].key
        if (ClozeClasses.plural(lemma) != lemma) return key != lemma
        val next = a.tokens.getOrNull(i + 1)?.key
        val prev = a.tokens.getOrNull(i - 1)?.key
        return next in setOf("are", "were", "have", "do") ||
            (prev != null && prev in numberWords && prev != "one") ||
            prev in ClozeClasses.PLURAL_ONLY
    }

    /** "a" or "an" as it actually appears before the gap, or null: the
     *  visible article must not give the first letter of the answer away. */
    private fun visibleArticleBefore(a: Analysed, i: Int): String? =
        a.tokens.getOrNull(i - 1)?.takeIf { it.key == "a" }?.let { key(it.raw) }

    private fun wordSlot(a: Analysed, i: Int, present: Set<String>): ClozeSlot? {
        val t = a.tokens[i]
        if (t.shape == Shape.PROPER || t.key in numberWords) return null
        val ctx = context(a, i)
        val raw = contextPool[ctx] ?: return null
        val left = a.tokens.getOrNull(i - 1)?.key
        val visibleArticle = visibleArticleBefore(a, i)
        // An adjective is a poor option for a noun's gap and vice versa;
        // "is warm" attestation is the nearest thing to a tag the bank has.
        val adjective = t.key in predicative
        val filtered = raw.asSequence()
            // Its plural too: "leaves" in the line must not offer "leaf".
            // Only here — in a CLOSED pool the naive plural of "i" is "is",
            // which would ban the pronoun from every line that contains it.
            .filter { it != t.key && it !in present && lemma(it) !in present && ClozeClasses.plural(it) !in present }
            .filter { withinLevel(it, a.sentence.level) }
            .filter { !sameMeaning(t.key, it) }
            .filter { it !in ClozeClasses.NEVER_DISTRACTOR }
            .filter { (it in predicative) == adjective }
            .filter { visibleArticle == null || ClozeClasses.articleFor(it) == visibleArticle }
            .toList()
        val next = a.tokens.getOrNull(i + 1)
        val verbLike = ClozeClasses.verbLike(left) && left !in ClozeClasses.BE_FORMS
        // The first half of a noun compound ("mixing bowl", "cookie shapes")
        // is a gap only when the Hebrew carries it; הקערה says nothing about
        // mixing, so any modifier would fit.
        val modifierPosition = !verbLike && next != null && !t.clauseEnd && a.roles[i + 1] == Role.Open &&
            next.shape in setOf(Shape.BASE, Shape.S) && next.key !in verbAttested &&
            t.shape in setOf(Shape.BASE, Shape.ING) && !adjective
        if (modifierPosition && glosses[t.key] != null && !hebrewMentionsGloss(a.sentence.he, t.key)) return null
        // The answer's own family (saw | see, seen) is judged by the Hebrew
        // tense, so it is set aside before the form filters and one member
        // may come back below.
        val (family, others) = filtered.partition { ClozeClasses.sameStem(t.key, it) }
        // "go to ___" over ללכת לישון: the Hebrew names sleep, so "sleep" is
        // not a wrong answer for "bed" — never offered. The answer's own
        // family is exempt: ראיתי names "see" too, and that is the point.
        var narrowed = others.filter { glosses[it] == null || !hebrewMentionsGloss(a.sentence.he, it) }
        if (verbLike) {
            // The slot's tense forbids some forms outright (a past after
            // "did", a base after "I" in a past line): those are not rivals.
            val expected = expectedForm(left, a.sentence.tense)
            if (expected != null) narrowed = narrowed.filter { verbForms[it]?.contains(expected) == true }
            // Before an object, only a verb that takes one.
            if (next != null && !t.clauseEnd && (isDeterminerish(next.key) || (a.roles[i + 1] == Role.Open && next.key !in verbAttested))) {
                narrowed = narrowed.filter { it in transitive }
            }
        }
        if (ctx.left == START && ctx.right == OPEN) narrowed = narrowed.filter { it in subjectAttested }
        val plain = narrowed
        val allowed = if (verbLike) family else emptyList()
        if (plain.size < MIN_DISTRACTORS - 1 || plain.size + minOf(1, allowed.size) < MIN_DISTRACTORS) return null
        return ClozeSlot(index = i, kind = ClozeKind.WORD, answer = t.key, pool = plain, variants = allowed)
    }

    private fun closedSlot(a: Analysed, i: Int, kind: Kind, present: Set<String>): ClozeSlot? {
        val t = a.tokens[i]
        if (t.key == "it") return null // dummy "it" has no Hebrew subject to key it
        val next = a.tokens.getOrNull(i + 1)
        val nextShape = next?.shape
        val prev = a.tokens.getOrNull(i - 1)?.takeIf { !it.clauseEnd }?.key
        val he = a.sentence.he
        if (!admitsClosed(a, i, kind)) return null
        val members: List<String> = if (kind in ClozeClasses.DETERMINERS) {
            ClozeClasses.DETERMINERS.flatMap { ClozeClasses.MEMBERS.getValue(it) }
        } else {
            ClozeClasses.MEMBERS.getValue(kind)
        }
        var candidates = members.asSequence()
            .filter { it != t.key && it !in present }
            .filter { withinLevel(it, a.sentence.level) }
            .filter { !sameMeaning(t.key, it) }
            .filter { it !in ClozeClasses.NEVER_DISTRACTOR }
        if (kind in ClozeClasses.DETERMINERS || kind == Kind.QUANTIFIER) {
            candidates = if (nextShape == Shape.S) {
                candidates.filter { it !in ClozeClasses.SINGULAR_ONLY }
            } else {
                candidates.filter { it !in ClozeClasses.PLURAL_ONLY }
            }
            // The same admission an answer gets: more/most only before a
            // plural, much never before a count noun.
            if (nextShape != Shape.S) candidates = candidates.filter { it != "more" && it != "most" }
            if (next != null && next.key in countNouns) candidates = candidates.filter { it != "much" }
        }
        if (kind == Kind.TIME_ADVERB) {
            val tense = a.sentence.tense
            val past = tense.startsWith("past") || tense == "conditional-second" || tense == "conditional-third"
            val perfect = "perfect" in tense
            candidates = candidates.filter {
                !((past || perfect) && it in FUTURE_ADVERBS) && !((!past || perfect) && it in PAST_ADVERBS)
            }
        }
        if (kind == Kind.CONJUNCTION) {
            val bothOpen = i > 0 && a.roles[i - 1] == Role.Open && next != null && a.roles[i + 1] == Role.Open
            candidates = when {
                // A line opens with a clause, and two adjectives are joined
                // by a coordinator: the other half of the class never fits.
                i == 0 -> candidates.filter { it !in ClozeClasses.COORDINATORS }
                bothOpen -> candidates.filter { it in ClozeClasses.COORDINATORS }
                else -> candidates
            }
        }
        if (kind == Kind.PLACE_PREP && t.key in ClozeClasses.BET_FAMILY) {
            // Bare ב in the Hebrew: nothing else in the family is a rival.
            val marker = ClozeClasses.BET_MARKERS[t.key]
            if (marker == null || marker !in he) candidates = candidates.filter { it !in ClozeClasses.BET_FAMILY }
        }
        if (kind == Kind.MODAL && i == 0 && a.sentence.en.trimEnd().endsWith("?") && t.key in ClozeClasses.REQUEST_MODALS) {
            candidates = candidates.filter { it !in ClozeClasses.REQUEST_MODALS }
        }
        if (kind in ClozeClasses.DETERMINERS && t.key in ARTICLES) {
            // a ↔ the only when the Hebrew line PROVES it: a cue whose span
            // carries ה for "the", or carries no ה/ב/ל/כ-initial word for
            // "a", and never after a preposition whose ב/ל absorbs it.
            val other = if (t.key == "a") "the" else "a"
            if (!articleProven(a, i)) candidates = candidates.filter { it != other }
        }
        if (kind == Kind.SUBJECT) {
            val allowed = agreeingSubjects(a, i)
            candidates = candidates.filter { it in allowed }
        }
        val pool = candidates.toList()
        if (pool.size < MIN_DISTRACTORS) return null
        val preferred = if (kind in ClozeClasses.DETERMINERS) {
            val own = ClozeClasses.kindsOf(t.key).firstOrNull { it in ClozeClasses.DETERMINERS }
            pool.filter { own != null && own in ClozeClasses.kindsOf(it) }
        } else {
            emptyList()
        }
        return ClozeSlot(
            index = i, kind = ClozeKind.CLOSED, answer = t.key, pool = pool,
            preferred = preferred, closedKind = kind,
        )
    }

    /**
     * Whether a function word at [i] is a MEANING gap at all: the cases the
     * item audit found where the Hebrew line carries nothing that decides it,
     * so the learner would be choosing by grammar or by elimination.
     */
    private fun admitsClosed(a: Analysed, i: Int, kind: Kind): Boolean {
        val t = a.tokens[i]
        val next = a.tokens.getOrNull(i + 1)
        val prev = a.tokens.getOrNull(i - 1)?.takeIf { !it.clauseEnd }?.key
        val he = a.sentence.he
        val heWords = he.split(' ').map { it.trimEnd(*TRAILING) }
        return when (kind) {
            Kind.SUBJECT -> {
                // Hebrew has two everyday impersonals, and in both the line
                // names nobody, so you/we/they all read it correctly.
                //
                // The bare present plural after a conditional: "If ___ mix
                // flour…" over אם מערבבים. A plural verb form ends in ־ים;
                // the future never does (it ends in ־ו), so the only thing
                // to exclude is a definite plural NOUN, which starts with ה.
                val conditional = heWords.zipWithNext().any { (c, v) ->
                    c in CONDITIONALS && impersonalPlural(v)
                } || heWords.any { w -> w.length > 5 && w.startsWith("כש") && impersonalPlural(w.substring(2)) }
                // And the modal with an infinitive: "You must wear a helmet"
                // over חייבים לחבוש — כדאי and אסור take no subject at all.
                val modal = heWords.any { it.trimStart(*CLITIC_CHARS) in IMPERSONAL_MODALS }
                // A reflexive elsewhere in the line fixes the subject.
                !conditional && !modal && a.tokens.none { it.key in ClozeClasses.REFLEXIVES }
            }
            // "your teeth" over צחצחת שיניים: no possession in the Hebrew.
            Kind.POSSESSIVE -> ClozeClasses.HEBREW_POSSESSION.any { sig -> heWords.any { it.startsWith(sig) || it == sig } }
            // "so ___ foam" and "such ___ tall tree" are fixed frames. (A
            // "the" whose ה the Hebrew hides stays: "in ___ garden" over
            // בגינה is still decided by reading — no שלי, no הזה — and it
            // is the owner's own example.)
            Kind.ARTICLE -> prev !in FRAME_OPENERS
            Kind.QUANTIFIER -> prev !in FRAME_OPENERS && (prev == null || Kind.SUBJECT !in ClozeClasses.kindsOf(prev))
            // "this week" is an adverbial the Hebrew writes as השבוע.
            Kind.DEMONSTRATIVE -> next == null || next.key !in timeNouns
            else -> true
        }
    }

    /** A plural verb form with nobody behind it: ends in ־ים and is not a
     *  definite noun (ה־). The future is no risk — it ends in ־ו. */
    private fun impersonalPlural(word: String): Boolean =
        word.length > 3 && word.endsWith("ים") && !word.startsWith("ה")

    private fun agreeingSubjects(a: Analysed, i: Int): Set<String> {
        // "Are ___ taking pictures?": in a question the verb stands BEFORE
        // the subject, so the word on the left constrains it too.
        val before = a.tokens.getOrNull(i - 1)?.let { ClozeClasses.AGREEMENT[it.key] } ?: ALL_SUBJECTS
        var j = i + 1
        while (j < a.tokens.size && isAdverb(a.tokens[j].key)) j++
        val verb = a.tokens.getOrNull(j) ?: return before
        ClozeClasses.AGREEMENT[verb.key]?.let { return it intersect before }
        if (a.roles[j] == Role.Open) {
            val after = when (verb.shape) {
                Shape.S -> setOf("he", "she", "it")
                Shape.BASE -> setOf("i", "you", "we", "they")
                else -> ALL_SUBJECTS
            }
            return after intersect before
        }
        return before
    }

    private fun isAdverb(key: String): Boolean {
        val kinds = ClozeClasses.kindsOf(key)
        return Kind.TIME_ADVERB in kinds || Kind.FREQUENCY in kinds || Kind.DEGREE in kinds
    }

    private fun articleProven(a: Analysed, i: Int): Boolean {
        val prev = a.tokens.getOrNull(i - 1)?.key
        if (prev != null && prev in ClozeClasses.ABSORBING_PREPS) return false
        val cue = a.sentence.align?.firstOrNull { it.en.size == 2 && i in it.en[0]..it.en[1] } ?: return false
        if (cue.he.size != 2) return false
        val he = a.sentence.he.split(' ')
        val span = (cue.he[0]..cue.he[1]).mapNotNull { he.getOrNull(it)?.trimEnd(*TRAILING) }
        if (span.isEmpty()) return false
        return if (a.tokens[i].key == "the") {
            span.any { it.startsWith("ה") }
        } else {
            span.none { it.firstOrNull() in HIDING_PREFIXES }
        }
    }

    // ---- shared filters ---------------------------------------------------

    /** "Relevant words" means words the learner has met: nothing that first
     *  appears more than one Level above the sentence. */
    private fun withinLevel(key: String, level: Int): Boolean {
        val first = firstLevel[key] ?: return false
        return first <= level + 1
    }

    private fun sameMeaning(answer: String, candidate: String): Boolean {
        if (ClozeClasses.SAME_MEANING.any { answer in it && candidate in it }) return true
        val ga = glosses[answer] ?: return false
        val gc = glosses[candidate] ?: return false
        return ga.any { it in gc }
    }

    // ---- templates --------------------------------------------------------

    /**
     * Pack words the bank never uses (tiger, zebra, rectangle…) still get a
     * turn as the answer through the pack's own template line, composed with
     * the pack word's Hebrew. The article is spelled by rule per word; for a
     * vowel-initial word its visible "an" would name the first letter, so
     * there the article joins the gap and every option brings its own.
     */
    private fun templateEntries(pack: PicturePack): List<Entry> {
        val template = pack.cloze ?: return emptyList()
        return pack.words.mapNotNull { word ->
            val en = template.en
                .replace("a ___", "${ClozeClasses.articleFor(word.en)} ${word.en}")
                .replace("___", word.en)
            val he = template.he.replace("___", word.he)
            val sentence = PhraseSentence(
                id = "pack:${pack.id}:${word.en}", level = 1, tense = "present-simple",
                frame = "pack-template", en = en, he = he, theme = pack.id,
            )
            val a = analyse(sentence)
            val i = a.tokens.indexOfFirst { it.key == word.en }
            if (i < 0) return@mapNotNull null
            val visibleArticle = visibleArticleBefore(a, i)
            val base = pack.words.map { it.en }.filter { it != word.en }
            val sameArticle = base.filter { visibleArticle == null || ClozeClasses.articleFor(it) == visibleArticle }
            val join = visibleArticle != null && sameArticle.size < MIN_DISTRACTORS
            val pool = if (join) base else sameArticle
            if (pool.size < MIN_DISTRACTORS) return@mapNotNull null
            Entry(
                sentence,
                listOf(ClozeSlot(i, ClozeKind.PACK, word.en, pool, packWord = word, packId = pack.id, joinArticle = join)),
            )
        }
    }

    // ---- rounds -----------------------------------------------------------

    /** Sentences a source can serve at [learnerLevel] — what the chip row
     *  shows, so a topic with nothing to fill in is not offered. */
    fun poolSize(source: ClozeSource, learnerLevel: Int): Int = entries(source, learnerLevel).size

    private fun entries(source: ClozeSource, learnerLevel: Int): List<Entry> = when (source) {
        ClozeSource.All -> bank.filter { it.sentence.level in DrillDeck.levelWindow(learnerLevel) && it.slots.isNotEmpty() }
        is ClozeSource.Theme -> bank.filter {
            it.sentence.theme == source.id && it.sentence.level in DrillDeck.levelWindow(learnerLevel) && it.slots.isNotEmpty()
        }
        is ClozeSource.Pack -> {
            if (source.id == ClozeClasses.MATHS_PACK) {
                emptyList()
            } else {
                // The pack is the thing under test and the sentence is only
                // its vehicle, so the whole ladder below the learner serves,
                // and the bank's own lines come before the template's.
                val fromBank = bank.mapNotNull { e ->
                    val packSlots = e.slots.filter { it.kind == ClozeKind.PACK && it.packId == source.id }
                    if (packSlots.isEmpty() || e.sentence.level > learnerLevel) null else Entry(e.sentence, packSlots)
                }
                fromBank + templates[source.id].orEmpty()
            }
        }
    }

    /**
     * A round: [size] items, distinct sentences, distinct answers, no kind
     * more than [MAX_PER_KIND] times, and — for the whole bank — as many
     * topics as it can. [avoid] maps a sentence id to the gap it wore last
     * time, so a revisited line blanks a different word.
     */
    fun round(
        source: ClozeSource,
        learnerLevel: Int,
        random: Random,
        size: Int = ROUND_SIZE,
        avoid: Map<String, Int> = emptyMap(),
    ): List<ClozeItem> {
        val own = entries(source, learnerLevel)
        val candidates = when (source) {
            is ClozeSource.Theme -> {
                // The theme first and the rest of the window behind it: a
                // theme with a dozen lines can still stall on the distinct-
                // answer rule, and a short round is the visible cost.
                own.shuffled(random) + entries(ClozeSource.All, learnerLevel)
                    .filter { it.sentence.theme != source.id }
                    .shuffled(random)
            }
            is ClozeSource.Pack -> {
                val (fromBank, fromTemplate) = own.partition { !it.sentence.id.startsWith("pack:") }
                fromBank.shuffled(random) + fromTemplate.shuffled(random)
            }
            ClozeSource.All -> own.shuffled(random)
        }
        val picked = mutableListOf<ClozeItem>()
        val usedText = mutableSetOf<String>()
        val usedAnswers = mutableSetOf<String>()
        val kindCount = mutableMapOf<ClozeKind, Int>()
        val usedThemes = mutableSetOf<String>()
        // Two passes over the whole bank: first one item per theme, then
        // whatever is left, so six items are six topics whenever they can be.
        val passes = if (source == ClozeSource.All) 2 else 1
        for (pass in 0 until passes) {
            for (entry in candidates) {
                if (picked.size >= size) break
                if (entry.sentence.en in usedText) continue
                if (pass == 0 && passes == 2 && entry.sentence.theme in usedThemes) continue
                val slot = chooseSlot(entry, random, usedAnswers, kindCount, avoid, capKinds = source !is ClozeSource.Pack)
                    ?: continue
                picked += item(entry.sentence, slot, random)
                usedText += entry.sentence.en
                usedAnswers += slot.answer
                usedThemes += entry.sentence.theme
                kindCount[slot.kind] = (kindCount[slot.kind] ?: 0) + 1
            }
        }
        // A last pass with the variety rules dropped. A round of six that
        // repeats an answer teaches more than a round of five, and a thin
        // pack at Level 1 — numbers has only a handful of distinct answers
        // that low — would otherwise always come back short.
        if (picked.size < size) {
            for (entry in candidates) {
                if (picked.size >= size) break
                if (entry.sentence.en in usedText) continue
                val slot = chooseSlot(entry, random, emptySet(), emptyMap(), avoid, capKinds = false) ?: continue
                picked += item(entry.sentence, slot, random)
                usedText += entry.sentence.en
            }
        }
        return picked
    }

    /**
     * One item for one sentence, or null when it has no gap: the round's own
     * choice rule, exposed for a caller that already knows which line it
     * wants. [avoid] is the gap this line wore last time.
     */
    fun itemFor(sentence: PhraseSentence, random: Random, avoid: Int? = null): ClozeItem? {
        val entry = Entry(sentence, slots(sentence))
        val slot = chooseSlot(
            entry, random, emptySet(), emptyMap(),
            if (avoid == null) emptyMap() else mapOf(sentence.id to avoid), capKinds = false,
        ) ?: return null
        return item(sentence, slot, random)
    }

    private fun chooseSlot(
        entry: Entry,
        random: Random,
        usedAnswers: Set<String>,
        kindCount: Map<ClozeKind, Int>,
        avoid: Map<String, Int>,
        /** False in pack mode, where every gap is a pack gap by design. */
        capKinds: Boolean,
    ): ClozeSlot? {
        var open = entry.slots.filter {
            it.answer !in usedAnswers && (!capKinds || (kindCount[it.kind] ?: 0) < MAX_PER_KIND)
        }
        val worn = avoid[entry.sentence.id]
        if (worn != null && open.any { it.index != worn }) open = open.filter { it.index != worn }
        if (open.isEmpty()) return null
        // Content gaps carry the meaning the Hebrew keys; function-word gaps
        // are a steady minority so that "the" (2,220 tokens) never dominates.
        val kinds = open.map { it.kind }.distinct()
        val weights = kinds.map { it to (if (it == ClozeKind.CLOSED) 1 else 2) }
        var pick = random.nextInt(weights.sumOf { it.second })
        val kind = weights.first { (_, w) -> pick -= w; pick < 0 }.first
        val ofKind = open.filter { it.kind == kind }
        return ofKind[random.nextInt(ofKind.size)]
    }

    /** Three distractors and the answer, shuffled, in display form. */
    fun item(sentence: PhraseSentence, slot: ClozeSlot, random: Random): ClozeItem {
        val distractors = mutableListOf<String>()
        if (slot.variants.isNotEmpty()) distractors += slot.variants[random.nextInt(slot.variants.size)]
        // One member per same-meaning set in a row: may and might together
        // would be two options with one Hebrew.
        fun fresh(c: String) = c !in distractors && distractors.none { sameMeaning(it, c) }
        for (c in slot.preferred.shuffled(random)) if (distractors.size < MIN_DISTRACTORS && fresh(c)) distractors += c
        for (c in slot.pool.shuffled(random)) if (distractors.size < MIN_DISTRACTORS && fresh(c)) distractors += c
        for (c in slot.pool.shuffled(random)) if (distractors.size < MIN_DISTRACTORS && c !in distractors) distractors += c
        val keys = (distractors + slot.answer).shuffled(random)
        val words = words(sentence.en)
        val nextKey = words.getOrNull(slot.index + 1)?.let { key(it) }
        val span = if (slot.joinArticle) (slot.index - 1)..slot.index else slot.index..slot.index
        val capitalise = span.first == 0
        return ClozeItem(
            sentence = sentence,
            blank = slot.index,
            options = keys.map {
                if (slot.joinArticle) {
                    val article = display("a", capitalise, it)
                    "$article $it"
                } else {
                    display(it, capitalise, nextKey)
                }
            },
            answer = keys.indexOf(slot.answer),
            kind = slot.kind,
            packWord = slot.packWord,
            span = span,
        )
    }

    /** For an invariant word (fish, sheep) the Hebrew shows the number the
     *  English hides: דגים is plural where דג is not. */
    private fun hebrewPlural(he: String, word: PackWord): Boolean {
        val forms = ClozeClasses.HEBREW_FORMS[word.en] ?: return false
        if (ClozeClasses.plural(word.en) != word.en || forms.size < 2) return false
        val variants = he.split(' ').flatMap { hebrewVariants(it, minLength = 1) }
        val singular = normaliseHebrew(forms[0])
        return variants.any { v -> forms.drop(1).any { f -> v.startsWith(normaliseHebrew(f)) } && !v.startsWith(singular + ".") && v != singular }
    }

    /** Does the line's Hebrew contain any known gloss of [key]? */
    private fun hebrewMentionsGloss(he: String, key: String): Boolean {
        val stems = glosses[key].orEmpty().map { it.take(3) }.filter { it.isNotEmpty() }
        val variants = he.split(' ').flatMap { hebrewVariants(it, minLength = 1) }
        return variants.any { v -> stems.any { s -> v.startsWith(s) } }
    }

    private fun hebrewMentions(he: String, word: PackWord): Boolean {
        val forms = ClozeClasses.HEBREW_FORMS[word.en] ?: listOf(word.he.substringBefore(' '))
        val stems = forms.map { normaliseHebrew(it.substringBefore(' ')).take(3) }.filter { it.isNotEmpty() }
        val variants = he.split(' ').flatMap { hebrewVariants(it, minLength = 1) }
        return variants.any { v -> stems.any { s -> v.startsWith(s) } }
    }

    companion object {
        const val ROUND_SIZE = 6
        const val OPTIONS = 4
        const val MIN_DISTRACTORS = OPTIONS - 1
        const val MAX_PER_KIND = 3
        const val BLANK = "____"

        /** Their Hebrew is an equivalent saying, not a translation of the
         *  words, so the key the whole room rests on does not exist there. */
        val EXCLUDED_THEMES: Set<String> = setOf("idioms")

        private const val START = " START"
        private const val END = " END"
        private const val OPEN = " OPEN"
        private const val TO_INF = " TO"
        private val TRAILING = charArrayOf('.', ',', '!', '?', ';', ':')
        private val ARTICLES = setOf("a", "the")
        private val FRAME_OPENERS = setOf("so", "such", "too", "how")
        private val CONDITIONALS = setOf("אם", "כש", "כאשר")
        /** Modals that take an infinitive and no subject: the line says what
         *  must be done, never who does it. */
        private val IMPERSONAL_MODALS = setOf("חייבים", "כדאי", "אסור", "אפשר", "צריך", "מותר", "חשוב")
        private val CLITIC_CHARS = charArrayOf('ו', 'ש', 'ה')
        private val FUTURE_ADVERBS = setOf("now", "soon", "later", "tomorrow")
        private val PAST_ADVERBS = setOf("yesterday", "ago")
        private val ALL_SUBJECTS = setOf("i", "you", "he", "she", "it", "we", "they")
        private val HIDING_PREFIXES = setOf('ה', 'ב', 'ל', 'כ')
        private val CLITICS = setOf('ה', 'ב', 'ל', 'מ', 'ו', 'ש')
        private val FINAL_FORMS = mapOf('ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ')

        /** The blank's index space: whitespace words, exactly as the align
         *  cues and the gloss columns count them. */
        fun words(en: String): List<String> = en.split(' ')

        /** Trailing punctuation off, lowercased; apostrophes and hyphens stay. */
        fun key(word: String): String = word.trimEnd(*TRAILING).lowercase()

        /** The form an option is shown in: the sentence's capitalisation at
         *  the gap, "I" always capital, a/an spelled for the next word. */
        fun display(key: String, capitalise: Boolean, nextKey: String?): String {
            val base = when (key) {
                "i" -> "I"
                "a" -> ClozeClasses.articleFor(nextKey)
                else -> key
            }
            return if (capitalise) base.replaceFirstChar { it.uppercase() } else base
        }

        private fun normaliseHebrew(w: String): String =
            w.trim('.', ',', '!', '?', ';', ':', '"', '\'', '(', ')').map { FINAL_FORMS[it] ?: it }.joinToString("")

        /**
         * The canonical Hebrew forms of one word, for the same-meaning check:
         * clitics peeled all the way down, so הים, בים and לים all reduce to
         * ים and two English words that mean it are seen to collide. Two
         * letters are enough here — ים, יד and לב are exactly the words the
         * three-letter floor was losing.
         */
        fun glossKeys(word: String): Set<String> = hebrewVariants(word, minLength = 2)

        /** A Hebrew word with up to two leading clitics peeled, every step
         *  kept, so הבית matches בית and בבית matches both. */
        fun hebrewVariants(word: String, minLength: Int = 3): Set<String> {
            var w = normaliseHebrew(word)
            val out = mutableSetOf<String>()
            if (w.length >= minLength) out += w
            repeat(2) {
                if (w.length >= 3 && w[0] in CLITICS) {
                    w = w.substring(1)
                    if (w.length >= minLength) out += w
                }
            }
            return out
        }
    }
}
