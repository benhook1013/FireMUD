-- Close CHECK UNKNOWN holes without changing the retained schema-1 Human row shape.
-- Schema 1 keeps NULL realm/catalog values; Human schemas 1/2 require their binding and release
-- digest, while schemas 2/3 require a present positive catalog revision. Fixture schema 3 keeps
-- its existing no-Human-binding/no-release-digest shape.
ALTER TABLE world_canonical_instance_association
    DROP CONSTRAINT ck_world_canonical_instance_association_schema;

ALTER TABLE world_canonical_instance_association
    ADD CONSTRAINT ck_world_canonical_instance_association_schema CHECK (
        (schema_version = 1
            AND (canonical_authority_kind IS NULL OR canonical_authority_kind = 'HUMAN')
            AND canonical_launch_binding_operation_id IS NOT NULL
            AND canonical_realm_id IS NULL
            AND catalog_revision IS NULL)
        OR
        (schema_version = 2
            AND (canonical_authority_kind IS NULL OR canonical_authority_kind = 'HUMAN')
            AND canonical_launch_binding_operation_id IS NOT NULL
            AND canonical_realm_id IS NOT NULL
            AND canonical_realm_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND catalog_revision IS NOT NULL
            AND catalog_revision > 0)
        OR
        (schema_version = 3
            AND canonical_authority_kind IS NOT NULL
            AND canonical_authority_kind = 'RUN_OWNED_FIXTURE'
            AND canonical_realm_id IS NOT NULL
            AND canonical_realm_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND catalog_revision IS NOT NULL
            AND catalog_revision > 0
            AND canonical_launch_binding_operation_id IS NULL
            AND release_attestation_digest IS NULL
            AND fixture_provenance_digest IS NOT NULL
            AND fixture_provenance_digest COLLATE "C" ~ '^sha256:[0-9a-f]{64}$'
            AND game_session_tenant_id IS NOT NULL
            AND game_session_tenant_id > 0
            AND game_session_game_instance_id IS NOT NULL
            AND game_session_game_instance_id > 0
            AND game_design_version_id IS NOT NULL
            AND game_design_version_id > 0
            AND preparation_input_digest IS NOT NULL
            AND preparation_input_digest COLLATE "C" ~ '^sha256:[0-9a-f]{64}$'
            AND preparing_lifecycle_epoch IS NOT NULL
            AND preparing_lifecycle_epoch > 0
            AND game_session_association_response_bytes IS NOT NULL
            AND octet_length(game_session_association_response_bytes) > 0)
    );

ALTER TABLE world_canonical_instance_association
    DROP CONSTRAINT ck_world_canonical_instance_association_digests;

ALTER TABLE world_canonical_instance_association
    ADD CONSTRAINT ck_world_canonical_instance_association_digests CHECK (
        source_evidence_digest COLLATE "C" ~ '^sha256:[0-9a-f]{64}$'
        AND intake_request_digest COLLATE "C" ~ '^sha256:[0-9a-f]{64}$'
        AND intake_receipt_digest COLLATE "C" ~ '^sha256:[0-9a-f]{64}$'
        AND descriptor_request_digest COLLATE "C" ~ '^sha256:[0-9a-f]{64}$'
        AND descriptor_result_digest COLLATE "C" ~ '^sha256:[0-9a-f]{64}$'
        AND (
            (schema_version IN (1, 2)
                AND release_attestation_digest IS NOT NULL
                AND release_attestation_digest COLLATE "C" ~ '^sha256:[0-9a-f]{64}$')
            OR (schema_version = 3 AND release_attestation_digest IS NULL)
        )
    );
