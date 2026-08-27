package org.sisam.langtutor.tutor.drill

import kotlin.random.Random
import org.sisam.langtutor.content.Activity
import org.sisam.langtutor.content.CurriculumUnit

/** Difficulty is sentence LENGTH, which a pre-reader can feel even though
 *  they cannot read the label. */
enum class DrillLevel { WORDS, SHORT, LONG }

/**
 * One thing to say, and its Hebrew meaning when we have a trustworthy one.
 *
 * [hebrew] is null unless the curriculum already carried it. That is the only
 * source here that is authored and reviewed; a model-written translation is a
 * different trust class and belongs to whatever produced the line, not to the
 * deck. Null renders as no translation row, which is the honest outcome.
 */
data class DrillItem(
    val text: String,
    val level: DrillLevel,
    val hebrew: String? = null,
)

/**
 * Builds practice rounds from the curriculum that already exists — the deck is
 * derived, not authored, so every future unit feeds it automatically.
 *
 * Three sources per unit: vocab words, the repeat-after-me phrases, and the
 * FULLEST expected answer of each question (that longest variant is the model
 * sentence; the short ones are just accepted answers). Bucketed by word count:
 * one word is WORDS, two to four is SHORT, five and up is LONG.
 */
object DrillDeck {

    fun classify(text: String): DrillLevel = when (WordMatch.tokens(text).size) {
        0, 1 -> DrillLevel.WORDS
        in 2..4 -> DrillLevel.SHORT
        else -> DrillLevel.LONG
    }

    /** Every distinct drillable line at [level], in curriculum order. */
    fun pool(units: List<CurriculumUnit>, level: DrillLevel): List<DrillItem> {
        val seen = mutableSetOf<List<String>>()
        val out = mutableListOf<DrillItem>()
        for (unit in units) {
            for (activity in unit.activities) {
                // Vocab is the one activity that already carries a reviewed
                // Hebrew translation, so it is the one that can show one.
                // A question's prompt.he translates the QUESTION, not the
                // answer we drill, so it would be the wrong Hebrew — worse
                // than none.
                val line = when (activity) {
                    is Activity.Vocab -> activity.word
                    is Activity.RepeatAfterMe -> activity.phrase
                    is Activity.QuestionAnswer -> activity.expectedAnswers.maxByOrNull { it.length }
                } ?: continue
                val hebrew = (activity as? Activity.Vocab)?.translation?.he?.takeIf { it.isNotBlank() }
                if (classify(line) != level) continue
                // Dedupe on the words, not the spelling — "Red!" and "red"
                // are the same drill item.
                if (!seen.add(WordMatch.tokens(line))) continue
                out += DrillItem(line, level, hebrew)
            }
        }
        return out
    }

    /** A shuffled round of at most [sizeFor] items. */
    fun round(units: List<CurriculumUnit>, level: DrillLevel, random: Random): List<DrillItem> =
        pool(units, level).shuffled(random).take(sizeFor(level))

    /** Longer sentences are more work per item, so rounds shrink with level. */
    fun sizeFor(level: DrillLevel): Int = when (level) {
        DrillLevel.WORDS -> 8
        DrillLevel.SHORT -> 6
        DrillLevel.LONG -> 5
    }
}
