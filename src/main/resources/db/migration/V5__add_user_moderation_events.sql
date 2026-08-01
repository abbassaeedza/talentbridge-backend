CREATE TABLE user_moderation_events (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    normalized_email VARCHAR(255) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    coordinator_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_moderation_email_type
    ON user_moderation_events(normalized_email, event_type);
