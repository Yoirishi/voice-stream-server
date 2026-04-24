package ru.voicestream.events

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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.voicestream.domain.ChannelType
import ru.voicestream.domain.PermissionEffect
import ru.voicestream.domain.RoleKind
import ru.voicestream.persistence.entity.ChannelEntity
import ru.voicestream.persistence.entity.ChannelMemberEntity
import ru.voicestream.persistence.entity.ChannelMemberRoleEntity
import ru.voicestream.persistence.entity.RoleEntity
import ru.voicestream.persistence.entity.RolePermissionEntity
import ru.voicestream.persistence.entity.UserContactEntity
import java.io.StringReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@QuarkusTest
class EventWebSocketEndpointTest {
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
    fun `websocket endpoint rejects invalid access token`() {
        val connection = connect("bad-token")

        try {
            val close = connection.listener.awaitClose()

            assertEquals(CloseReason.CloseCodes.CANNOT_ACCEPT.code, close.statusCode)
            assertEquals("Invalid or expired access token.", close.reason)
        } finally {
            connection.close()
        }
    }

    @Test
    fun `websocket endpoint receives channel message created events`() {
        val alice = registerUser("event_channel_alice", "Event Channel Alice")
        val bob = registerUser("event_channel_bob", "Event Channel Bob")
        val channelId = createVisibleChannel(
            ownerUserId = UUID.fromString(alice.userId),
            memberUserId = UUID.fromString(bob.userId),
            type = ChannelType.TEXT,
            namePrefix = "events-text",
        )
        val connection = connect(bob.accessToken)

        try {
            given()
                .contentType("application/json")
                .header("Authorization", "Bearer ${alice.accessToken}")
                .body(
                    """
                    {
                      "query": "mutation { sendChannelMessage(input: { channelId: \"$channelId\", body: \"hello realtime\" }) { id } }"
                    }
                    """.trimIndent(),
                )
                .post("/graphql")
                .then()
                .statusCode(200)

            val event = connection.listener.awaitObject { payload ->
                payload.getString("type", "") == "channelMessageCreated" &&
                    payload.getString("channelId", "") == channelId.toString()
            }

            assertEquals("hello realtime", event.getJsonObject("message").getString("body"))
            assertEquals(alice.userId, event.getJsonObject("message").getString("authorUserId"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `websocket endpoint receives direct and contact events`() {
        val alice = registerUser("event_direct_alice", "Event Direct Alice")
        val bob = registerUser("event_direct_bob", "Event Direct Bob")
        val bobConnection = connect(bob.accessToken)

        try {
            given()
                .contentType("application/json")
                .header("Authorization", "Bearer ${alice.accessToken}")
                .body(
                    """
                    {
                      "query": "mutation { sendContactRequest(userId: \"${bob.userId}\") { id } }"
                    }
                    """.trimIndent(),
                )
                .post("/graphql")
                .then()
                .statusCode(200)

            val contactEvent = bobConnection.listener.awaitObject { payload ->
                payload.getString("type", "") == "contactRequestReceived"
            }
            assertEquals(alice.userId, contactEvent.getJsonObject("contact").getJsonObject("user").getString("id"))
            assertEquals("PENDING", contactEvent.getJsonObject("contact").getString("status"))

            given()
                .contentType("application/json")
                .header("Authorization", "Bearer ${bob.accessToken}")
                .body(
                    """
                    {
                      "query": "mutation { acceptContactRequest(userId: \"${alice.userId}\") { id } }"
                    }
                    """.trimIndent(),
                )
                .post("/graphql")
                .then()
                .statusCode(200)

            val conversationId = JsonPath.from(
                given()
                    .contentType("application/json")
                    .header("Authorization", "Bearer ${alice.accessToken}")
                    .body(
                        """
                        {
                          "query": "mutation { startDirectConversation(userId: \"${bob.userId}\") { id } }"
                        }
                        """.trimIndent(),
                    )
                    .post("/graphql")
                    .then()
                    .statusCode(200)
                    .extract()
                    .body()
                    .asString(),
            ).getString("data.startDirectConversation.id")

            given()
                .contentType("application/json")
                .header("Authorization", "Bearer ${alice.accessToken}")
                .body(
                    """
                    {
                      "query": "mutation { sendDirectMessage(input: { conversationId: \"$conversationId\", body: \"dm event\" }) { id } }"
                    }
                    """.trimIndent(),
                )
                .post("/graphql")
                .then()
                .statusCode(200)

            val directEvent = bobConnection.listener.awaitObject { payload ->
                payload.getString("type", "") == "directMessageCreated" &&
                    payload.getString("conversationId", "") == conversationId
            }

            assertEquals("dm event", directEvent.getJsonObject("message").getString("body"))
            assertEquals(alice.userId, directEvent.getJsonObject("message").getString("authorUserId"))
        } finally {
            bobConnection.close()
        }
    }

    @Test
    fun `websocket endpoint receives media session started events`() {
        val alice = registerUser("event_media_alice", "Event Media Alice")
        val bob = registerUser("event_media_bob", "Event Media Bob")
        val channelId = createVisibleChannel(
            ownerUserId = UUID.fromString(alice.userId),
            memberUserId = UUID.fromString(bob.userId),
            type = ChannelType.VOICE,
            namePrefix = "events-voice",
        )
        val connection = connect(bob.accessToken)

        try {
            given()
                .contentType("application/json")
                .header("Authorization", "Bearer ${alice.accessToken}")
                .body(
                    """
                    {
                      "query": "mutation { startMediaSession(input: { channelId: \"$channelId\", type: VOICE, canPublishAudio: true, canPublishScreen: false }) { mediaSessionId } }"
                    }
                    """.trimIndent(),
                )
                .post("/graphql")
                .then()
                .statusCode(200)

            val event = connection.listener.awaitObject { payload ->
                payload.getString("type", "") == "mediaSessionStarted" &&
                    payload.getString("channelId", "") == channelId.toString()
            }

            assertEquals("VOICE", event.getJsonObject("mediaSession").getString("type"))
            assertEquals("ACTIVE", event.getJsonObject("mediaSession").getString("status"))
            assertTrue(event.getJsonObject("mediaSession").getString("id").isNotBlank())
        } finally {
            connection.close()
        }
    }

    @Test
    fun `websocket endpoint receives media session ended events`() {
        val alice = registerUser("event_media_end_alice", "Event Media End Alice")
        val bob = registerUser("event_media_end_bob", "Event Media End Bob")
        val channelId = createVisibleChannel(
            ownerUserId = UUID.fromString(alice.userId),
            memberUserId = UUID.fromString(bob.userId),
            type = ChannelType.VOICE,
            namePrefix = "events-voice-end",
        )
        val connection = connect(bob.accessToken)

        try {
            val mediaSessionId = JsonPath.from(
                given()
                    .contentType("application/json")
                    .header("Authorization", "Bearer ${alice.accessToken}")
                    .body(
                        """
                        {
                          "query": "mutation { startMediaSession(input: { channelId: \"$channelId\", type: VOICE, canPublishAudio: true, canPublishScreen: false }) { mediaSessionId } }"
                        }
                        """.trimIndent(),
                    )
                    .post("/graphql")
                    .then()
                    .statusCode(200)
                    .extract()
                    .body()
                    .asString(),
            ).getString("data.startMediaSession.mediaSessionId")

            connection.listener.awaitObject { payload ->
                payload.getString("type", "") == "mediaSessionStarted" &&
                    payload.getString("channelId", "") == channelId.toString()
            }

            given()
                .contentType("application/json")
                .header("Authorization", "Bearer ${alice.accessToken}")
                .body(
                    """
                    {
                      "query": "mutation { endMediaSession(mediaSessionId: \"$mediaSessionId\") { id status } }"
                    }
                    """.trimIndent(),
                )
                .post("/graphql")
                .then()
                .statusCode(200)

            val event = connection.listener.awaitObject { payload ->
                payload.getString("type", "") == "mediaSessionEnded" &&
                    payload.getString("channelId", "") == channelId.toString()
            }

            assertEquals(mediaSessionId, event.getJsonObject("mediaSession").getString("id"))
            assertEquals("ENDED", event.getJsonObject("mediaSession").getString("status"))
        } finally {
            connection.close()
        }
    }

    private fun createVisibleChannel(
        ownerUserId: UUID,
        memberUserId: UUID,
        type: ChannelType,
        namePrefix: String,
    ): UUID {
        val channelId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val roleId = UUID.randomUUID()
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)

        userTransaction.begin()
        try {
            entityManager.persist(
                ChannelEntity().apply {
                    id = channelId
                    this.ownerUserId = ownerUserId
                    name = "$namePrefix-$suffix"
                    this.type = type
                    privateChannel = false
                },
            )
            entityManager.persist(
                RoleEntity().apply {
                    id = roleId
                    this.channelId = channelId
                    name = "User-$suffix"
                    kind = RoleKind.USER
                    system = true
                    position = 0
                },
            )
            entityManager.persist(
                RolePermissionEntity().apply {
                    this.channelId = channelId
                    this.roleId = roleId
                    permissionKey = "channel.view"
                    effect = PermissionEffect.ALLOW
                },
            )
            entityManager.persist(
                ChannelMemberEntity().apply {
                    id = memberId
                    this.channelId = channelId
                    this.userId = memberUserId
                },
            )
            entityManager.persist(
                ChannelMemberRoleEntity().apply {
                    this.channelId = channelId
                    channelMemberId = memberId
                    this.roleId = roleId
                },
            )
            userTransaction.commit()
        } catch (exception: Throwable) {
            userTransaction.rollback()
            throw exception
        }

        return channelId
    }

    private fun connect(accessToken: String): ConnectedWebSocket {
        val listener = RecordingWebSocketListener()
        val webSocket = httpClient
            .newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .buildAsync(eventsUri(accessToken), listener)
            .get(5, TimeUnit.SECONDS)

        return ConnectedWebSocket(webSocket, listener)
    }

    private fun eventsUri(accessToken: String): URI {
        val scheme = if (baseUri.scheme == "https") "wss" else "ws"
        return URI(
            scheme,
            null,
            baseUri.host,
            baseUri.port,
            "/ws/events",
            "token=$accessToken",
            null,
        )
    }

    private fun registerUser(prefix: String, displayNamePrefix: String): RegisteredUser {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val response = given()
            .contentType("application/json")
            .body(
                """
                {
                  "username": "${prefix}_$suffix",
                  "displayName": "$displayNamePrefix-$suffix",
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

        private fun parse(message: String): JsonObject =
            Json.createReader(StringReader(message)).readObject()
    }

    private data class RegisteredUser(
        val userId: String,
        val accessToken: String,
    )

    private data class CloseEvent(
        val statusCode: Int,
        val reason: String,
    )
}
