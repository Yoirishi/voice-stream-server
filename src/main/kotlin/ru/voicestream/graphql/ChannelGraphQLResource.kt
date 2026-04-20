package ru.voicestream.graphql

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.graphql.Description
import org.eclipse.microprofile.graphql.GraphQLApi
import org.eclipse.microprofile.graphql.Input
import org.eclipse.microprofile.graphql.Mutation
import org.eclipse.microprofile.graphql.Name
import org.eclipse.microprofile.graphql.Query
import ru.voicestream.auth.AuthContext
import ru.voicestream.channel.ChannelCatalogService
import ru.voicestream.channel.ChannelDirectory
import ru.voicestream.channel.ChannelMessageView
import ru.voicestream.channel.MyChannelDirectory
import ru.voicestream.channel.RoleView
import ru.voicestream.domain.MediaSessionType
import ru.voicestream.media.JoinMediaSessionRequest
import ru.voicestream.media.MediaJoinTicket
import ru.voicestream.media.MediaSessionService
import ru.voicestream.media.MediaSessionView
import ru.voicestream.media.StartMediaSessionRequest
import java.util.UUID

@GraphQLApi
@ApplicationScoped
class ChannelGraphQLResource(
    private val channelCatalogService: ChannelCatalogService,
    private val mediaSessionService: MediaSessionService,
    private val authContext: AuthContext,
) {
    @Query("channels")
    @Description("Returns the basic channel tree visible to the current caller.")
    fun channels(): ChannelDirectory {
        val userId = authContext.requireUserId()
        return channelCatalogService.visibleChannelDirectory(userId)
    }

    @Query("myChannels")
    @Description("Returns the current caller's channel tree with role and computed permissions.")
    fun myChannels(): MyChannelDirectory {
        val userId = authContext.requireUserId()
        return channelCatalogService.myChannelDirectory(userId)
    }

    @Query("channelRoles")
    @Description("Returns role basics for a channel visible to the current caller.")
    fun channelRoles(@Name("channelId") channelId: UUID): List<RoleView> {
        authContext.requireUserId()
        return channelCatalogService.roles(channelId)
    }

    @Query("channelMessages")
    @Description("Returns recent text messages from a channel visible to the current caller.")
    fun channelMessages(
        @Name("channelId") channelId: UUID,
        @Name("limit") limit: Int?,
    ): List<ChannelMessageView> {
        authContext.requireUserId()
        return channelCatalogService.messages(channelId, limit ?: 50)
    }

    @Query("activeMediaSessions")
    @Description("Returns active voice or screen-share sessions for a channel.")
    fun activeMediaSessions(@Name("channelId") channelId: UUID): List<MediaSessionView> {
        authContext.requireUserId()
        return mediaSessionService.activeSessions(channelId)
    }

    @Mutation("sendChannelMessage")
    @Description("Stores a text message in a TEXT channel.")
    fun sendChannelMessage(input: SendChannelMessageInput): ChannelMessageView =
        channelCatalogService.sendMessage(
            channelId = input.channelId,
            authorUserId = authContext.requireUserId(),
            body = input.body,
        )

    @Mutation("startMediaSession")
    @Description("Starts or reuses a media session and returns an SFU/signaling join ticket.")
    fun startMediaSession(input: StartMediaSessionInput): MediaJoinTicket =
        mediaSessionService.startSession(
            StartMediaSessionRequest(
                channelId = input.channelId,
                userId = authContext.requireUserId(),
                type = input.type,
                canPublishAudio = input.canPublishAudio,
                canPublishScreen = input.canPublishScreen,
            ),
        )

    @Mutation("joinMediaSession")
    @Description("Joins an active media session and returns an SFU/signaling join ticket.")
    fun joinMediaSession(input: JoinMediaSessionInput): MediaJoinTicket =
        mediaSessionService.joinSession(
            JoinMediaSessionRequest(
                mediaSessionId = input.mediaSessionId,
                userId = authContext.requireUserId(),
                canPublishAudio = input.canPublishAudio,
                canPublishScreen = input.canPublishScreen,
                canSubscribe = input.canSubscribe,
            ),
        )
}

@Input
class SendChannelMessageInput {
    lateinit var channelId: UUID
    lateinit var body: String
}

@Input
class StartMediaSessionInput {
    lateinit var channelId: UUID
    var type: MediaSessionType = MediaSessionType.VOICE
    var canPublishAudio: Boolean = true
    var canPublishScreen: Boolean = false
}

@Input
class JoinMediaSessionInput {
    lateinit var mediaSessionId: UUID
    var canPublishAudio: Boolean = false
    var canPublishScreen: Boolean = false
    var canSubscribe: Boolean = true
}
