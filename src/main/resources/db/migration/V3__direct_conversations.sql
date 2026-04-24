CREATE TABLE direct_conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    participant_a_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    participant_b_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT direct_conversations_users_differ_check CHECK (participant_a_user_id <> participant_b_user_id)
);

CREATE UNIQUE INDEX direct_conversations_pair_idx
    ON direct_conversations (
        (LEAST(participant_a_user_id::text, participant_b_user_id::text)),
        (GREATEST(participant_a_user_id::text, participant_b_user_id::text))
    );

CREATE INDEX direct_conversations_updated_at_idx ON direct_conversations(updated_at);

CREATE TABLE direct_conversation_members (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES direct_conversations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (conversation_id, user_id)
);

CREATE INDEX direct_conversation_members_user_id_idx ON direct_conversation_members(user_id);

CREATE TABLE direct_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES direct_conversations(id) ON DELETE CASCADE,
    author_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    edited_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT direct_messages_body_not_blank CHECK (length(btrim(body)) > 0)
);

CREATE INDEX direct_messages_conversation_created_idx ON direct_messages(conversation_id, created_at);
CREATE INDEX direct_messages_author_user_id_idx ON direct_messages(author_user_id);

CREATE TRIGGER direct_conversations_set_updated_at
BEFORE UPDATE ON direct_conversations
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER direct_messages_set_updated_at
BEFORE UPDATE ON direct_messages
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();
