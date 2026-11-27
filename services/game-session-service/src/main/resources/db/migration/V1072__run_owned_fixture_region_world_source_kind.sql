-- Pre-v1 gate waiver (ADR 0185): the run-owned fixture world has no Human capture or region
-- graph, so its region runtime rows name an explicit RUN_OWNED_FIXTURE World source kind with no
-- capture id or graph digest instead of derived stand-in values. Its lifecycle request bytes still
-- carry the fixture provenance digest. Existing rows are HUMAN_CAPTURE and keep every Human check.
ALTER TABLE game_session_region_runtime_status
    ADD COLUMN world_source_kind character varying(24) NOT NULL DEFAULT 'HUMAN_CAPTURE';
ALTER TABLE game_session_region_runtime_status
    ALTER COLUMN capture_id DROP NOT NULL;
ALTER TABLE game_session_region_runtime_status
    ALTER COLUMN graph_sha256 DROP NOT NULL;
ALTER TABLE game_session_region_timeline_preparation
    ADD COLUMN world_source_kind character varying(24) NOT NULL DEFAULT 'HUMAN_CAPTURE';
ALTER TABLE game_session_region_timeline_preparation
    ALTER COLUMN capture_id DROP NOT NULL;
ALTER TABLE game_session_region_timeline_preparation
    ALTER COLUMN graph_sha256 DROP NOT NULL;
ALTER TABLE game_session_canonical_binding_region_bridge_obligation
    ADD COLUMN world_source_kind character varying(24) NOT NULL DEFAULT 'HUMAN_CAPTURE';

-- [jooq ignore start]
ALTER TABLE game_session_region_runtime_status
    ADD CONSTRAINT ck_game_session_region_runtime_world_source_kind CHECK (
        (world_source_kind = 'HUMAN_CAPTURE'
            AND capture_id IS NOT NULL AND graph_sha256 IS NOT NULL)
        OR (world_source_kind = 'RUN_OWNED_FIXTURE'
            AND capture_id IS NULL AND graph_sha256 IS NULL));
ALTER TABLE game_session_region_timeline_preparation
    ADD CONSTRAINT ck_game_session_region_timeline_preparation_world_source_kind CHECK (
        (world_source_kind = 'HUMAN_CAPTURE'
            AND capture_id IS NOT NULL AND graph_sha256 IS NOT NULL)
        OR (world_source_kind = 'RUN_OWNED_FIXTURE'
            AND capture_id IS NULL AND graph_sha256 IS NULL));
ALTER TABLE game_session_canonical_binding_region_bridge_obligation
    ADD CONSTRAINT ck_gs_region_bridge_obligation_world_source_kind CHECK (
        world_source_kind IN ('HUMAN_CAPTURE', 'RUN_OWNED_FIXTURE'));

ALTER TABLE game_session_canonical_binding_region_bridge_obligation
    DROP CONSTRAINT chk_gs_canonical_binding_region_bridge_obligation;

ALTER TABLE game_session_canonical_binding_region_bridge_obligation
    ADD CONSTRAINT chk_gs_canonical_binding_region_bridge_obligation CHECK (
        binding_generation > 0
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND game_instance_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND runtime_game_instance_id > 0
        AND session_id <> ''
        AND session_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        AND session_id <> '00000000-0000-0000-0000-000000000000'
        AND session_id !~ '^[[:space:]]*$'
        AND session_id !~ '[[:cntrl:]]'
        AND region_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND region_epoch > 0
        AND status IN ('REQUIRED', 'ACKNOWLEDGED', 'NEVER_DISPATCHED')
        AND inventory_revision > 0
        AND (
            (bridge_operation_id IS NULL
                AND bridge_preimage_state IS NULL
                AND runtime_entity_id IS NULL
                AND entity_projection_generation IS NULL
                AND entity_projection_fence IS NULL
                AND entity_workload_namespace IS NULL
                AND assignment_uuid IS NULL
                AND assignment_intent_digest IS NULL
                AND entity_created_origin IS NULL
                AND entity_reference_digest IS NULL
                AND world_source_digest IS NULL
                AND canonical_region_id IS NULL
                AND world_lifecycle_epoch IS NULL
                AND world_lifecycle_row_version IS NULL
                AND world_capture_id IS NULL
                AND world_graph_sha256 IS NULL
                AND world_preparation_input_digest IS NULL)
            OR
            (bridge_operation_id IS NOT NULL
                AND bridge_preimage_state IS NOT NULL
                AND bridge_preimage_state = 'ABSENT'
                AND runtime_entity_id IS NOT NULL
                AND runtime_entity_id > 0
                AND entity_projection_generation IS NOT NULL
                AND entity_projection_generation > 0
                AND entity_projection_fence IS NOT NULL
                AND entity_projection_fence > 0
                AND entity_workload_namespace IS NOT NULL
                AND entity_workload_namespace <> ''
                AND gs_original_actor_origin_valid(
                    assignment_uuid, assignment_intent_digest, entity_created_origin)
                AND entity_reference_digest IS NOT NULL
                AND entity_reference_digest ~ '^[0-9a-f]{64}$'
                AND world_source_digest IS NOT NULL
                AND world_source_digest ~ '^[0-9a-f]{64}$'
                AND canonical_region_id IS NOT NULL
                AND canonical_region_id <> '00000000-0000-0000-0000-000000000000'::uuid
                AND world_lifecycle_epoch IS NOT NULL
                AND world_lifecycle_epoch > 0
                AND world_lifecycle_row_version IS NOT NULL
                AND world_lifecycle_row_version >= 0
                AND ((world_source_kind = 'HUMAN_CAPTURE'
                        AND world_capture_id IS NOT NULL
                        AND world_capture_id <> '00000000-0000-0000-0000-000000000000'::uuid
                        AND world_graph_sha256 IS NOT NULL
                        AND world_graph_sha256 ~ '^[0-9a-f]{64}$')
                    OR (world_source_kind = 'RUN_OWNED_FIXTURE'
                        AND world_capture_id IS NULL
                        AND world_graph_sha256 IS NULL))
                AND world_preparation_input_digest IS NOT NULL
                AND world_preparation_input_digest ~ '^sha256:[0-9a-f]{64}$')
        )
        AND (
            (status IN ('REQUIRED', 'NEVER_DISPATCHED')
                AND redis_readback_digest IS NULL
                AND acknowledged_at IS NULL)
            OR
            (status = 'ACKNOWLEDGED'
                AND bridge_operation_id IS NOT NULL
                AND executor_fence IS NOT NULL
                AND executor_fence > 0
                AND lease_token_correlation IS NOT NULL
                AND lease_token_correlation ~ '^[0-9a-f]{64}$'
                AND redis_readback_digest IS NOT NULL
                AND redis_readback_digest ~ '^[0-9a-f]{64}$'
                AND acknowledged_at IS NOT NULL)
        )
        AND (
            (bridge_operation_id IS NULL
                AND executor_fence IS NULL
                AND lease_token_correlation IS NULL)
            OR
            (bridge_operation_id IS NOT NULL
                AND executor_fence IS NOT NULL
                AND executor_fence > 0
                AND lease_token_correlation IS NOT NULL
                AND lease_token_correlation ~ '^[0-9a-f]{64}$')
        )
    );

-- Extend the exact latest live definitions, failing closed on absent or repeated anchors.
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$          AND preparation.capture_id = bridge.world_capture_id
          AND preparation.graph_sha256 = bridge.world_graph_sha256$anchor$;
    replacement TEXT := $replacement$          AND preparation.world_source_kind = bridge.world_source_kind
          AND preparation.capture_id IS NOT DISTINCT FROM bridge.world_capture_id
          AND preparation.graph_sha256 IS NOT DISTINCT FROM bridge.world_graph_sha256$replacement$;
BEGIN
    SELECT pg_get_functiondef('"${flyway:defaultSchema}".gs_region_bridge_original_preparation_matches(game_session_canonical_binding_region_bridge_obligation)'::regprocedure)
        INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact V1051 region bridge preparation World source match';
    END IF;
    EXECUTE replace(original, anchor, replacement);
END;
$migration$;

DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$                   AND region_obligation.world_capture_id IS NOT NULL
                   AND region_obligation.world_capture_id <>
                       '00000000-0000-0000-0000-000000000000'::uuid
                   AND region_obligation.world_graph_sha256 ~ '^[0-9a-f]{64}$'$anchor$;
    replacement TEXT := $replacement$                   AND ((region_obligation.world_source_kind = 'HUMAN_CAPTURE'
                       AND region_obligation.world_capture_id IS NOT NULL
                       AND region_obligation.world_capture_id <>
                           '00000000-0000-0000-0000-000000000000'::uuid
                       AND region_obligation.world_graph_sha256 ~ '^[0-9a-f]{64}$')
                     OR (region_obligation.world_source_kind = 'RUN_OWNED_FIXTURE'
                       AND region_obligation.world_capture_id IS NULL
                       AND region_obligation.world_graph_sha256 IS NULL))$replacement$;
BEGIN
    SELECT pg_get_functiondef('"${flyway:defaultSchema}".require_first_new_abort_source_complete()'::regprocedure)
        INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact V1044.1 retained region bridge abort World source guard';
    END IF;
    EXECUTE replace(original, anchor, replacement);
END;
$migration$;

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1071'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1071'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1072__run_owned_fixture_region_world_source_kind.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1072'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1071'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1072''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1071','V1071__run_owned_fixture_preseeded_only_catalog.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1072'',''V1072__run_owned_fixture_region_world_source_kind.sql''';
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
