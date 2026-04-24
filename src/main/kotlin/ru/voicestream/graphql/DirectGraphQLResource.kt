package ru.voicestream.graphql

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.graphql.Description
import org.eclipse.microprofile.graphql.GraphQLApi
import org.eclipse.microprofile.graphql.Input
import org.eclipse.microprofile.graphql.Mutation
import org.eclipse.microprofile.graphql.Name
import org.eclipse.microprofile.graphql.Query
import ru.voicestream.auth.AuthContext
import ru.voicestream.direct.DirectConversationService
import ru.voicestream.direct.DirectConversationView
import ru.voicestream.direct.DirectMessageView
import java.util.UUID

@GraphQLApi
@ApplicationScoped
class DirectGraphQLResource(
    private val authContext: AuthContext,
    private val directConversationService: DirectConversationService,
) {
    @Query("myDirectConversations")
    @Description("Returns the current caller's direct conversations.")
    fun myDirectConversations(): List<DirectConversationView> =
        directConversationService.myDirectConversations(authContext.requireUserId())

    @Query("directMessages")
    @Description("Returns recent messages from a direct conversation visible to the current caller.")
    fun directMessages(
        @Name("conversationId") conversationId: UUID,
        @Name("limit") limit: Int?,
    ): List<DirectMessageView> =
        directConversationService.directMessages(
            conversationId = conversationId,
            limit = limit ?: 50,
            currentUserId = authContext.requireUserId(),
        )

    @Mutation("startDirectConversation")
    @Description("Starts or reuses a direct conversation with another user.")
    fun startDirectConversation(@Name("userId") userId: UUID): DirectConversationView =
        directConversationService.startDirectConversation(
            userId = userId,
            currentUserId = authContext.requireUserId(),
        )

    @Mutation("sendDirectMessage")
    @Description("Stores a text message in a direct conversation.")
    fun sendDirectMessage(input: SendDirectMessageInput): DirectMessageView =
        directConversationService.sendDirectMessage(
            conversationId = input.conversationId,
            currentUserId = authContext.requireUserId(),
            body = input.body,
        )
}

@Input
class SendDirectMessageInput {
    lateinit var conversationId: UUID
    lateinit var body: String
}
