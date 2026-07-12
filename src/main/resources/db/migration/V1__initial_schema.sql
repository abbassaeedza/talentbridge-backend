CREATE EXTENSION IF NOT EXISTS "uuid-ossp" SCHEMA public;

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    role VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    phone_number VARCHAR(50),
    github_access_token TEXT,
    github_username VARCHAR(100),
    rejection_reason TEXT,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    email_verification_token VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE student_profiles (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    age INTEGER,
    university VARCHAR(255),
    year_of_study VARCHAR(50),
    major VARCHAR(255),
    past_experience TEXT,
    bio TEXT,
    linkedin_url VARCHAR(500),
    portfolio_url VARCHAR(500),
    gpa NUMERIC(4,2),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE student_skills (
    student_id UUID NOT NULL REFERENCES student_profiles(id) ON DELETE CASCADE,
    skill VARCHAR(100) NOT NULL
);

CREATE TABLE company_profiles (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    company_name VARCHAR(255) NOT NULL,
    industry VARCHAR(100),
    description TEXT,
    website VARCHAR(500),
    logo_url VARCHAR(500),
    registration_number VARCHAR(100),
    country VARCHAR(100),
    city VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE projects (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title VARCHAR(500) NOT NULL,
    description TEXT,
    scope TEXT,
    deliverables TEXT,
    evaluation_criteria TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    created_by_id UUID NOT NULL REFERENCES users(id),
    company_id UUID REFERENCES company_profiles(id),
    project_supervisor_id UUID REFERENCES users(id),
    approved_by_id UUID REFERENCES users(id),
    deadline TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE project_tools (
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    tool VARCHAR(100) NOT NULL
);

CREATE INDEX idx_project_status ON projects(status);
CREATE INDEX idx_project_company ON projects(company_id);

CREATE TABLE parties (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    leader_id UUID NOT NULL REFERENCES users(id),
    supervisor_id UUID REFERENCES users(id),
    status VARCHAR(50) NOT NULL DEFAULT 'FORMING',
    semester VARCHAR(50),
    academic_year INTEGER,
    assigned_project_id UUID REFERENCES projects(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE party_members (
    party_id UUID NOT NULL REFERENCES parties(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (party_id, user_id)
);

CREATE INDEX idx_party_leader ON parties(leader_id);
CREATE INDEX idx_party_supervisor ON parties(supervisor_id);

CREATE TABLE applications (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    party_id UUID NOT NULL REFERENCES parties(id),
    project_id UUID NOT NULL REFERENCES projects(id),
    rank_position INTEGER NOT NULL CHECK (rank_position BETWEEN 1 AND 5),
    proposal_text TEXT,
    proposal_file_url VARCHAR(500),
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    coordinator_notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_application_party_project UNIQUE (party_id, project_id)
);

CREATE INDEX idx_app_party ON applications(party_id);
CREATE INDEX idx_app_project ON applications(project_id, status);

CREATE TABLE submissions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    party_id UUID NOT NULL UNIQUE REFERENCES parties(id),
    project_id UUID NOT NULL REFERENCES projects(id),
    repo_url VARCHAR(500),
    repo_branch VARCHAR(100) DEFAULT 'main',
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    submitted_at TIMESTAMP,
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE submission_documents (
    submission_id UUID NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
    document_url VARCHAR(500) NOT NULL
);

CREATE TABLE evaluation_reports (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    submission_id UUID NOT NULL UNIQUE REFERENCES submissions(id),
    ai_detection_score NUMERIC(5,2),
    ai_detection_notes TEXT,
    code_quality_score NUMERIC(5,2),
    code_quality_notes TEXT,
    functionality_score NUMERIC(5,2),
    functionality_notes TEXT,
    scope_alignment_score NUMERIC(5,2),
    scope_alignment_notes TEXT,
    team_collaboration_score NUMERIC(5,2),
    team_collaboration_notes TEXT,
    total_score NUMERIC(5,2),
    overall_summary TEXT,
    triggered_by_id UUID REFERENCES users(id),
    evaluated_at TIMESTAMP,
    finalized BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE student_evaluation_scores (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    evaluation_report_id UUID NOT NULL REFERENCES evaluation_reports(id),
    student_id UUID NOT NULL REFERENCES users(id),
    total_commits INTEGER DEFAULT 0,
    lines_added INTEGER DEFAULT 0,
    lines_deleted INTEGER DEFAULT 0,
    contribution_percentage NUMERIC(5,2),
    individual_score NUMERIC(5,2),
    performance_notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE scorecards (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    student_id UUID NOT NULL UNIQUE REFERENCES users(id),
    average_score NUMERIC(5,2) DEFAULT 0,
    total_projects INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE scorecard_entries (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    scorecard_id UUID NOT NULL REFERENCES scorecards(id),
    project_id UUID NOT NULL REFERENCES projects(id),
    evaluation_report_id UUID REFERENCES evaluation_reports(id),
    score NUMERIC(5,2),
    semester VARCHAR(50),
    academic_year INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    recipient_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(100) NOT NULL,
    title VARCHAR(255) NOT NULL,
    message TEXT,
    read BOOLEAN NOT NULL DEFAULT FALSE,
    reference_id VARCHAR(255),
    reference_type VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notif_recipient ON notifications(recipient_id, read);

-- Seed coordinator account (password: Admin1234!)
-- INSERT INTO users (id, email, password, first_name, last_name, role, status, email_verified)
-- VALUES (
--     uuid_generate_v4(),
--     'coordinator@talentbridge.com',
--     '$2a$12$Fgh7s6aKYkEn9j.oKpf6N.0x9yCo9qK4lR3Mw4wVWFKPsXVHj8Rae',
--     'System', 'Coordinator', 'COORDINATOR', 'APPROVED', TRUE
-- );
