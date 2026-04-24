package ru.voicestream.events

import jakarta.websocket.CloseReason
import jakarta.websocket.RemoteEndpoint
import jakarta.websocket.Session
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID
import java.util.concurrent.CompletableFuture

class EventHubTest {
    @Test
    fun `publish sends event to all connections of addressed users only`() {
        val hub = EventHub()
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val first = mockConnection("first")
        val second = mockConnection("second")
        val outsider = mockConnection("outsider")

        hub.join(userId, first.session)
        hub.join(userId, second.session)
        hub.join(otherUserId, outsider.session)

        hub.publishToUsers(setOf(userId), """{"type":"hello"}""")

        assertEquals(listOf("""{"type":"hello"}"""), first.sentTexts)
        assertEquals(listOf("""{"type":"hello"}"""), second.sentTexts)
        assertEquals(emptyList<String>(), outsider.sentTexts)
    }

    @Test
    fun `leave removes connection from future publishes`() {
        val hub = EventHub()
        val userId = UUID.randomUUID()
        val remaining = mockConnection("remaining")
        val leaving = mockConnection("leaving")

        hub.join(userId, remaining.session)
        hub.join(userId, leaving.session)
        hub.leave("leaving")

        hub.publishToUsers(setOf(userId), """{"type":"afterLeave"}""")

        assertEquals(listOf("""{"type":"afterLeave"}"""), remaining.sentTexts)
        assertEquals(emptyList<String>(), leaving.sentTexts)
    }

    @Test
    fun `close sends cannot accept close reason`() {
        val hub = EventHub()
        val session = mock<Session>()
        val reasonCaptor = argumentCaptor<CloseReason>()

        hub.close(session, "Invalid token.")

        verify(session).close(reasonCaptor.capture())
        assertEquals(CloseReason.CloseCodes.CANNOT_ACCEPT, reasonCaptor.firstValue.closeCode)
        assertEquals("Invalid token.", reasonCaptor.firstValue.reasonPhrase)
    }

    private fun mockConnection(connectionId: String, isOpen: Boolean = true): MockConnection {
        val sentTexts = mutableListOf<String>()
        val asyncRemote = mock<RemoteEndpoint.Async>()
        whenever(asyncRemote.sendText(any<String>())).thenAnswer { invocation ->
            sentTexts += invocation.getArgument<String>(0)
            CompletableFuture.completedFuture<Void?>(null)
        }

        val session = mock<Session>()
        whenever(session.id).thenReturn(connectionId)
        whenever(session.isOpen).thenReturn(isOpen)
        whenever(session.asyncRemote).thenReturn(asyncRemote)

        return MockConnection(session, sentTexts)
    }

    private data class MockConnection(
        val session: Session,
        val sentTexts: MutableList<String>,
    )
}
