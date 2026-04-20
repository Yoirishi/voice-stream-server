package ru.voicestream.graphql

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.graphql.Description
import org.eclipse.microprofile.graphql.GraphQLApi
import org.eclipse.microprofile.graphql.Query
import ru.voicestream.auth.AuthContext
import ru.voicestream.auth.AuthService
import ru.voicestream.auth.AuthUserView

@GraphQLApi
@ApplicationScoped
class AuthGraphQLResource(
    private val authContext: AuthContext,
    private val authService: AuthService,
) {
    @Query("me")
    @Description("Returns the currently authenticated user.")
    fun me(): AuthUserView =
        authService.currentUser(authContext.requireUserId())
}
