package ru.voicestream.auth

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
class AuthResourceTest {
    @Test
    fun `register creates user and returns tokens without sensitive fields`() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)

        given()
            .contentType("application/json")
            .body(
                """
                {
                  "username": "user_$suffix",
                  "displayName": "Test User",
                  "email": "user_$suffix@example.com",
                  "password": "correct-horse-battery-staple",
                  "deviceName": "JUnit"
                }
                """.trimIndent(),
            )
            .post("/api/auth/register")
            .then()
            .statusCode(200)
            .body("tokenType", equalTo("Bearer"))
            .body("accessToken", notNullValue())
            .body("refreshToken", notNullValue())
            .body("user.id", notNullValue())
            .body("user.username", equalTo("user_$suffix"))
            .body("user.displayName", equalTo("Test User"))
            .body("user.email", equalTo(null))
            .body("user.passwordHash", equalTo(null))
    }

    @Test
    fun `login accepts username and password`() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        register("login_$suffix", "login_$suffix@example.com", "very-secret-password")

        given()
            .contentType("application/json")
            .body(
                """
                {
                  "login": "login_$suffix",
                  "password": "very-secret-password",
                  "deviceName": "JUnit"
                }
                """.trimIndent(),
            )
            .post("/api/auth/login")
            .then()
            .statusCode(200)
            .body("tokenType", equalTo("Bearer"))
            .body("accessToken", notNullValue())
            .body("refreshToken", notNullValue())
            .body("user.username", equalTo("login_$suffix"))
    }

    @Test
    fun `login rejects wrong password`() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        register("wrong_$suffix", "wrong_$suffix@example.com", "right-password")

        given()
            .contentType("application/json")
            .body(
                """
                {
                  "login": "wrong_$suffix",
                  "password": "wrong-password"
                }
                """.trimIndent(),
            )
            .post("/api/auth/login")
            .then()
            .statusCode(401)
            .body("error", equalTo("auth_invalid_credentials"))
    }

    @Test
    fun `register rejects duplicate username`() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        register("dup_$suffix", "dup_$suffix@example.com", "strong-password")

        given()
            .contentType("application/json")
            .body(
                """
                {
                  "username": "dup_$suffix",
                  "displayName": "Duplicate",
                  "email": "dup2_$suffix@example.com",
                  "password": "strong-password",
                  "deviceName": "JUnit"
                }
                """.trimIndent(),
            )
            .post("/api/auth/register")
            .then()
            .statusCode(409)
            .body("error", equalTo("auth_conflict"))
    }

    private fun register(username: String, email: String, password: String) {
        given()
            .contentType("application/json")
            .body(
                """
                {
                  "username": "$username",
                  "displayName": "Test User",
                  "email": "$email",
                  "password": "$password",
                  "deviceName": "JUnit"
                }
                """.trimIndent(),
            )
            .post("/api/auth/register")
            .then()
            .statusCode(200)
    }
}
