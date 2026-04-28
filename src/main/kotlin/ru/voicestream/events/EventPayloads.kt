package ru.voicestream.events

import ru.voicestream.auth.AuthUserView
import ru.voicestream.channel.ChannelMessageView
import ru.voicestream.contact.ContactView
import ru.voicestream.direct.DirectMessageView
import ru.voicestream.media.MediaSessionView
import ru.voicestream.presence.ChannelVoiceStateView
import ru.voicestream.presence.UserPresenceView
import jakarta.json.Json
import jakarta.json.JsonObject
import jakarta.json.JsonObjectBuilder

object EventPayloads {
    fun channelMessageCreated(message: ChannelMessageView): JsonObject =
        Json.createObjectBuilder()
            .add("type", "channelMessageCreated")
            .add("channelId", message.channelId.toString())
            .add("message", channelMessage(message))
            .build()

    fun directMessageCreated(message: DirectMessageView): JsonObject =
        Json.createObjectBuilder()
            .add("type", "directMessageCreated")
            .add("conversationId", message.conversationId.toString())
            .add("message", directMessage(message))
            .build()

    fun contactRequestReceived(contact: ContactView): JsonObject =
        Json.createObjectBuilder()
            .add("type", "contactRequestReceived")
            .add("contact", contact(contact))
            .build()

    fun mediaSessionStarted(mediaSession: MediaSessionView): JsonObject =
        Json.createObjectBuilder()
            .add("type", "mediaSessionStarted")
            .add("channelId", mediaSession.channelId.toString())
            .add("mediaSession", mediaSession(mediaSession))
            .build()

    fun mediaSessionEnded(mediaSession: MediaSessionView): JsonObject =
        Json.createObjectBuilder()
            .add("type", "mediaSessionEnded")
            .add("channelId", mediaSession.channelId.toString())
            .add("mediaSession", mediaSession(mediaSession))
            .build()

    fun userPresenceUpdated(presence: UserPresenceView): JsonObject =
        Json.createObjectBuilder()
            .add("type", "userPresenceUpdated")
            .add("presence", userPresence(presence))
            .build()

    fun channelVoiceStateUpdated(voiceState: ChannelVoiceStateView): JsonObject =
        Json.createObjectBuilder()
            .add("type", "channelVoiceStateUpdated")
            .add("channelId", voiceState.channelId.toString())
            .add("voiceState", channelVoiceState(voiceState))
            .build()

    private fun channelMessage(message: ChannelMessageView): JsonObject =
        Json.createObjectBuilder()
            .add("id", message.id.toString())
            .add("channelId", message.channelId.toString())
            .add("authorUserId", message.authorUserId.toString())
            .add("body", message.body)
            .add("createdAt", message.createdAt.toString())
            .addNullable("editedAt", message.editedAt?.toString())
            .build()

    private fun directMessage(message: DirectMessageView): JsonObject =
        Json.createObjectBuilder()
            .add("id", message.id.toString())
            .add("conversationId", message.conversationId.toString())
            .add("authorUserId", message.authorUserId.toString())
            .add("body", message.body)
            .add("createdAt", message.createdAt.toString())
            .addNullable("editedAt", message.editedAt?.toString())
            .build()

    private fun contact(contact: ContactView): JsonObject =
        Json.createObjectBuilder()
            .add("id", contact.id.toString())
            .add("status", contact.status.name)
            .add("user", authUser(contact.user))
            .add("createdAt", contact.createdAt.toString())
            .add("updatedAt", contact.updatedAt.toString())
            .addNullable("respondedAt", contact.respondedAt?.toString())
            .addNullable("blockedAt", contact.blockedAt?.toString())
            .build()

    private fun mediaSession(mediaSession: MediaSessionView): JsonObject =
        Json.createObjectBuilder()
            .add("id", mediaSession.id.toString())
            .add("channelId", mediaSession.channelId.toString())
            .add("type", mediaSession.type.name)
            .add("sfuProvider", mediaSession.sfuProvider)
            .add("roomName", mediaSession.roomName)
            .add("status", mediaSession.status.name)
            .add("startedAt", mediaSession.startedAt.toString())
            .build()

    private fun userPresence(presence: UserPresenceView): JsonObject =
        Json.createObjectBuilder()
            .add("userId", presence.userId.toString())
            .add("onlineStatus", presence.onlineStatus.name)
            .addNullable("voiceChannelId", presence.voiceChannelId?.toString())
            .addNullable("mediaSessionId", presence.mediaSessionId?.toString())
            .add("muted", presence.muted)
            .add("deafened", presence.deafened)
            .add("screenSharing", presence.screenSharing)
            .addNullable("updatedAt", presence.updatedAt?.toString())
            .build()

    private fun channelVoiceState(voiceState: ChannelVoiceStateView): JsonObject =
        Json.createObjectBuilder()
            .add("userId", voiceState.user.id.toString())
            .add("channelId", voiceState.channelId.toString())
            .addNullable("mediaSessionId", voiceState.mediaSessionId?.toString())
            .add("active", voiceState.active)
            .add("muted", voiceState.muted)
            .add("deafened", voiceState.deafened)
            .add("screenSharing", voiceState.screenSharing)
            .add("onlineStatus", voiceState.onlineStatus.name)
            .addNullable("updatedAt", voiceState.updatedAt?.toString())
            .build()

    private fun authUser(user: AuthUserView): JsonObject =
        Json.createObjectBuilder()
            .add("id", user.id.toString())
            .add("username", user.username)
            .add("displayName", user.displayName)
            .addNullable("avatarMediaKey", user.avatarMediaKey)
            .build()

    private fun JsonObjectBuilder.addNullable(name: String, value: String?): JsonObjectBuilder =
        apply {
            if (value == null) {
                addNull(name)
            } else {
                add(name, value)
            }
        }
}
