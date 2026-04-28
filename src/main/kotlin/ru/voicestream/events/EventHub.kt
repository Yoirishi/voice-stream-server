package ru.voicestream.events

import jakarta.enterprise.context.ApplicationScoped
import jakarta.websocket.CloseReason
import jakarta.websocket.Session
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@ApplicationScoped
class EventHub {
    private val connectionsById = ConcurrentHashMap<String, EventConnection>()
    private val connectionIdsByUserId = ConcurrentHashMap<UUID, MutableSet<String>>()

    fun join(userId: UUID, session: Session) {
        connectionsById[session.id] = EventConnection(
            connectionId = session.id,
            userId = userId,
            session = session,
        )
        connectionIdsByUserId
            .computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }
            .add(session.id)
    }

    fun leave(connectionId: String): EventConnection? {
        val connection = connectionsById.remove(connectionId) ?: return null
        connectionIdsByUserId[connection.userId]?.remove(connectionId)
        return connection
    }

    fun close(session: Session, reason: String) {
        session.close(CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, reason))
    }

    fun isConnected(userId: UUID): Boolean =
        connectionIdsByUserId[userId]
            .orEmpty()
            .asSequence()
            .mapNotNull { connectionsById[it] }
            .any { it.session.isOpen }

    fun publishToUsers(userIds: Collection<UUID>, message: String) {
        userIds
            .toSet()
            .asSequence()
            .flatMap { userId -> connectionIdsByUserId[userId].orEmpty().asSequence() }
            .mapNotNull { connectionsById[it] }
            .filter { it.session.isOpen }
            .forEach { it.session.asyncRemote.sendText(message) }
    }
}

data class EventConnection(
    val connectionId: String,
    val userId: UUID,
    val session: Session,
)
