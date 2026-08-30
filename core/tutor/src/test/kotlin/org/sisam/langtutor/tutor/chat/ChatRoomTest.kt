package org.sisam.langtutor.tutor.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sisam.langtutor.llm.FakeLlmEngine
import org.sisam.langtutor.llm.Role
import org.sisam.langtutor.speech.FakeTtsEngine

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRoomTest {

    @Test
    fun `input while the parrot is replying is dropped, and busy says so`() = runTest {
        // The UI gates the send button and the mic on `busy` — `typing` ends
        // when generation ends, before the audio finishes, and a message sent
        // in that gap was silently lost after the input field had cleared.
        val room = ChatRoom(llm = FakeLlmEngine())
        room.start()
        assertFalse(room.busy.value)

        val first = launch { room.send("hello") }
        runCurrent()
        assertTrue(room.busy.value)
        room.send("this one is dropped")
        first.join()

        assertFalse(room.busy.value)
        assertEquals(1, room.messages.value.count { it.speaker == ChatSpeaker.CHILD })
        assertEquals("hello", room.messages.value.first().text)
    }

    @Test
    fun `one learner message yields exactly one spoken reply`() = runTest {
        // The room used to answer twice — Tuki then Kiki — which doubled both
        // the generation and the synthesis for every single message.
        val tts = FakeTtsEngine()
        val room = ChatRoom(
            llm = FakeLlmEngine(listOf("Hello friend!", "So fun! What game do you like?")),
            tts = tts,
        )
        room.start()
        room.send("hello")

        assertEquals(
            listOf(ChatSpeaker.CHILD, ChatSpeaker.TUKI),
            room.messages.value.map { it.speaker },
        )
        assertEquals("Hello friend!", room.messages.value[1].text)
        assertEquals(listOf("Hello friend!"), tts.spoken.map { it.text })
    }

    @Test
    fun `the history sent to the model is verbatim, so the KV cache survives`() = runTest {
        // The engine records each reply exactly as generated. Decorating the
        // window with a "Tuki: " prefix made it stop matching, and every turn
        // rebuilt the conversation from scratch — seconds of redundant
        // prefill per message on a phone.
        val llm = FakeLlmEngine(listOf("Hi there!", "Nice!"))
        val room = ChatRoom(llm = llm)
        room.start()
        room.send("hello")
        room.send("how are you")

        val sent = llm.calls.last().messages.map { it.text }
        assertEquals(listOf("hello", "Hi there!", "how are you"), sent)
        // A per-speaker instruction here would change the system text every
        // turn and defeat reuse on its own.
        assertTrue(llm.calls.last().messages.none { it.role == Role.SYSTEM })
    }

    @Test
    fun `the history repeats the RAW reply, translation line and all`() = runTest {
        // The engine's conversation recorded the reply WITH its HE: line; a
        // window built from the bubbles (undressed English) would mismatch
        // and silently rebuild the whole conversation on every turn — the
        // exact cost the translation was designed not to add.
        val rawReply = "I see a lion.\nHE: אני רואה אריה"
        val llm = FakeLlmEngine(listOf(rawReply, "Nice!"))
        val room = ChatRoom(llm = llm, wantsHebrew = { true })
        room.start()
        room.send("hi")
        room.send("wow")

        assertEquals(
            listOf("hi", rawReply, "wow"),
            llm.calls.last().messages.map { it.text },
        )
    }

    @Test
    fun `a name prefix echoed by the model is stripped`() = runTest {
        val room = ChatRoom(llm = FakeLlmEngine(listOf("Tuki: Hi there!")))
        room.start()
        room.send("hi")
        assertEquals("Hi there!", room.messages.value[1].text)
    }

    @Test
    fun `an unsafe reply is replaced by the fallback`() = runTest {
        val room = ChatRoom(llm = FakeLlmEngine(listOf("Let's play with the gun!")))
        room.start()
        room.send("what should we play")
        assertEquals(ChatRoom.FALLBACK, room.messages.value[1].text)
    }

    @Test
    fun `blank input and reentrant sends are ignored`() = runTest {
        val room = ChatRoom(llm = FakeLlmEngine(listOf("A", "B")))
        room.start()
        room.send("   ")
        assertTrue(room.messages.value.isEmpty())
    }

    // --- the Hebrew translation row ---------------------------------------

    @Test
    fun `the translation rides along in the same reply, not a second one`() = runTest {
        // One generation per turn is the whole reason the second parrot went.
        // Buying the translation with another decode would put it straight
        // back.
        val llm = FakeLlmEngine(listOf("I see a lion.\nHE: \u05d0\u05e0\u05d9 \u05e8\u05d5\u05d0\u05d4 \u05d0\u05e8\u05d9\u05d4"))
        val room = ChatRoom(llm = llm, wantsHebrew = { true })
        room.start()
        room.send("hi")

        assertEquals(1, llm.calls.size)
        val reply = room.messages.value.last()
        assertEquals("I see a lion.", reply.text)
        assertEquals("\u05d0\u05e0\u05d9 \u05e8\u05d5\u05d0\u05d4 \u05d0\u05e8\u05d9\u05d4", reply.hebrew)
    }

    @Test
    fun `only the english is spoken`() = runTest {
        // Reading the translation aloud would undercut the point of an
        // English room, and the marker itself must never reach the voice.
        val tts = FakeTtsEngine()
        val room = ChatRoom(
            llm = FakeLlmEngine(listOf("Hello!\nHE: \u05e9\u05dc\u05d5\u05dd")),
            tts = tts,
            wantsHebrew = { true },
        )
        room.start()
        room.send("hi")
        assertEquals(listOf("Hello!"), tts.spoken.map { it.text })
    }

    @Test
    fun `no translation is asked for when the learner does not want one`() = runTest {
        // Not just hidden — not requested, so it costs no tokens either.
        val llm = FakeLlmEngine(listOf("Hello!"))
        val room = ChatRoom(llm = llm, wantsHebrew = { false })
        room.start()
        room.send("hi")
        assertFalse(llm.calls.single().systemPrompt.contains(ChatRoom.HEBREW_MARKER))
        assertNull(room.messages.value.last().hebrew)
    }

    @Test
    fun `a missing marker leaves the reply whole and the translation absent`() = runTest {
        val room = ChatRoom(
            llm = FakeLlmEngine(listOf("Hello there!")),
            wantsHebrew = { true },
        )
        room.start()
        room.send("hi")
        assertEquals("Hello there!", room.messages.value.last().text)
        assertNull(room.messages.value.last().hebrew)
    }

    @Test
    fun `a translation that is not Hebrew is dropped, not shown`() = runTest {
        // Our own eval put this model below the gate on Hebrew overall. A
        // learner cannot check this row, so anything structurally wrong has
        // to vanish rather than be shown confidently.
        for (bad in listOf("I said hello", "", "   ", "shalom")) {
            val room = ChatRoom(
                llm = FakeLlmEngine(listOf("Hello!\nHE: $bad")),
                wantsHebrew = { true },
            )
            room.start()
            room.send("hi")
            assertEquals("Hello!", room.messages.value.last().text)
            assertNull("expected no translation for \"$bad\"", room.messages.value.last().hebrew)
        }
    }

    @Test
    fun `a mostly-English translation is dropped`() = runTest {
        // Cross-language leakage is the exact failure the eval recorded.
        val room = ChatRoom(
            llm = FakeLlmEngine(listOf("Hello!\nHE: the word is \u05e9\u05dc\u05d5\u05dd in Hebrew")),
            wantsHebrew = { true },
        )
        room.start()
        room.send("hi")
        assertNull(room.messages.value.last().hebrew)
    }

    @Test
    fun `only the first line after the marker is taken`() = runTest {
        // The model sometimes keeps going. A paragraph of commentary under an
        // English sentence is not a translation.
        val room = ChatRoom(
            llm = FakeLlmEngine(
                listOf("Hi!\nHE: \u05e9\u05dc\u05d5\u05dd\nAnd here is some more chatter."),
            ),
            wantsHebrew = { true },
        )
        room.start()
        room.send("hi")
        assertEquals("\u05e9\u05dc\u05d5\u05dd", room.messages.value.last().hebrew)
    }

    @Test
    fun `an unsafe reply loses its translation too`() = runTest {
        val room = ChatRoom(
            llm = FakeLlmEngine(listOf("Let's play with the gun!\nHE: \u05e9\u05dc\u05d5\u05dd")),
            wantsHebrew = { true },
        )
        room.start()
        room.send("hi")
        assertEquals(ChatRoom.FALLBACK, room.messages.value.last().text)
        assertNull(room.messages.value.last().hebrew)
    }

    @Test
    fun `the model's preamble and markdown are stripped, not shown`() = runTest {
        // Measured on the shipping E4B: every translation came back wrapped as
        // `התרגום לעברית הוא: **...**`. Rendered literally that is a bubble
        // full of asterisks and an announcement nobody asked for.
        val heb = "\u05d0\u05e0\u05d9 \u05e8\u05d5\u05d0\u05d4 \u05d0\u05e8\u05d9\u05d4"
        for (dressed in listOf(
            "**$heb**",
            "\u05d4\u05ea\u05e8\u05d2\u05d5\u05dd \u05dc\u05e2\u05d1\u05e8\u05d9\u05ea \u05d4\u05d5\u05d0: **$heb**",
            "\u05ea\u05e8\u05d2\u05d5\u05dd: $heb",
            "\"$heb\"",
        )) {
            val room = ChatRoom(
                llm = FakeLlmEngine(listOf("I see a lion.\nHE: $dressed")),
                wantsHebrew = { true },
            )
            room.start()
            room.send("hi")
            assertEquals("failed to undress: $dressed", heb, room.messages.value.last().hebrew)
        }
    }
}
