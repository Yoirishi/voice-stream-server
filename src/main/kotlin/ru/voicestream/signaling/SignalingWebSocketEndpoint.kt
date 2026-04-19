package ru.voicestream.signaling

import jakarta.inject.Inject
import jakarta.websocket.OnClose
import jakarta.websocket.OnError
import jakarta.websocket.OnMessage
import jakarta.websocket.OnOpen
import jakarta.websocket.Session
import jakarta.websocket.server.PathParam
import jakarta.websocket.server.ServerEndpoint
import ru.voicestream.media.MediaTokenService
import java.util.UUID

@ServerEndpoint("/ws/signaling/{mediaSessionId}")
class SignalingWebSocketEndpoint {
    @Inject
    lateinit var signalingHub: SignalingHub

    @Inject
    lateinit var mediaTokenService: MediaTokenService

    @OnOpen
    fun onOpen(session: Session, @PathParam("mediaSessionId") mediaSessionIdValue: String) {
        val mediaSessionId = runCatching { UUID.fromString(mediaSessionIdValue) }.getOrNull()
        if (mediaSessionId == null) {
            signalingHub.close(session, "Invalid mediaSessionId.")
            return
        }

        val token = session.requestParameterMap["token"]?.firstOrNull()
        if (token.isNullOrBlank() || !mediaTokenService.verify(mediaSessionId, token)) {
            signalingHub.close(session, "Invalid or expired signaling token.")
            return
        }

        signalingHub.join(mediaSessionId, session)
    }

    @OnMessage
    fun onMessage(message: String, session: Session) {
        signalingHub.relay(session.id, message)
    }

    @OnClose
    fun onClose(session: Session) {
        signalingHub.leave(session.id)
    }

    @OnError
    fun onError(session: Session?, error: Throwable) {
        if (session != null) {
            signalingHub.leave(session.id)
        }
    }
}
