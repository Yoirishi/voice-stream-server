package ru.voicestream.auth

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/auth")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class AuthResource(
    private val authService: AuthService,
) {
    @POST
    @Path("/register")
    fun register(
        request: RegisterRequest,
        @Context headers: HttpHeaders,
    ): AuthResponse =
        handleAuthErrors {
            authService.register(
                request = request,
                userAgent = headers.getHeaderString(HttpHeaders.USER_AGENT),
                ipAddress = clientIp(headers),
            )
        }

    @POST
    @Path("/login")
    fun login(
        request: LoginRequest,
        @Context headers: HttpHeaders,
    ): AuthResponse =
        handleAuthErrors {
            authService.login(
                request = request,
                userAgent = headers.getHeaderString(HttpHeaders.USER_AGENT),
                ipAddress = clientIp(headers),
            )
        }

    private fun <T> handleAuthErrors(block: () -> T): T =
        try {
            block()
        } catch (exception: AuthConflictException) {
            throw responseException(Response.Status.CONFLICT, "auth_conflict", exception.message)
        } catch (exception: AuthUnauthorizedException) {
            throw responseException(Response.Status.UNAUTHORIZED, "auth_invalid_credentials", exception.message)
        } catch (exception: AuthForbiddenException) {
            throw responseException(Response.Status.FORBIDDEN, "auth_forbidden", exception.message)
        } catch (exception: IllegalArgumentException) {
            throw responseException(Response.Status.BAD_REQUEST, "auth_invalid_request", exception.message)
        }

    private fun responseException(
        status: Response.Status,
        error: String,
        message: String?,
    ): WebApplicationException =
        WebApplicationException(
            Response.status(status)
                .entity(AuthErrorResponse(error = error, message = message ?: status.reasonPhrase))
                .build(),
        )

    private fun clientIp(headers: HttpHeaders): String? =
        headers.getHeaderString("X-Forwarded-For")
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
