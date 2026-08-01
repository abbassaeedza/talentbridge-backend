ALTER TABLE applications
    ADD CONSTRAINT uk_application_party_rank UNIQUE (party_id, rank_position);
