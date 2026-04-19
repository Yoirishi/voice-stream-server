package ru.voicestream.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import ru.voicestream.domain.RoleKind
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "roles")
class RoleEntity {
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID? = null

    @Column(name = "channel_id", nullable = false)
    var channelId: UUID? = null

    @Column(name = "name", nullable = false, length = 80)
    lateinit var name: String

    @Enumerated(EnumType.STRING)
    @Column(name = "role_kind", nullable = false, length = 16)
    var kind: RoleKind = RoleKind.CUSTOM

    @Column(name = "color_hex", length = 7)
    var colorHex: String? = null

    @Column(name = "position", nullable = false)
    var position: Int = 0

    @Column(name = "is_system", nullable = false)
    var system: Boolean = false

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
}
