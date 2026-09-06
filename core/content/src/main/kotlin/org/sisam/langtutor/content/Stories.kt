package org.sisam.langtutor.content

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Short stories a learner can actually read (`tuki-stories-v1`,
 * docs/short-stories.md).
 *
 * The rule that defines this file, and the reason it can exist at all: a
 * story may use ONLY words the phrasebank already teaches at or below its own
 * Level. That turns "does this fit the learner?" from a matter of taste into
 * a property `StoriesTest` checks, exactly the way the decodable readers a
 * school uses are built — you do not put a word in front of a reader before
 * you have taught it.
 *
 * The constraint is strict on purpose. No inflection is forgiven: if the bank
 * teaches "walk" but never "walked", a story below the Level that introduces
 * "walked" may not use it. Working inside that list is what makes the prose
 * plain, and plain is what a learner needs.
 *
 * Everything else follows the phrasebank's conventions: [he] is the natural
 * Hebrew MEANING rather than a word-for-word crib, [heF] appears only where
 * the written feminine differs, the Hebrew-letter pronunciation is derived at
 * runtime and never authored, and a story marked `review: pending` has not
 * yet had a native read.
 */
@Serializable
data class StoryPage(
    val en: String,
    /** The Hebrew MEANING of this page. */
    val he: String,
    /** Feminine variant, present only where the written forms differ. */
    @SerialName("he_f") val heF: String? = null,
)

@Serializable
data class Story(
    val id: String,
    /** Proficiency Level 1–7 (docs/learner-levels.md): the ceiling on the
     *  vocabulary this story is allowed to use, not a guess at difficulty. */
    val level: Int,
    val title: LocalizedText,
    val pages: List<StoryPage>,
    /** "pending" until a native speaker has read it end to end. */
    val review: String = "pending",
) {
    val reviewed: Boolean get() = review == "done"
}

@Serializable
data class StoryFile(
    val format: String,
    val notes: String? = null,
    val stories: List<Story>,
)

interface StoryRepository {
    suspend fun stories(): List<Story>
}

class ResourceStoryRepository(
    private val json: Json = ResourceContentRepository.DEFAULT_JSON,
) : StoryRepository {

    @Volatile private var cache: List<Story>? = null

    override suspend fun stories(): List<Story> = cache ?: withContext(Dispatchers.IO) {
        val stream = javaClass.classLoader?.getResourceAsStream(RESOURCE)
        // Absent is not an error: a build that ships no stories simply has no
        // story room, the same way a missing voice asset has no voice.
        val file: StoryFile = stream?.bufferedReader()?.use { json.decodeFromString(it.readText()) }
            ?: StoryFile(FORMAT, stories = emptyList())
        // A format this build does not know is dropped whole rather than
        // half-read, the rule the phrasebank and the twisters both follow.
        val stories = if (file.format == FORMAT) file.stories else emptyList()
        stories.also { cache = it }
    }

    companion object {
        const val FORMAT = "tuki-stories-v1"
        const val RESOURCE = "stories.json"
    }
}

/**
 * The vocabulary a learner has met by a given Level, derived from the
 * phrasebank rather than authored.
 *
 * Shared by the gate that admits a story and by anything else that needs to
 * ask "has this learner seen this word?", so the two can never disagree
 * about what has been taught.
 */
object TaughtWords {

    /** Words the bank uses at or below [level], lowercased, punctuation off. */
    fun upTo(sentences: List<PhraseSentence>, level: Int): Set<String> =
        sentences.asSequence()
            .filter { it.level <= level }
            .flatMap { tokens(it.en) }
            .toSet()

    /** The same tokenisation everywhere: split on spaces, drop trailing
     *  punctuation, lowercase. Contractions and hyphens stay inside a word. */
    fun tokens(text: String): List<String> =
        text.split(' ')
            .map { it.trimEnd('.', ',', '!', '?', ';', ':', '"').lowercase() }
            .filter { it.isNotEmpty() }
}
