package ru.voicestream.media

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@ApplicationScoped
class MediaTokenService(
    @param:ConfigProperty(name = "voice-stream.media.token-secret")
    private val tokenSecret: String,
    @param:ConfigProperty(name = "voice-stream.media.token-ttl-seconds")
    private val tokenTtlSeconds: Long,
) {
    fun issue(input: MediaTokenInput): IssuedMediaToken {
        val expiresAt = OffsetDateTime.now().plusSeconds(tokenTtlSeconds)
        val payload = listOf(
            "v1",
            input.mediaSessionId.toString(),
            input.channelId.toString(),
            input.userId.toString(),
            expiresAt.toInstant().epochSecond.toString(),
            input.canPublishAudio.toString(),
            input.canPublishScreen.toString(),
            input.canSubscribe.toString(),
        ).joinToString("|")

        val encodedPayload = base64Url(payload.toByteArray(StandardCharsets.UTF_8))
        val signature = sign(encodedPayload)

        return IssuedMediaToken(
            token = "$encodedPayload.$signature",
            expiresAt = expiresAt,
        )
    }

    fun verify(mediaSessionId: UUID, token: String): Boolean {
        val parts = token.split(".")
        if (parts.size != 2) {
            return false
        }

        val expectedSignature = sign(parts[0])
        if (!MessageDigest.isEqual(expectedSignature.toByteArray(), parts[1].toByteArray())) {
            return false
        }

        val payload = String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8).split("|")
        if (payload.size != 8 || payload[0] != "v1") {
            return false
        }

        val tokenSessionId = runCatching { UUID.fromString(payload[1]) }.getOrNull() ?: return false
        val expiresAtEpoch = payload[4].toLongOrNull() ?: return false

        return tokenSessionId == mediaSessionId &&
            OffsetDateTime.now().toInstant().epochSecond <= expiresAtEpoch
    }

    private fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(tokenSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return base64Url(mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

data class MediaTokenInput(
    val mediaSessionId: UUID,
    val channelId: UUID,
    val userId: UUID,
    val canPublishAudio: Boolean,
    val canPublishScreen: Boolean,
    val canSubscribe: Boolean,
)

data class IssuedMediaToken(
    val token: String,
    val expiresAt: OffsetDateTime,
)
