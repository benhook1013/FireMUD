-- Keep the immutable intent ledger aligned with the 128-character request identity accepted by
-- the V28 game_session_canonical_initial_admission_attempt ledger and pointer ledgers.
ALTER TABLE game_session_canonical_initial_admission_intent
    ALTER COLUMN initial_admission_request_id TYPE character varying(128);

ALTER TABLE game_session_canonical_initial_admission_intent
    DROP CONSTRAINT chk_gs_initial_admission_intent_identity;

ALTER TABLE game_session_canonical_initial_admission_intent
    ADD CONSTRAINT chk_gs_initial_admission_intent_identity CHECK (
        octet_length(target_namespace) BETWEEN 1 AND 63
        AND target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
        AND char_length(initial_admission_request_id) BETWEEN 1 AND 128
        AND initial_admission_request_id !~ '^[[:space:]]*$'
        AND initial_admission_request_id !~ '[[:cntrl:]]'
        AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND realm_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND catalog_creation_request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_association_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND canonical_game_instance_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND game_session_tenant_id > 0
        AND game_instance_id > 0
        AND runtime_version_id > 0
        AND catalog_revision > 0
        AND captured_starting_row_version >= 0
        AND current_row_version >= captured_starting_row_version
        AND playable_state_scope = 'SHARED'
        AND catalog_visible IS TRUE
        AND catalog_public_production IS TRUE);
