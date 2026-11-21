CREATE TABLE assessment_ai_quota (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    quota_day DATE NOT NULL,
    attempt_count INTEGER NOT NULL CHECK (attempt_count >= 0)
);

INSERT INTO assessment_ai_quota (id, quota_day, attempt_count)
VALUES (1, DATE '1970-01-01', 0);
