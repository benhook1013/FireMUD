-- Keep retained bootstrap membership stable across unrelated additive reservation columns.
-- Replace only the point-membership guard; original cohort bytes and all owner guards stay intact.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION gs_require_issuer_reservation_current_membership(reservation_identity uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    reservation game_session_canonical_issuer_partition_reservation%ROWTYPE;
    anchor game_session_canonical_issuer_installed_anchor%ROWTYPE;
    original game_session_canonical_issuer_binding_local_operation%ROWTYPE;
BEGIN
    SELECT * INTO STRICT reservation
      FROM game_session_canonical_issuer_partition_reservation
     WHERE reservation_id = reservation_identity;
    SELECT * INTO anchor FROM game_session_canonical_issuer_installed_anchor
     WHERE issuer_id = reservation.issuer_id;
    IF NOT FOUND THEN
        -- Genuine initial inventory preparation precedes the first positive installed anchor.
        RETURN;
    END IF;
    SELECT * INTO original FROM game_session_canonical_issuer_binding_local_operation
     WHERE reservation_id = reservation_identity;
    IF FOUND THEN
        IF original.anchor_bootstrap_operation_id <> anchor.bootstrap_operation_id
           OR NOT gs_issuer_local_operation_has_exact_source(original.operation_id) THEN
            RAISE EXCEPTION 'Issuer reservation differs from its exact installed local-operation source'
                USING ERRCODE = '23514';
        END IF;
        IF gs_issuer_local_operation_has_exact_retirement(original.operation_id) THEN
            IF EXISTS (SELECT 1 FROM game_session_canonical_issuer_current_local_operation current
                        WHERE current.operation_id = original.operation_id) THEN
                RAISE EXCEPTION 'Retired issuer operation cannot remain in current membership'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF NOT EXISTS (
            SELECT 1 FROM game_session_canonical_issuer_current_local_operation current
             WHERE current.operation_id = original.operation_id
               AND current.issuer_id = original.issuer_id
               AND current.anchor_bootstrap_operation_id = anchor.bootstrap_operation_id
               AND current.partition_id = original.partition_id) THEN
            RAISE EXCEPTION 'Unretired issuer reservation requires its exact current local operation'
                USING ERRCODE = '23514';
        END IF;
        RETURN;
    END IF;
    -- Bootstrap source keys are reservation UUID text, unique within the exact installed build.
    -- Preserve the complete original identity, owner, fences, layout and creation timestamp.
    -- Lifecycle and inventory revision are mutable under their existing owner guards; a qualified
    -- release must not lose baseline membership merely because it advances those two columns.
    -- Compare only the explicit frozen attributes: unrelated additive columns must not change
    -- membership or require rewriting the immutable captured cohort.
    IF EXISTS (
        SELECT 1 FROM game_session_canonical_issuer_partition_build_cohort_row cohort
         WHERE cohort.build_operation_id = anchor.build_operation_id
           AND cohort.source_operation_id = anchor.bootstrap_operation_id
           AND cohort.issuer_id = anchor.issuer_id
           AND cohort.source_kind = 'RESERVATION'
           AND cohort.source_key = reservation_identity::text
           AND cohort.source_inventory_revision <= reservation.inventory_revision
           AND cohort.source_row_json::jsonb -> 'reservation_id'
               = to_jsonb(reservation.reservation_id)
           AND cohort.source_row_json::jsonb -> 'binding_ref'
               = to_jsonb(reservation.binding_ref)
           AND cohort.source_row_json::jsonb -> 'account_id'
               = to_jsonb(reservation.account_id)
           AND cohort.source_row_json::jsonb -> 'tenant_id'
               = to_jsonb(reservation.tenant_id)
           AND cohort.source_row_json::jsonb -> 'game_instance_id'
               = to_jsonb(reservation.game_instance_id)
           AND cohort.source_row_json::jsonb -> 'runtime_game_instance_id'
               = to_jsonb(reservation.runtime_game_instance_id)
           AND cohort.source_row_json::jsonb -> 'session_id'
               = to_jsonb(reservation.session_id)
           AND cohort.source_row_json::jsonb -> 'binding_generation'
               = to_jsonb(reservation.binding_generation)
           AND cohort.source_row_json::jsonb -> 'transition_id'
               = to_jsonb(reservation.transition_id)
           AND cohort.source_row_json::jsonb -> 'issuer_id'
               = to_jsonb(reservation.issuer_id)
           AND cohort.source_row_json::jsonb -> 'issuer_auth_generation'
               = to_jsonb(reservation.issuer_auth_generation)
           AND cohort.source_row_json::jsonb -> 'playable_state_namespace_id'
               = to_jsonb(reservation.playable_state_namespace_id)
           AND cohort.source_row_json::jsonb -> 'character_id'
               = to_jsonb(reservation.character_id)
           AND cohort.source_row_json::jsonb -> 'playable_state_scope'
               = to_jsonb(reservation.playable_state_scope)
           AND cohort.source_row_json::jsonb -> 'region_id'
               = to_jsonb(reservation.region_id)
           AND cohort.source_row_json::jsonb -> 'region_epoch'
               = to_jsonb(reservation.region_epoch)
           AND cohort.source_row_json::jsonb -> 'partition_id'
               = to_jsonb(reservation.partition_id)
           AND cohort.source_row_json::jsonb -> 'issuer_index_layout_version'
               = to_jsonb(reservation.issuer_index_layout_version)
           AND cohort.source_row_json::jsonb -> 'issuer_index_partition_count'
               = to_jsonb(reservation.issuer_index_partition_count)
           AND cohort.source_row_json::jsonb -> 'issuer_index_partition_capacity'
               = to_jsonb(reservation.issuer_index_partition_capacity)
           AND cohort.source_row_json::jsonb -> 'reservation_fence'
               = to_jsonb(reservation.reservation_fence)
           AND cohort.source_row_json::jsonb -> 'issuer_coverage_operation_id'
               = to_jsonb(reservation.issuer_coverage_operation_id)
           AND cohort.source_row_json::jsonb -> 'issuer_coverage_operation_fence'
               = to_jsonb(reservation.issuer_coverage_operation_fence)
           AND cohort.source_row_json::jsonb -> 'coverage_fence'
               = to_jsonb(reservation.coverage_fence)
           AND cohort.source_row_json::jsonb -> 'inventory_snapshot_revision'
               = to_jsonb(reservation.inventory_snapshot_revision)
           AND cohort.source_row_json::jsonb -> 'created_at'
               = to_jsonb(reservation.created_at)) THEN
        RETURN;
    END IF;
    RAISE EXCEPTION 'Installed issuer reservation requires exact bootstrap or current local-operation membership'
        USING ERRCODE = '23514';
END;
$$;
-- [jooq ignore stop]
