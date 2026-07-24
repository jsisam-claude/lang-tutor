package org.sisam.langtutor.packs

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackRepositoryTest {

    @Test
    fun `bundled catalog loads with unique ids`() {
        val catalog = ResourceCatalogLoader.load()
        assertEquals(2, catalog.packs.size)
        assertEquals(catalog.packs.size, catalog.packs.map { it.id }.toSet().size)
        assertTrue(catalog.packs.any { it.kind == PackKind.LLM })
    }

    @Test
    fun `install walks download - verify - installed and updates shared state`() = runTest {
        val repo = FakePackRepository()
        val states = repo.install("llm-base-e2b").toList()

        assertEquals(InstallState.Downloading(0), states.first())
        assertTrue(states.contains(InstallState.Downloading(100)))
        assertTrue(states.contains(InstallState.Verifying))
        assertEquals(InstallState.Installed(1), states.last())

        assertEquals(InstallState.Installed(1), repo.installStates.value["llm-base-e2b"])
    }

    @Test
    fun `eligiblePacks gates on device RAM`() = runTest {
        val repo = FakePackRepository()

        val on8gb = repo.eligiblePacks(deviceRamGb = 8).map { it.id }
        assertTrue("llm-base-e2b" in on8gb)       // Pixel 9a: base tier only
        assertTrue("llm-quality-e4b" !in on8gb)

        val on12gb = repo.eligiblePacks(deviceRamGb = 12).map { it.id }
        assertTrue("llm-base-e2b" in on12gb)      // Pixel 9 gets the base tier…
        assertTrue("llm-quality-e4b" in on12gb)   // …and the E4B quality tier (gated at 12 GB)

        val on16gb = repo.eligiblePacks(deviceRamGb = 16).map { it.id }
        assertEquals(repo.catalog.packs.size, on16gb.size)
    }

    @Test
    fun `manual update check reports only outdated installed packs`() = runTest {
        val repo = FakePackRepository()
        repo.preinstall("llm-base-e2b", version = 0)
        repo.preinstall("llm-quality-e4b", version = 1)

        val updates = repo.checkForUpdatesManually()
        assertEquals(listOf(PackUpdate("llm-base-e2b", 0, 1)), updates)
    }

    @Test
    fun `delete returns pack to NotInstalled`() = runTest {
        val repo = FakePackRepository()
        repo.install("llm-base-e2b").toList()
        repo.delete("llm-base-e2b")

        assertEquals(InstallState.NotInstalled, repo.installStates.value["llm-base-e2b"])
        assertTrue(repo.checkForUpdatesManually().isEmpty())
    }
}
