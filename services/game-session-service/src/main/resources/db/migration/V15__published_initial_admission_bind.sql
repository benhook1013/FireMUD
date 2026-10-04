-- Keep V9 fixture attempts readable as historical fixture evidence while allowing a new
-- production attempt to reference the exact immutable V14 published snapshot and entry.
ALTER TABLE gameplay_published_realm_catalog_snapshot
    ADD CONSTRAINT uq_gs_published_realm_catalog_snapshot_bind_identity
        UNIQUE (target_namespace, tenant_id, catalog_revision, canonical_tenant_id, version_id);

ALTER TABLE gameplay_initial_admission_bind_attempt
    ADD COLUMN catalog_source_kind character varying(16) NOT NULL DEFAULT 'V9_FIXTURE',
    ADD COLUMN fixture_catalog_realm_id uuid,
    ADD COLUMN published_target_namespace character varying(63),
    ADD COLUMN canonical_tenant_id uuid,
    ADD COLUMN game_template_id bigint,
    ADD COLUMN launch_descriptor_id character varying(128),
    ADD COLUMN release_bundle_id bigint,
    ADD COLUMN published_release_bundle_ref character varying(200),
    ADD COLUMN version_state_epoch bigint;

UPDATE gameplay_initial_admission_bind_attempt
   SET fixture_catalog_realm_id = realm_id;

ALTER TABLE gameplay_initial_admission_bind_attempt
    DROP CONSTRAINT gameplay_initial_admission_bind_attempt_catalog_fk,
    ADD CONSTRAINT gameplay_initial_admission_bind_attempt_fixture_catalog_fk
        FOREIGN KEY (tenant_id, fixture_catalog_realm_id, playable_state_namespace_id, catalog_revision)
        REFERENCES gameplay_initial_admission_bind_catalog
            (tenant_id, realm_id, playable_state_namespace_id, catalog_revision),
    ADD CONSTRAINT gameplay_initial_admission_bind_attempt_published_snapshot_fk
        FOREIGN KEY (published_target_namespace, tenant_id, catalog_revision,
                     canonical_tenant_id, version_id)
        REFERENCES gameplay_published_realm_catalog_snapshot
            (target_namespace, tenant_id, catalog_revision, canonical_tenant_id, version_id),
    ADD CONSTRAINT gameplay_initial_admission_bind_attempt_published_entry_fk
        FOREIGN KEY (published_target_namespace, tenant_id, catalog_revision, realm_id)
        REFERENCES gameplay_published_realm_catalog_entry
            (target_namespace, tenant_id, catalog_revision, realm_id),
    ADD CONSTRAINT gameplay_initial_admission_bind_attempt_catalog_source_shape
        CHECK (
            (catalog_source_kind = 'V9_FIXTURE'
                AND fixture_catalog_realm_id = realm_id
                AND published_target_namespace IS NULL
                AND canonical_tenant_id IS NULL
                AND game_template_id IS NULL
                AND launch_descriptor_id IS NULL
                AND release_bundle_id IS NULL
                AND published_release_bundle_ref IS NULL
                AND version_state_epoch IS NULL)
            OR
            (catalog_source_kind = 'V14_PUBLISHED'
                AND fixture_catalog_realm_id IS NULL
                AND published_target_namespace IS NOT NULL
                AND canonical_tenant_id IS NOT NULL
                AND game_template_id IS NOT NULL AND game_template_id > 0
                AND launch_descriptor_id IS NOT NULL
                AND release_bundle_id IS NOT NULL AND release_bundle_id > 0
                AND published_release_bundle_ref IS NOT NULL
                AND version_state_epoch IS NOT NULL AND version_state_epoch > 0
                AND playable_state_scope = 'SHARED'
                AND expected_no_prior_pointer)
        );

ALTER TABLE gameplay_initial_admission_bind_attempt
    ADD CONSTRAINT gameplay_initial_admission_bind_attempt_published_namespace
        CHECK (published_target_namespace IS NULL OR
            (octet_length(published_target_namespace) BETWEEN 1 AND 63
                AND published_target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$')),
    ADD CONSTRAINT gameplay_initial_admission_bind_attempt_published_identity
        CHECK (canonical_tenant_id IS NULL OR
            canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    ADD CONSTRAINT gameplay_initial_admission_bind_attempt_published_launch
        CHECK ((launch_descriptor_id IS NULL OR
                    char_length(launch_descriptor_id) BETWEEN 1 AND 128)
            AND (published_release_bundle_ref IS NULL OR
                    char_length(published_release_bundle_ref) BETWEEN 1 AND 200));
