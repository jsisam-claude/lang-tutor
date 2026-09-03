package org.sisam.langtutor.content

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Named word sets for the picture room (`tuki-picture-packs-v1`).
 *
 * The room used to teach whatever vocabulary the curriculum happened to hold
 * and happened to have art for, which is a fine default and a poor answer to
 * "I want to do numbers". A pack is a curated set with a name: numbers,
 * shapes, maths, animals.
 *
 * [PackWord.he] is the Hebrew MEANING, authored to the same conventions as
 * the phrasebank. The Hebrew-letter PRONUNCIATION is derived at runtime from
 * the phonemizer and is never authored — see HebrewTransliteration.
 */
@Serializable
data class PackWord(val en: String, val he: String)

@Serializable
data class PicturePack(
    val id: String,
    val title: LocalizedText,
    val words: List<PackWord>,
    /**
     * "pending" until a native speaker has read the pack end to end.
     *
     * Carried in the data rather than in a tracker so it cannot drift from
     * the content it describes, and so a reviewer can see at a glance which
     * sets are still owed a read.
     */
    val review: String = "pending",
) {
    val reviewed: Boolean get() = review == "done"
}

@Serializable
data class PicturePackFile(
    val format: String,
    val notes: String? = null,
    val packs: List<PicturePack>,
)

interface PicturePackRepository {
    suspend fun packs(): List<PicturePack>
}

class ResourcePicturePackRepository(
    private val json: Json = ResourceContentRepository.DEFAULT_JSON,
) : PicturePackRepository {

    @Volatile private var cache: List<PicturePack>? = null

    override suspend fun packs(): List<PicturePack> = cache ?: withContext(Dispatchers.IO) {
        val stream = javaClass.classLoader?.getResourceAsStream(RESOURCE)
            ?: error("Missing picture pack resource: $RESOURCE")
        val file: PicturePackFile = json.decodeFromString(stream.bufferedReader().use { it.readText() })
        // A format this build does not know is dropped whole rather than
        // half-read, the same rule the phrasebank and the twisters follow.
        val packs = if (file.format == FORMAT) file.packs else emptyList()
        packs.also { cache = it }
    }

    companion object {
        const val FORMAT = "tuki-picture-packs-v1"
        const val RESOURCE = "picture-packs.json"
    }
}
