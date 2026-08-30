package org.sisam.langtutor.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LocalizedText(val en: String, val he: String)



/**
 * Polymorphic activity types, discriminated by a "type" field in JSON. New
 * activity kinds (P2: phonics, tracing, readers…) are added as new subtypes —
 * old content files keep deserializing (schemaVersion gates migrations).
 */
@Serializable
sealed interface Activity {

    @Serializable
    @SerialName("vocab")
    data class Vocab(
        val word: String,
        val translation: LocalizedText,
    ) : Activity

    @Serializable
    @SerialName("repeatAfterMe")
    data class RepeatAfterMe(
        val phrase: String,
    ) : Activity

    @Serializable
    @SerialName("questionAnswer")
    data class QuestionAnswer(
        val prompt: LocalizedText,
        val expectedAnswers: List<String>,
    ) : Activity
}

@Serializable
data class CurriculumUnit(
    val schemaVersion: Int,
    val id: String,
    val title: LocalizedText,
    val cefrLevel: String,
    /**
     * The unit's proficiency Level, 1–7 (docs/learner-levels.md). Replaced
     * the age bands — content difficulty is a fact about the CONTENT, and
     * the learners it fits are of every age. Old bands mapped 4-6→1, 5-8→2,
     * 7-10→3, 9-12→4, 11-13→5 when the files were migrated.
     */
    val level: Int,
    val activities: List<Activity>,
)

@Serializable
data class UnitSummary(
    val id: String,
    val title: LocalizedText,
    val cefrLevel: String,
)
