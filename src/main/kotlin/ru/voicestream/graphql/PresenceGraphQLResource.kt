package ru.voicestream.graphql

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.graphql.Description
import org.eclipse.microprofile.graphql.GraphQLApi
import org.eclipse.microprofile.graphql.Input
import org.eclipse.microprofile.graphql.Mutation
import org.eclipse.microprofile.graphql.Name
import org.eclipse.microprofile.graphql.Query
import ru.voicestream.auth.AuthContext
import ru.voicestream.presence.ChannelVoiceStateView
import ru.voicestream.presence.ContactPresenceView
import ru.voicestream.presence.PresenceService
import ru.voicestream.presence.UpdateMyVoiceStateRequest
import ru.voicestream.presence.UserPresenceView
import java.util.UUID

@GraphQLApi
@ApplicationScoped
class PresenceGraphQLResource(
    private val authContext: AuthContext,
    private val presenceService: PresenceService,
) {
    @Query("myPresence")
    @Description("Returns the current caller's online and voice presence snapshot.")
    fun myPresence(): UserPresenceView =
        presenceService.myPresence(authContext.requireUserId())

    @Query("myContactPresences")
    @Description("Returns accepted contacts together with their current online and voice presence snapshot.")
    fun myContactPresences(): List<ContactPresenceView> =
        presenceService.myContactPresences(authContext.requireUserId())

    @Query("channelVoiceStates")
    @Description("Returns active voice-channel state rows for users currently present in a channel.")
    fun channelVoiceStates(@Name("channelId") channelId: UUID): List<ChannelVoiceStateView> =
        presenceService.channelVoiceStates(
            channelId = channelId,
            currentUserId = authContext.requireUserId(),
        )

    @Mutation("updateMyVoiceState")
    @Description("Updates the current caller's in-memory muted, deafened, and screen-sharing state for an active media session.")
    fun updateMyVoiceState(input: UpdateMyVoiceStateInput): ChannelVoiceStateView =
        presenceService.updateMyVoiceState(
            UpdateMyVoiceStateRequest(
                userId = authContext.requireUserId(),
                mediaSessionId = input.mediaSessionId,
                muted = input.muted,
                deafened = input.deafened,
                screenSharing = input.screenSharing,
            ),
        )
}

@Input
class UpdateMyVoiceStateInput {
    lateinit var mediaSessionId: UUID
    var muted: Boolean = false
    var deafened: Boolean = false
    var screenSharing: Boolean = false
}
