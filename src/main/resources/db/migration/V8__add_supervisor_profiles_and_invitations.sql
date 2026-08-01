CREATE TABLE supervisor_profiles (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    company_id UUID REFERENCES company_profiles(id),
    job_title VARCHAR(100) NOT NULL, department VARCHAR(255) NOT NULL, bio TEXT NOT NULL,
    linkedin_url VARCHAR(500), profile_photo_url VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(), updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_supervisor_profile_company ON supervisor_profiles(company_id);

CREATE TABLE project_supervisor_invitations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    company_id UUID NOT NULL REFERENCES company_profiles(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL, token_hash VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL, expires_at TIMESTAMP NOT NULL, accepted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(), updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_project_supervisor_invitation_company ON project_supervisor_invitations(company_id);
CREATE INDEX idx_project_supervisor_invitation_email ON project_supervisor_invitations(email);
