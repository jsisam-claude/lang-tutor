package org.sisam.langtutor.content

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The authored sentence bank (`tuki-phrasebank-v1`, docs/phrasebank.md):
 * English/Hebrew pairs per proficiency Level 1–7, with tense and frame tags
 * and optional word-alignment cues. Authored and lint-gated
 * (scripts/phrasebank-lint.py), so unlike model output it needs no runtime
 * gauntlet — what is in the file is what may be shown.
 */
@Serializable
data class PhraseSentence(
    val id: String,
    /** Proficiency Level 1–7 (docs/learner-levels.md). */
    val level: Int,
    val tense: String,
    /** The repeatable sentence pattern, for slot drills. */
    val frame: String,
    val en: String,
    /** The Hebrew MEANING — natural sentence, masculine first person. */
    val he: String,
    /** Feminine first-person variant, present only where forms differ. */
    @SerialName("he_f") val heF: String? = null,
    /** EN-span ↔ HE-span cues for cross-highlighting; null when unaligned. */
    val align: List<AlignCue>? = null,
)

/** [en]/[he] are [startIndex, endIndex] (inclusive) into the word lists. */
@Serializable
data class AlignCue(val en: List<Int>, val he: List<Int>)

@Serializable
data class PhrasebankFile(
    val format: String,
    val theme: String,
    val title: LocalizedText? = null,
    val notes: String? = null,
    val sentences: List<PhraseSentence>,
)

interface PhrasebankRepository {
    /** Every sentence from every installed theme, load-once. */
    suspend fun sentences(): List<PhraseSentence>
}

/**
 * Reads phrasebank JSON from java resources — same doctrine as
 * [ResourceContentRepository]: one canonical copy serves JVM tests and the
 * APK. The index lists theme file stems so adding a batch is a data change.
 */
class ResourcePhrasebankRepository(
    private val json: Json = ResourceContentRepository.DEFAULT_JSON,
) : PhrasebankRepository {

    @Volatile private var cache: List<PhraseSentence>? = null

    override suspend fun sentences(): List<PhraseSentence> = cache ?: withContext(Dispatchers.IO) {
        val themes: List<String> = json.decodeFromString(readResource("phrasebank/index.json"))
        themes.flatMap { stem ->
            val file: PhrasebankFile = json.decodeFromString(readResource("phrasebank/$stem.json"))
            // A format this build does not know is skipped whole rather than
            // half-read: the bank is teaching material, and a misparsed row
            // is worse than a missing theme.
            if (file.format == FORMAT) file.sentences else emptyList()
        }.also { cache = it }
    }

    private fun readResource(path: String): String {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream(path)) {
            "Missing phrasebank resource: $path"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    companion object {
        const val FORMAT = "tuki-phrasebank-v1"
    }
}
