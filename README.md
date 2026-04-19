# voice-stream-server

Backend for a Discord-like voice, screen sharing, and text chat service.

## Stack

- Quarkus 3.34.5
- Kotlin
- Hibernate ORM
- Quarkus REST Jackson
- SmallRye GraphQL
- WebSocket signaling
- PostgreSQL 18.3
- Flyway

## Local Run

Start the database:

```shell
docker compose up -d postgres
```

Run Quarkus in dev mode:

```shell
./gradlew quarkusDev
```

Useful local URLs:

- Dev UI: <http://localhost:8080/q/dev/>
- GraphQL endpoint: <http://localhost:8080/graphql>
- GraphQL UI: <http://localhost:8080/q/graphql-ui/>
- Register: `POST http://localhost:8080/api/auth/register`
- Login: `POST http://localhost:8080/api/auth/login`
- Signaling WebSocket: `ws://localhost:8080/ws/signaling/{mediaSessionId}?token={mediaToken}`

Flyway migrations live in `src/main/resources/db/migration`. Hibernate entities mirror the initial schema, but Flyway owns schema creation and updates.

The MVP media flow is split this way:

- GraphQL stores and returns channel data, messages, roles, active media sessions, and media join tickets.
- The WebSocket endpoint relays app-level signaling events between participants in the same media session.
- Voice and screen media should go through an external WebRTC SFU. Configure it with `MEDIA_SFU_PROVIDER`, `MEDIA_SFU_URL`, `MEDIA_SIGNALING_URL`, `MEDIA_TOKEN_SECRET`, and `MEDIA_TOKEN_TTL_SECONDS`.

Auth endpoints:

```json
POST /api/auth/register
{
  "username": "danil",
  "displayName": "Danil",
  "email": "danil@example.com",
  "password": "change-me-please",
  "deviceName": "Desktop"
}
```

```json
POST /api/auth/login
{
  "login": "danil",
  "password": "change-me-please",
  "deviceName": "Desktop"
}
```

Auth can be configured with `AUTH_TOKEN_SECRET`, `AUTH_ACCESS_TOKEN_TTL_SECONDS`, `AUTH_REFRESH_TOKEN_TTL_SECONDS`, and `AUTH_PASSWORD_HASH_ITERATIONS`.

Current GraphQL entry points:

- `channels`
- `channelRoles(channelId)`
- `channelMessages(channelId, limit)`
- `activeMediaSessions(channelId)`
- `sendChannelMessage(input)`
- `startMediaSession(input)`
- `joinMediaSession(input)`
