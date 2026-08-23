package org.sisam.langtutor.tutor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.sisam.langtutor.content.Activity
import org.sisam.langtutor.content.AgeBand
import org.sisam.langtutor.content.CurriculumUnit
import org.sisam.langtutor.content.LocalizedText

class TargetPickerTest {

    private fun unit(vararg activities: Activity) = CurriculumUnit(
        schemaVersion = 1,
        id = "unit-test",
        title = LocalizedText(en = "Test", he = "בדיקה"),
        cefrLevel = "pre-A1",
        ageBand = AgeBand.AGES_4_6,
        activities = activities.toList(),
    )

    private val colors = unit(
        Activity.RepeatAfterMe("I see a red ball."),
        Activity.RepeatAfterMe("The bear is blue."),
        Activity.Vocab("yellow", LocalizedText(en = "yellow", he = "צהוב")),
        Activity.QuestionAnswer(
            prompt = LocalizedText(en = "What color is the sun?", he = "?באיזה צבע השמש"),
            expectedAnswers = listOf("The sun is yellow."),
        ),
    )

    @Test
    fun `scores against the phrase the child actually attempted, not the first`() {
        // The old firstOrNull() behaviour would have picked "I see a red ball."
        assertEquals("The bear is blue.", TargetPicker.pick("the bear is blue", colors))
    }

    @Test
    fun `asr noise still finds the right phrase`() {
        assertEquals("The bear is blue.", TargetPicker.pick("um the bear is blue I think", colors))
    }

    @Test
    fun `a single vocab word is a valid target`() {
        assertEquals("yellow", TargetPicker.pick("yellow", colors))
    }

    @Test
    fun `expected answers to questions are scoreable`() {
        assertEquals("The sun is yellow.", TargetPicker.pick("the sun is yellow", colors))
    }

    @Test
    fun `free conversation is never scored`() {
        assertNull(TargetPicker.pick("my dog likes to jump on my bed", colors))
        assertNull(TargetPicker.pick("", colors))
        assertNull(TargetPicker.pick("what", null))
    }

    @Test
    fun `punctuation and case do not matter`() {
        assertEquals("I see a red ball.", TargetPicker.pick("I SEE, a red BALL!", colors))
    }
}
