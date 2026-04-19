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
@Table(name = "channel_groups")
class ChannelGroupEntity {
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID? = null

    @Column(name = "name", nullable = false, length = 100)
    lateinit var name: String

    @Enumerated(EnumType.STRING)
    @Column(name = "group_type", nullable = false, length = 16)
    lateinit var type: ChannelType

    @Column(name = "position", nullable = false)
    var position: Int = 0

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
}
