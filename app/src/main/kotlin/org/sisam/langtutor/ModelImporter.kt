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
import org.sisam.langtutor.packs.PackDescriptor
import org.sisam.langtutor.packs.ResourceCatalogLoader

sealed interface ImportState {
    data object Idle : ImportState

    /**
     * [percent] is -1 while the source size is unknown (indeterminate).
     * [label] names the current file during a folder import ("2/4 model.onnx").
     */
    data class Copying(val percent: Int, val label: String = "") : ImportState
    data class Verifying(val label: String = "") : ImportState
    data class Done(val fileName: String) : ImportState

    /**
     * [canForce]: debug builds may re-import skipping verification.
     * [unreachable]: the remembered folder could not be opened at all — the
     * card or drive is not connected, or its grant was revoked.
     */
    data class Failed(
        val reason: String,
        val canForce: Boolean = false,
        val unreachable: Boolean = false,
    ) : ImportState

    /**
     * How a folder scan ends: every pack THIS device expects and where each
     * one stands, so "what is still missing" is answered in the same place
     * the files are imported. [folder] is the picked directory's own name.
     */
    data class Report(val folder: String, val items: List<PackStatus>) : ImportState {
        val ready: Int
            get() = items.count { it.status == PackStatus.Status.INSTALLED || it.status == PackStatus.Status.IMPORTED }
        val missing: List<PackStatus>
            get() = items.filter { it.status == PackStatus.Status.MISSING }
    }
}

/** One line of an [ImportState.Report]. [detail] carries a reason for FAILED. */
data class PackStatus(val pack: PackDescriptor, val status: Status, val detail: String = "") {
    enum class Status { INSTALLED, IMPORTED, MISSING, FAILED }

    /** The exact file the folder must hold for this pack. */
    val fileName: String get() = pack.resolvedInstallPath.substringAfterLast('/')
}

/**
 * Imports pack files from the phone's own storage into the app's internal
 * models dir — the no-adb, no-network sideload path. The primary shape is
 * ONE folder ([importTree]): a USB-C drive, a microSD card, Downloads —
 * picked through the Storage Access Framework, so no storage permission is
 * ever requested — holding every large file this device needs, in the
 * layout `scripts/download-sideload.sh` writes. A single shared file
 * ([import], from the share sheet) still works. Every copy is SHA-256-
 * verified against the pinned revisions, so integrity matches the in-app
 * downloader.
 *
 * [expected] answers "which packs should this device end up with" — the
 * RAM-gated catalog, minus what this build cannot use — and is what the
 * folder scan imports and reports against.
 */
class ModelImporter(
    context: Context,
    private val scope: CoroutineScope,
    private val expected: () -> List<PackDescriptor>,
) {

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state

    /** Set when verification rejected a file, so a debug build can force it. */
    private var lastRejectedUri: Uri? = null

    fun import(uri: Uri) {
        if (busy()) return
        scope.launch(Dispatchers.IO) { runImport(uri, verify = true) }
    }

    /**
     * Folder import: point at ONE directory (a USB drive or microSD card with
     * the whole sideload payload) and every pack this device EXPECTS that is
     * in it — top level or one subdirectory down, matching the sideload
     * layout with its speech/ folder — is imported with the same verification
     * as a single import. Files already installed at the right size are
     * skipped, so re-running after adding one file copies only that file.
     * Smallest first: quick wins land before a multi-GB copy starts. Known
     * files the device does not expect (a brain on a build without one) are
     * left where they are. It ends in an [ImportState.Report] either way.
     */
    fun importTree(treeUri: Uri) {
        if (busy()) return
        scope.launch(Dispatchers.IO) { runTreeImport(treeUri) }
    }

    private fun busy(): Boolean {
        val current = _state.value
        return current is ImportState.Copying || current is ImportState.Verifying
    }

    private fun runTreeImport(treeUri: Uri) {
        val folder = folderName(treeUri)
        try {
            val wanted = expected()
            val inFolder = listTreeFiles(treeUri).associateBy { it.name }
            val status = HashMap<String, PackStatus>(wanted.size)
            for (pack in wanted.sortedBy { it.sizeBytes }) {
                val line = PackStatus(pack, PackStatus.Status.MISSING)
                val target = File(appContext.filesDir, pack.resolvedInstallPath)
                if (target.exists() && target.length() == pack.sizeBytes) {
                    status[pack.id] = line.copy(status = PackStatus.Status.INSTALLED)
                    continue
                }
                val entry = inFolder[line.fileName]
                if (entry == null) {
                    status[pack.id] = line
                    continue
                }
                runImport(entry.uri, verify = true, label = line.fileName)
                status[pack.id] = when (val result = _state.value) {
                    is ImportState.Done -> line.copy(status = PackStatus.Status.IMPORTED)
                    is ImportState.Failed -> line.copy(status = PackStatus.Status.FAILED, detail = result.reason)
                    else -> line.copy(status = PackStatus.Status.FAILED, detail = "interrupted")
                }
            }
            // Catalog order for the report, whatever order the copies ran in.
            _state.value = ImportState.Report(folder, wanted.mapNotNull { status[it.id] })
        } catch (e: SecurityException) {
            _state.value = ImportState.Failed(e.message ?: "folder not reachable", unreachable = true)
        } catch (t: Throwable) {
            _state.value = ImportState.Failed("${t.javaClass.simpleName}: ${t.message ?: "folder import failed"}")
        }
    }

    /** The picked directory's own name ("Tuki" from "primary:Tuki"); "/" for a card root. */
    private fun folderName(treeUri: Uri): String = runCatching {
        android.provider.DocumentsContract.getTreeDocumentId(treeUri).substringAfterLast(':').ifEmpty { "/" }
    }.getOrDefault(treeUri.lastPathSegment ?: "?")

    private data class TreeEntry(val name: String, val uri: Uri)

    /** Files of the picked tree: top level plus ONE subdirectory level. */
    private fun listTreeFiles(treeUri: Uri): List<TreeEntry> {
        val root = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        val out = mutableListOf<TreeEntry>()
        val subdirs = mutableListOf<String>()
        listChildren(treeUri, root, out, subdirs)
        for (dir in subdirs) listChildren(treeUri, dir, out, mutableListOf())
        return out
    }

    private fun listChildren(
        treeUri: Uri,
        parentDocId: String,
        files: MutableList<TreeEntry>,
        dirs: MutableList<String>,
    ) {
        val childrenUri = android.provider.DocumentsContract
            .buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        appContext.contentResolver.query(
            childrenUri,
            arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                    dirs.add(docId)
                } else {
                    files.add(
                        TreeEntry(
                            name,
                            android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                        ),
                    )
                }
            }
        }
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
        if (busy()) return
        scope.launch(Dispatchers.IO) { runImport(uri, verify = false) }
    }

    private fun runImport(uri: Uri, verify: Boolean, label: String = "") {
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
                            _state.value = ImportState.Copying(pct, label)
                        }
                    }
                }
            }
            _state.value = ImportState.Verifying(label)
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
            "acft_whisper_small.en_10s.tflite" to listOf(
                KnownRevision(286_276_128, "58edc288e8aad1da2a3df0545edadf5f1c6119ff70682e37031119ad89130daf"),
            ),
            "whisper_medium_30s_i4.tflite" to listOf(
                KnownRevision(664_348_672, "4d5a521109aa64383bcb99d1f1951316bce024a916f89683c95579db4f5ffa63"),
            ),
            "model_quantized.onnx" to listOf(
                KnownRevision(92_361_116, "fbae9257e1e05ffc727e951ef9b9c98418e6d79f1c9b6b13bd59f5c9028a1478"),
            ),
            "wav2vec2-phoneme-int8.onnx" to listOf(
                KnownRevision(317_712_780, "74174710e34035bbb7f611601d016c32fc575de7a6f53b1078107dc10a84e7ae"),
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
