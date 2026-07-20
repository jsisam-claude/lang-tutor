package org.sisam.langtutor.packs

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * User-approved enhancement downloads. Policy contract, enforced by design:
 *
 * - Every network operation starts from an explicit user action (an [install]
 *   collected after a consent dialog, or [checkForUpdatesManually] from a
 *   button). There is NO scheduled/background path in this interface — adding
 *   one is a design violation, not a configuration option.
 * - Downloads are inbound only; nothing is ever uploaded. No telemetry.
 * - Integrity: production implementation verifies [PackDescriptor.sha256]
 *   before a pack becomes [InstallState.Installed].
 */
interface PackRepository {

    val catalog: PackCatalog

    /** Current state per pack id (packs absent from the map = NotInstalled). */
    val installStates: Flow<Map<String, InstallState>>

    /** Packs this device may offer (RAM gate; storage is checked at consent time). */
    fun eligiblePacks(deviceRamGb: Int): List<PackDescriptor>

    /**
     * Cold flow; collection starts the download. Emits
     * Downloading(0..100) → Verifying → Installed, or Failed.
     */
    fun install(packId: String): Flow<InstallState>

    /** Deletes pack files; a real, complete removal. */
    suspend fun delete(packId: String)

    /**
     * THE ONLY update path — compares installed pack versions against the
     * catalog and returns available updates. Never called automatically.
     */
    suspend fun checkForUpdatesManually(): List<PackUpdate>
}

/** Loads the catalog bundled as a java resource (shipped with app updates). */
object ResourceCatalogLoader {

    val DEFAULT_JSON = Json { ignoreUnknownKeys = true }

    fun load(json: Json = DEFAULT_JSON): PackCatalog {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("packs/catalog.json")) {
            "Missing bundled pack catalog"
        }
        return json.decodeFromString(stream.bufferedReader().use { it.readText() })
    }
}
