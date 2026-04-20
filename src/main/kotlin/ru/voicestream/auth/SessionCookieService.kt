package ru.voicestream.auth

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.HttpHeaders
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@ApplicationScoped
class SessionCookieService(
    @param:ConfigProperty(name = "voice-stream.auth.session-cookie-encryption-key")
    private val encryptionKey: String,
    @param:ConfigProperty(name = "voice-stream.auth.session-cookie-name")
    private val cookieName: String,
    @param:ConfigProperty(name = "voice-stream.auth.session-cookie-path")
    private val cookiePath: String,
    @param:ConfigProperty(name = "voice-stream.auth.session-cookie-secure")
    private val cookieSecure: Boolean,
    @param:ConfigProperty(name = "voice-stream.auth.session-cookie-same-site")
    private val cookieSameSite: String,
    @param:ConfigProperty(name = "voice-stream.auth.refresh-token-ttl-seconds")
    private val refreshTokenTtlSeconds: Long,
) {
    private val secureRandom = SecureRandom()

    fun buildSessionCookie(session: AuthSessionCookie): String {
        val encryptedToken = encrypt(session.refreshToken)
        val attributes = mutableListOf(
            "$cookieName=$encryptedToken",
            "Max-Age=$refreshTokenTtlSeconds",
            "Path=$cookiePath",
            "HttpOnly",
            "SameSite=$cookieSameSite",
        )

        if (cookieSecure) {
            attributes += "Secure"
        }

        return attributes.joinToString("; ")
    }

    fun clearSessionCookie(): String {
        val attributes = mutableListOf(
            "$cookieName=",
            "Max-Age=0",
            "Expires=Thu, 01 Jan 1970 00:00:00 GMT",
            "Path=$cookiePath",
            "HttpOnly",
            "SameSite=$cookieSameSite",
        )

        if (cookieSecure) {
            attributes += "Secure"
        }

        return attributes.joinToString("; ")
    }

    fun readRefreshToken(headers: HttpHeaders): String? =
        headers.cookies[cookieName]
            ?.value
            ?.let(::decrypt)

    fun encrypt(value: String): String {
        val iv = ByteArray(GCM_IV_BYTES)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(cookieAad())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))

        return listOf(COOKIE_VERSION, base64Url(iv), base64Url(encrypted)).joinToString(".")
    }

    fun decrypt(cookieValue: String): String? =
        runCatching {
            val parts = cookieValue.split(".")
            require(parts.size == 3 && parts[0] == COOKIE_VERSION)

            val iv = Base64.getUrlDecoder().decode(parts[1])
            val encrypted = Base64.getUrlDecoder().decode(parts[2])
            val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, keySpec(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(cookieAad())
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        }.getOrNull()

    private fun keySpec(): SecretKeySpec {
        require(encryptionKey.length >= MIN_KEY_CHARS) {
            "voice-stream.auth.session-cookie-encryption-key must contain at least $MIN_KEY_CHARS characters."
        }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(encryptionKey.toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }

    private fun cookieAad(): ByteArray =
        "$COOKIE_VERSION:$cookieName".toByteArray(StandardCharsets.UTF_8)

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    companion object {
        private const val COOKIE_VERSION = "v1"
        private const val AES_GCM_ALGORITHM = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val MIN_KEY_CHARS = 32
    }
}
