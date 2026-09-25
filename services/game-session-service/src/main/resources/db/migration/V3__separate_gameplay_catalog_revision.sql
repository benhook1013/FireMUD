ALTER TABLE gameplay_admission_pointer
    ADD COLUMN catalog_revision bigint NOT NULL DEFAULT 1,
    ADD CONSTRAINT gameplay_admission_pointer_catalog_revision_positive
        CHECK (catalog_revision > 0);
