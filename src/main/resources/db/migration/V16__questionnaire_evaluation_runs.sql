ALTER TABLE questionnaire_assignments ADD COLUMN locked_answer_revision BIGINT CHECK (locked_answer_revision >= 0);

CREATE TABLE questionnaire_evaluation_runs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    snapshot_id UUID NOT NULL REFERENCES questionnaire_snapshots(id),
    sequence INTEGER NOT NULL CHECK (sequence > 0),
    triggered_by_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(30) NOT NULL CHECK (status IN ('RUNNING','REVIEW','FAILED','FINALIZED','SUPERSEDED')),
    locked_members JSONB NOT NULL CHECK (jsonb_typeof(locked_members) = 'array'),
    incomplete_members JSONB NOT NULL CHECK (jsonb_typeof(incomplete_members) = 'array'),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (snapshot_id, sequence)
);
CREATE UNIQUE INDEX questionnaire_one_running_evaluation ON questionnaire_evaluation_runs(snapshot_id) WHERE status='RUNNING';

CREATE TABLE questionnaire_evaluation_steps (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_id UUID NOT NULL REFERENCES questionnaire_evaluation_runs(id),
    position INTEGER NOT NULL CHECK (position >= 0),
    kind VARCHAR(30) NOT NULL CHECK (kind IN ('REPOSITORY','QUESTIONNAIRE')),
    members JSONB NOT NULL CHECK (jsonb_typeof(members) = 'array'),
    result JSONB CHECK (jsonb_typeof(result) = 'object'),
    job_id UUID NOT NULL UNIQUE REFERENCES questionnaire_jobs(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (run_id,position),
    CHECK ((kind='REPOSITORY' AND position=0 AND jsonb_array_length(members)=0)
        OR (kind='QUESTIONNAIRE' AND position>0 AND jsonb_array_length(members)>0))
);

ALTER TABLE evaluation_reports
    ADD COLUMN repository_score NUMERIC CHECK (repository_score BETWEEN 0 AND 100),
    ADD COLUMN questionnaire_score NUMERIC CHECK (questionnaire_score BETWEEN 0 AND 100),
    ADD COLUMN contributor_captured_at TIMESTAMPTZ,
    ADD COLUMN contributors_truncated BOOLEAN,
    ALTER COLUMN ai_detection_score TYPE NUMERIC,
    ALTER COLUMN code_quality_score TYPE NUMERIC,
    ALTER COLUMN functionality_score TYPE NUMERIC,
    ALTER COLUMN scope_alignment_score TYPE NUMERIC,
    ALTER COLUMN team_collaboration_score TYPE NUMERIC,
    ALTER COLUMN total_score TYPE NUMERIC;
ALTER TABLE student_evaluation_scores
    ADD COLUMN frozen_student_name VARCHAR(255),
    ADD COLUMN frozen_github_login VARCHAR(100),
    ALTER COLUMN contribution_percentage TYPE NUMERIC,
    ALTER COLUMN individual_score TYPE NUMERIC;
ALTER TABLE scorecard_entries ALTER COLUMN score TYPE NUMERIC;
