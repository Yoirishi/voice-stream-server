package ru.voicestream.channel

import ru.voicestream.auth.AuthUserView
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
import ru.voicestream.persistence.entity.UserEntity
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
                    permissions = permissions.toView(),
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

    fun channelMembers(channelId: UUID, currentUserId: UUID): List<ChannelMemberView> {
        requireChannelPermission(channelId, currentUserId, "view channel members") { it.canView }
        return activeMemberViews(channelId)
    }

    @Transactional
    fun addChannelMember(channelId: UUID, userId: UUID, currentUserId: UUID): ChannelMemberView {
        requireChannelPermission(channelId, currentUserId, "add channel members") { it.canManageMembers }
        val targetUser = requireActiveUser(userId)
        val now = OffsetDateTime.now()
        val userRole = requiredSystemRole(channelId, RoleKind.USER)

        val member = findChannelMember(channelId, userId)?.also { existing ->
            if (existing.leftAt != null) {
                existing.leftAt = null
                existing.joinedAt = now
            }
        } ?: ChannelMemberEntity().apply {
            id = UUID.randomUUID()
            this.channelId = channelId
            this.userId = userId
            joinedAt = now
            entityManager.persist(this)
        }

        assignRoleIfMissing(member, userRole)
        return memberView(requireNotNull(member.id), targetUser)
    }

    @Transactional
    fun removeChannelMember(channelId: UUID, userId: UUID, currentUserId: UUID): Boolean {
        val access = requireChannelPermission(channelId, currentUserId, "remove channel members") { it.canManageMembers }
        require(requireNotNull(access.channel.ownerUserId) != userId) {
            "Channel owner cannot be removed."
        }

        val member = findActiveChannelMember(channelId, userId) ?: return false
        entityManager
            .createQuery(
                "delete from ChannelMemberRoleEntity mr where mr.channelMemberId = :channelMemberId",
            )
            .setParameter("channelMemberId", requireNotNull(member.id))
            .executeUpdate()
        member.leftAt = OffsetDateTime.now()
        return true
    }

    @Transactional
    fun assignChannelRole(channelMemberId: UUID, roleId: UUID, currentUserId: UUID): ChannelMemberView {
        val member = requireNotNull(entityManager.find(ChannelMemberEntity::class.java, channelMemberId)) {
            "Channel member $channelMemberId was not found."
        }
        require(member.leftAt == null) { "Channel member $channelMemberId is not active." }

        val channelId = requireNotNull(member.channelId)
        val access = requireChannelPermission(channelId, currentUserId, "assign channel roles") { it.canManageRoles }
        val role = requireNotNull(entityManager.find(RoleEntity::class.java, roleId)) {
            "Role $roleId was not found."
        }

        require(requireNotNull(role.channelId) == channelId) {
            "Role $roleId does not belong to channel $channelId."
        }
        require(role.kind != RoleKind.OWNER || requireNotNull(member.userId) == requireNotNull(access.channel.ownerUserId)) {
            "OWNER role can only be assigned to the channel owner."
        }

        assignRoleIfMissing(member, role)
        return memberView(channelMemberId)
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

    private fun activeMemberViews(channelId: UUID): List<ChannelMemberView> {
        val members = entityManager
            .createQuery(
                """
                select m from ChannelMemberEntity m
                where m.channelId = :channelId
                and m.leftAt is null
                order by m.joinedAt asc, m.id asc
                """.trimIndent(),
                ChannelMemberEntity::class.java,
            )
            .setParameter("channelId", channelId)
            .resultList

        return memberViews(members)
    }

    private fun memberView(channelMemberId: UUID, user: UserEntity? = null): ChannelMemberView {
        val member = requireNotNull(entityManager.find(ChannelMemberEntity::class.java, channelMemberId)) {
            "Channel member $channelMemberId was not found."
        }
        val usersById = user?.let { mapOf(requireNotNull(it.id) to it) }.orEmpty()
        return memberViews(listOf(member), usersById).single()
    }

    private fun memberViews(
        members: List<ChannelMemberEntity>,
        usersById: Map<UUID, UserEntity> = emptyMap(),
    ): List<ChannelMemberView> {
        if (members.isEmpty()) {
            return emptyList()
        }

        val memberIds = members.map { requireNotNull(it.id) }
        val resolvedUsersById = if (usersById.isNotEmpty()) {
            usersById
        } else {
            usersById(members.map { requireNotNull(it.userId) })
        }
        val rolesByMemberId = rolesByMemberId(memberIds)

        return members.map { member ->
            val memberId = requireNotNull(member.id)
            val userId = requireNotNull(member.userId)
            ChannelMemberView(
                id = memberId,
                channelId = requireNotNull(member.channelId),
                user = requireNotNull(resolvedUsersById[userId]) {
                    "User $userId was not found."
                }.toAuthView(),
                displayName = member.displayName,
                joinedAt = member.joinedAt,
                roles = rolesByMemberId[memberId].orEmpty().map { it.toView() },
            )
        }
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

    private fun findChannelMember(channelId: UUID, userId: UUID): ChannelMemberEntity? =
        entityManager
            .createQuery(
                """
                select m from ChannelMemberEntity m
                where m.channelId = :channelId
                and m.userId = :userId
                """.trimIndent(),
                ChannelMemberEntity::class.java,
            )
            .setParameter("channelId", channelId)
            .setParameter("userId", userId)
            .resultList
            .firstOrNull()

    private fun findActiveChannelMember(channelId: UUID, userId: UUID): ChannelMemberEntity? =
        findChannelMember(channelId, userId)
            ?.takeIf { it.leftAt == null }

    private fun requiredSystemRole(channelId: UUID, roleKind: RoleKind): RoleEntity =
        entityManager
            .createQuery(
                """
                select r from RoleEntity r
                where r.channelId = :channelId
                and r.kind = :roleKind
                """.trimIndent(),
                RoleEntity::class.java,
            )
            .setParameter("channelId", channelId)
            .setParameter("roleKind", roleKind)
            .resultList
            .firstOrNull()
            ?: throw IllegalArgumentException("System role $roleKind was not found for channel $channelId.")

    private fun usersById(userIds: Collection<UUID>): Map<UUID, UserEntity> {
        if (userIds.isEmpty()) {
            return emptyMap()
        }

        return entityManager
            .createQuery(
                "select u from UserEntity u where u.id in :userIds",
                UserEntity::class.java,
            )
            .setParameter("userIds", userIds)
            .resultList
            .associateBy { requireNotNull(it.id) }
    }

    private fun requireActiveUser(userId: UUID): UserEntity {
        val user = requireNotNull(entityManager.find(UserEntity::class.java, userId)) {
            "User $userId was not found."
        }
        require(user.disabledAt == null) { "User $userId is disabled." }
        return user
    }

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

    private fun requireChannelPermission(
        channelId: UUID,
        currentUserId: UUID,
        action: String,
        predicate: (EffectiveChannelPermissions) -> Boolean,
    ): ChannelActorAccess {
        val access = actorAccess(channelId, currentUserId)
        require(predicate(access.permissions)) {
            "Current user cannot $action in channel $channelId."
        }
        return access
    }

    private fun actorAccess(channelId: UUID, currentUserId: UUID): ChannelActorAccess {
        val channel = requireNotNull(entityManager.find(ChannelEntity::class.java, channelId)) {
            "Channel $channelId was not found."
        }

        if (requireNotNull(channel.ownerUserId) == currentUserId) {
            return ChannelActorAccess(
                channel = channel,
                permissions = EffectiveChannelPermissions.full(channel.type),
            )
        }

        val member = findActiveChannelMember(channelId, currentUserId)
            ?: throw IllegalArgumentException("Current user is not a member of channel $channelId.")
        val memberId = requireNotNull(member.id)
        val roles = rolesByMemberId(listOf(memberId))[memberId].orEmpty()
        val permissions = effectivePermissions(
            channel = channel,
            roles = roles,
            permissionsByRoleId = permissionsByRoleId(roles.map { requireNotNull(it.id) }.toSet()),
        )

        return ChannelActorAccess(
            channel = channel,
            permissions = permissions,
        )
    }

    private fun assignRoleIfMissing(member: ChannelMemberEntity, role: RoleEntity) {
        val memberId = requireNotNull(member.id)
        val roleId = requireNotNull(role.id)
        val existing = entityManager
            .createQuery(
                """
                select mr from ChannelMemberRoleEntity mr
                where mr.channelMemberId = :channelMemberId
                and mr.roleId = :roleId
                """.trimIndent(),
                ChannelMemberRoleEntity::class.java,
            )
            .setParameter("channelMemberId", memberId)
            .setParameter("roleId", roleId)
            .resultList
            .firstOrNull()
        if (existing != null) {
            return
        }

        entityManager.persist(
            ChannelMemberRoleEntity().apply {
                channelId = requireNotNull(member.channelId)
                channelMemberId = memberId
                this.roleId = roleId
                assignedAt = OffsetDateTime.now()
            },
        )
    }

    private fun effectivePermissions(
        channel: ChannelEntity,
        roles: List<RoleEntity>,
        permissionsByRoleId: Map<UUID, List<RolePermissionEntity>>,
    ): EffectiveChannelPermissions {
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

        return EffectiveChannelPermissions(
            canView = isAllowed(PERMISSION_CHANNEL_VIEW),
            canSendMessage = channel.type == ChannelType.TEXT && isAllowed(PERMISSION_MESSAGE_SEND),
            canConnectVoice = channel.type == ChannelType.VOICE && isAllowed(PERMISSION_VOICE_CONNECT),
            canManageChannel = isAllowed(PERMISSION_CHANNEL_MANAGE),
            canShareScreen = channel.type == ChannelType.VOICE && isAllowed(PERMISSION_SCREEN_SHARE),
            canManageMembers = isAllowed(PERMISSION_MEMBER_MANAGE),
            canManageRoles = isAllowed(PERMISSION_ROLE_MANAGE),
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

    private fun EffectiveChannelPermissions.toView(): ChannelPermissionsView =
        ChannelPermissionsView(
            canView = canView,
            canSendMessage = canSendMessage,
            canConnectVoice = canConnectVoice,
            canManageChannel = canManageChannel,
            canShareScreen = canShareScreen,
        )

    private fun UserEntity.toAuthView(): AuthUserView =
        AuthUserView(
            id = requireNotNull(id),
            username = username,
            displayName = displayName,
            avatarMediaKey = avatarMediaKey,
        )

    private data class ChannelActorAccess(
        val channel: ChannelEntity,
        val permissions: EffectiveChannelPermissions,
    )

    private data class EffectiveChannelPermissions(
        val canView: Boolean,
        val canSendMessage: Boolean,
        val canConnectVoice: Boolean,
        val canManageChannel: Boolean,
        val canShareScreen: Boolean,
        val canManageMembers: Boolean,
        val canManageRoles: Boolean,
    ) {
        companion object {
            fun full(channelType: ChannelType): EffectiveChannelPermissions =
                EffectiveChannelPermissions(
                    canView = true,
                    canSendMessage = channelType == ChannelType.TEXT,
                    canConnectVoice = channelType == ChannelType.VOICE,
                    canManageChannel = true,
                    canShareScreen = channelType == ChannelType.VOICE,
                    canManageMembers = true,
                    canManageRoles = true,
                )
        }
    }

    companion object {
        private const val PERMISSION_CHANNEL_MANAGE = "channel.manage"
        private const val PERMISSION_CHANNEL_VIEW = "channel.view"
        private const val PERMISSION_MEMBER_MANAGE = "member.manage"
        private const val PERMISSION_MESSAGE_SEND = "message.send"
        private const val PERMISSION_ROLE_MANAGE = "role.manage"
        private const val PERMISSION_VOICE_CONNECT = "voice.connect"
        private const val PERMISSION_SCREEN_SHARE = "screen.share"

        private val CLIENT_PERMISSION_KEYS = setOf(
            PERMISSION_CHANNEL_MANAGE,
            PERMISSION_CHANNEL_VIEW,
            PERMISSION_MEMBER_MANAGE,
            PERMISSION_MESSAGE_SEND,
            PERMISSION_ROLE_MANAGE,
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

data class ChannelMemberView(
    val id: UUID,
    val channelId: UUID,
    val user: AuthUserView,
    val displayName: String?,
    val joinedAt: OffsetDateTime,
    val roles: List<RoleView>,
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
