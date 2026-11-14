-- The old ACK writer retained a concurrent intermediate PRESENT stamp with COALESCE.
-- The frozen REQUIRED operation instead owns the completed readback revision.
-- Repair only that mutable projection on still-provisional exact first-NEW rows.
-- Never rewrite the frozen operation, readback, completion, fences, or abort evidence.
-- Data-only PostgreSQL UPDATE FROM cannot be emulated by the jOOQ H2 exporter.
-- [jooq ignore start]
UPDATE game_session_canonical_binding_account_index_obligation AS obligation
SET projection_inventory_revision = repair.completed_inventory_revision
FROM game_session_canonical_account_member_repair_operation AS repair,
     game_session_canonical_binding_transition AS transition
WHERE obligation.transition_id = repair.transition_id
  AND obligation.obligation_ordinal = repair.obligation_ordinal
  AND transition.transition_id = repair.transition_id
  AND transition.status = 'PROVISIONAL'
  AND transition.expected_prior_binding_ref IS NULL
  AND transition.expected_prior_binding_generation IS NULL
  AND transition.candidate_binding_ref = repair.binding_ref
  AND transition.candidate_binding_generation = repair.binding_generation
  AND transition.candidate_account_index_fence = repair.account_index_fence
  AND transition.admission_orphan_cleanup_id IS NULL
  AND obligation.binding_ref = repair.binding_ref
  AND obligation.account_id = repair.account_id
  AND obligation.tenant_id = repair.tenant_id
  AND obligation.binding_generation = repair.binding_generation
  AND obligation.account_index_fence = repair.account_index_fence
  AND obligation.action = repair.original_action
  AND obligation.expected_prior_generation IS NULL
  AND obligation.execution_phase = repair.original_execution_phase
  AND obligation.status = 'ACKNOWLEDGED'
  AND obligation.projection_state = 'PRESENT_AWAITING_COVERAGE'
  AND obligation.projection_member = repair.member
  AND obligation.inventory_revision = transition.inventory_revision
  AND repair.status = 'ACKNOWLEDGED'
  AND repair.original_projection_state = 'REQUIRED'
  AND repair.original_projection_member IS NULL
  AND repair.original_projection_inventory_revision IS NULL
  AND repair.readback_state = 'PRESENT'
  AND repair.readback_member = repair.member
  AND repair.completed_inventory_revision > repair.inventory_snapshot_revision
  AND repair.completed_inventory_revision <= obligation.inventory_revision
  AND obligation.projection_inventory_revision > repair.inventory_snapshot_revision
  AND obligation.projection_inventory_revision < repair.completed_inventory_revision;

-- [jooq ignore stop]

-- Runtime-family catalog registration
-- [jooq ignore start]
DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1065.0.12'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1065.0.12'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.13__repair_frozen_required_member_projection.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.13'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1065.0.12'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.13''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1065.0.12','V1065.0.12__align_capacity_runtime_owner_identity.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.13'',''V1065.0.13__repair_frozen_required_member_projection.sql''';
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
