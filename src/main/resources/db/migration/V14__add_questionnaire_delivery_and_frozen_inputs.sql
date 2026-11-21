ALTER TABLE submissions ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0);
ALTER TABLE questionnaire_snapshots
    ADD COLUMN frozen_label VARCHAR(100) NOT NULL DEFAULT 'Project questionnaire',
    ADD COLUMN frozen_resolution JSONB CHECK (jsonb_typeof(frozen_resolution) = 'object'),
    ADD COLUMN frozen_leader_id UUID;
ALTER TABLE questionnaire_assignments ADD COLUMN github_login VARCHAR(100);
ALTER TABLE questionnaire_jobs ADD COLUMN question_ids JSONB NOT NULL DEFAULT '[]'
    CHECK (jsonb_typeof(question_ids) = 'array' AND jsonb_array_length(question_ids) IN (0,5));
ALTER TABLE questionnaire_jobs ADD COLUMN retry_of UUID REFERENCES questionnaire_jobs(id);
CREATE TABLE questionnaire_job_attempts (
    job_id UUID NOT NULL REFERENCES questionnaire_jobs(id),
    attempt INTEGER NOT NULL CHECK (attempt BETWEEN 1 AND 3),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    outcome VARCHAR(20) NOT NULL CHECK (outcome IN ('STARTED','SUCCEEDED','FAILED','UNKNOWN')),
    error_code VARCHAR(100),
    PRIMARY KEY (job_id, attempt)
);
