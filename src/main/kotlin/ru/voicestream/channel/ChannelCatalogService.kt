package ru.voicestream.channel

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import ru.voicestream.domain.ChannelType
import ru.voicestream.persistence.entity.ChannelEntity
import ru.voicestream.persistence.entity.ChannelGroupEntity
import ru.voicestream.persistence.entity.ChannelMessageEntity
import ru.voicestream.persistence.entity.RoleEntity
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class ChannelCatalogService(
    private val entityManager: EntityManager,
) {
    fun channelDirectory(): ChannelDirectory {
        val groups = entityManager
            .createQuery(
                "select g from ChannelGroupEntity g order by g.position asc, g.name asc",
                ChannelGroupEntity::class.java,
            )
            .resultList

        val channels = entityManager
            .createQuery(
                "select c from ChannelEntity c order by c.position asc, c.name asc",
                ChannelEntity::class.java,
            )
            .resultList

        val channelsByGroup = channels
            .filter { it.groupId != null }
            .groupBy { it.groupId }

        return ChannelDirectory(
            groups = groups.map { group ->
                ChannelGroupView(
                    id = requireNotNull(group.id),
                    name = group.name,
                    type = group.type,
                    position = group.position,
                    channels = channelsByGroup[group.id].orEmpty().map { it.toView() },
                )
            },
            ungroupedChannels = channels
                .filter { it.groupId == null }
                .map { it.toView() },
        )
    }

    fun roles(channelId: UUID): List<RoleView> =
        entityManager
            .createQuery(
                "select r from RoleEntity r where r.channelId = :channelId order by r.position asc, r.name asc",
                RoleEntity::class.java,
            )
            .setParameter("channelId", channelId)
            .resultList
            .map { it.toView() }

    fun messages(channelId: UUID, limit: Int): List<ChannelMessageView> =
        entityManager
            .createQuery(
                """
                select m from ChannelMessageEntity m
                where m.channelId = :channelId and m.deletedAt is null
                order by m.createdAt desc
                """.trimIndent(),
                ChannelMessageEntity::class.java,
            )
            .setParameter("channelId", channelId)
            .setMaxResults(limit.coerceIn(1, 100))
            .resultList
            .asReversed()
            .map { it.toView() }

    @Transactional
    fun sendMessage(channelId: UUID, authorUserId: UUID, body: String): ChannelMessageView {
        val normalizedBody = body.trim()
        require(normalizedBody.isNotEmpty()) { "Message body must not be blank." }

        val channel = requireNotNull(entityManager.find(ChannelEntity::class.java, channelId)) {
            "Channel $channelId was not found."
        }
        require(channel.type == ChannelType.TEXT) { "Messages can only be sent to TEXT channels." }
        require(entityManager.find(ru.voicestream.persistence.entity.UserEntity::class.java, authorUserId) != null) {
            "User $authorUserId was not found."
        }

        val now = OffsetDateTime.now()
        val message = ChannelMessageEntity().apply {
            id = UUID.randomUUID()
            this.channelId = channelId
            this.authorUserId = authorUserId
            this.body = normalizedBody
            createdAt = now
            updatedAt = now
        }

        entityManager.persist(message)
        return message.toView()
    }

    private fun ChannelEntity.toView(): ChannelView =
        ChannelView(
            id = requireNotNull(id),
            name = name,
            type = type,
            topic = topic,
            position = position,
            privateChannel = privateChannel,
            voiceUserLimit = voiceUserLimit,
            voiceBitrate = voiceBitrate,
        )

    private fun RoleEntity.toView(): RoleView =
        RoleView(
            id = requireNotNull(id),
            channelId = requireNotNull(channelId),
            name = name,
            kind = kind.name,
            colorHex = colorHex,
            position = position,
            system = system,
        )

    private fun ChannelMessageEntity.toView(): ChannelMessageView =
        ChannelMessageView(
            id = requireNotNull(id),
            channelId = requireNotNull(channelId),
            authorUserId = requireNotNull(authorUserId),
            body = body,
            createdAt = createdAt,
            editedAt = editedAt,
        )
}

data class ChannelDirectory(
    val groups: List<ChannelGroupView>,
    val ungroupedChannels: List<ChannelView>,
)

data class ChannelGroupView(
    val id: UUID,
    val name: String,
    val type: ChannelType,
    val position: Int,
    val channels: List<ChannelView>,
)

data class ChannelView(
    val id: UUID,
    val name: String,
    val type: ChannelType,
    val topic: String?,
    val position: Int,
    val privateChannel: Boolean,
    val voiceUserLimit: Int?,
    val voiceBitrate: Int?,
)

data class RoleView(
    val id: UUID,
    val channelId: UUID,
    val name: String,
    val kind: String,
    val colorHex: String?,
    val position: Int,
    val system: Boolean,
)

data class ChannelMessageView(
    val id: UUID,
    val channelId: UUID,
    val authorUserId: UUID,
    val body: String,
    val createdAt: OffsetDateTime,
    val editedAt: OffsetDateTime?,
)
