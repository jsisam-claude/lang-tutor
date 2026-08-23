package org.sisam.langtutor.tutor

import org.sisam.langtutor.content.Activity
import org.sisam.langtutor.content.CurriculumUnit

/**
 * Decides WHICH lesson phrase a spoken attempt should be pronunciation-scored
 * against — by comparing the ASR transcript to every scoreable phrase in the
 * unit and taking the best word-overlap match.
 *
 * The previous behaviour was `RepeatAfterMe.firstOrNull()`: the unit's FIRST
 * phrase, regardless of what the child was asked or actually said. A child
 * answering the unit's third exercise was scored against the first one and
 * shown red marks for sounds they never attempted.
 *
 * Matching is deliberately dumb and transparent: lowercase, strip
 * punctuation, compare word sets. A candidate must pass three gates —
 * enough of the TARGET was said ([MIN_OVERLAP]), enough of what the child
 * SAID belongs to the target ([MIN_PRECISION], so free conversation that
 * merely mentions a lesson word is never marked), and at least one shared
 * word carries content (stopword overlap like "the…is" proves nothing).
 *
 * Known limit: a sentence differing only in its content noun ("I see a
 * little fish" against target "I see a little bird.") still matches — word
 * overlap cannot see which word carries the meaning. The GOP scorer then
 * marks the swapped word's sounds, which is noisy but not baseless: relative
 * to the target being practiced, that word WAS said differently.
 */
object TargetPicker {

    /** At least half the target's words must appear in the transcript. */
    const val MIN_OVERLAP = 0.5f

    /** …and at least half of what the child said must belong to the target —
     *  "my dog likes to play" is a story about the dog, not an attempt at the
     *  vocab word "dog". */
    const val MIN_PRECISION = 0.5f

    /** Words too common to signal an attempt on their own. */
    private val STOPWORDS = setOf(
        "the", "a", "an", "is", "are", "am", "i", "it", "to", "and", "of", "in", "on",
    )

    fun pick(transcript: String, unit: CurriculumUnit?): String? {
        if (unit == null) return null
        val said = words(transcript)
        if (said.isEmpty()) return null

        val candidates = unit.activities.flatMap { activity ->
            when (activity) {
                is Activity.RepeatAfterMe -> listOf(activity.phrase)
                is Activity.Vocab -> listOf(activity.word)
                is Activity.QuestionAnswer -> activity.expectedAnswers
            }
        }
        // Rank by target coverage; break ties by how MANY words matched, so a
        // full-sentence attempt is scored against the full sentence, not a
        // single vocab word that also matched perfectly ("the sun is yellow"
        // must pick "The sun is yellow." over "yellow").
        return candidates
            .map { candidate ->
                val target = words(candidate)
                val shared = said.intersect(target)
                Scored(
                    candidate = candidate,
                    ratio = if (target.isEmpty()) 0f else shared.size.toFloat() / target.size,
                    precision = if (said.isEmpty()) 0f else shared.size.toFloat() / said.size,
                    matched = shared.size,
                    hasContent = (shared - STOPWORDS).isNotEmpty(),
                )
            }
            .filter { it.ratio >= MIN_OVERLAP && it.precision >= MIN_PRECISION && it.hasContent }
            .maxWithOrNull(compareBy({ it.ratio }, { it.matched }))
            ?.candidate
    }

    private data class Scored(
        val candidate: String,
        val ratio: Float,
        val precision: Float,
        val matched: Int,
        val hasContent: Boolean,
    )

    private fun words(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{Nd}']+"))
            .filter { it.isNotBlank() }
            .toSet()
}
