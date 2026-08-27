package org.sisam.langtutor.tutor.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sisam.langtutor.llm.FakeLlmEngine
import org.sisam.langtutor.llm.Role

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
        val spoken = mutableListOf<Pair<ChatSpeaker, String>>()
        val room = ChatRoom(
            llm = FakeLlmEngine(listOf("Hello friend!", "So fun! What game do you like?")),
            speak = { s, t -> spoken += s to t },
        )
        room.start()
        room.send("hello")

        assertEquals(
            listOf(ChatSpeaker.CHILD, ChatSpeaker.TUKI),
            room.messages.value.map { it.speaker },
        )
        assertEquals("Hello friend!", room.messages.value[1].text)
        assertEquals(listOf(ChatSpeaker.TUKI to "Hello friend!"), spoken)
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
}
