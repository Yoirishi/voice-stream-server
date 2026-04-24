package ru.voicestream.graphql

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.UserTransaction
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import ru.voicestream.domain.ChannelType
import ru.voicestream.domain.MediaSessionStatus
import ru.voicestream.persistence.entity.ChannelEntity
import ru.voicestream.persistence.entity.MediaSessionEntity
import ru.voicestream.persistence.entity.MediaSessionParticipantEntity
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

        val mediaSessionId = startMediaSession(owner.accessToken, channelId)
        joinMediaSession(member.accessToken, mediaSessionId)

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

        val mediaSessionId = startMediaSession(starter.accessToken, channelId)

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

    private fun startMediaSession(accessToken: String, channelId: UUID): UUID {
        val response = given()
            .contentType("application/json")
            .header("Authorization", "Bearer $accessToken")
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
            .body("errors", equalTo(null))
            .extract()
            .body()
            .asString()

        return UUID.fromString(JsonPath.from(response).getString("data.startMediaSession.mediaSessionId"))
    }

    private fun joinMediaSession(accessToken: String, mediaSessionId: UUID) {
        given()
            .contentType("application/json")
            .header("Authorization", "Bearer $accessToken")
            .body(
                """
                {
                  "query": "mutation { joinMediaSession(input: { mediaSessionId: \"$mediaSessionId\", canPublishAudio: false, canPublishScreen: false, canSubscribe: true }) { mediaSessionId } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.joinMediaSession.mediaSessionId", equalTo(mediaSessionId.toString()))
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
}
