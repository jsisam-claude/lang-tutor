package org.sisam.langtutor.packs

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Real, on-device implementation of [PackRepository]: streams a pack to
 * `installRoot/<pack.resolvedInstallPath>`, checksum-verifies it, and records
 * the installed version — so a consented download in the Parent Zone lands the
 * model exactly where the engine loads it (e.g. `models/gemma-4-E4B-it.litertlm`),
 * with no adb push. Honors the [PackRepository] policy: every download is
 * user-initiated, inbound only, no telemetry.
 *
 * Pure-JVM (network via the injected [PackFetcher]) so the whole flow is unit
 * tested without a device or a real server.
 */
class RealPackRepository(
    override val catalog: PackCatalog,
    private val installRoot: File,
    private val fetcher: PackFetcher = HttpPackFetcher(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : PackRepository {

    private val manifestFile = File(installRoot, MANIFEST_NAME)
    private val states = MutableStateFlow(initialStates())
    override val installStates: StateFlow<Map<String, InstallState>> = states

    override fun eligiblePacks(deviceRamGb: Int): List<PackDescriptor> =
        catalog.packs.filter { it.minRamGb <= deviceRamGb }

    override fun install(packId: String): Flow<InstallState> = flow {
        val pack = requireNotNull(catalog.packs.find { it.id == packId }) { "Unknown pack: $packId" }
        val target = File(installRoot, pack.resolvedInstallPath)
        val part = File(target.parentFile ?: installRoot, target.name + PART_SUFFIX)
        target.parentFile?.mkdirs()
        try {
            var have = if (part.exists()) part.length() else 0L
            val result = fetcher.open(pack.url, have)
            val total = result.totalBytes.takeIf { it > 0 } ?: pack.sizeBytes
            val digest = MessageDigest.getInstance("SHA-256")

            // Resume only if the server actually served from where we left off
            // (206 Partial at our offset); otherwise it sent the whole file, so
            // overwrite from the start.
            val append = result.startedAt > 0L && result.startedAt == have
            if (append) {
                part.inputStream().use { it.digestInto(digest) }
            } else {
                have = 0L
            }

            emit(publish(packId, InstallState.Downloading(percent(have, total))))
            result.stream.use { input ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        have += read
                        val pct = percent(have, total)
                        if (pct != lastPercent) {
                            lastPercent = pct
                            emit(publish(packId, InstallState.Downloading(pct)))
                        }
                    }
                }
            }

            emit(publish(packId, InstallState.Verifying))
            val actual = digest.digest().toHex()
            val expected = pack.sha256.lowercase()
            if (expected.isChecksum() && actual != expected) {
                part.delete()
                emit(publish(packId, InstallState.Failed("Checksum mismatch")))
                return@flow
            }

            target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            recordInstalled(packId, pack.version)
            emit(publish(packId, InstallState.Installed(pack.version)))
        } catch (t: Throwable) {
            emit(publish(packId, InstallState.Failed(t.message ?: "download failed")))
        }
    }.flowOn(ioDispatcher)

    override suspend fun delete(packId: String) = withContext(ioDispatcher) {
        val pack = catalog.packs.find { it.id == packId }
        if (pack != null) {
            File(installRoot, pack.resolvedInstallPath).delete()
            File(installRoot, pack.resolvedInstallPath + PART_SUFFIX).delete()
        }
        writeManifest(readManifest() - packId)
        states.value = states.value + (packId to InstallState.NotInstalled)
    }

    override suspend fun checkForUpdatesManually(): List<PackUpdate> = withContext(ioDispatcher) {
        readManifest().mapNotNull { (id, installedVersion) ->
            val available = catalog.packs.find { it.id == id }?.version ?: return@mapNotNull null
            if (available > installedVersion) PackUpdate(id, installedVersion, available) else null
        }
    }

    private fun publish(packId: String, state: InstallState): InstallState {
        states.value = states.value + (packId to state)
        return state
    }

    private fun recordInstalled(packId: String, version: Int) =
        writeManifest(readManifest() + (packId to version))

    private fun initialStates(): Map<String, InstallState> =
        readManifest().mapValues { (_, v) -> InstallState.Installed(v) }

    private fun readManifest(): Map<String, Int> =
        if (manifestFile.exists()) {
            runCatching { json.decodeFromString<Map<String, Int>>(manifestFile.readText()) }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }

    private fun writeManifest(map: Map<String, Int>) {
        installRoot.mkdirs()
        manifestFile.writeText(json.encodeToString(map))
    }

    private companion object {
        const val MANIFEST_NAME = "packs-installed.json"
        const val PART_SUFFIX = ".part"
        const val BUFFER_BYTES = 1 shl 16

        fun percent(have: Long, total: Long): Int =
            if (total <= 0L) 0 else ((have * 100) / total).toInt().coerceIn(0, 100)

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        /** Placeholder catalog hashes are all-zero; treat those as "unset". */
        fun String.isChecksum(): Boolean = length == 64 && any { it != '0' }

        fun java.io.InputStream.digestInto(digest: MessageDigest) {
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
    }
}
