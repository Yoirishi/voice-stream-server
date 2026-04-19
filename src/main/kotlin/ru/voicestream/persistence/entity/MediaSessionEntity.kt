package ru.voicestream.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import ru.voicestream.domain.MediaSessionStatus
import ru.voicestream.domain.MediaSessionType
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "media_sessions")
class MediaSessionEntity {
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID? = null

    @Column(name = "channel_id", nullable = false)
    var channelId: UUID? = null

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "session_type", nullable = false, length = 16)
    lateinit var type: MediaSessionType

    @Column(name = "sfu_provider", nullable = false, length = 64)
    lateinit var sfuProvider: String

    @Column(name = "sfu_room_name", nullable = false, unique = true, length = 160)
    lateinit var sfuRoomName: String

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: MediaSessionStatus = MediaSessionStatus.ACTIVE

    @Column(name = "started_at", nullable = false)
    var startedAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "ended_at")
    var endedAt: OffsetDateTime? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
}
