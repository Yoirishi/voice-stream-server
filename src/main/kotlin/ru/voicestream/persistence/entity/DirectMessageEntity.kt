package ru.voicestream.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "direct_messages")
class DirectMessageEntity {
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID? = null

    @Column(name = "conversation_id", nullable = false)
    var conversationId: UUID? = null

    @Column(name = "author_user_id", nullable = false)
    var authorUserId: UUID? = null

    @Column(name = "body", nullable = false)
    lateinit var body: String

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "edited_at")
    var editedAt: OffsetDateTime? = null

    @Column(name = "deleted_at")
    var deletedAt: OffsetDateTime? = null
}
