-- Restore only PREPARING->ACTIVE, PREPARING->FAILED_PRE_ACTIVATION, ACTIVE->TERMINATING and
-- TERMINATING->TERMINATED for the existing source-first lifecycle. Canonical fixture activation
-- and all Human activation-operation requirements stay closed. The authenticated
-- predecessor owner remains responsible for receipt verification, locking, expected-epoch checks,
-- hold refusal and termination coordination; this row shape is not authority.
-- Retire this staging exception forward only after old writers are drained and cleanup is settled.
-- [jooq ignore start]
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$IF (OLD.run_owned_fixture_binding_sha256 IS NOT NULL
            OR NEW.run_owned_fixture_binding_sha256 IS NOT NULL)
        AND (OLD.status IS DISTINCT FROM NEW.status
            OR OLD.lifecycle_epoch IS DISTINCT FROM NEW.lifecycle_epoch) THEN
        RAISE EXCEPTION 'World lifecycle transitions are disabled for rows carrying fixture binding evidence'
            USING ERRCODE = '23514';
    END IF;$anchor$;
    replacement TEXT := $replacement$IF (OLD.run_owned_fixture_binding_sha256 IS NOT NULL
            OR NEW.run_owned_fixture_binding_sha256 IS NOT NULL)
        AND NOT ((
            OLD.run_owned_fixture_binding_sha256 IS NOT NULL
            AND NEW.run_owned_fixture_binding_sha256 IS NOT DISTINCT FROM OLD.run_owned_fixture_binding_sha256
            AND OLD.run_owned_fixture_binding_sha256 COLLATE "C" ~ '^[0-9a-f]{64}$'
            AND OLD.run_owned_fixture_preparing_lifecycle_epoch = 1
            AND NEW.run_owned_fixture_preparing_lifecycle_epoch = OLD.run_owned_fixture_preparing_lifecycle_epoch
            AND OLD.row_version >= 0
            AND NEW.row_version = OLD.row_version + 1
            AND ROW(
                OLD.id, OLD.tenant_id, OLD.game_instance_id, OLD.game_template_id,
                OLD.control_plane_request_id, OLD.launch_descriptor_id, OLD.version_id,
                OLD.script_patch_version, OLD.runtime_flags_json, OLD.generation_config_revision,
                OLD.release_bundle_id, OLD.published_release_bundle_ref, OLD.version_state_epoch,
                OLD.remap_set_id, OLD.created_at, OLD.updated_at
            ) IS NOT DISTINCT FROM ROW(
                NEW.id, NEW.tenant_id, NEW.game_instance_id, NEW.game_template_id,
                NEW.control_plane_request_id, NEW.launch_descriptor_id, NEW.version_id,
                NEW.script_patch_version, NEW.runtime_flags_json, NEW.generation_config_revision,
                NEW.release_bundle_id, NEW.published_release_bundle_ref, NEW.version_state_epoch,
                NEW.remap_set_id, NEW.created_at, NEW.updated_at
            )
            AND OLD.canonical_game_instance_id IS NULL
            AND OLD.canonical_target_namespace IS NULL
            AND OLD.canonical_tenant_id IS NULL
            AND OLD.canonical_world_slug IS NULL
            AND OLD.playable_state_namespace_id IS NULL
            AND OLD.playable_state_scope IS NULL
            AND OLD.public_production IS NULL
            AND OLD.playtest_lifecycle_id IS NULL
            AND OLD.playtest_state_generation IS NULL
            AND OLD.canonical_launch_binding_operation_id IS NULL
            AND OLD.canonical_authority_kind IS NULL
            AND OLD.fixture_provenance_digest IS NULL
            AND OLD.canonical_realm_id IS NULL
            AND OLD.catalog_revision IS NULL
            AND OLD.game_session_tenant_id IS NULL
            AND OLD.game_session_game_instance_id IS NULL
            AND OLD.game_design_version_id IS NULL
            AND NEW.canonical_game_instance_id IS NULL
            AND NEW.canonical_target_namespace IS NULL
            AND NEW.canonical_tenant_id IS NULL
            AND NEW.canonical_world_slug IS NULL
            AND NEW.playable_state_namespace_id IS NULL
            AND NEW.playable_state_scope IS NULL
            AND NEW.public_production IS NULL
            AND NEW.playtest_lifecycle_id IS NULL
            AND NEW.playtest_state_generation IS NULL
            AND NEW.canonical_launch_binding_operation_id IS NULL
            AND NEW.canonical_authority_kind IS NULL
            AND NEW.fixture_provenance_digest IS NULL
            AND NEW.canonical_realm_id IS NULL
            AND NEW.catalog_revision IS NULL
            AND NEW.game_session_tenant_id IS NULL
            AND NEW.game_session_game_instance_id IS NULL
            AND NEW.game_design_version_id IS NULL
            AND (
                (
                    OLD.status = 'PREPARING'
                    AND OLD.lifecycle_epoch = OLD.run_owned_fixture_preparing_lifecycle_epoch
                    AND NEW.status = 'ACTIVE'
                    AND NEW.lifecycle_epoch = OLD.lifecycle_epoch + 1
                    AND OLD.failure_reason IS NULL
                    AND NEW.failure_reason IS NULL
                    AND OLD.termination_request_id IS NULL
                    AND NEW.termination_request_id IS NULL
                    AND OLD.terminated_at IS NULL
                    AND NEW.terminated_at IS NULL
                )
                OR (
                    OLD.status = 'PREPARING'
                    AND OLD.lifecycle_epoch = OLD.run_owned_fixture_preparing_lifecycle_epoch
                    AND NEW.status = 'FAILED_PRE_ACTIVATION'
                    AND NEW.lifecycle_epoch = OLD.lifecycle_epoch + 1
                    AND OLD.failure_reason IS NULL
                    AND OLD.termination_request_id IS NULL
                    AND NEW.termination_request_id IS NULL
                    AND OLD.terminated_at IS NULL
                    AND NEW.terminated_at IS NULL
                )
                OR (
                    OLD.status = 'ACTIVE'
                    AND OLD.lifecycle_epoch = OLD.run_owned_fixture_preparing_lifecycle_epoch + 1
                    AND NEW.status = 'TERMINATING'
                    AND NEW.lifecycle_epoch = OLD.lifecycle_epoch + 1
                    AND OLD.failure_reason IS NULL
                    AND OLD.termination_request_id IS NULL
                    AND NEW.termination_request_id IS NOT NULL
                    AND btrim(NEW.termination_request_id) <> ''
                    AND OLD.terminated_at IS NULL
                    AND NEW.terminated_at IS NULL
                )
                OR (
                    OLD.status = 'TERMINATING'
                    AND OLD.lifecycle_epoch = OLD.run_owned_fixture_preparing_lifecycle_epoch + 2
                    AND NEW.status = 'TERMINATED'
                    AND NEW.lifecycle_epoch = OLD.lifecycle_epoch + 1
                    AND OLD.failure_reason IS NOT DISTINCT FROM NEW.failure_reason
                    AND OLD.termination_request_id IS NOT NULL
                    AND btrim(OLD.termination_request_id) <> ''
                    AND NEW.termination_request_id IS NOT DISTINCT FROM OLD.termination_request_id
                    AND OLD.terminated_at IS NULL
                    AND NEW.terminated_at IS NOT NULL
                )
            )
        ) IS TRUE) THEN
        RAISE EXCEPTION 'World lifecycle transitions are disabled for rows carrying fixture binding evidence'
            USING ERRCODE = '23514';
    END IF;$replacement$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_guard_canonical_activation_transition()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one current fixture-binding lifecycle guard anchor';
    END IF;
    EXECUTE replace(original, anchor, replacement);
END;
$migration$;
-- [jooq ignore stop]
