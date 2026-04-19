package ru.voicestream.domain

enum class ChannelType {
    TEXT,
    VOICE,
}

enum class PermissionEffect {
    ALLOW,
    DENY,
}

enum class RoleKind {
    OWNER,
    USER,
    CUSTOM,
}

enum class MediaSessionType {
    VOICE,
    SCREEN_SHARE,
}

enum class MediaSessionStatus {
    ACTIVE,
    ENDED,
}
