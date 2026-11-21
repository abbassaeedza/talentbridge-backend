ALTER TABLE submissions ADD COLUMN assessment_version INTEGER NOT NULL DEFAULT 1
    CHECK (assessment_version >= 1);

CREATE TABLE questionnaire_snapshots (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    submission_id UUID NOT NULL UNIQUE REFERENCES submissions(id),
    assessment_version INTEGER NOT NULL DEFAULT 2 CHECK (assessment_version >= 1),
    state VARCHAR(40) NOT NULL CHECK (state IN
        ('GENERATING','OPEN','READY_FOR_EVALUATION','GRADING','REVIEW','FINALIZED','FAILED')),
    frozen_brief TEXT NOT NULL,
    opens_at TIMESTAMPTZ,
    closes_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (submission_id, assessment_version),
    CHECK ((opens_at IS NULL AND closes_at IS NULL AND state IN ('GENERATING','FAILED'))
        OR (opens_at IS NOT NULL AND closes_at IS NOT NULL
            AND closes_at = opens_at + INTERVAL '48 hours' AND state <> 'GENERATING'))
);

CREATE TABLE questionnaire_evidence (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    snapshot_id UUID NOT NULL UNIQUE REFERENCES questionnaire_snapshots(id),
    repository_url VARCHAR(500) NOT NULL,
    repository_branch VARCHAR(100) NOT NULL,
    commit_sha VARCHAR(40) NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    captured_at TIMESTAMPTZ NOT NULL,
    evidence JSONB NOT NULL CHECK (jsonb_typeof(evidence) = 'object'),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE questionnaire_assignments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    snapshot_id UUID NOT NULL REFERENCES questionnaire_snapshots(id),
    student_id UUID NOT NULL,
    student_name VARCHAR(255) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    answers JSONB NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(answers) = 'object'),
    saved_at TIMESTAMPTZ,
    grade_state VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (grade_state IN ('PENDING','GRADED','INCOMPLETE')),
    graded_revision BIGINT CHECK (graded_revision >= 0),
    ai_score INTEGER CHECK (ai_score BETWEEN 0 AND 100),
    effective_score INTEGER CHECK (effective_score BETWEEN 0 AND 100),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (snapshot_id, student_id),
    UNIQUE (id, snapshot_id)
);
CREATE INDEX idx_questionnaire_assignment_owner ON questionnaire_assignments(student_id, created_at DESC);

CREATE TABLE questionnaire_questions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    assignment_id UUID NOT NULL REFERENCES questionnaire_assignments(id),
    position INTEGER NOT NULL CHECK (position BETWEEN 1 AND 5),
    prompt TEXT NOT NULL CHECK (length(trim(prompt)) > 0),
    source_references JSONB NOT NULL CHECK (jsonb_typeof(source_references) = 'array'),
    hidden_rubric TEXT NOT NULL CHECK (length(trim(hidden_rubric)) > 0),
    ai_score INTEGER CHECK (ai_score BETWEEN 0 AND 20),
    effective_score INTEGER CHECK (effective_score BETWEEN 0 AND 20),
    grading_notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (assignment_id, position)
);

CREATE TABLE questionnaire_jobs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    snapshot_id UUID NOT NULL REFERENCES questionnaire_snapshots(id),
    assignment_id UUID,
    job_key VARCHAR(200) NOT NULL UNIQUE,
    kind VARCHAR(30) NOT NULL CHECK (kind IN ('EVIDENCE','GENERATE_MEMBER','EVALUATE')),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (status IN
        ('PENDING','DISPATCHED','RUNNING','DEFERRED','SUCCEEDED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    dispatch_generation INTEGER NOT NULL DEFAULT 0 CHECK (dispatch_generation >= 0),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    last_error_code VARCHAR(100),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    FOREIGN KEY (assignment_id, snapshot_id) REFERENCES questionnaire_assignments(id, snapshot_id),
    CHECK ((kind = 'GENERATE_MEMBER' AND assignment_id IS NOT NULL)
        OR (kind IN ('EVIDENCE','EVALUATE') AND assignment_id IS NULL)),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL))
);
CREATE INDEX idx_questionnaire_jobs_due ON questionnaire_jobs(status, next_attempt_at);

CREATE TABLE questionnaire_mark_adjustments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    question_id UUID NOT NULL REFERENCES questionnaire_questions(id),
    actor_id UUID NOT NULL,
    previous_score INTEGER NOT NULL CHECK (previous_score BETWEEN 0 AND 20),
    adjusted_score INTEGER NOT NULL CHECK (adjusted_score BETWEEN 0 AND 20),
    reason VARCHAR(2000) NOT NULL CHECK (length(trim(reason)) > 0),
    adjusted_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_questionnaire_adjustment_question ON questionnaire_mark_adjustments(question_id, adjusted_at);
