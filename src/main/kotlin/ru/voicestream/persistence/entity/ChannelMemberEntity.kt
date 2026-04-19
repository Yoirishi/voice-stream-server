package ru.voicestream.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "channel_members")
class ChannelMemberEntity {
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID? = null

    @Column(name = "channel_id", nullable = false)
    var channelId: UUID? = null

    @Column(name = "user_id", nullable = false)
    var userId: UUID? = null

    @Column(name = "display_name", length = 80)
    var displayName: String? = null

    @Column(name = "joined_at", nullable = false)
    var joinedAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "left_at")
    var leftAt: OffsetDateTime? = null
}
