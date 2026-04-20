package ru.voicestream.graphql

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.UserTransaction
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import ru.voicestream.domain.ChannelType
import ru.voicestream.persistence.entity.ChannelEntity
import java.util.UUID

@QuarkusTest
class GraphQLAuthContextTest {
    @Inject
    lateinit var entityManager: EntityManager

    @Inject
    lateinit var userTransaction: UserTransaction

    @Test
    fun `graphql rejects requests without bearer access token`() {
        given()
            .contentType("application/json")
            .body(
                """
                {
                  "query": "query { channels { groups { id } } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("data", equalTo(null))
            .body("errors", notNullValue())
    }

    @Test
    fun `sendChannelMessage uses authenticated user from access token`() {
        val registeredUser = registerUser()
        val channelId = createTextChannel(UUID.fromString(registeredUser.userId))

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${registeredUser.accessToken}")
            .body(
                """
                {
                  "query": "mutation { sendChannelMessage(input: { channelId: \"$channelId\", body: \"hello from auth context\" }) { channelId authorUserId body } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.sendChannelMessage.channelId", equalTo(channelId.toString()))
            .body("data.sendChannelMessage.authorUserId", equalTo(registeredUser.userId))
            .body("data.sendChannelMessage.body", equalTo("hello from auth context"))
    }

    @Test
    fun `me returns current authenticated user`() {
        val registeredUser = registerUser()

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${registeredUser.accessToken}")
            .body(
                """
                {
                  "query": "query { me { id username displayName avatarMediaKey } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.me.id", equalTo(registeredUser.userId))
            .body("data.me.username", equalTo(registeredUser.username))
            .body("data.me.displayName", equalTo("GraphQL User"))
            .body("data.me.email", equalTo(null))
            .body("data.me.passwordHash", equalTo(null))
    }

    private fun registerUser(): RegisteredUser {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val response = given()
            .contentType("application/json")
            .body(
                """
                {
                  "username": "gql_$suffix",
                  "displayName": "GraphQL User",
                  "email": "gql_$suffix@example.com",
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
            username = json.getString("user.username"),
            accessToken = json.getString("accessToken"),
        )
    }

    private fun createTextChannel(ownerUserId: UUID): UUID {
        val channelId = UUID.randomUUID()
        userTransaction.begin()
        try {
            entityManager.persist(
                ChannelEntity().apply {
                    id = channelId
                    this.ownerUserId = ownerUserId
                    name = "graphql-auth-context-$channelId"
                    type = ChannelType.TEXT
                },
            )
            userTransaction.commit()
        } catch (exception: Throwable) {
            userTransaction.rollback()
            throw exception
        }

        return channelId
    }

    private data class RegisteredUser(
        val userId: String,
        val username: String,
        val accessToken: String,
    )
}
