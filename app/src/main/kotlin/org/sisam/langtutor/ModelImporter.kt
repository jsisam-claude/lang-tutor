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
    data class Failed(val reason: String) : ImportState
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

    fun import(uri: Uri) {
        val current = _state.value
        if (current is ImportState.Copying || current == ImportState.Verifying) return
        scope.launch(Dispatchers.IO) { runImport(uri) }
    }

    private fun runImport(uri: Uri) {
        try {
            val (name, size) = queryNameAndSize(uri)
            // Only files the engine actually loads are accepted; the catalog is
            // the single source of truth for names, sizes, and hashes.
            val pack = ResourceCatalogLoader.load().packs.firstOrNull {
                it.resolvedInstallPath.substringAfterLast('/') == name
            }
            if (pack == null) {
                _state.value = ImportState.Failed(
                    "Unsupported file '$name' — expected gemma-4-E4B-it.litertlm or gemma-4-E2B-it.litertlm",
                )
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
            if (!pack.sha256.equals(got, ignoreCase = true)) {
                tmp.delete()
                _state.value = ImportState.Failed("Checksum mismatch — the file looks corrupted; re-download it")
                return
            }
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
