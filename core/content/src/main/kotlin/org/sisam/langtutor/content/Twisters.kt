package org.sisam.langtutor.content

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Tongue twisters (`tuki-twisters-v1`, docs/tongue-twisters.md).
 *
 * These are NOT phrasebank sentences and deliberately do not live in it. A
 * phrasebank line is picked for the grammar it carries at a Level; a twister
 * is picked for one SOUND — one English phoneme Hebrew does not have, or one
 * contrast Hebrew speakers collapse — and its level says how hard the line is
 * to say, not what tense it teaches. Mixing the two would corrupt the
 * grammar ladder that the drill deck draws from.
 *
 * Everything else follows the phrasebank's conventions exactly: [he] is the
 * meaning rather than a word-for-word crib, [heF] appears only where the
 * written feminine differs, transliteration is derived at runtime and never
 * authored, and levels 1–3 carry alignment cues.
 */
@Serializable
data class TwisterSound(
    /** Stable key referenced by [Twister.sound]. */
    val key: String,
    /** IPA for the target phone(s) — what the pronunciation coach scores. */
    val ipa: String,
    /** An everyday word carrying the sound, for the picker card. */
    val example: String,
    val label: LocalizedText,
)

@Serializable
data class Twister(
    val id: String,
    /** How hard the line is to SAY, 1–7 — not a grammar level. */
    val level: Int,
    /** [TwisterSound.key] this line drills. */
    val sound: String,
    val en: String,
    /** The Hebrew MEANING — natural sentence, masculine second person. */
    val he: String,
    @SerialName("he_f") val heF: String? = null,
    val align: List<AlignCue>? = null,
)

@Serializable
data class TwisterFile(
    val format: String,
    val notes: String? = null,
    val sounds: List<TwisterSound>,
    val twisters: List<Twister>,
)

/** The twisters, and the sounds they are organised by. */
data class TwisterBook(val sounds: List<TwisterSound>, val twisters: List<Twister>) {

    /** Lines drilling [key], easiest to say first. */
    fun forSound(key: String): List<Twister> =
        twisters.filter { it.sound == key }.sortedBy { it.level }

    /** Sounds that actually have lines, in file order — what the picker shows. */
    fun playableSounds(): List<TwisterSound> =
        sounds.filter { s -> twisters.any { it.sound == s.key } }

    companion object {
        val EMPTY = TwisterBook(emptyList(), emptyList())
    }
}

interface TwisterRepository {
    suspend fun book(): TwisterBook
}

/**
 * Reads the twisters from java resources — same doctrine as the phrasebank:
 * one canonical copy serves JVM tests and the APK.
 */
class ResourceTwisterRepository(
    private val json: Json = ResourceContentRepository.DEFAULT_JSON,
) : TwisterRepository {

    @Volatile private var cache: TwisterBook? = null

    override suspend fun book(): TwisterBook = cache ?: withContext(Dispatchers.IO) {
        val stream = javaClass.classLoader?.getResourceAsStream(RESOURCE)
            ?: error("Missing twister resource: $RESOURCE")
        val file: TwisterFile = json.decodeFromString(stream.bufferedReader().use { it.readText() })
        // A format this build does not know is dropped whole rather than
        // half-read, exactly as the phrasebank does: this is teaching
        // material, and a misparsed line is worse than a missing room.
        val book = if (file.format == FORMAT) {
            TwisterBook(file.sounds, file.twisters)
        } else {
            TwisterBook.EMPTY
        }
        book.also { cache = it }
    }

    companion object {
        const val FORMAT = "tuki-twisters-v1"
        const val RESOURCE = "twisters.json"
    }
}
