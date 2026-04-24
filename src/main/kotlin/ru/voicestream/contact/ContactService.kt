package ru.voicestream.contact

import ru.voicestream.auth.AuthUserView
import ru.voicestream.events.EventHub
import ru.voicestream.events.EventPayloads
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import ru.voicestream.domain.ContactStatus
import ru.voicestream.persistence.entity.UserContactEntity
import ru.voicestream.persistence.entity.UserEntity
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class ContactService(
    private val entityManager: EntityManager,
    private val eventHub: EventHub,
) {
    fun relationshipStatus(firstUserId: UUID, secondUserId: UUID): ContactStatus? =
        findContactBetweenUsers(firstUserId, secondUserId)?.status

    fun myContacts(currentUserId: UUID): List<ContactView> =
        contactViews(
            contacts(
                """
                select c from UserContactEntity c
                where c.status = :status
                and (c.requesterUserId = :currentUserId or c.addresseeUserId = :currentUserId)
                """.trimIndent(),
            ) {
                setParameter("status", ContactStatus.ACCEPTED)
                setParameter("currentUserId", currentUserId)
            },
            currentUserId,
        ).sortedWith(compareBy<ContactView> { it.user.displayName.lowercase() }.thenBy { it.user.username.lowercase() })

    fun incomingContactRequests(currentUserId: UUID): List<ContactView> =
        contactViews(
            contacts(
                """
                select c from UserContactEntity c
                where c.status = :status
                and c.addresseeUserId = :currentUserId
                order by c.createdAt desc, c.id desc
                """.trimIndent(),
            ) {
                setParameter("status", ContactStatus.PENDING)
                setParameter("currentUserId", currentUserId)
            },
            currentUserId,
        )

    fun outgoingContactRequests(currentUserId: UUID): List<ContactView> =
        contactViews(
            contacts(
                """
                select c from UserContactEntity c
                where c.status = :status
                and c.requesterUserId = :currentUserId
                order by c.createdAt desc, c.id desc
                """.trimIndent(),
            ) {
                setParameter("status", ContactStatus.PENDING)
                setParameter("currentUserId", currentUserId)
            },
            currentUserId,
        )

    fun findUsers(currentUserId: UUID, query: String): List<AuthUserView> {
        val normalizedQuery = query.trim()
        require(normalizedQuery.isNotEmpty()) { "Search query must not be blank." }

        val parsedUserId = normalizedQuery.toUUIDOrNull()
        return if (parsedUserId != null) {
            entityManager
                .createQuery(
                    """
                    select u from UserEntity u
                    where u.id = :userId
                    and u.disabledAt is null
                    """.trimIndent(),
                    UserEntity::class.java,
                )
                .setParameter("userId", parsedUserId)
                .resultList
                .asSequence()
                .filter { requireNotNull(it.id) != currentUserId }
                .map { it.toAuthView() }
                .toList()
        } else {
            entityManager
                .createQuery(
                    """
                    select u from UserEntity u
                    where u.disabledAt is null
                    and u.id <> :currentUserId
                    and lower(u.displayName) like :pattern
                    order by lower(u.displayName) asc, lower(u.username) asc
                    """.trimIndent(),
                    UserEntity::class.java,
                )
                .setParameter("currentUserId", currentUserId)
                .setParameter("pattern", "%${normalizedQuery.lowercase()}%")
                .setMaxResults(MAX_FIND_USERS_RESULTS)
                .resultList
                .map { it.toAuthView() }
        }
    }

    @Transactional
    fun sendContactRequest(userId: UUID, currentUserId: UUID): ContactView {
        require(currentUserId != userId) { "Cannot send a contact request to yourself." }
        val currentUser = requireActiveUser(currentUserId)
        val targetUser = requireActiveUser(userId)
        val contact = findContactBetweenUsers(currentUserId, userId)
        val now = OffsetDateTime.now()
        var shouldNotifyRecipient = false

        val result = when {
            contact == null -> UserContactEntity().apply {
                id = UUID.randomUUID()
                requesterUserId = currentUserId
                addresseeUserId = userId
                status = ContactStatus.PENDING
                createdAt = now
                updatedAt = now
                shouldNotifyRecipient = true
                entityManager.persist(this)
            }

            contact.status == ContactStatus.BLOCKED && contact.requesterUserId == currentUserId ->
                throw IllegalArgumentException("User $userId is blocked.")

            contact.status == ContactStatus.BLOCKED ->
                throw IllegalArgumentException("Current user is blocked by user $userId.")

            contact.status == ContactStatus.ACCEPTED ->
                contact

            contact.status == ContactStatus.PENDING && contact.requesterUserId == currentUserId ->
                contact

            contact.status == ContactStatus.PENDING ->
                throw IllegalArgumentException("Incoming contact request from user $userId already exists.")

            else -> {
                contact.requesterUserId = currentUserId
                contact.addresseeUserId = userId
                contact.status = ContactStatus.PENDING
                contact.respondedAt = null
                contact.blockedAt = null
                contact.updatedAt = now
                shouldNotifyRecipient = true
                contact
            }
        }

        val senderView = contactView(result, currentUserId, mapOf(userId to targetUser))
        if (shouldNotifyRecipient) {
            val recipientView = contactView(result, userId, mapOf(currentUserId to currentUser))
            eventHub.publishToUsers(
                userIds = setOf(userId),
                message = EventPayloads.contactRequestReceived(recipientView).toString(),
            )
        }

        return senderView
    }

    @Transactional
    fun acceptContactRequest(userId: UUID, currentUserId: UUID): ContactView {
        require(currentUserId != userId) { "Cannot accept your own contact request." }
        val targetUser = requireActiveUser(userId)
        val contact = requireIncomingPendingContact(userId, currentUserId)
        val now = OffsetDateTime.now()

        contact.status = ContactStatus.ACCEPTED
        contact.respondedAt = now
        contact.blockedAt = null
        contact.updatedAt = now
        return contactView(contact, currentUserId, mapOf(userId to targetUser))
    }

    @Transactional
    fun declineContactRequest(userId: UUID, currentUserId: UUID): ContactView {
        require(currentUserId != userId) { "Cannot decline your own contact request." }
        val targetUser = requireActiveUser(userId)
        val contact = requireIncomingPendingContact(userId, currentUserId)
        val now = OffsetDateTime.now()

        contact.status = ContactStatus.DECLINED
        contact.respondedAt = now
        contact.blockedAt = null
        contact.updatedAt = now
        return contactView(contact, currentUserId, mapOf(userId to targetUser))
    }

    @Transactional
    fun removeContact(userId: UUID, currentUserId: UUID): Boolean {
        require(currentUserId != userId) { "Cannot remove yourself from contacts." }
        val contact = findContactBetweenUsers(currentUserId, userId) ?: return false
        if (contact.status == ContactStatus.BLOCKED) {
            return false
        }

        entityManager.remove(contact)
        return true
    }

    @Transactional
    fun blockUser(userId: UUID, currentUserId: UUID): ContactView {
        require(currentUserId != userId) { "Cannot block yourself." }
        val targetUser = requireActiveUser(userId)
        val contact = findContactBetweenUsers(currentUserId, userId)
        val now = OffsetDateTime.now()

        val result = when {
            contact == null -> UserContactEntity().apply {
                id = UUID.randomUUID()
                requesterUserId = currentUserId
                addresseeUserId = userId
                status = ContactStatus.BLOCKED
                createdAt = now
                updatedAt = now
                blockedAt = now
                respondedAt = now
                entityManager.persist(this)
            }

            contact.status == ContactStatus.BLOCKED ->
                contact

            else -> {
                contact.requesterUserId = currentUserId
                contact.addresseeUserId = userId
                contact.status = ContactStatus.BLOCKED
                contact.respondedAt = now
                contact.blockedAt = now
                contact.updatedAt = now
                contact
            }
        }

        return contactView(result, currentUserId, mapOf(userId to targetUser))
    }

    private fun requireIncomingPendingContact(requesterUserId: UUID, currentUserId: UUID): UserContactEntity =
        requireNotNull(findContactBetweenUsers(requesterUserId, currentUserId)) {
            "Incoming contact request from user $requesterUserId was not found."
        }.also { contact ->
            require(contact.status == ContactStatus.PENDING) {
                "Incoming contact request from user $requesterUserId was not found."
            }
            require(contact.requesterUserId == requesterUserId && contact.addresseeUserId == currentUserId) {
                "Incoming contact request from user $requesterUserId was not found."
            }
        }

    private fun contactViews(contacts: List<UserContactEntity>, currentUserId: UUID): List<ContactView> {
        if (contacts.isEmpty()) {
            return emptyList()
        }

        val userIds = contacts
            .flatMap { listOf(requireNotNull(it.requesterUserId), requireNotNull(it.addresseeUserId)) }
            .filter { it != currentUserId }
            .toSet()
        val usersById = usersById(userIds)

        return contacts.map { contact ->
            contactView(contact, currentUserId, usersById)
        }
    }

    private fun contactView(
        contact: UserContactEntity,
        currentUserId: UUID,
        usersById: Map<UUID, UserEntity>,
    ): ContactView {
        val otherUserId = otherUserId(contact, currentUserId)
        val otherUser = requireNotNull(usersById[otherUserId]) {
            "User $otherUserId was not found."
        }

        return ContactView(
            id = requireNotNull(contact.id),
            user = otherUser.toAuthView(),
            status = contact.status,
            createdAt = contact.createdAt,
            updatedAt = contact.updatedAt,
            respondedAt = contact.respondedAt,
            blockedAt = contact.blockedAt,
        )
    }

    private fun otherUserId(contact: UserContactEntity, currentUserId: UUID): UUID {
        val requesterUserId = requireNotNull(contact.requesterUserId)
        val addresseeUserId = requireNotNull(contact.addresseeUserId)
        return when (currentUserId) {
            requesterUserId -> addresseeUserId
            addresseeUserId -> requesterUserId
            else -> throw IllegalArgumentException("User $currentUserId is not part of contact ${contact.id}.")
        }
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
                and u.disabledAt is null
                """.trimIndent(),
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

    private fun findContactBetweenUsers(firstUserId: UUID, secondUserId: UUID): UserContactEntity? =
        entityManager
            .createQuery(
                """
                select c from UserContactEntity c
                where (c.requesterUserId = :firstUserId and c.addresseeUserId = :secondUserId)
                   or (c.requesterUserId = :secondUserId and c.addresseeUserId = :firstUserId)
                """.trimIndent(),
                UserContactEntity::class.java,
            )
            .setParameter("firstUserId", firstUserId)
            .setParameter("secondUserId", secondUserId)
            .resultList
            .firstOrNull()

    private fun contacts(
        query: String,
        bind: jakarta.persistence.TypedQuery<UserContactEntity>.() -> Unit,
    ): List<UserContactEntity> =
        entityManager
            .createQuery(query, UserContactEntity::class.java)
            .apply(bind)
            .resultList

    private fun UserEntity.toAuthView(): AuthUserView =
        AuthUserView(
            id = requireNotNull(id),
            username = username,
            displayName = displayName,
            avatarMediaKey = avatarMediaKey,
        )

    private fun String.toUUIDOrNull(): UUID? =
        try {
            UUID.fromString(this)
        } catch (_: IllegalArgumentException) {
            null
        }

    companion object {
        private const val MAX_FIND_USERS_RESULTS = 20
    }
}

data class ContactView(
    val id: UUID,
    val user: AuthUserView,
    val status: ContactStatus,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val respondedAt: OffsetDateTime?,
    val blockedAt: OffsetDateTime?,
)
