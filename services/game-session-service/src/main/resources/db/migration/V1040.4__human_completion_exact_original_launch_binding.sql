-- Bind each completion row to its own exact original owner and captured launch tuple.
-- Existing Java and attempt guards remain; retained rows and prior migrations are unchanged.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION require_game_session_canonical_human_runtime_completion()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    runtime game_instances%ROWTYPE;
    launch game_session_canonical_instance_launch%ROWTYPE;
    intent game_session_canonical_initial_admission_intent%ROWTYPE;
    attempt game_session_canonical_initial_admission_attempt%ROWTYPE;
    original game_session_start_session_operator_attempt%ROWTYPE;
BEGIN
    SELECT * INTO launch FROM game_session_canonical_instance_launch
     WHERE target_namespace=NEW.target_namespace
       AND game_instance_uuid=NEW.canonical_game_instance_id;
    SELECT * INTO runtime FROM game_instances
     WHERE tenant_id=NEW.game_session_tenant_id AND id=NEW.game_instance_id;
    SELECT * INTO intent FROM game_session_canonical_initial_admission_intent
     WHERE target_namespace=NEW.target_namespace
       AND initial_admission_request_id=NEW.initial_admission_request_id;
    SELECT * INTO attempt FROM game_session_canonical_initial_admission_attempt
     WHERE target_namespace=NEW.target_namespace
       AND initial_admission_request_id=NEW.initial_admission_request_id;
    SELECT * INTO original FROM game_session_start_session_operator_attempt
     WHERE target_namespace=NEW.target_namespace
       AND owner_attempt_id=NEW.original_owner_attempt_id
       AND control_plane_request_id=NEW.control_plane_request_id;

    IF launch.target_namespace IS NULL OR runtime.id IS NULL OR intent.target_namespace IS NULL
       OR attempt.target_namespace IS NULL OR original.target_namespace IS NULL
       OR runtime.status IS DISTINCT FROM 'RUNNING'
       OR runtime.row_version IS DISTINCT FROM NEW.completed_running_row_version
       OR launch.captured_starting_row_version IS DISTINCT FROM NEW.captured_starting_row_version
       OR launch.game_session_tenant_id IS DISTINCT FROM NEW.game_session_tenant_id
       OR launch.game_instance_id IS DISTINCT FROM NEW.game_instance_id
       OR launch.control_plane_request_id IS DISTINCT FROM NEW.control_plane_request_id
       OR launch.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
       OR launch.canonical_realm_id IS DISTINCT FROM NEW.realm_id
       OR launch.world_slug IS DISTINCT FROM NEW.world_slug
       OR launch.playable_state_namespace_id IS DISTINCT FROM NEW.playable_state_namespace_id
       OR launch.playable_state_scope IS DISTINCT FROM NEW.playable_state_scope
       OR launch.launch_descriptor_id IS DISTINCT FROM NEW.launch_descriptor_id
       OR launch.complete_launch_binding_evidence->'descriptor'->>'requestDigest'
            IS DISTINCT FROM NEW.descriptor_request_digest
       OR launch.complete_launch_binding_evidence->'descriptor'->>'resultDigest'
            IS DISTINCT FROM NEW.descriptor_result_digest
       OR launch.complete_launch_binding_evidence->'releaseAttestation'->>'evidenceDigest'
            IS DISTINCT FROM NEW.release_attestation_digest
       OR intent.state IS DISTINCT FROM 'HOLD_ATTACHED'
       OR intent.request_digest IS DISTINCT FROM NEW.request_digest
       OR intent.hold_identity_bytes IS NULL
       OR intent.control_plane_request_id IS DISTINCT FROM NEW.control_plane_request_id
       OR attempt.status IS DISTINCT FROM 'PENDING'
       OR attempt.request_digest IS DISTINCT FROM NEW.request_digest
       OR attempt.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
       OR attempt.world_slug IS DISTINCT FROM NEW.world_slug
       OR attempt.realm_id IS DISTINCT FROM NEW.realm_id
       OR attempt.playable_state_namespace_id IS DISTINCT FROM NEW.playable_state_namespace_id
       OR attempt.playable_state_scope IS DISTINCT FROM NEW.playable_state_scope
       OR attempt.canonical_game_instance_id IS DISTINCT FROM NEW.canonical_game_instance_id
       OR attempt.canonical_version_id IS DISTINCT FROM NEW.canonical_version_id
       OR attempt.active_lifecycle_epoch IS DISTINCT FROM NEW.active_lifecycle_epoch
       OR attempt.expected_catalog_revision IS DISTINCT FROM NEW.expected_catalog_revision
       OR attempt.origin_kind IS DISTINCT FROM NEW.origin_kind
       OR attempt.expected_prior_pointer_version IS DISTINCT FROM NEW.expected_prior_pointer_version
       OR attempt.hold_id IS DISTINCT FROM NEW.hold_id
       OR attempt.hold_fence IS DISTINCT FROM NEW.hold_fence
       OR attempt.hold_binding_digest IS DISTINCT FROM NEW.hold_binding_digest
       OR attempt.account_protection_id IS DISTINCT FROM NEW.account_protection_id
       OR attempt.account_protection_fence IS DISTINCT FROM NEW.account_protection_fence
       OR attempt.original_owner_attempt_id IS DISTINCT FROM NEW.original_owner_attempt_id
       OR attempt.original_owner_mutation_id IS DISTINCT FROM NEW.original_owner_mutation_id
       OR attempt.original_owner_fence IS DISTINCT FROM NEW.original_owner_fence
       OR attempt.account_protection_evidence_bytes IS DISTINCT FROM NEW.account_protection_evidence_bytes
       OR original.owner_mutation_id IS DISTINCT FROM NEW.original_owner_mutation_id
       OR original.owner_fence IS DISTINCT FROM NEW.original_owner_fence
       OR original.phase_state IS DISTINCT FROM 'OWNER_EXECUTION_PENDING'
       OR original.lease_expires_at <= clock_timestamp()
       OR original.post_authorization_execution_tuple IS DISTINCT FROM NEW.original_start_session_tuple
       OR launch.original_start_session_tuple IS DISTINCT FROM NEW.original_start_session_tuple
       OR original.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
       OR original.account_redemption_projection IS DISTINCT FROM NEW.account_redemption_projection
       OR EXISTS (SELECT 1 FROM gameplay_admission_pointer p
                   WHERE p.target_namespace=NEW.target_namespace
                     AND p.initial_admission_request_id=NEW.initial_admission_request_id)
       OR EXISTS (SELECT 1 FROM gameplay_admission_pointer_event e
                   WHERE e.target_namespace=NEW.target_namespace
                     AND e.initial_admission_request_id=NEW.initial_admission_request_id)
    THEN
        RAISE EXCEPTION 'Human runtime completion requires exact pending protection, live original owner, attached hold, and RUNNING CAS'
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
-- [jooq ignore stop]
