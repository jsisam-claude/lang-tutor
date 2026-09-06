package org.sisam.langtutor.tutor.cloze

/**
 * The one AUTHORED table behind the fill-the-gap room (docs/fill-the-gap.md).
 *
 * Everything else in the room is derived from the phrasebank and the picture
 * packs. This file is the exception, and it is small on purpose: English
 * function words, grouped into the classes a learner can confuse them in,
 * plus the handful of rules that keep a four-option item honest — which
 * pronouns a verb form admits, which pairs Hebrew renders with one word, when
 * `a` is spelled `an`. Because it is authored it is test-gated
 * (`ClozeClassesTest` walks every list) the way `pronunciation-exceptions.tsv`
 * and `twisters.json` are.
 *
 * There is no part-of-speech tagger on the device or in the build, so a
 * function word's class is decided by membership here plus position
 * ([roleOf]); an open-class word's "class" is its immediate context and its
 * morphological shape ([Shape]), which is the whole of `ClozeDeck`'s
 * distractor logic. No Hebrew is authored anywhere in this object: the Hebrew
 * side of every item is the sentence's own reviewed `he`.
 */
object ClozeClasses {

    enum class Kind {
        ARTICLE, POSSESSIVE, DEMONSTRATIVE, QUANTIFIER, SUBJECT, OBJECT, MODAL,
        CONJUNCTION, QUESTION, TIME_ADVERB, FREQUENCY, DEGREE, PLACE_PREP, TIME_PREP,
    }

    /** The three determiner classes are ONE slot in English ("in the / my /
     *  this garden"), so they pool together when distractors are drawn. */
    val DETERMINERS: Set<Kind> = setOf(Kind.ARTICLE, Kind.POSSESSIVE, Kind.DEMONSTRATIVE)

    /**
     * Class members. Narrow classes on purpose: a distractor is only a fair
     * test of meaning if it could grammatically stand in the gap, so "in"
     * must never face "until" and "always" must never face "yesterday".
     * A word may appear in two classes only if [roleOf] resolves it by
     * position; the test pins that set exactly.
     */
    val MEMBERS: Map<Kind, List<String>> = mapOf(
        // `an` is never a member: a/an is one lexeme spelled by rule (articleFor).
        Kind.ARTICLE to listOf("a", "the"),
        Kind.POSSESSIVE to listOf("my", "your", "his", "her", "its", "our", "their"),
        Kind.DEMONSTRATIVE to listOf("this", "that", "these", "those"),
        Kind.QUANTIFIER to listOf(
            "some", "any", "every", "each", "no", "all", "many", "much", "more", "most",
            "few", "both", "another",
        ),
        Kind.SUBJECT to listOf("i", "you", "he", "she", "it", "we", "they"),
        Kind.OBJECT to listOf("me", "you", "him", "her", "it", "us", "them"),
        // No contractions: "can't" fuses a modal and a negation, and no other
        // member can stand where it stood without flipping the polarity.
        Kind.MODAL to listOf("can", "could", "will", "would", "shall", "should", "may", "might", "must"),
        Kind.CONJUNCTION to listOf(
            "and", "but", "or", "so", "because", "if", "when", "while", "although",
            "though", "unless", "whether", "since", "until",
        ),
        Kind.QUESTION to listOf("who", "what", "where", "when", "why", "how", "which", "whose"),
        Kind.TIME_ADVERB to listOf(
            "yesterday", "today", "tomorrow", "now", "then", "soon", "later", "already",
            "still", "yet", "again", "ago",
        ),
        Kind.FREQUENCY to listOf("always", "never", "sometimes", "often", "usually", "ever"),
        Kind.DEGREE to listOf("very", "too", "also", "just", "only"),
        Kind.PLACE_PREP to listOf(
            "in", "on", "at", "under", "over", "into", "near", "behind", "between", "around",
            "through", "inside", "outside", "above", "below", "across", "along", "past",
            "from", "to",
        ),
        Kind.TIME_PREP to listOf("before", "after", "during", "until", "since"),
    )

    /**
     * Function words that are never blanked and never offered.
     *
     * Three reasons, each fatal to a meaning item: the Hebrew is a prefix or
     * a construct, not a word the learner can find in the line (of, than,
     * like, as, for, by, about, with); the word flips the sentence rather
     * than filling it (not); or it is a particle keyed by its verb, not by
     * the Hebrew (up, down, out, off). Every BE/HAVE/DO form is here because
     * its only contrast is person agreement — grammar, not meaning — and
     * Hebrew has no present-tense copula to key it from.
     */
    val NEVER: Set<String> = setOf(
        "of", "than", "as", "not", "with", "without", "for", "about", "by",
        "up", "down", "out", "off", "here", "there", "other", "one",
        "am", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "having", "do", "does", "did", "done",
        // Constructs and particles with no Hebrew word of their own.
        "same", "first", "last", "next", "whole", "almost", "please", "welcome", "mine", "yours",
        // A reflexive is fixed by its subject; it decides nothing by meaning.
        "myself", "yourself", "himself", "herself", "itself", "ourselves", "yourselves", "themselves",
    )

    /** Numerals outside the numbers pack: quantifiers in disguise, like the pack's. */
    val NUMBER_WORDS: Set<String> = setOf(
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
        "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
        "hundred", "thousand",
    )

    val REFLEXIVES: Set<String> = setOf(
        "myself", "yourself", "himself", "herself", "itself", "ourselves", "yourselves", "themselves",
    )

    /**
     * Prepositions Hebrew renders with a bare ב unless a marker word is
     * present: "on the swing" is בנדנדה, "in the rain" is בגשם. Without the
     * marker (על, לתוך, דרך, בתוך) nothing in this family is a fair rival.
     */
    val BET_FAMILY: Set<String> = setOf("in", "at", "inside", "on", "into", "through")
    val BET_MARKERS: Map<String, String> = mapOf("on" to "על", "into" to "לתוך", "through" to "דרך", "inside" to "בתוך")

    /** "Will you…?" and "Can you…?" are one request in Hebrew (תיתן לי). */
    val REQUEST_MODALS: Set<String> = setOf("will", "can", "could", "would")

    /** The conjunctions that join two like things; the rest open a clause. */
    val COORDINATORS: Set<String> = setOf("and", "but", "or", "so")

    /** The dative and possessive signals a Hebrew line carries for "my/your":
     *  של־, a dative clitic (לי, לך…) or a suffixed kin noun (אחי, אמי). */
    val HEBREW_POSSESSION: List<String> = listOf(
        "של", "לי", "לך", "לו", "לה", "לנו", "לכם", "לכן", "להם", "להן",
        "אחי", "אחותי", "אמי", "אבי", "הוריי", "סבי", "סבתי", "אחיך", "אחותך",
    )

    /** The glue words; never a distractor in any class even where a member
     *  list could reach them, because "that"/"not"/"one" in an option row
     *  read as tricks rather than choices. */
    val NEVER_DISTRACTOR: Set<String> = setOf("than", "of", "as", "like", "that", "not", "one")

    /**
     * Pairs Hebrew renders with ONE word, so the meaning line cannot separate
     * them: never offered against each other. Each set is a claim about the
     * bank's Hebrew (may/might → אולי, in/at/inside → ב, every/each → כל…),
     * and this list is on the native-read queue for exactly that reason.
     * this/that and in/on are deliberately NOT here: the bank renders them
     * distinctly (הזה/ההוא, ב/על).
     */
    val SAME_MEANING: List<Set<String>> = listOf(
        setOf("may", "might"), setOf("can", "could"), setOf("will", "shall"),
        setOf("if", "whether"), setOf("because", "since"), setOf("although", "though"),
        setOf("in", "at", "inside", "into"), setOf("over", "above"), setOf("under", "below"),
        setOf("every", "each"), setOf("some", "any"), setOf("many", "much"),
        setOf("also", "too"), setOf("still", "yet"),
        // Open-class pairs the align cues stop short of (they end at Level
        // 3), found by the item audit: קטן, גדול, and the hypernym pair
        // ציפור/ברווז, where a duck is also a bird.
        setOf("little", "small"), setOf("big", "large"), setOf("bird", "duck"),
        // Hebrew has no neuter: his/its are both שלו, him/it both אותו.
        setOf("his", "its"), setOf("him", "it"),
        // One Hebrew word, two English ones, and no cue anywhere in the bank
        // to derive it from: אמבטיה, חנות, תמונה, ידיים. Found by reading
        // generated items, which is the only way these surface — the same
        // way the pairs above were.
        setOf("bath", "tub"), setOf("shop", "store"),
        setOf("picture", "photo"), setOf("hands", "arms"),
    )

    /** Number agreement for determiners and quantifiers: "these cat" is a
     *  grammar error, not a meaning choice, so it is never an option. */
    val SINGULAR_ONLY: Set<String> = setOf("a", "this", "that", "every", "each", "another", "much")
    val PLURAL_ONLY: Set<String> = setOf("these", "those", "many", "few", "both")

    /**
     * Which subject pronouns the following verb form admits. A subject blank
     * is only a meaning item if every option agrees with the verb; "___ am
     * happy" admits one pronoun and therefore never becomes a slot.
     */
    val AGREEMENT: Map<String, Set<String>> = mapOf(
        "am" to setOf("i"),
        "is" to setOf("he", "she", "it"),
        "are" to setOf("you", "we", "they"),
        "was" to setOf("i", "he", "she", "it"),
        "were" to setOf("you", "we", "they"),
        "has" to setOf("he", "she", "it"),
        "have" to setOf("i", "you", "we", "they"),
        "does" to setOf("he", "she", "it"),
        "do" to setOf("i", "you", "we", "they"),
        // "Did ___ finish?" admits every person — but naming it here is what
        // makes a subject after "did" a subject and not an object.
        "did" to setOf("i", "you", "he", "she", "it", "we", "they"),
    )

    /** Words spelled with a consonant and said with a vowel — the whole
     *  exception list the bank needs; 30 of its 45 "an" tokens are "an hour". */
    val AN_WORDS: Set<String> = setOf("hour", "honest")

    /**
     * After these, the Hebrew ב/ל prefix swallows the article: באוהל is
     * be-ohel or ba-ohel without nikud, so a/the cannot be told apart and are
     * never offered as a pair there (18% of "the", 14% of "a" in the bank).
     */
    val ABSORBING_PREPS: Set<String> = setOf(
        "in", "at", "on", "to", "for", "into", "by", "near", "from", "with",
        "under", "over", "inside", "about",
    )

    /** Pack-word plurals the suffix rule gets wrong. */
    val IRREGULAR_PLURAL: Map<String, String> = mapOf("mouse" to "mice", "sheep" to "sheep", "fish" to "fish")

    /**
     * Hebrew forms of pack words whose written stem changes with gender or
     * number, so the "is the pack word really in this line's Hebrew" check
     * (which is what keeps the town-square `square` and the aquarium's `fish
     * tank` out of the pack) can still find them. Numbers agree in gender
     * with the counted noun; three animals have irregular plurals.
     */
    val HEBREW_FORMS: Map<String, List<String>> = mapOf(
        "two" to listOf("שני", "שתי", "שתיים"),
        "three" to listOf("שלוש", "שלושה"),
        "four" to listOf("ארבע", "ארבעה"),
        "five" to listOf("חמש", "חמישה"),
        "six" to listOf("שש", "שישה"),
        "seven" to listOf("שבע", "שבעה"),
        "eight" to listOf("שמונה"),
        "nine" to listOf("תשע", "תשעה"),
        "ten" to listOf("עשר", "עשרה"),
        "cow" to listOf("פרה", "פרות"),
        "fish" to listOf("דג", "דגים"),
        "sheep" to listOf("כבשה", "כבשים", "כבש"),
        "goat" to listOf("עז", "עיזים"),
        "ladybug" to listOf("פרת משה", "פרות משה"),
        "snail" to listOf("חילזון", "חלזונות"),
    )

    /** `that` is a demonstrative only when the Hebrew says so; otherwise it
     *  is the complementiser ש, a clitic the learner never sees as a word. */
    val DEMONSTRATIVE_HEBREW: List<String> = listOf("ההוא", "ההיא", "אותו", "אותה", "ההם", "ההן")

    /** Which pack is a counting pack: its words are quantifiers in disguise
     *  and never fill a noun or verb gap as an open word. */
    const val NUMBERS_PACK = "numbers"

    /** Operators, not things: nothing in the bank uses them as such ("half
     *  an hour" is the only hit), so the pack is not offered in this room. */
    const val MATHS_PACK = "maths"

    private val membership: Map<String, Set<Kind>> = buildMap<String, MutableSet<Kind>> {
        for ((kind, words) in MEMBERS) for (w in words) getOrPut(w) { mutableSetOf() }.add(kind)
    }

    fun kindsOf(key: String): Set<Kind> = membership[key].orEmpty()

    fun isMember(key: String): Boolean = key in membership

    /** Morphological shape of an open-class word: the tagger the app does not have. */
    enum class Shape { BASE, S, ING, ED, ER, EST, LY, PROPER }

    fun shapeOf(key: String, capitalised: Boolean, initial: Boolean): Shape = when {
        // A capital letter not at the start of the sentence is a name — Noa,
        // Tuki, Jaffa. "I" is a pronoun and never reaches here as open class.
        capitalised && !initial && key != "i" -> Shape.PROPER
        key.length > 5 && key.endsWith("ing") -> Shape.ING
        key.length > 5 && key.endsWith("est") -> Shape.EST
        key.length > 4 && key.endsWith("ed") -> Shape.ED
        key.length > 4 && key.endsWith("ly") -> Shape.LY
        key.length > 4 && key.endsWith("er") -> Shape.ER
        key.length > 3 && key.endsWith("s") && !key.endsWith("ss") -> Shape.S
        else -> Shape.BASE
    }

    sealed interface Role {
        data class Closed(val kind: Kind) : Role
        data object Open : Role
        data object Never : Role
    }

    /**
     * The class of the word at [i], resolved by position for the words that
     * belong to more than one. [keys] and [shapes] are the whole sentence in
     * the blank's index space; [hebrew] is the line's meaning, read only for
     * `that`.
     */
    fun roleOf(
        keys: List<String>,
        shapes: List<Shape>,
        i: Int,
        hebrew: String,
        /** Indexes that open a clause after punctuation ("…bed, you fall"),
         *  where a pronoun is a subject exactly as at index 0. */
        clauseStarts: Set<Int> = emptySet(),
    ): Role {
        val key = keys[i]
        if (key in NEVER || key.contains('\'')) return Role.Never
        val next = keys.getOrNull(i + 1)
        val nextOpen = next != null && !isMember(next) && next !in NEVER && !next.contains('\'')
        val nextShape = shapes.getOrNull(i + 1)
        val last = i == keys.lastIndex
        val prev = if (i in clauseStarts) null else keys.getOrNull(i - 1)
        fun closed(kind: Kind): Role = Role.Closed(kind)
        if (key in NUMBER_WORDS) return Role.Never
        return when (key) {
            // The verb after a subject or a do-form; the preposition
            // ("looks like") everywhere else, which is glue.
            "like" -> if (prev != null && (Kind.SUBJECT in kindsOf(prev) || prev in setOf("don't", "doesn't", "didn't"))) Role.Open else Role.Never
            "her" -> if (nextOpen) closed(Kind.POSSESSIVE) else closed(Kind.OBJECT)
            "his" -> if (nextOpen) closed(Kind.POSSESSIVE) else Role.Never
            "you", "it" -> {
                val subjectPosition = i == 0 || i in clauseStarts ||
                    (next != null && (Kind.MODAL in kindsOf(next) || next in AGREEMENT ||
                        Kind.TIME_ADVERB in kindsOf(next) || Kind.FREQUENCY in kindsOf(next) ||
                        Kind.DEGREE in kindsOf(next) || nextOpen)) &&
                    (prev == null || Kind.CONJUNCTION in kindsOf(prev) || Kind.QUESTION in kindsOf(prev) ||
                        Kind.TIME_PREP in kindsOf(prev) || Kind.MODAL in kindsOf(prev) ||
                        prev in AGREEMENT || prev == "so")
                if (subjectPosition) closed(Kind.SUBJECT) else closed(Kind.OBJECT)
            }
            "that" -> if (nextOpen && DEMONSTRATIVE_HEBREW.any { it in hebrew }) closed(Kind.DEMONSTRATIVE) else Role.Never
            "when" -> if (i == 0) closed(Kind.QUESTION) else closed(Kind.CONJUNCTION)
            "before", "after", "until", "since" -> when {
                last -> Role.Never // trailing adverb use: "…in a tent before."
                next != null && Kind.SUBJECT in kindsOf(next) -> closed(Kind.CONJUNCTION)
                else -> closed(Kind.TIME_PREP)
            }
            // The infinitive marker before a verb (to play, to order, to do)
            // is not a place; the shape test must also catch -er verbs.
            "to" -> if (next in setOf("do", "be", "have") || (nextOpen && (nextShape == Shape.BASE || nextShape == Shape.ER))) Role.Never else closed(Kind.PLACE_PREP)
            "so" -> if (next != null && Kind.SUBJECT in kindsOf(next)) closed(Kind.CONJUNCTION) else Role.Never
            "no" -> if (nextOpen) closed(Kind.QUANTIFIER) else Role.Never
            "much" -> if (nextOpen) closed(Kind.QUANTIFIER) else Role.Never
            "more", "most" -> if (nextOpen && nextShape == Shape.S) closed(Kind.QUANTIFIER) else Role.Never
            else -> {
                val kinds = kindsOf(key)
                when {
                    kinds.isEmpty() -> if (shapes[i] == Shape.PROPER) Role.Never else Role.Open
                    kinds.size != 1 -> Role.Never // a two-class word with no rule above: the test forbids this
                    // A modal is followed by a verb, a subject or "not"; a
                    // "can" before "is" is a watering can.
                    kinds.first() == Kind.MODAL ->
                        if (next == null || next in BE_FORMS || next in AGREEMENT || (isMember(next) && kindsOf(next).any { it in DETERMINERS })) Role.Never else closed(Kind.MODAL)
                    // A determiner is only a determiner before a word it
                    // determines; "This is my pillow" and "all of them" are
                    // pronouns, and a pronoun has no same-class options.
                    kinds.first() in DETERMINERS || kinds.first() == Kind.QUANTIFIER ->
                        if (nextOpen) closed(kinds.first()) else Role.Never
                    else -> closed(kinds.first())
                }
            }
        }
    }

    /** The words [roleOf] has a positional rule for; the test pins the set
     *  of multi-class members to exactly this, so a new overlap cannot slip
     *  in without a rule. */
    val RESOLVED_BY_POSITION: Set<String> = setOf("you", "it", "her", "his", "that", "when", "since", "until", "like", "to")

    /** a/an by the next word: a vowel LETTER or a silent-h word takes "an". */
    fun articleFor(nextKey: String?): String {
        val k = nextKey ?: return "a"
        return if (k.firstOrNull() in VOWELS || k in AN_WORDS) "an" else "a"
    }

    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')

    /** Regular English plural plus the pack's three irregulars. */
    fun plural(word: String): String {
        IRREGULAR_PLURAL[word]?.let { return it }
        return when {
            word.endsWith("y") && word.length > 1 && word[word.length - 2] !in VOWELS -> word.dropLast(1) + "ies"
            word.endsWith("s") || word.endsWith("x") || word.endsWith("z") ||
                word.endsWith("ch") || word.endsWith("sh") -> word + "es"
            word.endsWith("f") -> word.dropLast(1) + "ves"
            else -> word + "s"
        }
    }

    /**
     * The irregular verbs the bank uses, as families. Authored because no
     * rule derives went from go; test-gated like every list here. They serve
     * the owner's own example — "saw | see" is a fair item, decided by the
     * Hebrew tense — and they say which forms are never a base form.
     */
    val IRREGULAR_FAMILIES: List<Set<String>> = listOf(
        setOf("see", "saw", "seen"), setOf("go", "went", "gone"), setOf("eat", "ate", "eaten"),
        setOf("find", "found"), setOf("make", "made"), setOf("take", "took", "taken"),
        setOf("give", "gave", "given"), setOf("come", "came"), setOf("run", "ran"),
        setOf("sing", "sang", "sung"), setOf("swim", "swam", "swum"), setOf("buy", "bought"),
        setOf("tell", "told"), setOf("say", "said"), setOf("get", "got", "gotten"),
        setOf("sleep", "slept"), setOf("fly", "flew", "flown"), setOf("drink", "drank", "drunk"),
        setOf("write", "wrote", "written"), setOf("bring", "brought"), setOf("leave", "left"),
        setOf("grow", "grew", "grown"), setOf("know", "knew", "known"), setOf("build", "built"),
        setOf("feed", "fed"), setOf("ride", "rode", "ridden"), setOf("break", "broke", "broken"),
        setOf("wear", "wore", "worn"), setOf("forget", "forgot", "forgotten"), setOf("hear", "heard"),
        setOf("meet", "met"), setOf("sit", "sat"), setOf("stand", "stood"), setOf("teach", "taught"),
        setOf("throw", "threw", "thrown"), setOf("win", "won"), setOf("lose", "lost"),
        setOf("keep", "kept"), setOf("hold", "held"), setOf("draw", "drew", "drawn"),
        setOf("speak", "spoke", "spoken"), setOf("fall", "fell", "fallen"), setOf("catch", "caught"),
        setOf("choose", "chose", "chosen"), setOf("hide", "hid", "hidden"), setOf("wake", "woke", "woken"),
        setOf("send", "sent"), setOf("feel", "felt"), setOf("blow", "blew", "blown"),
        setOf("sell", "sold"), setOf("hang", "hung"), setOf("bite", "bit", "bitten"),
        setOf("forgive", "forgave", "forgiven"), setOf("burn", "burnt"), setOf("spend", "spent"),
        setOf("think", "thought"), setOf("begin", "began", "begun"), setOf("shake", "shook", "shaken"),
    )

    private val irregularFamilyOf: Map<String, Set<String>> = buildMap {
        for (family in IRREGULAR_FAMILIES) for (w in family) put(w, family)
    }

    /** A past or participle form of an irregular verb: never a base form,
     *  whatever context the bank happens to attest it in. */
    fun isIrregularNonBase(key: String): Boolean {
        val family = irregularFamilyOf[key] ?: return false
        return key != family.first()
    }

    /** True when the two keys are inflections of one verb or noun: by the
     *  crude suffix rule (warm/warmed, cat/cats, play/playing) or by an
     *  authored irregular family (see/saw). */
    fun sameStem(a: String, b: String): Boolean {
        if (a == b) return true
        irregularFamilyOf[a]?.let { if (b in it) return true }
        return stem(a) == stem(b)
    }

    /** One suffix off, a trailing e off, a doubled consonant undoubled:
     *  carries/carry → carr, making/make → mak, running/run → run. */
    private fun stem(k: String): String {
        var s = k
        for (suffix in listOf("ing", "ies", "ied", "ed", "es", "s")) {
            if (s.endsWith(suffix) && s.length - suffix.length >= 3) {
                s = s.dropLast(suffix.length)
                break
            }
        }
        if (s.endsWith("y") && s.length >= 4) s = s.dropLast(1)
        if (s.endsWith("e") && s.length >= 4) s = s.dropLast(1)
        if (s.length >= 4 && s[s.length - 1] == s[s.length - 2] && s.last() !in VOWELS) s = s.dropLast(1)
        return s
    }

    /** True for a verb-like position: what stands to the left is a subject,
     *  a modal, an auxiliary, "to" or "not" — the places an inflected form
     *  is a fair distractor. Never after a BE form: warm/warmed after "is"
     *  is adjective versus participle, which Hebrew cannot decide. */
    fun verbLike(leftKey: String?): Boolean = leftKey != null && (
        Kind.SUBJECT in kindsOf(leftKey) || Kind.MODAL in kindsOf(leftKey) ||
            leftKey in setOf("have", "has", "had", "do", "does", "did", "to", "not")
        )

    val BE_FORMS: Set<String> = setOf("am", "is", "are", "was", "were", "be", "been", "being")
}
