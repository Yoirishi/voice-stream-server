package ru.voicestream.auth

import jakarta.enterprise.context.RequestScoped
import jakarta.ws.rs.core.HttpHeaders
import java.util.UUID
import io.vertx.ext.web.RoutingContext

@RequestScoped
class AuthContext(
    private val authTokenService: AuthTokenService,
    private val routingContext: RoutingContext,
) {
    private var resolved = false
    private var principal: AuthPrincipal? = null

    fun currentPrincipal(): AuthPrincipal? {
        if (!resolved) {
            principal = bearerToken()?.let(authTokenService::verifyAccessToken)
            resolved = true
        }

        return principal
    }

    fun requirePrincipal(): AuthPrincipal =
        currentPrincipal() ?: throw AuthRequiredException()

    fun requireUserId(): UUID =
        requirePrincipal().userId

    private fun bearerToken(): String? {
        val header = routingContext.request().getHeader(HttpHeaders.AUTHORIZATION) ?: return null
        val parts = header.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) {
            return null
        }

        return parts[1].takeIf { it.isNotBlank() }
    }
}

class AuthRequiredException : RuntimeException("Authentication is required.")
