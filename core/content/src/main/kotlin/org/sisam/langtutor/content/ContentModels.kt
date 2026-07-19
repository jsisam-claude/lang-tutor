package org.sisam.langtutor.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LocalizedText(val en: String, val he: String)

@Serializable
enum class AgeBand {
    @SerialName("4-6") AGES_4_6,
    @SerialName("5-8") AGES_5_8,
    @SerialName("7-10") AGES_7_10,
    @SerialName("9-12") AGES_9_12,
    @SerialName("11-13") AGES_11_13,
}

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
    val ageBand: AgeBand,
    val activities: List<Activity>,
)

@Serializable
data class UnitSummary(
    val id: String,
    val title: LocalizedText,
    val cefrLevel: String,
)
