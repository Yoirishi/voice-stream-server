package ru.voicestream.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "users")
class UserEntity {
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID? = null

    @Column(name = "username", nullable = false, unique = true, columnDefinition = "citext")
    lateinit var username: String

    @Column(name = "display_name", nullable = false, length = 80)
    lateinit var displayName: String

    @Column(name = "email", nullable = false, unique = true, columnDefinition = "citext")
    lateinit var email: String

    @Column(name = "password_hash", nullable = false)
    lateinit var passwordHash: String

    @Column(name = "avatar_media_key")
    var avatarMediaKey: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "disabled_at")
    var disabledAt: OffsetDateTime? = null
}
