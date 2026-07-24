package org.sisam.langtutor.ui.parent

import androidx.compose.foundation.layout.Arrangement
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
import kotlinx.coroutines.launch
import org.sisam.langtutor.AppContainer
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
                            Button(onClick = { scope.launch { repo.install(pack.id).collect { } } }) {
                                Text(stringResource(R.string.packs_retry))
                            }
                        }

                        InstallState.NotInstalled -> Button(onClick = { consentPack = pack }) {
                            Text(stringResource(R.string.packs_download))
                        }
                    }
                }
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
                        scope.launch { repo.install(pack.id).collect { } }
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
}

private fun packName(pack: PackDescriptor, hebrew: Boolean): String =
    if (hebrew) pack.nameHe else pack.nameEn

private fun formatSize(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    return if (gb >= 1) "%.1f GB".format(gb) else "${bytes / 1_000_000} MB"
}
