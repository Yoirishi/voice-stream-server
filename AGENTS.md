# Agent Notes

This project is a Quarkus/Kotlin backend for a Discord-like voice, screen sharing, and text chat service.

## Current Shape

- Kotlin source lives under `src/main/kotlin`.
- Java generator examples were removed.
- The database is PostgreSQL 18.3 from `compose.yaml`.
- Quarkus connects to `jdbc:postgresql://localhost:5432/voice_stream` by default.
- Flyway owns schema changes. Put migrations in `src/main/resources/db/migration`.
- Do not use `import.sql` or Hibernate schema generation for core schema work.

## Domain Decisions

- `channel_groups` group channels by `TEXT` or `VOICE`.
- `channels` are top-level communication spaces and optionally belong to a channel group.
- Roles are scoped directly to a channel. `OWNER` and `USER` are system role kinds; custom channel roles use `CUSTOM`.
- Channel access is modeled through channel-scoped roles and `role_permissions`.
- Direct per-user access overrides and sensitive-field filtering are planned but not implemented yet.

## Sensitive Data

Treat these fields as server-only until an explicit access/filtering layer exists:

- `users.email`
- `users.password_hash`
- `user_sessions.refresh_token_hash`
- `user_sessions.ip_address`
- `user_sessions.user_agent`

GraphQL output should be built from explicit DTOs. Do not expose database rows directly to the client.
