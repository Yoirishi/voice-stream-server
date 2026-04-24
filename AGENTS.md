# Agent Notes

This project is a Quarkus/Kotlin backend for a Discord-like voice, screen sharing, and text chat service.

## Current Shape

- Kotlin source lives under `src/main/kotlin`.
- Java generator examples were removed.
- The database is PostgreSQL 18.3 from `compose.yaml`.
- Quarkus connects to `jdbc:postgresql://localhost:5432/voice_stream` by default.
- Flyway owns schema changes. Put migrations in `src/main/resources/db/migration`.
- Hibernate ORM is present and entities mirror `V1__initial_schema.sql`, but do not use `import.sql` or Hibernate schema generation for core schema work.
- REST auth endpoints live under `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/refresh`, and `POST /api/auth/logout`.
- Frontend-facing API details live in `FRONTEND_AGENT.md`; read it before changing client contracts.
- GraphQL uses bearer access tokens through `AuthContext`; do not accept caller user ids from GraphQL inputs.
- GraphQL `me` returns the current safe `AuthUserView` from the bearer token and database user row.
- GraphQL contacts API now includes `myContacts`, `incomingContactRequests`, `outgoingContactRequests`, `findUsers(query)`, `sendContactRequest`, `acceptContactRequest`, `declineContactRequest`, `removeContact`, and `blockUser`.
- GraphQL `myChannels` is the frontend channel tree. It starts from `channel_members`, requires effective `channel.view`, hides private channels without access, and returns the caller's primary role plus computed channel permissions.
- GraphQL `channelMembers(channelId)` returns active members plus their expanded role list. `addChannelMember` auto-assigns the system `USER` role, `removeChannelMember` marks `left_at` and clears role assignments, and `assignChannelRole` appends roles idempotently.
- GraphQL `channelRoles(channelId)` now returns each role with sparse explicit permission rules. `createChannelRole`/`updateChannelRole`/`deleteChannelRole` work only for `CUSTOM` roles, and `setRolePermission` upserts or clears explicit permission rows.
- Media is not handled by Quarkus directly. Quarkus issues media join tickets and relays app-level signaling; an external WebRTC SFU should move audio/video/screen packets.
- Unit tests use JUnit 5 and Mockito-Kotlin. Integration tests can use QuarkusTest, RestAssured, and JDK WebSocket clients.
- Browser media e2e scaffolding lives in `e2e/media` and runs through `compose.e2e.yaml`; it currently validates fake camera/mic WebRTC flow before an SFU is selected.

## Domain Decisions

- `channel_groups` group channels by `TEXT` or `VOICE`.
- `channels` are top-level communication spaces and optionally belong to a channel group.
- Text messages are stored in `channel_messages`.
- User auth stores PBKDF2-SHA256 password hashes and SHA-256 refresh-token hashes.
- Auth responses set an encrypted HttpOnly `voice_stream_session` cookie. The raw refresh token is not exposed in JSON.
- `POST /api/auth/refresh` reads that cookie, checks the SHA-256 refresh-token hash in `user_sessions`, issues a fresh access token, and rotates the cookie plus stored refresh hash on the same session row.
- `POST /api/auth/logout` revokes the matching session when the cookie is valid and always clears `voice_stream_session` with `Max-Age=0`.
- Effective channel permissions currently use assigned channel roles; `DENY` beats `ALLOW`, and `OWNER` is treated as all permissions allowed.
- Member-management mutations currently enforce `member.manage`, role assignment enforces `role.manage`, and the channel owner gets full access even if the owner row is not present in `channel_members`.
- Supported permission keys for MVP are `channel.view`, `message.read`, `message.send`, `voice.connect`, `voice.speak`, `screen.share`, `channel.manage`, `role.manage`, and `member.manage`.
- Roles are scoped directly to a channel. `OWNER` and `USER` are system role kinds; custom channel roles use `CUSTOM`.
- Channel access is modeled through channel-scoped roles and `role_permissions`.
- Contacts live in `user_contacts` with one row per unordered user pair. `PENDING`, `ACCEPTED`, `DECLINED`, and `BLOCKED` are the current statuses.
- `findUsers(query)` matches exact `users.id` when the query parses as UUID, otherwise it does case-insensitive partial matching on `display_name` and excludes the caller.
- `removeContact(userId)` currently deletes non-blocked relations, so it doubles as "remove friend" and "cancel/clear request"; there is no unblock mutation yet.
- `media_sessions` and `media_session_participants` model active voice/screen-share sessions and issued join tickets.
- Signaling uses `ws://.../ws/signaling/{mediaSessionId}?token={mediaToken}` and currently relays JSON messages to other peers in the same media session.
- Direct per-user access overrides and sensitive-field filtering are planned but not implemented yet.

## Sensitive Data

Treat these fields as server-only until an explicit access/filtering layer exists:

- `users.email`
- `users.password_hash`
- `user_sessions.refresh_token_hash`
- `user_sessions.ip_address`
- `user_sessions.user_agent`
- auth access tokens
- raw refresh tokens
- encrypted session cookies
- media join tokens

GraphQL output should be built from explicit DTOs. Do not expose database rows directly to the client.
