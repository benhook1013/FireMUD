-- Correct the promotion insert guard's receipt source without adding a duplicate promotion-row
-- field. The immutable readiness plan already binds the generation receipt digest.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_jwt_signer_promotion_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    state_row RECORD;
    generation_row RECORD;
    result_row RECORD;
    plan_generation_receipt_digest VARCHAR(64);
    plan_inventory_complete BOOLEAN;
    plan_expires_at BIGINT;
    entry_count BIGINT;
    verified_count BIGINT;
BEGIN
    IF NEW.operation_action <> 'PROMOTE_PENDING'
        OR NEW.generation_operation_id IS NULL
        OR NEW.expected_cluster_incarnation_uid IS NULL
        OR NEW.expected_namespace_uid IS NULL
        OR NEW.expected_private_secret_uid IS NULL
        OR NEW.expected_public_config_map_uid IS NULL THEN
        RAISE EXCEPTION 'Promotion request lacks its exact lifecycle binding'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_promotion_insert_binding';
    END IF;

    -- Lock order is desired state, then source generation, then immutable evidence.
    SELECT * INTO state_row FROM account_jwt_signer_desired_states
     WHERE environment_id = NEW.environment_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account JWT signer desired state is missing'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_promotion_insert_binding';
    END IF;
    SELECT * INTO generation_row FROM account_jwt_signer_generation_operations
     WHERE operation_id = NEW.generation_operation_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account JWT generation operation is missing'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_promotion_insert_binding';
    END IF;
    SELECT * INTO result_row FROM account_jwt_signer_generation_results
     WHERE operation_id = NEW.generation_operation_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account JWT generation result is missing'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_promotion_insert_binding';
    END IF;
    IF state_row.generation_operation_id IS DISTINCT FROM NEW.generation_operation_id
        OR state_row.prepared_operation_id IS NOT NULL
        OR state_row.record_version IS DISTINCT FROM NEW.expected_record_version
        OR (state_row.cluster_id, state_row.kubernetes_namespace, state_row.custody_mode)
            IS DISTINCT FROM (NEW.cluster_id, NEW.kubernetes_namespace, NEW.custody_mode)
        OR (state_row.durable_active_generation, state_row.durable_active_kid)
            IS DISTINCT FROM (NEW.expected_previous_generation, NEW.expected_previous_kid)
        OR (state_row.published_active_generation, state_row.published_active_kid)
            IS DISTINCT FROM (NEW.expected_public_active_generation, NEW.expected_public_active_kid)
        OR state_row.enrollment_cluster_incarnation_uid IS DISTINCT FROM NEW.expected_cluster_incarnation_uid
        OR state_row.enrollment_namespace_uid IS DISTINCT FROM NEW.expected_namespace_uid
        OR state_row.enrollment_materializer_binding_digest IS DISTINCT FROM NEW.materializer_trust_binding_digest
        OR state_row.enrollment_materializer_config_revision IS DISTINCT FROM NEW.materializer_trust_config_revision
        OR state_row.enrollment_api_binding_digest IS DISTINCT FROM NEW.api_binding_digest
        OR state_row.enrollment_api_config_revision IS DISTINCT FROM NEW.api_config_revision
        OR state_row.enrollment_public_config_map_uid IS DISTINCT FROM NEW.expected_public_config_map_uid
        OR generation_row.operation_id IS NULL
        OR generation_row.environment_id IS DISTINCT FROM NEW.environment_id
        OR generation_row.expected_cluster_incarnation_uid IS DISTINCT FROM NEW.expected_cluster_incarnation_uid
        OR generation_row.expected_namespace_uid IS DISTINCT FROM NEW.expected_namespace_uid
        OR generation_row.trust_binding_digest IS DISTINCT FROM NEW.materializer_trust_binding_digest
        OR generation_row.trust_config_revision IS DISTINCT FROM NEW.materializer_trust_config_revision
        OR generation_row.target_generation IS DISTINCT FROM NEW.target_generation
        OR generation_row.target_kid IS DISTINCT FROM NEW.target_kid
        OR generation_row.expected_record_version + 1 IS DISTINCT FROM result_row.desired_state_version
        OR result_row.secret_uid IS DISTINCT FROM NEW.expected_private_secret_uid
        OR result_row.observed_resource_version IS DISTINCT FROM NEW.expected_private_secret_resource_version
        OR result_row.public_key_fingerprint IS DISTINCT FROM NEW.target_public_key_fingerprint THEN
        RAISE EXCEPTION 'JWT promotion request does not match current Account generation evidence'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_promotion_insert_binding';
    END IF;

    SELECT generation_receipt_digest, validator_inventory_complete, expires_at_epoch_seconds
      INTO plan_generation_receipt_digest, plan_inventory_complete, plan_expires_at
      FROM account_jwt_readiness_probe_plans
     WHERE rotation_operation_id = NEW.generation_operation_id
       AND plan_digest = NEW.readiness_plan_digest FOR SHARE;
    IF plan_generation_receipt_digest IS DISTINCT FROM result_row.receipt_digest
        OR plan_inventory_complete IS DISTINCT FROM TRUE
        OR plan_expires_at IS NULL
        OR plan_expires_at <= floor(extract(epoch FROM CURRENT_TIMESTAMP))::BIGINT THEN
        RAISE EXCEPTION 'JWT promotion requires current complete validator inventory proof'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_promotion_readiness_incomplete';
    END IF;
    SELECT count(*), count(*) FILTER (WHERE state = 'VERIFIED'
          AND verified_at_epoch_seconds < expires_at_epoch_seconds)
      INTO entry_count, verified_count
     FROM account_jwt_readiness_probe_entries
     WHERE rotation_operation_id = NEW.generation_operation_id
       AND plan_digest = NEW.readiness_plan_digest;
    IF entry_count = 0 OR verified_count <> entry_count THEN
        RAISE EXCEPTION 'JWT promotion requires every exact readiness probe to be VERIFIED'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_promotion_readiness_incomplete';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM account_jwt_jwks_prepublication_intents AS intent
        JOIN account_jwt_jwks_publication_receipts AS receipt
          ON receipt.operation_id = intent.operation_id
         AND receipt.intent_digest = intent.intent_digest
        JOIN account_jwt_jwks_mount_observations AS mount
          ON mount.operation_id = intent.operation_id
        WHERE intent.operation_id = NEW.generation_operation_id
          AND intent.intent_digest = NEW.prepublication_intent_digest
          AND receipt.receipt_digest = NEW.prepublication_receipt_digest
          AND mount.observation_digest = NEW.mounted_observation_digest
          AND intent.config_map_uid = NEW.expected_public_config_map_uid
          AND intent.api_binding_digest = NEW.api_binding_digest
          AND intent.api_config_revision = NEW.api_config_revision
          AND receipt.observed_resource_version = NEW.expected_public_jwks_resource_version
          AND intent.public_key_fingerprint = NEW.target_public_key_fingerprint
          AND intent.jwks_json = NEW.expected_public_jwks_json) THEN
        RAISE EXCEPTION 'JWT promotion request does not match exact current prepublication evidence'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_promotion_insert_binding';
    END IF;
    RETURN NEW;
END;
$$;
-- [jooq ignore stop]
