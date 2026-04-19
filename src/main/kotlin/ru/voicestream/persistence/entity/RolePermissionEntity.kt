package ru.voicestream.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import ru.voicestream.domain.PermissionEffect
import java.io.Serializable
import java.time.OffsetDateTime
import java.util.Objects
import java.util.UUID

class RolePermissionEntityId : Serializable {
    var roleId: UUID? = null
    var permissionKey: String? = null

    override fun equals(other: Any?): Boolean =
        other is RolePermissionEntityId &&
            roleId == other.roleId &&
            permissionKey == other.permissionKey

    override fun hashCode(): Int = Objects.hash(roleId, permissionKey)
}

@Entity
@IdClass(RolePermissionEntityId::class)
@Table(name = "role_permissions")
class RolePermissionEntity {
    @Column(name = "channel_id", nullable = false)
    var channelId: UUID? = null

    @Id
    @Column(name = "role_id", nullable = false)
    var roleId: UUID? = null

    @Id
    @Column(name = "permission_key", nullable = false, length = 80)
    var permissionKey: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "effect", nullable = false, length = 8)
    lateinit var effect: PermissionEffect

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()
}
