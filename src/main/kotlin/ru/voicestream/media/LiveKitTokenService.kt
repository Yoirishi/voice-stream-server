package ru.voicestream.media

import jakarta.enterprise.context.ApplicationScoped
import jakarta.json.Json
import jakarta.json.JsonArrayBuilder
import jakarta.json.JsonObject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@ApplicationScoped
class LiveKitTokenService(
    @param:ConfigProperty(name = "voice-stream.media.livekit.server-url")
    private val serverUrl: String,
    @param:ConfigProperty(name = "voice-stream.media.livekit.api-key")
    private val apiKey: String,
    @param:ConfigProperty(name = "voice-stream.media.livekit.api-secret")
    private val apiSecret: String,
    @param:ConfigProperty(name = "voice-stream.media.livekit.token-ttl-seconds")
    private val tokenTtlSeconds: Long,
) {
    fun issue(input: LiveKitTokenInput): IssuedLiveKitToken {
        val now = OffsetDateTime.now()
        val expiresAt = now.plusSeconds(tokenTtlSeconds)
        val publishSources = Json.createArrayBuilder().applyPublishSources(input)
        val canPublishMedia = input.canPublishAudio || input.canPublishScreen
        val payload = Json.createObjectBuilder()
            .add("iss", apiKey)
            .add("sub", input.identity)
            .add("name", input.name)
            .add("nbf", now.toInstant().epochSecond)
            .add("exp", expiresAt.toInstant().epochSecond)
            .add("metadata", input.metadata.toString())
            .add(
                "video",
                Json.createObjectBuilder()
                    .add("room", input.roomName)
                    .add("roomJoin", true)
                    .add("canSubscribe", input.canSubscribe)
                    .add("canPublish", canPublishMedia)
                    .add("canPublishData", canPublishMedia)
                    .add("canPublishSources", publishSources),
            )
            .build()

        return IssuedLiveKitToken(
            serverUrl = serverUrl.trimEnd('/'),
            token = signJwt(payload),
            expiresAt = expiresAt,
        )
    }

    private fun JsonArrayBuilder.applyPublishSources(input: LiveKitTokenInput): JsonArrayBuilder =
        apply {
            if (input.canPublishAudio) {
                add("microphone")
                add("camera")
            }
            if (input.canPublishScreen) {
                add("screen_share")
                add("screen_share_audio")
            }
        }

    private fun signJwt(payload: JsonObject): String {
        val header = Json.createObjectBuilder()
            .add("alg", "HS256")
            .add("typ", "JWT")
            .build()
        val encodedHeader = base64Url(header.toString().toByteArray(StandardCharsets.UTF_8))
        val encodedPayload = base64Url(payload.toString().toByteArray(StandardCharsets.UTF_8))
        val unsignedToken = "$encodedHeader.$encodedPayload"

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val signature = base64Url(mac.doFinal(unsignedToken.toByteArray(StandardCharsets.UTF_8)))
        return "$unsignedToken.$signature"
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

data class LiveKitTokenInput(
    val identity: String,
    val name: String,
    val roomName: String,
    val metadata: JsonObject,
    val canPublishAudio: Boolean,
    val canPublishScreen: Boolean,
    val canSubscribe: Boolean,
)

data class IssuedLiveKitToken(
    val serverUrl: String,
    val token: String,
    val expiresAt: OffsetDateTime,
)
