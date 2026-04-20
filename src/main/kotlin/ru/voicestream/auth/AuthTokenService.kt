package ru.voicestream.auth

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
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
        val tokenIdBytes = ByteArray(ACCESS_TOKEN_ID_BYTES)
        secureRandom.nextBytes(tokenIdBytes)
        val payload = listOf(
            "v1",
            userId.toString(),
            username,
            expiresAt.toInstant().epochSecond.toString(),
            base64Url(tokenIdBytes),
        ).joinToString("|")
        val encodedPayload = base64Url(payload.toByteArray(StandardCharsets.UTF_8))
        val signature = sign(encodedPayload)

        return IssuedAccessToken(
            token = "$encodedPayload.$signature",
            expiresAt = expiresAt,
        )
    }

    fun verifyAccessToken(token: String): AuthPrincipal? {
        val parts = token.split(".")
        if (parts.size != 2) {
            return null
        }

        val expectedSignature = sign(parts[0])
        if (!MessageDigest.isEqual(
                expectedSignature.toByteArray(StandardCharsets.UTF_8),
                parts[1].toByteArray(StandardCharsets.UTF_8),
            )
        ) {
            return null
        }

        val payload = runCatching {
            String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8).split("|")
        }.getOrNull() ?: return null

        if (payload.size !in 4..5 || payload[0] != ACCESS_TOKEN_VERSION) {
            return null
        }

        val userId = runCatching { UUID.fromString(payload[1]) }.getOrNull() ?: return null
        val expiresAtEpoch = payload[3].toLongOrNull() ?: return null
        if (OffsetDateTime.now().toInstant().epochSecond > expiresAtEpoch) {
            return null
        }

        return AuthPrincipal(
            userId = userId,
            username = payload[2],
            expiresAt = OffsetDateTime.ofInstant(Instant.ofEpochSecond(expiresAtEpoch), ZoneOffset.UTC),
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
        private const val ACCESS_TOKEN_VERSION = "v1"
        private const val ACCESS_TOKEN_ID_BYTES = 16
        private const val REFRESH_TOKEN_BYTES = 32
    }
}

data class AuthPrincipal(
    val userId: UUID,
    val username: String,
    val expiresAt: OffsetDateTime,
)

data class IssuedAccessToken(
    val token: String,
    val expiresAt: OffsetDateTime,
)

data class IssuedRefreshToken(
    val token: String,
    val tokenHash: String,
    val expiresAt: OffsetDateTime,
)
