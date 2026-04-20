package ru.voicestream.auth

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.persistence.NoResultException
import jakarta.transaction.Transactional
import ru.voicestream.persistence.entity.UserEntity
import ru.voicestream.persistence.entity.UserSessionEntity
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class AuthService(
    private val entityManager: EntityManager,
    private val passwordHashService: PasswordHashService,
    private val authTokenService: AuthTokenService,
) {
    fun currentUser(userId: UUID): AuthUserView {
        val user = entityManager.find(UserEntity::class.java, userId) ?: throw AuthRequiredException()
        if (user.disabledAt != null) {
            throw AuthForbiddenException("User is disabled.")
        }

        return user.toAuthView()
    }

    @Transactional
    fun refresh(rawRefreshToken: String): AuthResult {
        val now = OffsetDateTime.now()
        val session = findActiveSessionByRefreshToken(rawRefreshToken, now)
        val user = entityManager.find(UserEntity::class.java, requireNotNull(session.userId))
            ?: throw AuthUnauthorizedException()

        if (user.disabledAt != null) {
            throw AuthForbiddenException("User is disabled.")
        }

        val userId = requireNotNull(user.id)
        val accessToken = authTokenService.issueAccessToken(userId, user.username)
        val rotatedRefreshToken = authTokenService.issueRefreshToken()

        session.refreshTokenHash = rotatedRefreshToken.tokenHash
        session.expiresAt = rotatedRefreshToken.expiresAt
        session.lastSeenAt = now

        return AuthResult(
            response = AuthResponse(
                accessToken = accessToken.token,
                accessTokenExpiresAt = accessToken.expiresAt,
                refreshTokenExpiresAt = rotatedRefreshToken.expiresAt,
                user = user.toAuthView(),
            ),
            session = AuthSessionCookie(
                refreshToken = rotatedRefreshToken.token,
                expiresAt = rotatedRefreshToken.expiresAt,
            ),
        )
    }

    @Transactional
    fun register(request: RegisterRequest, userAgent: String?, ipAddress: String?): AuthResult {
        val username = normalizeUsername(request.username)
        val displayName = normalizeDisplayName(request.displayName)
        val email = normalizeEmail(request.email)
        val password = request.password

        requireValidUsername(username)
        require(displayName.isNotBlank()) { "Display name must not be blank." }
        requireValidEmail(email)
        requireValidPassword(password)

        if (findUserByUsernameOrEmail(username) != null) {
            throw AuthConflictException("Username is already taken.")
        }
        if (findUserByUsernameOrEmail(email) != null) {
            throw AuthConflictException("Email is already taken.")
        }

        val now = OffsetDateTime.now()
        val user = UserEntity().apply {
            id = UUID.randomUUID()
            this.username = username
            this.displayName = displayName
            this.email = email
            passwordHash = passwordHashService.hash(password)
            createdAt = now
            updatedAt = now
        }

        entityManager.persist(user)
        return issueSession(user, request.deviceName, userAgent, ipAddress)
    }

    @Transactional
    fun login(request: LoginRequest, userAgent: String?, ipAddress: String?): AuthResult {
        val login = request.login.trim()
        val user = findUserByUsernameOrEmail(login) ?: throw AuthUnauthorizedException()

        if (user.disabledAt != null) {
            throw AuthForbiddenException("User is disabled.")
        }
        if (!passwordHashService.verify(request.password, user.passwordHash)) {
            throw AuthUnauthorizedException()
        }

        return issueSession(user, request.deviceName, userAgent, ipAddress)
    }

    private fun issueSession(
        user: UserEntity,
        deviceName: String?,
        userAgent: String?,
        ipAddress: String?,
    ): AuthResult {
        val userId = requireNotNull(user.id)
        val accessToken = authTokenService.issueAccessToken(userId, user.username)
        val refreshToken = authTokenService.issueRefreshToken()
        val now = OffsetDateTime.now()

        val session = UserSessionEntity().apply {
            id = UUID.randomUUID()
            this.userId = userId
            refreshTokenHash = refreshToken.tokenHash
            this.deviceName = deviceName?.trim()?.takeIf { it.isNotEmpty() }?.take(120)
            this.userAgent = userAgent
            this.ipAddress = ipAddress
            createdAt = now
            lastSeenAt = now
            expiresAt = refreshToken.expiresAt
        }
        entityManager.persist(session)

        return AuthResult(
            response = AuthResponse(
                accessToken = accessToken.token,
                accessTokenExpiresAt = accessToken.expiresAt,
                refreshTokenExpiresAt = refreshToken.expiresAt,
                user = user.toAuthView(),
            ),
            session = AuthSessionCookie(
                refreshToken = refreshToken.token,
                expiresAt = refreshToken.expiresAt,
            ),
        )
    }

    private fun findUserByUsernameOrEmail(login: String): UserEntity? =
        try {
            entityManager
                .createQuery(
                    """
                    select u from UserEntity u
                    where u.username = :login or u.email = :login
                    """.trimIndent(),
                    UserEntity::class.java,
                )
                .setParameter("login", login.trim())
                .singleResult
        } catch (_: NoResultException) {
            null
        }

    private fun findActiveSessionByRefreshToken(rawRefreshToken: String, now: OffsetDateTime): UserSessionEntity {
        val tokenHash = authTokenService.sha256TokenHash(rawRefreshToken)
        val session = try {
            entityManager
                .createQuery(
                    """
                    select s from UserSessionEntity s
                    where s.refreshTokenHash = :tokenHash
                    and s.revokedAt is null
                    """.trimIndent(),
                    UserSessionEntity::class.java,
                )
                .setParameter("tokenHash", tokenHash)
                .singleResult
        } catch (_: NoResultException) {
            throw AuthUnauthorizedException()
        }

        if (!session.expiresAt.isAfter(now)) {
            throw AuthUnauthorizedException()
        }

        return session
    }

    private fun UserEntity.toAuthView(): AuthUserView =
        AuthUserView(
            id = requireNotNull(id),
            username = username,
            displayName = displayName,
            avatarMediaKey = avatarMediaKey,
        )

    private fun normalizeUsername(username: String): String =
        username.trim()

    private fun normalizeDisplayName(displayName: String): String =
        displayName.trim().take(80)

    private fun normalizeEmail(email: String): String =
        email.trim().lowercase()

    private fun requireValidUsername(username: String) {
        require(USERNAME_PATTERN.matches(username)) {
            "Username must be 3-32 chars and may contain latin letters, digits, _, ., or -."
        }
    }

    private fun requireValidEmail(email: String) {
        require(email.length <= 254 && EMAIL_PATTERN.matches(email)) {
            "Email is invalid."
        }
    }

    private fun requireValidPassword(password: String) {
        require(password.length >= 8) { "Password must contain at least 8 characters." }
        require(password.length <= 256) { "Password is too long." }
    }

    companion object {
        private val USERNAME_PATTERN = Regex("^[a-zA-Z0-9_][a-zA-Z0-9_.-]{2,31}$")
        private val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}

class AuthConflictException(message: String) : RuntimeException(message)

class AuthUnauthorizedException : RuntimeException("Invalid login or password.")

class AuthForbiddenException(message: String) : RuntimeException(message)
