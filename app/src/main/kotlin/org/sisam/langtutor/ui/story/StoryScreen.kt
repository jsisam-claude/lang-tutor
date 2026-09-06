package org.sisam.langtutor.ui.story

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
import org.sisam.langtutor.content.Story
import org.sisam.langtutor.profile.LearnerProfile
import org.sisam.langtutor.speech.HebrewTransliteration.GlossWord
import org.sisam.langtutor.tutor.story.StoryReader
import org.sisam.langtutor.tutor.story.StoryState
import org.sisam.langtutor.ui.common.A11y
import org.sisam.langtutor.ui.common.EngineStatusLine
import org.sisam.langtutor.ui.common.GlossedText
import org.sisam.langtutor.ui.common.TukiParrot
import org.sisam.langtutor.ui.common.rememberGloss
import org.sisam.langtutor.ui.common.rememberTranslation

class StoryViewModel(container: AppContainer) : ViewModel() {
    private val reader: StoryReader = container.createStoryReader(viewModelScope)
    val state = reader.state

    fun open(story: Story) = reader.open(story)
    fun next() = reader.next()
    fun back() = reader.back()
    fun readAgain() = reader.readAgain()
    fun again() = reader.again()

    /** Silence without losing the page — a rotation or a detour comes back
     *  to where the reader was. */
    fun stop() = reader.silence()

    override fun onCleared() = reader.shutdown()
}

/**
 * The reading room (docs/short-stories.md).
 *
 * The one room that asks the learner for nothing: no microphone, no answer,
 * no score. Everything the other rooms drill has to be met somewhere in
 * running prose before it settles, and this is that somewhere. Every word in
 * every story is one the phrasebank has already taught at or below the
 * story's Level, which is checked by `StoriesTest` rather than promised here.
 */
@Composable
fun StoryScreen(container: AppContainer) {
    val profile by container.profile.profile.collectAsState(initial = LearnerProfile.EMPTY)
    val hebrew = LocalConfiguration.current.locales[0].language in setOf("he", "iw")
    val stories by produceState(initialValue = emptyList<Story>(), container) {
        value = runCatching { container.stories.stories() }.getOrDefault(emptyList())
    }
    val viewModel: StoryViewModel = viewModel(
        factory = viewModelFactory { initializer { StoryViewModel(container) } },
    )
    val state by viewModel.state.collectAsState()

    // A story at or below the learner's Level is one they can read; above it
    // is one that would meet them with words nobody has taught them.
    val readable = stories.filter { it.level <= profile.effectiveLevel }

    when (val s = state) {
        StoryState.Idle -> StoryPicker(readable, hebrew, onPick = viewModel::open)
        is StoryState.Reading -> ReadingPane(container, s, hebrew, viewModel)
        is StoryState.Done -> DonePane(s.story, hebrew, viewModel)
    }
}

@Composable
private fun StoryPicker(stories: List<Story>, hebrew: Boolean, onPick: (Story) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = A11y.gutter, vertical = A11y.sectionGap),
        verticalArrangement = Arrangement.spacedBy(A11y.sectionGap),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TukiParrot(speaking = false, size = A11y.decorativeDp(comfortable = 64, minimum = 40))
            Text(
                text = stringResource(R.string.story_pick),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
        }
        EngineStatusLine()
        if (stories.isEmpty()) {
            // Nothing at this Level yet: honest, and the Parent Zone is where
            // a Level is changed.
            Text(
                text = stringResource(R.string.story_none),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        stories.forEachIndexed { index, story ->
            val accent = ACCENTS[index % ACCENTS.size]
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onPick(story) },
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(A11y.decorativeDp(comfortable = 52, minimum = 40))
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "📖", style = MaterialTheme.typography.titleMedium)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (hebrew) story.title.he else story.title.en,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(R.string.story_pages, story.pages.size),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingPane(
    container: AppContainer,
    state: StoryState.Reading,
    hebrew: Boolean,
    viewModel: StoryViewModel,
) {
    val page = state.story.pages[state.page]
    val gloss by rememberGloss(container, page.en)
    val meaning by rememberTranslation(container, page.he)
    val repeat = stringResource(R.string.story_read_again)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = A11y.gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(A11y.sectionGap),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TukiParrot(speaking = state.speaking, size = A11y.decorativeDp(comfortable = 56, minimum = 36))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (hebrew) state.story.title.he else state.story.title.en,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.vocab_progress, state.page + 1, state.total),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .clickable(onClickLabel = repeat) { viewModel.readAgain() },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            // The same three-line treatment every room uses: the line, its
            // pronunciation where the Level wants one, the meaning below.
            GlossedText(
                words = gloss.ifEmpty { listOf(GlossWord(page.en, "")) },
                style = if (A11y.hugeText) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.headlineSmall
                },
                glossStyle = MaterialTheme.typography.titleMedium,
                translation = meaning,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = viewModel::back,
                enabled = !state.first,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.story_back))
            }
            Button(onClick = viewModel::next, modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(if (state.last) R.string.story_finish else R.string.picture_next),
                )
            }
        }
    }
}

@Composable
private fun DonePane(story: Story, hebrew: Boolean, viewModel: StoryViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(A11y.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(A11y.sectionGap, Alignment.CenterVertically),
    ) {
        Text(
            text = if (hebrew) story.title.he else story.title.en,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.story_done),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = viewModel::again) {
            Text(stringResource(R.string.story_read_it_again))
        }
    }
}

/** One accent per card, cycled — the same handle on a list the twister
 *  picker gives, for a reader who cannot yet read the titles. */
private val ACCENTS = listOf(
    Color(0xFF19B8A6), Color(0xFF3E8ED0), Color(0xFF7C6BEA),
    Color(0xFFE0679A), Color(0xFFE29B3F),
)
