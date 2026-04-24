package ru.voicestream.graphql

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.graphql.Description
import org.eclipse.microprofile.graphql.GraphQLApi
import org.eclipse.microprofile.graphql.Mutation
import org.eclipse.microprofile.graphql.Name
import org.eclipse.microprofile.graphql.Query
import ru.voicestream.auth.AuthContext
import ru.voicestream.auth.AuthUserView
import ru.voicestream.contact.ContactService
import ru.voicestream.contact.ContactView
import java.util.UUID

@GraphQLApi
@ApplicationScoped
class ContactGraphQLResource(
    private val authContext: AuthContext,
    private val contactService: ContactService,
) {
    @Query("myContacts")
    @Description("Returns the current caller's accepted contacts.")
    fun myContacts(): List<ContactView> =
        contactService.myContacts(authContext.requireUserId())

    @Query("incomingContactRequests")
    @Description("Returns pending contact requests addressed to the current caller.")
    fun incomingContactRequests(): List<ContactView> =
        contactService.incomingContactRequests(authContext.requireUserId())

    @Query("outgoingContactRequests")
    @Description("Returns pending contact requests sent by the current caller.")
    fun outgoingContactRequests(): List<ContactView> =
        contactService.outgoingContactRequests(authContext.requireUserId())

    @Query("findUsers")
    @Description("Finds users by exact id or partial display name match.")
    fun findUsers(@Name("query") query: String): List<AuthUserView> =
        contactService.findUsers(
            currentUserId = authContext.requireUserId(),
            query = query,
        )

    @Mutation("sendContactRequest")
    @Description("Creates or reopens a pending contact request to another user.")
    fun sendContactRequest(@Name("userId") userId: UUID): ContactView =
        contactService.sendContactRequest(
            userId = userId,
            currentUserId = authContext.requireUserId(),
        )

    @Mutation("acceptContactRequest")
    @Description("Accepts an incoming contact request from another user.")
    fun acceptContactRequest(@Name("userId") userId: UUID): ContactView =
        contactService.acceptContactRequest(
            userId = userId,
            currentUserId = authContext.requireUserId(),
        )

    @Mutation("declineContactRequest")
    @Description("Declines an incoming contact request from another user.")
    fun declineContactRequest(@Name("userId") userId: UUID): ContactView =
        contactService.declineContactRequest(
            userId = userId,
            currentUserId = authContext.requireUserId(),
        )

    @Mutation("removeContact")
    @Description("Removes an accepted or pending contact relationship, except blocked entries.")
    fun removeContact(@Name("userId") userId: UUID): Boolean =
        contactService.removeContact(
            userId = userId,
            currentUserId = authContext.requireUserId(),
        )

    @Mutation("blockUser")
    @Description("Blocks another user and collapses any existing contact relation into BLOCKED.")
    fun blockUser(@Name("userId") userId: UUID): ContactView =
        contactService.blockUser(
            userId = userId,
            currentUserId = authContext.requireUserId(),
        )
}
