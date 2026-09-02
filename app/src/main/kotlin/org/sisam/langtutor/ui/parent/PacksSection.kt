package org.sisam.langtutor.ui.parent

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.BuildConfig
import org.sisam.langtutor.ImportState
import org.sisam.langtutor.PackStatus
import org.sisam.langtutor.profile.LearnerProfile
import org.sisam.langtutor.R
import org.sisam.langtutor.packs.InstallState
import org.sisam.langtutor.packs.PackDescriptor
import androidx.compose.runtime.rememberCoroutineScope


/**
 * Parent-zone pack manager. Policy surface for the scope decision "downloads
 * with user approval, manual updates only": every install starts from the
 * consent dialog below; the only update path is the explicit button.
 */
@Composable
fun PacksSection(container: AppContainer) {
    val repo = container.packs
    val states by repo.installStates.collectAsState(initial = emptyMap())
    val scope = rememberCoroutineScope()
    var consentPack by remember { mutableStateOf<PackDescriptor?>(null) }
    var sslOverridePack by remember { mutableStateOf<PackDescriptor?>(null) }
    var updatesCount by remember { mutableStateOf<Int?>(null) }
    val isHebrew = LocalConfiguration.current.locales[0].language in setOf("he", "iw")

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.packs_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.packs_policy_note),
            style = MaterialTheme.typography.bodySmall,
        )

        // ONE folder for every large file (docs/practice-flavor.md): pick it
        // once — a USB drive, a microSD card, Downloads; the system tree
        // picker needs no storage permission — and every later tap re-scans
        // it. Each scan ends in a report of what this device expects and what
        // is still missing. Sharing a single file TO the app still works too
        // (MainActivity's share-to-import).
        if (container.expectedPacks().isEmpty()) {
            // Everything this device could want rides inside the APK (the
            // practice flavor ships its speech models — docs/practice-flavor.md):
            // nothing to pick, download, update or report.
            Text(
                text = stringResource(R.string.packs_all_bundled),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            PackFolderCard(container, isHebrew)

            container.expectedPacks().forEach { pack ->
                val state = states[pack.id] ?: InstallState.NotInstalled
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "${packName(pack, isHebrew)} · ${formatSize(pack.sizeBytes)}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        when (state) {
                            is InstallState.Downloading -> LinearProgressIndicator(
                                progress = { state.progressPercent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )

                            InstallState.Verifying ->
                                Text(stringResource(R.string.packs_verifying))

                            is InstallState.Installed -> Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.packs_installed))
                                TextButton(onClick = { scope.launch { repo.delete(pack.id) } }) {
                                    Text(stringResource(R.string.packs_delete))
                                }
                            }

                            is InstallState.Failed -> Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                // Show the real reason so a failed download is diagnosable,
                                // and offer a retry (resumes from the .part file).
                                Text(
                                    text = "${stringResource(R.string.packs_failed)}: ${state.reason}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Button(onClick = { container.appScope.launch { repo.install(pack.id).collect { } } }) {
                                    Text(stringResource(R.string.packs_retry))
                                }
                                // Debug builds only: offer a TLS-bypass retry when the
                                // failure looks like a certificate/trust problem.
                                val looksLikeSsl = listOf("SSL", "trust", "certif", "CertPath")
                                    .any { state.reason.contains(it, ignoreCase = true) }
                                if (BuildConfig.DEBUG && looksLikeSsl) {
                                    TextButton(onClick = { sslOverridePack = pack }) {
                                        Text(stringResource(R.string.packs_ignore_ssl))
                                    }
                                }
                            }

                            InstallState.NotInstalled -> Button(onClick = { consentPack = pack }) {
                                Text(stringResource(R.string.packs_download))
                            }
                        }
                    }
                }
            }

            // One tap for a fresh device: sequentially download every eligible pack
            // that isn't installed yet (same consent framing — this IS the consent).
            val pending = container.expectedPacks()
                .filter { (states[it.id] ?: InstallState.NotInstalled) !is InstallState.Installed }
            if (pending.size > 1) {
                OutlinedButton(onClick = {
                    container.appScope.launch {
                        for (p in pending) {
                            runCatching { repo.install(p.id).collect { } }
                        }
                    }
                }) {
                    Text(stringResource(R.string.packs_download_all, pending.size))
                }
            }

            OutlinedButton(
                onClick = { scope.launch { updatesCount = repo.checkForUpdatesManually().size } },
            ) {
                Text(stringResource(R.string.packs_check_updates))
            }
            updatesCount?.let { count ->
                Text(
                    text = if (count == 0) {
                        stringResource(R.string.packs_updates_none)
                    } else {
                        stringResource(R.string.packs_updates_found, count)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    consentPack?.let { pack ->
        AlertDialog(
            onDismissRequest = { consentPack = null },
            title = { Text(stringResource(R.string.packs_consent_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.packs_consent_body,
                        packName(pack, isHebrew),
                        formatSize(pack.sizeBytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        consentPack = null
                        container.appScope.launch { repo.install(pack.id).collect { } }
                    },
                ) {
                    Text(stringResource(R.string.packs_consent_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { consentPack = null }) {
                    Text(stringResource(R.string.packs_consent_cancel))
                }
            },
        )
    }

    // Testing-only (debug builds): confirm before disabling TLS checks, then retry.
    sslOverridePack?.let { pack ->
        AlertDialog(
            onDismissRequest = { sslOverridePack = null },
            title = { Text(stringResource(R.string.packs_ignore_ssl_title)) },
            text = { Text(stringResource(R.string.packs_ignore_ssl_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        sslOverridePack = null
                        container.enableInsecureDownloads()
                        container.appScope.launch { repo.install(pack.id).collect { } }
                    },
                ) {
                    Text(stringResource(R.string.packs_ignore_ssl_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { sslOverridePack = null }) {
                    Text(stringResource(R.string.packs_consent_cancel))
                }
            },
        )
    }
}

@Composable
private fun PackFolderCard(container: AppContainer, isHebrew: Boolean) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val profile by container.profile.profile.collectAsState(initial = LearnerProfile.EMPTY)
    val remembered = profile.parentSettings.packFolder?.let { runCatching { Uri.parse(it) }.getOrNull() }
    val importState by container.modelImporter.state.collectAsState()
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // Keep the grant across restarts, so the next tap needs no picker.
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        scope.launch {
            container.profile.update {
                it.copy(parentSettings = it.parentSettings.copy(packFolder = uri.toString()))
            }
        }
        container.modelImporter.importTree(uri)
    }
    val busy = importState is ImportState.Copying || importState is ImportState.Verifying

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.packs_folder_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.packs_folder_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = remembered?.let { stringResource(R.string.packs_folder_current, folderLabel(it)) }
                    ?: stringResource(R.string.packs_folder_none),
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The one button: re-scan the remembered folder, or pick it.
                Button(
                    enabled = !busy,
                    onClick = {
                        if (remembered != null) container.modelImporter.importTree(remembered) else folderPicker.launch(null)
                    },
                ) {
                    Text(stringResource(R.string.packs_folder_import))
                }
                if (remembered != null) {
                    TextButton(enabled = !busy, onClick = { folderPicker.launch(null) }) {
                        Text(stringResource(R.string.packs_folder_change))
                    }
                }
            }
            ImportProgress(container, importState, isHebrew)
        }
    }
}

@Composable
private fun ImportProgress(container: AppContainer, state: ImportState, isHebrew: Boolean) {
    when (state) {
        is ImportState.Copying -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.label.isNotEmpty()) {
                Text(state.label, style = MaterialTheme.typography.bodySmall)
            }
            if (state.percent >= 0) {
                LinearProgressIndicator(
                    progress = { state.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        is ImportState.Verifying -> Text(
            listOf(state.label, stringResource(R.string.packs_verifying))
                .filter { it.isNotEmpty() }.joinToString(" — "),
        )

        is ImportState.Done ->
            Text(stringResource(R.string.packs_import_done, state.fileName))

        is ImportState.Report -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.packs_report_summary, state.ready, state.items.size),
                style = MaterialTheme.typography.titleSmall,
            )
            for (item in state.items) {
                val name = packName(item.pack, isHebrew)
                val line = when (item.status) {
                    PackStatus.Status.INSTALLED ->
                        "✓ $name — ${stringResource(R.string.packs_status_installed)}"
                    PackStatus.Status.IMPORTED ->
                        "✓ $name — ${stringResource(R.string.packs_status_imported)}"
                    PackStatus.Status.MISSING ->
                        "✗ $name — ${stringResource(R.string.packs_status_missing, item.fileName, formatSize(item.pack.sizeBytes))}"
                    PackStatus.Status.FAILED ->
                        "⚠ $name — ${stringResource(R.string.packs_status_failed, item.detail)}"
                }
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
            if (state.missing.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.packs_report_missing_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        is ImportState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (state.unreachable) {
                    stringResource(R.string.packs_folder_unreachable)
                } else {
                    "${stringResource(R.string.packs_failed)}: ${state.reason}"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            // Debug builds only: accept a file that failed verification (e.g.
            // an HF revision newer than our pin list). Checks are skipped —
            // testing only, never present in release.
            if (BuildConfig.DEBUG && state.canForce) {
                TextButton(onClick = { container.modelImporter.importUnverified() }) {
                    Text(stringResource(R.string.packs_import_anyway))
                }
            }
        }

        ImportState.Idle -> Unit
    }
}

/** "Tuki" from "primary:Tuki"; "/" for the root of a card or drive. */
private fun folderLabel(uri: Uri): String = runCatching {
    DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').ifEmpty { "/" }
}.getOrDefault(uri.toString())

private fun packName(pack: PackDescriptor, hebrew: Boolean): String =
    if (hebrew) pack.nameHe else pack.nameEn

private fun formatSize(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    return if (gb >= 1) "%.1f GB".format(gb) else "${bytes / 1_000_000} MB"
}
