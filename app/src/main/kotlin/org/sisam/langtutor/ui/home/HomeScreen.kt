package org.sisam.langtutor.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
import org.sisam.langtutor.content.UnitSummary

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenLesson: () -> Unit,
    onOpenConversation: () -> Unit,
    onOpenParent: () -> Unit,
) {
    val units by produceState<List<UnitSummary>>(initialValue = emptyList(), container) {
        value = container.content.listUnits()
    }
    val profile by container.profile.profile.collectAsState(
        initial = org.sisam.langtutor.profile.LearnerProfile.EMPTY,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.home_greeting),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.home_xp, profile.xp),
            style = MaterialTheme.typography.bodyLarge,
        )

        units.forEach { unit ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = unit.title.he, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "${unit.title.en} · ${unit.cefrLevel}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onOpenLesson) {
                            Text(stringResource(R.string.home_start_lesson))
                        }
                        OutlinedButton(onClick = onOpenConversation) {
                            Text(stringResource(R.string.home_start_conversation))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenParent) {
            Text(stringResource(R.string.home_parent_zone))
        }
    }
}
