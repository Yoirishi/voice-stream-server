package ru.voicestream.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class AuthTokenServiceTest {
    @Test
    fun `issued access token verifies to auth principal`() {
        val service = service(accessTokenTtlSeconds = 60)
        val userId = UUID.randomUUID()

        val token = service.issueAccessToken(userId, "danil").token
        val principal = service.verifyAccessToken(token)

        assertEquals(userId, principal?.userId)
        assertEquals("danil", principal?.username)
    }

    @Test
    fun `access token verification rejects tampered token`() {
        val service = service(accessTokenTtlSeconds = 60)
        val token = service.issueAccessToken(UUID.randomUUID(), "danil").token

        assertNull(service.verifyAccessToken("$token-tampered"))
    }

    @Test
    fun `issued access tokens are unique`() {
        val service = service(accessTokenTtlSeconds = 60)
        val userId = UUID.randomUUID()

        val firstToken = service.issueAccessToken(userId, "danil").token
        val secondToken = service.issueAccessToken(userId, "danil").token

        assertNotEquals(firstToken, secondToken)
    }

    @Test
    fun `access token verification rejects expired token`() {
        val service = service(accessTokenTtlSeconds = -1)
        val token = service.issueAccessToken(UUID.randomUUID(), "danil").token

        assertNull(service.verifyAccessToken(token))
    }

    private fun service(accessTokenTtlSeconds: Long): AuthTokenService =
        AuthTokenService().apply {
            tokenSecret = "test-auth-token-secret"
            this.accessTokenTtlSeconds = accessTokenTtlSeconds
            refreshTokenTtlSeconds = 60
        }
}
