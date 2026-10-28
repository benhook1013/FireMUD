-- Retain the original PRESEEDED assignment or the closed PLAYER_CREATED origin separately.
-- Shape validation authenticates neither Entity nor World and grants no runtime authority.
ALTER TABLE game_session_canonical_binding_region_bridge_obligation
    ADD COLUMN entity_created_origin JSONB;
ALTER TABLE game_session_region_timeline_preparation
    ADD COLUMN entity_created_origin JSONB;
ALTER TABLE game_session_region_timeline_preparation
    ALTER COLUMN assignment_uuid DROP NOT NULL;
ALTER TABLE game_session_region_timeline_preparation
    ALTER COLUMN assignment_intent_digest DROP NOT NULL;

-- [jooq ignore start]
CREATE FUNCTION gs_player_created_origin_valid(origin JSONB)
RETURNS BOOLEAN LANGUAGE SQL IMMUTABLE
SET search_path = pg_catalog
AS $$
    SELECT CASE WHEN jsonb_typeof(origin) = 'object' THEN (
        octet_length(origin::text) BETWEEN 1 AND 4096
        AND (SELECT count(*) FROM jsonb_object_keys(origin)) = 5
        AND origin ?& ARRAY['targetNamespace','creationOperationId','mutationDigest','terminalId','terminalSha256']
        AND NOT EXISTS (SELECT 1 FROM jsonb_each(origin) p WHERE jsonb_typeof(p.value) <> 'string')
        AND length(origin->>'targetNamespace') BETWEEN 1 AND 63
        AND (origin->>'targetNamespace') COLLATE "C" ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'
        AND origin->>'creationOperationId' ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        AND origin->>'terminalId' ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        AND origin->>'creationOperationId' <> '00000000-0000-0000-0000-000000000000'
        AND origin->>'terminalId' <> '00000000-0000-0000-0000-000000000000'
        AND origin->>'mutationDigest' ~ '^[0-9a-f]{64}$'
        AND origin->>'terminalSha256' ~ '^[0-9a-f]{64}$') IS TRUE
        ELSE FALSE END;
$$;
REVOKE ALL ON FUNCTION gs_player_created_origin_valid(JSONB) FROM PUBLIC;

CREATE FUNCTION gs_original_actor_origin_valid(assignment_id UUID, assignment_digest TEXT, created_origin JSONB)
RETURNS BOOLEAN LANGUAGE SQL IMMUTABLE
AS $$
    SELECT ((created_origin IS NULL
                AND assignment_id IS NOT NULL
                AND assignment_id <> '00000000-0000-0000-0000-000000000000'::uuid
                AND assignment_digest IS NOT NULL
                AND assignment_digest ~ '^[0-9a-f]{64}$')
        OR (assignment_id IS NULL AND assignment_digest IS NULL
                AND "${flyway:defaultSchema}".gs_player_created_origin_valid(created_origin))) IS TRUE;
$$;
REVOKE ALL ON FUNCTION gs_original_actor_origin_valid(UUID, TEXT, JSONB) FROM PUBLIC;

ALTER TABLE game_session_region_timeline_preparation
    ADD CONSTRAINT ck_gs_region_timeline_original_actor_origin CHECK (
        gs_original_actor_origin_valid(assignment_uuid, assignment_intent_digest, entity_created_origin));

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
        AND status IN ('REQUIRED', 'ACKNOWLEDGED')
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
                AND world_capture_id IS NOT NULL
                AND world_capture_id <> '00000000-0000-0000-0000-000000000000'::uuid
                AND world_graph_sha256 IS NOT NULL
                AND world_graph_sha256 ~ '^[0-9a-f]{64}$'
                AND world_preparation_input_digest IS NOT NULL
                AND world_preparation_input_digest ~ '^sha256:[0-9a-f]{64}$')
        )
        AND (
            (status = 'REQUIRED'
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

-- The first actor retains its exact preparation. Later distinct transitions retain their own
-- closed actor origins against that same installed region source; they never initialize a second
-- timeline or adopt the first actor's assignment/creation. A fresh bridge grant may differ from
-- the initialization grant. Protected incarnation/readback remains checked by the owner at runtime.
CREATE FUNCTION gs_region_bridge_original_preparation_matches(
    bridge game_session_canonical_binding_region_bridge_obligation)
RETURNS BOOLEAN LANGUAGE SQL STABLE
AS $$
    SELECT bridge.bridge_operation_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM "${flyway:defaultSchema}".game_session_region_timeline_preparation preparation
        JOIN "${flyway:defaultSchema}".game_session_canonical_gameplay_binding_inventory candidate
          ON candidate.binding_ref = bridge.binding_ref
         AND candidate.transition_id = bridge.transition_id
         AND candidate.binding_generation = bridge.binding_generation
         AND candidate.account_id = bridge.account_id
         AND candidate.tenant_id = bridge.tenant_id
         AND candidate.game_instance_id = bridge.game_instance_id
         AND candidate.runtime_game_instance_id = bridge.runtime_game_instance_id
         AND candidate.session_id = bridge.session_id
         AND candidate.region_id = bridge.region_id
         AND candidate.region_epoch = bridge.region_epoch
        WHERE preparation.tenant_id = bridge.tenant_id
          AND preparation.playable_state_namespace_id = candidate.playable_state_namespace_id
          AND preparation.playable_state_scope = candidate.playable_state_scope
          AND preparation.game_instance_id = bridge.game_instance_id
          AND preparation.runtime_game_instance_id = bridge.runtime_game_instance_id
          AND preparation.canonical_region_id = bridge.canonical_region_id
          AND preparation.region_id = bridge.region_id
          AND preparation.region_epoch = bridge.region_epoch
          AND preparation.lifecycle_epoch = bridge.world_lifecycle_epoch
          AND preparation.lifecycle_row_version = bridge.world_lifecycle_row_version
          AND preparation.capture_id = bridge.world_capture_id
          AND preparation.graph_sha256 = bridge.world_graph_sha256
          AND preparation.preparation_input_digest = bridge.world_preparation_input_digest
          AND "${flyway:defaultSchema}".gs_original_actor_origin_valid(
              bridge.assignment_uuid, bridge.assignment_intent_digest, bridge.entity_created_origin)
          AND preparation.phase = 'INSTALLED'
          AND (preparation.transition_id <> bridge.transition_id
              OR (preparation.binding_ref = bridge.binding_ref
                  AND preparation.binding_generation = bridge.binding_generation
                  AND preparation.account_id = bridge.account_id
                  AND preparation.character_id = candidate.character_id
                  AND preparation.session_id = bridge.session_id
                  AND preparation.world_source_digest = bridge.world_source_digest
                  AND preparation.assignment_uuid IS NOT DISTINCT FROM bridge.assignment_uuid
                  AND preparation.assignment_intent_digest IS NOT DISTINCT FROM bridge.assignment_intent_digest
                  AND preparation.entity_created_origin IS NOT DISTINCT FROM bridge.entity_created_origin)));
$$;
REVOKE ALL ON FUNCTION gs_region_bridge_original_preparation_matches(
    game_session_canonical_binding_region_bridge_obligation) FROM PUBLIC;

CREATE FUNCTION gs_guard_original_region_actor_origin() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    retain_origin BOOLEAN := FALSE;
BEGIN
    IF TG_TABLE_NAME = 'game_session_region_timeline_preparation' THEN
        retain_origin := TRUE;
    ELSE
        retain_origin := OLD.bridge_operation_id IS NOT NULL;
    END IF;
    IF retain_origin AND ROW(NEW.assignment_uuid, NEW.assignment_intent_digest, NEW.entity_created_origin)
        IS DISTINCT FROM ROW(OLD.assignment_uuid, OLD.assignment_intent_digest, OLD.entity_created_origin) THEN
        RAISE EXCEPTION 'Original region actor origin is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'gs_original_region_actor_origin_immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_region_bridge_original_actor_origin_immutable
    BEFORE UPDATE ON game_session_canonical_binding_region_bridge_obligation
    FOR EACH ROW EXECUTE FUNCTION gs_guard_original_region_actor_origin();
CREATE TRIGGER gs_region_timeline_original_actor_origin_immutable
    BEFORE UPDATE ON game_session_region_timeline_preparation
    FOR EACH ROW EXECUTE FUNCTION gs_guard_original_region_actor_origin();

-- Check final row images after the owner's existing multi-statement transaction. An untouched
-- REQUIRED obligation needs no timeline and retains all three origin columns as SQL NULL.
CREATE FUNCTION gs_require_original_region_actor_pair() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    affected_transition UUID;
    previous_transition UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN affected_transition := OLD.transition_id;
    ELSE affected_transition := NEW.transition_id;
    END IF;
    IF TG_OP = 'UPDATE' THEN previous_transition := OLD.transition_id;
    END IF;
    IF EXISTS (SELECT 1 FROM game_session_canonical_binding_region_bridge_obligation bridge
        WHERE bridge.transition_id IN (affected_transition, previous_transition)
          AND bridge.bridge_operation_id IS NOT NULL
          AND NOT gs_region_bridge_original_preparation_matches(bridge)) THEN
        RAISE EXCEPTION 'Prepared region bridge requires its exact original actor preparation'
            USING ERRCODE = '23514', CONSTRAINT = 'gs_region_bridge_original_actor_pair';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER gs_region_bridge_original_actor_pair
    AFTER INSERT OR UPDATE OR DELETE ON game_session_canonical_binding_region_bridge_obligation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION gs_require_original_region_actor_pair();
CREATE CONSTRAINT TRIGGER gs_region_timeline_original_actor_pair
    AFTER INSERT OR UPDATE OR DELETE ON game_session_region_timeline_preparation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION gs_require_original_region_actor_pair();

-- Extend exact latest live definitions, failing closed on absent or repeated source anchors.
-- Every V1044.1 abort predicate and V1050 current RUNNING/terminal/receipt guard stays present.
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$                   AND region_obligation.assignment_uuid IS NOT NULL
                   AND region_obligation.assignment_uuid <>
                       '00000000-0000-0000-0000-000000000000'::uuid
                   AND region_obligation.assignment_intent_digest ~ '^[0-9a-f]{64}$'$anchor$;
    replacement TEXT := $replacement$                   AND "${flyway:defaultSchema}".gs_original_actor_origin_valid(
                       region_obligation.assignment_uuid, region_obligation.assignment_intent_digest,
                       region_obligation.entity_created_origin)
                   AND "${flyway:defaultSchema}".gs_region_bridge_original_preparation_matches(region_obligation)$replacement$;
BEGIN
    SELECT pg_get_functiondef('"${flyway:defaultSchema}".require_first_new_abort_source_complete()'::regprocedure)
        INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact V1044.1 retained region bridge abort origin guard';
    END IF;
    EXECUTE replace(original, anchor, replacement);
END;
$migration$;

DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$    -- Initial publication and approved original-NEW final commit both retain this full current
    -- runtime/launch/Human/catalog guard. No final-receipt branch returns before the locking read.
    -- Its row lock serializes the decision with stop/replacement/deletion through commit.$anchor$;
    replacement TEXT := $replacement$    -- Consume the same retained actor origin on acquisition, retry, abort and final commit.
    IF EXISTS (SELECT 1 FROM game_session_canonical_binding_region_bridge_obligation bridge
        WHERE bridge.transition_id = NEW.transition_id
          AND bridge.bridge_operation_id IS NOT NULL
          AND (NOT "${flyway:defaultSchema}".gs_region_bridge_original_preparation_matches(bridge)
              OR (bridge.entity_created_origin IS NOT NULL
                  AND bridge.entity_created_origin->>'targetNamespace'
                      IS DISTINCT FROM NEW.admission_target_namespace)))
        OR (approved_original_new_final AND NOT EXISTS (
            SELECT 1 FROM game_session_canonical_binding_region_bridge_obligation bridge
            WHERE bridge.transition_id = NEW.transition_id
              AND bridge.binding_ref = NEW.candidate_binding_ref
              AND bridge.binding_generation = NEW.candidate_binding_generation
              AND bridge.status = 'ACKNOWLEDGED'
              AND "${flyway:defaultSchema}".gs_region_bridge_original_preparation_matches(bridge))) THEN
        RAISE EXCEPTION 'Lease-bound decision requires the exact original region actor source'
            USING ERRCODE = '23514', CONSTRAINT = 'gs_binding_runtime_original_region_actor_source';
    END IF;

    -- Initial publication and approved original-NEW final commit both retain this full current
    -- runtime/launch/Human/catalog guard. No final-receipt branch returns before the locking read.
    -- Its row lock serializes the decision with stop/replacement/deletion through commit.$replacement$;
BEGIN
    SELECT pg_get_functiondef('"${flyway:defaultSchema}".require_running_runtime_for_lease_bound_binding_decision()'::regprocedure)
        INTO STRICT original;
    IF position('approved_original_new_final boolean := false;' IN original) = 0
        OR position('AND runtime.status = ''RUNNING''' IN original) = 0
        OR (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact V1050 current runtime and original-NEW final guard';
    END IF;
    EXECUTE replace(original, anchor, replacement);
END;
$migration$;
-- [jooq ignore stop]

