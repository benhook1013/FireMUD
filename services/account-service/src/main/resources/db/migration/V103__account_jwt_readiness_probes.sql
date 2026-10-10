-- Durable, operation-scoped probe plans for the interim Account-mounted JWT signer.
-- Plans bind existing desired-state, generation-result, JWKS publication, and mount evidence;
-- this migration creates no second key authority, validator endpoint, or promotion transition.
CREATE TABLE account_jwt_readiness_probe_plans (
    rotation_operation_id UUID PRIMARY KEY,
    environment_id VARCHAR(63) NOT NULL,
    cluster_id VARCHAR(128) NOT NULL,
    kubernetes_namespace VARCHAR(63) NOT NULL,
    custody_mode VARCHAR(64) NOT NULL,
    operation_digest VARCHAR(64) NOT NULL,
    generation_request_digest VARCHAR(64) NOT NULL,
    generation_receipt_digest VARCHAR(64) NOT NULL,
    desired_state_version BIGINT NOT NULL,
    expected_cluster_incarnation_uid UUID NOT NULL,
    expected_namespace_uid UUID NOT NULL,
    trust_binding_digest VARCHAR(64) NOT NULL,
    trust_config_revision VARCHAR(128) NOT NULL,
    target_generation BIGINT NOT NULL,
    target_kid VARCHAR(64) NOT NULL,
    target_public_key_fingerprint VARCHAR(64) NOT NULL,
    expected_active_generation BIGINT,
    expected_active_kid VARCHAR(64),
    expected_published_generation BIGINT,
    expected_published_kid VARCHAR(64),
    publication_intent_digest VARCHAR(64) NOT NULL,
    publication_receipt_digest VARCHAR(64) NOT NULL,
    mounted_observation_digest VARCHAR(64) NOT NULL,
    applicability_matrix_json TEXT NOT NULL,
    applicability_matrix_digest VARCHAR(64) NOT NULL,
    validator_inventory_complete BOOLEAN NOT NULL DEFAULT FALSE,
    maximum_cache_age_seconds SMALLINT NOT NULL,
    planned_at_epoch_seconds BIGINT NOT NULL,
    not_before_epoch_seconds BIGINT NOT NULL,
    expires_at_epoch_seconds BIGINT NOT NULL,
    plan_version SMALLINT NOT NULL DEFAULT 1,
    plan_digest VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_jwt_readiness_plan_binding_fk
        FOREIGN KEY (environment_id, cluster_id, kubernetes_namespace, custody_mode)
        REFERENCES account_jwt_signer_desired_states(
            environment_id, cluster_id, kubernetes_namespace, custody_mode)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_readiness_plan_operation_fk
        FOREIGN KEY (rotation_operation_id)
        REFERENCES account_jwt_signer_generation_operations(operation_id)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_readiness_plan_result_fk
        FOREIGN KEY (rotation_operation_id)
        REFERENCES account_jwt_signer_generation_results(operation_id)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_readiness_plan_publication_intent_fk
        FOREIGN KEY (rotation_operation_id, publication_intent_digest)
        REFERENCES account_jwt_jwks_prepublication_intents(operation_id, intent_digest)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_readiness_plan_publication_receipt_fk
        FOREIGN KEY (rotation_operation_id, publication_intent_digest, publication_receipt_digest)
        REFERENCES account_jwt_jwks_publication_receipts(
            operation_id, intent_digest, receipt_digest)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_readiness_plan_mount_observation_fk
        FOREIGN KEY (rotation_operation_id)
        REFERENCES account_jwt_jwks_mount_observations(operation_id)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_readiness_plan_identity_check
        CHECK (rotation_operation_id::text ~
                '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
            AND custody_mode = 'INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK'
            AND desired_state_version > 1
            AND target_generation > COALESCE(expected_active_generation, 0)
            AND ((expected_active_generation IS NULL AND expected_active_kid IS NULL
                    AND expected_published_generation IS NULL AND expected_published_kid IS NULL)
                OR (expected_active_generation IS NOT NULL AND expected_active_kid IS NOT NULL
                    AND expected_published_generation IS NOT NULL AND expected_published_kid IS NOT NULL
                    AND expected_active_generation > 0
                    AND expected_active_generation = expected_published_generation
                    AND expected_active_kid = expected_published_kid))
            AND target_kid ~ '^[A-Za-z0-9_-]{1,64}$'
            AND (expected_active_kid IS NULL OR expected_active_kid ~ '^[A-Za-z0-9_-]{1,64}$')
            AND target_kid <> expected_active_kid
            AND target_public_key_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT account_jwt_readiness_plan_digest_check
        CHECK (operation_digest ~ '^[0-9a-f]{64}$'
            AND generation_request_digest ~ '^[0-9a-f]{64}$'
            AND generation_receipt_digest ~ '^[0-9a-f]{64}$'
            AND trust_binding_digest ~ '^[0-9a-f]{64}$'
            AND publication_intent_digest ~ '^[0-9a-f]{64}$'
            AND publication_receipt_digest ~ '^[0-9a-f]{64}$'
            AND mounted_observation_digest ~ '^[0-9a-f]{64}$'
            AND applicability_matrix_digest ~ '^[0-9a-f]{64}$'
            AND plan_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT account_jwt_readiness_plan_revision_check
        CHECK (trust_config_revision ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT account_jwt_readiness_plan_trust_uid_check
        CHECK (expected_cluster_incarnation_uid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND expected_namespace_uid <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT account_jwt_readiness_plan_inventory_check
        CHECK (octet_length(applicability_matrix_json) BETWEEN 2 AND 8192),
    CONSTRAINT account_jwt_readiness_plan_window_check
        CHECK (maximum_cache_age_seconds BETWEEN 1 AND 300
            AND planned_at_epoch_seconds > 0
            AND not_before_epoch_seconds >= planned_at_epoch_seconds + maximum_cache_age_seconds
            AND expires_at_epoch_seconds > not_before_epoch_seconds
            AND expires_at_epoch_seconds - not_before_epoch_seconds <= 300
            AND plan_version = 1),
    CONSTRAINT account_jwt_readiness_plan_digest_unique
        UNIQUE (rotation_operation_id, plan_digest)
);

CREATE TABLE account_jwt_readiness_probe_entries (
    rotation_operation_id UUID NOT NULL,
    plan_digest VARCHAR(64) NOT NULL,
    validator_id VARCHAR(63) NOT NULL,
    token_profile VARCHAR(128) NOT NULL,
    audience VARCHAR(128) NOT NULL,
    probe_kind VARCHAR(16) NOT NULL,
    jti UUID NOT NULL UNIQUE,
    target_generation BIGINT NOT NULL,
    target_kid VARCHAR(64) NOT NULL,
    expected_active_generation BIGINT,
    expected_active_kid VARCHAR(64),
    registry_version SMALLINT NOT NULL DEFAULT 1,
    entry_version BIGINT NOT NULL DEFAULT 1,
    planned_issued_at_epoch_seconds BIGINT NOT NULL,
    expires_at_epoch_seconds BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PLANNED',
    terminal_outcome VARCHAR(16),
    signing_attempted_at_epoch_seconds BIGINT,
    compact_token_sha256 VARCHAR(64),
    verification_receipt_version SMALLINT,
    verification_source_entry_version BIGINT,
    verified_at_epoch_seconds BIGINT,
    verified_kid VARCHAR(64),
    validator_instance_id VARCHAR(128),
    validator_binding_digest VARCHAR(64),
    validator_config_revision VARCHAR(128),
    validator_peer_uri VARCHAR(256),
    validator_peer_spki_sha256 VARCHAR(64),
    verification_receipt_sha256 VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (rotation_operation_id, validator_id, token_profile, audience, probe_kind),
    CONSTRAINT account_jwt_readiness_probe_plan_fk
        FOREIGN KEY (rotation_operation_id, plan_digest)
        REFERENCES account_jwt_readiness_probe_plans(rotation_operation_id, plan_digest)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_readiness_probe_jti_v4_check
        CHECK (jti::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    CONSTRAINT account_jwt_readiness_probe_identity_check
        CHECK (validator_id ~ '^[a-z0-9][a-z0-9-]{0,62}$'
            AND token_profile ~ '^[a-z0-9][a-z0-9-]{0,127}$'
            AND audience ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            AND probe_kind IN ('CANARY', 'REPRESENTATIVE')
            AND target_generation > COALESCE(expected_active_generation, 0)
            AND target_kid ~ '^[A-Za-z0-9_-]{1,64}$'
            AND ((expected_active_generation IS NULL AND expected_active_kid IS NULL)
                OR (expected_active_generation IS NOT NULL AND expected_active_kid IS NOT NULL
                    AND expected_active_generation > 0
                    AND expected_active_kid ~ '^[A-Za-z0-9_-]{1,64}$'))
            AND target_kid <> expected_active_kid),
    CONSTRAINT account_jwt_readiness_probe_state_check
        CHECK (state IN ('PLANNED', 'ISSUED', 'VERIFIED', 'RETIRED', 'CLEANED', 'EXPIRED', 'ABORTED')
            AND (terminal_outcome IS NULL OR terminal_outcome IN ('EXPIRED', 'ABORTED'))
            AND ((state IN ('EXPIRED', 'ABORTED', 'CLEANED') AND terminal_outcome IS NOT NULL)
                OR (state NOT IN ('EXPIRED', 'ABORTED', 'CLEANED') AND terminal_outcome IS NULL))),
    CONSTRAINT account_jwt_readiness_probe_evidence_check
        CHECK (plan_digest ~ '^[0-9a-f]{64}$'
            AND registry_version = 1
            AND entry_version > 0
            AND planned_issued_at_epoch_seconds > 0
            AND expires_at_epoch_seconds > planned_issued_at_epoch_seconds
            AND (compact_token_sha256 IS NULL OR compact_token_sha256 ~ '^[0-9a-f]{64}$')
            AND (signing_attempted_at_epoch_seconds IS NULL
                OR (signing_attempted_at_epoch_seconds >= planned_issued_at_epoch_seconds
                    AND signing_attempted_at_epoch_seconds < expires_at_epoch_seconds))
            AND ((compact_token_sha256 IS NULL AND signing_attempted_at_epoch_seconds IS NULL)
                OR (compact_token_sha256 IS NOT NULL AND signing_attempted_at_epoch_seconds IS NOT NULL))
            AND (state NOT IN ('ISSUED', 'VERIFIED', 'RETIRED')
                OR compact_token_sha256 IS NOT NULL)
            AND (verification_receipt_version IS NULL OR verification_receipt_version = 1)
            AND (verification_source_entry_version IS NULL OR verification_source_entry_version > 0)
            AND (verified_at_epoch_seconds IS NULL
                OR (verified_at_epoch_seconds >= planned_issued_at_epoch_seconds
                    AND verified_at_epoch_seconds < expires_at_epoch_seconds))
            AND (verified_kid IS NULL OR verified_kid = target_kid)
            AND (validator_instance_id IS NULL
                OR validator_instance_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$')
            AND (validator_binding_digest IS NULL OR validator_binding_digest ~ '^[0-9a-f]{64}$')
            AND (validator_config_revision IS NULL
                OR validator_config_revision ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$')
            AND (validator_peer_uri IS NULL
                OR validator_peer_uri ~ '^spiffe://firemud/ns/[a-z0-9-]{1,63}/sa/account-jwt-readiness-harness$')
            AND (validator_peer_spki_sha256 IS NULL
                OR validator_peer_spki_sha256 ~ '^[0-9a-f]{64}$')
            AND (verification_receipt_sha256 IS NULL
                OR verification_receipt_sha256 ~ '^[0-9a-f]{64}$')
            AND ((verification_receipt_sha256 IS NULL
                    AND verification_receipt_version IS NULL
                    AND verification_source_entry_version IS NULL
                    AND verified_at_epoch_seconds IS NULL
                    AND verified_kid IS NULL
                    AND validator_instance_id IS NULL
                    AND validator_binding_digest IS NULL
                    AND validator_config_revision IS NULL
                    AND validator_peer_uri IS NULL
                    AND validator_peer_spki_sha256 IS NULL)
                OR (verification_receipt_sha256 IS NOT NULL
                    AND verification_receipt_version = 1
                    AND verification_source_entry_version IS NOT NULL
                    AND verified_at_epoch_seconds IS NOT NULL
                    AND verified_kid IS NOT NULL
                    AND validator_instance_id IS NOT NULL
                    AND validator_binding_digest IS NOT NULL
                    AND validator_config_revision IS NOT NULL
                    AND validator_peer_uri IS NOT NULL
                    AND validator_peer_spki_sha256 IS NOT NULL))
            AND (state NOT IN ('VERIFIED', 'RETIRED') OR verification_receipt_sha256 IS NOT NULL)
            AND (state NOT IN ('PLANNED', 'ISSUED') OR verification_receipt_sha256 IS NULL))
);

-- [jooq ignore start]
CREATE FUNCTION account_jwt_readiness_plan_inventory_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    matrix jsonb;
BEGIN
    IF NEW.validator_inventory_complete THEN
        RAISE EXCEPTION 'Readiness inventory is incomplete without authenticated validator evidence'
            USING ERRCODE = '23514';
    END IF;
    matrix := NEW.applicability_matrix_json::jsonb;
    IF matrix ->> 'inventoryStatus' <> 'PARTIAL_UNCONFIRMED'
       OR matrix ->> 'schemaVersion' <> '1'
       OR jsonb_array_length(matrix -> 'validators') <> 1
       OR matrix #>> '{validators,0,validatorId}' <> 'account-service'
       OR jsonb_array_length(matrix #> '{validators,0,applicableProfiles}') <> 3
       OR NOT (matrix #> '{validators,0,applicableProfiles}' @>
          '[{"tokenProfile":"control-ui","audience":"control-ui"}]'::jsonb)
       OR NOT (matrix #> '{validators,0,applicableProfiles}' @>
          '[{"tokenProfile":"player-bootstrap","audience":"player-bootstrap"}]'::jsonb)
       OR NOT (matrix #> '{validators,0,applicableProfiles}' @>
          '[{"tokenProfile":"game-session-account-delegation","audience":"account-service"}]'::jsonb) THEN
        RAISE EXCEPTION 'Readiness applicability must bind the exact partial Account profile set'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_jwt_readiness_plan_inventory
    BEFORE INSERT ON account_jwt_readiness_probe_plans
    FOR EACH ROW EXECUTE FUNCTION account_jwt_readiness_plan_inventory_guard();

CREATE FUNCTION account_jwt_readiness_plan_immutable_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account JWT readiness probe plans are immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER account_jwt_readiness_plan_immutable
    BEFORE UPDATE OR DELETE ON account_jwt_readiness_probe_plans
    FOR EACH ROW EXECUTE FUNCTION account_jwt_readiness_plan_immutable_guard();

CREATE FUNCTION account_jwt_readiness_entry_lifecycle_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    plan_row account_jwt_readiness_probe_plans%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'PLANNED'
           OR NEW.entry_version <> 1
           OR NEW.terminal_outcome IS NOT NULL
           OR NEW.compact_token_sha256 IS NOT NULL
           OR NEW.signing_attempted_at_epoch_seconds IS NOT NULL
           OR NEW.verification_receipt_sha256 IS NOT NULL THEN
            RAISE EXCEPTION 'Account JWT readiness entries must begin PLANNED' USING ERRCODE = '23514';
        END IF;
        SELECT * INTO plan_row
        FROM account_jwt_readiness_probe_plans
        WHERE rotation_operation_id = NEW.rotation_operation_id
          AND plan_digest = NEW.plan_digest;
        IF NOT FOUND
           OR NEW.target_generation IS DISTINCT FROM plan_row.target_generation
           OR NEW.target_kid IS DISTINCT FROM plan_row.target_kid
           OR NEW.expected_active_generation IS DISTINCT FROM plan_row.expected_active_generation
           OR NEW.expected_active_kid IS DISTINCT FROM plan_row.expected_active_kid
           OR NEW.planned_issued_at_epoch_seconds IS DISTINCT FROM plan_row.not_before_epoch_seconds
           OR NEW.expires_at_epoch_seconds IS DISTINCT FROM plan_row.expires_at_epoch_seconds THEN
            RAISE EXCEPTION 'Account JWT readiness entry differs from its immutable plan'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.probe_kind = 'CANARY' THEN
            IF NEW.validator_id <> 'account-service'
               OR NEW.token_profile <> 'account-jwt-readiness-canary'
               OR NEW.audience <> 'firemud-account-jwt-readiness' THEN
                RAISE EXCEPTION 'Account JWT readiness canary shape is not fixed'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF NOT EXISTS (
            SELECT 1
            FROM jsonb_array_elements(plan_row.applicability_matrix_json::jsonb -> 'validators')
                AS validator(value)
            CROSS JOIN LATERAL jsonb_array_elements(validator.value -> 'applicableProfiles')
                AS applicable(value)
            WHERE validator.value ->> 'validatorId' = NEW.validator_id
              AND applicable.value ->> 'tokenProfile' = NEW.token_profile
              AND applicable.value ->> 'audience' = NEW.audience
        ) THEN
            RAISE EXCEPTION 'Account JWT readiness profile is outside its applicability matrix'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Account JWT readiness evidence is retained' USING ERRCODE = '23514';
    END IF;

    IF NEW.rotation_operation_id IS DISTINCT FROM OLD.rotation_operation_id
       OR NEW.plan_digest IS DISTINCT FROM OLD.plan_digest
       OR NEW.validator_id IS DISTINCT FROM OLD.validator_id
       OR NEW.token_profile IS DISTINCT FROM OLD.token_profile
       OR NEW.audience IS DISTINCT FROM OLD.audience
       OR NEW.probe_kind IS DISTINCT FROM OLD.probe_kind
       OR NEW.jti IS DISTINCT FROM OLD.jti
       OR NEW.target_generation IS DISTINCT FROM OLD.target_generation
       OR NEW.target_kid IS DISTINCT FROM OLD.target_kid
       OR NEW.expected_active_generation IS DISTINCT FROM OLD.expected_active_generation
       OR NEW.expected_active_kid IS DISTINCT FROM OLD.expected_active_kid
       OR NEW.registry_version IS DISTINCT FROM OLD.registry_version
       OR NEW.planned_issued_at_epoch_seconds IS DISTINCT FROM OLD.planned_issued_at_epoch_seconds
       OR NEW.expires_at_epoch_seconds IS DISTINCT FROM OLD.expires_at_epoch_seconds THEN
        RAISE EXCEPTION 'Account JWT readiness probe identity is immutable' USING ERRCODE = '23514';
    END IF;

    IF NEW.entry_version <> OLD.entry_version + 1 THEN
        RAISE EXCEPTION 'Account JWT readiness entry version must advance once' USING ERRCODE = '23514';
    END IF;

    IF OLD.state = 'PLANNED'
       AND NEW.state = 'PLANNED'
       AND OLD.compact_token_sha256 IS NULL
       AND NEW.compact_token_sha256 IS NOT NULL
       AND NEW.signing_attempted_at_epoch_seconds IS NOT NULL
       AND NEW.signing_attempted_at_epoch_seconds >= OLD.planned_issued_at_epoch_seconds
       AND NEW.signing_attempted_at_epoch_seconds < OLD.expires_at_epoch_seconds
       AND NEW.verification_receipt_sha256 IS NULL
       AND CURRENT_TIMESTAMP >= to_timestamp(OLD.planned_issued_at_epoch_seconds)
       AND CURRENT_TIMESTAMP < to_timestamp(OLD.expires_at_epoch_seconds)
       AND NEW.terminal_outcome IS NULL THEN
        RETURN NEW;
    END IF;

    IF OLD.state = 'PLANNED'
       AND NEW.state = 'ISSUED'
       AND OLD.compact_token_sha256 IS NOT NULL
       AND NEW.compact_token_sha256 = OLD.compact_token_sha256
       AND NEW.signing_attempted_at_epoch_seconds = OLD.signing_attempted_at_epoch_seconds
       AND NEW.verification_receipt_sha256 IS NULL
       AND CURRENT_TIMESTAMP >= to_timestamp(OLD.planned_issued_at_epoch_seconds)
       AND CURRENT_TIMESTAMP < to_timestamp(OLD.expires_at_epoch_seconds)
       AND NEW.terminal_outcome IS NULL THEN
        RETURN NEW;
    END IF;

    IF OLD.state = 'ISSUED' AND NEW.state = 'VERIFIED' THEN
        SELECT * INTO plan_row
        FROM account_jwt_readiness_probe_plans
        WHERE rotation_operation_id = NEW.rotation_operation_id
          AND plan_digest = NEW.plan_digest;
        IF NOT FOUND
           OR NEW.target_generation IS DISTINCT FROM plan_row.target_generation
           OR NEW.target_kid IS DISTINCT FROM plan_row.target_kid
           OR NEW.compact_token_sha256 IS DISTINCT FROM OLD.compact_token_sha256
           OR NEW.signing_attempted_at_epoch_seconds
                IS DISTINCT FROM OLD.signing_attempted_at_epoch_seconds
           OR NEW.verification_receipt_version IS DISTINCT FROM 1
           OR NEW.verification_source_entry_version IS DISTINCT FROM OLD.entry_version
           OR NEW.entry_version IS DISTINCT FROM OLD.entry_version + 1
           OR NEW.verified_kid IS DISTINCT FROM OLD.target_kid
           OR NEW.verified_at_epoch_seconds IS NULL
           OR NEW.verified_at_epoch_seconds < OLD.planned_issued_at_epoch_seconds
           OR NEW.verified_at_epoch_seconds >= OLD.expires_at_epoch_seconds
           OR to_timestamp(NEW.verified_at_epoch_seconds) > CURRENT_TIMESTAMP
           OR NEW.validator_id IS DISTINCT FROM 'account-service'
           OR NEW.validator_peer_uri IS DISTINCT FROM
                'spiffe://firemud/ns/' || plan_row.kubernetes_namespace || '/sa/account-jwt-readiness-harness'
           OR CURRENT_TIMESTAMP < to_timestamp(OLD.planned_issued_at_epoch_seconds)
           OR CURRENT_TIMESTAMP >= to_timestamp(OLD.expires_at_epoch_seconds)
           OR NEW.terminal_outcome IS NOT NULL THEN
            RAISE EXCEPTION 'Verified readiness probe differs from its current immutable plan'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.state IN ('PLANNED', 'ISSUED', 'VERIFIED')
       AND NEW.state = 'ABORTED'
       AND NEW.compact_token_sha256 IS NOT DISTINCT FROM OLD.compact_token_sha256
       AND NEW.signing_attempted_at_epoch_seconds IS NOT DISTINCT FROM OLD.signing_attempted_at_epoch_seconds
       AND NEW.verification_receipt_sha256 IS NOT DISTINCT FROM OLD.verification_receipt_sha256
       AND NEW.verification_receipt_version IS NOT DISTINCT FROM OLD.verification_receipt_version
       AND NEW.verification_source_entry_version IS NOT DISTINCT FROM OLD.verification_source_entry_version
       AND NEW.verified_at_epoch_seconds IS NOT DISTINCT FROM OLD.verified_at_epoch_seconds
       AND NEW.verified_kid IS NOT DISTINCT FROM OLD.verified_kid
       AND NEW.validator_instance_id IS NOT DISTINCT FROM OLD.validator_instance_id
       AND NEW.validator_binding_digest IS NOT DISTINCT FROM OLD.validator_binding_digest
       AND NEW.validator_config_revision IS NOT DISTINCT FROM OLD.validator_config_revision
       AND NEW.validator_peer_uri IS NOT DISTINCT FROM OLD.validator_peer_uri
       AND NEW.validator_peer_spki_sha256 IS NOT DISTINCT FROM OLD.validator_peer_spki_sha256
       AND NEW.terminal_outcome = 'ABORTED' THEN
        RETURN NEW;
    END IF;

    IF OLD.state IN ('PLANNED', 'ISSUED', 'VERIFIED')
       AND NEW.state = 'EXPIRED'
       AND CURRENT_TIMESTAMP >= to_timestamp(OLD.expires_at_epoch_seconds)
       AND NEW.compact_token_sha256 IS NOT DISTINCT FROM OLD.compact_token_sha256
       AND NEW.signing_attempted_at_epoch_seconds IS NOT DISTINCT FROM OLD.signing_attempted_at_epoch_seconds
       AND NEW.verification_receipt_sha256 IS NOT DISTINCT FROM OLD.verification_receipt_sha256
       AND NEW.verification_receipt_version IS NOT DISTINCT FROM OLD.verification_receipt_version
       AND NEW.verification_source_entry_version IS NOT DISTINCT FROM OLD.verification_source_entry_version
       AND NEW.verified_at_epoch_seconds IS NOT DISTINCT FROM OLD.verified_at_epoch_seconds
       AND NEW.verified_kid IS NOT DISTINCT FROM OLD.verified_kid
       AND NEW.validator_instance_id IS NOT DISTINCT FROM OLD.validator_instance_id
       AND NEW.validator_binding_digest IS NOT DISTINCT FROM OLD.validator_binding_digest
       AND NEW.validator_config_revision IS NOT DISTINCT FROM OLD.validator_config_revision
       AND NEW.validator_peer_uri IS NOT DISTINCT FROM OLD.validator_peer_uri
       AND NEW.validator_peer_spki_sha256 IS NOT DISTINCT FROM OLD.validator_peer_spki_sha256
       AND NEW.terminal_outcome = 'EXPIRED' THEN
        RETURN NEW;
    END IF;

    IF OLD.state IN ('ABORTED', 'EXPIRED')
       AND NEW.state = 'CLEANED'
       AND NEW.terminal_outcome = OLD.terminal_outcome
       AND NEW.compact_token_sha256 IS NOT DISTINCT FROM OLD.compact_token_sha256
       AND NEW.signing_attempted_at_epoch_seconds IS NOT DISTINCT FROM OLD.signing_attempted_at_epoch_seconds
       AND NEW.verification_receipt_sha256 IS NOT DISTINCT FROM OLD.verification_receipt_sha256
       AND NEW.verification_receipt_version IS NOT DISTINCT FROM OLD.verification_receipt_version
       AND NEW.verification_source_entry_version IS NOT DISTINCT FROM OLD.verification_source_entry_version
       AND NEW.verified_at_epoch_seconds IS NOT DISTINCT FROM OLD.verified_at_epoch_seconds
       AND NEW.verified_kid IS NOT DISTINCT FROM OLD.verified_kid
       AND NEW.validator_instance_id IS NOT DISTINCT FROM OLD.validator_instance_id
       AND NEW.validator_binding_digest IS NOT DISTINCT FROM OLD.validator_binding_digest
       AND NEW.validator_config_revision IS NOT DISTINCT FROM OLD.validator_config_revision
       AND NEW.validator_peer_uri IS NOT DISTINCT FROM OLD.validator_peer_uri
       AND NEW.validator_peer_spki_sha256 IS NOT DISTINCT FROM OLD.validator_peer_spki_sha256 THEN
        RETURN NEW;
    END IF;

    -- This table records bounded non-authorizing observations only. RETIRED and every promotion
    -- transition remain unavailable until their separate Account owner contracts are implemented.
    RAISE EXCEPTION 'Account JWT readiness probe lifecycle transition is unavailable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER account_jwt_readiness_entry_lifecycle
    BEFORE INSERT OR UPDATE OR DELETE ON account_jwt_readiness_probe_entries
    FOR EACH ROW EXECUTE FUNCTION account_jwt_readiness_entry_lifecycle_guard();
-- [jooq ignore end]
