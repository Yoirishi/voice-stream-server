package ru.voicestream.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "permissions")
class PermissionEntity {
    @Id
    @Column(name = "permission_key", nullable = false, length = 80)
    lateinit var key: String

    @Column(name = "description", nullable = false)
    lateinit var description: String

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()
}
