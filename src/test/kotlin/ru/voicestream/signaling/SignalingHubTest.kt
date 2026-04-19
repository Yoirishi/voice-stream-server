package ru.voicestream.signaling

import jakarta.json.Json
import jakarta.websocket.CloseReason
import jakarta.websocket.RemoteEndpoint
import jakarta.websocket.Session
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.StringReader
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

class SignalingHubTest {
    @Test
    fun `join notifies existing peers in the same media session`() {
        val hub = SignalingHub()
        val mediaSessionId = UUID.randomUUID()
        val first = FakeSession("first")
        val second = FakeSession("second")

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
        val sender = FakeSession("sender")
        val receiver = FakeSession("receiver")
        val outsider = FakeSession("outsider")

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
        val sender = FakeSession("sender")
        val receiver = FakeSession("receiver")

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
        val remaining = FakeSession("remaining")
        val leaving = FakeSession("leaving")

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
        val session = FakeSession("connection")

        hub.close(session.session, "Invalid token.")

        assertEquals(false, session.isOpen)
        assertEquals(CloseReason.CloseCodes.CANNOT_ACCEPT, session.closeReason?.closeCode)
        assertEquals("Invalid token.", session.closeReason?.reasonPhrase)
    }

    private fun parse(message: String) =
        Json.createReader(StringReader(message)).readObject()

    private class FakeSession(private val connectionId: String) {
        val sentTexts = mutableListOf<String>()
        var closeReason: CloseReason? = null
            private set
        var isOpen: Boolean = true
            private set

        val session: Session = Proxy.newProxyInstance(
            Session::class.java.classLoader,
            arrayOf(Session::class.java),
            SessionHandler(),
        ) as Session

        fun clear() {
            sentTexts.clear()
        }

        private val asyncRemote: RemoteEndpoint.Async = Proxy.newProxyInstance(
            RemoteEndpoint.Async::class.java.classLoader,
            arrayOf(RemoteEndpoint.Async::class.java),
            AsyncRemoteHandler(),
        ) as RemoteEndpoint.Async

        private inner class SessionHandler : InvocationHandler {
            override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? =
                when (method.name) {
                    "getId" -> connectionId
                    "isOpen" -> isOpen
                    "getAsyncRemote" -> asyncRemote
                    "close" -> {
                        isOpen = false
                        closeReason = args?.firstOrNull() as? CloseReason
                        null
                    }
                    "toString" -> "FakeSession($connectionId)"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> defaultValue(method.returnType)
                }
        }

        private inner class AsyncRemoteHandler : InvocationHandler {
            override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
                if (method.name == "sendText") {
                    sentTexts += args?.firstOrNull() as String
                    return if (Future::class.java.isAssignableFrom(method.returnType)) {
                        CompletableFuture.completedFuture<Void?>(null)
                    } else {
                        null
                    }
                }

                return when (method.name) {
                    "toString" -> "FakeAsyncRemote($connectionId)"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> defaultValue(method.returnType)
                }
            }
        }

        private fun defaultValue(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                java.lang.Character.TYPE -> 0.toChar()
                java.lang.Void.TYPE -> null
                else -> null
            }
    }
}
