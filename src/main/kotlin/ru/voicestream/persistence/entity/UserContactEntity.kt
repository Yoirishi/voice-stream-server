package ru.voicestream.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import ru.voicestream.domain.ContactStatus
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "user_contacts")
class UserContactEntity {
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID? = null

    @Column(name = "requester_user_id", nullable = false)
    var requesterUserId: UUID? = null

    @Column(name = "addressee_user_id", nullable = false)
    var addresseeUserId: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    lateinit var status: ContactStatus

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "responded_at")
    var respondedAt: OffsetDateTime? = null

    @Column(name = "blocked_at")
    var blockedAt: OffsetDateTime? = null
}
