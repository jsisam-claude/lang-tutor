package org.sisam.langtutor.packs

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * Deterministic in-memory implementation so the app and tests exercise the full
 * consent → download → verify → installed → delete flow with no network.
 */
class FakePackRepository(
    override val catalog: PackCatalog = ResourceCatalogLoader.load(),
) : PackRepository {

    private val installedVersions = mutableMapOf<String, Int>()
    private val states = MutableStateFlow<Map<String, InstallState>>(emptyMap())

    override val installStates: StateFlow<Map<String, InstallState>> = states

    override fun eligiblePacks(deviceRamGb: Int): List<PackDescriptor> =
        catalog.packs.filter { it.minRamGb <= deviceRamGb }

    override fun install(packId: String): Flow<InstallState> = flow {
        val pack = requireNotNull(catalog.packs.find { it.id == packId }) {
            "Unknown pack: $packId"
        }
        for (percent in PROGRESS_STEPS) {
            update(packId, InstallState.Downloading(percent))
            emit(InstallState.Downloading(percent))
            delay(STEP_DELAY_MS)
        }
        update(packId, InstallState.Verifying)
        emit(InstallState.Verifying)
        delay(STEP_DELAY_MS)
        installedVersions[packId] = pack.version
        val installed = InstallState.Installed(pack.version)
        update(packId, installed)
        emit(installed)
    }

    override suspend fun delete(packId: String) {
        installedVersions.remove(packId)
        update(packId, InstallState.NotInstalled)
    }

    override suspend fun checkForUpdatesManually(): List<PackUpdate> =
        installedVersions.mapNotNull { (id, installedVersion) ->
            val available = catalog.packs.find { it.id == id }?.version ?: return@mapNotNull null
            if (available > installedVersion) PackUpdate(id, installedVersion, available) else null
        }

    /** Test helper: mark a pack as already installed at [version]. */
    fun preinstall(packId: String, version: Int) {
        installedVersions[packId] = version
        update(packId, InstallState.Installed(version))
    }

    private fun update(packId: String, state: InstallState) {
        states.value = states.value + (packId to state)
    }

    companion object {
        private val PROGRESS_STEPS = listOf(0, 25, 50, 75, 100)
        private const val STEP_DELAY_MS = 25L
    }
}
