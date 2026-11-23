-- The retained completion and fixture policy seal identify this fixture's exact OPEN.
-- Keep the existing runtime lock, target checks and FINAL requirements for all sources.
-- [jooq ignore start]
CREATE FUNCTION gs_has_current_run_owned_fixture_binding_source(
    decision game_session_canonical_binding_transition,
    launch game_session_canonical_instance_launch,
    runtime game_instances)
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    SELECT launch.fixture_policy_request_bytes IS NOT NULL
       AND launch.original_start_session_tuple IS NULL
       AND launch.fixture_policy_seal_evidence_digest IS NOT NULL
       AND runtime.run_owned_start_request_id = launch.control_plane_request_id
       AND decision.admission_target_owner_proof_outcome = 'COMMITTED'
       AND decision.admission_target_positive_durable_abort IS FALSE
       AND EXISTS (
           SELECT 1
             FROM game_session_run_owned_fixture_runtime_completion completion
             JOIN game_session_canonical_initial_admission_attempt attempt
               ON attempt.target_namespace = completion.target_namespace
              AND attempt.initial_admission_request_id = completion.initial_admission_request_id
              AND attempt.request_digest = completion.request_digest
              AND attempt.fixture_provenance_digest = completion.fixture_provenance_digest
              AND attempt.status = 'COMMITTED'
             JOIN gameplay_admission_pointer pointer
               ON pointer.id = attempt.pointer_id
              AND pointer.representation_version = 3
              AND pointer.target_namespace = attempt.target_namespace
              AND pointer.initial_admission_request_id = attempt.initial_admission_request_id
              AND pointer.initial_admission_request_digest = attempt.request_digest
              AND pointer.pointer_version = attempt.pointer_version
              AND pointer.admission_state = 'OPEN'
             JOIN gameplay_admission_pointer_event event
               ON event.id = attempt.audit_event_id
              AND event.representation_version = 3
              AND event.target_namespace = attempt.target_namespace
              AND event.initial_admission_request_id = attempt.initial_admission_request_id
              AND event.initial_admission_request_digest = attempt.request_digest
              AND event.pointer_version = attempt.pointer_version
              AND event.admission_state = 'OPEN'
            WHERE completion.target_namespace = decision.admission_target_namespace
              AND completion.initial_admission_request_id = decision.admission_target_initial_admission_request_id
              AND completion.request_digest = decision.admission_target_initial_admission_request_digest
              AND completion.canonical_tenant_id = decision.admission_target_tenant_id
              AND completion.world_slug = decision.admission_target_world_slug
              AND completion.realm_id = decision.admission_target_realm_id
              AND completion.playable_state_namespace_id = decision.admission_target_playable_state_namespace_id
              AND completion.playable_state_scope = decision.admission_target_playable_state_scope
              AND completion.canonical_game_instance_id = decision.admission_target_canonical_game_instance_id
              AND completion.canonical_version_id = decision.admission_target_canonical_version_id
              AND completion.expected_catalog_revision = decision.admission_target_catalog_revision
              AND completion.active_lifecycle_epoch = decision.admission_target_active_world_epoch
              AND completion.origin_kind = decision.admission_target_origin_kind
              AND completion.expected_prior_pointer_version IS NOT DISTINCT FROM
                  decision.admission_target_expected_prior_pointer_version
              AND completion.hold_id = decision.admission_target_hold_id
              AND completion.hold_fence = decision.admission_target_hold_fence
              AND completion.hold_binding_digest = decision.admission_target_hold_binding_digest
              AND completion.game_session_tenant_id = runtime.tenant_id
              AND completion.game_instance_id = runtime.id
              AND completion.control_plane_request_id = launch.control_plane_request_id
              AND completion.captured_starting_row_version = launch.captured_starting_row_version
              AND completion.completed_running_row_version = runtime.row_version
              AND completion.fixture_provenance_digest = launch.fixture_policy_seal_evidence_digest
              AND attempt.pointer_version = decision.admission_target_pointer_version
              AND attempt.audit_event_id = decision.admission_target_audit_event_id
              AND attempt.commit_proof_digest = decision.admission_target_owner_proof_digest
              AND attempt.terminal_at = decision.admission_target_terminal_at
              AND pointer.character_creation_policy = decision.admission_target_character_creation_policy
       )
$$;

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())
        ||'.require_running_runtime_for_lease_bound_binding_decision()')::regprocedure)
        INTO STRICT definition;

    original := $needle$           (
               -- Preserve the original run-owned source exactly for its owner route.
               runtime.run_owned_start_active_epoch$needle$;
    replacement := $replacement$           gs_has_current_run_owned_fixture_binding_source(NEW, launch, runtime)
           OR (
               -- Preserve the original run-owned source exactly for its owner route.
               launch.fixture_policy_request_bytes IS NULL
               AND runtime.run_owned_start_active_epoch$replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact prior run-owned binding runtime clause'; END IF;
    definition := replace(definition,original,replacement);

    original := $needle$               NEW.admission_target_owner_proof_outcome = 'COMMITTED'
               AND NEW.admission_target_positive_durable_abort IS FALSE$needle$;
    replacement := $replacement$               launch.fixture_policy_request_bytes IS NULL
               AND NEW.admission_target_owner_proof_outcome = 'COMMITTED'
               AND NEW.admission_target_positive_durable_abort IS FALSE$replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact Human binding runtime clause'; END IF;
    definition := replace(definition,original,replacement);

    original := $needle$       AND launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'canonicalVersionId'
           = NEW.admission_target_canonical_version_id::text$needle$;
    replacement := $replacement$       AND (launch.fixture_policy_request_bytes IS NOT NULL
           OR launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'canonicalVersionId'
               = NEW.admission_target_canonical_version_id::text)$replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact binding runtime canonical version clause'; END IF;
    EXECUTE replace(definition,original,replacement);
END;
$migration$;

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1072'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1072'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1073__run_owned_fixture_binding_runtime_fence.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1073'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1072'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1073''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1072','V1072__run_owned_fixture_region_world_source_kind.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1073'',''V1073__run_owned_fixture_binding_runtime_fence.sql''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog marker'; END IF;
    EXECUTE replace(definition,original,replacement);
END;
$migration$;

INSERT INTO game_session_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT epoch,bytes,'sha256:'||encode(sha256(bytes),'hex') FROM (
    SELECT epoch,gs_runtime_family_catalog_bytes(epoch) AS bytes FROM (
        SELECT coalesce(max(catalog_epoch),0)+1 AS epoch FROM game_session_runtime_family_catalog_epoch
    ) next_epoch
) seal;
-- [jooq ignore stop]
