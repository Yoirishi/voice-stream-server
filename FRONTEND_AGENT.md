# Frontend Agent API Notes

This file is the quick contract for agents building the React + TypeScript + Tauri client.

The backend is an MVP. Some security boundaries are deliberately not finished yet, so do not infer final role or permission behavior from the current broad GraphQL results.

## Runtime URLs

- REST auth base: `http://localhost:8080/api/auth`
- GraphQL endpoint: `http://localhost:8080/graphql`
- GraphQL UI in dev: `http://localhost:8080/q/graphql-ui/`
- Signaling WebSocket shape: `ws://localhost:8080/ws/signaling/{mediaSessionId}?token={mediaToken}`

Local backend startup:

```shell
docker compose up -d postgres
./gradlew quarkusDev
```

## Current Auth Model

REST auth exists and GraphQL operations read the current user from `Authorization: Bearer <accessToken>`.

The client should still store and send access tokens as if auth will become enforced soon:

```http
Authorization: Bearer <accessToken>
```

Important MVP limitations:

- There is no password reset, invite, or membership endpoint yet.
- GraphQL mutations no longer accept `userId` / `authorUserId` from the client. They use the access token auth context.
- Do not expose or persist sensitive fields beyond what auth returns.
- Refresh/session tokens are not returned in JSON. They are stored as an encrypted HttpOnly cookie.
- Use GraphQL `me` to restore the current user after app startup when an access token is available.

Sensitive server-only data:

- `users.email`
- `users.password_hash`
- `user_sessions.refresh_token_hash`
- `user_sessions.ip_address`
- `user_sessions.user_agent`
- auth access tokens, except in the client token store
- raw refresh tokens
- encrypted session cookies
- media join tokens, except while joining a media session

## REST Endpoints

### `POST /api/auth/register`

Request:

```json
{
  "username": "danil",
  "displayName": "Danil",
  "email": "danil@example.com",
  "password": "change-me-please",
  "deviceName": "Desktop"
}
```

Validation:

- `username`: 3-32 chars, latin letters/digits/`_`/`.`/`-`, first char must be latin letter/digit/`_`.
- `displayName`: must not be blank, trimmed, max returned/stored length is 80.
- `email`: basic email shape, lowercased server-side, max 254.
- `password`: 8-256 chars.
- `deviceName`: optional, trimmed, max stored length is 120.

Success response:

```json
{
  "tokenType": "Bearer",
  "accessToken": "...",
  "accessTokenExpiresAt": "2026-04-19T08:00:00.000Z",
  "refreshTokenExpiresAt": "2026-05-19T08:00:00.000Z",
  "user": {
    "id": "uuid",
    "username": "danil",
    "displayName": "Danil",
    "avatarMediaKey": null
  }
}
```

The response also sets:

```http
Set-Cookie: voice_stream_session=<encrypted-refresh-token>; Max-Age=2592000; Path=/; HttpOnly; SameSite=Lax
```

Cookie notes:

- The cookie value is an AES-GCM encrypted refresh token.
- The raw refresh token is never returned to JavaScript.
- The database stores only a SHA-256 hash of the refresh token.
- Local dev does not mark the cookie `Secure` by default because the backend runs on plain HTTP.
- Production should set `AUTH_SESSION_COOKIE_SECURE=true` and override `AUTH_SESSION_COOKIE_ENCRYPTION_KEY`.

### `POST /api/auth/login`

Request:

```json
{
  "login": "danil",
  "password": "change-me-please",
  "deviceName": "Desktop"
}
```

`login` can be username or email.

Success response is the same `AuthResponse` as registration and also sets the HttpOnly `voice_stream_session` cookie.

### `POST /api/auth/refresh`

No JSON request body is required.

The backend reads the HttpOnly `voice_stream_session` cookie, decrypts the raw refresh token, checks its SHA-256 hash in `user_sessions`, and rejects missing, unknown, revoked, or expired sessions.

On success it returns the same `AuthResponse` shape as registration/login:

```json
{
  "tokenType": "Bearer",
  "accessToken": "...",
  "accessTokenExpiresAt": "2026-04-19T08:15:00.000Z",
  "refreshTokenExpiresAt": "2026-05-19T08:00:00.000Z",
  "user": {
    "id": "uuid",
    "username": "danil",
    "displayName": "Danil",
    "avatarMediaKey": null
  }
}
```

Refresh token rotation is enabled. A successful refresh updates the existing `user_sessions.refresh_token_hash`, extends the session expiry, and sends a new `voice_stream_session` cookie. The previous cookie should be treated as invalid immediately after refresh.

Frontend notes:

- Send cookies with the request, for example `credentials: "include"` in browser `fetch`.
- Store the new `accessToken` from the response.
- Do not expect a `refreshToken` JSON field.
- If refresh returns `401 auth_invalid_credentials`, clear the local access token and send the user to login.

### `POST /api/auth/logout`

No JSON request body is required.

The backend reads the HttpOnly `voice_stream_session` cookie. If it decrypts to a known non-revoked session, the matching `user_sessions` row gets `revoked_at` set. Logout is intentionally tolerant of missing or already-invalid cookies so the client can always clean up local state.

Success response:

```http
204 No Content
Set-Cookie: voice_stream_session=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; HttpOnly; SameSite=Lax
```

Frontend notes:

- Send cookies with the request, for example `credentials: "include"` in browser `fetch`.
- Clear the local access token after any successful logout response.
- After logout, the old session cookie cannot be used for refresh.

### Auth Error Shape

Errors are JSON:

```json
{
  "error": "auth_invalid_credentials",
  "message": "Invalid login or password."
}
```

Known auth statuses:

- `400 auth_invalid_request`
- `401 auth_invalid_credentials`
- `403 auth_forbidden`
- `409 auth_conflict`

The backend also reads `User-Agent` and first IP from `X-Forwarded-For` for session metadata.

## GraphQL

Send GraphQL as normal HTTP POST to `/graphql`.

Current GraphQL operations require a bearer access token. Access-control filtering by roles is still incomplete, but the caller identity already comes from the token.

Example:

```http
POST /graphql
Content-Type: application/json
Authorization: Bearer <accessToken>
```

```json
{
  "query": "query { channels { groups { id name type channels { id name type } } ungroupedChannels { id name type } } }"
}
```

Enums are serialized as strings:

- `ChannelType`: `TEXT`, `VOICE`
- `RoleKind`: `OWNER`, `USER`, `CUSTOM`
- `PermissionEffect`: `ALLOW`, `DENY`
- `MediaSessionType`: `VOICE`, `SCREEN_SHARE`
- `MediaSessionStatus`: `ACTIVE`, `ENDED`

Timestamps are ISO offset date-time strings.

### Query `me`

Returns the current authenticated user from `Authorization: Bearer <accessToken>`.

Query:

```graphql
query Me {
  me {
    id
    username
    displayName
    avatarMediaKey
  }
}
```

Shape:

```ts
type AuthUserView = {
  id: string;
  username: string;
  displayName: string;
  avatarMediaKey: string | null;
};
```

Nuances:

- Requires a valid bearer access token.
- Loads the user from the database, not only from token payload.
- Does not expose `email`, `passwordHash`, session metadata, or refresh token data.

### Query `channels`

Returns the channel tree visible to the caller. Access filtering is not implemented yet, so currently this is effectively the full tree.

Query:

```graphql
query Channels {
  channels {
    groups {
      id
      name
      type
      position
      channels {
        id
        name
        type
        topic
        position
        privateChannel
        voiceUserLimit
        voiceBitrate
      }
    }
    ungroupedChannels {
      id
      name
      type
      topic
      position
      privateChannel
      voiceUserLimit
      voiceBitrate
    }
  }
}
```

Shape:

```ts
type ChannelDirectory = {
  groups: ChannelGroupView[];
  ungroupedChannels: ChannelView[];
};

type ChannelGroupView = {
  id: string;
  name: string;
  type: 'TEXT' | 'VOICE';
  position: number;
  channels: ChannelView[];
};

type ChannelView = {
  id: string;
  name: string;
  type: 'TEXT' | 'VOICE';
  topic: string | null;
  position: number;
  privateChannel: boolean;
  voiceUserLimit: number | null;
  voiceBitrate: number | null;
};
```

Nuances:

- Groups are ordered by `position`, then `name`.
- Channels are ordered by `position`, then `name`.
- Voice-only fields are `voiceUserLimit` and `voiceBitrate`; they should be null for text channels.

### Query `channelRoles(channelId)`

Query:

```graphql
query ChannelRoles($channelId: UUID!) {
  channelRoles(channelId: $channelId) {
    id
    channelId
    name
    kind
    colorHex
    position
    system
  }
}
```

Shape:

```ts
type RoleView = {
  id: string;
  channelId: string;
  name: string;
  kind: 'OWNER' | 'USER' | 'CUSTOM';
  colorHex: string | null;
  position: number;
  system: boolean;
};
```

Nuances:

- Roles are scoped directly to channels.
- `OWNER` and `USER` are system roles.
- `CUSTOM` roles are planned for user-created roles, but role management mutations do not exist yet.

### Query `channelMessages(channelId, limit)`

Query:

```graphql
query ChannelMessages($channelId: UUID!, $limit: Int) {
  channelMessages(channelId: $channelId, limit: $limit) {
    id
    channelId
    authorUserId
    body
    createdAt
    editedAt
  }
}
```

Shape:

```ts
type ChannelMessageView = {
  id: string;
  channelId: string;
  authorUserId: string;
  body: string;
  createdAt: string;
  editedAt: string | null;
};
```

Nuances:

- Default `limit` is 50.
- Backend clamps `limit` to `1..100`.
- Backend returns messages in chronological order after selecting the latest rows.
- Deleted messages are filtered out.

### Query `activeMediaSessions(channelId)`

Query:

```graphql
query ActiveMediaSessions($channelId: UUID!) {
  activeMediaSessions(channelId: $channelId) {
    id
    channelId
    type
    sfuProvider
    roomName
    status
    startedAt
  }
}
```

Shape:

```ts
type MediaSessionView = {
  id: string;
  channelId: string;
  type: 'VOICE' | 'SCREEN_SHARE';
  sfuProvider: string;
  roomName: string;
  status: 'ACTIVE' | 'ENDED';
  startedAt: string;
};
```

Nuances:

- Only `ACTIVE` sessions are returned.
- The backend currently models media sessions, tickets, and signaling. It does not move RTP audio/video/screen bytes.

### Mutation `sendChannelMessage(input)`

Mutation:

```graphql
mutation SendChannelMessage {
  sendChannelMessage(input: {
    channelId: "uuid"
    body: "hello"
  }) {
    id
    channelId
    authorUserId
    body
    createdAt
    editedAt
  }
}
```

Nuances:

- `authorUserId` is taken from the bearer access token.
- Body is trimmed server-side and must not be blank.
- Only `TEXT` channels accept messages.
- The author user must exist.
- There is no realtime message subscription yet; after send, update local cache manually or refetch.

### Mutation `startMediaSession(input)`

Starts or reuses an active media session for a voice channel and returns a join ticket.

Mutation:

```graphql
mutation StartMediaSession {
  startMediaSession(input: {
    channelId: "uuid"
    type: VOICE
    canPublishAudio: true
    canPublishScreen: false
  }) {
    mediaSessionId
    channelId
    userId
    sfuProvider
    sfuUrl
    signalingUrl
    roomName
    token
    expiresAt
    canPublishAudio
    canPublishScreen
    canSubscribe
  }
}
```

Input defaults:

- `type`: `VOICE`
- `canPublishAudio`: `true`
- `canPublishScreen`: `false`

Nuances:

- `userId` is taken from the bearer access token.
- Channel must be a `VOICE` channel.
- User must exist.
- If an active session already exists for the channel and media type, backend reuses it.
- The returned `signalingUrl` already includes `mediaSessionId` and `token`; the frontend can connect to it directly.
- The returned `token` is also the media token; keep it private.

### Mutation `joinMediaSession(input)`

Joins an existing active media session and returns a join ticket.

Mutation:

```graphql
mutation JoinMediaSession {
  joinMediaSession(input: {
    mediaSessionId: "uuid"
    canPublishAudio: false
    canPublishScreen: false
    canSubscribe: true
  }) {
    mediaSessionId
    channelId
    userId
    sfuProvider
    sfuUrl
    signalingUrl
    roomName
    token
    expiresAt
    canPublishAudio
    canPublishScreen
    canSubscribe
  }
}
```

Input defaults:

- `canPublishAudio`: `false`
- `canPublishScreen`: `false`
- `canSubscribe`: `true`

Nuances:

- `userId` is taken from the bearer access token.
- The media session must exist and be `ACTIVE`.
- User must exist.

### `MediaJoinTicket` Shape

```ts
type MediaJoinTicket = {
  mediaSessionId: string;
  channelId: string;
  userId: string;
  sfuProvider: string;
  sfuUrl: string;
  signalingUrl: string;
  roomName: string;
  token: string;
  expiresAt: string;
  canPublishAudio: boolean;
  canPublishScreen: boolean;
  canSubscribe: boolean;
};
```

## Signaling WebSocket

Connect to:

```text
ws://localhost:8080/ws/signaling/{mediaSessionId}?token={mediaToken}
```

Use the `signalingUrl` returned by `startMediaSession` / `joinMediaSession` when possible.

Invalid connection behavior:

- bad UUID path: close with reason `Invalid mediaSessionId.`
- missing, bad, expired, or mismatched token: close with reason `Invalid or expired signaling token.`

Current server events:

```ts
type PeerJoinedEvent = {
  type: 'peerJoined';
  connectionId: string;
  mediaSessionId: string;
};

type PeerLeftEvent = {
  type: 'peerLeft';
  connectionId: string;
  mediaSessionId: string;
};

type SignalEvent = {
  type: 'signal';
  from: string;
  mediaSessionId: string;
  payload: unknown;
};
```

Nuances:

- `connectionId` / `from` are server WebSocket session ids, not user ids.
- When a client joins, existing peers in that media session receive `peerJoined`.
- The joining client does not currently receive a peer list.
- When a client disconnects, remaining peers receive `peerLeft`.
- Client messages are relayed to other open peers in the same media session only.
- The sender does not receive its own relayed signal.
- If client text is valid JSON, server sends it as JSON `payload`.
- If client text is not JSON, server wraps it as string `payload`.

Suggested client message payloads for WebRTC signaling:

```ts
type ClientSignalPayload =
  | { kind: 'offer'; sdp: string }
  | { kind: 'answer'; sdp: string }
  | { kind: 'ice-candidate'; candidate: RTCIceCandidateInit }
  | { kind: 'renegotiate' }
  | { kind: 'ping'; at: string };
```

The backend does not validate this payload yet. Keep the client tolerant of unknown `kind` values.

## Media Transport

Current recommendation:

- Use WebRTC for audio/video/screen.
- Backend handles auth/session/tickets/control signaling.
- An external SFU should carry RTP media traffic.
- The current backend signaling WebSocket is app-level signaling relay, not a media relay.

Browser/Tauri frontend notes:

- Use `navigator.mediaDevices.getUserMedia()` for microphone.
- Use `navigator.mediaDevices.getDisplayMedia()` for screen/window capture.
- Use the SFU SDK once the SFU is selected.
- Use `MediaJoinTicket.sfuUrl`, `roomName`, `token`, and capability flags to join/publish/subscribe.
- Keep media tokens short-lived and never log them.

Testing notes:

- JVM tests cover REST auth, `SignalingHub`, and real WebSocket signaling.
- Browser fake-media e2e lives in `e2e/media`.
- The browser media test currently validates fake camera/mic WebRTC flow without an SFU.
- Run it with:

```shell
docker compose -f compose.e2e.yaml up --abort-on-container-exit --exit-code-from media-e2e media-e2e
```

## Missing Frontend-Facing API

Do not invent these endpoints yet; they are not implemented:

- channel create/update/delete/reorder
- channel membership/invites
- role create/update/delete/assign
- permission editing
- realtime text message subscriptions
- media session end/leave endpoint
- file/media upload endpoint
- sensitive-field access filtering

## Error Handling Notes

REST auth errors have the explicit `{ error, message }` shape above.

GraphQL errors currently come from thrown `IllegalArgumentException` / `require` checks and will be returned as GraphQL errors, not as the REST auth error shape.

Frontend should:

- handle GraphQL `errors[]`;
- show `message` where safe;
- keep UI resilient when optional fields are null;
- not assume GraphQL role/access filtering exists yet.
