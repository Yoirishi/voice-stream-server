package ru.voicestream.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.OffsetDateTime
import java.util.Objects
import java.util.UUID

class ChannelMemberRoleEntityId : Serializable {
    var channelMemberId: UUID? = null
    var roleId: UUID? = null

    override fun equals(other: Any?): Boolean =
        other is ChannelMemberRoleEntityId &&
            channelMemberId == other.channelMemberId &&
            roleId == other.roleId

    override fun hashCode(): Int = Objects.hash(channelMemberId, roleId)
}

@Entity
@IdClass(ChannelMemberRoleEntityId::class)
@Table(name = "channel_member_roles")
class ChannelMemberRoleEntity {
    @Column(name = "channel_id", nullable = false)
    var channelId: UUID? = null

    @Id
    @Column(name = "channel_member_id", nullable = false)
    var channelMemberId: UUID? = null

    @Id
    @Column(name = "role_id", nullable = false)
    var roleId: UUID? = null

    @Column(name = "assigned_at", nullable = false)
    var assignedAt: OffsetDateTime = OffsetDateTime.now()
}
