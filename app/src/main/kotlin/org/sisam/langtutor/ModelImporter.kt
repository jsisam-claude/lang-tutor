package org.sisam.langtutor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.sisam.langtutor.packs.ResourceCatalogLoader

sealed interface ImportState {
    data object Idle : ImportState

    /** [percent] is -1 while the source size is unknown (indeterminate). */
    data class Copying(val percent: Int) : ImportState
    data object Verifying : ImportState
    data class Done(val fileName: String) : ImportState

    /** [canForce]: debug builds may re-import skipping verification. */
    data class Failed(val reason: String, val canForce: Boolean = false) : ImportState
}

/**
 * Imports a model file picked with the system file picker (Storage Access
 * Framework) into the app's internal models dir — the no-adb, no-network
 * sideload path: put the `.litertlm` anywhere on the phone (USB-C drive, USB
 * file transfer into Downloads, cloud app) and use Parent Zone → "Import model
 * file". SAF needs no storage permission, and the copy is SHA-256-verified
 * against the pack catalog's pin for that filename, so integrity matches the
 * in-app downloader.
 */
class ModelImporter(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state

    /** Set when verification rejected a file, so a debug build can force it. */
    private var lastRejectedUri: Uri? = null

    fun import(uri: Uri) {
        val current = _state.value
        if (current is ImportState.Copying || current == ImportState.Verifying) return
        scope.launch(Dispatchers.IO) { runImport(uri, verify = true) }
    }

    /**
     * TESTING ONLY (debug builds): re-import the last rejected file WITHOUT
     * size/hash verification. For genuinely different artifacts (e.g. a future
     * HF revision not yet in [KNOWN_REVISIONS]). Filename validation still
     * applies; release builds ignore this entirely.
     */
    fun importUnverified() {
        if (!BuildConfig.DEBUG) return
        val uri = lastRejectedUri ?: return
        val current = _state.value
        if (current is ImportState.Copying || current == ImportState.Verifying) return
        scope.launch(Dispatchers.IO) { runImport(uri, verify = false) }
    }

    private fun runImport(uri: Uri, verify: Boolean) {
        try {
            val (name, size) = queryNameAndSize(uri)
            // Only files the engine actually loads are accepted; the catalog is
            // the single source of truth for names, sizes, and hashes.
            val packs = ResourceCatalogLoader.load().packs
            val pack = packs.firstOrNull {
                it.resolvedInstallPath.substringAfterLast('/') == name
            }
            if (pack == null) {
                val known = packs.joinToString(", ") { it.resolvedInstallPath.substringAfterLast('/') }
                _state.value = ImportState.Failed("Unsupported file '$name' — expected one of: $known")
                return
            }
            val target = File(appContext.filesDir, pack.resolvedInstallPath)
            target.parentFile?.mkdirs()
            val expected = if (size > 0) size else pack.sizeBytes
            if (appContext.filesDir.usableSpace in 1 until (expected * 1.05).toLong()) {
                _state.value = ImportState.Failed("Not enough storage: need ~${expected / 1_000_000} MB free")
                return
            }
            // Distinct suffix from the downloader's ".part" so the two paths
            // can never collide on the same temp file.
            val tmp = File(target.parentFile, target.name + ".import")
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            appContext.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open the selected file" }
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var lastPct = -2
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        copied += n
                        val pct = if (size > 0) ((copied * 100) / size).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            _state.value = ImportState.Copying(pct)
                        }
                    }
                }
            }
            _state.value = ImportState.Verifying
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (verify) {
                // Accept ANY genuine published revision of this file — HF replaced
                // both models on 2026-05-04, so files downloaded earlier are valid
                // older revisions, not corruption. Verified against the revision
                // list pinned from HF's LFS metadata.
                val revisions = KNOWN_REVISIONS[name].orEmpty()
                val matched = revisions.any { it.size == copied && it.sha256.equals(got, ignoreCase = true) }
                if (!matched) {
                    tmp.delete()
                    lastRejectedUri = uri
                    val sizeKnown = revisions.any { it.size == copied }
                    _state.value = ImportState.Failed(
                        if (!sizeKnown) {
                            "Incomplete or unknown file: ${copied / 1_000_000} MB (known revisions: " +
                                revisions.joinToString("/") { "${it.size / 1_000_000}" } +
                                " MB) — re-copy or re-download"
                        } else {
                            "Checksum mismatch (got ${got.take(12)}…) — file damaged in transfer; re-copy it"
                        },
                        canForce = true,
                    )
                    return
                }
            }
            lastRejectedUri = null
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            _state.value = ImportState.Done(name)
        } catch (t: Throwable) {
            _state.value = ImportState.Failed("${t.javaClass.simpleName}: ${t.message ?: "import failed"}")
        }
    }

    private data class KnownRevision(val size: Long, val sha256: String)

    private companion object {
        /**
         * Every published revision of each model file, pinned from HF's LFS
         * metadata (litert-community). First entry = current (what the evals
         * ran on); second = the pre-2026-05-04 original, still genuine.
         */
        val KNOWN_REVISIONS: Map<String, List<KnownRevision>> = mapOf(
            "gemma-4-E2B-it.litertlm" to listOf(
                KnownRevision(2_588_147_712, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"),
                KnownRevision(2_583_085_056, "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42"),
            ),
            "whisper_large_v3_turbo_30s_i4.tflite" to listOf(
                KnownRevision(755_273_648, "da3c91fcd149174cbb5abd3a5583ea95982c5e401c2d68cabac89117f5ce1a4c"),
            ),
            "whisper_medium_30s_i4.tflite" to listOf(
                KnownRevision(664_348_672, "4d5a521109aa64383bcb99d1f1951316bce024a916f89683c95579db4f5ffa63"),
            ),
            "model_q8f16.onnx" to listOf(
                KnownRevision(86_033_585, "04c658aec1b6008857c2ad10f8c589d4180d0ec427e7e6118ceb487e215c3cd0"),
            ),
            "model.onnx" to listOf(
                KnownRevision(63_511_038, "dfe0a8f33002654fa560c4cdb796d934b6aa84b3bfb16779646a5b0f1bd9d968"),
            ),
            "phonikud-1.0.int8.onnx" to listOf(
                KnownRevision(307_844_158, "113afb58d3140502aa1e7691cdc6b240b56cf97e5852fc870e1a7fb5a400dd62"),
            ),
            "gemma-4-E4B-it.litertlm" to listOf(
                KnownRevision(3_659_530_240, "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"),
                KnownRevision(3_654_467_584, "f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc"),
            ),
        )
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        appContext.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else -1L
                if (name != null) return name to size
            }
        }
        return (uri.lastPathSegment ?: "unknown") to -1L
    }
}
