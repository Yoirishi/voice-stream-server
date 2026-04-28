# Frontend Agent API Notes

This file is the quick contract for agents building the React + TypeScript + Tauri client.

The backend is an MVP. Some security boundaries are deliberately not finished yet, so do not infer final role or permission behavior from the current broad GraphQL results.

## Runtime URLs

- REST auth base: `http://localhost:8080/api/auth`
- GraphQL endpoint: `http://localhost:8080/graphql`
- GraphQL UI in dev: `http://localhost:8080/q/graphql-ui/`
- Events WebSocket: `ws://localhost:8080/ws/events?token=<accessToken>`
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
  "query": "query { myChannels { groups { id name type channels { id name type role { kind name } permissions { canView canSendMessage } } } ungroupedChannels { id name type } } }"
}
```

Enums are serialized as strings:

- `ChannelType`: `TEXT`, `VOICE`
- `RoleKind`: `OWNER`, `USER`, `CUSTOM`
- `PermissionEffect`: `ALLOW`, `DENY`
- `ContactStatus`: `PENDING`, `ACCEPTED`, `DECLINED`, `BLOCKED`
- `MediaSessionType`: `VOICE`, `SCREEN_SHARE`
- `MediaSessionStatus`: `ACTIVE`, `ENDED`
- `UserOnlineStatus`: `ONLINE`, `OFFLINE`

### Query `myContacts`

Returns the caller's accepted contacts.

Query:

```graphql
query MyContacts {
  myContacts {
    id
    status
    user {
      id
      username
      displayName
      avatarMediaKey
    }
    createdAt
    updatedAt
    respondedAt
    blockedAt
  }
}
```

Shape:

```ts
type ContactView = {
  id: string;
  status: 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'BLOCKED';
  user: AuthUserView;
  createdAt: string;
  updatedAt: string;
  respondedAt: string | null;
  blockedAt: string | null;
};
```

Nuances:

- Returns only `ACCEPTED` rows.
- `user` is always the other side of the relationship, never the caller.
- Sorted by `displayName`, then `username`.

### Query `incomingContactRequests`

Returns pending requests where the caller is the addressee.

### Query `outgoingContactRequests`

Returns pending requests where the caller is the requester.

Both queries return `ContactView[]`.

Nuances:

- Both lists include only `PENDING` rows.
- `incomingContactRequests` and `outgoingContactRequests` are sorted by newest first.

### Query `findUsers(query)`

Finds users for the contact picker.

Query:

```graphql
query FindUsers($query: String!) {
  findUsers(query: $query) {
    id
    username
    displayName
    avatarMediaKey
  }
}
```

Nuances:

- If `query` parses as UUID, backend does an exact `id` match.
- Otherwise backend does case-insensitive partial matching on `displayName`.
- The current caller is excluded from results.
- Disabled users are excluded.
- Display-name search is capped to 20 rows.

### Mutation `sendContactRequest(userId)`

Creates or reopens a pending contact request.

Rules:

- Requires `Authorization: Bearer <accessToken>`.
- Refuses self-requests.
- Returns the existing row unchanged when the relation is already `ACCEPTED` or when the caller already has an outgoing `PENDING` request.
- If there is already an incoming request from that user, backend returns a GraphQL error; call `acceptContactRequest` instead.
- If either side already has a `BLOCKED` relation, backend returns a GraphQL error.
- Re-sending after `DECLINED` reopens the same pair as `PENDING`.

### Mutation `acceptContactRequest(userId)`

Accepts an incoming pending request and returns `ContactView` with `status: ACCEPTED`.

### Mutation `declineContactRequest(userId)`

Declines an incoming pending request and returns `ContactView` with `status: DECLINED`.

### Mutation `removeContact(userId)`

Removes a non-blocked relation and returns `Boolean`.

Nuances:

- Returns `true` when a non-blocked row existed and was deleted.
- Returns `false` when there was no relation or when the current relation is `BLOCKED`.
- This currently works for accepted contacts and also clears pending/declined rows, so the frontend can use it as "remove friend" or "cancel request".

### Mutation `blockUser(userId)`

Blocks another user and returns `ContactView` with `status: BLOCKED`.

Nuances:

- Collapses any existing accepted/pending/declined relation into `BLOCKED`.
- If the pair is already blocked, returns the current blocked row.
- There is no unblock mutation yet.

Timestamps are ISO offset date-time strings.

## Realtime Event WebSocket

Connect to:

```text
ws://localhost:8080/ws/events?token=<accessToken>
```

Use the current bearer access token as the `token` query parameter.

Connection notes:

- Missing, bad, or expired token: close with reason `Invalid or expired access token.`
- Browser clients usually cannot set `Authorization` headers on raw WebSocket connections, so query-param auth is used here.
- Reconnect with a fresh access token after auth refresh.
- Events are fan-out by user id. A user can have multiple open event sockets.

Current server events:

```ts
type ChannelMessageCreatedEvent = {
  type: 'channelMessageCreated';
  channelId: string;
  message: ChannelMessageView;
};

type DirectMessageCreatedEvent = {
  type: 'directMessageCreated';
  conversationId: string;
  message: DirectMessageView;
};

type ContactRequestReceivedEvent = {
  type: 'contactRequestReceived';
  contact: ContactView;
};

type UserPresenceUpdatedEvent = {
  type: 'userPresenceUpdated';
  presence: UserPresenceView;
};

type ChannelVoiceStateUpdatedEvent = {
  type: 'channelVoiceStateUpdated';
  channelId: string;
  voiceState: ChannelVoiceStateView;
};

type MediaSessionStartedEvent = {
  type: 'mediaSessionStarted';
  channelId: string;
  mediaSession: MediaSessionView;
};

type MediaSessionEndedEvent = {
  type: 'mediaSessionEnded';
  channelId: string;
  mediaSession: MediaSessionView;
};
```

Nuances:

- `channelMessageCreated` is currently sent to users who can at least view the channel.
- `directMessageCreated` is sent to both DM participants, including the sender.
- `contactRequestReceived` is sent only to the addressee of a new or reopened pending request.
- `userPresenceUpdated` is currently emitted when backend presence changes through media/session state updates. Plain socket open/close does not yet emit a dedicated online/offline event.
- `channelVoiceStateUpdated` is sent to users who can view the voice channel. `active: false` means the user left or the session ended.
- `mediaSessionStarted` is sent only when a new media session row is created, not when an existing active one is reused.
- `mediaSessionEnded` is sent when a session transitions to `ENDED`, either by explicit `endMediaSession` or when the last active participant leaves through `leaveMediaSession`.
- `connectionStatus` should stay client-local and come from WebSocket + LiveKit lifecycle, not from backend payloads.

### Query `myDirectConversations`

Returns the caller's 1:1 direct conversations.

Query:

```graphql
query MyDirectConversations {
  myDirectConversations {
    id
    user {
      id
      username
      displayName
      avatarMediaKey
    }
    createdAt
    updatedAt
    lastMessage {
      id
      conversationId
      authorUserId
      body
      createdAt
      editedAt
    }
  }
}
```

Shape:

```ts
type DirectConversationView = {
  id: string;
  user: AuthUserView;
  createdAt: string;
  updatedAt: string;
  lastMessage: DirectMessageView | null;
};

type DirectMessageView = {
  id: string;
  conversationId: string;
  authorUserId: string;
  body: string;
  createdAt: string;
  editedAt: string | null;
};
```

Nuances:

- Current MVP supports only 1:1 DMs.
- Conversations are ordered by `updatedAt` descending.
- `user` is always the other participant.
- `lastMessage` skips deleted rows and is `null` until the first message is sent.

### Query `directMessages(conversationId, limit)`

Returns recent non-deleted direct messages from a conversation.

Nuances:

- Requires membership in that DM.
- Default `limit` is 50.
- Backend clamps `limit` to `1..100`.
- Backend returns messages in chronological order after selecting the latest rows.

### Mutation `startDirectConversation(userId)`

Starts or reuses a direct conversation with another user.

Rules:

- Requires `Authorization: Bearer <accessToken>`.
- Refuses self-DMs.
- If a DM already exists for that pair and the relation is not blocked, backend returns the existing conversation.
- Creating a brand-new DM requires the contact relation to be `ACCEPTED`.
- If the pair is blocked, backend returns a GraphQL error.

### Mutation `sendDirectMessage(input)`

Sends a text message to a direct conversation.

Mutation:

```graphql
mutation SendDirectMessage($conversationId: UUID!) {
  sendDirectMessage(
    input: {
      conversationId: $conversationId
      body: "hello"
    }
  ) {
    id
    conversationId
    authorUserId
    body
    createdAt
    editedAt
  }
}
```

Nuances:

- Requires membership in the DM.
- Trims `body` server-side and rejects blank messages.
- Rejects sending when the user pair is currently blocked.
- Removing a contact does not delete an existing DM; it can still be listed/opened unless blocked.

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

## UI State Contract

For MVP, UI state is split into server-owned presence snapshots, server-pushed realtime events, and client-local transport state.

Server-owned snapshot state:

- `myPresence`
- `myContactPresences`
- `channelVoiceStates(channelId)`
- `updateMyVoiceState(input)` writes the current caller's voice flags

Server-pushed realtime events:

- `channelMessageCreated`
- `directMessageCreated`
- `contactRequestReceived`
- `mediaSessionStarted`
- `mediaSessionEnded`
- `userPresenceUpdated`
- `channelVoiceStateUpdated`

Client-local only state:

- `connectionStatus: 'connecting' | 'connected' | 'reconnecting' | 'disconnected'`
- current LiveKit room and participant transport state
- unread counters, which are not implemented by backend yet

Important nuances:

- Presence is in-memory for MVP. Restarting the backend clears it.
- `onlineStatus` is currently derived from active `/ws/events` connections on the backend.
- Plain online/offline transitions are queryable immediately through GraphQL presence queries. The backend does not yet push a dedicated online/offline event on raw socket open/close.
- `muted`, `deafened`, and `screenSharing` are application-level UI flags stored in-memory. Update them only after the local media action succeeds.

### Query `myPresence`

Returns the current caller's presence snapshot.

Query:

```graphql
query MyPresence {
  myPresence {
    userId
    onlineStatus
    voiceChannelId
    mediaSessionId
    muted
    deafened
    screenSharing
    updatedAt
  }
}
```

Shape:

```ts
type UserPresenceView = {
  userId: string;
  onlineStatus: 'ONLINE' | 'OFFLINE';
  voiceChannelId: string | null;
  mediaSessionId: string | null;
  muted: boolean;
  deafened: boolean;
  screenSharing: boolean;
  updatedAt: string | null;
};
```

Nuances:

- `voiceChannelId` and `mediaSessionId` are null when the user is not in an active voice session.
- `updatedAt` is null until the backend has tracked at least one presence-changing action for the user.

### Query `myContactPresences`

Returns accepted contacts together with their current presence snapshot.

Query:

```graphql
query MyContactPresences {
  myContactPresences {
    user {
      id
      username
      displayName
      avatarMediaKey
    }
    presence {
      userId
      onlineStatus
      voiceChannelId
      mediaSessionId
      muted
      deafened
      screenSharing
      updatedAt
    }
  }
}
```

Shape:

```ts
type ContactPresenceView = {
  user: AuthUserView;
  presence: UserPresenceView;
};
```

Nuances:

- Only accepted contacts are returned.
- The list is sorted by contact `displayName`, then `username`.

### Query `channelVoiceStates(channelId)`

Returns the active voice-state roster for one visible voice channel.

Query:

```graphql
query ChannelVoiceStates($channelId: UUID!) {
  channelVoiceStates(channelId: $channelId) {
    user {
      id
      username
      displayName
      avatarMediaKey
    }
    channelId
    mediaSessionId
    active
    muted
    deafened
    screenSharing
    onlineStatus
    updatedAt
  }
}
```

Shape:

```ts
type ChannelVoiceStateView = {
  user: AuthUserView;
  channelId: string;
  mediaSessionId: string | null;
  active: boolean;
  muted: boolean;
  deafened: boolean;
  screenSharing: boolean;
  onlineStatus: 'ONLINE' | 'OFFLINE';
  updatedAt: string | null;
};
```

Nuances:

- Only currently active voice participants are returned.
- Requires access to the channel through the existing channel visibility rules.
- `active` should be treated as authoritative for roster presence; users who have left the session disappear from this query.

### Mutation `updateMyVoiceState(input)`

Updates the current caller's in-memory voice UI flags for an active media session.

Mutation:

```graphql
mutation UpdateMyVoiceState($mediaSessionId: UUID!) {
  updateMyVoiceState(input: {
    mediaSessionId: $mediaSessionId
    muted: true
    deafened: false
    screenSharing: true
  }) {
    user {
      id
    }
    channelId
    mediaSessionId
    active
    muted
    deafened
    screenSharing
    onlineStatus
    updatedAt
  }
}
```

Nuances:

- The caller must be an active participant in the target media session.
- `screenSharing: true` is rejected unless the issued media ticket allowed screen publish.
- Use this after the local mute/deafen/share action succeeds, not before.

### Query `myChannels`

Returns the current user's channel tree. This is the preferred channel list for the client.

Filtering rules:

- Requires `Authorization: Bearer <accessToken>`.
- Starts from active `channel_members` rows for the current user.
- Computes effective permissions from assigned roles and `role_permissions`.
- Requires effective `channel.view`; channels without it are excluded.
- Private channels are not returned unless the current user is a member with `channel.view`.
- `DENY` wins over `ALLOW`; `OWNER` role is treated as all permissions allowed.

Query:

```graphql
query MyChannels {
  myChannels {
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
        role {
          id
          channelId
          name
          kind
          colorHex
          position
          system
        }
        permissions {
          canView
          canSendMessage
          canConnectVoice
          canManageChannel
          canShareScreen
        }
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
      role {
        id
        channelId
        name
        kind
        colorHex
        position
        system
      }
      permissions {
        canView
        canSendMessage
        canConnectVoice
        canManageChannel
        canShareScreen
      }
    }
  }
}
```

Shape:

```ts
type MyChannelDirectory = {
  groups: MyChannelGroupView[];
  ungroupedChannels: MyChannelView[];
};

type MyChannelGroupView = {
  id: string;
  name: string;
  type: 'TEXT' | 'VOICE';
  position: number;
  channels: MyChannelView[];
};

type MyChannelView = {
  id: string;
  name: string;
  type: 'TEXT' | 'VOICE';
  topic: string | null;
  position: number;
  privateChannel: boolean;
  voiceUserLimit: number | null;
  voiceBitrate: number | null;
  role: RoleView | null;
  permissions: ChannelPermissionsView;
};

type ChannelPermissionsView = {
  canView: boolean;
  canSendMessage: boolean;
  canConnectVoice: boolean;
  canManageChannel: boolean;
  canShareScreen: boolean;
};
```

Nuances:

- Groups are ordered by `position`, then `name`.
- Channels are ordered by `position`, then `name`.
- Voice-only fields are `voiceUserLimit` and `voiceBitrate`; they should be null for text channels.
- `canSendMessage` is only true for `TEXT` channels with `message.send`.
- `canConnectVoice` and `canShareScreen` are only true for `VOICE` channels with the matching permissions.

### Query `channels`

Legacy/basic visible channel tree. It now uses the same access filtering as `myChannels`, but does not include the current user's role or computed permissions. Prefer `myChannels` for new UI work.

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
    permissions {
      permissionKey
      effect
    }
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
  permissions: RolePermissionView[];
};

type RolePermissionView = {
  permissionKey:
    | 'channel.view'
    | 'message.read'
    | 'message.send'
    | 'voice.connect'
    | 'voice.speak'
    | 'screen.share'
    | 'channel.manage'
    | 'role.manage'
    | 'member.manage';
  effect: 'ALLOW' | 'DENY';
};
```

Nuances:

- Roles are scoped directly to channels.
- `OWNER` and `USER` are system roles.
- Permission rows are sparse. Missing permission entry means neutral/no explicit rule.
- Roles are ordered by `position`, then `name`.

### Mutation `createChannelRole(input)`

Creates a custom role in a channel.

Rules:

- Requires effective `role.manage`.
- Creates only `CUSTOM` roles.
- Role names are unique per channel, case-insensitively.
- `colorHex` must match `#RRGGBB` when present.
- `position` must be non-negative.

Example:

```graphql
mutation CreateChannelRole($channelId: UUID!) {
  createChannelRole(
    input: {
      channelId: $channelId
      name: "Officer"
      colorHex: "#445566"
      position: 25
    }
  ) {
    id
    name
    kind
    colorHex
    position
    system
    permissions {
      permissionKey
      effect
    }
  }
}
```

### Mutation `updateChannelRole(input)`

Updates a custom role.

Rules:

- Requires effective `role.manage`.
- System roles cannot be updated.
- `colorHex: null` clears the color.

Example:

```graphql
mutation UpdateChannelRole($roleId: UUID!) {
  updateChannelRole(
    input: {
      roleId: $roleId
      name: "Officer+"
      colorHex: "#112233"
      position: 30
    }
  ) {
    id
    name
    colorHex
    position
    permissions {
      permissionKey
      effect
    }
  }
}
```

### Mutation `deleteChannelRole(roleId)`

Deletes a custom role.

Rules:

- Requires effective `role.manage`.
- System roles cannot be deleted.
- Returns `true` when the role existed and was deleted, `false` when the role id was already missing.

### Mutation `setRolePermission(input)`

Upserts or clears a role permission.

Rules:

- Requires effective `role.manage`.
- Supports only these permission keys:
  `channel.view`, `message.read`, `message.send`, `voice.connect`, `voice.speak`, `screen.share`, `channel.manage`, `role.manage`, `member.manage`
- `effect: ALLOW` or `DENY` creates/updates the explicit rule.
- Omitting `effect` or sending `null` removes the explicit rule.

Example:

```graphql
mutation SetRolePermission($roleId: UUID!) {
  setRolePermission(
    input: {
      roleId: $roleId
      permissionKey: "message.read"
      effect: ALLOW
    }
  ) {
    id
    permissions {
      permissionKey
      effect
    }
  }
}
```

### Query `channelMembers(channelId)`

Returns active members of a visible channel.

Rules:

- Requires `Authorization: Bearer <accessToken>`.
- Requires effective `channel.view` in the channel.
- Returns only active members where `leftAt` is still null.
- Roles are returned already expanded and sorted by priority.

Query:

```graphql
query ChannelMembers($channelId: UUID!) {
  channelMembers(channelId: $channelId) {
    id
    channelId
    user {
      id
      username
      displayName
      avatarMediaKey
    }
    displayName
    joinedAt
    roles {
      id
      channelId
      name
      kind
      colorHex
      position
      system
    }
  }
}
```

Shape:

```ts
type ChannelMemberView = {
  id: string;
  channelId: string;
  user: AuthUserView;
  displayName: string | null;
  joinedAt: string;
  roles: RoleView[];
};
```

### Mutation `addChannelMember(channelId, userId)`

Adds a user to the channel or reactivates a previously removed member.

Rules:

- Requires effective `member.manage`.
- The target user must exist and not be disabled.
- Automatically assigns the system `USER` role for that channel.
- Returns the active member view after the add/reactivate.

Example:

```graphql
mutation AddChannelMember($channelId: UUID!, $userId: UUID!) {
  addChannelMember(channelId: $channelId, userId: $userId) {
    id
    channelId
    user {
      id
      username
      displayName
    }
    roles {
      id
      name
      kind
    }
  }
}
```

### Mutation `removeChannelMember(channelId, userId)`

Marks a member as removed from the channel.

Rules:

- Requires effective `member.manage`.
- Clears assigned `channel_member_roles` for that member.
- Returns `true` when an active member was removed, `false` when the user was not an active member.
- Refuses to remove the channel owner.

Example:

```graphql
mutation RemoveChannelMember($channelId: UUID!, $userId: UUID!) {
  removeChannelMember(channelId: $channelId, userId: $userId)
}
```

### Mutation `assignChannelRole(channelMemberId, roleId)`

Assigns a role to an active channel member.

Rules:

- Requires effective `role.manage`.
- The member and role must belong to the same channel.
- Duplicate assignments are ignored and return the current member view.
- `OWNER` can only be assigned to the channel owner.

Example:

```graphql
mutation AssignChannelRole($channelMemberId: UUID!, $roleId: UUID!) {
  assignChannelRole(channelMemberId: $channelMemberId, roleId: $roleId) {
    id
    user {
      id
      username
    }
    roles {
      id
      name
      kind
    }
  }
}
```

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
- There is no GraphQL subscription for messages yet. Use the `/ws/events` socket for realtime fan-out, or update local cache after send.

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
    participantToken
    serverUrl
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
- Preferred LiveKit fields are `serverUrl` and `participantToken`.
- `signalingUrl` and `token` are legacy compatibility fields for the internal signaling relay.
- For new frontend work, connect the LiveKit SDK with `serverUrl` + `participantToken`.
- `canPublishAudio=true` currently maps to LiveKit microphone + camera publish sources; there is no separate webcam capability yet.

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
    participantToken
    serverUrl
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

### Mutation `leaveMediaSession(mediaSessionId)`

Marks the current user as left from a media session.

Mutation:

```graphql
mutation LeaveMediaSession($mediaSessionId: UUID!) {
  leaveMediaSession(mediaSessionId: $mediaSessionId)
}
```

Rules:

- Requires `Authorization: Bearer <accessToken>`.
- Returns `true` when the caller had an active participant row and is now marked left.
- Returns `false` when the caller was already absent or the session is already ended/missing.
- If that caller was the last active participant, the media session transitions to `ENDED`.
- When the session ends this way, `activeMediaSessions` stops returning it, event WS emits `mediaSessionEnded`, and signaling sockets for that session are closed.

### Mutation `endMediaSession(mediaSessionId)`

Explicitly ends an active media session and returns its ended view.

Mutation:

```graphql
mutation EndMediaSession($mediaSessionId: UUID!) {
  endMediaSession(mediaSessionId: $mediaSessionId) {
    id
    channelId
    type
    status
    startedAt
  }
}
```

Rules:

- Requires `Authorization: Bearer <accessToken>`.
- Allowed for the session creator or the channel owner.
- Marks all still-active participants as left.
- Sets session `status` to `ENDED`.
- Emits `mediaSessionEnded` on `/ws/events`.
- Closes active signaling sockets for that media session with reason `Media session has ended.`

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
  participantToken: string;
  serverUrl: string;
  expiresAt: string;
  canPublishAudio: boolean;
  canPublishScreen: boolean;
  canSubscribe: boolean;
};
```

Preferred usage:

- `serverUrl` + `participantToken`: LiveKit SDK path for real media.
- `signalingUrl` + `token`: legacy internal signaling relay, kept for compatibility and tests.

## Legacy Signaling WebSocket

Connect to:

```text
ws://localhost:8080/ws/signaling/{mediaSessionId}?token={mediaToken}
```

Use the `signalingUrl` returned by `startMediaSession` / `joinMediaSession` only for the legacy signaling path.

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
- When a session is ended through GraphQL, server closes signaling sockets for that session with reason `Media session has ended.`

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
- LiveKit is the default SFU for local Docker Compose and should carry RTP media traffic.
- The current backend signaling WebSocket is a legacy app-level relay, not the primary media path.

Browser/Tauri frontend notes:

- Use `navigator.mediaDevices.getUserMedia()` for microphone.
- Use `navigator.mediaDevices.getDisplayMedia()` for screen/window capture.
- Use the LiveKit React/JS SDK.
- Use `MediaJoinTicket.serverUrl`, `roomName`, `participantToken`, and capability flags to join/publish/subscribe.
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
- channel invites
- realtime text message subscriptions
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
