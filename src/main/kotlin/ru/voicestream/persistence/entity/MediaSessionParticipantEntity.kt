package ru.voicestream.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "media_session_participants")
class MediaSessionParticipantEntity {
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID? = null

    @Column(name = "media_session_id", nullable = false)
    var mediaSessionId: UUID? = null

    @Column(name = "user_id", nullable = false)
    var userId: UUID? = null

    @Column(name = "can_publish_audio", nullable = false)
    var canPublishAudio: Boolean = false

    @Column(name = "can_publish_screen", nullable = false)
    var canPublishScreen: Boolean = false

    @Column(name = "can_subscribe", nullable = false)
    var canSubscribe: Boolean = true

    @Column(name = "joined_at", nullable = false)
    var joinedAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "left_at")
    var leftAt: OffsetDateTime? = null
}
