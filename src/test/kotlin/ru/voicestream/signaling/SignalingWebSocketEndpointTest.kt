package ru.voicestream.signaling

import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.json.Json
import jakarta.json.JsonObject
import jakarta.websocket.CloseReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import ru.voicestream.media.MediaTokenInput
import ru.voicestream.media.MediaTokenService
import java.io.StringReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@QuarkusTest
class SignalingWebSocketEndpointTest {
    @field:TestHTTPResource("/")
    lateinit var baseUri: URI

    @Inject
    lateinit var mediaTokenService: MediaTokenService

    private val httpClient: HttpClient = HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    @Test
    fun `websocket endpoint relays signaling messages between clients`() {
        val mediaSessionId = UUID.randomUUID()
        val channelId = UUID.randomUUID()
        val first = connect(mediaSessionId, issueToken(mediaSessionId, channelId))
        val second = connect(mediaSessionId, issueToken(mediaSessionId, channelId))

        try {
            val peerJoined = first.listener.awaitObject { event ->
                event.getString("type", "") == "peerJoined" &&
                    event.getString("mediaSessionId", "") == mediaSessionId.toString()
            }

            first.webSocket
                .sendText("""{"kind":"offer","sdp":"abc"}""", true)
                .get(5, TimeUnit.SECONDS)

            val signal = second.listener.awaitObject { event ->
                event.getString("type", "") == "signal" &&
                    event.getString("mediaSessionId", "") == mediaSessionId.toString()
            }

            assertNotNull(peerJoined.getString("connectionId"))
            assertEquals("offer", signal.getJsonObject("payload").getString("kind"))
            assertEquals("abc", signal.getJsonObject("payload").getString("sdp"))
            assertFalse(signal.getString("from").isBlank())
            assertFalse(first.listener.hasMessage { it.getString("type", "") == "signal" })
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `websocket endpoint rejects invalid media token`() {
        val connection = connect(UUID.randomUUID(), "bad-token")

        try {
            val close = connection.listener.awaitClose()

            assertEquals(CloseReason.CloseCodes.CANNOT_ACCEPT.code, close.statusCode)
            assertEquals("Invalid or expired signaling token.", close.reason)
        } finally {
            connection.close()
        }
    }

    private fun connect(mediaSessionId: UUID, token: String): ConnectedWebSocket {
        val listener = RecordingWebSocketListener()
        val webSocket = httpClient
            .newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .buildAsync(signalingUri(mediaSessionId, token), listener)
            .get(5, TimeUnit.SECONDS)

        return ConnectedWebSocket(webSocket, listener)
    }

    private fun issueToken(mediaSessionId: UUID, channelId: UUID): String =
        mediaTokenService.issue(
            MediaTokenInput(
                mediaSessionId = mediaSessionId,
                channelId = channelId,
                userId = UUID.randomUUID(),
                canPublishAudio = true,
                canPublishScreen = true,
                canSubscribe = true,
            ),
        ).token

    private fun signalingUri(mediaSessionId: UUID, token: String): URI {
        val scheme = if (baseUri.scheme == "https") "wss" else "ws"
        return URI(
            scheme,
            null,
            baseUri.host,
            baseUri.port,
            "/ws/signaling/$mediaSessionId",
            "token=$token",
            null,
        )
    }

    private class ConnectedWebSocket(
        val webSocket: WebSocket,
        val listener: RecordingWebSocketListener,
    ) {
        fun close() {
            runCatching {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test done").get(5, TimeUnit.SECONDS)
            }
        }
    }

    private class RecordingWebSocketListener : WebSocket.Listener {
        private val messages = LinkedBlockingQueue<String>()
        private val closeEvents = LinkedBlockingQueue<CloseEvent>()
        private val errors = LinkedBlockingQueue<Throwable>()
        private val textBuffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            textBuffer.append(data)
            if (last) {
                messages += textBuffer.toString()
                textBuffer.clear()
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
            closeEvents += CloseEvent(statusCode, reason)
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            errors += error
        }

        fun awaitObject(predicate: (JsonObject) -> Boolean): JsonObject {
            val seen = mutableListOf<String>()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)

            while (System.nanoTime() < deadline) {
                val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
                val message = messages.poll(remainingMillis, TimeUnit.MILLISECONDS) ?: break
                seen += message

                val event = parse(message)
                if (predicate(event)) {
                    return event
                }
            }

            errors.poll()?.let { throw AssertionError("WebSocket failed while waiting for message.", it) }
            throw AssertionError("Timed out waiting for WebSocket message. Seen messages: $seen")
        }

        fun awaitClose(): CloseEvent {
            val close = closeEvents.poll(5, TimeUnit.SECONDS)
            errors.poll()?.let { throw AssertionError("WebSocket failed while waiting for close.", it) }
            return close ?: throw AssertionError("Timed out waiting for WebSocket close.")
        }

        fun hasMessage(predicate: (JsonObject) -> Boolean): Boolean =
            messages
                .map { parse(it) }
                .any(predicate)

        private fun parse(message: String): JsonObject =
            Json.createReader(StringReader(message)).readObject()
    }

    private data class CloseEvent(
        val statusCode: Int,
        val reason: String,
    )
}
