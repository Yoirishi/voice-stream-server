package ru.voicestream.graphql

import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.UserTransaction
import jakarta.websocket.Session
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import ru.voicestream.domain.ChannelType
import ru.voicestream.domain.PermissionEffect
import ru.voicestream.domain.RoleKind
import ru.voicestream.persistence.entity.ChannelEntity
import ru.voicestream.persistence.entity.ChannelMemberEntity
import ru.voicestream.persistence.entity.ChannelMemberRoleEntity
import ru.voicestream.persistence.entity.RoleEntity
import ru.voicestream.persistence.entity.RolePermissionEntity
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

@QuarkusTest
class PresenceGraphQLTest {
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
    fun `myContactPresences reflects online and offline contacts`() {
        val alice = registerUser("presence_contact_alice", "Presence Contact Alice")
        val bob = registerUser("presence_contact_bob", "Presence Contact Bob")

        sendContactRequest(alice.accessToken, bob.userId)
        acceptContactRequest(bob.accessToken, alice.userId)

        assertEquals("OFFLINE", contactPresenceStatus(alice.accessToken, bob.userId))

        val bobConnection = connect(bob.accessToken)
        try {
            assertEquals("ONLINE", awaitContactPresenceStatus(alice.accessToken, bob.userId, "ONLINE"))
        } finally {
            bobConnection.close()
        }

        assertEquals("OFFLINE", awaitContactPresenceStatus(alice.accessToken, bob.userId, "OFFLINE"))
    }

    @Test
    fun `myPresence and channelVoiceStates reflect voice state updates`() {
        val alice = registerUser("presence_voice_alice", "Presence Voice Alice")
        val bob = registerUser("presence_voice_bob", "Presence Voice Bob")
        val channelId = createVisibleChannel(
            ownerUserId = UUID.fromString(alice.userId),
            memberUserId = UUID.fromString(bob.userId),
            type = ChannelType.VOICE,
            namePrefix = "presence-voice",
        )
        val bobConnection = connect(bob.accessToken)

        try {
            assertEquals("ONLINE", awaitMyPresenceStatus(bob.accessToken, "ONLINE"))

            val mediaSessionId = JsonPath.from(
                given()
                    .contentType("application/json")
                    .header("Authorization", "Bearer ${bob.accessToken}")
                    .body(
                        """
                        {
                          "query": "mutation { startMediaSession(input: { channelId: \"$channelId\", type: VOICE, canPublishAudio: true, canPublishScreen: true }) { mediaSessionId } }"
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

            given()
                .contentType("application/json")
                .header("Authorization", "Bearer ${bob.accessToken}")
                .body(
                    """
                    {
                      "query": "mutation { updateMyVoiceState(input: { mediaSessionId: \"$mediaSessionId\", muted: true, deafened: true, screenSharing: true }) { user { id } muted deafened screenSharing active onlineStatus channelId mediaSessionId } }"
                    }
                    """.trimIndent(),
                )
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("errors", equalTo(null))
                .body("data.updateMyVoiceState.user.id", equalTo(bob.userId))
                .body("data.updateMyVoiceState.muted", equalTo(true))
                .body("data.updateMyVoiceState.deafened", equalTo(true))
                .body("data.updateMyVoiceState.screenSharing", equalTo(true))
                .body("data.updateMyVoiceState.active", equalTo(true))
                .body("data.updateMyVoiceState.onlineStatus", equalTo("ONLINE"))
                .body("data.updateMyVoiceState.channelId", equalTo(channelId.toString()))
                .body("data.updateMyVoiceState.mediaSessionId", equalTo(mediaSessionId))

            given()
                .contentType("application/json")
                .header("Authorization", "Bearer ${bob.accessToken}")
                .body("""{"query":"query { myPresence { userId onlineStatus voiceChannelId mediaSessionId muted deafened screenSharing updatedAt } }"}""")
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("errors", equalTo(null))
                .body("data.myPresence.userId", equalTo(bob.userId))
                .body("data.myPresence.onlineStatus", equalTo("ONLINE"))
                .body("data.myPresence.voiceChannelId", equalTo(channelId.toString()))
                .body("data.myPresence.mediaSessionId", equalTo(mediaSessionId))
                .body("data.myPresence.muted", equalTo(true))
                .body("data.myPresence.deafened", equalTo(true))
                .body("data.myPresence.screenSharing", equalTo(true))

            given()
                .contentType("application/json")
                .header("Authorization", "Bearer ${alice.accessToken}")
                .body(
                    """
                    {
                      "query": "query { channelVoiceStates(channelId: \"$channelId\") { user { id } channelId mediaSessionId active muted deafened screenSharing onlineStatus updatedAt } }"
                    }
                    """.trimIndent(),
                )
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("errors", equalTo(null))
                .body("data.channelVoiceStates.size()", equalTo(1))
                .body("data.channelVoiceStates[0].user.id", equalTo(bob.userId))
                .body("data.channelVoiceStates[0].channelId", equalTo(channelId.toString()))
                .body("data.channelVoiceStates[0].mediaSessionId", equalTo(mediaSessionId))
                .body("data.channelVoiceStates[0].active", equalTo(true))
                .body("data.channelVoiceStates[0].muted", equalTo(true))
                .body("data.channelVoiceStates[0].deafened", equalTo(true))
                .body("data.channelVoiceStates[0].screenSharing", equalTo(true))
                .body("data.channelVoiceStates[0].onlineStatus", equalTo("ONLINE"))
                .body("data.channelVoiceStates[0].updatedAt", org.hamcrest.Matchers.notNullValue())

            given()
                .contentType("application/json")
                .header("Authorization", "Bearer ${bob.accessToken}")
                .body(
                    """
                    {
                      "query": "mutation { leaveMediaSession(mediaSessionId: \"$mediaSessionId\") }"
                    }
                    """.trimIndent(),
                )
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("errors", equalTo(null))
                .body("data.leaveMediaSession", equalTo(true))

            given()
                .contentType("application/json")
                .header("Authorization", "Bearer ${alice.accessToken}")
                .body(
                    """
                    {
                      "query": "query { channelVoiceStates(channelId: \"$channelId\") { user { id } } }"
                    }
                    """.trimIndent(),
                )
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("errors", equalTo(null))
                .body("data.channelVoiceStates.size()", equalTo(0))
        } finally {
            bobConnection.close()
        }
    }

    private fun contactPresenceStatus(accessToken: String, otherUserId: String): String {
        val response = given()
            .contentType("application/json")
            .header("Authorization", "Bearer $accessToken")
            .body("""{"query":"query { myContactPresences { user { id } presence { onlineStatus } } }"}""")
            .post("/graphql")
            .then()
            .statusCode(200)
            .extract()
            .body()
            .asString()

        val presences = JsonPath.from(response).getList<Map<String, Any>>("data.myContactPresences")
        val contact = presences.firstOrNull { (it["user"] as Map<*, *>)["id"] == otherUserId }
        return requireNotNull((contact?.get("presence") as? Map<*, *>)?.get("onlineStatus") as? String) {
            "Contact presence for user $otherUserId was not found."
        }
    }

    private fun myPresenceStatus(accessToken: String): String {
        val response = given()
            .contentType("application/json")
            .header("Authorization", "Bearer $accessToken")
            .body("""{"query":"query { myPresence { onlineStatus } }"}""")
            .post("/graphql")
            .then()
            .statusCode(200)
            .extract()
            .body()
            .asString()

        return requireNotNull(JsonPath.from(response).getString("data.myPresence.onlineStatus"))
    }

    private fun awaitMyPresenceStatus(accessToken: String, expectedStatus: String): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var lastStatus: String? = null
        while (System.nanoTime() < deadline) {
            lastStatus = myPresenceStatus(accessToken)
            if (lastStatus == expectedStatus) {
                return lastStatus
            }
            Thread.sleep(100)
        }
        return requireNotNull(lastStatus)
    }

    private fun awaitContactPresenceStatus(accessToken: String, otherUserId: String, expectedStatus: String): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var lastStatus: String? = null
        while (System.nanoTime() < deadline) {
            lastStatus = contactPresenceStatus(accessToken, otherUserId)
            if (lastStatus == expectedStatus) {
                return lastStatus
            }
            Thread.sleep(100)
        }
        return requireNotNull(lastStatus)
    }

    private fun sendContactRequest(accessToken: String, userId: String) {
        given()
            .contentType("application/json")
            .header("Authorization", "Bearer $accessToken")
            .body("""{"query":"mutation { sendContactRequest(userId: \"$userId\") { id } }"}""")
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
    }

    private fun acceptContactRequest(accessToken: String, userId: String) {
        given()
            .contentType("application/json")
            .header("Authorization", "Bearer $accessToken")
            .body("""{"query":"mutation { acceptContactRequest(userId: \"$userId\") { id } }"}""")
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
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
        val webSocket = httpClient
            .newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .buildAsync(eventsUri(accessToken), NoopListener())
            .get(5, TimeUnit.SECONDS)

        return ConnectedWebSocket(webSocket)
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
        val usernamePrefix = prefix.take(21)
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val response = given()
            .contentType("application/json")
            .body(
                """
                {
                  "username": "${usernamePrefix}_$suffix",
                  "displayName": "$displayNamePrefix-$suffix",
                  "email": "${usernamePrefix}_$suffix@example.com",
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
        private val webSocket: WebSocket,
    ) {
        fun close() {
            runCatching {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test done").get(5, TimeUnit.SECONDS)
            }
        }
    }

    private class NoopListener : WebSocket.Listener {
        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }
    }

    private data class RegisteredUser(
        val userId: String,
        val accessToken: String,
    )
}
