package ru.voicestream.presence

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import ru.voicestream.auth.AuthUserView
import ru.voicestream.channel.ChannelCatalogService
import ru.voicestream.domain.ContactStatus
import ru.voicestream.domain.MediaSessionStatus
import ru.voicestream.events.EventHub
import ru.voicestream.events.EventPayloads
import ru.voicestream.persistence.entity.MediaSessionEntity
import ru.voicestream.persistence.entity.MediaSessionParticipantEntity
import ru.voicestream.persistence.entity.UserContactEntity
import ru.voicestream.persistence.entity.UserEntity
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class PresenceService(
    private val entityManager: EntityManager,
    private val channelCatalogService: ChannelCatalogService,
    private val eventHub: EventHub,
) {
    private val lock = Any()
    private val statesByUserId = linkedMapOf<UUID, MutablePresenceState>()

    fun myPresence(currentUserId: UUID): UserPresenceView =
        snapshot(currentUserId)

    @Transactional
    fun myContactPresences(currentUserId: UUID): List<ContactPresenceView> {
        val contacts = acceptedContactEntries(currentUserId)
        if (contacts.isEmpty()) {
            return emptyList()
        }

        val userIds = contacts.map { it.otherUserId }.toSet()
        val usersById = usersById(userIds)
        return contacts
            .mapNotNull { contact ->
                val user = usersById[contact.otherUserId] ?: return@mapNotNull null
                ContactPresenceView(
                    user = user.toAuthView(),
                    presence = snapshot(requireNotNull(user.id)),
                )
            }
            .sortedWith(compareBy<ContactPresenceView> { it.user.displayName.lowercase() }.thenBy { it.user.username.lowercase() })
    }

    @Transactional
    fun channelVoiceStates(channelId: UUID, currentUserId: UUID): List<ChannelVoiceStateView> {
        channelCatalogService.channelMembers(channelId, currentUserId)

        val activeSessions = entityManager
            .createQuery(
                """
                select s from MediaSessionEntity s
                where s.channelId = :channelId and s.status = :status
                order by s.startedAt desc
                """.trimIndent(),
                MediaSessionEntity::class.java,
            )
            .setParameter("channelId", channelId)
            .setParameter("status", MediaSessionStatus.ACTIVE)
            .resultList
        if (activeSessions.isEmpty()) {
            return emptyList()
        }

        val sessionIds = activeSessions.map { requireNotNull(it.id) }.toSet()
        val participants = entityManager
            .createQuery(
                """
                select p from MediaSessionParticipantEntity p
                where p.mediaSessionId in :sessionIds
                and p.leftAt is null
                order by p.joinedAt desc
                """.trimIndent(),
                MediaSessionParticipantEntity::class.java,
            )
            .setParameter("sessionIds", sessionIds)
            .resultList
        if (participants.isEmpty()) {
            return emptyList()
        }

        val usersById = usersById(participants.map { requireNotNull(it.userId) }.toSet())
        val sessionIdsByUserId = participants.groupBy { requireNotNull(it.userId) }
        return sessionIdsByUserId.entries
            .mapNotNull { (userId, userParticipants) ->
                val user = usersById[userId] ?: return@mapNotNull null
                val state = stateSnapshot(userId)
                val participant = selectParticipant(userParticipants, state)
                channelVoiceStateView(
                    user = user.toAuthView(),
                    channelId = channelId,
                    mediaSessionId = participant.mediaSessionId,
                    state = state,
                    active = true,
                    fallbackUpdatedAt = participant.joinedAt,
                )
            }
            .sortedWith(compareBy<ChannelVoiceStateView> { it.user.displayName.lowercase() }.thenBy { it.user.username.lowercase() })
    }

    fun onMediaSessionJoined(userId: UUID, mediaSessionId: UUID, channelId: UUID) {
        val now = OffsetDateTime.now()
        val transition = synchronized(lock) {
            val previous = stateSnapshot(userId)
            val state = mutableState(userId)
            state.activeVoiceChannelId = channelId
            state.activeMediaSessionId = mediaSessionId
            state.muted = false
            state.deafened = false
            state.screenSharing = false
            state.updatedAt = now
            PresenceTransition(previous, state.toView(userId, onlineStatusFor(userId)))
        }

        publishPresence(userId)
        publishVoiceTransition(
            userId = userId,
            previous = transition.previous,
            current = transition.current,
        )
    }

    fun onMediaSessionLeft(userId: UUID, mediaSessionId: UUID, channelId: UUID) {
        val now = OffsetDateTime.now()
        val transition = synchronized(lock) {
            val previous = stateSnapshot(userId)
            val state = mutableState(userId)
            if (state.activeMediaSessionId == mediaSessionId || state.activeVoiceChannelId == channelId) {
                state.activeVoiceChannelId = null
                state.activeMediaSessionId = null
                state.muted = false
                state.deafened = false
                state.screenSharing = false
            }
            state.updatedAt = now
            PresenceTransition(
                previous = previous ?: UserPresenceView(
                    userId = userId,
                    onlineStatus = onlineStatusFor(userId),
                    voiceChannelId = channelId,
                    mediaSessionId = mediaSessionId,
                    muted = false,
                    deafened = false,
                    screenSharing = false,
                    updatedAt = now,
                ),
                current = state.toView(userId, onlineStatusFor(userId)),
            )
        }

        publishPresence(userId)
        publishVoiceTransition(
            userId = userId,
            previous = transition.previous,
            current = transition.current,
        )
    }

    fun onMediaSessionEnded(mediaSessionId: UUID, channelId: UUID, userIds: Collection<UUID>) {
        userIds.toSet().forEach { userId ->
            onMediaSessionLeft(
                userId = userId,
                mediaSessionId = mediaSessionId,
                channelId = channelId,
            )
        }
    }

    @Transactional
    fun updateMyVoiceState(input: UpdateMyVoiceStateRequest): ChannelVoiceStateView {
        val session = requireNotNull(entityManager.find(MediaSessionEntity::class.java, input.mediaSessionId)) {
            "Media session ${input.mediaSessionId} was not found."
        }
        require(session.status == MediaSessionStatus.ACTIVE) { "Media session ${input.mediaSessionId} is not active." }

        val participant = entityManager
            .createQuery(
                """
                select p from MediaSessionParticipantEntity p
                where p.mediaSessionId = :mediaSessionId
                and p.userId = :userId
                and p.leftAt is null
                """.trimIndent(),
                MediaSessionParticipantEntity::class.java,
            )
            .setParameter("mediaSessionId", input.mediaSessionId)
            .setParameter("userId", input.userId)
            .setMaxResults(1)
            .resultList
            .firstOrNull()
        requireNotNull(participant) {
            "Current user is not an active participant in media session ${input.mediaSessionId}."
        }
        require(!input.screenSharing || participant.canPublishScreen) {
            "Current user cannot mark screen sharing active in media session ${input.mediaSessionId}."
        }

        val user = requireNotNull(entityManager.find(UserEntity::class.java, input.userId)) {
            "User ${input.userId} was not found."
        }
        val channelId = requireNotNull(session.channelId)
        val now = OffsetDateTime.now()
        val transition = synchronized(lock) {
            val previous = stateSnapshot(input.userId)
            val state = mutableState(input.userId)
            state.activeVoiceChannelId = channelId
            state.activeMediaSessionId = input.mediaSessionId
            state.muted = input.muted
            state.deafened = input.deafened
            state.screenSharing = input.screenSharing
            state.updatedAt = now
            PresenceTransition(previous, state.toView(input.userId, onlineStatusFor(input.userId)))
        }

        publishPresence(input.userId)
        publishVoiceTransition(
            userId = input.userId,
            previous = transition.previous,
            current = transition.current,
        )

        return channelVoiceStateView(
            user = user.toAuthView(),
            channelId = channelId,
            mediaSessionId = input.mediaSessionId,
            state = transition.current,
            active = true,
            fallbackUpdatedAt = now,
        )
    }

    private fun publishPresence(userId: UUID, includeSelf: Boolean = true) {
        val presence = snapshot(userId)
        val recipients = acceptedContactUserIds(userId).toMutableSet().apply {
            if (includeSelf) {
                add(userId)
            }
        }
        if (recipients.isEmpty()) {
            return
        }
        eventHub.publishToUsers(
            userIds = recipients,
            message = EventPayloads.userPresenceUpdated(presence).toString(),
        )
    }

    private fun publishVoiceTransition(
        userId: UUID,
        previous: UserPresenceView?,
        current: UserPresenceView,
    ) {
        val user = requireActiveUser(userId).toAuthView()

        if (previous?.voiceChannelId != null && previous.voiceChannelId != current.voiceChannelId) {
            publishChannelVoiceState(
                channelId = previous.voiceChannelId,
                userId = userId,
                view = channelVoiceStateView(
                    user = user,
                    channelId = previous.voiceChannelId,
                    mediaSessionId = previous.mediaSessionId,
                    state = previous,
                    active = false,
                    fallbackUpdatedAt = previous.updatedAt,
                ),
            )
        }

        if (current.voiceChannelId != null) {
            publishChannelVoiceState(
                channelId = current.voiceChannelId,
                userId = userId,
                view = channelVoiceStateView(
                    user = user,
                    channelId = current.voiceChannelId,
                    mediaSessionId = current.mediaSessionId,
                    state = current,
                    active = true,
                    fallbackUpdatedAt = current.updatedAt,
                ),
            )
        }
    }

    private fun publishChannelVoiceState(channelId: UUID, userId: UUID, view: ChannelVoiceStateView) {
        val recipients = channelCatalogService.visibleChannelUserIds(channelId).toMutableSet().apply { add(userId) }
        eventHub.publishToUsers(
            userIds = recipients,
            message = EventPayloads.channelVoiceStateUpdated(view).toString(),
        )
    }

    private fun snapshot(userId: UUID): UserPresenceView =
        synchronized(lock) {
            statesByUserId[userId]?.toView(userId, onlineStatusFor(userId))
                ?: UserPresenceView(
                    userId = userId,
                    onlineStatus = onlineStatusFor(userId),
                    voiceChannelId = null,
                    mediaSessionId = null,
                    muted = false,
                    deafened = false,
                    screenSharing = false,
                    updatedAt = null,
                )
        }

    private fun stateSnapshot(userId: UUID): UserPresenceView? =
        synchronized(lock) { statesByUserId[userId]?.toView(userId, onlineStatusFor(userId)) }

    private fun mutableState(userId: UUID): MutablePresenceState =
        statesByUserId.getOrPut(userId) { MutablePresenceState() }

    private fun onlineStatusFor(userId: UUID): UserOnlineStatus =
        if (eventHub.isConnected(userId)) {
            UserOnlineStatus.ONLINE
        } else {
            UserOnlineStatus.OFFLINE
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

    private fun acceptedContactEntries(currentUserId: UUID): List<AcceptedContactEntry> =
        entityManager
            .createQuery(
                """
                select c from UserContactEntity c
                where c.status = :status
                and (c.requesterUserId = :currentUserId or c.addresseeUserId = :currentUserId)
                """.trimIndent(),
                UserContactEntity::class.java,
            )
            .setParameter("status", ContactStatus.ACCEPTED)
            .setParameter("currentUserId", currentUserId)
            .resultList
            .map { contact ->
                AcceptedContactEntry(
                    otherUserId = otherUserId(contact, currentUserId),
                )
            }

    private fun acceptedContactUserIds(currentUserId: UUID): Set<UUID> =
        acceptedContactEntries(currentUserId).map { it.otherUserId }.toSet()

    private fun otherUserId(contact: UserContactEntity, currentUserId: UUID): UUID {
        val requesterUserId = requireNotNull(contact.requesterUserId)
        val addresseeUserId = requireNotNull(contact.addresseeUserId)
        return when (currentUserId) {
            requesterUserId -> addresseeUserId
            addresseeUserId -> requesterUserId
            else -> throw IllegalArgumentException("User $currentUserId is not part of contact ${contact.id}.")
        }
    }

    private fun requireActiveUser(userId: UUID): UserEntity =
        requireNotNull(entityManager.find(UserEntity::class.java, userId)) {
            "User $userId was not found."
        }.also { user ->
            require(user.disabledAt == null) { "User $userId is disabled." }
        }

    private fun selectParticipant(
        participants: List<MediaSessionParticipantEntity>,
        state: UserPresenceView?,
    ): MediaSessionParticipantEntity =
        participants.firstOrNull { requireNotNull(it.mediaSessionId) == state?.mediaSessionId }
            ?: participants.maxByOrNull { it.joinedAt }
            ?: participants.first()

    private fun channelVoiceStateView(
        user: AuthUserView,
        channelId: UUID,
        mediaSessionId: UUID?,
        state: UserPresenceView?,
        active: Boolean,
        fallbackUpdatedAt: OffsetDateTime?,
    ): ChannelVoiceStateView =
        ChannelVoiceStateView(
            user = user,
            channelId = channelId,
            mediaSessionId = mediaSessionId,
            active = active,
            muted = state?.muted ?: false,
            deafened = state?.deafened ?: false,
            screenSharing = state?.screenSharing ?: false,
            onlineStatus = state?.onlineStatus ?: UserOnlineStatus.OFFLINE,
            updatedAt = state?.updatedAt ?: fallbackUpdatedAt,
        )

    private fun UserEntity.toAuthView(): AuthUserView =
        AuthUserView(
            id = requireNotNull(id),
            username = username,
            displayName = displayName,
            avatarMediaKey = avatarMediaKey,
        )
}

data class UpdateMyVoiceStateRequest(
    val userId: UUID,
    val mediaSessionId: UUID,
    val muted: Boolean,
    val deafened: Boolean,
    val screenSharing: Boolean,
)

enum class UserOnlineStatus {
    ONLINE,
    OFFLINE,
}

data class UserPresenceView(
    val userId: UUID,
    val onlineStatus: UserOnlineStatus,
    val voiceChannelId: UUID?,
    val mediaSessionId: UUID?,
    val muted: Boolean,
    val deafened: Boolean,
    val screenSharing: Boolean,
    val updatedAt: OffsetDateTime?,
)

data class ContactPresenceView(
    val user: AuthUserView,
    val presence: UserPresenceView,
)

data class ChannelVoiceStateView(
    val user: AuthUserView,
    val channelId: UUID,
    val mediaSessionId: UUID?,
    val active: Boolean,
    val muted: Boolean,
    val deafened: Boolean,
    val screenSharing: Boolean,
    val onlineStatus: UserOnlineStatus,
    val updatedAt: OffsetDateTime?,
)

private data class AcceptedContactEntry(
    val otherUserId: UUID,
)

private data class PresenceTransition(
    val previous: UserPresenceView?,
    val current: UserPresenceView,
)

private data class MutablePresenceState(
    var activeVoiceChannelId: UUID? = null,
    var activeMediaSessionId: UUID? = null,
    var muted: Boolean = false,
    var deafened: Boolean = false,
    var screenSharing: Boolean = false,
    var updatedAt: OffsetDateTime? = null,
) {
    fun toView(userId: UUID, onlineStatus: UserOnlineStatus): UserPresenceView =
        UserPresenceView(
            userId = userId,
            onlineStatus = onlineStatus,
            voiceChannelId = activeVoiceChannelId,
            mediaSessionId = activeMediaSessionId,
            muted = muted,
            deafened = deafened,
            screenSharing = screenSharing,
            updatedAt = updatedAt,
        )
}
