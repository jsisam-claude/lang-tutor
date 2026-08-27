package org.sisam.langtutor.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
import org.sisam.langtutor.profile.LearnerProfile

/**
 * Switches that exist to answer a question, not to improve anything.
 *
 * Separate from the rest of the Parent Zone and visually marked, because the
 * honest label for what is in here is "this may make the app worse". Anything
 * that graduates out of it stops being a switch and becomes the default.
 */
@Composable
fun ExperimentalSection(container: AppContainer) {
    val profile by container.profile.profile.collectAsState(initial = LearnerProfile.EMPTY)
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.parent_experimental_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.parent_experimental_hint),
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.parent_npu_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.parent_npu_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = profile.parentSettings.tryNpuBackend,
                    onCheckedChange = { on ->
                        scope.launch {
                            container.profile.update {
                                it.copy(
                                    parentSettings = it.parentSettings.copy(
                                        tryNpuBackend = on,
                                    ),
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}
