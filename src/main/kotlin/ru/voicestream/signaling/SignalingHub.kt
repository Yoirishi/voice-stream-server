package ru.voicestream.signaling

import jakarta.enterprise.context.ApplicationScoped
import jakarta.json.Json
import jakarta.json.JsonValue
import jakarta.websocket.CloseReason
import jakarta.websocket.Session
import java.io.StringReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@ApplicationScoped
class SignalingHub {
    private val peersByConnectionId = ConcurrentHashMap<String, SignalingPeer>()
    private val connectionIdsByMediaSessionId = ConcurrentHashMap<UUID, MutableSet<String>>()

    fun join(mediaSessionId: UUID, session: Session) {
        val peer = SignalingPeer(
            connectionId = session.id,
            mediaSessionId = mediaSessionId,
            session = session,
        )
        peersByConnectionId[session.id] = peer
        connectionIdsByMediaSessionId
            .computeIfAbsent(mediaSessionId) { ConcurrentHashMap.newKeySet() }
            .add(session.id)

        broadcastServerEvent(mediaSessionId, "peerJoined", session.id)
    }

    fun relay(senderConnectionId: String, message: String) {
        val sender = peersByConnectionId[senderConnectionId] ?: return
        val payload = parseJsonOrString(message)
        val event = Json.createObjectBuilder()
            .add("type", "signal")
            .add("from", senderConnectionId)
            .add("mediaSessionId", sender.mediaSessionId.toString())
            .add("payload", payload)
            .build()
            .toString()

        broadcast(sender.mediaSessionId, event, exceptConnectionId = senderConnectionId)
    }

    fun leave(connectionId: String) {
        val peer = peersByConnectionId.remove(connectionId) ?: return
        connectionIdsByMediaSessionId[peer.mediaSessionId]?.remove(connectionId)
        broadcastServerEvent(peer.mediaSessionId, "peerLeft", connectionId)
    }

    fun close(session: Session, reason: String) {
        session.close(CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, reason))
    }

    private fun broadcastServerEvent(mediaSessionId: UUID, type: String, connectionId: String) {
        val event = Json.createObjectBuilder()
            .add("type", type)
            .add("connectionId", connectionId)
            .add("mediaSessionId", mediaSessionId.toString())
            .build()
            .toString()

        broadcast(mediaSessionId, event, exceptConnectionId = connectionId)
    }

    private fun broadcast(mediaSessionId: UUID, message: String, exceptConnectionId: String) {
        connectionIdsByMediaSessionId[mediaSessionId]
            .orEmpty()
            .asSequence()
            .filter { it != exceptConnectionId }
            .mapNotNull { peersByConnectionId[it] }
            .filter { it.session.isOpen }
            .forEach { it.session.asyncRemote.sendText(message) }
    }

    private fun parseJsonOrString(message: String): JsonValue =
        runCatching {
            Json.createReader(StringReader(message)).readValue()
        }.getOrElse {
            Json.createValue(message)
        }
}

data class SignalingPeer(
    val connectionId: String,
    val mediaSessionId: UUID,
    val session: Session,
)
