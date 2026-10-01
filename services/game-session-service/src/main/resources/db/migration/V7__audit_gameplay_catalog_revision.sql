ALTER TABLE gameplay_admission_pointer_event
    ADD COLUMN catalog_revision bigint,
    ADD COLUMN realm_id uuid,
    ADD COLUMN playable_state_namespace_id uuid,
    ADD CONSTRAINT gameplay_admission_pointer_event_catalog_revision_positive
        CHECK (catalog_revision IS NULL OR catalog_revision > 0),
    ADD CONSTRAINT gameplay_admission_pointer_event_identity_pair_complete
        CHECK ((realm_id IS NULL) = (playable_state_namespace_id IS NULL));

