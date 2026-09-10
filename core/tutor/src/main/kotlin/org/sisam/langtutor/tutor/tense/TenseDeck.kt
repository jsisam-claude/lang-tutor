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
     * Where the tense lives, so the reveal can underline it. Each tense has a
     * shape the bank writes it in; anything that does not match one is not an
     * item, because a reveal that cannot point at the form has nothing to say.
     */
    private fun findVerb(words: List<String>, tense: String): Verb? {
        val keys = words.map { ClozeDeck.key(it) }
        fun at(vararg i: Int) = i.toList()
        fun ing(from: Int): Int? = (from until keys.size).firstOrNull { keys[it].endsWith("ing") && keys[it].length > 4 }

        return when (tense) {
            "future-simple" -> {
                val i = keys.indexOfFirst { it in WILL }
                if (i < 0 || i + 1 !in keys.indices) null else Verb(at(i, i + 1), TenseCue.AUXILIARY)
            }
            "future-going-to" -> {
                val i = keys.indexOfFirst { it == "going" }
                val be = keys.take(i.coerceAtLeast(0)).indexOfLast { it in BE_PRESENT }
                if (i < 0 || be < 0 || i + 2 !in keys.indices) {
                    null
                } else {
                    Verb((be..i + 2).toList(), TenseCue.AUXILIARY)
                }
            }
            "present-progressive", "past-progressive" -> {
                // The -ing first, then the LAST be before it. Taking the first
                // be instead crosses a clause: "I am excited because we are
                // flying." would mark `am` … `flying`, an auxiliary and a
                // participle from two different verb phrases.
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
                val v = if (have < 0) null else participle(keys, have + 1)
                if (have < 0 || v == null) null else Verb(at(have, v), TenseCue.AUXILIARY)
            }
            "past-simple" -> {
                val did = keys.indexOfFirst { it in DID }
                if (did >= 0) {
                    val v = (did + 1 until keys.size).firstOrNull { keys[it] in verbs }
                    return if (v == null) null else Verb(at(did, v), TenseCue.AUXILIARY)
                }
                // A passive reads its tense off the be, not off the
                // participle: "The cake was baked by Mom." is `was baked`.
                val bePassive = keys.indices.firstOrNull { keys[it] in BE_PAST && participleAt(keys, it + 1) }
                if (bePassive != null) return Verb(at(bePassive, bePassive + 1), TenseCue.AUXILIARY)
                // Irregulars BEFORE the -ed scan: "We took a hundred photos."
                // would otherwise mark `hundred`, which ends in -ed and is not
                // a verb at all.
                val irr = keys.indices.firstOrNull { isIrregularPast(keys[it]) && !afterBe(keys, it) }
                if (irr != null) return Verb(at(irr), TenseCue.INFLECTION)
                val ed = keys.indices.firstOrNull { edVerbAt(keys, it) && !afterBe(keys, it) }
                if (ed != null) return Verb(at(ed), TenseCue.INFLECTION)
                val be = keys.indexOfFirst { it in BE_PAST }
                if (be >= 0) Verb(at(be), TenseCue.COPULA) else null
            }
            "present-simple" -> {
                val does = keys.indexOfFirst { it in DOES }
                if (does >= 0) {
                    val v = (does + 1 until keys.size).firstOrNull { keys[it] in verbs }
                    return if (v == null) null else Verb(at(does, v), TenseCue.AUXILIARY)
                }
                // A passive carries its tense on the be: "Bandages are kept in
                // a small box." is `are kept`, not a bare `kept`.
                val bePassive = keys.indices.firstOrNull { keys[it] in BE_PRESENT && participleAt(keys, it + 1) }
                if (bePassive != null) return Verb(at(bePassive, bePassive + 1), TenseCue.AUXILIARY)
                // A lexical verb before be: "The nurse washes her hands" beats
                // "The doctor is kind" whenever the line has both.
                val lex = keys.indices.firstOrNull { lexicalPresent(words, keys, it) }
                if (lex != null) return Verb(at(lex), TenseCue.INFLECTION)
                val be = keys.indexOfFirst { it in BE_PRESENT }
                if (be >= 0) Verb(at(be), TenseCue.COPULA) else null
            }
            else -> null
        }
    }

    /** A present-simple lexical verb: an attested stem, bare or wearing its
     *  third-person -s, standing somewhere a verb can stand — never first,
     *  never after *to* or a modal. */
    private fun lexicalPresent(words: List<String>, keys: List<String>, i: Int): Boolean {
        if (i == 0) return false
        val k = keys[i]
        // A capital away from the start is a name. The bank attests "mom" as a
        // verb — "Did Mom buy white paint?" puts it right after a did — and
        // without this "…because Mom is with me." marks `Mom`.
        if (words[i].firstOrNull()?.isUpperCase() == true) return false
        // A word governed by a determiner is the head of a noun phrase:
        // "I want to see the bees." must not mark `bees`.
        if (keys[i - 1] in OPENERS) return false
        if (k in BE_PRESENT || k in HAVE_PRESENT || k in DOES) return false
        if (keys[i - 1] == "to" || keys[i - 1] in ClozeClasses.MEMBERS.getValue(ClozeClasses.Kind.MODAL)) return false
        // What follows a be is its complement, not a second verb. Without this
        // "The water is warm." marks `water` — the bank attests `water` as a
        // verb ("Dad is watering the plants"), and the first match wins.
        if (afterBe(keys, i)) return false
        return k in verbs || thirdPersonStems(k).any { it in verbs }
    }

    /** Whether [i] sits directly after a be-form, where a complement lives. */
    private fun afterBe(keys: List<String>, i: Int): Boolean =
        i > 0 && (keys[i - 1] in BE_PRESENT || keys[i - 1] in BE_PAST)

    /** Whether [i] is a past participle: a -ed form the bank attests as a verb,
     *  or an irregular one. */
    private fun participleAt(keys: List<String>, i: Int): Boolean =
        i in keys.indices && (edVerbAt(keys, i) || isIrregularParticiple(keys[i]))

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


    /**
     * The stems a third-person present could have been spelled from.
     *
     * English writes the -s four ways and none of them is recoverable on its
     * own: *carries* could come from carry, *washes* from wash, *closes* from
     * close or clos. So every candidate is offered and the bank decides which
     * one it actually attests — the same refusal to author a rule that the
     * rest of this file makes.
     */
    private fun thirdPersonStems(k: String): List<String> = when {
        k.endsWith("ss") -> emptyList()
        k.endsWith("ies") && k.length > 4 -> listOf(k.dropLast(3) + "y")
        k.endsWith("es") && k.length > 3 -> listOf(k.dropLast(2), k.dropLast(1))
        k.endsWith("s") && k.length > 2 -> listOf(k.dropLast(1))
        else -> emptyList()
    }

    /**
     * The participle governed by an auxiliary at [from] - 1. Adverbs may sit
     * between them (*had already listened*), so the scan walks forward — but
     * it stops at the first participle rather than the first -ed-shaped word,
     * so a subordinate clause's verb is never mistaken for the main one.
     */
    private fun participle(keys: List<String>, from: Int): Int? =
        (from until keys.size).firstOrNull { participleAt(keys, it) }

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

        /** Determiners and possessives that open a subject noun phrase. */
        private val OPENERS = setOf(
            "the", "a", "an", "my", "your", "his", "her", "our", "their", "this", "that",
            "these", "those", "some", "two", "three", "four", "five", "many", "every", "all",
        )
    }
}
