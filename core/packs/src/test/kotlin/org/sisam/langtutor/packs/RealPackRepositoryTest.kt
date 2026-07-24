package org.sisam.langtutor.packs

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealPackRepositoryTest {

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun tempRoot(): File = Files.createTempDirectory("packs-test").toFile()

    private fun pack(
        id: String,
        sha: String,
        size: Long,
        installPath: String = "models/$id.bin",
        version: Int = 1,
        ramGb: Int = 0,
    ) = PackDescriptor(
        id = id, version = version, nameEn = id, nameHe = id,
        descriptionEn = "", descriptionHe = "", kind = PackKind.LLM,
        sizeBytes = size, sha256 = sha, url = "https://example.test/$id.bin",
        installPath = installPath, minRamGb = ramGb,
    )

    /** Serves bytes from memory; honors resume when [supportsRange]. */
    private class FakeFetcher(val data: ByteArray, val supportsRange: Boolean = true) : PackFetcher {
        var lastOffset = -1L
        override suspend fun open(url: String, offset: Long): FetchResult {
            lastOffset = offset
            return if (offset > 0 && supportsRange) {
                FetchResult(
                    ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt()),
                    totalBytes = data.size.toLong(),
                    startedAt = offset,
                )
            } else {
                FetchResult(ByteArrayInputStream(data), totalBytes = data.size.toLong(), startedAt = 0L)
            }
        }
    }

    @Test
    fun `install downloads verifies and lands the file at its install path`() = runTest {
        val root = tempRoot()
        val data = ByteArray(5000) { (it % 251).toByte() }
        val descriptor = pack("llm-x", sha256(data), data.size.toLong(), installPath = "models/gemma.litertlm")
        val repo = RealPackRepository(
            PackCatalog(1, listOf(descriptor)), root,
            FakeFetcher(data), UnconfinedTestDispatcher(testScheduler),
        )

        val states = repo.install("llm-x").toList()

        assertTrue(states.any { it is InstallState.Downloading })
        assertTrue(states.contains(InstallState.Verifying))
        assertEquals(InstallState.Installed(1), states.last())
        val target = File(root, "models/gemma.litertlm")
        assertTrue(target.exists())
        assertTrue(target.readBytes().contentEquals(data))
        assertFalse(File(root, "models/gemma.litertlm.part").exists())
        assertEquals(InstallState.Installed(1), repo.installStates.value["llm-x"])
    }

    @Test
    fun `checksum mismatch fails and leaves no installed file`() = runTest {
        val root = tempRoot()
        val data = ByteArray(4096) { 7 }
        val descriptor = pack("llm-bad", sha = "a".repeat(64), size = data.size.toLong())
        val repo = RealPackRepository(
            PackCatalog(1, listOf(descriptor)), root,
            FakeFetcher(data), UnconfinedTestDispatcher(testScheduler),
        )

        val states = repo.install("llm-bad").toList()

        assertTrue(states.last() is InstallState.Failed)
        assertFalse(File(root, "models/llm-bad.bin").exists())
    }

    @Test
    fun `resume continues from the partial file`() = runTest {
        val root = tempRoot()
        val data = ByteArray(8000) { (it % 97).toByte() }
        val installPath = "models/resume.bin"
        File(root, "models").mkdirs()
        File(root, "$installPath.part").writeBytes(data.copyOfRange(0, 3000))
        val fetcher = FakeFetcher(data, supportsRange = true)
        val descriptor = pack("llm-r", sha256(data), data.size.toLong(), installPath = installPath)
        val repo = RealPackRepository(
            PackCatalog(1, listOf(descriptor)), root, fetcher, UnconfinedTestDispatcher(testScheduler),
        )

        val states = repo.install("llm-r").toList()

        assertEquals(3000L, fetcher.lastOffset) // asked the server to resume from the partial size
        assertEquals(InstallState.Installed(1), states.last())
        assertTrue(File(root, installPath).readBytes().contentEquals(data))
    }

    @Test
    fun `delete removes the file and clears the version, update check reflects catalog`() = runTest {
        val root = tempRoot()
        val data = ByteArray(1000) { 1 }
        val descriptor = pack("llm-d", sha256(data), data.size.toLong(), version = 1)
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = RealPackRepository(PackCatalog(1, listOf(descriptor)), root, FakeFetcher(data), dispatcher)
        repo.install("llm-d").toList()
        assertTrue(File(root, "models/llm-d.bin").exists())

        // A newer catalog (version 2) offers an update for the installed pack.
        val newer = RealPackRepository(
            PackCatalog(2, listOf(descriptor.copy(version = 2))), root, FakeFetcher(data), dispatcher,
        )
        assertEquals(listOf(PackUpdate("llm-d", 1, 2)), newer.checkForUpdatesManually())

        repo.delete("llm-d")
        assertFalse(File(root, "models/llm-d.bin").exists())
        assertEquals(InstallState.NotInstalled, repo.installStates.value["llm-d"])
        assertTrue(repo.checkForUpdatesManually().isEmpty())
    }

    @Test
    fun `allowInsecureTls routes the download through the insecure fetcher`() = runTest {
        val root = tempRoot()
        val data = ByteArray(1000) { 3 }
        val normal = FakeFetcher(data)
        val insecure = FakeFetcher(data)
        val descriptor = pack("llm-i", sha256(data), data.size.toLong())
        val repo = RealPackRepository(
            PackCatalog(1, listOf(descriptor)), root, normal,
            UnconfinedTestDispatcher(testScheduler), insecureFetcher = insecure,
        )
        repo.allowInsecureTls = true

        repo.install("llm-i").toList()

        assertEquals(-1L, normal.lastOffset)  // secure fetcher untouched
        assertEquals(0L, insecure.lastOffset) // insecure fetcher used instead
        assertTrue(File(root, "models/llm-i.bin").exists())
    }

    @Test
    fun `eligiblePacks respects the RAM gate`() {
        val root = tempRoot()
        val small = pack("small", "0".repeat(64), 10, ramGb = 0)
        val big = pack("big", "0".repeat(64), 10, ramGb = 16)
        val repo = RealPackRepository(PackCatalog(1, listOf(small, big)), root, FakeFetcher(ByteArray(0)))

        assertEquals(listOf("small"), repo.eligiblePacks(12).map { it.id })
        assertEquals(setOf("small", "big"), repo.eligiblePacks(16).map { it.id }.toSet())
    }
}
