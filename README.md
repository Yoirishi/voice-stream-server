# voice-stream-server

Backend for a Discord-like voice, screen sharing, and text chat service.

## Stack

- Quarkus 3.34.5
- Kotlin
- Hibernate ORM
- Quarkus REST Jackson
- SmallRye GraphQL
- WebSocket signaling
- LiveKit SFU
- PostgreSQL 18.3
- Flyway

## Local Run

Run everything in Docker:

```shell
docker compose up --build
```

Or, for backend dev mode, start infra only:

```shell
docker compose up -d postgres livekit
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
- Refresh: `POST http://localhost:8080/api/auth/refresh`
- Logout: `POST http://localhost:8080/api/auth/logout`
- Events WebSocket: `ws://localhost:8080/ws/events?token=<accessToken>`
- Signaling WebSocket: `ws://localhost:8080/ws/signaling/{mediaSessionId}?token={mediaToken}`
- LiveKit WS URL: `ws://localhost:7880`

Frontend/API agent notes live in `FRONTEND_AGENT.md`.

Flyway migrations live in `src/main/resources/db/migration`. Hibernate entities mirror the initial schema, but Flyway owns schema creation and updates.

The MVP media flow is split this way:

- GraphQL stores and returns channel data, messages, roles, active media sessions, and media join tickets.
- LiveKit runs as a separate SFU service in `compose.yaml` and handles actual voice/screen WebRTC transport.
- The custom WebSocket signaling endpoint remains available as a legacy relay path and for tests.
- `startMediaSession` / `joinMediaSession` now return LiveKit room credentials (`serverUrl`, `participantToken`) plus legacy signaling fields (`signalingUrl`, `token`) for compatibility.
- For the current MVP media grant model, `canPublishAudio=true` allows LiveKit microphone and camera publishing; screen-share remains gated by `canPublishScreen=true`.

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

```http
POST /api/auth/refresh
Cookie: voice_stream_session=<encrypted-refresh-token>
```

```http
POST /api/auth/logout
Cookie: voice_stream_session=<encrypted-refresh-token>
```

Auth can be configured with `AUTH_TOKEN_SECRET`, `AUTH_ACCESS_TOKEN_TTL_SECONDS`, `AUTH_REFRESH_TOKEN_TTL_SECONDS`, and `AUTH_PASSWORD_HASH_ITERATIONS`.
Session cookies are encrypted with `AUTH_SESSION_COOKIE_ENCRYPTION_KEY` and returned as an HttpOnly `voice_stream_session` cookie. Refresh rotates the cookie and the stored `user_sessions.refresh_token_hash`; logout revokes the current session and clears the cookie with `Max-Age=0`.

## Tests

Run JVM tests:

```shell
./gradlew test
```

Run the browser fake-media harness:

```shell
docker compose -f compose.e2e.yaml up --abort-on-container-exit --exit-code-from media-e2e media-e2e
```

That stack now boots PostgreSQL, Quarkus, and LiveKit together, then runs a Playwright check that exercises REST auth, GraphQL media tickets, and browser-to-browser fake media through LiveKit.

Current GraphQL entry points:

GraphQL operations require `Authorization: Bearer <accessToken>`.

- `me`
- `myContacts`
- `incomingContactRequests`
- `outgoingContactRequests`
- `findUsers(query)`
- `myDirectConversations`
- `directMessages(conversationId, limit)`
- `myChannels`
- `channels`
- `channelMembers(channelId)`
- `channelRoles(channelId)`
- `channelMessages(channelId, limit)`
- `activeMediaSessions(channelId)`
- `addChannelMember(channelId, userId)`
- `removeChannelMember(channelId, userId)`
- `assignChannelRole(channelMemberId, roleId)`
- `createChannelRole(input)`
- `updateChannelRole(input)`
- `deleteChannelRole(roleId)`
- `setRolePermission(input)`
- `sendChannelMessage(input)`
- `sendContactRequest(userId)`
- `acceptContactRequest(userId)`
- `declineContactRequest(userId)`
- `removeContact(userId)`
- `blockUser(userId)`
- `startDirectConversation(userId)`
- `sendDirectMessage(input)`
- `startMediaSession(input)`
- `joinMediaSession(input)`
- `leaveMediaSession(mediaSessionId)`
- `endMediaSession(mediaSessionId)`
