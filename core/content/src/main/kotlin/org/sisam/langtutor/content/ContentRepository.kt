package org.sisam.langtutor.content

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

interface ContentRepository {
    suspend fun listUnits(): List<UnitSummary>
    suspend fun loadUnit(id: String): CurriculumUnit
}

/**
 * Reads curriculum JSON from java resources. The same files are packaged into
 * the APK via this module's jar, so JVM tests and the app read one canonical
 * copy (core/content/src/main/resources/curriculum/).
 */
class ResourceContentRepository(
    private val json: Json = DEFAULT_JSON,
) : ContentRepository {

    override suspend fun listUnits(): List<UnitSummary> = withContext(Dispatchers.IO) {
        json.decodeFromString(readResource("curriculum/index.json"))
    }

    override suspend fun loadUnit(id: String): CurriculumUnit = withContext(Dispatchers.IO) {
        json.decodeFromString(readResource("curriculum/$id.json"))
    }

    private fun readResource(path: String): String {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream(path)) {
            "Missing curriculum resource: $path"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    companion object {
        val DEFAULT_JSON = Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
        }
    }
}
