# voice-stream-server

Backend for a Discord-like voice, screen sharing, and text chat service.

## Stack

- Quarkus 3.34.5
- Kotlin
- SmallRye GraphQL
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

Flyway migrations live in `src/main/resources/db/migration`. The initial schema creates users, auth sessions, channel groups, channels, channel members, channel roles, role assignments, and permissions.
