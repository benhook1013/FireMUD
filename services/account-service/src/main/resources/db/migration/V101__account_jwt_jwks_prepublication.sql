-- Account records its exact public-JWKS intent before the protected ConfigMap CAS, then binds
-- server readback and local mounted correspondence to the same current generation operation.
-- These observations are non-authorizing and never advance signer lifecycle state.
CREATE TABLE account_jwt_jwks_prepublication_intents (
    operation_id UUID PRIMARY KEY,
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
    api_binding_digest VARCHAR(64) NOT NULL,
    api_config_revision VARCHAR(128) NOT NULL,
    config_map_name VARCHAR(63) NOT NULL,
    config_map_uid UUID NOT NULL,
    expected_resource_version VARCHAR(256) NOT NULL,
    expected_snapshot_digest VARCHAR(64) NOT NULL,
    target_generation BIGINT NOT NULL,
    target_kid VARCHAR(64) NOT NULL,
    public_key_fingerprint VARCHAR(64) NOT NULL,
    expected_durable_active_json VARCHAR(512) NOT NULL,
    expected_published_active_json VARCHAR(512) NOT NULL,
    jwks_json TEXT NOT NULL,
    generation_marker_json TEXT NOT NULL,
    public_data_digest VARCHAR(64) NOT NULL,
    generation_marker_digest VARCHAR(64) NOT NULL,
    intent_digest VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_jwt_jwks_intent_state_fk
        FOREIGN KEY (environment_id, cluster_id, kubernetes_namespace, custody_mode)
        REFERENCES account_jwt_signer_desired_states(
            environment_id, cluster_id, kubernetes_namespace, custody_mode)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_jwks_intent_generation_result_fk
        FOREIGN KEY (operation_id)
        REFERENCES account_jwt_signer_generation_results(operation_id)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_jwks_intent_digest_unique
        UNIQUE (operation_id, intent_digest),
    CONSTRAINT account_jwt_jwks_intent_identity_check
        CHECK (custody_mode = 'INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK'
            AND config_map_name = 'jwt-jwks'
            AND desired_state_version > 1
            AND target_generation > 0
            AND target_kid ~ '^[A-Za-z0-9_-]+$'
            AND char_length(target_kid) BETWEEN 1 AND 64),
    CONSTRAINT account_jwt_jwks_intent_uid_check
        CHECK (expected_cluster_incarnation_uid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND expected_namespace_uid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND config_map_uid <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT account_jwt_jwks_intent_resource_version_check
        CHECK (expected_resource_version ~ '^[!-~]+$'
            AND char_length(expected_resource_version) BETWEEN 1 AND 256),
    CONSTRAINT account_jwt_jwks_intent_digest_check
        CHECK (operation_digest ~ '^[0-9a-f]{64}$'
            AND generation_request_digest ~ '^[0-9a-f]{64}$'
            AND generation_receipt_digest ~ '^[0-9a-f]{64}$'
            AND trust_binding_digest ~ '^[0-9a-f]{64}$'
            AND api_binding_digest ~ '^[0-9a-f]{64}$'
            AND expected_snapshot_digest ~ '^[0-9a-f]{64}$'
            AND public_key_fingerprint ~ '^[0-9a-f]{64}$'
            AND public_data_digest ~ '^[0-9a-f]{64}$'
            AND generation_marker_digest ~ '^[0-9a-f]{64}$'
            AND intent_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT account_jwt_jwks_intent_revision_check
        CHECK (trust_config_revision ~ '^[A-Za-z0-9][A-Za-z0-9._:-]*$'
            AND char_length(trust_config_revision) BETWEEN 1 AND 128
            AND api_config_revision ~ '^[A-Za-z0-9][A-Za-z0-9._:-]*$'
            AND char_length(api_config_revision) BETWEEN 1 AND 128),
    CONSTRAINT account_jwt_jwks_intent_content_bounds_check
        CHECK (octet_length(jwks_json) BETWEEN 1 AND 262144
            AND octet_length(generation_marker_json) BETWEEN 1 AND 16384
            AND octet_length(expected_durable_active_json) BETWEEN 1 AND 512
            AND octet_length(expected_published_active_json) BETWEEN 1 AND 512)
);

CREATE TABLE account_jwt_jwks_publication_receipts (
    operation_id UUID PRIMARY KEY,
    environment_id VARCHAR(63) NOT NULL,
    cluster_id VARCHAR(128) NOT NULL,
    kubernetes_namespace VARCHAR(63) NOT NULL,
    custody_mode VARCHAR(64) NOT NULL,
    intent_digest VARCHAR(64) NOT NULL,
    config_map_uid UUID NOT NULL,
    expected_resource_version VARCHAR(256) NOT NULL,
    observed_resource_version VARCHAR(256) NOT NULL,
    public_data_digest VARCHAR(64) NOT NULL,
    receipt_digest VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_jwt_jwks_receipt_state_fk
        FOREIGN KEY (environment_id, cluster_id, kubernetes_namespace, custody_mode)
        REFERENCES account_jwt_signer_desired_states(
            environment_id, cluster_id, kubernetes_namespace, custody_mode)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_jwks_receipt_intent_fk
        FOREIGN KEY (operation_id, intent_digest)
        REFERENCES account_jwt_jwks_prepublication_intents(operation_id, intent_digest)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_jwks_receipt_digest_unique
        UNIQUE (operation_id, intent_digest, receipt_digest),
    CONSTRAINT account_jwt_jwks_receipt_identity_check
        CHECK (custody_mode = 'INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK'
            AND config_map_uid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND expected_resource_version ~ '^[!-~]+$'
            AND char_length(expected_resource_version) BETWEEN 1 AND 256
            AND observed_resource_version ~ '^[!-~]+$'
            AND char_length(observed_resource_version) BETWEEN 1 AND 256
            AND expected_resource_version <> observed_resource_version),
    CONSTRAINT account_jwt_jwks_receipt_digest_check
        CHECK (intent_digest ~ '^[0-9a-f]{64}$'
            AND public_data_digest ~ '^[0-9a-f]{64}$'
            AND receipt_digest ~ '^[0-9a-f]{64}$')
);

CREATE TABLE account_jwt_jwks_mount_observations (
    operation_id UUID PRIMARY KEY,
    environment_id VARCHAR(63) NOT NULL,
    cluster_id VARCHAR(128) NOT NULL,
    kubernetes_namespace VARCHAR(63) NOT NULL,
    custody_mode VARCHAR(64) NOT NULL,
    intent_digest VARCHAR(64) NOT NULL,
    publication_receipt_digest VARCHAR(64) NOT NULL,
    generation_marker_digest VARCHAR(64) NOT NULL,
    public_data_digest VARCHAR(64) NOT NULL,
    public_key_fingerprint VARCHAR(64) NOT NULL,
    observation_digest VARCHAR(64) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_jwt_jwks_mount_state_fk
        FOREIGN KEY (environment_id, cluster_id, kubernetes_namespace, custody_mode)
        REFERENCES account_jwt_signer_desired_states(
            environment_id, cluster_id, kubernetes_namespace, custody_mode)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_jwks_mount_receipt_fk
        FOREIGN KEY (operation_id, intent_digest, publication_receipt_digest)
        REFERENCES account_jwt_jwks_publication_receipts(
            operation_id, intent_digest, receipt_digest)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_jwks_mount_digest_check
        CHECK (custody_mode = 'INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK'
            AND intent_digest ~ '^[0-9a-f]{64}$'
            AND publication_receipt_digest ~ '^[0-9a-f]{64}$'
            AND generation_marker_digest ~ '^[0-9a-f]{64}$'
            AND public_data_digest ~ '^[0-9a-f]{64}$'
            AND public_key_fingerprint ~ '^[0-9a-f]{64}$'
            AND observation_digest ~ '^[0-9a-f]{64}$')
);

-- Keep the trigger bodies out of jOOQ DDL parsing; the database still enforces these invariants.
-- [jooq ignore start]
CREATE FUNCTION account_jwt_jwks_lock_current_generation(
    p_operation_id UUID,
    p_environment_id VARCHAR,
    p_cluster_id VARCHAR,
    p_namespace VARCHAR,
    p_custody_mode VARCHAR,
    p_operation_digest VARCHAR,
    p_generation_request_digest VARCHAR,
    p_generation_receipt_digest VARCHAR,
    p_desired_state_version BIGINT,
    p_cluster_uid UUID,
    p_namespace_uid UUID,
    p_trust_binding_digest VARCHAR,
    p_trust_config_revision VARCHAR,
    p_target_generation BIGINT,
    p_target_kid VARCHAR,
    p_public_key_fingerprint VARCHAR
)
RETURNS JSONB
LANGUAGE plpgsql
AS $$
DECLARE
    state_operation_id UUID;
    state_version BIGINT;
    state_prepared_operation_id UUID;
    durable_active JSONB;
    published_active JSONB;
    operation_environment VARCHAR(63);
    operation_cluster VARCHAR(128);
    operation_namespace VARCHAR(63);
    operation_mode VARCHAR(64);
    operation_digest VARCHAR(64);
    operation_expected_version BIGINT;
    operation_cluster_uid UUID;
    operation_namespace_uid UUID;
    operation_trust_digest VARCHAR(64);
    operation_trust_revision VARCHAR(128);
    operation_target_generation BIGINT;
    operation_target_kid VARCHAR(64);
    operation_target_algorithm VARCHAR(16);
    operation_previous_generation BIGINT;
    operation_previous_kid VARCHAR(128);
    operation_published_generation BIGINT;
    operation_published_kid VARCHAR(128);
    result_environment VARCHAR(63);
    result_cluster VARCHAR(128);
    result_namespace VARCHAR(63);
    result_mode VARCHAR(64);
    result_operation_digest VARCHAR(64);
    result_generation_request_digest VARCHAR(64);
    result_desired_state_version BIGINT;
    result_cluster_uid UUID;
    result_namespace_uid UUID;
    result_trust_digest VARCHAR(64);
    result_trust_revision VARCHAR(128);
    result_generation BIGINT;
    result_kid VARCHAR(64);
    result_fingerprint VARCHAR(64);
    result_receipt_digest VARCHAR(64);
    result_jwk VARCHAR(8192);
BEGIN
    SELECT desired_state.generation_operation_id,
           desired_state.record_version,
           desired_state.prepared_operation_id,
           CASE WHEN desired_state.durable_active_generation IS NULL
                THEN '{"present":false}'::jsonb
                ELSE jsonb_build_object('present', true,
                                        'generation', desired_state.durable_active_generation::text,
                                        'kid', desired_state.durable_active_kid)
           END,
           CASE WHEN desired_state.published_active_generation IS NULL
                THEN '{"present":false}'::jsonb
                ELSE jsonb_build_object('present', true,
                                        'generation', desired_state.published_active_generation::text,
                                        'kid', desired_state.published_active_kid)
           END
      INTO state_operation_id, state_version, state_prepared_operation_id,
           durable_active, published_active
      FROM account_jwt_signer_desired_states AS desired_state
      WHERE desired_state.environment_id = p_environment_id
        AND desired_state.cluster_id = p_cluster_id
        AND desired_state.kubernetes_namespace = p_namespace
        AND desired_state.custody_mode = p_custody_mode
      FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account JWT JWKS current desired state is missing' USING ERRCODE = '23514';
    END IF;

    SELECT generation_op.environment_id, generation_op.cluster_id,
           generation_op.kubernetes_namespace, generation_op.custody_mode,
           generation_op.operation_digest, generation_op.expected_record_version,
           generation_op.expected_cluster_incarnation_uid,
           generation_op.expected_namespace_uid, generation_op.trust_binding_digest,
           generation_op.trust_config_revision, generation_op.target_generation,
           generation_op.target_kid, generation_op.target_algorithm,
           generation_op.expected_previous_generation, generation_op.expected_previous_kid,
           generation_op.expected_published_generation, generation_op.expected_published_kid
      INTO operation_environment, operation_cluster, operation_namespace, operation_mode,
           operation_digest, operation_expected_version, operation_cluster_uid,
           operation_namespace_uid, operation_trust_digest, operation_trust_revision,
           operation_target_generation, operation_target_kid, operation_target_algorithm,
           operation_previous_generation, operation_previous_kid,
           operation_published_generation, operation_published_kid
      FROM account_jwt_signer_generation_operations AS generation_op
      WHERE generation_op.operation_id = p_operation_id
      FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account JWT JWKS generation operation is missing' USING ERRCODE = '23514';
    END IF;

    SELECT generation_result.environment_id, generation_result.cluster_id,
           generation_result.kubernetes_namespace, generation_result.custody_mode,
           generation_result.operation_digest, generation_result.generation_request_digest,
           generation_result.desired_state_version,
           generation_result.expected_cluster_incarnation_uid,
           generation_result.expected_namespace_uid, generation_result.trust_binding_digest,
           generation_result.trust_config_revision, generation_result.target_generation,
           generation_result.target_kid, generation_result.public_key_fingerprint,
           generation_result.receipt_digest, generation_result.public_jwk_json
      INTO result_environment, result_cluster, result_namespace, result_mode,
           result_operation_digest, result_generation_request_digest, result_desired_state_version,
           result_cluster_uid, result_namespace_uid, result_trust_digest, result_trust_revision,
           result_generation, result_kid, result_fingerprint, result_receipt_digest, result_jwk
      FROM account_jwt_signer_generation_results AS generation_result
      WHERE generation_result.operation_id = p_operation_id
      FOR UPDATE;

    IF NOT FOUND
        OR state_operation_id IS DISTINCT FROM p_operation_id
        OR state_version IS DISTINCT FROM p_desired_state_version
        OR state_prepared_operation_id IS NOT NULL
        OR operation_environment IS DISTINCT FROM p_environment_id
        OR operation_cluster IS DISTINCT FROM p_cluster_id
        OR operation_namespace IS DISTINCT FROM p_namespace
        OR operation_mode IS DISTINCT FROM p_custody_mode
        OR operation_digest IS DISTINCT FROM p_operation_digest
        OR operation_expected_version + 1 IS DISTINCT FROM p_desired_state_version
        OR operation_cluster_uid IS DISTINCT FROM p_cluster_uid
        OR operation_namespace_uid IS DISTINCT FROM p_namespace_uid
        OR operation_trust_digest IS DISTINCT FROM p_trust_binding_digest
        OR operation_trust_revision IS DISTINCT FROM p_trust_config_revision
        OR operation_target_generation IS DISTINCT FROM p_target_generation
        OR operation_target_kid IS DISTINCT FROM p_target_kid
        OR operation_target_algorithm IS DISTINCT FROM 'RS256'
        OR result_environment IS DISTINCT FROM p_environment_id
        OR result_cluster IS DISTINCT FROM p_cluster_id
        OR result_namespace IS DISTINCT FROM p_namespace
        OR result_mode IS DISTINCT FROM p_custody_mode
        OR result_operation_digest IS DISTINCT FROM p_operation_digest
        OR result_generation_request_digest IS DISTINCT FROM p_generation_request_digest
        OR result_receipt_digest IS DISTINCT FROM p_generation_receipt_digest
        OR result_desired_state_version IS DISTINCT FROM p_desired_state_version
        OR result_cluster_uid IS DISTINCT FROM p_cluster_uid
        OR result_namespace_uid IS DISTINCT FROM p_namespace_uid
        OR result_trust_digest IS DISTINCT FROM p_trust_binding_digest
        OR result_trust_revision IS DISTINCT FROM p_trust_config_revision
        OR result_generation IS DISTINCT FROM p_target_generation
        OR result_kid IS DISTINCT FROM p_target_kid
        OR result_fingerprint IS DISTINCT FROM p_public_key_fingerprint
        OR operation_previous_generation IS DISTINCT FROM
            NULLIF(durable_active->>'generation', '')::BIGINT
        OR operation_previous_kid IS DISTINCT FROM NULLIF(durable_active->>'kid', '')
        OR operation_published_generation IS DISTINCT FROM
            NULLIF(published_active->>'generation', '')::BIGINT
        OR operation_published_kid IS DISTINCT FROM NULLIF(published_active->>'kid', '') THEN
        RAISE EXCEPTION 'Account JWT JWKS evidence is stale or mismatched' USING ERRCODE = '23514';
    END IF;

    RETURN jsonb_build_object(
        'durableActive', durable_active,
        'publishedActive', published_active,
        'publicJwk', result_jwk::jsonb);
END;
$$;

CREATE FUNCTION account_jwt_jwks_validate_public_intent()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    current_evidence JSONB;
    jwks_document JSONB;
    marker_document JSONB;
    key_document JSONB;
    key_kid TEXT;
    seen_kids TEXT[] := ARRAY[]::TEXT[];
    target_key_count INTEGER := 0;
    public_jwk JSONB;
BEGIN
    current_evidence := account_jwt_jwks_lock_current_generation(
        NEW.operation_id, NEW.environment_id, NEW.cluster_id, NEW.kubernetes_namespace,
        NEW.custody_mode, NEW.operation_digest, NEW.generation_request_digest,
        NEW.generation_receipt_digest, NEW.desired_state_version,
        NEW.expected_cluster_incarnation_uid, NEW.expected_namespace_uid,
        NEW.trust_binding_digest, NEW.trust_config_revision,
        NEW.target_generation, NEW.target_kid, NEW.public_key_fingerprint);

    IF NEW.expected_durable_active_json::jsonb IS DISTINCT FROM current_evidence->'durableActive'
        OR NEW.expected_published_active_json::jsonb IS DISTINCT FROM current_evidence->'publishedActive' THEN
        RAISE EXCEPTION 'Account JWT JWKS active fence is stale' USING ERRCODE = '23514';
    END IF;

    jwks_document := NEW.jwks_json::jsonb;
    IF jsonb_typeof(jwks_document) IS DISTINCT FROM 'object'
        OR jwks_document - ARRAY['keys'] <> '{}'::jsonb
        OR jsonb_typeof(jwks_document->'keys') IS DISTINCT FROM 'array'
        OR jsonb_array_length(jwks_document->'keys') < 1
        OR jsonb_array_length(jwks_document->'keys') > 64 THEN
        RAISE EXCEPTION 'Account public JWKS has an unsupported shape' USING ERRCODE = '23514';
    END IF;
    public_jwk := current_evidence->'publicJwk';
    FOR key_document IN
        SELECT jwks_key.value
          FROM jsonb_array_elements(jwks_document->'keys') AS jwks_key(value)
    LOOP
        IF jsonb_typeof(key_document) IS DISTINCT FROM 'object'
            OR key_document - ARRAY['kty', 'use', 'alg', 'kid', 'key_ops', 'n', 'e'] <> '{}'::jsonb
            OR (SELECT count(*) FROM jsonb_object_keys(key_document)) NOT BETWEEN 6 AND 7
            OR key_document ?| ARRAY['d', 'p', 'q', 'dp', 'dq', 'qi', 'oth', 'k']
            OR key_document->>'kty' IS DISTINCT FROM 'RSA'
            OR key_document->>'use' IS DISTINCT FROM 'sig'
            OR key_document->>'alg' IS DISTINCT FROM 'RS256'
            OR key_document->>'kid' IS NULL
            OR key_document->>'kid' !~ '^[A-Za-z0-9_-]{1,64}$'
            OR jsonb_typeof(key_document->'n') IS DISTINCT FROM 'string'
            OR jsonb_typeof(key_document->'e') IS DISTINCT FROM 'string'
            OR key_document->>'n' !~ '^[A-Za-z0-9_-]+$'
            OR key_document->>'e' !~ '^[A-Za-z0-9_-]+$'
            OR (key_document ? 'key_ops'
                AND (jsonb_typeof(key_document->'key_ops') IS DISTINCT FROM 'array'
                    OR key_document->'key_ops' <> '["verify"]'::jsonb)) THEN
            RAISE EXCEPTION 'Account public JWKS contains an invalid or private key' USING ERRCODE = '23514';
        END IF;
        key_kid := key_document->>'kid';
        IF key_kid = ANY(seen_kids) THEN
            RAISE EXCEPTION 'Account public JWKS has duplicate kids' USING ERRCODE = '23514';
        END IF;
        seen_kids := array_append(seen_kids, key_kid);
        IF key_kid = NEW.target_kid THEN
            target_key_count := target_key_count + 1;
            IF key_document IS DISTINCT FROM public_jwk THEN
                RAISE EXCEPTION 'Account public JWKS pending key differs from receipt' USING ERRCODE = '23514';
            END IF;
        END IF;
    END LOOP;
    IF target_key_count <> 1 THEN
        RAISE EXCEPTION 'Account public JWKS does not contain exactly one pending key' USING ERRCODE = '23514';
    END IF;

    marker_document := NEW.generation_marker_json::jsonb;
    IF jsonb_typeof(marker_document) IS DISTINCT FROM 'object'
        OR (marker_document - ARRAY['schemaVersion', 'phase', 'operationId', 'operationDigest',
            'generationRequestDigest', 'generationReceiptDigest', 'binding', 'publicConfigMap',
            'expectedDurableActive', 'expectedPublishedActive', 'pending']) <> '{}'::jsonb
        OR (SELECT count(*) FROM jsonb_object_keys(marker_document)) <> 11
        OR jsonb_typeof((marker_document -> 'binding')) IS DISTINCT FROM 'object'
        OR ((marker_document -> 'binding') - ARRAY['environmentId', 'clusterId', 'namespace',
            'expectedClusterIncarnationUid', 'expectedNamespaceUid', 'trustBindingDigest',
            'trustConfigRevision', 'apiBindingDigest', 'apiConfigRevision']) <> '{}'::jsonb
        OR (SELECT count(*) FROM jsonb_object_keys((marker_document -> 'binding'))) <> 9
        OR jsonb_typeof((marker_document -> 'publicConfigMap')) IS DISTINCT FROM 'object'
        OR ((marker_document -> 'publicConfigMap') - ARRAY['name']) <> '{}'::jsonb
        OR (SELECT count(*) FROM jsonb_object_keys((marker_document -> 'publicConfigMap'))) <> 1
        OR jsonb_typeof((marker_document -> 'pending')) IS DISTINCT FROM 'object'
        OR ((marker_document -> 'pending') - ARRAY['generation', 'kid', 'algorithm',
            'publicKeyFingerprint']) <> '{}'::jsonb
        OR (SELECT count(*) FROM jsonb_object_keys((marker_document -> 'pending'))) <> 4
        OR (marker_document ->> 'schemaVersion') IS DISTINCT FROM '1'
        OR (marker_document ->> 'phase') IS DISTINCT FROM 'PREPUBLISHED'
        OR (marker_document ->> 'operationId') IS DISTINCT FROM NEW.operation_id::TEXT
        OR (marker_document ->> 'operationDigest') IS DISTINCT FROM NEW.operation_digest
        OR (marker_document ->> 'generationRequestDigest') IS DISTINCT FROM NEW.generation_request_digest
        OR (marker_document ->> 'generationReceiptDigest') IS DISTINCT FROM NEW.generation_receipt_digest
        OR ((marker_document -> 'binding') ->> 'environmentId') IS DISTINCT FROM NEW.environment_id
        OR ((marker_document -> 'binding') ->> 'clusterId') IS DISTINCT FROM NEW.cluster_id
        OR ((marker_document -> 'binding') ->> 'namespace') IS DISTINCT FROM NEW.kubernetes_namespace
        OR ((marker_document -> 'binding') ->> 'expectedClusterIncarnationUid') IS DISTINCT FROM NEW.expected_cluster_incarnation_uid::TEXT
        OR ((marker_document -> 'binding') ->> 'expectedNamespaceUid') IS DISTINCT FROM NEW.expected_namespace_uid::TEXT
        OR ((marker_document -> 'binding') ->> 'trustBindingDigest') IS DISTINCT FROM NEW.trust_binding_digest
        OR ((marker_document -> 'binding') ->> 'trustConfigRevision') IS DISTINCT FROM NEW.trust_config_revision
        OR ((marker_document -> 'binding') ->> 'apiBindingDigest') IS DISTINCT FROM NEW.api_binding_digest
        OR ((marker_document -> 'binding') ->> 'apiConfigRevision') IS DISTINCT FROM NEW.api_config_revision
        OR ((marker_document -> 'publicConfigMap') ->> 'name') IS DISTINCT FROM 'jwt-jwks'
        OR (marker_document -> 'expectedDurableActive') IS DISTINCT FROM (NEW.expected_durable_active_json::jsonb)
        OR (marker_document -> 'expectedPublishedActive') IS DISTINCT FROM (NEW.expected_published_active_json::jsonb)
        OR ((marker_document -> 'pending') ->> 'generation') IS DISTINCT FROM NEW.target_generation::TEXT
        OR ((marker_document -> 'pending') ->> 'kid') IS DISTINCT FROM NEW.target_kid
        OR ((marker_document -> 'pending') ->> 'algorithm') IS DISTINCT FROM 'RS256'
        OR ((marker_document -> 'pending') ->> 'publicKeyFingerprint') IS DISTINCT FROM NEW.public_key_fingerprint THEN
        RAISE EXCEPTION 'Account public generation marker is stale or mismatched' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_jwt_jwks_validate_publication_receipt()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    intent_row account_jwt_jwks_prepublication_intents%ROWTYPE;
BEGIN
    PERFORM account_jwt_jwks_lock_current_generation(
        NEW.operation_id, NEW.environment_id, NEW.cluster_id, NEW.kubernetes_namespace,
        NEW.custody_mode, (SELECT intent.operation_digest FROM account_jwt_jwks_prepublication_intents AS intent
                           WHERE intent.operation_id = NEW.operation_id),
        (SELECT intent.generation_request_digest FROM account_jwt_jwks_prepublication_intents AS intent
                           WHERE intent.operation_id = NEW.operation_id),
        (SELECT intent.generation_receipt_digest FROM account_jwt_jwks_prepublication_intents AS intent
                           WHERE intent.operation_id = NEW.operation_id),
        (SELECT intent.desired_state_version FROM account_jwt_jwks_prepublication_intents AS intent
                           WHERE intent.operation_id = NEW.operation_id),
        (SELECT intent.expected_cluster_incarnation_uid FROM account_jwt_jwks_prepublication_intents AS intent
                           WHERE intent.operation_id = NEW.operation_id),
        (SELECT intent.expected_namespace_uid FROM account_jwt_jwks_prepublication_intents AS intent
                           WHERE intent.operation_id = NEW.operation_id),
        (SELECT intent.trust_binding_digest FROM account_jwt_jwks_prepublication_intents AS intent
                           WHERE intent.operation_id = NEW.operation_id),
        (SELECT intent.trust_config_revision FROM account_jwt_jwks_prepublication_intents AS intent
                           WHERE intent.operation_id = NEW.operation_id),
        (SELECT intent.target_generation FROM account_jwt_jwks_prepublication_intents AS intent
                           WHERE intent.operation_id = NEW.operation_id),
        (SELECT intent.target_kid FROM account_jwt_jwks_prepublication_intents AS intent
                           WHERE intent.operation_id = NEW.operation_id),
        (SELECT intent.public_key_fingerprint FROM account_jwt_jwks_prepublication_intents AS intent
                           WHERE intent.operation_id = NEW.operation_id));
    SELECT intent.* INTO intent_row
      FROM account_jwt_jwks_prepublication_intents AS intent
      WHERE intent.operation_id = NEW.operation_id
      FOR UPDATE;
    IF NOT FOUND
        OR intent_row.environment_id IS DISTINCT FROM NEW.environment_id
        OR intent_row.cluster_id IS DISTINCT FROM NEW.cluster_id
        OR intent_row.kubernetes_namespace IS DISTINCT FROM NEW.kubernetes_namespace
        OR intent_row.custody_mode IS DISTINCT FROM NEW.custody_mode
        OR intent_row.intent_digest IS DISTINCT FROM NEW.intent_digest
        OR intent_row.config_map_uid IS DISTINCT FROM NEW.config_map_uid
        OR intent_row.expected_resource_version IS DISTINCT FROM NEW.expected_resource_version
        OR intent_row.public_data_digest IS DISTINCT FROM NEW.public_data_digest
        OR NEW.expected_resource_version = NEW.observed_resource_version THEN
        RAISE EXCEPTION 'Account JWT publication receipt does not match current intent' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_jwt_jwks_validate_mount_observation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    intent_row account_jwt_jwks_prepublication_intents%ROWTYPE;
    receipt_row account_jwt_jwks_publication_receipts%ROWTYPE;
BEGIN
    PERFORM account_jwt_jwks_lock_current_generation(
        NEW.operation_id, NEW.environment_id, NEW.cluster_id, NEW.kubernetes_namespace,
        NEW.custody_mode, (SELECT intent.operation_digest FROM account_jwt_jwks_prepublication_intents AS intent
                           WHERE intent.operation_id = NEW.operation_id),
        (SELECT intent.generation_request_digest FROM account_jwt_jwks_prepublication_intents AS intent
                           WHERE intent.operation_id = NEW.operation_id),
        (SELECT intent.generation_receipt_digest FROM account_jwt_jwks_prepublication_intents AS intent
                           WHERE intent.operation_id = NEW.operation_id),
        (SELECT intent.desired_state_version FROM account_jwt_jwks_prepublication_intents AS intent
                           WHERE intent.operation_id = NEW.operation_id),
        (SELECT intent.expected_cluster_incarnation_uid FROM account_jwt_jwks_prepublication_intents AS intent
                           WHERE intent.operation_id = NEW.operation_id),
        (SELECT intent.expected_namespace_uid FROM account_jwt_jwks_prepublication_intents AS intent
                           WHERE intent.operation_id = NEW.operation_id),
        (SELECT intent.trust_binding_digest FROM account_jwt_jwks_prepublication_intents AS intent
                           WHERE intent.operation_id = NEW.operation_id),
        (SELECT intent.trust_config_revision FROM account_jwt_jwks_prepublication_intents AS intent
                           WHERE intent.operation_id = NEW.operation_id),
        (SELECT intent.target_generation FROM account_jwt_jwks_prepublication_intents AS intent
                           WHERE intent.operation_id = NEW.operation_id),
        (SELECT intent.target_kid FROM account_jwt_jwks_prepublication_intents AS intent
                           WHERE intent.operation_id = NEW.operation_id),
        (SELECT intent.public_key_fingerprint FROM account_jwt_jwks_prepublication_intents AS intent
                           WHERE intent.operation_id = NEW.operation_id));
    SELECT intent.* INTO intent_row
      FROM account_jwt_jwks_prepublication_intents AS intent
      WHERE intent.operation_id = NEW.operation_id
      FOR UPDATE;
    SELECT receipt.* INTO receipt_row
      FROM account_jwt_jwks_publication_receipts AS receipt
      WHERE receipt.operation_id = NEW.operation_id
      FOR UPDATE;
    IF NOT FOUND
        OR intent_row.environment_id IS DISTINCT FROM NEW.environment_id
        OR intent_row.cluster_id IS DISTINCT FROM NEW.cluster_id
        OR intent_row.kubernetes_namespace IS DISTINCT FROM NEW.kubernetes_namespace
        OR intent_row.custody_mode IS DISTINCT FROM NEW.custody_mode
        OR intent_row.intent_digest IS DISTINCT FROM NEW.intent_digest
        OR receipt_row.intent_digest IS DISTINCT FROM NEW.intent_digest
        OR receipt_row.receipt_digest IS DISTINCT FROM NEW.publication_receipt_digest
        OR intent_row.generation_marker_digest IS DISTINCT FROM NEW.generation_marker_digest
        OR intent_row.public_data_digest IS DISTINCT FROM NEW.public_data_digest
        OR intent_row.public_key_fingerprint IS DISTINCT FROM NEW.public_key_fingerprint THEN
        RAISE EXCEPTION 'Mounted Account JWT correspondence is stale or mismatched' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_jwt_jwks_reject_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account JWT JWKS publication evidence is immutable' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER account_jwt_jwks_intent_current_guard
    BEFORE INSERT ON account_jwt_jwks_prepublication_intents
    FOR EACH ROW EXECUTE FUNCTION account_jwt_jwks_validate_public_intent();
CREATE TRIGGER account_jwt_jwks_receipt_current_guard
    BEFORE INSERT ON account_jwt_jwks_publication_receipts
    FOR EACH ROW EXECUTE FUNCTION account_jwt_jwks_validate_publication_receipt();
CREATE TRIGGER account_jwt_jwks_mount_current_guard
    BEFORE INSERT ON account_jwt_jwks_mount_observations
    FOR EACH ROW EXECUTE FUNCTION account_jwt_jwks_validate_mount_observation();

CREATE TRIGGER account_jwt_jwks_intent_immutable
    BEFORE UPDATE OR DELETE ON account_jwt_jwks_prepublication_intents
    FOR EACH ROW EXECUTE FUNCTION account_jwt_jwks_reject_mutation();
CREATE TRIGGER account_jwt_jwks_receipt_immutable
    BEFORE UPDATE OR DELETE ON account_jwt_jwks_publication_receipts
    FOR EACH ROW EXECUTE FUNCTION account_jwt_jwks_reject_mutation();
CREATE TRIGGER account_jwt_jwks_mount_immutable
    BEFORE UPDATE OR DELETE ON account_jwt_jwks_mount_observations
    FOR EACH ROW EXECUTE FUNCTION account_jwt_jwks_reject_mutation();

CREATE TRIGGER account_jwt_jwks_intent_no_truncate
    BEFORE TRUNCATE ON account_jwt_jwks_prepublication_intents
    FOR EACH STATEMENT EXECUTE FUNCTION account_jwt_jwks_reject_mutation();
CREATE TRIGGER account_jwt_jwks_receipt_no_truncate
    BEFORE TRUNCATE ON account_jwt_jwks_publication_receipts
    FOR EACH STATEMENT EXECUTE FUNCTION account_jwt_jwks_reject_mutation();
CREATE TRIGGER account_jwt_jwks_mount_no_truncate
    BEFORE TRUNCATE ON account_jwt_jwks_mount_observations
    FOR EACH STATEMENT EXECUTE FUNCTION account_jwt_jwks_reject_mutation();
-- [jooq ignore stop]
