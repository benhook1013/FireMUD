-- Two exact typed provenances in the existing owners. Never backfill retained rows.
ALTER TABLE game_session_canonical_realm_catalog
    ADD COLUMN fixture_policy_request_bytes BYTEA,
    ADD COLUMN fixture_policy_seal_operation_id UUID,
    ADD COLUMN fixture_policy_seal_request_digest VARCHAR(71),
    ADD COLUMN fixture_policy_seal_evidence_digest VARCHAR(71);

ALTER TABLE game_session_canonical_realm_catalog
    ADD CONSTRAINT chk_gs_catalog_fixture_provenance CHECK (
        (fixture_policy_request_bytes IS NULL AND fixture_policy_seal_operation_id IS NULL
         AND fixture_policy_seal_request_digest IS NULL AND fixture_policy_seal_evidence_digest IS NULL)
        OR (complete_policy_set_evidence IS NULL AND fixture_policy_request_bytes IS NOT NULL
            AND octet_length(fixture_policy_request_bytes) > 0
            AND fixture_policy_seal_operation_id IS NOT NULL
            AND fixture_policy_seal_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND fixture_policy_seal_request_digest IS NOT NULL
            AND fixture_policy_seal_request_digest ~ '^sha256:[0-9a-f]{64}$'
            AND fixture_policy_seal_evidence_digest IS NOT NULL
            AND fixture_policy_seal_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
            AND visible AND public_production AND state_scope = 'SHARED'
            AND character_creation_policy = 'ALLOW_NEW'));

ALTER TABLE game_session_canonical_instance_launch
    ADD COLUMN fixture_policy_request_bytes BYTEA,
    ADD COLUMN fixture_policy_seal_operation_id UUID,
    ADD COLUMN fixture_policy_seal_request_digest VARCHAR(71),
    ADD COLUMN fixture_policy_seal_evidence_digest VARCHAR(71);

ALTER TABLE game_session_canonical_instance_launch
    DROP CONSTRAINT chk_gs_canonical_instance_launch_catalog_identity;

ALTER TABLE game_session_canonical_instance_launch
    DROP CONSTRAINT chk_gs_canonical_instance_launch_binding;

-- PostgreSQL JSONB checks are proved natively; H2 cannot execute these operators.
-- [jooq ignore start]
ALTER TABLE game_session_canonical_instance_launch
    ADD CONSTRAINT chk_gs_launch_fixture_provenance CHECK (
        (fixture_policy_request_bytes IS NULL AND fixture_policy_seal_operation_id IS NULL
         AND fixture_policy_seal_request_digest IS NULL AND fixture_policy_seal_evidence_digest IS NULL)
        OR (original_start_session_tuple IS NULL AND fixture_policy_request_bytes IS NOT NULL
            AND octet_length(fixture_policy_request_bytes) > 0
            AND fixture_policy_seal_operation_id IS NOT NULL
            AND fixture_policy_seal_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND fixture_policy_seal_request_digest IS NOT NULL
            AND fixture_policy_seal_request_digest ~ '^sha256:[0-9a-f]{64}$'
            AND fixture_policy_seal_evidence_digest IS NOT NULL
            AND fixture_policy_seal_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
            AND complete_launch_binding_evidence -> 'releaseAttestation' IS NULL));
-- [jooq ignore stop]

ALTER TABLE game_session_canonical_instance_launch
    ADD CONSTRAINT chk_gs_canonical_instance_launch_catalog_identity CHECK (
        (original_start_session_tuple IS NULL AND fixture_policy_request_bytes IS NULL
         AND catalog_creation_request_id IS NULL AND catalog_revision IS NULL
         AND catalog_source_intake_operation_id IS NULL AND catalog_source_intake_request_id IS NULL)
        OR ((original_start_session_tuple IS NOT NULL AND fixture_policy_request_bytes IS NULL
             OR original_start_session_tuple IS NULL AND fixture_policy_request_bytes IS NOT NULL)
            AND catalog_creation_request_id IS NOT NULL AND catalog_revision IS NOT NULL AND catalog_revision > 0
            AND catalog_source_intake_operation_id IS NOT NULL AND catalog_source_intake_request_id IS NOT NULL
            AND catalog_source_intake_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND catalog_source_intake_request_id <> '00000000-0000-0000-0000-000000000000'::UUID));

-- PostgreSQL JSONB checks are proved natively; H2 cannot execute these operators.
-- [jooq ignore start]
ALTER TABLE game_session_canonical_instance_launch
    ADD CONSTRAINT chk_gs_canonical_instance_launch_binding CHECK (
        char_length(launch_descriptor_id) BETWEEN 1 AND 128
        AND launch_descriptor_id !~ '^[[:space:]]*$'
        AND char_length(generation_config_revision) BETWEEN 1 AND 128
        AND generation_config_revision !~ '^[[:space:]]*$'
        AND jsonb_typeof(complete_launch_binding_evidence) = 'object'
        AND jsonb_typeof(complete_launch_binding_evidence -> 'descriptor') = 'object'
        AND ((fixture_policy_request_bytes IS NULL
              AND jsonb_typeof(complete_launch_binding_evidence -> 'releaseAttestation') = 'object')
             OR (fixture_policy_request_bytes IS NOT NULL
                 AND jsonb_typeof(complete_launch_binding_evidence -> 'fixturePolicySeal') = 'object')));
-- [jooq ignore stop]

-- The published V26 immutable-update/delete/truncate guards remain unchanged.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION require_game_session_canonical_realm_complete_policy_evidence()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.complete_policy_set_evidence IS NOT NULL
        AND octet_length(NEW.complete_policy_set_evidence) > 0
        AND NEW.fixture_policy_request_bytes IS NULL)
       OR (NEW.complete_policy_set_evidence IS NULL AND NEW.fixture_policy_request_bytes IS NOT NULL
           AND octet_length(NEW.fixture_policy_request_bytes) > 0
           AND NEW.fixture_policy_seal_operation_id IS NOT NULL
           AND NEW.fixture_policy_seal_request_digest IS NOT NULL
           AND NEW.fixture_policy_seal_evidence_digest IS NOT NULL) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'New canonical realm requires exactly one complete Human or verified fixture policy provenance'
        USING ERRCODE = '23514', CONSTRAINT = 'chk_gs_canonical_realm_catalog_complete_policy_evidence';
END;
$$;

CREATE OR REPLACE FUNCTION require_complete_canonical_launch_catalog_identity()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE catalog_row game_session_canonical_realm_catalog%ROWTYPE;
BEGIN
    IF NEW.catalog_creation_request_id IS NULL OR NEW.catalog_revision IS NULL OR NEW.catalog_revision <= 0
       OR NEW.catalog_source_intake_operation_id IS NULL OR NEW.catalog_source_intake_request_id IS NULL
       OR NEW.catalog_source_intake_operation_id = '00000000-0000-0000-0000-000000000000'::UUID
       OR NEW.catalog_source_intake_request_id = '00000000-0000-0000-0000-000000000000'::UUID
       OR NOT ((NEW.original_start_session_tuple IS NOT NULL AND NEW.fixture_policy_request_bytes IS NULL
                AND octet_length(NEW.original_start_session_tuple) BETWEEN 1 AND 262144)
               OR (NEW.original_start_session_tuple IS NULL AND NEW.fixture_policy_request_bytes IS NOT NULL
                   AND octet_length(NEW.fixture_policy_request_bytes) > 0)) THEN
        RAISE EXCEPTION 'New canonical launch requires exactly one complete Human or verified fixture provenance and exact catalog identity'
            USING ERRCODE = '23514', CONSTRAINT = 'chk_gs_canonical_instance_launch_catalog_identity';
    END IF;
    SELECT * INTO catalog_row FROM game_session_canonical_realm_catalog
      WHERE target_namespace = NEW.target_namespace AND realm_id = NEW.canonical_realm_id;
    IF NOT FOUND OR (NEW.fixture_policy_request_bytes IS NULL AND catalog_row.fixture_policy_request_bytes IS NOT NULL)
       OR (NEW.fixture_policy_request_bytes IS NOT NULL AND (
            catalog_row.complete_policy_set_evidence IS NOT NULL
            OR catalog_row.fixture_policy_request_bytes IS DISTINCT FROM NEW.fixture_policy_request_bytes
            OR catalog_row.fixture_policy_seal_operation_id IS DISTINCT FROM NEW.fixture_policy_seal_operation_id
            OR catalog_row.fixture_policy_seal_request_digest IS DISTINCT FROM NEW.fixture_policy_seal_request_digest
            OR catalog_row.fixture_policy_seal_evidence_digest IS DISTINCT FROM NEW.fixture_policy_seal_evidence_digest
            OR NEW.complete_launch_binding_evidence -> 'fixturePolicySeal' ->> 'schema'
                IS DISTINCT FROM 'firemud.run-owned-fixture-policy-seal.v1'
            OR NEW.complete_launch_binding_evidence -> 'fixturePolicySeal' ->> 'ownerService'
                IS DISTINCT FROM 'game-design-service'
            OR NEW.complete_launch_binding_evidence -> 'fixturePolicySeal' ->> 'operationId'
                IS DISTINCT FROM NEW.fixture_policy_seal_operation_id::text
            OR NEW.complete_launch_binding_evidence -> 'fixturePolicySeal' ->> 'requestDigest'
                IS DISTINCT FROM NEW.fixture_policy_seal_request_digest
            OR NEW.complete_launch_binding_evidence -> 'fixturePolicySeal' ->> 'evidenceDigest'
                IS DISTINCT FROM NEW.fixture_policy_seal_evidence_digest)) THEN
        RAISE EXCEPTION 'Launch provenance differs from the exact retained canonical catalog owner'
            USING ERRCODE = '23514', CONSTRAINT = 'chk_gs_launch_fixture_provenance';
    END IF;
    RETURN NEW;
END;
$$;
-- [jooq ignore stop]
