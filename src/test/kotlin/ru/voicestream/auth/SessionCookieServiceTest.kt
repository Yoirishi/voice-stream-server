package ru.voicestream.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class SessionCookieServiceTest {
    @Test
    fun `session cookie stores encrypted refresh token with http only attributes`() {
        val service = service(cookieName = "voice_stream_session")
        val session = AuthSessionCookie(
            refreshToken = "raw-refresh-token",
            expiresAt = OffsetDateTime.now().plusSeconds(60),
        )

        val cookie = service.buildSessionCookie(session)
        val encryptedValue = cookie
            .substringAfter("voice_stream_session=")
            .substringBefore(";")

        assertTrue(cookie.contains("HttpOnly"))
        assertTrue(cookie.contains("SameSite=Lax"))
        assertTrue(cookie.contains("Path=/"))
        assertTrue(cookie.contains("Max-Age=60"))
        assertFalse(cookie.contains("raw-refresh-token"))
        assertEquals("raw-refresh-token", service.decrypt(encryptedValue))
    }

    @Test
    fun `encrypted session cookie is bound to cookie name`() {
        val service = service(cookieName = "voice_stream_session")
        val otherCookieService = service(cookieName = "other_session")
        val encryptedValue = service.encrypt("raw-refresh-token")

        assertNull(otherCookieService.decrypt(encryptedValue))
    }

    private fun service(cookieName: String): SessionCookieService =
        SessionCookieService(
            encryptionKey = "test-session-cookie-encryption-key-change-me",
            cookieName = cookieName,
            cookiePath = "/",
            cookieSecure = false,
            cookieSameSite = "Lax",
            refreshTokenTtlSeconds = 60,
        )
}
