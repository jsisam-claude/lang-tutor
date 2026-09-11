package org.sisam.langtutor.tutor.tense

import kotlin.random.Random
import org.sisam.langtutor.content.PhraseSentence
import org.sisam.langtutor.profile.Skill
import org.sisam.langtutor.tutor.cloze.ClozeClasses
import org.sisam.langtutor.tutor.cloze.ClozeDeck
import org.sisam.langtutor.tutor.drill.DrillDeck

/**
 * Which three destinations a round offers, in timeline order.
 *
 * [TIME] asks *when*, which is the question Hebrew can answer: three tenses,
 * three stops, one for one. [ASPECT] asks what Hebrew has no form for — has
 * it finished, is it still going, did it come before the other thing — and
 * so it is the harder stage and the later one.
 */
enum class TenseStage(val stops: List<TenseStop>) {
    TIME(listOf(TenseStop.YESTERDAY, TenseStop.TODAY, TenseStop.TOMORROW)),
    ASPECT(listOf(TenseStop.BEFORE_THAT, TenseStop.FINISHED, TenseStop.STILL_GOING)),
}

/** One destination. The room lays these out in [TenseStage.stops] order and
 *  lets the ambient layout direction decide which end the past sits at. */
enum class TenseStop { YESTERDAY, TODAY, TOMORROW, BEFORE_THAT, FINISHED, STILL_GOING }

/**
 * What the learner has to read to place the sentence.
 *
 * INFLECTION — the verb's own ending is the cue: *listened*, *closes*.
 * AUXILIARY  — a helper carries it: *will hang*, *is going to look*, *had listened*.
 * COPULA     — the sentence's only verb is *be* and it links rather than acts:
 *              *This is my doctor.* Its stop is honest, but placing it drills
 *              where a form lives on the timeline, not which form it is, so a
 *              round holds at most [MAX_COPULA] of them.
 */
enum class TenseCue { INFLECTION, AUXILIARY, COPULA }

/** Where a round's sentences come from — what the room's ViewModel is keyed on. */
sealed interface TenseSource {
    val sessionKey: String

    data object All : TenseSource {
        override val sessionKey: String get() = "tense:all"
    }

    data class Theme(val id: String) : TenseSource {
        override val sessionKey: String get() = "tense:theme:$id"
    }

    companion object {
        /** Inverse of [sessionKey], for restoring the chip after a rotation. */
        fun parse(key: String): TenseSource =
            if (key.startsWith("tense:theme:")) Theme(key.removePrefix("tense:theme:")) else All
    }
}

/** One question as the room shows it. */
data class TenseItem(
    /** The whole bank line: en, he, level and theme all travel with it. */
    val sentence: PhraseSentence,
    val stage: TenseStage,
    val stop: TenseStop,
    /** What goes on screen — the bank line with its time word taken out. */
    val shown: String,
    /** The word taken out, ready to hand back on a wrong tap. Null when the
     *  bank line never carried one and the verb was always the only cue. */
    val hidden: String?,
    /** Indices into [words] that carry the tense, underlined on the reveal. */
    val verb: List<Int>,
    val cue: TenseCue,
) {
    val words: List<String> get() = ClozeDeck.words(shown)

    /** The line the reveal reads out: the bank's own sentence, time word and all. */
    val restored: String get() = sentence.en
}

/**
 * Builds tense-room items out of the phrasebank, with nothing authored beyond
 * three short word lists: [CLOCK_WORDS] and [ASPECT_WORDS], which say what a
 * time word is, and [NOT_A_VERB], which says what a *did* or a *to* can precede
 * without it being a verb (docs/tense-room.md).
 *
 * The room's one principle is VanPatten's: the learner interprets before they
 * produce, and the grammatical form is made the only cue to meaning. English
 * says *when* twice — once in the verb and once in a time word — and a learner
 * who can read the time word never has to read the verb. So the deck takes the
 * time word out. `We visited the doctor yesterday.` goes on screen as
 * `We visited the doctor.`, and *yesterday* is held back to hand over on a
 * wrong tap, where it is the explanation and the answer key at once.
 *
 * Everything else here exists to remove the lines where that principle does
 * not hold: a time word the deck cannot lift out of the middle of a clause,
 * a second time word left behind, a reported sentence whose two clauses sit
 * at two different stops, a verb the deck cannot point at when it reveals.
 *
 * There is no part-of-speech tagger. A verb is a word the bank itself attests
 * as one — the stem inside an `is Ving`, and the base after a `did`, a `will`
 * or a `to` — plus the irregular families [ClozeClasses] already carries for the
 * fill-the-gap room. The set is built once from one pass over the bank, and
 * [NOT_A_VERB] is the short authored list of what those shapes can precede
 * without being a verb.
 */
class TenseDeck(sentences: List<PhraseSentence>) {

    /** Verb stems the bank attests, in every form it attests them in. */
    private val verbs: Set<String> = buildVerbs(sentences)

    private val bank: List<TenseItem> = sentences.flatMap { s ->
        TenseStage.entries.mapNotNull { stage -> build(s, stage) }
    }

    // ---- rounds -----------------------------------------------------------

    /** Every item the bank yields for a stage, in bank order. The chip row,
     *  the census and the round all read the pool through here. */
    fun items(stage: TenseStage): List<TenseItem> = bank.filter { it.stage == stage }

    /** Items a source can serve at [learnerLevel] — what the chip row shows,
     *  so a topic with nothing to place is not offered. */
    fun poolSize(source: TenseSource, stage: TenseStage, learnerLevel: Int): Int =
        entries(source, stage, learnerLevel).size

    /**
     * The destinations this pool can actually fill, in timeline order.
     *
     * Not always three. The bank teaches one tense per Level — present simple
     * at 1, progressive at 2, past at 3, future at 4 — so a learner at Level 3
     * has met two of the three stops and the timeline shows two. That is the
     * honest thing to show: a destination the learner has never been taught to
     * reach is not a choice, it is a decoy.
     */
    fun stops(source: TenseSource, stage: TenseStage, learnerLevel: Int): List<TenseStop> {
        val covered = entries(source, stage, learnerLevel).mapTo(mutableSetOf()) { it.stop }
        return stage.stops.filter { it in covered }
    }

    /** Whether the room can be offered at all: two destinations is the least
     *  that is still a question. */
    fun offers(source: TenseSource, stage: TenseStage, learnerLevel: Int): Boolean =
        stops(source, stage, learnerLevel).size >= MIN_STOPS

    /**
     * Everything up to [learnerLevel], not the drill's two-Level window.
     *
     * This room exists to make two forms compete, and the bank introduces them
     * a Level apart, so a window that holds two Levels can hold at most two
     * tenses and the round collapses onto one stop. A contrast room has to
     * reach back over the whole ladder, because the ladder is the thing it is
     * contrasting.
     */
    private fun entries(source: TenseSource, stage: TenseStage, learnerLevel: Int): List<TenseItem> =
        bank.filter {
            it.stage == stage && it.sentence.level <= learnerLevel &&
                (source !is TenseSource.Theme || it.sentence.theme == source.id)
        }

    /**
     * A round: [size] items, distinct sentences, spread as evenly over the
     * three stops as the pool allows and never more than [MAX_COPULA] lines
     * whose only verb is *be*.
     *
     * The spread is the point. A round that is four Yesterdays and two Todays
     * teaches the learner to bet on Yesterday, which is the elimination the
     * three-destination shape was chosen to avoid.
     */
    fun round(
        source: TenseSource,
        stage: TenseStage,
        learnerLevel: Int,
        random: Random,
        size: Int = ROUND_SIZE,
        /** How well the learner knows a skill id, 0..1 (`SkillTracker`). The
         *  default knows nothing about anybody, which is a plain shuffle. */
        mastery: (String) -> Double = { 0.0 },
    ): List<TenseItem> {
        val own = entries(source, stage, learnerLevel)
        val candidates = when (source) {
            // A theme with a dozen lines can stall on the spread rule, and a
            // short round is the visible cost; the window behind it fills in.
            is TenseSource.Theme ->
                order(own, random, mastery) +
                    order(entries(TenseSource.All, stage, learnerLevel).filter { it.sentence.theme != source.id }, random, mastery)
            TenseSource.All -> order(own, random, mastery)
        }
        val open = stops(source, stage, learnerLevel)
        if (open.size < MIN_STOPS) return emptyList()
        val byStop = open.associateWith { stop -> candidates.filter { it.stop == stop } }
        val picked = mutableListOf<TenseItem>()
        val usedText = mutableSetOf<String>()
        val cursor = open.associateWith { 0 }.toMutableMap()
        var copulas = 0

        fun take(stop: TenseStop): Boolean {
            val pool = byStop.getValue(stop)
            var i = cursor.getValue(stop)
            while (i < pool.size) {
                val item = pool[i]
                i++
                if (item.shown in usedText) continue
                if (item.cue == TenseCue.COPULA && copulas >= MAX_COPULA) continue
                cursor[stop] = i
                usedText += item.shown
                if (item.cue == TenseCue.COPULA) copulas++
                picked += item
                return true
            }
            cursor[stop] = i
            return false
        }

        // Round-robin the stops so the round is level before it is long.
        var progress = true
        while (picked.size < size && progress) {
            progress = false
            for (stop in open) {
                if (picked.size >= size) break
                if (take(stop)) progress = true
            }
        }
        return picked.shuffled(random)
    }

    private fun order(items: List<TenseItem>, random: Random, mastery: (String) -> Double): List<TenseItem> =
        items.shuffled(random).sortedBy { band(mastery(skillId(it.sentence.tense))) }

    // ---- building one item ------------------------------------------------

    private fun build(s: PhraseSentence, stage: TenseStage): TenseItem? {
        val stop = STOPS.getValue(stage)[s.tense] ?: return null
        val words = ClozeDeck.words(s.en)
        if (words.size < MIN_WORDS) return null
        if (straddles(words)) return null

        val strip = if (stage == TenseStage.TIME) CLOCK_WORDS else CLOCK_WORDS + ASPECT_WORDS
        val cut = lift(words, strip) ?: return null
        // A second time word the deck could not lift is a second cue, and the
        // verb stops being the only thing worth reading.
        if (cut.rest.any { ClozeDeck.key(it) in strip }) return null
        if (stage == TenseStage.TIME && cut.rest.any { ClozeDeck.key(it) in PERFECT_AUX }) return null

        val verb = findVerb(cut.rest, s.tense) ?: return null
        return TenseItem(
            sentence = s,
            stage = stage,
            stop = stop,
            shown = cut.rest.joinToString(" "),
            hidden = cut.taken,
            verb = verb.at,
            cue = verb.cue,
        )
    }

    /** The sentence with its leading or trailing time phrase removed. */
    private class Cut(val rest: List<String>, val taken: String?)

    /**
     * Lifts a time phrase off the front or the back of a sentence. Only those
     * two positions: a phrase in the middle of a clause cannot come out and
     * leave a line anyone would say, and a line the learner would not say is
     * worse than one extra cue.
     */
    private fun lift(words: List<String>, strip: Set<String>): Cut? {
        // Trailing: "…the doctor yesterday." — the full stop stays behind.
        for (n in MAX_PHRASE downTo 1) {
            if (words.size <= n + MIN_REMAINDER - 1) continue
            val tail = words.takeLast(n)
            val phrase = tail.joinToString(" ").trimEnd(*TRAILING)
            if (ClozeDeck.key(phrase) !in strip) continue
            val kept = words.dropLast(n).toMutableList()
            val punct = tail.last().takeLastWhile { it in TRAILING }
            kept[kept.lastIndex] = kept.last().trimEnd(*TRAILING) + punct
            // "My knee is better than yesterday." would leave "…better than.",
            // which the room would put on screen and read aloud. A time word
            // governed by the word before it does not come out alone.
            if (ClozeDeck.key(kept.last()) in DANGLING) return null
            return Cut(kept, phrase)
        }
        // Leading: "Yesterday we visited…" — the next word takes the capital.
        for (n in MAX_PHRASE downTo 1) {
            if (words.size <= n + MIN_REMAINDER - 1) continue
            val head = words.take(n).joinToString(" ").trimEnd(',')
            if (ClozeDeck.key(head) !in strip) continue
            val kept = words.drop(n).toMutableList()
            // "Last summer was very hot." — the phrase IS the subject, and
            // lifting it leaves the verb-initial fragment "Was very hot."
            if (ClozeDeck.key(kept[0]) in FINITE_OPENERS ||
                edVerbAt(kept.map { ClozeDeck.key(it) }, 0) ||
                isIrregularPast(ClozeDeck.key(kept[0]))
            ) {
                return null
            }
            kept[0] = kept[0].replaceFirstChar { it.uppercaseChar() }
            return Cut(kept, head)
        }
        // Nothing to lift, and nothing wrong with that: plenty of bank lines
        // never carried a time word, and those are the cleanest items here.
        if (words.any { ClozeDeck.key(it) in strip }) return null
        return Cut(words, null)
    }

    /**
     * Two stops in one sentence. `She said she will come.` is past and future
     * at once, and no single tap is right, so it is not an item — whatever the
     * bank's tense field calls it.
     */
    private fun straddles(words: List<String>): Boolean {
        val keys = words.map { ClozeDeck.key(it) }
        val future = keys.any { it in FUTURE_MARKERS } ||
            keys.windowed(2).any { it[0] == "going" && it[1] == "to" }
        if (!future) return false
        return keys.any { it in PAST_MARKERS || (it.endsWith("ed") && it.dropLast(2) in verbs) || isIrregularPast(it) }
    }

    private class Verb(val at: List<Int>, val cue: TenseCue)

    /**
     * Where the tense lives, so the reveal can underline it.
     *
     * Two rules do the work, and both were learned the hard way.
     *
     * SCAN ONE CLAUSE. A bank line often carries two — `We cleaned our table
     * before we left.`, `The table was set before the guests arrived.` — and a
     * scan that runs to the end of the sentence reaches into the second and
     * marks its verb. Everything below looks only as far as [mainClause].
     *
     * TRUST STRUCTURE, NOT THE LEXICON. An auxiliary pattern identifies itself:
     * a *be* before an *-ing*, a *have* before a participle, a *did* before a
     * base. A bare inflected verb does not, and the only way to recognise one
     * is to ask whether the bank attests the word as a verb elsewhere — which
     * it does for `water`, `story`, `paint` and `Mom`, every one of them a noun
     * where it stands. So an INFLECTION mark is admitted only where a finite
     * verb can be, and where it cannot be identified the item is REFUSED. The
     * doc's rule is the deck's rule: a reveal that cannot point at the form has
     * nothing to say, and one that points at the wrong word is worse.
     */
    private fun findVerb(words: List<String>, tense: String): Verb? {
        val all = words.map { ClozeDeck.key(it) }
        // Each clause in turn, and the first that yields a match wins. Not the
        // first clause blindly: the bank tags "I am excited because we are
        // flying." present-progressive, and the form that makes it one lives in
        // the second clause. What no match may ever do is SPAN two.
        for (range in clauses(all)) {
            val hit = inClause(words, all, range, tense)
            if (hit != null) return hit
        }
        return null
    }

    private fun inClause(words: List<String>, all: List<String>, range: IntRange, tense: String): Verb? {
        val keys = all.slice(range)
        val base = range.first
        val w = words.slice(range)
        fun at(vararg i: Int) = i.map { it + base }
        fun ing(from: Int): Int? =
            (from until keys.size).firstOrNull { keys[it].endsWith("ing") && keys[it].length > 4 }

        return when (tense) {
            "future-simple" -> {
                val i = keys.indexOfFirst { it in WILL }
                if (i < 0 || i + 1 !in keys.indices) null else Verb(at(i, i + 1), TenseCue.AUXILIARY)
            }
            "modal", "conditional-zero", "conditional-first", "conditional-second",
            "conditional-third", "future-perfect", "imperative",
            -> null
            "future-going-to" -> {
                val i = keys.indexOfFirst { it == "going" }
                val be = if (i <= 0) -1 else keys.take(i).indexOfLast { it in BE_PRESENT }
                if (i < 0 || be < 0 || i + 2 !in keys.indices) null else Verb((be..i + 2).map { it + base }, TenseCue.AUXILIARY)
            }
            "present-progressive", "past-progressive" -> {
                // The -ing first, then the LAST be before it: taking the first
                // be crosses a clause, and "I am excited because we are flying."
                // would mark `am` … `flying` from two different verb phrases.
                val set = if (tense.startsWith("present")) BE_PRESENT else BE_PAST
                val v = ing(0)
                val be = if (v == null) -1 else keys.take(v).indexOfLast { it in set }
                if (v == null || be < 0) null else Verb(at(be, v), TenseCue.AUXILIARY)
            }
            "present-perfect-progressive", "past-perfect-progressive" -> {
                val have = keys.indexOfFirst { it in if (tense.startsWith("present")) HAVE_PRESENT else setOf("had") }
                val been = if (have < 0) -1 else keys.indexOf("been")
                val v = if (been < 0) null else ing(been + 1)
                if (have < 0 || been < 0 || v == null) null else Verb(at(have, been, v), TenseCue.AUXILIARY)
            }
            "present-perfect", "past-perfect" -> {
                val have = keys.indexOfFirst { it in if (tense.startsWith("present")) HAVE_PRESENT else setOf("had") }
                // `been` is part of the group, not the end of it: "had been
                // ground" keeps all three, and a participle the shape test does
                // not know still ends the group rather than being walked past.
                val v = if (have < 0) null else groupAfter(keys, have + 1)
                if (have < 0 || v == null) null else Verb((listOf(have) + v).map { it + base }, TenseCue.AUXILIARY)
            }
            "past-simple" -> {
                val did = keys.indexOfFirst { it in DID }
                if (did >= 0) {
                    // The did carries the tense on its own, so a base the bank
                    // does not attest costs the pair, never the item: "Did you
                    // ask...?" used to fall through and mark the next clause.
                    val v = baseAfter(w, keys, did + 1)
                    return if (v == null) Verb(at(did), TenseCue.AUXILIARY) else Verb(at(did, v), TenseCue.AUXILIARY)
                }
                // A passive reads its tense off the be: the participle after one
                // is a participle by POSITION, whatever the lexicon knows.
                val be = keys.indexOfFirst { it in BE_PAST }
                if (be >= 0 && participleShape(keys, be + 1)) return Verb(at(be, be + 1), TenseCue.AUXILIARY)
                // Leftmost finite verb of either kind, so an irregular later in
                // the clause cannot beat a regular one earlier.
                val v = keys.indices.firstOrNull {
                    !afterBe(keys, it) && (isIrregularPast(keys[it]) || edVerbAt(keys, it)) && finitePlace(w, keys, it)
                }
                if (v != null) return Verb(at(v), TenseCue.INFLECTION)
                if (be >= 0) Verb(at(be), TenseCue.COPULA) else null
            }
            "present-simple" -> {
                val does = keys.indexOfFirst { it in DOES }
                if (does >= 0) {
                    val v = baseAfter(w, keys, does + 1)
                    return if (v == null) Verb(at(does), TenseCue.AUXILIARY) else Verb(at(does, v), TenseCue.AUXILIARY)
                }
                val be = keys.indexOfFirst { it in BE_PRESENT }
                if (be >= 0 && participleShape(keys, be + 1)) return Verb(at(be, be + 1), TenseCue.AUXILIARY)
                val lex = keys.indices.firstOrNull { lexicalPresent(w, keys, it) }
                if (lex != null) return Verb(at(lex), TenseCue.INFLECTION)
                if (be >= 0) Verb(at(be), TenseCue.COPULA) else null
            }
            else -> null
        }
    }

    /**
     * The sentence cut into clauses, at every comma and every subordinator.
     *
     * A mark that spans two of these is always wrong — it takes an auxiliary
     * from one verb phrase and a participle from another — and the bugs it
     * produces are quiet ones: `We cleaned our table before we left.` marked
     * `left`, `The table was set before the guests arrived.` marked `arrived`.
     */
    private fun clauses(keys: List<String>): List<IntRange> {
        val cuts = mutableListOf(0)
        for (i in 1 until keys.size) {
            if (keys[i] in SUBORDINATORS || keys[i - 1].endsWith(",")) cuts += i
        }
        cuts += keys.size
        return cuts.zipWithNext().mapNotNull { (a, b) -> if (b > a) a until b else null }
    }

    /** A word shaped like a past participle, wherever it stands. Used only
     *  right after a be, where nothing else can be. */
    private fun participleShape(keys: List<String>, i: Int): Boolean {
        val k = keys.getOrNull(i) ?: return false
        if (k in NOT_A_VERB || k.endsWith("ing")) return false
        return (k.endsWith("ed") && k.length > 3) || isIrregularParticiple(k) || isIrregularPast(k)
    }

    /** The verb group after an auxiliary: `been` and any adverb travel with it,
     *  and the group ends at the first word that could be the participle. */
    private fun groupAfter(keys: List<String>, from: Int): List<Int>? {
        val out = mutableListOf<Int>()
        for (i in from until keys.size) {
            val k = keys[i]
            if (k == "been") { out += i; continue }
            if (k in ADVERBS_IN_GROUP) continue
            if (participleShape(keys, i)) return out + i
            return null
        }
        return null
    }

    /** The base verb an auxiliary governs — never a name, never a noun the
     *  bank happens to attest as a verb somewhere else. */
    private fun baseAfter(words: List<String>, keys: List<String>, from: Int): Int? =
        (from until keys.size).firstOrNull { i ->
            val k = keys[i]
            // A base verb never wears the third-person -s, so a word that does
            // is the inverted subject, not the verb: "Not only do bees make
            // honey" was marking `bees`.
            val inflected = k.endsWith("s") && !k.endsWith("ss")
            k !in NOT_A_VERB && k in verbs && !inflected &&
                words[i].firstOrNull()?.isUpperCase() != true &&
                keys.getOrNull(i - 1) !in OPENERS
        }

    /** Where a finite verb can stand: after its subject, not inside a noun
     *  phrase, not as a name. */
    private fun finitePlace(words: List<String>, keys: List<String>, i: Int): Boolean {
        if (i == 0) return false
        if (words[i].firstOrNull()?.isUpperCase() == true) return false
        val prev = keys[i - 1]
        return prev !in OPENERS && prev !in PREPOSITIONS && prev !in SUBORDINATORS && prev != "to"
    }

    /**
     * A present-simple lexical verb, admitted only where one can stand.
     *
     * The bank attests `water` ("Dad is watering the plants"), `story` ("go to
     * story hour") and `paint` as verbs, and every one of them is a noun in the
     * line that got marked. Position is the only thing that separates them, so
     * a candidate must sit after a subject and wear its third-person -s, or
     * follow a pronoun subject directly. Anything less and the item is refused
     * rather than marked wrong.
     */
    private fun lexicalPresent(words: List<String>, keys: List<String>, i: Int): Boolean {
        if (!finitePlace(words, keys, i)) return false
        val k = keys[i]
        if (k in BE_PRESENT || k in HAVE_PRESENT || k in DOES || k in NOT_A_VERB) return false
        if (afterBe(keys, i)) return false
        if (keys[i - 1] in ClozeClasses.MEMBERS.getValue(ClozeClasses.Kind.MODAL)) return false
        // A third-person -s is self-identifying: no noun in the bank wears one
        // in a verb's place. A bare base is not, so it is admitted only right
        // after a pronoun subject, where nothing else can be.
        val thirdPerson = thirdPersonStems(k).any { it in verbs }
        // "I just want to say thanks." — an adverb may stand between the
        // subject and its verb, and without stepping over it the scan ran on
        // and marked `thanks`.
        // What follows an infinitive is its object: "I just want to say
        // thanks." was marking `thanks`, which the bank attests only because
        // `thank` is a verb somewhere else.
        if (keys.getOrNull(i - 2) == "to") return false
        val subject = (i - 1 downTo 0).firstOrNull { keys[it] !in ADVERBS_IN_GROUP }
        val afterPronoun = subject != null && keys[subject] in PRONOUN_SUBJECTS
        return (thirdPerson || (afterPronoun && k in verbs))
    }

    /**
     * The stems a third-person present could have been spelled from.
     *
     * English writes the -s four ways and none is recoverable on its own:
     * *carries* could come from carry, *washes* from wash, *closes* from close
     * or clos. Every candidate is offered and the bank decides which one it
     * actually attests — the same refusal to author a rule the rest of this
     * file makes.
     */
    private fun thirdPersonStems(k: String): List<String> = when {
        k.endsWith("ss") -> emptyList()
        k.endsWith("ies") && k.length > 4 -> listOf(k.dropLast(3) + "y")
        k.endsWith("es") && k.length > 3 -> listOf(k.dropLast(2), k.dropLast(1))
        k.endsWith("s") && k.length > 2 -> listOf(k.dropLast(1))
        else -> emptyList()
    }

    /** Whether [i] sits directly after a be-form, where a complement lives. */
    private fun afterBe(keys: List<String>, i: Int): Boolean =
        i > 0 && (keys[i - 1] in BE_PRESENT || keys[i - 1] in BE_PAST)

    /** A -ed word the bank attests as a verb — so *walked* but not *hundred*,
     *  *crowded* or *tired*, none of which the bank ever uses as a verb. */
    private fun edVerbAt(keys: List<String>, i: Int): Boolean {
        val k = keys.getOrNull(i) ?: return false
        if (!k.endsWith("ed") || k.length <= 3) return false
        val stem = k.dropLast(2)
        return stem in verbs || k.dropLast(1) in verbs ||
            (stem.length > 2 && stem.last() == stem[stem.length - 2] && stem.dropLast(1) in verbs) ||
            (k.endsWith("ied") && k.dropLast(3) + "y" in verbs)
    }

    private fun isIrregularPast(key: String): Boolean =
        ClozeClasses.IRREGULAR_FAMILIES.any { it.size >= 2 && it.elementAt(1) == key }

    /**
     * A two-member family means the past and the participle are the same word
     * — *leave/left*, *find/found*, *buy/bought* — so the participle is always
     * the family's LAST member, never its third. Requiring a third dropped
     * every perfect line built on one of the 29 two-member families.
     */
    private fun isIrregularParticiple(key: String): Boolean =
        ClozeClasses.IRREGULAR_FAMILIES.any { it.size >= 2 && it.elementAt(it.size - 1) == key }

    /**
     * Verbs the bank attests, learned from the shapes that mostly hold one: the
     * stem inside an *is Ving*, and the base after a *did*, a *do/does*, a
     * *to* or a *will*. No authored lexicon, the same way the fill-the-gap room
     * refuses one — only [NOT_A_VERB], for the words those shapes can precede
     * that are not verbs at all.
     */
    private fun buildVerbs(sentences: List<PhraseSentence>): Set<String> {
        val out = mutableSetOf<String>()
        for (s in sentences) {
            val keys = ClozeDeck.words(s.en).map { ClozeDeck.key(it) }
            for (i in keys.indices) {
                val k = keys[i]
                if (k.endsWith("ing") && k.length > 5 && i > 0 && keys[i - 1] in BE_ALL) {
                    out += stemsOfIng(k)
                }
                val prev = keys.getOrNull(i - 1)
                if (prev != null && (prev in DID || prev in DOES || prev == "to" || prev in WILL)) {
                    if (k !in NOT_A_VERB) out += k
                }
            }
        }
        for (family in ClozeClasses.IRREGULAR_FAMILIES) out += family
        return out
    }

    /**
     * The stems an -ing form could have been spelled from: running → run,
     * sitting → sit, closing → close *or* clos, carrying → carry.
     *
     * Like [thirdPersonStems], the doubling and the dropped silent e are not
     * recoverable, so both readings are attested and a later lookup finds
     * whichever one the bank really uses.
     */
    private fun stemsOfIng(k: String): List<String> {
        val base = k.dropLast(3)
        if (base.length > 2 && base.last() == base[base.length - 2] && base.last() !in "lsz") {
            return listOf(base.dropLast(1), base)
        }
        return listOf(base, base + "e")
    }

    private fun band(mastery: Double): Int = (mastery * DrillDeck.MASTERY_BANDS).toInt()

    companion object {
        const val ROUND_SIZE = 6

        /** One be-only line per round, so a round always drills a form. */
        const val MAX_COPULA = 1

        /** Fewer destinations than this is not a question worth asking. */
        const val MIN_STOPS = 2

        /** Below this the sentence has no room for both a subject and a verb. */
        private const val MIN_WORDS = 3

        /** What must survive a lift, so the shown line is still a sentence. */
        private const val MIN_REMAINDER = 3

        /** "this morning", "the day before yesterday". */
        private const val MAX_PHRASE = 4

        /** The skill id a tense answers to. One spelling, in [Skill]. */
        fun skillId(tense: String): String = Skill.tense(tense)

        /** Which tense lands where, per stage. A tense absent from a stage's
         *  map has no honest stop there and is simply not offered. */
        val STOPS: Map<TenseStage, Map<String, TenseStop>> = mapOf(
            TenseStage.TIME to mapOf(
                "past-simple" to TenseStop.YESTERDAY,
                "past-progressive" to TenseStop.YESTERDAY,
                "present-simple" to TenseStop.TODAY,
                "present-progressive" to TenseStop.TODAY,
                "future-simple" to TenseStop.TOMORROW,
                "future-going-to" to TenseStop.TOMORROW,
            ),
            TenseStage.ASPECT to mapOf(
                "past-perfect" to TenseStop.BEFORE_THAT,
                "past-perfect-progressive" to TenseStop.BEFORE_THAT,
                "past-simple" to TenseStop.FINISHED,
                "present-perfect" to TenseStop.FINISHED,
                "present-progressive" to TenseStop.STILL_GOING,
                "present-perfect-progressive" to TenseStop.STILL_GOING,
            ),
        )

        /**
         * Words that name a place on the timeline, and so say what the verb
         * says. These come out.
         *
         * Frequency words are deliberately absent: *always*, *every day*,
         * *never* say how often, not when, and `I always brush my teeth` is
         * as true of last year as of today. They can stay, and they must —
         * they are what a habitual present simple is made of.
         */
        val CLOCK_WORDS: Set<String> = setOf(
            "yesterday", "today", "tomorrow", "now", "right now", "tonight",
            "soon", "later", "then", "this morning", "this afternoon", "this evening",
            "last night", "last week", "last month", "last year", "last summer",
            "next week", "next month", "next year", "next summer", "this week",
            "this year", "at the moment", "in a minute", "the day before yesterday",
            "the day after tomorrow", "a moment ago", "a minute ago", "an hour ago",
            "this time", "these days", "in a week", "in an hour",
        )

        /**
         * Words that say how a thing sits against now rather than when it
         * happened. They are the aspect stage's giveaway and nothing at all
         * to the time stage, so only the aspect stage lifts them out.
         */
        val ASPECT_WORDS: Set<String> = setOf("already", "still", "yet", "since", "so far", "just")

        private val TRAILING = charArrayOf('.', ',', '!', '?', ';', ':', '…')

        private val WILL = setOf("will", "'ll", "won't")
        private val DID = setOf("did", "didn't")
        private val DOES = setOf("do", "does", "don't", "doesn't")
        private val BE_PRESENT = setOf("am", "is", "are", "isn't", "aren't", "i'm", "it's", "he's", "she's", "we're", "they're", "that's")
        private val BE_PAST = setOf("was", "were", "wasn't", "weren't")
        private val BE_ALL = BE_PRESENT + BE_PAST
        private val HAVE_PRESENT = setOf("have", "has", "haven't", "hasn't", "i've", "we've", "they've")
        private val PERFECT_AUX = setOf("have", "has", "had", "haven't", "hasn't", "hadn't")
        private val FUTURE_MARKERS = WILL + setOf("shall")
        private val PAST_MARKERS = BE_PAST + DID + setOf("had", "hadn't")

        /** Words a *to* or a *did* can precede that are not verbs. */
        /**
         * Words a *to*, a *did* or a *will* can precede that are not verbs.
         * The pronouns matter: a question inverts to *Will I get a sticker?*
         * and a negation to *we did not wait*, so without them the bank
         * attests *i* and *not* as verbs and the reveal underlines them.
         */
        private val NOT_A_VERB = setOf(
            "the", "a", "an", "my", "your", "his", "her", "our", "their", "this", "that",
            "school", "bed", "work", "him", "them", "us", "me", "it", "you",
            "i", "he", "she", "we", "they", "not", "never", "always", "there", "here",
        )

        /**
         * Words a lifted time phrase cannot be taken away from: they govern it,
         * and without it they dangle. "better than yesterday" loses its object.
         */
        private val DANGLING = setOf(
            "than", "for", "since", "until", "before", "after", "by", "from", "to",
            "in", "on", "at", "of", "with", "about", "and", "but", "or", "because",
        )

        /** Verb forms that cannot open a sentence a learner would say, so a
         *  leading lift that produces one took the subject with it. */
        private val FINITE_OPENERS = setOf(
            "was", "were", "is", "are", "am", "has", "have", "had", "wasn't", "weren't",
            "isn't", "aren't", "hasn't", "haven't", "hadn't",
        )

        /** Where a second clause starts. The reveal never reaches past one. */
        private val SUBORDINATORS = setOf(
            "because", "before", "after", "while", "when", "although", "though", "unless",
            "until", "since", "if", "that", "which", "who", "whom", "whose", "so", "and", "but", "or",
        )

        /** Adverbs that sit inside a verb group without ending it. */
        private val ADVERBS_IN_GROUP = setOf(
            "already", "never", "always", "just", "still", "ever", "not", "recently", "finally",
        )

        /** A bare base verb is only self-evidently a verb straight after one
         *  of these, where nothing else can stand. */
        private val PRONOUN_SUBJECTS = setOf("i", "you", "we", "they", "he", "she", "it")

        private val PREPOSITIONS = setOf(
            "in", "on", "at", "of", "with", "about", "for", "from", "by", "under", "over",
            "into", "near", "behind", "between", "around", "through", "inside", "outside",
            "above", "below", "across", "along", "than",
        )

        /** Determiners and possessives that open a subject noun phrase. */
        private val OPENERS = setOf(
            "the", "a", "an", "my", "your", "his", "her", "our", "their", "this", "that",
            "these", "those", "some", "two", "three", "four", "five", "many", "every", "all",
        )
    }
}
