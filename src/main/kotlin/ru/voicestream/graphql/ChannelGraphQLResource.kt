package ru.voicestream.graphql

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.graphql.Description
import org.eclipse.microprofile.graphql.GraphQLApi
import org.eclipse.microprofile.graphql.Name
import org.eclipse.microprofile.graphql.Query
import java.util.UUID

@GraphQLApi
@ApplicationScoped
class ChannelGraphQLResource {
    @Query("channels")
    @Description("Returns the channel tree visible to the current caller.")
    fun channels(): ChannelDirectoryPayload =
        ChannelDirectoryPayload(
            groups = emptyList(),
            ungroupedChannels = emptyList(),
        )

    @Query("channelRoles")
    @Description("Returns role basics for a channel visible to the current caller.")
    fun channelRoles(@Name("channelId") channelId: UUID): List<RoleSummary> =
        listOf(
            RoleSummary(channelId = channelId, code = "OWNER", name = "Owner", system = true),
            RoleSummary(channelId = channelId, code = "USER", name = "User", system = true),
        )
}

data class ChannelDirectoryPayload(
    val groups: List<ChannelGroupSummary>,
    val ungroupedChannels: List<ChannelSummary>,
)

data class ChannelGroupSummary(
    val id: UUID,
    val name: String,
    val type: ChannelType,
    val position: Int,
    val channels: List<ChannelSummary>,
)

data class ChannelSummary(
    val id: UUID,
    val name: String,
    val type: ChannelType,
    val topic: String?,
    val position: Int,
    val privateChannel: Boolean,
)

data class RoleSummary(
    val channelId: UUID,
    val code: String,
    val name: String,
    val system: Boolean,
)

enum class ChannelType {
    TEXT,
    VOICE,
}
