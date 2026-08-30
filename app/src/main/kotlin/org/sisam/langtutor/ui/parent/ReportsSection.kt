package org.sisam.langtutor.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R

/**
 * Review of flagged AI replies — the adult half of the report mechanism
 * (long-press in the rooms is the child-reachable half). Everything stays on
 * this device; the honest report flow for an app with no backend is "show
 * the adult in charge", and that is exactly what this is.
 */
@Composable
fun ReportsSection(container: AppContainer) {
    val reports by container.reports.reports.collectAsState()
    val scope = rememberCoroutineScope()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.parent_reports_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.parent_reports_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            if (reports.isEmpty()) {
                Text(
                    text = stringResource(R.string.parent_reports_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                // Newest first — the flag someone just placed is the one
                // they came here to look at.
                reports.asReversed().forEach { report ->
                    HorizontalDivider()
                    Text(
                        text = "${report.room} · " +
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(report.atMillis)),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(text = report.text, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(
                    onClick = { scope.launch { container.reports.clear() } },
                ) {
                    Text(stringResource(R.string.parent_reports_clear))
                }
            }
        }
    }
}
