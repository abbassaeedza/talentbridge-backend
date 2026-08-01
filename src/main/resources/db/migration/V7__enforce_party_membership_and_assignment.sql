DO $$
BEGIN
    IF EXISTS (
        SELECT user_id FROM party_members GROUP BY user_id HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Cannot enforce one-party-per-student: duplicate party memberships exist';
    END IF;

    IF EXISTS (
        SELECT assigned_project_id FROM parties
        WHERE assigned_project_id IS NOT NULL
        GROUP BY assigned_project_id HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Cannot enforce one-party-per-project: duplicate project assignments exist';
    END IF;
END $$;

ALTER TABLE party_members
    ADD CONSTRAINT uk_party_member_user UNIQUE (user_id);

CREATE UNIQUE INDEX uk_party_assigned_project
    ON parties (assigned_project_id)
    WHERE assigned_project_id IS NOT NULL;
