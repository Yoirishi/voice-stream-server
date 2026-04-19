CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username CITEXT NOT NULL UNIQUE,
    display_name VARCHAR(80) NOT NULL,
    email CITEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    avatar_media_key TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    disabled_at TIMESTAMPTZ,
    CONSTRAINT users_username_format CHECK (username ~ '^[a-zA-Z0-9_][a-zA-Z0-9_.-]{2,31}$')
);

CREATE TABLE user_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    refresh_token_hash TEXT NOT NULL UNIQUE,
    device_name VARCHAR(120),
    user_agent TEXT,
    ip_address INET,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoke_reason TEXT
);

CREATE INDEX user_sessions_user_id_idx ON user_sessions(user_id);
CREATE INDEX user_sessions_expires_at_idx ON user_sessions(expires_at);

CREATE TABLE channel_groups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    group_type VARCHAR(16) NOT NULL,
    position INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, group_type),
    CONSTRAINT channel_groups_type_check CHECK (group_type IN ('TEXT', 'VOICE')),
    CONSTRAINT channel_groups_position_check CHECK (position >= 0)
);

CREATE INDEX channel_groups_type_position_idx ON channel_groups(group_type, position);

CREATE TABLE channels (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    group_id UUID REFERENCES channel_groups(id) ON DELETE SET NULL,
    name VARCHAR(100) NOT NULL,
    channel_type VARCHAR(16) NOT NULL,
    topic VARCHAR(512),
    position INTEGER NOT NULL DEFAULT 0,
    is_private BOOLEAN NOT NULL DEFAULT false,
    voice_user_limit INTEGER,
    voice_bitrate INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT channels_type_check CHECK (channel_type IN ('TEXT', 'VOICE')),
    CONSTRAINT channels_position_check CHECK (position >= 0),
    CONSTRAINT channels_voice_user_limit_check CHECK (voice_user_limit IS NULL OR voice_user_limit >= 0),
    CONSTRAINT channels_voice_bitrate_check CHECK (voice_bitrate IS NULL OR voice_bitrate > 0),
    CONSTRAINT channels_text_voice_settings_check CHECK (
        channel_type = 'VOICE' OR (voice_user_limit IS NULL AND voice_bitrate IS NULL)
    ),
    CONSTRAINT channels_group_type_fk FOREIGN KEY (group_id, channel_type) REFERENCES channel_groups(id, group_type) ON DELETE SET NULL (group_id)
);

CREATE INDEX channels_owner_user_id_idx ON channels(owner_user_id);
CREATE INDEX channels_group_position_idx ON channels(group_id, position);
CREATE INDEX channels_type_position_idx ON channels(channel_type, position);

CREATE TABLE channel_members (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_id UUID NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    display_name VARCHAR(80),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at TIMESTAMPTZ,
    UNIQUE (channel_id, user_id),
    UNIQUE (id, channel_id)
);

CREATE INDEX channel_members_user_id_idx ON channel_members(user_id);

CREATE TABLE permissions (
    permission_key VARCHAR(80) PRIMARY KEY,
    description TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO permissions (permission_key, description) VALUES
    ('channel.manage', 'Create, update, delete, and reorder channel groups and channels.'),
    ('member.manage', 'Invite, kick, ban, and otherwise manage channel members.'),
    ('role.manage', 'Create, update, delete, and assign channel roles.'),
    ('channel.view', 'See a channel in the client channel tree.'),
    ('message.read', 'Read text channel messages and history.'),
    ('message.send', 'Send messages to text channels.'),
    ('voice.connect', 'Join voice channels.'),
    ('voice.speak', 'Speak in voice channels.'),
    ('screen.share', 'Share a screen or stream in voice channels.');

CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_id UUID NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    role_kind VARCHAR(16) NOT NULL DEFAULT 'CUSTOM',
    color_hex CHAR(7),
    position INTEGER NOT NULL DEFAULT 0,
    is_system BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, channel_id),
    UNIQUE (channel_id, name),
    CONSTRAINT roles_kind_check CHECK (role_kind IN ('OWNER', 'USER', 'CUSTOM')),
    CONSTRAINT roles_position_check CHECK (position >= 0),
    CONSTRAINT roles_color_hex_check CHECK (color_hex IS NULL OR color_hex ~ '^#[0-9A-Fa-f]{6}$'),
    CONSTRAINT roles_system_kind_check CHECK (
        (role_kind IN ('OWNER', 'USER') AND is_system = true)
        OR (role_kind = 'CUSTOM' AND is_system = false)
    )
);

CREATE UNIQUE INDEX roles_one_owner_per_channel_idx ON roles(channel_id) WHERE role_kind = 'OWNER';
CREATE UNIQUE INDEX roles_one_user_per_channel_idx ON roles(channel_id) WHERE role_kind = 'USER';
CREATE INDEX roles_channel_position_idx ON roles(channel_id, position);

CREATE TABLE channel_member_roles (
    channel_id UUID NOT NULL,
    channel_member_id UUID NOT NULL,
    role_id UUID NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (channel_member_id, role_id),
    CONSTRAINT channel_member_roles_member_fk FOREIGN KEY (channel_member_id, channel_id) REFERENCES channel_members(id, channel_id) ON DELETE CASCADE,
    CONSTRAINT channel_member_roles_role_fk FOREIGN KEY (role_id, channel_id) REFERENCES roles(id, channel_id) ON DELETE CASCADE
);

CREATE INDEX channel_member_roles_role_id_idx ON channel_member_roles(role_id);

CREATE TABLE role_permissions (
    channel_id UUID NOT NULL,
    role_id UUID NOT NULL,
    permission_key VARCHAR(80) NOT NULL REFERENCES permissions(permission_key) ON DELETE RESTRICT,
    effect VARCHAR(8) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (role_id, permission_key),
    CONSTRAINT role_permissions_role_fk FOREIGN KEY (role_id, channel_id) REFERENCES roles(id, channel_id) ON DELETE CASCADE,
    CONSTRAINT role_permissions_effect_check CHECK (effect IN ('ALLOW', 'DENY'))
);

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER users_set_updated_at
BEFORE UPDATE ON users
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER channel_groups_set_updated_at
BEFORE UPDATE ON channel_groups
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER channels_set_updated_at
BEFORE UPDATE ON channels
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER roles_set_updated_at
BEFORE UPDATE ON roles
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();
