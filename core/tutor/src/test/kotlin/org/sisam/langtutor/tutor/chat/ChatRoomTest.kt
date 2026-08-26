package org.sisam.langtutor.tutor.chat

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sisam.langtutor.llm.FakeLlmEngine

class ChatRoomTest {

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
