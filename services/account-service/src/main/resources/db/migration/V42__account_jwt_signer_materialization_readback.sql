-- Immutable Account-bound reports for the fixed private Secret. The first report observes only
-- the fixed-name pre-created Secret; the second records one pending-key generation/CAS result.
-- Neither report changes active state, PREPARED state, JWKS, token signing, or readiness.
CREATE TABLE account_jwt_signer_secret_observations (
    operation_id UUID PRIMARY KEY,
    environment_id VARCHAR(63) NOT NULL,
    cluster_id VARCHAR(128) NOT NULL,
    kubernetes_namespace VARCHAR(63) NOT NULL,
    custody_mode VARCHAR(64) NOT NULL,
    operation_digest_version SMALLINT NOT NULL,
    operation_digest VARCHAR(64) NOT NULL,
    expected_record_version BIGINT NOT NULL,
    expected_cluster_incarnation_uid UUID NOT NULL,
    expected_namespace_uid UUID NOT NULL,
    trust_binding_digest VARCHAR(64) NOT NULL,
    trust_config_revision VARCHAR(128) NOT NULL,
    private_secret_name VARCHAR(63) NOT NULL,
    secret_uid UUID NOT NULL,
    observed_resource_version VARCHAR(256) NOT NULL,
    observation_digest_version SMALLINT NOT NULL,
    observation_digest VARCHAR(64) NOT NULL,
    generation_request_digest VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_jwt_signer_observation_exact_state_fk
        FOREIGN KEY (environment_id, cluster_id, kubernetes_namespace, custody_mode)
        REFERENCES account_jwt_signer_desired_states(
            environment_id, cluster_id, kubernetes_namespace, custody_mode)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_signer_observation_exact_operation_fk
        FOREIGN KEY (operation_id, environment_id, cluster_id, kubernetes_namespace,
                     custody_mode, operation_digest, expected_record_version,
                     expected_cluster_incarnation_uid, expected_namespace_uid,
                     trust_binding_digest, trust_config_revision)
        REFERENCES account_jwt_signer_generation_operations(
            operation_id, environment_id, cluster_id, kubernetes_namespace, custody_mode,
            operation_digest, expected_record_version, expected_cluster_incarnation_uid,
            expected_namespace_uid, trust_binding_digest, trust_config_revision)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_signer_observation_readback_unique
        UNIQUE (operation_id, operation_digest, generation_request_digest,
                secret_uid, observed_resource_version),
    CONSTRAINT account_jwt_signer_observation_name_check
        CHECK (private_secret_name = 'jwt-signing-keys'),
    CONSTRAINT account_jwt_signer_observation_uid_check
        CHECK (expected_cluster_incarnation_uid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND expected_namespace_uid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND secret_uid <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT account_jwt_signer_observation_trust_check
        CHECK (trust_binding_digest ~ '^[0-9a-f]{64}$'
            AND trust_config_revision ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT account_jwt_signer_observation_version_check
        CHECK (expected_record_version > 0 AND operation_digest_version = 1
            AND observation_digest_version = 1),
    CONSTRAINT account_jwt_signer_observation_digest_check
        CHECK (operation_digest ~ '^[0-9a-f]{64}$'
            AND observation_digest ~ '^[0-9a-f]{64}$'
            AND generation_request_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT account_jwt_signer_observation_resource_version_check
        CHECK (observed_resource_version ~ '^[!-~]+$'
            AND char_length(observed_resource_version) BETWEEN 1 AND 256)
);

CREATE TABLE account_jwt_signer_generation_results (
    operation_id UUID PRIMARY KEY,
    environment_id VARCHAR(63) NOT NULL,
    cluster_id VARCHAR(128) NOT NULL,
    kubernetes_namespace VARCHAR(63) NOT NULL,
    custody_mode VARCHAR(64) NOT NULL,
    operation_digest VARCHAR(64) NOT NULL,
    generation_request_digest VARCHAR(64) NOT NULL,
    desired_state_version BIGINT NOT NULL,
    expected_cluster_incarnation_uid UUID NOT NULL,
    expected_namespace_uid UUID NOT NULL,
    trust_binding_digest VARCHAR(64) NOT NULL,
    trust_config_revision VARCHAR(128) NOT NULL,
    private_secret_name VARCHAR(63) NOT NULL,
    secret_uid UUID NOT NULL,
    expected_prior_resource_version VARCHAR(256) NOT NULL,
    observed_resource_version VARCHAR(256) NOT NULL,
    target_generation BIGINT NOT NULL,
    target_kid VARCHAR(64) NOT NULL,
    target_algorithm VARCHAR(16) NOT NULL,
    public_key_fingerprint VARCHAR(64) NOT NULL,
    public_jwk_json VARCHAR(8192) NOT NULL,
    receipt_digest VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_jwt_signer_generation_result_exact_state_fk
        FOREIGN KEY (environment_id, cluster_id, kubernetes_namespace, custody_mode)
        REFERENCES account_jwt_signer_desired_states(
            environment_id, cluster_id, kubernetes_namespace, custody_mode)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_signer_generation_result_exact_operation_fk
        FOREIGN KEY (operation_id, environment_id, cluster_id, kubernetes_namespace,
                     custody_mode, operation_digest,
                     target_generation, target_kid, target_algorithm,
                     expected_cluster_incarnation_uid, expected_namespace_uid,
                     trust_binding_digest, trust_config_revision)
        REFERENCES account_jwt_signer_generation_operations(
            operation_id, environment_id, cluster_id, kubernetes_namespace, custody_mode,
            operation_digest, target_generation, target_kid,
            target_algorithm, expected_cluster_incarnation_uid, expected_namespace_uid,
            trust_binding_digest, trust_config_revision)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_signer_generation_result_exact_observation_fk
        FOREIGN KEY (operation_id, operation_digest, generation_request_digest,
                     secret_uid, expected_prior_resource_version)
        REFERENCES account_jwt_signer_secret_observations(
            operation_id, operation_digest, generation_request_digest,
            secret_uid, observed_resource_version)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_signer_generation_result_name_check
        CHECK (private_secret_name = 'jwt-signing-keys'),
    CONSTRAINT account_jwt_signer_generation_result_uid_check
        CHECK (expected_cluster_incarnation_uid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND expected_namespace_uid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND secret_uid <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT account_jwt_signer_generation_result_trust_check
        CHECK (trust_binding_digest ~ '^[0-9a-f]{64}$'
            AND trust_config_revision ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT account_jwt_signer_generation_result_version_check
        CHECK (desired_state_version > 1),
    CONSTRAINT account_jwt_signer_generation_result_digest_check
        CHECK (operation_digest ~ '^[0-9a-f]{64}$'
            AND generation_request_digest ~ '^[0-9a-f]{64}$'
            AND receipt_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT account_jwt_signer_generation_result_resource_versions_check
        CHECK (expected_prior_resource_version ~ '^[!-~]+$'
            AND char_length(expected_prior_resource_version) BETWEEN 1 AND 256
            AND observed_resource_version ~ '^[!-~]+$'
            AND char_length(observed_resource_version) BETWEEN 1 AND 256
            AND expected_prior_resource_version <> observed_resource_version),
    CONSTRAINT account_jwt_signer_generation_result_key_check
        CHECK (target_generation > 0
            AND target_kid ~ '^[A-Za-z0-9_-]{1,64}$'
            AND target_algorithm = 'RS256'
            AND public_key_fingerprint ~ '^[0-9a-f]{64}$')
);

-- [jooq ignore start]
ALTER TABLE account_jwt_signer_generation_results
    ADD CONSTRAINT account_jwt_signer_generation_result_public_jwk_check
    CHECK (octet_length(public_jwk_json) <= 8192
        AND jsonb_typeof(public_jwk_json::jsonb) = 'object'
        AND (public_jwk_json::jsonb ?& ARRAY['kty', 'use', 'alg', 'kid', 'key_ops', 'n', 'e'])
        AND NOT (public_jwk_json::jsonb ?| ARRAY['d', 'p', 'q', 'dp', 'dq', 'qi', 'oth', 'k'])
        AND public_jwk_json::jsonb - ARRAY['kty', 'use', 'alg', 'kid', 'key_ops', 'n', 'e'] = '{}'::jsonb);
-- [jooq ignore stop]

-- [jooq ignore start]
CREATE FUNCTION account_jwt_signer_secret_observation_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    state_operation_id UUID;
    state_record_version BIGINT;
    operation_digest VARCHAR(64);
    operation_expected_version BIGINT;
    operation_environment VARCHAR(63);
    operation_cluster VARCHAR(128);
    operation_namespace VARCHAR(63);
    operation_mode VARCHAR(64);
    operation_cluster_uid UUID;
    operation_namespace_uid UUID;
    operation_trust_digest VARCHAR(64);
    operation_trust_revision VARCHAR(128);
BEGIN
    SELECT desired_state.generation_operation_id, desired_state.record_version
      INTO state_operation_id, state_record_version
      FROM account_jwt_signer_desired_states AS desired_state
      WHERE desired_state.environment_id = NEW.environment_id
        AND desired_state.cluster_id = NEW.cluster_id
        AND desired_state.kubernetes_namespace = NEW.kubernetes_namespace
        AND desired_state.custody_mode = NEW.custody_mode
      FOR UPDATE;

    SELECT generation_op.operation_digest, generation_op.expected_record_version,
           generation_op.environment_id, generation_op.cluster_id, generation_op.kubernetes_namespace,
           generation_op.custody_mode, generation_op.expected_cluster_incarnation_uid,
           generation_op.expected_namespace_uid, generation_op.trust_binding_digest,
           generation_op.trust_config_revision
      INTO operation_digest, operation_expected_version, operation_environment,
           operation_cluster, operation_namespace, operation_mode, operation_cluster_uid,
           operation_namespace_uid, operation_trust_digest, operation_trust_revision
      FROM account_jwt_signer_generation_operations AS generation_op
      WHERE generation_op.operation_id = NEW.operation_id
      FOR UPDATE;

    IF state_operation_id IS DISTINCT FROM NEW.operation_id
        OR state_record_version IS DISTINCT FROM operation_expected_version + 1
        OR operation_digest IS DISTINCT FROM NEW.operation_digest
        OR operation_expected_version IS DISTINCT FROM NEW.expected_record_version
        OR operation_environment IS DISTINCT FROM NEW.environment_id
        OR operation_cluster IS DISTINCT FROM NEW.cluster_id
        OR operation_namespace IS DISTINCT FROM NEW.kubernetes_namespace
        OR operation_mode IS DISTINCT FROM NEW.custody_mode
        OR operation_cluster_uid IS DISTINCT FROM NEW.expected_cluster_incarnation_uid
        OR operation_namespace_uid IS DISTINCT FROM NEW.expected_namespace_uid
        OR operation_trust_digest IS DISTINCT FROM NEW.trust_binding_digest
        OR operation_trust_revision IS DISTINCT FROM NEW.trust_config_revision
        OR NEW.private_secret_name IS DISTINCT FROM 'jwt-signing-keys' THEN
        RAISE EXCEPTION 'JWT signer Secret observation must bind the current Account operation and trust'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_secret_observation_binding';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_jwt_signer_generation_result_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    state_operation_id UUID;
    state_record_version BIGINT;
    operation_digest VARCHAR(64);
    operation_expected_version BIGINT;
    operation_environment VARCHAR(63);
    operation_cluster VARCHAR(128);
    operation_namespace VARCHAR(63);
    operation_mode VARCHAR(64);
    operation_generation BIGINT;
    operation_kid VARCHAR(64);
    operation_algorithm VARCHAR(16);
    operation_cluster_uid UUID;
    operation_namespace_uid UUID;
    operation_trust_digest VARCHAR(64);
    operation_trust_revision VARCHAR(128);
    observation_generation_request_digest VARCHAR(64);
    observation_secret_uid UUID;
    observation_resource_version VARCHAR(256);
BEGIN
    -- Lock order matches every Account repository write: desired state, then operation.
    SELECT desired_state.generation_operation_id, desired_state.record_version
      INTO state_operation_id, state_record_version
      FROM account_jwt_signer_desired_states AS desired_state
      WHERE desired_state.environment_id = NEW.environment_id
        AND desired_state.cluster_id = NEW.cluster_id
        AND desired_state.kubernetes_namespace = NEW.kubernetes_namespace
        AND desired_state.custody_mode = NEW.custody_mode
      FOR UPDATE;

    SELECT generation_op.operation_digest, generation_op.expected_record_version,
           generation_op.environment_id, generation_op.cluster_id, generation_op.kubernetes_namespace,
           generation_op.custody_mode, generation_op.target_generation, generation_op.target_kid,
           generation_op.target_algorithm, generation_op.expected_cluster_incarnation_uid,
           generation_op.expected_namespace_uid, generation_op.trust_binding_digest,
           generation_op.trust_config_revision
      INTO operation_digest, operation_expected_version, operation_environment,
           operation_cluster, operation_namespace, operation_mode, operation_generation,
           operation_kid, operation_algorithm, operation_cluster_uid,
           operation_namespace_uid, operation_trust_digest, operation_trust_revision
      FROM account_jwt_signer_generation_operations AS generation_op
      WHERE generation_op.operation_id = NEW.operation_id
      FOR UPDATE;

    SELECT observation.generation_request_digest, observation.secret_uid,
           observation.observed_resource_version
      INTO observation_generation_request_digest, observation_secret_uid,
           observation_resource_version
      FROM account_jwt_signer_secret_observations AS observation
      WHERE observation.operation_id = NEW.operation_id
      FOR UPDATE;

    IF state_operation_id IS DISTINCT FROM NEW.operation_id
        OR state_record_version IS DISTINCT FROM operation_expected_version + 1
        OR NEW.desired_state_version IS DISTINCT FROM state_record_version
        OR operation_digest IS DISTINCT FROM NEW.operation_digest
        OR operation_expected_version IS NULL
        OR operation_environment IS DISTINCT FROM NEW.environment_id
        OR operation_cluster IS DISTINCT FROM NEW.cluster_id
        OR operation_namespace IS DISTINCT FROM NEW.kubernetes_namespace
        OR operation_mode IS DISTINCT FROM NEW.custody_mode
        OR operation_generation IS DISTINCT FROM NEW.target_generation
        OR operation_kid IS DISTINCT FROM NEW.target_kid
        OR operation_algorithm IS DISTINCT FROM NEW.target_algorithm
        OR operation_cluster_uid IS DISTINCT FROM NEW.expected_cluster_incarnation_uid
        OR operation_namespace_uid IS DISTINCT FROM NEW.expected_namespace_uid
        OR operation_trust_digest IS DISTINCT FROM NEW.trust_binding_digest
        OR operation_trust_revision IS DISTINCT FROM NEW.trust_config_revision
        OR observation_generation_request_digest IS DISTINCT FROM NEW.generation_request_digest
        OR observation_secret_uid IS DISTINCT FROM NEW.secret_uid
        OR observation_resource_version IS DISTINCT FROM NEW.expected_prior_resource_version
        OR NEW.private_secret_name IS DISTINCT FROM 'jwt-signing-keys' THEN
        RAISE EXCEPTION 'JWT signer generation result must bind the exact current request and observation'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_generation_result_binding';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_jwt_signer_generation_evidence_immutable()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account JWT signer generation evidence is immutable'
        USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_generation_evidence_immutable';
    RETURN NULL;
END;
$$;

CREATE TRIGGER account_jwt_signer_secret_observation_guard
    BEFORE INSERT ON account_jwt_signer_secret_observations
    FOR EACH ROW EXECUTE FUNCTION account_jwt_signer_secret_observation_guard();

CREATE TRIGGER account_jwt_signer_secret_observation_immutable
    BEFORE UPDATE OR DELETE ON account_jwt_signer_secret_observations
    FOR EACH ROW EXECUTE FUNCTION account_jwt_signer_generation_evidence_immutable();

CREATE TRIGGER account_jwt_signer_secret_observation_no_truncate
    BEFORE TRUNCATE ON account_jwt_signer_secret_observations
    FOR EACH STATEMENT EXECUTE FUNCTION account_jwt_signer_generation_evidence_immutable();

CREATE TRIGGER account_jwt_signer_generation_result_guard
    BEFORE INSERT ON account_jwt_signer_generation_results
    FOR EACH ROW EXECUTE FUNCTION account_jwt_signer_generation_result_guard();

CREATE TRIGGER account_jwt_signer_generation_result_immutable
    BEFORE UPDATE OR DELETE ON account_jwt_signer_generation_results
    FOR EACH ROW EXECUTE FUNCTION account_jwt_signer_generation_evidence_immutable();

CREATE TRIGGER account_jwt_signer_generation_result_no_truncate
    BEFORE TRUNCATE ON account_jwt_signer_generation_results
    FOR EACH STATEMENT EXECUTE FUNCTION account_jwt_signer_generation_evidence_immutable();
-- [jooq ignore stop]

