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
import org.junit.jupiter.api.Assertions.assertTrue
import ru.voicestream.domain.ContactStatus
import ru.voicestream.persistence.entity.UserContactEntity
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.jupiter.api.Test

@QuarkusTest
class DirectMessagesGraphQLTest {
    @Inject
    lateinit var entityManager: EntityManager

    @Inject
    lateinit var userTransaction: UserTransaction

    @Test
    fun `direct conversations start reuse and exchange messages`() {
        val alice = registerUser("dm_alice", "Alice DM")
        val bob = registerUser("dm_bob", "Bob DM")
        createAcceptedContact(UUID.fromString(alice.userId), UUID.fromString(bob.userId))

        val firstStartResponse = given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${alice.accessToken}")
            .body(
                """
                {
                  "query": "mutation { startDirectConversation(userId: \"${bob.userId}\") { id user { id username displayName } createdAt updatedAt lastMessage { id body } } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.startDirectConversation.user.id", equalTo(bob.userId))
            .body("data.startDirectConversation.lastMessage", equalTo(null))
            .extract()
            .body()
            .asString()

        val conversationId = JsonPath.from(firstStartResponse).getString("data.startDirectConversation.id")

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${bob.accessToken}")
            .body(
                """
                {
                  "query": "mutation { startDirectConversation(userId: \"${alice.userId}\") { id user { id } } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.startDirectConversation.id", equalTo(conversationId))
            .body("data.startDirectConversation.user.id", equalTo(alice.userId))

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${alice.accessToken}")
            .body(
                """
                {
                  "query": "mutation { sendDirectMessage(input: { conversationId: \"$conversationId\", body: \"  hello bob  \" }) { id conversationId authorUserId body createdAt editedAt } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.sendDirectMessage.conversationId", equalTo(conversationId))
            .body("data.sendDirectMessage.authorUserId", equalTo(alice.userId))
            .body("data.sendDirectMessage.body", equalTo("hello bob"))

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${bob.accessToken}")
            .body(
                """
                {
                  "query": "mutation { sendDirectMessage(input: { conversationId: \"$conversationId\", body: \"yo\" }) { id conversationId authorUserId body } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.sendDirectMessage.conversationId", equalTo(conversationId))
            .body("data.sendDirectMessage.authorUserId", equalTo(bob.userId))
            .body("data.sendDirectMessage.body", equalTo("yo"))

        val messagesResponse = given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${alice.accessToken}")
            .body(
                """
                {
                  "query": "query { directMessages(conversationId: \"$conversationId\", limit: 50) { id conversationId authorUserId body createdAt editedAt } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.directMessages.size()", equalTo(2))
            .extract()
            .body()
            .asString()

        val messages = JsonPath.from(messagesResponse).getList<Map<String, String>>("data.directMessages")
        assertEquals("hello bob", messages[0]["body"])
        assertEquals(alice.userId, messages[0]["authorUserId"])
        assertEquals("yo", messages[1]["body"])
        assertEquals(bob.userId, messages[1]["authorUserId"])

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${bob.accessToken}")
            .body(
                """
                {
                  "query": "query { myDirectConversations { id user { id username displayName } lastMessage { body authorUserId } } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.myDirectConversations.size()", equalTo(1))
            .body("data.myDirectConversations[0].id", equalTo(conversationId))
            .body("data.myDirectConversations[0].user.id", equalTo(alice.userId))
            .body("data.myDirectConversations[0].lastMessage.body", equalTo("yo"))
            .body("data.myDirectConversations[0].lastMessage.authorUserId", equalTo(bob.userId))
    }

    @Test
    fun `direct messages require accepted contact block on blocked relation and deny non members`() {
        val alice = registerUser("dm_guard_alice", "Guard Alice")
        val bob = registerUser("dm_guard_bob", "Guard Bob")
        val charlie = registerUser("dm_guard_charlie", "Guard Charlie")
        createAcceptedContact(UUID.fromString(alice.userId), UUID.fromString(bob.userId))

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
                .body("errors", equalTo(null))
                .extract()
                .body()
                .asString(),
        ).getString("data.startDirectConversation.id")

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${charlie.accessToken}")
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
            .body("data.startDirectConversation", equalTo(null))
            .body("errors", notNullValue())

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${charlie.accessToken}")
            .body(
                """
                {
                  "query": "query { directMessages(conversationId: \"$conversationId\", limit: 20) { id } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("data.directMessages", equalTo(null))
            .body("errors", notNullValue())

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${bob.accessToken}")
            .body(
                """
                {
                  "query": "mutation { blockUser(userId: \"${alice.userId}\") { status user { id } blockedAt } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.blockUser.status", equalTo("BLOCKED"))
            .body("data.blockUser.user.id", equalTo(alice.userId))
            .body("data.blockUser.blockedAt", notNullValue())

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${alice.accessToken}")
            .body(
                """
                {
                  "query": "mutation { sendDirectMessage(input: { conversationId: \"$conversationId\", body: \"still there?\" }) { id } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("data.sendDirectMessage", equalTo(null))
            .body("errors", notNullValue())
    }

    private fun createAcceptedContact(firstUserId: UUID, secondUserId: UUID) {
        val now = OffsetDateTime.now()
        userTransaction.begin()
        try {
            entityManager.persist(
                UserContactEntity().apply {
                    id = UUID.randomUUID()
                    requesterUserId = firstUserId
                    addresseeUserId = secondUserId
                    status = ContactStatus.ACCEPTED
                    createdAt = now
                    updatedAt = now
                    respondedAt = now
                },
            )
            userTransaction.commit()
        } catch (exception: Throwable) {
            userTransaction.rollback()
            throw exception
        }
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

    private data class RegisteredUser(
        val userId: String,
        val accessToken: String,
    )
}
