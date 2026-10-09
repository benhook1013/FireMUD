-- One durable winner may emit the exact current operation's readiness probes to its authenticated
-- harness. A claim is a one-shot concurrency fence, not delivery proof, validator evidence, or
-- signer authority; it is never cleared after an ambiguous response.
CREATE TABLE account_jwt_readiness_delivery_claims (
    rotation_operation_id UUID PRIMARY KEY,
    plan_digest VARCHAR(64) NOT NULL,
    expected_cluster_incarnation_uid UUID NOT NULL,
    expected_namespace_uid UUID NOT NULL,
    materializer_trust_binding_digest VARCHAR(64) NOT NULL,
    materializer_trust_config_revision VARCHAR(128) NOT NULL,
    target_generation BIGINT NOT NULL,
    target_kid VARCHAR(64) NOT NULL,
    expected_active_generation BIGINT,
    expected_active_kid VARCHAR(64),
    readiness_binding_digest VARCHAR(64) NOT NULL,
    readiness_config_revision VARCHAR(128) NOT NULL,
    readiness_binding_valid_until_epoch_seconds BIGINT NOT NULL,
    validator_id VARCHAR(63) NOT NULL,
    validator_instance_id VARCHAR(128) NOT NULL,
    validator_peer_uri VARCHAR(256) NOT NULL,
    validator_peer_spki_sha256 VARCHAR(64) NOT NULL,
    claimed_at_epoch_seconds BIGINT NOT NULL,
    expires_at_epoch_seconds BIGINT NOT NULL,
    claim_digest VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_jwt_readiness_delivery_claim_plan_fk
        FOREIGN KEY (rotation_operation_id, plan_digest)
        REFERENCES account_jwt_readiness_probe_plans(rotation_operation_id, plan_digest)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_readiness_delivery_claim_shape_check
        CHECK (rotation_operation_id::text ~
                '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
            AND expected_cluster_incarnation_uid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND expected_namespace_uid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND materializer_trust_binding_digest ~ '^[0-9a-f]{64}$'
            AND materializer_trust_config_revision ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            AND target_generation > 0
            AND target_kid ~ '^[A-Za-z0-9_-]{1,64}$'
            AND ((expected_active_generation IS NULL AND expected_active_kid IS NULL)
                OR (expected_active_generation > 0
                    AND expected_active_kid ~ '^[A-Za-z0-9_-]{1,64}$'))
            AND target_kid <> expected_active_kid
            AND readiness_binding_digest ~ '^[0-9a-f]{64}$'
            AND readiness_config_revision ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            AND readiness_binding_valid_until_epoch_seconds > claimed_at_epoch_seconds
            AND validator_id = 'account-service'
            AND validator_instance_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            AND validator_peer_uri ~ '^spiffe://firemud/ns/[a-z0-9-]{1,63}/sa/account-jwt-readiness-harness$'
            AND validator_peer_spki_sha256 ~ '^[0-9a-f]{64}$'
            AND claimed_at_epoch_seconds > 0
            AND expires_at_epoch_seconds > claimed_at_epoch_seconds
            AND claim_digest ~ '^[0-9a-f]{64}$')
);

-- [jooq ignore start]
CREATE FUNCTION account_jwt_readiness_delivery_claim_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    plan_row account_jwt_readiness_probe_plans%ROWTYPE;
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
    expected_entry_count :=
        1 + jsonb_array_length(plan_row.applicability_matrix_json::jsonb #>
            '{validators,0,applicableProfiles}');
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

CREATE TRIGGER account_jwt_readiness_delivery_claim_insert_guard
    BEFORE INSERT ON account_jwt_readiness_delivery_claims
    FOR EACH ROW EXECUTE FUNCTION account_jwt_readiness_delivery_claim_guard();

CREATE FUNCTION account_jwt_readiness_delivery_claim_immutable_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account JWT readiness delivery claims are immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER account_jwt_readiness_delivery_claim_immutable
    BEFORE UPDATE OR DELETE ON account_jwt_readiness_delivery_claims
    FOR EACH ROW EXECUTE FUNCTION account_jwt_readiness_delivery_claim_immutable_guard();
-- [jooq ignore stop]
