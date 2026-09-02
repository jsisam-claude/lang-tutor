package org.sisam.langtutor.profile

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

interface LearnerProfileStore {
    val profile: Flow<LearnerProfile>

    /**
     * The current value, without suspending.
     *
     * Some callers cannot suspend and cannot wait: the LLM backend ladder
     * decides which accelerator to try from inside a non-suspend section, and
     * a chat turn reads a display setting while assembling a request. The
     * alternative — mirroring settings into volatile fields from a collector —
     * looked cheaper and was not: the collector has to start somewhere, and
     * starting it in AppContainer's init block read `profile` before the
     * property was assigned and crashed the app on launch with an NPE. Both
     * implementations are StateFlow-backed, so the value is always there.
     */
    fun snapshot(): LearnerProfile

    suspend fun current(): LearnerProfile
    suspend fun update(transform: (LearnerProfile) -> LearnerProfile)
}

class InMemoryProfileStore(
    initial: LearnerProfile = LearnerProfile.EMPTY,
) : LearnerProfileStore {

    private val state = MutableStateFlow(initial)
    override val profile: StateFlow<LearnerProfile> = state

    override fun snapshot(): LearnerProfile = state.value

    override suspend fun current(): LearnerProfile = state.value

    override suspend fun update(transform: (LearnerProfile) -> LearnerProfile) {
        state.value = transform(state.value)
    }
}

/**
 * JSON file with atomic replace (tmp + move). Plain kotlinx-serialization rather
 * than DataStore keeps this module pure-JVM and identically testable; DataStore
 * can replace the impl behind the same interface if IPC-safety ever demands it.
 */
class JsonFileProfileStore(
    private val file: Path,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true },
) : LearnerProfileStore {

    private val mutex = Mutex()
    private val state = MutableStateFlow(readFromDisk())
    override val profile: StateFlow<LearnerProfile> = state

    override fun snapshot(): LearnerProfile = state.value

    override suspend fun current(): LearnerProfile = state.value

    override suspend fun update(transform: (LearnerProfile) -> LearnerProfile) {
        mutex.withLock {
            val updated = transform(state.value)
            withContext(Dispatchers.IO) { writeToDisk(updated) }
            state.value = updated
        }
    }

    private fun readFromDisk(): LearnerProfile {
        if (!Files.exists(file)) return LearnerProfile.EMPTY
        return runCatching {
            json.decodeFromString(LearnerProfile.serializer(), String(Files.readAllBytes(file), Charsets.UTF_8))
        }.getOrDefault(LearnerProfile.EMPTY)
    }

    private fun writeToDisk(profile: LearnerProfile) {
        file.parent?.let(Files::createDirectories)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        // Files.write, not Files.writeString: the latter is a Java 11 convenience
        // Android's libcore never gained — it crashed the tablet's first save
        // (NoSuchMethodError) while the Pixels' newer ART happened to carry it.
        // scripts/check-android-api.py now fails the build on any such call.
        Files.write(tmp, json.encodeToString(LearnerProfile.serializer(), profile).toByteArray(Charsets.UTF_8))
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
