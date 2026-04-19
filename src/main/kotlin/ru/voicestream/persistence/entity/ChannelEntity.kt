package ru.voicestream.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import ru.voicestream.domain.ChannelType
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "channels")
class ChannelEntity {
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID? = null

    @Column(name = "owner_user_id", nullable = false)
    var ownerUserId: UUID? = null

    @Column(name = "group_id")
    var groupId: UUID? = null

    @Column(name = "name", nullable = false, length = 100)
    lateinit var name: String

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false, length = 16)
    lateinit var type: ChannelType

    @Column(name = "topic", length = 512)
    var topic: String? = null

    @Column(name = "position", nullable = false)
    var position: Int = 0

    @Column(name = "is_private", nullable = false)
    var privateChannel: Boolean = false

    @Column(name = "voice_user_limit")
    var voiceUserLimit: Int? = null

    @Column(name = "voice_bitrate")
    var voiceBitrate: Int? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
}
