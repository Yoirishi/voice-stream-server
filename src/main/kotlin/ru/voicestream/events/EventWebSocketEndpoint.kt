package ru.voicestream.events

import ru.voicestream.auth.AuthTokenService
import jakarta.inject.Inject
import jakarta.websocket.OnClose
import jakarta.websocket.OnError
import jakarta.websocket.OnOpen
import jakarta.websocket.Session
import jakarta.websocket.server.ServerEndpoint

@ServerEndpoint("/ws/events")
class EventWebSocketEndpoint {
    @Inject
    lateinit var eventHub: EventHub

    @Inject
    lateinit var authTokenService: AuthTokenService

    @OnOpen
    fun onOpen(session: Session) {
        val token = session.requestParameterMap["token"]?.firstOrNull()
        val principal = token?.takeIf { it.isNotBlank() }?.let(authTokenService::verifyAccessToken)
        if (principal == null) {
            eventHub.close(session, "Invalid or expired access token.")
            return
        }

        eventHub.join(principal.userId, session)
    }

    @OnClose
    fun onClose(session: Session) {
        eventHub.leave(session.id)
    }

    @OnError
    fun onError(session: Session?, error: Throwable) {
        if (session != null) {
            eventHub.leave(session.id)
        }
    }
}
