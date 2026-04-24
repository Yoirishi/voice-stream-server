CREATE TABLE user_contacts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    requester_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    addressee_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    responded_at TIMESTAMPTZ,
    blocked_at TIMESTAMPTZ,
    CONSTRAINT user_contacts_users_differ_check CHECK (requester_user_id <> addressee_user_id),
    CONSTRAINT user_contacts_status_check CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'BLOCKED'))
);

CREATE UNIQUE INDEX user_contacts_pair_idx
    ON user_contacts (
        (LEAST(requester_user_id::text, addressee_user_id::text)),
        (GREATEST(requester_user_id::text, addressee_user_id::text))
    );

CREATE INDEX user_contacts_requester_status_idx ON user_contacts(requester_user_id, status);
CREATE INDEX user_contacts_addressee_status_idx ON user_contacts(addressee_user_id, status);
CREATE INDEX user_contacts_status_updated_idx ON user_contacts(status, updated_at);

CREATE TRIGGER user_contacts_set_updated_at
BEFORE UPDATE ON user_contacts
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();
