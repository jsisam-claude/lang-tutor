package org.sisam.langtutor.ui.parent

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.BuildConfig
import org.sisam.langtutor.ImportState
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

        repo.eligiblePacks(container.deviceRamGb).forEach { pack ->
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
        val pending = repo.eligiblePacks(container.deviceRamGb)
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

        // No-adb, no-network sideload: pick the .litertlm from anywhere on the
        // phone (USB-C drive, Downloads, a cloud app) — SAF needs no permission —
        // and it's copied into files/models with the same SHA-256 verification
        // as the downloader. Sharing a file TO the app does the same (MainActivity).
        val importState by container.modelImporter.state.collectAsState()
        val importPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri -> uri?.let { container.modelImporter.import(it) } }
        // Folder import: point at a directory holding ALL the model files (a
        // USB drive with the sideload payload) and everything known is imported
        // in one pass, already-installed files skipped.
        val folderPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri -> uri?.let { container.modelImporter.importTree(it) } }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { importPicker.launch(arrayOf("*/*")) }) {
                Text(stringResource(R.string.packs_import))
            }
            OutlinedButton(onClick = { folderPicker.launch(null) }) {
                Text(stringResource(R.string.packs_import_folder))
            }
        }
        when (val st = importState) {
            is ImportState.Copying -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (st.label.isNotEmpty()) {
                    Text(st.label, style = MaterialTheme.typography.bodySmall)
                }
                if (st.percent >= 0) {
                    LinearProgressIndicator(
                        progress = { st.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            is ImportState.Verifying -> Text(
                listOf(st.label, stringResource(R.string.packs_verifying))
                    .filter { it.isNotEmpty() }.joinToString(" — "),
            )
            is ImportState.Done ->
                Text(stringResource(R.string.packs_import_done, st.fileName))

            is ImportState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "${stringResource(R.string.packs_failed)}: ${st.reason}",
                    style = MaterialTheme.typography.bodySmall,
                )
                // Debug builds only: accept a file that failed verification (e.g.
                // an HF revision newer than our pin list). Checks are skipped —
                // testing only, never present in release.
                if (BuildConfig.DEBUG && st.canForce) {
                    TextButton(onClick = { container.modelImporter.importUnverified() }) {
                        Text(stringResource(R.string.packs_import_anyway))
                    }
                }
            }

            ImportState.Idle -> Unit
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

private fun packName(pack: PackDescriptor, hebrew: Boolean): String =
    if (hebrew) pack.nameHe else pack.nameEn

private fun formatSize(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    return if (gb >= 1) "%.1f GB".format(gb) else "${bytes / 1_000_000} MB"
}
