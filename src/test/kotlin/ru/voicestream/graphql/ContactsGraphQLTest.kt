package ru.voicestream.graphql

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
class ContactsGraphQLTest {
    @Test
    fun `contact queries and mutations manage pending and accepted relations`() {
        val alice = registerUser("contacts_alice", "Alice Contacts")
        val bob = registerUser("contacts_bob", "Bob Contacts")

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${alice.accessToken}")
            .body(
                """
                {
                  "query": "mutation { sendContactRequest(userId: \"${bob.userId}\") { id status user { id username displayName } createdAt respondedAt blockedAt } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.sendContactRequest.status", equalTo("PENDING"))
            .body("data.sendContactRequest.user.id", equalTo(bob.userId))
            .body("data.sendContactRequest.respondedAt", equalTo(null))
            .body("data.sendContactRequest.blockedAt", equalTo(null))

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${alice.accessToken}")
            .body(
                """
                {
                  "query": "query { outgoingContactRequests { id status user { id } } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.outgoingContactRequests.size()", equalTo(1))
            .body("data.outgoingContactRequests[0].status", equalTo("PENDING"))
            .body("data.outgoingContactRequests[0].user.id", equalTo(bob.userId))

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${bob.accessToken}")
            .body(
                """
                {
                  "query": "query { incomingContactRequests { id status user { id } } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.incomingContactRequests.size()", equalTo(1))
            .body("data.incomingContactRequests[0].status", equalTo("PENDING"))
            .body("data.incomingContactRequests[0].user.id", equalTo(alice.userId))

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${bob.accessToken}")
            .body(
                """
                {
                  "query": "mutation { acceptContactRequest(userId: \"${alice.userId}\") { id status user { id } respondedAt blockedAt } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.acceptContactRequest.status", equalTo("ACCEPTED"))
            .body("data.acceptContactRequest.user.id", equalTo(alice.userId))
            .body("data.acceptContactRequest.respondedAt", notNullValue())
            .body("data.acceptContactRequest.blockedAt", equalTo(null))

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${alice.accessToken}")
            .body(
                """
                {
                  "query": "query { myContacts { id status user { id username displayName } } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.myContacts.size()", equalTo(1))
            .body("data.myContacts[0].status", equalTo("ACCEPTED"))
            .body("data.myContacts[0].user.id", equalTo(bob.userId))

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${bob.accessToken}")
            .body(
                """
                {
                  "query": "query { myContacts { id status user { id username displayName } } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.myContacts.size()", equalTo(1))
            .body("data.myContacts[0].status", equalTo("ACCEPTED"))
            .body("data.myContacts[0].user.id", equalTo(alice.userId))

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${alice.accessToken}")
            .body(
                """
                {
                  "query": "mutation { removeContact(userId: \"${bob.userId}\") }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.removeContact", equalTo(true))

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${alice.accessToken}")
            .body(
                """
                {
                  "query": "query { myContacts { id } outgoingContactRequests { id } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.myContacts.size()", equalTo(0))
            .body("data.outgoingContactRequests.size()", equalTo(0))

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${bob.accessToken}")
            .body(
                """
                {
                  "query": "query { myContacts { id } incomingContactRequests { id } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.myContacts.size()", equalTo(0))
            .body("data.incomingContactRequests.size()", equalTo(0))
    }

    @Test
    fun `contact flow supports decline block and user search`() {
        val requester = registerUser("contacts_requester", "Requester Friend")
        val targetToken = UUID.randomUUID().toString().replace("-", "").take(8)
        val target = registerUser("contacts_target", "Findable-$targetToken")
        val finder = registerUser("contacts_finder", "Finder Friend")

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${requester.accessToken}")
            .body(
                """
                {
                  "query": "mutation { sendContactRequest(userId: \"${target.userId}\") { status user { id } } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.sendContactRequest.status", equalTo("PENDING"))
            .body("data.sendContactRequest.user.id", equalTo(target.userId))

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${target.accessToken}")
            .body(
                """
                {
                  "query": "mutation { declineContactRequest(userId: \"${requester.userId}\") { status user { id } respondedAt } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.declineContactRequest.status", equalTo("DECLINED"))
            .body("data.declineContactRequest.user.id", equalTo(requester.userId))
            .body("data.declineContactRequest.respondedAt", notNullValue())

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${requester.accessToken}")
            .body(
                """
                {
                  "query": "query { outgoingContactRequests { id } myContacts { id } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.outgoingContactRequests.size()", equalTo(0))
            .body("data.myContacts.size()", equalTo(0))

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${target.accessToken}")
            .body(
                """
                {
                  "query": "mutation { blockUser(userId: \"${requester.userId}\") { status user { id } blockedAt } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.blockUser.status", equalTo("BLOCKED"))
            .body("data.blockUser.user.id", equalTo(requester.userId))
            .body("data.blockUser.blockedAt", notNullValue())

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${requester.accessToken}")
            .body(
                """
                {
                  "query": "mutation { sendContactRequest(userId: \"${target.userId}\") { id } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("data.sendContactRequest", equalTo(null))
            .body("errors", notNullValue())

        val exactIdResponse = given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${finder.accessToken}")
            .body(
                """
                {
                  "query": "query { findUsers(query: \"${target.userId}\") { id username displayName avatarMediaKey } }"
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

        assertEquals(
            listOf(target.userId),
            JsonPath.from(exactIdResponse).getList<String>("data.findUsers.id"),
        )

        val partialResponse = given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${finder.accessToken}")
            .body(
                """
                {
                  "query": "query { findUsers(query: \"$targetToken\") { id username displayName } }"
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

        val foundUsers = JsonPath.from(partialResponse).getList<Map<String, String>>("data.findUsers")
        assertTrue(foundUsers.any { user -> user["id"] == target.userId })
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
