package ru.voicestream.auth

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@ApplicationScoped
class AuthTokenService {
    @ConfigProperty(name = "voice-stream.auth.token-secret")
    lateinit var tokenSecret: String

    @ConfigProperty(name = "voice-stream.auth.access-token-ttl-seconds")
    var accessTokenTtlSeconds: Long = 900

    @ConfigProperty(name = "voice-stream.auth.refresh-token-ttl-seconds")
    var refreshTokenTtlSeconds: Long = 2_592_000

    private val secureRandom = SecureRandom()

    fun issueAccessToken(userId: UUID, username: String): IssuedAccessToken {
        val expiresAt = OffsetDateTime.now().plusSeconds(accessTokenTtlSeconds)
        val payload = listOf(
            "v1",
            userId.toString(),
            username,
            expiresAt.toInstant().epochSecond.toString(),
        ).joinToString("|")
        val encodedPayload = base64Url(payload.toByteArray(StandardCharsets.UTF_8))
        val signature = sign(encodedPayload)

        return IssuedAccessToken(
            token = "$encodedPayload.$signature",
            expiresAt = expiresAt,
        )
    }

    fun issueRefreshToken(): IssuedRefreshToken {
        val rawBytes = ByteArray(REFRESH_TOKEN_BYTES)
        secureRandom.nextBytes(rawBytes)
        val token = base64Url(rawBytes)

        return IssuedRefreshToken(
            token = token,
            tokenHash = sha256TokenHash(token),
            expiresAt = OffsetDateTime.now().plusSeconds(refreshTokenTtlSeconds),
        )
    }

    fun sha256TokenHash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(StandardCharsets.UTF_8))
        return "sha256\$${base64Url(digest)}"
    }

    private fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(tokenSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return base64Url(mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    companion object {
        private const val REFRESH_TOKEN_BYTES = 32
    }
}

data class IssuedAccessToken(
    val token: String,
    val expiresAt: OffsetDateTime,
)

data class IssuedRefreshToken(
    val token: String,
    val tokenHash: String,
    val expiresAt: OffsetDateTime,
)
