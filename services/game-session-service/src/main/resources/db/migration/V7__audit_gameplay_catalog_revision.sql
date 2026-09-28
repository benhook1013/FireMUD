ALTER TABLE gameplay_admission_pointer_event
    ADD COLUMN catalog_revision bigint,
    ADD CONSTRAINT gameplay_admission_pointer_event_catalog_revision_positive
        CHECK (catalog_revision IS NULL OR catalog_revision > 0);
