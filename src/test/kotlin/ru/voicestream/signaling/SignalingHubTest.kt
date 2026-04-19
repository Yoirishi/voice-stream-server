package ru.voicestream.signaling

import jakarta.json.Json
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
import java.io.StringReader
import java.util.UUID
import java.util.concurrent.CompletableFuture

class SignalingHubTest {
    @Test
    fun `join notifies existing peers in the same media session`() {
        val hub = SignalingHub()
        val mediaSessionId = UUID.randomUUID()
        val first = mockPeer("first")
        val second = mockPeer("second")

        hub.join(mediaSessionId, first.session)
        hub.join(mediaSessionId, second.session)

        assertEquals(1, first.sentTexts.size)
        assertEquals(0, second.sentTexts.size)

        val event = parse(first.sentTexts.single())
        assertEquals("peerJoined", event.getString("type"))
        assertEquals("second", event.getString("connectionId"))
        assertEquals(mediaSessionId.toString(), event.getString("mediaSessionId"))
    }

    @Test
    fun `relay sends json payload to peers in the same media session except sender`() {
        val hub = SignalingHub()
        val mediaSessionId = UUID.randomUUID()
        val otherMediaSessionId = UUID.randomUUID()
        val sender = mockPeer("sender")
        val receiver = mockPeer("receiver")
        val outsider = mockPeer("outsider")

        hub.join(mediaSessionId, sender.session)
        hub.join(mediaSessionId, receiver.session)
        hub.join(otherMediaSessionId, outsider.session)
        sender.clear()
        receiver.clear()
        outsider.clear()

        hub.relay("sender", """{"kind":"offer","sdp":"abc"}""")

        assertEquals(0, sender.sentTexts.size)
        assertEquals(1, receiver.sentTexts.size)
        assertEquals(0, outsider.sentTexts.size)

        val event = parse(receiver.sentTexts.single())
        assertEquals("signal", event.getString("type"))
        assertEquals("sender", event.getString("from"))
        assertEquals(mediaSessionId.toString(), event.getString("mediaSessionId"))
        assertEquals("offer", event.getJsonObject("payload").getString("kind"))
        assertEquals("abc", event.getJsonObject("payload").getString("sdp"))
    }

    @Test
    fun `relay wraps non-json payload as a string`() {
        val hub = SignalingHub()
        val mediaSessionId = UUID.randomUUID()
        val sender = mockPeer("sender")
        val receiver = mockPeer("receiver")

        hub.join(mediaSessionId, sender.session)
        hub.join(mediaSessionId, receiver.session)
        sender.clear()
        receiver.clear()

        hub.relay("sender", "plain signaling payload")

        val event = parse(receiver.sentTexts.single())
        assertEquals("signal", event.getString("type"))
        assertEquals("plain signaling payload", event.getString("payload"))
    }

    @Test
    fun `leave removes peer and notifies remaining peers`() {
        val hub = SignalingHub()
        val mediaSessionId = UUID.randomUUID()
        val remaining = mockPeer("remaining")
        val leaving = mockPeer("leaving")

        hub.join(mediaSessionId, remaining.session)
        hub.join(mediaSessionId, leaving.session)
        remaining.clear()
        leaving.clear()

        hub.leave("leaving")
        hub.relay("remaining", """{"kind":"candidate"}""")

        assertEquals(1, remaining.sentTexts.size)
        assertEquals(0, leaving.sentTexts.size)

        val event = parse(remaining.sentTexts.single())
        assertEquals("peerLeft", event.getString("type"))
        assertEquals("leaving", event.getString("connectionId"))
        assertEquals(mediaSessionId.toString(), event.getString("mediaSessionId"))
    }

    @Test
    fun `close sends cannot accept close reason`() {
        val hub = SignalingHub()
        val session = mock<Session>()
        val reasonCaptor = argumentCaptor<CloseReason>()

        hub.close(session, "Invalid token.")

        verify(session).close(reasonCaptor.capture())
        assertEquals(CloseReason.CloseCodes.CANNOT_ACCEPT, reasonCaptor.firstValue.closeCode)
        assertEquals("Invalid token.", reasonCaptor.firstValue.reasonPhrase)
    }

    private fun mockPeer(connectionId: String, isOpen: Boolean = true): MockPeer {
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

        return MockPeer(session, sentTexts)
    }

    private fun parse(message: String) =
        Json.createReader(StringReader(message)).readObject()

    private data class MockPeer(
        val session: Session,
        val sentTexts: MutableList<String>,
    ) {
        fun clear() {
            sentTexts.clear()
        }
    }
}
