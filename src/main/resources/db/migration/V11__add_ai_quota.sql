CREATE TABLE ai_quota_global (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    quota_day DATE NOT NULL,
    accepted_count SMALLINT NOT NULL CHECK (accepted_count BETWEEN 0 AND 50)
);

INSERT INTO ai_quota_global (id, quota_day, accepted_count) VALUES (1, DATE '1970-01-01', 0);

CREATE TABLE ai_quota_attempts (
    lease_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    accepted_at TIMESTAMPTZ NOT NULL,
    released BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_ai_quota_attempts_user_time ON ai_quota_attempts (user_id, accepted_at);
