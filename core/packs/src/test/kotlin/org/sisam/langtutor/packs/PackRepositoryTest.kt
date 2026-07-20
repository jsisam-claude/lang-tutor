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
        assertEquals(6, catalog.packs.size)
        assertEquals(catalog.packs.size, catalog.packs.map { it.id }.toSet().size)
        assertTrue(catalog.packs.any { it.kind == PackKind.LLM })
    }

    @Test
    fun `install walks download - verify - installed and updates shared state`() = runTest {
        val repo = FakePackRepository()
        val states = repo.install("voices-hvpt").toList()

        assertEquals(InstallState.Downloading(0), states.first())
        assertTrue(states.contains(InstallState.Downloading(100)))
        assertTrue(states.contains(InstallState.Verifying))
        assertEquals(InstallState.Installed(1), states.last())

        assertEquals(InstallState.Installed(1), repo.installStates.value["voices-hvpt"])
    }

    @Test
    fun `eligiblePacks gates on device RAM`() = runTest {
        val repo = FakePackRepository()
        val on12gb = repo.eligiblePacks(deviceRamGb = 12).map { it.id }
        assertTrue("asr-en-pro" in on12gb)
        assertTrue("llm-quality-e4b" !in on12gb)
        assertTrue("llm-advanced-8b" !in on12gb)

        val on16gb = repo.eligiblePacks(deviceRamGb = 16).map { it.id }
        assertEquals(repo.catalog.packs.size, on16gb.size)
    }

    @Test
    fun `manual update check reports only outdated installed packs`() = runTest {
        val repo = FakePackRepository()
        repo.preinstall("content-readers-1", version = 0)
        repo.preinstall("voices-hvpt", version = 1)

        val updates = repo.checkForUpdatesManually()
        assertEquals(listOf(PackUpdate("content-readers-1", 0, 1)), updates)
    }

    @Test
    fun `delete returns pack to NotInstalled`() = runTest {
        val repo = FakePackRepository()
        repo.install("content-readers-1").toList()
        repo.delete("content-readers-1")

        assertEquals(InstallState.NotInstalled, repo.installStates.value["content-readers-1"])
        assertTrue(repo.checkForUpdatesManually().isEmpty())
    }
}
