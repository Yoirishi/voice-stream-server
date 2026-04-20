package ru.voicestream.channel

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import ru.voicestream.domain.ChannelType
import ru.voicestream.domain.PermissionEffect
import ru.voicestream.domain.RoleKind
import ru.voicestream.persistence.entity.ChannelEntity
import ru.voicestream.persistence.entity.ChannelGroupEntity
import ru.voicestream.persistence.entity.ChannelMemberEntity
import ru.voicestream.persistence.entity.ChannelMemberRoleEntity
import ru.voicestream.persistence.entity.ChannelMessageEntity
import ru.voicestream.persistence.entity.RoleEntity
import ru.voicestream.persistence.entity.RolePermissionEntity
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

    fun visibleChannelDirectory(userId: UUID): ChannelDirectory =
        myChannelDirectory(userId).toChannelDirectory()

    fun myChannelDirectory(userId: UUID): MyChannelDirectory {
        val members = activeChannelMembers(userId)
        if (members.isEmpty()) {
            return MyChannelDirectory(groups = emptyList(), ungroupedChannels = emptyList())
        }

        val memberByChannelId = members.associateBy { requireNotNull(it.channelId) }
        val channels = channels(memberByChannelId.keys)
        val rolesByMemberId = rolesByMemberId(members.map { requireNotNull(it.id) })
        val permissionsByRoleId = permissionsByRoleId(
            rolesByMemberId.values
                .flatten()
                .map { requireNotNull(it.id) }
                .toSet(),
        )

        val visibleChannels = channels.mapNotNull { channel ->
            val member = memberByChannelId[requireNotNull(channel.id)] ?: return@mapNotNull null
            val roles = rolesByMemberId[requireNotNull(member.id)].orEmpty()
            val permissions = effectivePermissions(channel, roles, permissionsByRoleId)

            if (!permissions.canView) {
                return@mapNotNull null
            }

            AccessibleChannel(
                groupId = channel.groupId,
                view = channel.toMyView(
                    role = roles.firstOrNull()?.toView(),
                    permissions = permissions,
                ),
            )
        }

        val visibleChannelsByGroup = visibleChannels
            .filter { it.groupId != null }
            .groupBy { it.groupId }
        val groups = channelGroups(visibleChannelsByGroup.keys.filterNotNull())

        return MyChannelDirectory(
            groups = groups.mapNotNull { group ->
                val groupChannels = visibleChannelsByGroup[group.id]
                    .orEmpty()
                    .map { it.view }
                if (groupChannels.isEmpty()) {
                    null
                } else {
                    MyChannelGroupView(
                        id = requireNotNull(group.id),
                        name = group.name,
                        type = group.type,
                        position = group.position,
                        channels = groupChannels,
                    )
                }
            },
            ungroupedChannels = visibleChannels
                .filter { it.groupId == null }
                .map { it.view },
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

    private fun activeChannelMembers(userId: UUID): List<ChannelMemberEntity> =
        entityManager
            .createQuery(
                """
                select m from ChannelMemberEntity m
                where m.userId = :userId
                and m.leftAt is null
                """.trimIndent(),
                ChannelMemberEntity::class.java,
            )
            .setParameter("userId", userId)
            .resultList

    private fun channels(channelIds: Collection<UUID>): List<ChannelEntity> {
        if (channelIds.isEmpty()) {
            return emptyList()
        }

        return entityManager
            .createQuery(
                """
                select c from ChannelEntity c
                where c.id in :channelIds
                order by c.position asc, c.name asc
                """.trimIndent(),
                ChannelEntity::class.java,
            )
            .setParameter("channelIds", channelIds)
            .resultList
    }

    private fun channelGroups(groupIds: Collection<UUID>): List<ChannelGroupEntity> {
        if (groupIds.isEmpty()) {
            return emptyList()
        }

        return entityManager
            .createQuery(
                """
                select g from ChannelGroupEntity g
                where g.id in :groupIds
                order by g.position asc, g.name asc
                """.trimIndent(),
                ChannelGroupEntity::class.java,
            )
            .setParameter("groupIds", groupIds)
            .resultList
    }

    private fun rolesByMemberId(memberIds: Collection<UUID>): Map<UUID, List<RoleEntity>> {
        if (memberIds.isEmpty()) {
            return emptyMap()
        }

        val memberRoles = entityManager
            .createQuery(
                "select mr from ChannelMemberRoleEntity mr where mr.channelMemberId in :memberIds",
                ChannelMemberRoleEntity::class.java,
            )
            .setParameter("memberIds", memberIds)
            .resultList

        val roleIds = memberRoles.mapNotNull { it.roleId }.toSet()
        if (roleIds.isEmpty()) {
            return emptyMap()
        }

        val rolesById = entityManager
            .createQuery(
                "select r from RoleEntity r where r.id in :roleIds",
                RoleEntity::class.java,
            )
            .setParameter("roleIds", roleIds)
            .resultList
            .associateBy { requireNotNull(it.id) }

        return memberRoles
            .groupBy { requireNotNull(it.channelMemberId) }
            .mapValues { (_, assignments) ->
                assignments
                    .mapNotNull { rolesById[it.roleId] }
                    .sortedWith(roleComparator)
            }
    }

    private fun permissionsByRoleId(roleIds: Collection<UUID>): Map<UUID, List<RolePermissionEntity>> {
        if (roleIds.isEmpty()) {
            return emptyMap()
        }

        return entityManager
            .createQuery(
                """
                select p from RolePermissionEntity p
                where p.roleId in :roleIds
                and p.permissionKey in :permissionKeys
                """.trimIndent(),
                RolePermissionEntity::class.java,
            )
            .setParameter("roleIds", roleIds)
            .setParameter("permissionKeys", CLIENT_PERMISSION_KEYS)
            .resultList
            .groupBy { requireNotNull(it.roleId) }
    }

    private fun effectivePermissions(
        channel: ChannelEntity,
        roles: List<RoleEntity>,
        permissionsByRoleId: Map<UUID, List<RolePermissionEntity>>,
    ): ChannelPermissionsView {
        val hasOwnerRole = roles.any { it.kind == RoleKind.OWNER }
        val permissionsByKey = roles
            .flatMap { role -> permissionsByRoleId[requireNotNull(role.id)].orEmpty() }
            .groupBy { requireNotNull(it.permissionKey) }

        fun isAllowed(permissionKey: String): Boolean {
            if (hasOwnerRole) {
                return true
            }

            val effects = permissionsByKey[permissionKey].orEmpty().map { it.effect }
            return PermissionEffect.DENY !in effects && PermissionEffect.ALLOW in effects
        }

        val canView = isAllowed(PERMISSION_CHANNEL_VIEW)

        return ChannelPermissionsView(
            canView = canView,
            canSendMessage = channel.type == ChannelType.TEXT && isAllowed(PERMISSION_MESSAGE_SEND),
            canConnectVoice = channel.type == ChannelType.VOICE && isAllowed(PERMISSION_VOICE_CONNECT),
            canManageChannel = isAllowed(PERMISSION_CHANNEL_MANAGE),
            canShareScreen = channel.type == ChannelType.VOICE && isAllowed(PERMISSION_SCREEN_SHARE),
        )
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

    private fun ChannelEntity.toMyView(role: RoleView?, permissions: ChannelPermissionsView): MyChannelView =
        MyChannelView(
            id = requireNotNull(id),
            name = name,
            type = type,
            topic = topic,
            position = position,
            privateChannel = privateChannel,
            voiceUserLimit = voiceUserLimit,
            voiceBitrate = voiceBitrate,
            role = role,
            permissions = permissions,
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

    private fun MyChannelDirectory.toChannelDirectory(): ChannelDirectory =
        ChannelDirectory(
            groups = groups.map { group ->
                ChannelGroupView(
                    id = group.id,
                    name = group.name,
                    type = group.type,
                    position = group.position,
                    channels = group.channels.map { it.toChannelView() },
                )
            },
            ungroupedChannels = ungroupedChannels.map { it.toChannelView() },
        )

    private fun MyChannelView.toChannelView(): ChannelView =
        ChannelView(
            id = id,
            name = name,
            type = type,
            topic = topic,
            position = position,
            privateChannel = privateChannel,
            voiceUserLimit = voiceUserLimit,
            voiceBitrate = voiceBitrate,
        )

    private data class AccessibleChannel(
        val groupId: UUID?,
        val view: MyChannelView,
    )

    private fun roleRank(roleKind: RoleKind): Int =
        when (roleKind) {
            RoleKind.OWNER -> 3
            RoleKind.CUSTOM -> 2
            RoleKind.USER -> 1
        }

    private val roleComparator =
        compareByDescending<RoleEntity> { roleRank(it.kind) }
            .thenByDescending { it.position }
            .thenBy { it.name }

    companion object {
        private const val PERMISSION_CHANNEL_MANAGE = "channel.manage"
        private const val PERMISSION_CHANNEL_VIEW = "channel.view"
        private const val PERMISSION_MESSAGE_SEND = "message.send"
        private const val PERMISSION_VOICE_CONNECT = "voice.connect"
        private const val PERMISSION_SCREEN_SHARE = "screen.share"

        private val CLIENT_PERMISSION_KEYS = setOf(
            PERMISSION_CHANNEL_MANAGE,
            PERMISSION_CHANNEL_VIEW,
            PERMISSION_MESSAGE_SEND,
            PERMISSION_VOICE_CONNECT,
            PERMISSION_SCREEN_SHARE,
        )
    }
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

data class MyChannelDirectory(
    val groups: List<MyChannelGroupView>,
    val ungroupedChannels: List<MyChannelView>,
)

data class MyChannelGroupView(
    val id: UUID,
    val name: String,
    val type: ChannelType,
    val position: Int,
    val channels: List<MyChannelView>,
)

data class MyChannelView(
    val id: UUID,
    val name: String,
    val type: ChannelType,
    val topic: String?,
    val position: Int,
    val privateChannel: Boolean,
    val voiceUserLimit: Int?,
    val voiceBitrate: Int?,
    val role: RoleView?,
    val permissions: ChannelPermissionsView,
)

data class ChannelPermissionsView(
    val canView: Boolean,
    val canSendMessage: Boolean,
    val canConnectVoice: Boolean,
    val canManageChannel: Boolean,
    val canShareScreen: Boolean,
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
