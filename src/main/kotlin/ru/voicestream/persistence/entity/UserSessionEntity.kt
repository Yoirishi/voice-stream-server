package ru.voicestream.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.ColumnTransformer
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "user_sessions")
class UserSessionEntity {
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID? = null

    @Column(name = "user_id", nullable = false)
    var userId: UUID? = null

    @Column(name = "refresh_token_hash", nullable = false, unique = true)
    lateinit var refreshTokenHash: String

    @Column(name = "device_name", length = 120)
    var deviceName: String? = null

    @Column(name = "user_agent")
    var userAgent: String? = null

    @Column(name = "ip_address", columnDefinition = "inet")
    @ColumnTransformer(write = "?::inet")
    var ipAddress: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "last_seen_at")
    var lastSeenAt: OffsetDateTime? = null

    @Column(name = "expires_at", nullable = false)
    var expiresAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "revoked_at")
    var revokedAt: OffsetDateTime? = null

    @Column(name = "revoke_reason")
    var revokeReason: String? = null
}
