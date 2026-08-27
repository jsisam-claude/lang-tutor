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

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRoomTest {

    @Test
    fun `input while the parrots are replying is dropped, and busy says so`() = runTest {
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
    fun `one learner message yields tuki then kiki, each spoken in turn`() = runTest {
        val spoken = mutableListOf<Pair<ChatSpeaker, String>>()
        val room = ChatRoom(
            llm = FakeLlmEngine(listOf("Hello friend!", "So fun! What game do you like?")),
            speak = { s, t -> spoken += s to t },
        )
        room.start()
        room.send("hello")

        val speakers = room.messages.value.map { it.speaker }
        assertEquals(listOf(ChatSpeaker.CHILD, ChatSpeaker.TUKI, ChatSpeaker.KIKI), speakers)
        assertEquals("Hello friend!", room.messages.value[1].text)
        assertEquals(listOf(ChatSpeaker.TUKI, ChatSpeaker.KIKI), spoken.map { it.first })
    }

    @Test
    fun `a name prefix echoed by the model is stripped`() = runTest {
        val room = ChatRoom(llm = FakeLlmEngine(listOf("Tuki: Hi there!", "Kiki: Me too!")))
        room.start()
        room.send("hi")
        assertEquals("Hi there!", room.messages.value[1].text)
        assertEquals("Me too!", room.messages.value[2].text)
    }

    @Test
    fun `an unsafe reply is replaced by the fallback, per speaker`() = runTest {
        val room = ChatRoom(
            llm = FakeLlmEngine(listOf("Let's play with the gun!", "Nice day today!")),
        )
        room.start()
        room.send("what should we play")
        assertEquals(ChatRoom.FALLBACK, room.messages.value[1].text)
        assertEquals("Nice day today!", room.messages.value[2].text)
    }

    @Test
    fun `blank input and reentrant sends are ignored`() = runTest {
        val room = ChatRoom(llm = FakeLlmEngine(listOf("A", "B")))
        room.start()
        room.send("   ")
        assertTrue(room.messages.value.isEmpty())
    }
}
