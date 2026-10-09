-- Account-owned signer desired state, Account-selected generation requests, and a dormant
-- PREPARED schema for the later lifecycle phase. Generation/materialization evidence is not a
-- PREPARED promotion record and never authorizes signing.
CREATE TABLE account_jwt_signer_desired_states (
    environment_id VARCHAR(63) PRIMARY KEY,
    cluster_id VARCHAR(128) NOT NULL,
    kubernetes_namespace VARCHAR(63) NOT NULL,
    custody_mode VARCHAR(64) NOT NULL,
    private_secret_name VARCHAR(63) NOT NULL DEFAULT 'jwt-signing-keys',
    public_jwks_config_map_name VARCHAR(63) NOT NULL DEFAULT 'jwt-jwks',
    record_version BIGINT NOT NULL DEFAULT 1,
    durable_active_generation BIGINT,
    durable_active_kid VARCHAR(128),
    published_active_generation BIGINT,
    published_active_kid VARCHAR(128),
    generation_operation_id UUID,
    prepared_operation_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_jwt_signer_state_binding_unique
        UNIQUE (environment_id, cluster_id, kubernetes_namespace, custody_mode),
    CONSTRAINT account_jwt_signer_state_environment_check
        CHECK (environment_id ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT account_jwt_signer_state_cluster_check
        CHECK (cluster_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT account_jwt_signer_state_namespace_check
        CHECK (kubernetes_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT account_jwt_signer_state_mode_check
        CHECK (custody_mode = 'INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK'),
    CONSTRAINT account_jwt_signer_state_resource_names_check
        CHECK (private_secret_name = 'jwt-signing-keys'
            AND public_jwks_config_map_name = 'jwt-jwks'),
    CONSTRAINT account_jwt_signer_state_version_check CHECK (record_version > 0),
    CONSTRAINT account_jwt_signer_state_durable_active_shape_check
        CHECK ((durable_active_generation IS NULL AND durable_active_kid IS NULL)
            OR (durable_active_generation IS NOT NULL
                AND durable_active_generation > 0
                AND durable_active_kid IS NOT NULL
                AND durable_active_kid ~ '^[A-Za-z0-9_-]{1,64}$')),
    CONSTRAINT account_jwt_signer_state_published_active_shape_check
        CHECK ((published_active_generation IS NULL AND published_active_kid IS NULL)
            OR (published_active_generation IS NOT NULL
                AND published_active_generation > 0
                AND published_active_kid IS NOT NULL
                AND published_active_kid ~ '^[A-Za-z0-9_-]{1,64}$'))
);

CREATE TABLE account_jwt_signer_promotion_operations (
    operation_id UUID PRIMARY KEY,
    environment_id VARCHAR(63) NOT NULL,
    cluster_id VARCHAR(128) NOT NULL,
    kubernetes_namespace VARCHAR(63) NOT NULL,
    custody_mode VARCHAR(64) NOT NULL,
    request_digest_version SMALLINT NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    expected_record_version BIGINT NOT NULL,
    expected_previous_generation BIGINT,
    expected_previous_kid VARCHAR(128),
    target_generation BIGINT NOT NULL,
    target_kid VARCHAR(128) NOT NULL,
    target_algorithm VARCHAR(16) NOT NULL,
    target_public_key_fingerprint VARCHAR(64) NOT NULL,
    expected_private_secret_resource_version VARCHAR(256) NOT NULL,
    expected_public_jwks_resource_version VARCHAR(256) NOT NULL,
    expected_public_active_generation BIGINT,
    expected_public_active_kid VARCHAR(128),
    operation_action VARCHAR(32) NOT NULL,
    allowed_private_slots_canonical_bytes BYTEA NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PREPARED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_jwt_signer_promotion_operation_state_fk
        FOREIGN KEY (environment_id)
        REFERENCES account_jwt_signer_desired_states(environment_id)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_signer_promotion_exact_binding_fk
        FOREIGN KEY (environment_id, cluster_id, kubernetes_namespace, custody_mode)
        REFERENCES account_jwt_signer_desired_states(
            environment_id, cluster_id, kubernetes_namespace, custody_mode)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_signer_promotion_cluster_check
        CHECK (cluster_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT account_jwt_signer_promotion_namespace_check
        CHECK (kubernetes_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT account_jwt_signer_promotion_mode_check
        CHECK (custody_mode = 'INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK'),
    CONSTRAINT account_jwt_signer_promotion_id_v4_check
        CHECK (operation_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    CONSTRAINT account_jwt_signer_promotion_digest_check
        CHECK (request_digest_version = 1 AND request_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT account_jwt_signer_promotion_record_version_check
        CHECK (expected_record_version > 0
            AND expected_record_version < 9223372036854775807),
    CONSTRAINT account_jwt_signer_promotion_previous_shape_check
        CHECK ((expected_previous_generation IS NULL AND expected_previous_kid IS NULL)
            OR (expected_previous_generation IS NOT NULL
                AND expected_previous_generation > 0
                AND expected_previous_kid IS NOT NULL
                AND expected_previous_kid ~ '^[A-Za-z0-9_-]{1,64}$')),
    CONSTRAINT account_jwt_signer_promotion_target_check
        CHECK (target_generation > 0
            AND target_kid ~ '^[A-Za-z0-9_-]{1,64}$'
            AND (expected_previous_generation IS NULL
                OR (expected_previous_generation IS NOT NULL
                    AND target_generation > expected_previous_generation
                    AND target_kid <> expected_previous_kid))),
    CONSTRAINT account_jwt_signer_promotion_algorithm_check
        CHECK (target_algorithm = 'RS256'),
    CONSTRAINT account_jwt_signer_promotion_fingerprint_check
        CHECK (target_public_key_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT account_jwt_signer_promotion_resource_versions_check
        CHECK (expected_private_secret_resource_version ~ '^[!-~]+$'
            AND char_length(expected_private_secret_resource_version) BETWEEN 1 AND 256
            AND expected_public_jwks_resource_version ~ '^[!-~]+$'
            AND char_length(expected_public_jwks_resource_version) BETWEEN 1 AND 256),
    CONSTRAINT account_jwt_signer_promotion_public_active_shape_check
        CHECK ((expected_public_active_generation IS NULL AND expected_public_active_kid IS NULL)
            OR (expected_public_active_generation IS NOT NULL
                AND expected_public_active_generation > 0
                AND expected_public_active_kid IS NOT NULL
                AND expected_public_active_kid ~ '^[A-Za-z0-9_-]{1,64}$')),
    CONSTRAINT account_jwt_signer_promotion_published_generation_check
        CHECK (expected_public_active_generation IS NULL
            OR (target_generation > expected_public_active_generation
                AND target_kid <> expected_public_active_kid)),
    CONSTRAINT account_jwt_signer_promotion_action_check
        CHECK (operation_action = 'MATERIALIZE_PENDING'),
    CONSTRAINT account_jwt_signer_promotion_slots_check
        CHECK (allowed_private_slots_canonical_bytes = '\x5b2270656e64696e67225d'::bytea),
    CONSTRAINT account_jwt_signer_promotion_status_check CHECK (status = 'PREPARED')
);

ALTER TABLE account_jwt_signer_desired_states
    ADD CONSTRAINT account_jwt_signer_state_prepared_operation_fk
    FOREIGN KEY (prepared_operation_id)
    REFERENCES account_jwt_signer_promotion_operations(operation_id)
    ON DELETE RESTRICT;

CREATE TABLE account_jwt_signer_generation_operations (
    operation_id UUID PRIMARY KEY,
    environment_id VARCHAR(63) NOT NULL,
    cluster_id VARCHAR(128) NOT NULL,
    kubernetes_namespace VARCHAR(63) NOT NULL,
    custody_mode VARCHAR(64) NOT NULL,
    operation_digest_version SMALLINT NOT NULL,
    operation_digest VARCHAR(64) NOT NULL,
    expected_record_version BIGINT NOT NULL,
    expected_previous_generation BIGINT,
    expected_previous_kid VARCHAR(128),
    expected_published_generation BIGINT,
    expected_published_kid VARCHAR(128),
    target_generation BIGINT NOT NULL,
    target_kid VARCHAR(64) NOT NULL,
    target_algorithm VARCHAR(16) NOT NULL,
    expected_cluster_incarnation_uid UUID NOT NULL,
    expected_namespace_uid UUID NOT NULL,
    trust_binding_digest VARCHAR(64) NOT NULL,
    trust_config_revision VARCHAR(128) NOT NULL,
    operation_action VARCHAR(32) NOT NULL,
    allowed_private_slots_canonical_bytes BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_jwt_signer_generation_state_fk
        FOREIGN KEY (environment_id)
        REFERENCES account_jwt_signer_desired_states(environment_id)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_signer_generation_exact_binding_fk
        FOREIGN KEY (environment_id, cluster_id, kubernetes_namespace, custody_mode)
        REFERENCES account_jwt_signer_desired_states(
            environment_id, cluster_id, kubernetes_namespace, custody_mode)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_signer_generation_binding_unique
        UNIQUE (operation_id, environment_id, cluster_id, kubernetes_namespace, custody_mode,
                operation_digest, expected_record_version, target_generation, target_kid,
                expected_published_generation, expected_published_kid, target_algorithm,
                expected_cluster_incarnation_uid, expected_namespace_uid,
                trust_binding_digest, trust_config_revision),
    CONSTRAINT account_jwt_signer_generation_observation_binding_unique
        UNIQUE (operation_id, environment_id, cluster_id, kubernetes_namespace, custody_mode,
                operation_digest, expected_record_version, expected_cluster_incarnation_uid,
                expected_namespace_uid, trust_binding_digest, trust_config_revision),
    CONSTRAINT account_jwt_signer_generation_receipt_binding_unique
        UNIQUE (operation_id, environment_id, cluster_id, kubernetes_namespace, custody_mode,
                operation_digest, target_generation, target_kid, target_algorithm,
                expected_cluster_incarnation_uid, expected_namespace_uid,
                trust_binding_digest, trust_config_revision),
    CONSTRAINT account_jwt_signer_generation_id_v4_check
        CHECK (operation_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    CONSTRAINT account_jwt_signer_generation_binding_check
        CHECK (cluster_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            AND kubernetes_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
            AND custody_mode = 'INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK'),
    CONSTRAINT account_jwt_signer_generation_digest_check
        CHECK (operation_digest_version = 1 AND operation_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT account_jwt_signer_generation_state_version_check
        CHECK (expected_record_version > 0 AND expected_record_version < 9223372036854775807),
    CONSTRAINT account_jwt_signer_generation_previous_shape_check
        CHECK ((expected_previous_generation IS NULL AND expected_previous_kid IS NULL)
            OR (expected_previous_generation IS NOT NULL AND expected_previous_generation > 0
                AND expected_previous_kid IS NOT NULL
                AND expected_previous_kid ~ '^[A-Za-z0-9_-]{1,64}$')),
    CONSTRAINT account_jwt_signer_generation_target_check
        CHECK (target_generation > 0
            AND target_kid ~ '^[A-Za-z0-9_-]{1,64}$'
            AND (expected_previous_generation IS NULL
                OR (target_generation > expected_previous_generation
                    AND target_kid <> expected_previous_kid))),
    CONSTRAINT account_jwt_signer_generation_algorithm_check
        CHECK (target_algorithm = 'RS256'),
    CONSTRAINT account_jwt_signer_generation_published_shape_check
        CHECK ((expected_published_generation IS NULL AND expected_published_kid IS NULL)
            OR (expected_published_generation IS NOT NULL AND expected_published_generation > 0
                AND expected_published_kid IS NOT NULL
                AND expected_published_kid ~ '^[A-Za-z0-9_-]{1,64}$')),
    CONSTRAINT account_jwt_signer_generation_trust_check
        CHECK (expected_cluster_incarnation_uid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND expected_namespace_uid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND trust_binding_digest ~ '^[0-9a-f]{64}$'
            AND trust_config_revision ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT account_jwt_signer_generation_action_check
        CHECK (operation_action = 'MATERIALIZE_PENDING'),
    CONSTRAINT account_jwt_signer_generation_slots_check
        CHECK (allowed_private_slots_canonical_bytes = '\x5b2270656e64696e67225d'::bytea)
);

ALTER TABLE account_jwt_signer_desired_states
    ADD CONSTRAINT account_jwt_signer_state_generation_operation_fk
    FOREIGN KEY (generation_operation_id)
    REFERENCES account_jwt_signer_generation_operations(operation_id)
    ON DELETE RESTRICT;

CREATE UNIQUE INDEX account_jwt_signer_generation_target_once
    ON account_jwt_signer_generation_operations (environment_id, target_generation);

CREATE UNIQUE INDEX account_jwt_signer_generation_kid_once
    ON account_jwt_signer_generation_operations (environment_id, target_kid);

CREATE UNIQUE INDEX account_jwt_signer_one_prepared_operation_per_environment
    ON account_jwt_signer_promotion_operations (environment_id)
    WHERE status = 'PREPARED';

CREATE UNIQUE INDEX account_jwt_signer_target_generation_once
    ON account_jwt_signer_promotion_operations (environment_id, target_generation);

CREATE UNIQUE INDEX account_jwt_signer_target_kid_once
    ON account_jwt_signer_promotion_operations (environment_id, target_kid);

-- [jooq ignore start]
CREATE FUNCTION account_jwt_signer_desired_state_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    operation_environment VARCHAR(63);
    operation_expected_version BIGINT;
    operation_previous_generation BIGINT;
    operation_previous_kid VARCHAR(128);
    operation_published_generation BIGINT;
    operation_published_kid VARCHAR(128);
    operation_status VARCHAR(16);
    operation_cluster VARCHAR(128);
    operation_namespace VARCHAR(63);
    operation_mode VARCHAR(64);
    operation_target_generation BIGINT;
    operation_target_kid VARCHAR(64);
    operation_algorithm VARCHAR(16);
    operation_cluster_uid UUID;
    operation_namespace_uid UUID;
    operation_trust_digest VARCHAR(64);
    operation_trust_revision VARCHAR(128);
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Account JWT signer desired state cannot be deleted'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_desired_state_immutable';
    END IF;

    IF NEW.environment_id IS DISTINCT FROM OLD.environment_id
        OR NEW.cluster_id IS DISTINCT FROM OLD.cluster_id
        OR NEW.kubernetes_namespace IS DISTINCT FROM OLD.kubernetes_namespace
        OR NEW.custody_mode IS DISTINCT FROM OLD.custody_mode
        OR NEW.private_secret_name IS DISTINCT FROM OLD.private_secret_name
        OR NEW.public_jwks_config_map_name IS DISTINCT FROM OLD.public_jwks_config_map_name
        OR NEW.durable_active_generation IS DISTINCT FROM OLD.durable_active_generation
        OR NEW.durable_active_kid IS DISTINCT FROM OLD.durable_active_kid
        OR NEW.published_active_generation IS DISTINCT FROM OLD.published_active_generation
        OR NEW.published_active_kid IS DISTINCT FROM OLD.published_active_kid
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.record_version <> OLD.record_version + 1 THEN
        RAISE EXCEPTION 'Account JWT signer desired state permits only a versioned owner transition'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_desired_state_immutable';
    END IF;

    IF NEW.prepared_operation_id IS DISTINCT FROM OLD.prepared_operation_id THEN
        RAISE EXCEPTION 'JWT signer promotion prerequisites are incomplete'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_prepared_operation_binding';
    END IF;

    IF OLD.generation_operation_id IS NOT NULL
        OR NEW.generation_operation_id IS NULL
        OR OLD.prepared_operation_id IS NOT NULL
        OR NEW.prepared_operation_id IS NOT NULL THEN
        RAISE EXCEPTION 'Account JWT signer generation operation is already fenced or not promotable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_generation_operation_binding';
    END IF;

    SELECT environment_id, cluster_id, kubernetes_namespace, custody_mode,
           expected_record_version, expected_previous_generation, expected_previous_kid,
           expected_published_generation, expected_published_kid,
           target_generation, target_kid, target_algorithm,
           expected_cluster_incarnation_uid, expected_namespace_uid,
           trust_binding_digest, trust_config_revision
      INTO operation_environment, operation_cluster, operation_namespace, operation_mode,
           operation_expected_version, operation_previous_generation, operation_previous_kid,
           operation_published_generation, operation_published_kid,
           operation_target_generation, operation_target_kid, operation_algorithm,
           operation_cluster_uid, operation_namespace_uid,
           operation_trust_digest, operation_trust_revision
      FROM account_jwt_signer_generation_operations
      WHERE operation_id = NEW.generation_operation_id;
    IF operation_environment IS DISTINCT FROM NEW.environment_id
        OR operation_cluster IS DISTINCT FROM NEW.cluster_id
        OR operation_namespace IS DISTINCT FROM NEW.kubernetes_namespace
        OR operation_mode IS DISTINCT FROM NEW.custody_mode
        OR operation_expected_version IS DISTINCT FROM OLD.record_version
        OR operation_previous_generation IS DISTINCT FROM OLD.durable_active_generation
        OR operation_previous_kid IS DISTINCT FROM OLD.durable_active_kid
        OR operation_published_generation IS DISTINCT FROM OLD.published_active_generation
        OR operation_published_kid IS DISTINCT FROM OLD.published_active_kid
        OR operation_algorithm IS DISTINCT FROM 'RS256'
        OR operation_cluster_uid IS NULL OR operation_namespace_uid IS NULL
        OR operation_trust_digest IS NULL OR operation_trust_revision IS NULL THEN
        RAISE EXCEPTION 'Account JWT signer state must point to its exact owner-selected generation operation'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_generation_operation_binding';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_jwt_signer_desired_state_no_truncate()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account JWT signer desired state cannot be truncated'
        USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_desired_state_immutable';
    RETURN NULL;
END;
$$;

CREATE FUNCTION account_jwt_signer_operation_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account JWT signer operation history is immutable and PREPARED-only'
        USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_operation_immutable';
    RETURN NULL;
END;
$$;

CREATE FUNCTION account_jwt_signer_operation_no_truncate()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account JWT signer operation history cannot be truncated'
        USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_operation_immutable';
    RETURN NULL;
END;
$$;

CREATE FUNCTION account_jwt_signer_generation_operation_immutable()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account JWT signer generation operation is immutable'
        USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_generation_operation_immutable';
    RETURN NULL;
END;
$$;

CREATE TRIGGER account_jwt_signer_desired_state_guard
    BEFORE UPDATE OR DELETE ON account_jwt_signer_desired_states
    FOR EACH ROW EXECUTE FUNCTION account_jwt_signer_desired_state_guard();

CREATE TRIGGER account_jwt_signer_desired_state_no_truncate
    BEFORE TRUNCATE ON account_jwt_signer_desired_states
    FOR EACH STATEMENT EXECUTE FUNCTION account_jwt_signer_desired_state_no_truncate();

CREATE TRIGGER account_jwt_signer_operation_guard
    BEFORE UPDATE OR DELETE ON account_jwt_signer_promotion_operations
    FOR EACH ROW EXECUTE FUNCTION account_jwt_signer_operation_guard();

CREATE TRIGGER account_jwt_signer_operation_no_truncate
    BEFORE TRUNCATE ON account_jwt_signer_promotion_operations
    FOR EACH STATEMENT EXECUTE FUNCTION account_jwt_signer_operation_no_truncate();

CREATE TRIGGER account_jwt_signer_generation_operation_immutable
    BEFORE UPDATE OR DELETE ON account_jwt_signer_generation_operations
    FOR EACH ROW EXECUTE FUNCTION account_jwt_signer_generation_operation_immutable();

CREATE TRIGGER account_jwt_signer_generation_operation_no_truncate
    BEFORE TRUNCATE ON account_jwt_signer_generation_operations
    FOR EACH STATEMENT EXECUTE FUNCTION account_jwt_signer_generation_operation_immutable();
-- [jooq ignore stop]

