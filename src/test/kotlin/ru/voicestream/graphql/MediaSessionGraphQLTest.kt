package ru.voicestream.graphql

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import jakarta.json.Json
import jakarta.json.JsonObject
import jakarta.persistence.EntityManager
import jakarta.transaction.UserTransaction
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.voicestream.domain.ChannelType
import ru.voicestream.domain.MediaSessionStatus
import ru.voicestream.persistence.entity.ChannelEntity
import ru.voicestream.persistence.entity.MediaSessionEntity
import ru.voicestream.persistence.entity.MediaSessionParticipantEntity
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

@QuarkusTest
class MediaSessionGraphQLTest {
    @Inject
    lateinit var entityManager: EntityManager

    @Inject
    lateinit var userTransaction: UserTransaction

    @Test
    fun `leaveMediaSession ends session when the last participant leaves`() {
        val owner = registerUser("media_owner")
        val member = registerUser("media_member")
        val channelId = createVoiceChannel(UUID.fromString(owner.userId))

        val started = startMediaSession(owner.accessToken, channelId)
        val mediaSessionId = started.mediaSessionId
        val joined = joinMediaSession(member.accessToken, mediaSessionId)

        assertEquals("livekit", started.sfuProvider)
        assertEquals("ws://localhost:7880", started.serverUrl)
        assertEquals("ws://localhost:7880", started.sfuUrl)
        assertTrue(started.participantToken.isNotBlank())
        assertTrue(started.token.isNotBlank())
        assertTrue(started.signalingUrl.contains("/ws/signaling/$mediaSessionId?token="))
        assertLiveKitGrant(
            token = started.participantToken,
            expectedIdentity = owner.userId,
            expectedRoom = started.roomName,
            expectedName = "media_owner user",
            expectedCanSubscribe = true,
            expectedSources = setOf("microphone", "camera"),
        )
        assertLiveKitGrant(
            token = joined.participantToken,
            expectedIdentity = member.userId,
            expectedRoom = started.roomName,
            expectedName = "media_member user",
            expectedCanSubscribe = true,
            expectedSources = emptySet(),
        )

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${owner.accessToken}")
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
            .header("Authorization", "Bearer ${member.accessToken}")
            .body(
                """
                {
                  "query": "query { activeMediaSessions(channelId: \"$channelId\") { id status } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.activeMediaSessions.size()", equalTo(1))

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${member.accessToken}")
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
            .header("Authorization", "Bearer ${owner.accessToken}")
            .body(
                """
                {
                  "query": "query { activeMediaSessions(channelId: \"$channelId\") { id } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.activeMediaSessions.size()", equalTo(0))

        val session = entityManager.find(MediaSessionEntity::class.java, mediaSessionId)
        assertEquals(MediaSessionStatus.ENDED, session.status)
        assertNotNull(session.endedAt)

        val activeParticipants = entityManager
            .createQuery(
                """
                select p from MediaSessionParticipantEntity p
                where p.mediaSessionId = :mediaSessionId
                and p.leftAt is null
                """.trimIndent(),
                MediaSessionParticipantEntity::class.java,
            )
            .setParameter("mediaSessionId", mediaSessionId)
            .resultList
        assertEquals(0, activeParticipants.size)
    }

    @Test
    fun `endMediaSession requires creator or channel owner and ends the session`() {
        val owner = registerUser("media_end_owner")
        val starter = registerUser("media_end_starter")
        val outsider = registerUser("media_end_outsider")
        val channelId = createVoiceChannel(UUID.fromString(owner.userId))

        val mediaSessionId = startMediaSession(starter.accessToken, channelId).mediaSessionId

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${outsider.accessToken}")
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
            .body("data.endMediaSession", equalTo(null))
            .body("errors", notNullValue())

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${owner.accessToken}")
            .body(
                """
                {
                  "query": "mutation { endMediaSession(mediaSessionId: \"$mediaSessionId\") { id channelId status type } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.endMediaSession.id", equalTo(mediaSessionId.toString()))
            .body("data.endMediaSession.channelId", equalTo(channelId.toString()))
            .body("data.endMediaSession.status", equalTo("ENDED"))
            .body("data.endMediaSession.type", equalTo("VOICE"))

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${starter.accessToken}")
            .body(
                """
                {
                  "query": "query { activeMediaSessions(channelId: \"$channelId\") { id } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.activeMediaSessions.size()", equalTo(0))
    }

    private fun startMediaSession(accessToken: String, channelId: UUID): MediaJoinInfo {
        val response = given()
            .contentType("application/json")
            .header("Authorization", "Bearer $accessToken")
            .body(
                """
                {
                  "query": "mutation { startMediaSession(input: { channelId: \"$channelId\", type: VOICE, canPublishAudio: true, canPublishScreen: false }) { mediaSessionId sfuProvider sfuUrl signalingUrl roomName token participantToken serverUrl expiresAt } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .extract()
            .body()
            .asString()

        val json = JsonPath.from(response)
        return MediaJoinInfo(
            mediaSessionId = UUID.fromString(json.getString("data.startMediaSession.mediaSessionId")),
            sfuProvider = json.getString("data.startMediaSession.sfuProvider"),
            sfuUrl = json.getString("data.startMediaSession.sfuUrl"),
            signalingUrl = json.getString("data.startMediaSession.signalingUrl"),
            roomName = json.getString("data.startMediaSession.roomName"),
            token = json.getString("data.startMediaSession.token"),
            participantToken = json.getString("data.startMediaSession.participantToken"),
            serverUrl = json.getString("data.startMediaSession.serverUrl"),
        )
    }

    private fun joinMediaSession(accessToken: String, mediaSessionId: UUID): MediaJoinInfo {
        val response = given()
            .contentType("application/json")
            .header("Authorization", "Bearer $accessToken")
            .body(
                """
                {
                  "query": "mutation { joinMediaSession(input: { mediaSessionId: \"$mediaSessionId\", canPublishAudio: false, canPublishScreen: false, canSubscribe: true }) { mediaSessionId sfuProvider sfuUrl signalingUrl roomName token participantToken serverUrl expiresAt } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.joinMediaSession.mediaSessionId", equalTo(mediaSessionId.toString()))
            .extract()
            .body()
            .asString()

        val json = JsonPath.from(response)
        return MediaJoinInfo(
            mediaSessionId = UUID.fromString(json.getString("data.joinMediaSession.mediaSessionId")),
            sfuProvider = json.getString("data.joinMediaSession.sfuProvider"),
            sfuUrl = json.getString("data.joinMediaSession.sfuUrl"),
            signalingUrl = json.getString("data.joinMediaSession.signalingUrl"),
            roomName = json.getString("data.joinMediaSession.roomName"),
            token = json.getString("data.joinMediaSession.token"),
            participantToken = json.getString("data.joinMediaSession.participantToken"),
            serverUrl = json.getString("data.joinMediaSession.serverUrl"),
        )
    }

    private fun assertLiveKitGrant(
        token: String,
        expectedIdentity: String,
        expectedRoom: String,
        expectedName: String,
        expectedCanSubscribe: Boolean,
        expectedSources: Set<String>,
    ) {
        val payload = decodeJwtPayload(token)
        assertEquals("devkey", payload.getString("iss"))
        assertEquals(expectedIdentity, payload.getString("sub"))
        assertEquals(expectedName, payload.getString("name"))

        val metadata = Json.createReader(StringReader(payload.getString("metadata"))).readObject()
        assertTrue(metadata.getString("mediaSessionId").isNotBlank())
        assertTrue(metadata.getString("channelId").isNotBlank())
        assertEquals("VOICE", metadata.getString("mediaSessionType"))

        val video = payload.getJsonObject("video")
        assertEquals(expectedRoom, video.getString("room"))
        assertEquals(true, video.getBoolean("roomJoin"))
        assertEquals(expectedCanSubscribe, video.getBoolean("canSubscribe"))
        assertEquals(expectedSources.isNotEmpty(), video.getBoolean("canPublish"))
        assertEquals(expectedSources.isNotEmpty(), video.getBoolean("canPublishData"))
        val sources = video.getJsonArray("canPublishSources")
            .map { it.toString().trim('"') }
            .toSet()
        assertEquals(expectedSources, sources)
    }

    private fun decodeJwtPayload(token: String): JsonObject {
        val encodedPayload = token.split(".")[1]
        val json = String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8)
        return Json.createReader(StringReader(json)).readObject()
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
                    name = "media-$suffix"
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

    private data class RegisteredUser(
        val userId: String,
        val accessToken: String,
    )

    private data class MediaJoinInfo(
        val mediaSessionId: UUID,
        val sfuProvider: String,
        val sfuUrl: String,
        val signalingUrl: String,
        val roomName: String,
        val token: String,
        val participantToken: String,
        val serverUrl: String,
    )
}
