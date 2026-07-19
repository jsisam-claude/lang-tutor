package org.sisam.langtutor.ui.lesson

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
import org.sisam.langtutor.content.Activity
import org.sisam.langtutor.content.CurriculumUnit
import org.sisam.langtutor.ui.common.EnglishContent

@Composable
fun LessonScreen(container: AppContainer) {
    val unit by produceState<CurriculumUnit?>(initialValue = null, container) {
        value = container.content.loadUnit("unit-001")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = unit?.title?.he ?: stringResource(R.string.lesson_loading),
            style = MaterialTheme.typography.headlineSmall,
        )

        unit?.activities?.filterIsInstance<Activity.Vocab>()?.forEach { vocab ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    EnglishContent {
                        Text(text = vocab.word, style = MaterialTheme.typography.displaySmall)
                    }
                    Text(text = vocab.translation.he, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
