-- V2: Add internal name field for company projects
ALTER TABLE projects ADD COLUMN IF NOT EXISTS internal_name VARCHAR(255);
