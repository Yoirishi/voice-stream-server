package ru.voicestream.direct

import ru.voicestream.auth.AuthUserView
import ru.voicestream.contact.ContactService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import ru.voicestream.domain.ContactStatus
import ru.voicestream.persistence.entity.DirectConversationEntity
import ru.voicestream.persistence.entity.DirectConversationMemberEntity
import ru.voicestream.persistence.entity.DirectMessageEntity
import ru.voicestream.persistence.entity.UserEntity
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class DirectConversationService(
    private val entityManager: EntityManager,
    private val contactService: ContactService,
) {
    fun myDirectConversations(currentUserId: UUID): List<DirectConversationView> {
        val conversations = entityManager
            .createQuery(
                """
                select c from DirectConversationEntity c
                where exists (
                    select 1 from DirectConversationMemberEntity m
                    where m.conversationId = c.id
                    and m.userId = :currentUserId
                )
                order by c.updatedAt desc, c.createdAt desc, c.id desc
                """.trimIndent(),
                DirectConversationEntity::class.java,
            )
            .setParameter("currentUserId", currentUserId)
            .resultList

        if (conversations.isEmpty()) {
            return emptyList()
        }

        val conversationIds = conversations.map { requireNotNull(it.id) }
        val usersById = usersById(conversations.map { otherUserId(it, currentUserId) }.toSet())
        val lastMessagesByConversationId = lastMessagesByConversationId(conversationIds)

        return conversations.map { conversation ->
            conversation.toView(
                currentUserId = currentUserId,
                usersById = usersById,
                lastMessagesByConversationId = lastMessagesByConversationId,
            )
        }
    }

    fun directMessages(conversationId: UUID, limit: Int, currentUserId: UUID): List<DirectMessageView> {
        requireConversationAccess(conversationId, currentUserId)
        return entityManager
            .createQuery(
                """
                select m from DirectMessageEntity m
                where m.conversationId = :conversationId
                and m.deletedAt is null
                order by m.createdAt desc, m.id desc
                """.trimIndent(),
                DirectMessageEntity::class.java,
            )
            .setParameter("conversationId", conversationId)
            .setMaxResults(limit.coerceIn(1, 100))
            .resultList
            .asReversed()
            .map { it.toView() }
    }

    @Transactional
    fun startDirectConversation(userId: UUID, currentUserId: UUID): DirectConversationView {
        require(currentUserId != userId) { "Cannot start a direct conversation with yourself." }
        val targetUser = requireActiveUser(userId)
        val existingConversation = findConversationBetweenUsers(currentUserId, userId)

        if (existingConversation != null) {
            requireNotBlockedRelationship(currentUserId, userId)
            val now = OffsetDateTime.now()
            ensureConversationMember(existingConversation, currentUserId, now)
            ensureConversationMember(existingConversation, userId, now)
            return existingConversation.toView(
                currentUserId = currentUserId,
                usersById = mapOf(userId to targetUser),
                lastMessagesByConversationId = lastMessagesByConversationId(setOf(requireNotNull(existingConversation.id))),
            )
        }

        require(contactService.relationshipStatus(currentUserId, userId) == ContactStatus.ACCEPTED) {
            "Direct conversations require an accepted contact."
        }

        val now = OffsetDateTime.now()
        val conversation = DirectConversationEntity().apply {
            id = UUID.randomUUID()
            participantAUserId = currentUserId
            participantBUserId = userId
            createdAt = now
            updatedAt = now
        }
        entityManager.persist(conversation)
        ensureConversationMember(conversation, currentUserId, now)
        ensureConversationMember(conversation, userId, now)

        return conversation.toView(
            currentUserId = currentUserId,
            usersById = mapOf(userId to targetUser),
            lastMessagesByConversationId = emptyMap(),
        )
    }

    @Transactional
    fun sendDirectMessage(conversationId: UUID, currentUserId: UUID, body: String): DirectMessageView {
        val normalizedBody = body.trim()
        require(normalizedBody.isNotEmpty()) { "Message body must not be blank." }

        val access = requireConversationAccess(conversationId, currentUserId)
        requireActiveUser(currentUserId)
        requireNotBlockedRelationship(currentUserId, access.otherUserId)

        val now = OffsetDateTime.now()
        val message = DirectMessageEntity().apply {
            id = UUID.randomUUID()
            this.conversationId = conversationId
            authorUserId = currentUserId
            this.body = normalizedBody
            createdAt = now
            updatedAt = now
        }
        entityManager.persist(message)
        access.conversation.updatedAt = now
        return message.toView()
    }

    private fun requireConversationAccess(conversationId: UUID, currentUserId: UUID): DirectConversationAccess {
        val conversation = requireNotNull(entityManager.find(DirectConversationEntity::class.java, conversationId)) {
            "Direct conversation $conversationId was not found."
        }
        require(isConversationMember(conversationId, currentUserId)) {
            "Current user is not a member of direct conversation $conversationId."
        }
        return DirectConversationAccess(
            conversation = conversation,
            otherUserId = otherUserId(conversation, currentUserId),
        )
    }

    private fun ensureConversationMember(conversation: DirectConversationEntity, userId: UUID, joinedAt: OffsetDateTime) {
        if (isConversationMember(requireNotNull(conversation.id), userId)) {
            return
        }

        entityManager.persist(
            DirectConversationMemberEntity().apply {
                id = UUID.randomUUID()
                conversationId = requireNotNull(conversation.id)
                this.userId = userId
                this.joinedAt = joinedAt
            },
        )
    }

    private fun isConversationMember(conversationId: UUID, userId: UUID): Boolean =
        entityManager
            .createQuery(
                """
                select m from DirectConversationMemberEntity m
                where m.conversationId = :conversationId
                and m.userId = :userId
                """.trimIndent(),
                DirectConversationMemberEntity::class.java,
            )
            .setParameter("conversationId", conversationId)
            .setParameter("userId", userId)
            .setMaxResults(1)
            .resultList
            .isNotEmpty()

    private fun findConversationBetweenUsers(firstUserId: UUID, secondUserId: UUID): DirectConversationEntity? =
        entityManager
            .createQuery(
                """
                select c from DirectConversationEntity c
                where (c.participantAUserId = :firstUserId and c.participantBUserId = :secondUserId)
                   or (c.participantAUserId = :secondUserId and c.participantBUserId = :firstUserId)
                """.trimIndent(),
                DirectConversationEntity::class.java,
            )
            .setParameter("firstUserId", firstUserId)
            .setParameter("secondUserId", secondUserId)
            .setMaxResults(1)
            .resultList
            .firstOrNull()

    private fun lastMessagesByConversationId(conversationIds: Collection<UUID>): Map<UUID, DirectMessageEntity> {
        if (conversationIds.isEmpty()) {
            return emptyMap()
        }

        return entityManager
            .createQuery(
                """
                select m from DirectMessageEntity m
                where m.conversationId in :conversationIds
                and m.deletedAt is null
                order by m.createdAt desc, m.id desc
                """.trimIndent(),
                DirectMessageEntity::class.java,
            )
            .setParameter("conversationIds", conversationIds)
            .resultList
            .groupBy { requireNotNull(it.conversationId) }
            .mapValues { (_, messages) -> messages.first() }
    }

    private fun usersById(userIds: Collection<UUID>): Map<UUID, UserEntity> {
        if (userIds.isEmpty()) {
            return emptyMap()
        }

        return entityManager
            .createQuery(
                """
                select u from UserEntity u
                where u.id in :userIds
                """.trimIndent(),
                UserEntity::class.java,
            )
            .setParameter("userIds", userIds)
            .resultList
            .associateBy { requireNotNull(it.id) }
    }

    private fun otherUserId(conversation: DirectConversationEntity, currentUserId: UUID): UUID {
        val participantAUserId = requireNotNull(conversation.participantAUserId)
        val participantBUserId = requireNotNull(conversation.participantBUserId)

        return when (currentUserId) {
            participantAUserId -> participantBUserId
            participantBUserId -> participantAUserId
            else -> throw IllegalArgumentException("User $currentUserId is not part of direct conversation ${conversation.id}.")
        }
    }

    private fun requireActiveUser(userId: UUID): UserEntity {
        val user = requireNotNull(entityManager.find(UserEntity::class.java, userId)) {
            "User $userId was not found."
        }
        require(user.disabledAt == null) { "User $userId is disabled." }
        return user
    }

    private fun requireNotBlockedRelationship(currentUserId: UUID, otherUserId: UUID) {
        require(contactService.relationshipStatus(currentUserId, otherUserId) != ContactStatus.BLOCKED) {
            "Direct messages are blocked for user $otherUserId."
        }
    }

    private fun DirectConversationEntity.toView(
        currentUserId: UUID,
        usersById: Map<UUID, UserEntity>,
        lastMessagesByConversationId: Map<UUID, DirectMessageEntity>,
    ): DirectConversationView {
        val otherUserId = otherUserId(this, currentUserId)
        val otherUser = requireNotNull(usersById[otherUserId]) {
            "User $otherUserId was not found."
        }

        return DirectConversationView(
            id = requireNotNull(id),
            user = otherUser.toAuthView(),
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastMessage = lastMessagesByConversationId[requireNotNull(id)]?.toView(),
        )
    }

    private fun DirectMessageEntity.toView(): DirectMessageView =
        DirectMessageView(
            id = requireNotNull(id),
            conversationId = requireNotNull(conversationId),
            authorUserId = requireNotNull(authorUserId),
            body = body,
            createdAt = createdAt,
            editedAt = editedAt,
        )

    private fun UserEntity.toAuthView(): AuthUserView =
        AuthUserView(
            id = requireNotNull(id),
            username = username,
            displayName = displayName,
            avatarMediaKey = avatarMediaKey,
        )

    private data class DirectConversationAccess(
        val conversation: DirectConversationEntity,
        val otherUserId: UUID,
    )
}

data class DirectConversationView(
    val id: UUID,
    val user: AuthUserView,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val lastMessage: DirectMessageView?,
)

data class DirectMessageView(
    val id: UUID,
    val conversationId: UUID,
    val authorUserId: UUID,
    val body: String,
    val createdAt: OffsetDateTime,
    val editedAt: OffsetDateTime?,
)
