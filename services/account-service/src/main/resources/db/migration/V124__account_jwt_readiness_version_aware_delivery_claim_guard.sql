-- Keep delivery claims compatible with retained V1 plans while requiring the complete V2
-- validator/profile registry created from the exact immutable protected inventory snapshot.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_jwt_readiness_delivery_claim_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    plan_row account_jwt_readiness_probe_plans%ROWTYPE;
    snapshot_json jsonb;
    expected_entry_count integer;
    actual_entry_count integer;
BEGIN
    SELECT * INTO plan_row
    FROM account_jwt_readiness_probe_plans
    WHERE rotation_operation_id = NEW.rotation_operation_id
      AND plan_digest = NEW.plan_digest;
    IF NOT FOUND
       OR NEW.expected_cluster_incarnation_uid IS DISTINCT FROM plan_row.expected_cluster_incarnation_uid
       OR NEW.expected_namespace_uid IS DISTINCT FROM plan_row.expected_namespace_uid
       OR NEW.materializer_trust_binding_digest IS DISTINCT FROM plan_row.trust_binding_digest
       OR NEW.materializer_trust_config_revision IS DISTINCT FROM plan_row.trust_config_revision
       OR NEW.target_generation IS DISTINCT FROM plan_row.target_generation
       OR NEW.target_kid IS DISTINCT FROM plan_row.target_kid
       OR NEW.expected_active_generation IS DISTINCT FROM plan_row.expected_active_generation
       OR NEW.expected_active_kid IS DISTINCT FROM plan_row.expected_active_kid
       OR NEW.expires_at_epoch_seconds IS DISTINCT FROM plan_row.expires_at_epoch_seconds
       OR NEW.validator_id <> 'account-service'
       OR NEW.readiness_binding_valid_until_epoch_seconds <= NEW.claimed_at_epoch_seconds
       OR NEW.validator_peer_uri IS DISTINCT FROM
            'spiffe://firemud/ns/' || plan_row.kubernetes_namespace || '/sa/account-jwt-readiness-harness'
       OR NEW.claimed_at_epoch_seconds < plan_row.not_before_epoch_seconds
       OR NEW.claimed_at_epoch_seconds >= plan_row.expires_at_epoch_seconds
       OR CURRENT_TIMESTAMP < to_timestamp(plan_row.not_before_epoch_seconds)
       OR CURRENT_TIMESTAMP >= to_timestamp(plan_row.expires_at_epoch_seconds) THEN
        RAISE EXCEPTION 'Account readiness delivery claim differs from its current exact plan'
            USING ERRCODE = '23514';
    END IF;

    IF plan_row.plan_version = 1 THEN
        expected_entry_count :=
            1 + jsonb_array_length(plan_row.applicability_matrix_json::jsonb #>
                '{validators,0,applicableProfiles}');
    ELSIF plan_row.plan_version = 2 THEN
        IF plan_row.validator_inventory_complete IS DISTINCT FROM TRUE
           OR plan_row.inventory_snapshot_digest IS NULL
           OR plan_row.applicability_matrix_json::jsonb ->> 'inventorySnapshotDigest'
                IS DISTINCT FROM plan_row.inventory_snapshot_digest THEN
            RAISE EXCEPTION 'Account readiness delivery claim requires a complete exact V2 inventory plan'
                USING ERRCODE = '23514';
        END IF;
        SELECT convert_from(canonical_snapshot, 'UTF8')::jsonb
          INTO snapshot_json
          FROM account_jwt_validator_inventory_snapshots
         WHERE snapshot_digest = plan_row.inventory_snapshot_digest;
        IF NOT FOUND
           OR snapshot_json IS NULL
           OR jsonb_typeof(snapshot_json -> 'validators') IS DISTINCT FROM 'array'
           OR jsonb_array_length(snapshot_json -> 'validators') NOT BETWEEN 1 AND 32 THEN
            RAISE EXCEPTION 'Account readiness delivery claim requires its exact V2 inventory snapshot'
                USING ERRCODE = '23514';
        END IF;
        expected_entry_count := jsonb_array_length(snapshot_json -> 'validators') * 4;
    ELSE
        RAISE EXCEPTION 'Account readiness delivery claim has an unknown plan version'
            USING ERRCODE = '23514';
    END IF;

    SELECT COUNT(*) INTO actual_entry_count
    FROM account_jwt_readiness_probe_entries
    WHERE rotation_operation_id = NEW.rotation_operation_id
      AND plan_digest = NEW.plan_digest
      AND state = 'PLANNED'
      AND compact_token_sha256 IS NULL
      AND signing_attempted_at_epoch_seconds IS NULL
      AND verification_receipt_sha256 IS NULL;
    IF actual_entry_count <> expected_entry_count THEN
        RAISE EXCEPTION 'Account readiness delivery claim requires untouched planned entries'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
-- [jooq ignore end]
