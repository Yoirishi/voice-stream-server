package ru.voicestream.media

import jakarta.enterprise.context.ApplicationScoped
import jakarta.json.Json
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import ru.voicestream.channel.ChannelCatalogService
import ru.voicestream.domain.ChannelType
import ru.voicestream.domain.MediaSessionStatus
import ru.voicestream.domain.MediaSessionType
import ru.voicestream.events.EventHub
import ru.voicestream.events.EventPayloads
import ru.voicestream.persistence.entity.ChannelEntity
import ru.voicestream.persistence.entity.MediaSessionEntity
import ru.voicestream.persistence.entity.MediaSessionParticipantEntity
import ru.voicestream.persistence.entity.UserEntity
import ru.voicestream.signaling.SignalingHub
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class MediaSessionService(
    private val entityManager: EntityManager,
    private val mediaTokenService: MediaTokenService,
    private val liveKitTokenService: LiveKitTokenService,
    private val channelCatalogService: ChannelCatalogService,
    private val eventHub: EventHub,
    private val signalingHub: SignalingHub,
    @param:ConfigProperty(name = "voice-stream.media.sfu-provider")
    private val sfuProvider: String,
    @param:ConfigProperty(name = "voice-stream.media.signaling-url")
    private val signalingUrl: String,
) {
    @Transactional
    fun startSession(input: StartMediaSessionRequest): MediaJoinTicket {
        val channel = requireVoiceChannel(input.channelId)
        val user = requireActiveUser(input.userId)

        val existingSession = activeSession(channel, input.type)
        val session = existingSession ?: createSession(channel, input)
        joinParticipant(
            mediaSessionId = requireNotNull(session.id),
            userId = input.userId,
            canPublishAudio = input.canPublishAudio,
            canPublishScreen = input.canPublishScreen,
            canSubscribe = true,
        )

        if (existingSession == null) {
            val sessionView = session.toView()
            eventHub.publishToUsers(
                userIds = channelCatalogService.visibleChannelUserIds(requireNotNull(channel.id)),
                message = EventPayloads.mediaSessionStarted(sessionView).toString(),
            )
        }

        return issueTicket(
            session = session,
            user = user,
            canPublishAudio = input.canPublishAudio,
            canPublishScreen = input.canPublishScreen,
            canSubscribe = true,
        )
    }

    @Transactional
    fun joinSession(input: JoinMediaSessionRequest): MediaJoinTicket {
        val session = requireNotNull(entityManager.find(MediaSessionEntity::class.java, input.mediaSessionId)) {
            "Media session ${input.mediaSessionId} was not found."
        }
        require(session.status == MediaSessionStatus.ACTIVE) { "Media session ${input.mediaSessionId} is not active." }
        val user = requireActiveUser(input.userId)

        joinParticipant(
            mediaSessionId = requireNotNull(session.id),
            userId = input.userId,
            canPublishAudio = input.canPublishAudio,
            canPublishScreen = input.canPublishScreen,
            canSubscribe = input.canSubscribe,
        )

        return issueTicket(
            session = session,
            user = user,
            canPublishAudio = input.canPublishAudio,
            canPublishScreen = input.canPublishScreen,
            canSubscribe = input.canSubscribe,
        )
    }

    @Transactional
    fun leaveSession(mediaSessionId: UUID, userId: UUID): Boolean {
        val session = entityManager.find(MediaSessionEntity::class.java, mediaSessionId) ?: return false
        if (session.status != MediaSessionStatus.ACTIVE) {
            return false
        }

        val participant = activeParticipant(mediaSessionId, userId) ?: return false
        val now = OffsetDateTime.now()
        participant.leftAt = now
        session.updatedAt = now

        if (!hasActiveParticipants(mediaSessionId)) {
            endSessionInternal(session, now)
        }

        return true
    }

    @Transactional
    fun endSession(mediaSessionId: UUID, currentUserId: UUID): MediaSessionView {
        val session = requireNotNull(entityManager.find(MediaSessionEntity::class.java, mediaSessionId)) {
            "Media session $mediaSessionId was not found."
        }
        val channel = requireNotNull(entityManager.find(ChannelEntity::class.java, requireNotNull(session.channelId))) {
            "Channel ${session.channelId} was not found."
        }
        require(
            currentUserId == requireNotNull(session.createdByUserId) ||
                currentUserId == requireNotNull(channel.ownerUserId),
        ) {
            "Current user cannot end media session $mediaSessionId."
        }

        return endSessionInternal(session, OffsetDateTime.now())
    }

    fun activeSessions(channelId: UUID): List<MediaSessionView> =
        entityManager
            .createQuery(
                """
                select s from MediaSessionEntity s
                where s.channelId = :channelId and s.status = :status
                order by s.startedAt asc
                """.trimIndent(),
                MediaSessionEntity::class.java,
            )
            .setParameter("channelId", channelId)
            .setParameter("status", MediaSessionStatus.ACTIVE)
            .resultList
            .map { it.toView() }

    private fun requireVoiceChannel(channelId: UUID): ChannelEntity {
        val channel = requireNotNull(entityManager.find(ChannelEntity::class.java, channelId)) {
            "Channel $channelId was not found."
        }
        require(channel.type == ChannelType.VOICE) { "Media sessions can only be started in VOICE channels." }
        return channel
    }

    private fun activeSession(channel: ChannelEntity, type: MediaSessionType): MediaSessionEntity? =
        entityManager
            .createQuery(
                """
                select s from MediaSessionEntity s
                where s.channelId = :channelId and s.type = :type and s.status = :status
                order by s.startedAt desc
                """.trimIndent(),
                MediaSessionEntity::class.java,
            )
            .setParameter("channelId", channel.id)
            .setParameter("type", type)
            .setParameter("status", MediaSessionStatus.ACTIVE)
            .setMaxResults(1)
            .resultList
            .firstOrNull()

    private fun activeParticipant(mediaSessionId: UUID, userId: UUID): MediaSessionParticipantEntity? =
        entityManager
            .createQuery(
                """
                select p from MediaSessionParticipantEntity p
                where p.mediaSessionId = :mediaSessionId
                and p.userId = :userId
                and p.leftAt is null
                """.trimIndent(),
                MediaSessionParticipantEntity::class.java,
            )
            .setParameter("mediaSessionId", mediaSessionId)
            .setParameter("userId", userId)
            .setMaxResults(1)
            .resultList
            .firstOrNull()

    private fun hasActiveParticipants(mediaSessionId: UUID): Boolean =
        entityManager
            .createQuery(
                """
                select p from MediaSessionParticipantEntity p
                where p.mediaSessionId = :mediaSessionId
                and p.leftAt is null
                """.trimIndent(),
                MediaSessionParticipantEntity::class.java,
            )
            .setParameter("mediaSessionId", mediaSessionId)
            .setMaxResults(1)
            .resultList
            .isNotEmpty()

    private fun requireActiveUser(userId: UUID): UserEntity {
        val user = requireNotNull(entityManager.find(UserEntity::class.java, userId)) {
            "User $userId was not found."
        }
        require(user.disabledAt == null) { "User $userId is disabled." }
        return user
    }

    private fun createSession(channel: ChannelEntity, input: StartMediaSessionRequest): MediaSessionEntity {
        val now = OffsetDateTime.now()
        val sessionId = UUID.randomUUID()
        val session = MediaSessionEntity().apply {
            id = sessionId
            channelId = requireNotNull(channel.id)
            createdByUserId = input.userId
            type = input.type
            this.sfuProvider = this@MediaSessionService.sfuProvider
            sfuRoomName = "channel-${channel.id}-${input.type.name.lowercase()}-$sessionId"
            status = MediaSessionStatus.ACTIVE
            startedAt = now
            createdAt = now
            updatedAt = now
        }
        entityManager.persist(session)
        return session
    }

    private fun joinParticipant(
        mediaSessionId: UUID,
        userId: UUID,
        canPublishAudio: Boolean,
        canPublishScreen: Boolean,
        canSubscribe: Boolean,
    ) {
        val existing = entityManager
            .createQuery(
                """
                select p from MediaSessionParticipantEntity p
                where p.mediaSessionId = :mediaSessionId and p.userId = :userId
                """.trimIndent(),
                MediaSessionParticipantEntity::class.java,
            )
            .setParameter("mediaSessionId", mediaSessionId)
            .setParameter("userId", userId)
            .resultList
            .firstOrNull()

        val participant = existing ?: MediaSessionParticipantEntity().apply {
            id = UUID.randomUUID()
            this.mediaSessionId = mediaSessionId
            this.userId = userId
            joinedAt = OffsetDateTime.now()
            entityManager.persist(this)
        }

        participant.canPublishAudio = canPublishAudio
        participant.canPublishScreen = canPublishScreen
        participant.canSubscribe = canSubscribe
        participant.leftAt = null
    }

    private fun endSessionInternal(session: MediaSessionEntity, endedAt: OffsetDateTime): MediaSessionView {
        if (session.status == MediaSessionStatus.ENDED) {
            return session.toView()
        }

        val mediaSessionId = requireNotNull(session.id)
        entityManager
            .createQuery(
                """
                select p from MediaSessionParticipantEntity p
                where p.mediaSessionId = :mediaSessionId
                and p.leftAt is null
                """.trimIndent(),
                MediaSessionParticipantEntity::class.java,
            )
            .setParameter("mediaSessionId", mediaSessionId)
            .resultList
            .forEach { it.leftAt = endedAt }

        session.status = MediaSessionStatus.ENDED
        session.endedAt = endedAt
        session.updatedAt = endedAt

        val sessionView = session.toView()
        eventHub.publishToUsers(
            userIds = channelCatalogService.visibleChannelUserIds(requireNotNull(session.channelId)),
            message = EventPayloads.mediaSessionEnded(sessionView).toString(),
        )
        signalingHub.closeMediaSession(mediaSessionId, "Media session has ended.")
        return sessionView
    }

    private fun issueTicket(
        session: MediaSessionEntity,
        user: UserEntity,
        canPublishAudio: Boolean,
        canPublishScreen: Boolean,
        canSubscribe: Boolean,
    ): MediaJoinTicket {
        val mediaSessionId = requireNotNull(session.id)
        val channelId = requireNotNull(session.channelId)
        val legacyToken = mediaTokenService.issue(
            MediaTokenInput(
                mediaSessionId = mediaSessionId,
                channelId = channelId,
                userId = requireNotNull(user.id),
                canPublishAudio = canPublishAudio,
                canPublishScreen = canPublishScreen,
                canSubscribe = canSubscribe,
            ),
        )
        val participantToken = liveKitTokenService.issue(
            LiveKitTokenInput(
                identity = requireNotNull(user.id).toString(),
                name = user.displayName,
                roomName = session.sfuRoomName,
                metadata = Json.createObjectBuilder()
                    .add("mediaSessionId", mediaSessionId.toString())
                    .add("channelId", channelId.toString())
                    .add("mediaSessionType", session.type.name)
                    .build(),
                canPublishAudio = canPublishAudio,
                canPublishScreen = canPublishScreen,
                canSubscribe = canSubscribe,
            ),
        )

        return MediaJoinTicket(
            mediaSessionId = mediaSessionId,
            channelId = channelId,
            userId = requireNotNull(user.id),
            sfuProvider = session.sfuProvider,
            sfuUrl = participantToken.serverUrl,
            signalingUrl = "$signalingUrl/$mediaSessionId?token=${legacyToken.token}",
            roomName = session.sfuRoomName,
            token = legacyToken.token,
            participantToken = participantToken.token,
            serverUrl = participantToken.serverUrl,
            expiresAt = participantToken.expiresAt,
            canPublishAudio = canPublishAudio,
            canPublishScreen = canPublishScreen,
            canSubscribe = canSubscribe,
        )
    }

    private fun MediaSessionEntity.toView(): MediaSessionView =
        MediaSessionView(
            id = requireNotNull(id),
            channelId = requireNotNull(channelId),
            type = type,
            sfuProvider = sfuProvider,
            roomName = sfuRoomName,
            status = status,
            startedAt = startedAt,
        )
}

data class StartMediaSessionRequest(
    val channelId: UUID,
    val userId: UUID,
    val type: MediaSessionType,
    val canPublishAudio: Boolean,
    val canPublishScreen: Boolean,
)

data class JoinMediaSessionRequest(
    val mediaSessionId: UUID,
    val userId: UUID,
    val canPublishAudio: Boolean,
    val canPublishScreen: Boolean,
    val canSubscribe: Boolean,
)

data class MediaJoinTicket(
    val mediaSessionId: UUID,
    val channelId: UUID,
    val userId: UUID,
    val sfuProvider: String,
    val sfuUrl: String,
    val signalingUrl: String,
    val roomName: String,
    val token: String,
    val participantToken: String,
    val serverUrl: String,
    val expiresAt: OffsetDateTime,
    val canPublishAudio: Boolean,
    val canPublishScreen: Boolean,
    val canSubscribe: Boolean,
)

data class MediaSessionView(
    val id: UUID,
    val channelId: UUID,
    val type: MediaSessionType,
    val sfuProvider: String,
    val roomName: String,
    val status: MediaSessionStatus,
    val startedAt: OffsetDateTime,
)
