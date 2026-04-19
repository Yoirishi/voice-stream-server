package ru.voicestream.auth

import java.time.OffsetDateTime
import java.util.UUID

class RegisterRequest {
    lateinit var username: String
    lateinit var displayName: String
    lateinit var email: String
    lateinit var password: String
    var deviceName: String? = null
}

class LoginRequest {
    lateinit var login: String
    lateinit var password: String
    var deviceName: String? = null
}

data class AuthResponse(
    val tokenType: String = "Bearer",
    val accessToken: String,
    val accessTokenExpiresAt: OffsetDateTime,
    val refreshToken: String,
    val refreshTokenExpiresAt: OffsetDateTime,
    val user: AuthUserView,
)

data class AuthUserView(
    val id: UUID,
    val username: String,
    val displayName: String,
    val avatarMediaKey: String?,
)

data class AuthErrorResponse(
    val error: String,
    val message: String,
)
