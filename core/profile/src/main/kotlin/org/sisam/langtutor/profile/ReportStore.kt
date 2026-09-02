package org.sisam.langtutor.profile

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One AI reply a person flagged as wrong or inappropriate.
 *
 * The report mechanism generated-content policy requires
 * (docs/feasibility.md risk #5) — and the honest version of it for an app
 * with no backend: reports are DEVICE-LOCAL, reviewed in the Parent Zone,
 * and never transmitted, exactly like the rest of the learner's data.
 * Flagging also serves the learner directly: the flagged line is on record
 * for the adult in charge, instead of scrolling away.
 */
@Serializable
data class FlaggedReply(
    val text: String,
    /** Which room said it ("chat", "lesson") — the reviewer's first question. */
    val room: String,
    val atMillis: Long,
)

interface ReportStore {
    val reports: StateFlow<List<FlaggedReply>>
    suspend fun add(report: FlaggedReply)
    suspend fun clear()
}

class InMemoryReportStore : ReportStore {
    private val state = MutableStateFlow<List<FlaggedReply>>(emptyList())
    override val reports: StateFlow<List<FlaggedReply>> = state

    override suspend fun add(report: FlaggedReply) {
        state.value = state.value + report
    }

    override suspend fun clear() {
        state.value = emptyList()
    }
}

/**
 * File-backed, same shape as [JsonFileProfileStore]: atomic replace via a
 * temp file, newest last, capped so a tap-happy day cannot grow unbounded.
 */
class JsonFileReportStore(
    private val path: Path,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ReportStore {

    private val serializer = ListSerializer(FlaggedReply.serializer())
    private val mutex = Mutex()
    private val state = MutableStateFlow(load())
    override val reports: StateFlow<List<FlaggedReply>> = state

    private fun load(): List<FlaggedReply> = runCatching {
        if (Files.exists(path)) {
            json.decodeFromString(serializer, String(Files.readAllBytes(path), Charsets.UTF_8))
        } else {
            emptyList()
        }
    }.getOrDefault(emptyList())

    override suspend fun add(report: FlaggedReply) = write { it + report }

    override suspend fun clear() = write { emptyList() }

    private suspend fun write(transform: (List<FlaggedReply>) -> List<FlaggedReply>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val next = transform(state.value).takeLast(MAX_REPORTS)
                state.value = next
                runCatching {
                    val tmp = path.resolveSibling("${path.fileName}.tmp")
                    Files.createDirectories(path.parent)
                    // Files.write, not writeString — see LearnerProfileStore.
                    Files.write(tmp, json.encodeToString(serializer, next).toByteArray(Charsets.UTF_8))
                    Files.move(
                        tmp, path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                }
                Unit
            }
        }

    private companion object {
        const val MAX_REPORTS = 200
    }
}
