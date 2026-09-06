package org.sisam.langtutor.tutor.story

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sisam.langtutor.content.LocalizedText
import org.sisam.langtutor.content.Story
import org.sisam.langtutor.content.StoryPage
import org.sisam.langtutor.speech.FakeTtsEngine

@OptIn(ExperimentalCoroutinesApi::class)
class StoryReaderTest {

    private val story = Story(
        id = "the-lost-sock",
        level = 2,
        title = LocalizedText("The Lost Sock", "הגרב שאבד"),
        pages = listOf(
            StoryPage("One sock is here.", "גרב אחת כאן."),
            StoryPage("The other sock is not here.", "הגרב השנייה לא כאן."),
            StoryPage("It is under the bed.", "היא מתחת למיטה."),
            StoryPage("Now we can go.", "עכשיו אפשר ללכת."),
        ),
    )

    @Test
    fun `opening a story reads its first page`() = runTest {
        val tts = FakeTtsEngine()
        val reader = StoryReader(tts, this)
        reader.open(story)
        advanceUntilIdle()
        assertEquals(listOf("One sock is here."), tts.spoken.map { it.text })
        val s = reader.state.value as StoryState.Reading
        assertEquals(0, s.page)
        assertEquals(4, s.total)
        assertTrue(s.first)
        assertFalse(s.last)
        assertFalse("the page has been read", s.speaking)
    }

    @Test
    fun `turning a page reads the new one and never the old one again`() = runTest {
        val tts = FakeTtsEngine()
        val reader = StoryReader(tts, this)
        reader.open(story)
        advanceUntilIdle()
        reader.next()
        advanceUntilIdle()
        reader.next()
        advanceUntilIdle()
        assertEquals(
            listOf("One sock is here.", "The other sock is not here.", "It is under the bed."),
            tts.spoken.map { it.text },
        )
        reader.back()
        advanceUntilIdle()
        assertEquals("The other sock is not here.", tts.spoken.last().text)
        assertEquals(1, (reader.state.value as StoryState.Reading).page)
    }

    @Test
    fun `turning a page mid-line cuts it rather than queueing behind it`() = runTest {
        val tts = FakeTtsEngine()
        val reader = StoryReader(tts, this)
        reader.open(story)
        advanceTimeBy(10) // the first page is still being read
        assertTrue((reader.state.value as StoryState.Reading).speaking)
        reader.next()
        advanceUntilIdle()
        // The learner is on page two and the room is talking about page two.
        val s = reader.state.value as StoryState.Reading
        assertEquals(1, s.page)
        assertFalse(s.speaking)
        assertEquals("The other sock is not here.", tts.spoken.last().text)
    }

    @Test
    fun `tapping the page reads it again, as often as asked`() = runTest {
        val tts = FakeTtsEngine()
        val reader = StoryReader(tts, this)
        reader.open(story)
        advanceUntilIdle()
        reader.readAgain()
        advanceUntilIdle()
        reader.readAgain()
        advanceUntilIdle()
        assertEquals(3, tts.spoken.count { it.text == "One sock is here." })
    }

    @Test
    fun `the last page ends the story, and it can be read again`() = runTest {
        val tts = FakeTtsEngine()
        val reader = StoryReader(tts, this)
        reader.open(story)
        advanceUntilIdle()
        repeat(3) { reader.next(); advanceUntilIdle() }
        assertTrue((reader.state.value as StoryState.Reading).last)
        reader.next()
        advanceUntilIdle()
        assertEquals(StoryState.Done(story), reader.state.value)
        reader.again()
        advanceUntilIdle()
        assertEquals(0, (reader.state.value as StoryState.Reading).page)
    }

    @Test
    fun `back on the first page does nothing`() = runTest {
        val tts = FakeTtsEngine()
        val reader = StoryReader(tts, this)
        reader.open(story)
        advanceUntilIdle()
        reader.back()
        advanceUntilIdle()
        assertEquals(0, (reader.state.value as StoryState.Reading).page)
        assertEquals(1, tts.spoken.size)
    }

    @Test
    fun `silence keeps the page and shutdown does not`() = runTest {
        val tts = FakeTtsEngine()
        val reader = StoryReader(tts, this)
        reader.open(story)
        advanceUntilIdle()
        reader.next()
        advanceUntilIdle()
        reader.silence()
        advanceUntilIdle()
        // A rotation or a detour comes back to the page the reader was on.
        assertEquals(1, (reader.state.value as StoryState.Reading).page)
        reader.shutdown()
        assertEquals(StoryState.Idle, reader.state.value)
    }

    @Test
    fun `a story with no pages is not opened`() = runTest {
        val tts = FakeTtsEngine()
        val reader = StoryReader(tts, this)
        reader.open(story.copy(pages = emptyList()))
        advanceUntilIdle()
        assertEquals(StoryState.Idle, reader.state.value)
        assertTrue(tts.spoken.isEmpty())
    }
}
