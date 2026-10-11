-- Forward-only lifecycle extension for the existing Account-owned signer authority.  The
-- generation operation remains immutable; one separate promotion operation binds it to the
-- authenticated Secret/JWKS/probe evidence and owns the PREPARED -> COMMITTED|ABORTED CAS.

ALTER TABLE account_jwt_signer_promotion_operations
    DROP CONSTRAINT account_jwt_signer_promotion_action_check;

ALTER TABLE account_jwt_signer_promotion_operations
    DROP CONSTRAINT account_jwt_signer_promotion_slots_check;

ALTER TABLE account_jwt_signer_promotion_operations
    DROP CONSTRAINT account_jwt_signer_promotion_status_check;

ALTER TABLE account_jwt_signer_promotion_operations
    ADD COLUMN generation_operation_id UUID,
    ADD COLUMN expected_cluster_incarnation_uid UUID,
    ADD COLUMN expected_namespace_uid UUID,
    ADD COLUMN materializer_trust_binding_digest VARCHAR(64),
    ADD COLUMN materializer_trust_config_revision VARCHAR(128),
    ADD COLUMN api_binding_digest VARCHAR(64),
    ADD COLUMN api_config_revision VARCHAR(128),
    ADD COLUMN expected_private_secret_uid UUID,
    ADD COLUMN expected_public_config_map_uid UUID,
    ADD COLUMN prepublication_intent_digest VARCHAR(64),
    ADD COLUMN prepublication_receipt_digest VARCHAR(64),
    ADD COLUMN mounted_observation_digest VARCHAR(64),
    ADD COLUMN readiness_plan_digest VARCHAR(64),
    ADD COLUMN readiness_evidence_digest VARCHAR(64),
    ADD COLUMN expected_public_jwks_json TEXT,
    ADD COLUMN expected_active_generation_marker_json TEXT,
    ADD COLUMN private_promotion_observed_resource_version VARCHAR(256),
    ADD COLUMN private_promotion_receipt_digest VARCHAR(64),
    ADD COLUMN private_promotion_dispatched_at TIMESTAMPTZ,
    ADD COLUMN active_jwks_observed_resource_version VARCHAR(256),
    ADD COLUMN active_jwks_public_data_digest VARCHAR(64),
    ADD COLUMN active_jwks_receipt_digest VARCHAR(64),
    ADD CONSTRAINT account_jwt_signer_promotion_generation_fk
        FOREIGN KEY (generation_operation_id)
        REFERENCES account_jwt_signer_generation_operations(operation_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT account_jwt_signer_promotion_lifecycle_action_check
        CHECK ((operation_action = 'MATERIALIZE_PENDING'
                    AND allowed_private_slots_canonical_bytes = '\x5b2270656e64696e67225d'::bytea)
            OR (operation_action = 'PROMOTE_PENDING'
                    AND allowed_private_slots_canonical_bytes =
                        '\x5b2263757272656e74222c2270656e64696e67222c2270726576696f7573225d'::bytea)),
    ADD CONSTRAINT account_jwt_signer_promotion_lifecycle_status_check
        CHECK (status IN ('PREPARED', 'COMMITTED', 'ABORTED')),
    ADD CONSTRAINT account_jwt_signer_promotion_lifecycle_identity_check
        CHECK (operation_action <> 'PROMOTE_PENDING'
            OR (generation_operation_id IS NOT NULL
                AND expected_cluster_incarnation_uid IS NOT NULL
                AND expected_namespace_uid IS NOT NULL
                AND materializer_trust_binding_digest IS NOT NULL
                AND materializer_trust_binding_digest ~ '^[0-9a-f]{64}$'
                AND materializer_trust_config_revision IS NOT NULL
                AND materializer_trust_config_revision ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
                AND api_binding_digest IS NOT NULL
                AND api_binding_digest ~ '^[0-9a-f]{64}$'
                AND api_config_revision IS NOT NULL
                AND api_config_revision ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
                AND expected_private_secret_uid IS NOT NULL
                AND expected_public_config_map_uid IS NOT NULL
                AND prepublication_intent_digest IS NOT NULL
                AND prepublication_intent_digest ~ '^[0-9a-f]{64}$'
                AND prepublication_receipt_digest IS NOT NULL
                AND prepublication_receipt_digest ~ '^[0-9a-f]{64}$'
                AND mounted_observation_digest IS NOT NULL
                AND mounted_observation_digest ~ '^[0-9a-f]{64}$'
                AND readiness_plan_digest IS NOT NULL
                AND readiness_plan_digest ~ '^[0-9a-f]{64}$'
                AND readiness_evidence_digest IS NOT NULL
                AND readiness_evidence_digest ~ '^[0-9a-f]{64}$'
                AND expected_public_jwks_json IS NOT NULL
                AND expected_active_generation_marker_json IS NOT NULL)),
    ADD CONSTRAINT account_jwt_signer_promotion_lifecycle_uid_check
        CHECK ((expected_cluster_incarnation_uid IS NULL
                    OR expected_cluster_incarnation_uid <> '00000000-0000-0000-0000-000000000000'::UUID)
            AND (expected_namespace_uid IS NULL
                    OR expected_namespace_uid <> '00000000-0000-0000-0000-000000000000'::UUID)
            AND (expected_private_secret_uid IS NULL
                    OR expected_private_secret_uid <> '00000000-0000-0000-0000-000000000000'::UUID)
            AND (expected_public_config_map_uid IS NULL
                    OR expected_public_config_map_uid <> '00000000-0000-0000-0000-000000000000'::UUID)),
    ADD CONSTRAINT account_jwt_signer_promotion_lifecycle_digest_check
        CHECK ((materializer_trust_binding_digest IS NULL
                    OR materializer_trust_binding_digest ~ '^[0-9a-f]{64}$')
            AND (api_binding_digest IS NULL OR api_binding_digest ~ '^[0-9a-f]{64}$')
            AND (prepublication_intent_digest IS NULL
                    OR prepublication_intent_digest ~ '^[0-9a-f]{64}$')
            AND (prepublication_receipt_digest IS NULL
                    OR prepublication_receipt_digest ~ '^[0-9a-f]{64}$')
            AND (mounted_observation_digest IS NULL
                    OR mounted_observation_digest ~ '^[0-9a-f]{64}$')
            AND (readiness_plan_digest IS NULL
                    OR readiness_plan_digest ~ '^[0-9a-f]{64}$')
            AND (readiness_evidence_digest IS NULL
                    OR readiness_evidence_digest ~ '^[0-9a-f]{64}$')
            AND (private_promotion_receipt_digest IS NULL
                    OR private_promotion_receipt_digest ~ '^[0-9a-f]{64}$')
            AND (active_jwks_public_data_digest IS NULL
                    OR active_jwks_public_data_digest ~ '^[0-9a-f]{64}$')
            AND (active_jwks_receipt_digest IS NULL
                    OR active_jwks_receipt_digest ~ '^[0-9a-f]{64}$')),
    ADD CONSTRAINT account_jwt_signer_promotion_lifecycle_rv_check
        CHECK ((private_promotion_observed_resource_version IS NULL
                    OR (private_promotion_observed_resource_version ~ '^[!-~]+$'
                        AND char_length(private_promotion_observed_resource_version) BETWEEN 1 AND 256))
            AND (active_jwks_observed_resource_version IS NULL
                    OR (active_jwks_observed_resource_version ~ '^[!-~]+$'
                        AND char_length(active_jwks_observed_resource_version) BETWEEN 1 AND 256))),
    ADD CONSTRAINT account_jwt_signer_promotion_lifecycle_content_check
        CHECK ((expected_public_jwks_json IS NULL OR octet_length(expected_public_jwks_json) BETWEEN 1 AND 262144)
            AND (expected_active_generation_marker_json IS NULL
                    OR octet_length(expected_active_generation_marker_json) BETWEEN 1 AND 16384)),
    ADD CONSTRAINT account_jwt_signer_promotion_dispatch_check
        CHECK ((private_promotion_receipt_digest IS NULL
                    OR private_promotion_dispatched_at IS NOT NULL)
            AND (status <> 'COMMITTED'
                    OR (private_promotion_dispatched_at IS NOT NULL
                        AND private_promotion_receipt_digest IS NOT NULL
                        AND active_jwks_receipt_digest IS NOT NULL))
            AND (status <> 'ABORTED'
                    OR (private_promotion_dispatched_at IS NULL
                        AND private_promotion_receipt_digest IS NULL
                        AND active_jwks_receipt_digest IS NULL)));

-- Enrollment is admitted only after both independently protected bindings and a live read of the
-- fixed pre-created public ConfigMap agree on the environment/cluster/namespace incarnation.
-- Existing rows with NULL pins are intentionally not backfilled or promoted.
ALTER TABLE account_jwt_signer_desired_states
    ADD COLUMN enrollment_cluster_incarnation_uid UUID,
    ADD COLUMN enrollment_namespace_uid UUID,
    ADD COLUMN enrollment_materializer_binding_digest VARCHAR(64),
    ADD COLUMN enrollment_materializer_config_revision VARCHAR(128),
    ADD COLUMN enrollment_api_binding_digest VARCHAR(64),
    ADD COLUMN enrollment_api_config_revision VARCHAR(128),
    ADD COLUMN enrollment_public_config_map_uid UUID,
    ADD COLUMN enrollment_public_config_map_resource_version VARCHAR(256),
    ADD COLUMN enrollment_public_config_map_snapshot_digest VARCHAR(64),
    ADD CONSTRAINT account_jwt_signer_enrollment_shape_check
        CHECK ((enrollment_cluster_incarnation_uid IS NULL
                    AND enrollment_namespace_uid IS NULL
                    AND enrollment_materializer_binding_digest IS NULL
                    AND enrollment_materializer_config_revision IS NULL
                    AND enrollment_api_binding_digest IS NULL
                    AND enrollment_api_config_revision IS NULL
                    AND enrollment_public_config_map_uid IS NULL
                    AND enrollment_public_config_map_resource_version IS NULL
                    AND enrollment_public_config_map_snapshot_digest IS NULL)
            OR (enrollment_cluster_incarnation_uid <> '00000000-0000-0000-0000-000000000000'::UUID
                AND enrollment_cluster_incarnation_uid IS NOT NULL
                AND enrollment_namespace_uid IS NOT NULL
                AND enrollment_namespace_uid <> '00000000-0000-0000-0000-000000000000'::UUID
                AND enrollment_materializer_binding_digest IS NOT NULL
                AND enrollment_materializer_binding_digest ~ '^[0-9a-f]{64}$'
                AND enrollment_materializer_config_revision IS NOT NULL
                AND enrollment_materializer_config_revision ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
                AND enrollment_api_binding_digest IS NOT NULL
                AND enrollment_api_binding_digest ~ '^[0-9a-f]{64}$'
                AND enrollment_api_config_revision IS NOT NULL
                AND enrollment_api_config_revision ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
                AND enrollment_public_config_map_uid IS NOT NULL
                AND enrollment_public_config_map_uid <> '00000000-0000-0000-0000-000000000000'::UUID
                AND enrollment_public_config_map_resource_version IS NOT NULL
                AND enrollment_public_config_map_resource_version ~ '^[!-~]+$'
                AND char_length(enrollment_public_config_map_resource_version) BETWEEN 1 AND 256
                AND enrollment_public_config_map_snapshot_digest IS NOT NULL
                AND enrollment_public_config_map_snapshot_digest ~ '^[0-9a-f]{64}$'));

-- A generation whose readiness proof cannot enter PREPARED may be terminally abandoned by
-- Account. This receipt only releases that exact current pointer; it never changes either active
-- fence or records authority to materialize, promote, or replace a key.
CREATE TABLE account_jwt_signer_generation_abort_receipts (
    operation_id UUID PRIMARY KEY,
    environment_id VARCHAR(63) NOT NULL,
    cluster_id VARCHAR(128) NOT NULL,
    kubernetes_namespace VARCHAR(63) NOT NULL,
    custody_mode VARCHAR(64) NOT NULL,
    operation_digest VARCHAR(64) NOT NULL,
    generation_request_digest VARCHAR(64) NOT NULL,
    generation_receipt_digest VARCHAR(64) NOT NULL,
    expected_state_version BIGINT NOT NULL,
    resulting_state_version BIGINT NOT NULL,
    expected_cluster_incarnation_uid UUID NOT NULL,
    expected_namespace_uid UUID NOT NULL,
    trust_binding_digest VARCHAR(64) NOT NULL,
    trust_config_revision VARCHAR(128) NOT NULL,
    durable_active_generation BIGINT,
    durable_active_kid VARCHAR(128),
    published_active_generation BIGINT,
    published_active_kid VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_jwt_generation_abort_state_fk
        FOREIGN KEY (environment_id, cluster_id, kubernetes_namespace, custody_mode)
        REFERENCES account_jwt_signer_desired_states(
            environment_id, cluster_id, kubernetes_namespace, custody_mode)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_generation_abort_operation_fk
        FOREIGN KEY (operation_id)
        REFERENCES account_jwt_signer_generation_operations(operation_id)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_generation_abort_result_fk
        FOREIGN KEY (operation_id)
        REFERENCES account_jwt_signer_generation_results(operation_id)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_generation_abort_version_check
        CHECK (expected_state_version > 1
            AND expected_state_version < 9223372036854775807
            AND resulting_state_version = expected_state_version + 1),
    CONSTRAINT account_jwt_generation_abort_digest_check
        CHECK (operation_digest ~ '^[0-9a-f]{64}$'
            AND generation_request_digest ~ '^[0-9a-f]{64}$'
            AND generation_receipt_digest ~ '^[0-9a-f]{64}$'
            AND trust_binding_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT account_jwt_generation_abort_trust_check
        CHECK (expected_cluster_incarnation_uid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND expected_namespace_uid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND trust_config_revision ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT account_jwt_generation_abort_active_shape_check
        CHECK (((durable_active_generation IS NULL AND durable_active_kid IS NULL)
                    OR (durable_active_generation > 0
                        AND durable_active_kid ~ '^[A-Za-z0-9_-]{1,64}$'))
            AND ((published_active_generation IS NULL AND published_active_kid IS NULL)
                    OR (published_active_generation > 0
                        AND published_active_kid ~ '^[A-Za-z0-9_-]{1,64}$')))
);

-- PREPARED proof is immutable. Account first durably records its one-way peer dispatch before
-- returning mutation authority. Only the two exact public-only CAS receipts may then be filled
-- once, followed by one terminal state transition. No receipt includes, hashes, or returns
-- private Secret bytes.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_jwt_signer_operation_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    private_receipt_changed BOOLEAN;
    private_dispatch_changed BOOLEAN;
    jwks_receipt_changed BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Account JWT signer promotion history cannot be deleted'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_operation_immutable';
    END IF;
    IF (NEW.operation_id, NEW.environment_id, NEW.cluster_id, NEW.kubernetes_namespace,
        NEW.custody_mode, NEW.request_digest_version, NEW.request_digest,
        NEW.expected_record_version, NEW.expected_previous_generation, NEW.expected_previous_kid,
        NEW.target_generation, NEW.target_kid, NEW.target_algorithm,
        NEW.target_public_key_fingerprint, NEW.expected_private_secret_resource_version,
        NEW.expected_public_jwks_resource_version, NEW.expected_public_active_generation,
        NEW.expected_public_active_kid, NEW.operation_action,
        NEW.allowed_private_slots_canonical_bytes, NEW.generation_operation_id,
        NEW.expected_cluster_incarnation_uid, NEW.expected_namespace_uid,
        NEW.materializer_trust_binding_digest, NEW.materializer_trust_config_revision,
        NEW.api_binding_digest, NEW.api_config_revision, NEW.expected_private_secret_uid,
        NEW.expected_public_config_map_uid, NEW.prepublication_intent_digest,
        NEW.prepublication_receipt_digest, NEW.mounted_observation_digest,
        NEW.readiness_plan_digest, NEW.readiness_evidence_digest,
        NEW.expected_public_jwks_json, NEW.expected_active_generation_marker_json,
        NEW.created_at)
       IS DISTINCT FROM
       (OLD.operation_id, OLD.environment_id, OLD.cluster_id, OLD.kubernetes_namespace,
        OLD.custody_mode, OLD.request_digest_version, OLD.request_digest,
        OLD.expected_record_version, OLD.expected_previous_generation, OLD.expected_previous_kid,
        OLD.target_generation, OLD.target_kid, OLD.target_algorithm,
        OLD.target_public_key_fingerprint, OLD.expected_private_secret_resource_version,
        OLD.expected_public_jwks_resource_version, OLD.expected_public_active_generation,
        OLD.expected_public_active_kid, OLD.operation_action,
        OLD.allowed_private_slots_canonical_bytes, OLD.generation_operation_id,
        OLD.expected_cluster_incarnation_uid, OLD.expected_namespace_uid,
        OLD.materializer_trust_binding_digest, OLD.materializer_trust_config_revision,
        OLD.api_binding_digest, OLD.api_config_revision, OLD.expected_private_secret_uid,
        OLD.expected_public_config_map_uid, OLD.prepublication_intent_digest,
        OLD.prepublication_receipt_digest, OLD.mounted_observation_digest,
        OLD.readiness_plan_digest, OLD.readiness_evidence_digest,
        OLD.expected_public_jwks_json, OLD.expected_active_generation_marker_json,
        OLD.created_at) THEN
        RAISE EXCEPTION 'Account JWT signer promotion request is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_operation_immutable';
    END IF;
    IF OLD.status <> 'PREPARED' OR NEW.status NOT IN ('PREPARED', 'COMMITTED', 'ABORTED') THEN
        RAISE EXCEPTION 'Account JWT signer promotion is already terminal'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_operation_immutable';
    END IF;

    private_receipt_changed :=
        (NEW.private_promotion_observed_resource_version,
         NEW.private_promotion_receipt_digest)
        IS DISTINCT FROM
        (OLD.private_promotion_observed_resource_version,
         OLD.private_promotion_receipt_digest);
    private_dispatch_changed :=
        NEW.private_promotion_dispatched_at IS DISTINCT FROM OLD.private_promotion_dispatched_at;
    jwks_receipt_changed :=
        (NEW.active_jwks_observed_resource_version, NEW.active_jwks_public_data_digest,
         NEW.active_jwks_receipt_digest)
        IS DISTINCT FROM
        (OLD.active_jwks_observed_resource_version, OLD.active_jwks_public_data_digest,
         OLD.active_jwks_receipt_digest);

    IF private_receipt_changed THEN
        IF OLD.private_promotion_receipt_digest IS NOT NULL
            OR NEW.private_promotion_receipt_digest IS NULL
            OR NEW.private_promotion_observed_resource_version IS NULL
            OR jwks_receipt_changed OR NEW.status <> 'PREPARED' THEN
            RAISE EXCEPTION 'JWT private promotion receipt is not a first exact readback'
                USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_operation_immutable';
        END IF;
    END IF;
    IF private_dispatch_changed THEN
        IF OLD.private_promotion_dispatched_at IS NOT NULL
            OR NEW.private_promotion_dispatched_at IS NULL
            OR NEW.status <> 'PREPARED'
            OR private_receipt_changed OR jwks_receipt_changed THEN
            RAISE EXCEPTION 'JWT private promotion dispatch is not a first durable authorization'
                USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_operation_immutable';
        END IF;
    END IF;
    IF jwks_receipt_changed THEN
        IF OLD.active_jwks_receipt_digest IS NOT NULL
            OR NEW.active_jwks_receipt_digest IS NULL
            OR NEW.active_jwks_observed_resource_version IS NULL
            OR NEW.active_jwks_public_data_digest IS NULL
            OR OLD.private_promotion_receipt_digest IS NULL
            OR NEW.status <> 'PREPARED' THEN
            RAISE EXCEPTION 'JWT active JWKS receipt is not a first exact readback'
                USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_operation_immutable';
        END IF;
    END IF;
    IF NEW.status = OLD.status AND NOT private_dispatch_changed
        AND NOT private_receipt_changed AND NOT jwks_receipt_changed THEN
        RAISE EXCEPTION 'JWT promotion update has no exact receipt transition'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_operation_immutable';
    END IF;
    IF NEW.status = 'COMMITTED'
        AND (NEW.private_promotion_dispatched_at IS NULL
            OR NEW.private_promotion_receipt_digest IS NULL
            OR NEW.active_jwks_receipt_digest IS NULL) THEN
        RAISE EXCEPTION 'JWT promotion cannot commit without both exact resource receipts'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_operation_immutable';
    END IF;
    IF NEW.status = 'ABORTED'
        AND (OLD.private_promotion_dispatched_at IS NOT NULL
            OR OLD.private_promotion_receipt_digest IS NOT NULL
            OR OLD.active_jwks_receipt_digest IS NOT NULL) THEN
        RAISE EXCEPTION 'Dispatched JWT promotion must remain PREPARED for reconciliation'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_operation_immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION account_jwt_signer_desired_state_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    operation_row RECORD;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Account JWT signer desired state cannot be deleted'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_desired_state_immutable';
    END IF;
    IF (NEW.environment_id, NEW.cluster_id, NEW.kubernetes_namespace, NEW.custody_mode,
        NEW.private_secret_name, NEW.public_jwks_config_map_name, NEW.created_at,
        NEW.enrollment_cluster_incarnation_uid, NEW.enrollment_namespace_uid,
        NEW.enrollment_materializer_binding_digest, NEW.enrollment_materializer_config_revision,
        NEW.enrollment_api_binding_digest, NEW.enrollment_api_config_revision,
        NEW.enrollment_public_config_map_uid, NEW.enrollment_public_config_map_resource_version,
        NEW.enrollment_public_config_map_snapshot_digest)
       IS DISTINCT FROM
       (OLD.environment_id, OLD.cluster_id, OLD.kubernetes_namespace, OLD.custody_mode,
        OLD.private_secret_name, OLD.public_jwks_config_map_name, OLD.created_at,
        OLD.enrollment_cluster_incarnation_uid, OLD.enrollment_namespace_uid,
        OLD.enrollment_materializer_binding_digest, OLD.enrollment_materializer_config_revision,
        OLD.enrollment_api_binding_digest, OLD.enrollment_api_config_revision,
        OLD.enrollment_public_config_map_uid, OLD.enrollment_public_config_map_resource_version,
        OLD.enrollment_public_config_map_snapshot_digest)
        OR NEW.record_version <> OLD.record_version + 1 THEN
        RAISE EXCEPTION 'Account JWT signer desired state permits only a versioned owner transition'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_desired_state_immutable';
    END IF;

    -- Creating a generation operation is the only transition from an idle state to a new key
    -- request.  Active identities and publication fences remain unchanged.
    IF OLD.generation_operation_id IS NULL AND OLD.prepared_operation_id IS NULL
        AND NEW.generation_operation_id IS NOT NULL AND NEW.prepared_operation_id IS NULL
        AND NEW.durable_active_generation IS NOT DISTINCT FROM OLD.durable_active_generation
        AND NEW.durable_active_kid IS NOT DISTINCT FROM OLD.durable_active_kid
        AND NEW.published_active_generation IS NOT DISTINCT FROM OLD.published_active_generation
        AND NEW.published_active_kid IS NOT DISTINCT FROM OLD.published_active_kid THEN
        IF OLD.enrollment_cluster_incarnation_uid IS NULL
            OR OLD.enrollment_namespace_uid IS NULL
            OR OLD.enrollment_materializer_binding_digest IS NULL
            OR OLD.enrollment_api_binding_digest IS NULL
            OR OLD.enrollment_public_config_map_uid IS NULL THEN
            RAISE EXCEPTION 'JWT signer state has no independently protected enrollment pins'
                USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_generation_operation_binding';
        END IF;
        PERFORM 1 FROM account_jwt_signer_generation_operations AS generation_op
          WHERE generation_op.operation_id = NEW.generation_operation_id
            AND generation_op.environment_id = NEW.environment_id
            AND generation_op.cluster_id = NEW.cluster_id
            AND generation_op.kubernetes_namespace = NEW.kubernetes_namespace
            AND generation_op.custody_mode = NEW.custody_mode
            AND generation_op.expected_record_version = OLD.record_version
            AND generation_op.expected_previous_generation IS NOT DISTINCT FROM OLD.durable_active_generation
            AND generation_op.expected_previous_kid IS NOT DISTINCT FROM OLD.durable_active_kid
            AND generation_op.expected_published_generation IS NOT DISTINCT FROM OLD.published_active_generation
            AND generation_op.expected_published_kid IS NOT DISTINCT FROM OLD.published_active_kid;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'JWT generation pointer is not the exact current Account operation'
                USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_generation_operation_binding';
        END IF;
        RETURN NEW;
    END IF;

    -- Abandon only one complete, exact UNPREPARED generation. Both active fences stay untouched;
    -- the immutable Account receipt is the only authority to release the generation pointer.
    IF OLD.generation_operation_id IS NOT NULL AND OLD.prepared_operation_id IS NULL
        AND NEW.generation_operation_id IS NULL AND NEW.prepared_operation_id IS NULL
        AND NEW.durable_active_generation IS NOT DISTINCT FROM OLD.durable_active_generation
        AND NEW.durable_active_kid IS NOT DISTINCT FROM OLD.durable_active_kid
        AND NEW.published_active_generation IS NOT DISTINCT FROM OLD.published_active_generation
        AND NEW.published_active_kid IS NOT DISTINCT FROM OLD.published_active_kid THEN
        SELECT receipt.* INTO operation_row
          FROM account_jwt_signer_generation_abort_receipts AS receipt
         WHERE receipt.operation_id = OLD.generation_operation_id
           AND receipt.environment_id = OLD.environment_id
           AND receipt.cluster_id = OLD.cluster_id
           AND receipt.kubernetes_namespace = OLD.kubernetes_namespace
           AND receipt.custody_mode = OLD.custody_mode
           AND receipt.expected_state_version = OLD.record_version
           AND receipt.resulting_state_version = NEW.record_version
           AND receipt.durable_active_generation IS NOT DISTINCT FROM OLD.durable_active_generation
           AND receipt.durable_active_kid IS NOT DISTINCT FROM OLD.durable_active_kid
           AND receipt.published_active_generation IS NOT DISTINCT FROM OLD.published_active_generation
           AND receipt.published_active_kid IS NOT DISTINCT FROM OLD.published_active_kid;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'JWT generation pointer release lacks its exact Account abort receipt'
                USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_generation_abort_binding';
        END IF;
        RETURN NEW;
    END IF;

    -- Enter PREPARED only after insertion of the exact durable promotion request.
    IF OLD.generation_operation_id IS NOT NULL AND OLD.prepared_operation_id IS NULL
        AND NEW.generation_operation_id IS NULL AND NEW.prepared_operation_id IS NOT NULL
        AND NEW.durable_active_generation IS NOT DISTINCT FROM OLD.durable_active_generation
        AND NEW.durable_active_kid IS NOT DISTINCT FROM OLD.durable_active_kid
        AND NEW.published_active_generation IS NOT DISTINCT FROM OLD.published_active_generation
        AND NEW.published_active_kid IS NOT DISTINCT FROM OLD.published_active_kid THEN
        SELECT promotion.* INTO operation_row
          FROM account_jwt_signer_promotion_operations AS promotion
         WHERE promotion.operation_id = NEW.prepared_operation_id
           AND promotion.generation_operation_id = OLD.generation_operation_id
           AND promotion.environment_id = OLD.environment_id
           AND promotion.cluster_id = OLD.cluster_id
           AND promotion.kubernetes_namespace = OLD.kubernetes_namespace
           AND promotion.custody_mode = OLD.custody_mode
           AND promotion.expected_record_version = OLD.record_version
           AND promotion.expected_previous_generation IS NOT DISTINCT FROM OLD.durable_active_generation
           AND promotion.expected_previous_kid IS NOT DISTINCT FROM OLD.durable_active_kid
           AND promotion.expected_public_active_generation IS NOT DISTINCT FROM OLD.published_active_generation
           AND promotion.expected_public_active_kid IS NOT DISTINCT FROM OLD.published_active_kid
           AND promotion.status = 'PREPARED'
           AND promotion.operation_action = 'PROMOTE_PENDING';
        IF NOT FOUND THEN
            RAISE EXCEPTION 'JWT PREPARED pointer is not the exact Account promotion operation'
                USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_prepared_operation_binding';
        END IF;
        RETURN NEW;
    END IF;

    -- Close only the exact PREPARED operation. COMMITTED changes both active fences to target;
    -- ABORTED preserves both previous fences. No resource observation can perform either CAS.
    IF OLD.generation_operation_id IS NULL AND OLD.prepared_operation_id IS NOT NULL
        AND NEW.generation_operation_id IS NULL AND NEW.prepared_operation_id IS NULL THEN
        SELECT promotion.* INTO operation_row
          FROM account_jwt_signer_promotion_operations AS promotion
         WHERE promotion.operation_id = OLD.prepared_operation_id
           AND promotion.environment_id = OLD.environment_id
           AND promotion.cluster_id = OLD.cluster_id
           AND promotion.kubernetes_namespace = OLD.kubernetes_namespace
           AND promotion.custody_mode = OLD.custody_mode
           AND promotion.expected_record_version + 1 = OLD.record_version
           AND promotion.status IN ('COMMITTED', 'ABORTED');
        IF NOT FOUND THEN
            RAISE EXCEPTION 'JWT promotion terminal transition has no exact durable result'
                USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_prepared_operation_binding';
        END IF;
        IF operation_row.status = 'COMMITTED' THEN
            IF NEW.durable_active_generation IS DISTINCT FROM operation_row.target_generation
                OR NEW.durable_active_kid IS DISTINCT FROM operation_row.target_kid
                OR NEW.published_active_generation IS DISTINCT FROM operation_row.target_generation
                OR NEW.published_active_kid IS DISTINCT FROM operation_row.target_kid THEN
                RAISE EXCEPTION 'Committed JWT signer and published active fences must equal target'
                    USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_prepared_operation_binding';
            END IF;
        ELSIF NEW.durable_active_generation IS DISTINCT FROM OLD.durable_active_generation
            OR NEW.durable_active_kid IS DISTINCT FROM OLD.durable_active_kid
            OR NEW.published_active_generation IS DISTINCT FROM OLD.published_active_generation
            OR NEW.published_active_kid IS DISTINCT FROM OLD.published_active_kid THEN
            RAISE EXCEPTION 'Aborted JWT signer operation must preserve previous active fences'
                USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_prepared_operation_binding';
        END IF;
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'Account JWT signer state transition is not in the lifecycle CAS'
        USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_generation_operation_binding';
END;
$$;

CREATE FUNCTION account_jwt_signer_promotion_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    state_row RECORD;
    generation_row RECORD;
    result_row RECORD;
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
        OR result_row.receipt_digest IS DISTINCT FROM NEW.generation_receipt_digest
        OR result_row.public_key_fingerprint IS DISTINCT FROM NEW.target_public_key_fingerprint
        OR EXISTS (
            SELECT 1 FROM account_jwt_signer_generation_abort_receipts AS abort_receipt
             WHERE abort_receipt.operation_id = NEW.generation_operation_id) THEN
        RAISE EXCEPTION 'JWT promotion request does not match current Account generation evidence'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_promotion_insert_binding';
    END IF;

    SELECT validator_inventory_complete, expires_at_epoch_seconds
      INTO plan_inventory_complete, plan_expires_at
      FROM account_jwt_readiness_probe_plans
     WHERE rotation_operation_id = NEW.generation_operation_id
       AND plan_digest = NEW.readiness_plan_digest FOR SHARE;
    IF plan_inventory_complete IS DISTINCT FROM TRUE
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

CREATE TRIGGER account_jwt_signer_promotion_insert_guard
    BEFORE INSERT ON account_jwt_signer_promotion_operations
    FOR EACH ROW EXECUTE FUNCTION account_jwt_signer_promotion_insert_guard();

CREATE FUNCTION account_jwt_signer_promotion_no_truncate()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account JWT signer promotion history cannot be truncated'
        USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_operation_immutable';
    RETURN NULL;
END;
$$;
CREATE TRIGGER account_jwt_signer_promotion_no_truncate
    BEFORE TRUNCATE ON account_jwt_signer_promotion_operations
    FOR EACH STATEMENT EXECUTE FUNCTION account_jwt_signer_promotion_no_truncate();

CREATE FUNCTION account_jwt_generation_abort_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    state_row RECORD;
    generation_row RECORD;
    result_row RECORD;
BEGIN
    SELECT * INTO state_row
      FROM account_jwt_signer_desired_states
     WHERE environment_id = NEW.environment_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'JWT generation abort desired state is missing'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_generation_abort_binding';
    END IF;
    SELECT * INTO generation_row
      FROM account_jwt_signer_generation_operations
     WHERE operation_id = NEW.operation_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'JWT generation abort operation is missing'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_generation_abort_binding';
    END IF;
    SELECT * INTO result_row
      FROM account_jwt_signer_generation_results
     WHERE operation_id = NEW.operation_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'JWT generation abort result is missing'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_generation_abort_binding';
    END IF;

    IF (state_row.cluster_id, state_row.kubernetes_namespace, state_row.custody_mode,
        state_row.record_version, state_row.generation_operation_id, state_row.prepared_operation_id,
        state_row.durable_active_generation, state_row.durable_active_kid,
        state_row.published_active_generation, state_row.published_active_kid)
       IS DISTINCT FROM
       (NEW.cluster_id, NEW.kubernetes_namespace, NEW.custody_mode,
        NEW.expected_state_version, NEW.operation_id, NULL,
        NEW.durable_active_generation, NEW.durable_active_kid,
        NEW.published_active_generation, NEW.published_active_kid)
        OR (state_row.enrollment_cluster_incarnation_uid,
            state_row.enrollment_namespace_uid,
            state_row.enrollment_materializer_binding_digest,
            state_row.enrollment_materializer_config_revision)
            IS DISTINCT FROM
           (NEW.expected_cluster_incarnation_uid, NEW.expected_namespace_uid,
            NEW.trust_binding_digest, NEW.trust_config_revision)
        OR (generation_row.environment_id, generation_row.cluster_id,
            generation_row.kubernetes_namespace, generation_row.custody_mode,
            generation_row.operation_digest, generation_row.expected_record_version,
            generation_row.expected_previous_generation, generation_row.expected_previous_kid,
            generation_row.expected_published_generation, generation_row.expected_published_kid,
            generation_row.expected_cluster_incarnation_uid, generation_row.expected_namespace_uid,
            generation_row.trust_binding_digest, generation_row.trust_config_revision)
            IS DISTINCT FROM
           (NEW.environment_id, NEW.cluster_id, NEW.kubernetes_namespace, NEW.custody_mode,
            NEW.operation_digest, NEW.expected_state_version - 1,
            NEW.durable_active_generation, NEW.durable_active_kid,
            NEW.published_active_generation, NEW.published_active_kid,
            NEW.expected_cluster_incarnation_uid, NEW.expected_namespace_uid,
            NEW.trust_binding_digest, NEW.trust_config_revision)
        OR (result_row.environment_id, result_row.cluster_id,
            result_row.kubernetes_namespace, result_row.custody_mode,
            result_row.operation_digest, result_row.generation_request_digest,
            result_row.receipt_digest, result_row.desired_state_version,
            result_row.expected_cluster_incarnation_uid, result_row.expected_namespace_uid,
            result_row.trust_binding_digest, result_row.trust_config_revision,
            result_row.target_generation, result_row.target_kid,
            result_row.public_key_fingerprint)
            IS DISTINCT FROM
           (NEW.environment_id, NEW.cluster_id, NEW.kubernetes_namespace, NEW.custody_mode,
            NEW.operation_digest, NEW.generation_request_digest,
            NEW.generation_receipt_digest, NEW.expected_state_version,
            NEW.expected_cluster_incarnation_uid, NEW.expected_namespace_uid,
            NEW.trust_binding_digest, NEW.trust_config_revision,
            generation_row.target_generation, generation_row.target_kid,
            result_row.public_key_fingerprint)
        OR EXISTS (
            SELECT 1 FROM account_jwt_signer_promotion_operations promotion
             WHERE promotion.generation_operation_id = NEW.operation_id) THEN
        RAISE EXCEPTION 'JWT generation abort receipt does not bind the exact current UNPREPARED operation'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_generation_abort_binding';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_jwt_generation_abort_immutable_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account JWT signer generation abort receipts are immutable'
        USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_generation_abort_immutable';
    RETURN NULL;
END;
$$;

CREATE FUNCTION account_jwt_generation_abort_terminal_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    state_row RECORD;
BEGIN
    SELECT * INTO state_row
      FROM account_jwt_signer_desired_states
     WHERE environment_id = NEW.environment_id;
    IF NOT FOUND
        OR state_row.cluster_id IS DISTINCT FROM NEW.cluster_id
        OR state_row.kubernetes_namespace IS DISTINCT FROM NEW.kubernetes_namespace
        OR state_row.custody_mode IS DISTINCT FROM NEW.custody_mode
        OR state_row.record_version IS DISTINCT FROM NEW.resulting_state_version
        OR state_row.generation_operation_id IS NOT NULL
        OR state_row.prepared_operation_id IS NOT NULL
        OR state_row.durable_active_generation IS DISTINCT FROM NEW.durable_active_generation
        OR state_row.durable_active_kid IS DISTINCT FROM NEW.durable_active_kid
        OR state_row.published_active_generation IS DISTINCT FROM NEW.published_active_generation
        OR state_row.published_active_kid IS DISTINCT FROM NEW.published_active_kid THEN
        RAISE EXCEPTION 'Account generation abort receipt is not paired with its exact terminal state CAS'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_generation_abort_binding';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER account_jwt_generation_abort_insert_guard
    BEFORE INSERT ON account_jwt_signer_generation_abort_receipts
    FOR EACH ROW EXECUTE FUNCTION account_jwt_generation_abort_insert_guard();
CREATE TRIGGER account_jwt_generation_abort_immutable_guard
    BEFORE UPDATE OR DELETE ON account_jwt_signer_generation_abort_receipts
    FOR EACH ROW EXECUTE FUNCTION account_jwt_generation_abort_immutable_guard();
CREATE TRIGGER account_jwt_generation_abort_no_truncate
    BEFORE TRUNCATE ON account_jwt_signer_generation_abort_receipts
    FOR EACH STATEMENT EXECUTE FUNCTION account_jwt_generation_abort_immutable_guard();
CREATE CONSTRAINT TRIGGER account_jwt_generation_abort_terminal_guard
    AFTER INSERT ON account_jwt_signer_generation_abort_receipts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_jwt_generation_abort_terminal_guard();
-- [jooq ignore stop]
