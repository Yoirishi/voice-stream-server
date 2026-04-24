package ru.voicestream.signaling

import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import jakarta.json.Json
import jakarta.json.JsonObject
import jakarta.persistence.EntityManager
import jakarta.transaction.UserTransaction
import jakarta.websocket.CloseReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import ru.voicestream.domain.ChannelType
import ru.voicestream.persistence.entity.ChannelEntity
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
    lateinit var entityManager: EntityManager

    @Inject
    lateinit var userTransaction: UserTransaction

    private val httpClient: HttpClient = HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    @Test
    fun `websocket endpoint relays signaling messages between clients`() {
        val firstUser = registerUser("signal_first")
        val secondUser = registerUser("signal_second")
        val channelId = createVoiceChannel(UUID.fromString(firstUser.userId))
        val firstTicket = startMediaSession(firstUser.accessToken, channelId)
        val secondTicket = joinMediaSession(secondUser.accessToken, firstTicket.mediaSessionId)
        val first = connect(firstTicket.mediaSessionId, firstTicket.token)
        val second = connect(secondTicket.mediaSessionId, secondTicket.token)

        try {
            first.webSocket
                .sendText("""{"kind":"offer","sdp":"abc"}""", true)
                .get(5, TimeUnit.SECONDS)

            val signal = second.listener.awaitObject { event ->
                event.getString("type", "") == "signal" &&
                    event.getString("mediaSessionId", "") == firstTicket.mediaSessionId.toString()
            }

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

    @Test
    fun `websocket endpoint closes active peers after media session end`() {
        val firstUser = registerUser("signal_end_first")
        val secondUser = registerUser("signal_end_second")
        val channelId = createVoiceChannel(UUID.fromString(firstUser.userId))
        val firstTicket = startMediaSession(firstUser.accessToken, channelId)
        val secondTicket = joinMediaSession(secondUser.accessToken, firstTicket.mediaSessionId)
        val first = connect(firstTicket.mediaSessionId, firstTicket.token)
        val second = connect(secondTicket.mediaSessionId, secondTicket.token)

        try {
            given()
                .contentType("application/json")
                .header("Authorization", "Bearer ${firstUser.accessToken}")
                .body(
                    """
                    {
                      "query": "mutation { endMediaSession(mediaSessionId: \"${firstTicket.mediaSessionId}\") { id status } }"
                    }
                    """.trimIndent(),
                )
                .post("/graphql")
                .then()
                .statusCode(200)

            val firstClose = first.listener.awaitClose()
            val secondClose = second.listener.awaitClose()
            assertEquals(WebSocket.NORMAL_CLOSURE, firstClose.statusCode)
            assertEquals("Media session has ended.", firstClose.reason)
            assertEquals(WebSocket.NORMAL_CLOSURE, secondClose.statusCode)
            assertEquals("Media session has ended.", secondClose.reason)
        } finally {
            first.close()
            second.close()
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

    private fun startMediaSession(accessToken: String, channelId: UUID): MediaJoinInfo {
        val response = given()
            .contentType("application/json")
            .header("Authorization", "Bearer $accessToken")
            .body(
                """
                {
                  "query": "mutation { startMediaSession(input: { channelId: \"$channelId\", type: VOICE, canPublishAudio: true, canPublishScreen: false }) { mediaSessionId token } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .extract()
            .body()
            .asString()

        val json = JsonPath.from(response)
        return MediaJoinInfo(
            mediaSessionId = UUID.fromString(json.getString("data.startMediaSession.mediaSessionId")),
            token = json.getString("data.startMediaSession.token"),
        )
    }

    private fun joinMediaSession(accessToken: String, mediaSessionId: UUID): MediaJoinInfo {
        val response = given()
            .contentType("application/json")
            .header("Authorization", "Bearer $accessToken")
            .body(
                """
                {
                  "query": "mutation { joinMediaSession(input: { mediaSessionId: \"$mediaSessionId\", canPublishAudio: false, canPublishScreen: false, canSubscribe: true }) { mediaSessionId token } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .extract()
            .body()
            .asString()

        val json = JsonPath.from(response)
        return MediaJoinInfo(
            mediaSessionId = UUID.fromString(json.getString("data.joinMediaSession.mediaSessionId")),
            token = json.getString("data.joinMediaSession.token"),
        )
    }

    private fun createVoiceChannel(ownerUserId: UUID): UUID {
        val channelId = UUID.randomUUID()
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)

        userTransaction.begin()
        try {
            entityManager.persist(
                ChannelEntity().apply {
                    id = channelId
                    this.ownerUserId = ownerUserId
                    name = "signal-$suffix"
                    type = ChannelType.VOICE
                    privateChannel = false
                },
            )
            userTransaction.commit()
        } catch (exception: Throwable) {
            userTransaction.rollback()
            throw exception
        }

        return channelId
    }

    private fun registerUser(prefix: String): RegisteredUser {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val response = given()
            .contentType("application/json")
            .body(
                """
                {
                  "username": "${prefix}_$suffix",
                  "displayName": "${prefix} user",
                  "email": "${prefix}_$suffix@example.com",
                  "password": "correct-horse-battery-staple",
                  "deviceName": "JUnit"
                }
                """.trimIndent(),
            )
            .post("/api/auth/register")
            .then()
            .statusCode(200)
            .extract()
            .body()
            .asString()

        val json = JsonPath.from(response)
        return RegisteredUser(
            userId = json.getString("user.id"),
            accessToken = json.getString("accessToken"),
        )
    }

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

    private data class RegisteredUser(
        val userId: String,
        val accessToken: String,
    )

    private data class MediaJoinInfo(
        val mediaSessionId: UUID,
        val token: String,
    )
}
