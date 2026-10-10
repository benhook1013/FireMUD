-- Bind the exact Account-verified readiness proof bytes and the complete parsed proof projection
-- to the existing immutable signer, publication, inventory, and probe owner rows.
ALTER TABLE account_jwt_signer_promotion_operations
    ADD COLUMN readiness_evidence_preimage BYTEA NOT NULL;

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
    plan_row RECORD;
    proof_document JSONB;
    expected_plan JSONB;
    expected_generation JSONB;
    expected_publication JSONB;
    expected_verified_probes JSONB;
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

    IF NEW.readiness_evidence_preimage IS NULL
        OR octet_length(NEW.readiness_evidence_preimage) NOT BETWEEN 2 AND 1048576
        OR encode(sha256(NEW.readiness_evidence_preimage), 'hex')
            IS DISTINCT FROM NEW.readiness_evidence_digest THEN
        RAISE EXCEPTION 'JWT promotion readiness digest does not hash its exact proof preimage'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_promotion_readiness_digest';
    END IF;
    proof_document := convert_from(NEW.readiness_evidence_preimage, 'UTF8')::jsonb;
    IF jsonb_typeof(proof_document) IS DISTINCT FROM 'object'
        OR proof_document - ARRAY['digestVersion', 'plan', 'planDigest', 'generation',
            'publication', 'inventoryEvidenceReference', 'inventoryEvidenceDigest',
            'verifiedProbes'] <> '{}'::jsonb
        OR (SELECT count(*) FROM jsonb_object_keys(proof_document)) <> 8
        OR proof_document->>'digestVersion' IS DISTINCT FROM
            'account-jwt-readiness-promotion-proof/v1' THEN
        RAISE EXCEPTION 'JWT promotion readiness proof has an unexpected shape or version'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_promotion_readiness_binding';
    END IF;

    SELECT * INTO plan_row
      FROM account_jwt_readiness_probe_plans
     WHERE rotation_operation_id = NEW.generation_operation_id
       AND plan_digest = NEW.readiness_plan_digest FOR SHARE;
    plan_generation_receipt_digest := plan_row.generation_receipt_digest;
    plan_inventory_complete := plan_row.validator_inventory_complete;
    plan_expires_at := plan_row.expires_at_epoch_seconds;
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

    -- V2 receipts are mutable only through their one-way Account transition. Lock the exact
    -- ordered set before comparing the complete proof projection to those owner rows.
    PERFORM 1 FROM account_jwt_readiness_probe_entries
     WHERE rotation_operation_id = NEW.generation_operation_id
       AND plan_digest = NEW.readiness_plan_digest
     ORDER BY validator_id,
         CASE token_profile WHEN 'account-jwt-readiness-canary' THEN 0 WHEN 'control-ui' THEN 1
             WHEN 'player-bootstrap' THEN 2 WHEN 'game-session-account-delegation' THEN 3 ELSE 4 END,
         token_profile, audience, probe_kind
     FOR SHARE;

    SELECT jsonb_build_object(
        'digestVersion', 'account-jwt-readiness-probe-plan/v2',
        'rotationOperationId', plan_row.rotation_operation_id::text,
        'environmentId', plan_row.environment_id,
        'clusterId', plan_row.cluster_id,
        'namespace', plan_row.kubernetes_namespace,
        'custodyMode', plan_row.custody_mode,
        'operationDigest', plan_row.operation_digest,
        'generationRequestDigest', plan_row.generation_request_digest,
        'generationReceiptDigest', plan_row.generation_receipt_digest,
        'desiredStateVersion', plan_row.desired_state_version,
        'trustFence', jsonb_build_object(
            'clusterIncarnationUid', plan_row.expected_cluster_incarnation_uid::text,
            'namespaceUid', plan_row.expected_namespace_uid::text,
            'bindingDigest', plan_row.trust_binding_digest,
            'configRevision', plan_row.trust_config_revision),
        'targetGeneration', plan_row.target_generation::text,
        'targetKid', plan_row.target_kid,
        'targetPublicKeyFingerprint', plan_row.target_public_key_fingerprint,
        'expectedActive', CASE WHEN plan_row.expected_active_generation IS NULL
            THEN jsonb_build_object('present', false)
            ELSE jsonb_build_object('present', true,
                'generation', plan_row.expected_active_generation::text,
                'kid', plan_row.expected_active_kid) END,
        'expectedPublishedActive', CASE WHEN plan_row.expected_published_generation IS NULL
            THEN jsonb_build_object('present', false)
            ELSE jsonb_build_object('present', true,
                'generation', plan_row.expected_published_generation::text,
                'kid', plan_row.expected_published_kid) END,
        'publicationIntentDigest', plan_row.publication_intent_digest,
        'publicationReceiptDigest', plan_row.publication_receipt_digest,
        'mountedObservationDigest', plan_row.mounted_observation_digest,
        'applicabilityMatrixJson', plan_row.applicability_matrix_json,
        'applicabilityMatrixDigest', plan_row.applicability_matrix_digest,
        'validatorInventoryComplete', plan_row.validator_inventory_complete,
        'planVersion', plan_row.plan_version,
        'inventorySnapshotDigest', plan_row.inventory_snapshot_digest,
        'maximumCacheAgeSeconds', plan_row.maximum_cache_age_seconds,
        'plannedAtEpochSecond', plan_row.planned_at_epoch_seconds,
        'notBeforeEpochSecond', plan_row.not_before_epoch_seconds,
        'expiresAtEpochSecond', plan_row.expires_at_epoch_seconds,
        'entries', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'validatorId', entry.validator_id,
                'tokenProfile', entry.token_profile,
                'audience', entry.audience,
                'probeKind', entry.probe_kind,
                'jti', entry.jti::text,
                'targetGeneration', entry.target_generation::text,
                'targetKid', entry.target_kid,
                'expectedActive', CASE WHEN entry.expected_active_generation IS NULL
                    THEN jsonb_build_object('present', false)
                    ELSE jsonb_build_object('present', true,
                        'generation', entry.expected_active_generation::text,
                        'kid', entry.expected_active_kid) END,
                'registryVersion', entry.registry_version,
                'entryVersion', 1,
                'plannedIssuedAtEpochSecond', entry.planned_issued_at_epoch_seconds,
                'expiresAtEpochSecond', entry.expires_at_epoch_seconds)
                ORDER BY entry.validator_id,
                    CASE entry.token_profile WHEN 'account-jwt-readiness-canary' THEN 0 WHEN 'control-ui' THEN 1
                        WHEN 'player-bootstrap' THEN 2
                        WHEN 'game-session-account-delegation' THEN 3 ELSE 4 END,
                    entry.token_profile, entry.audience, entry.probe_kind)
              FROM account_jwt_readiness_probe_entries AS entry
             WHERE entry.rotation_operation_id = plan_row.rotation_operation_id
               AND entry.plan_digest = plan_row.plan_digest), '[]'::jsonb))
      INTO expected_plan;

    expected_generation := jsonb_build_object(
        'operationId', result_row.operation_id::text,
        'environmentId', result_row.environment_id,
        'clusterId', result_row.cluster_id,
        'namespace', result_row.kubernetes_namespace,
        'custodyMode', result_row.custody_mode,
        'operationDigest', result_row.operation_digest,
        'generationRequestDigest', result_row.generation_request_digest,
        'desiredStateVersion', result_row.desired_state_version,
        'trustFence', jsonb_build_object(
            'clusterIncarnationUid', result_row.expected_cluster_incarnation_uid::text,
            'namespaceUid', result_row.expected_namespace_uid::text,
            'bindingDigest', result_row.trust_binding_digest,
            'configRevision', result_row.trust_config_revision),
        'privateSecretName', result_row.private_secret_name,
        'secretUid', result_row.secret_uid::text,
        'expectedPriorResourceVersion', result_row.expected_prior_resource_version,
        'observedResourceVersion', result_row.observed_resource_version,
        'targetGeneration', result_row.target_generation::text,
        'targetKid', result_row.target_kid,
        'targetAlgorithm', result_row.target_algorithm,
        'publicKeyFingerprint', result_row.public_key_fingerprint,
        'publicJwkJson', result_row.public_jwk_json,
        'receiptDigest', result_row.receipt_digest);

    SELECT jsonb_build_object(
        'intent', jsonb_build_object(
            'operationId', intent.operation_id::text,
            'environmentId', intent.environment_id,
            'clusterId', intent.cluster_id,
            'namespace', intent.kubernetes_namespace,
            'operationDigest', intent.operation_digest,
            'generationRequestDigest', intent.generation_request_digest,
            'generationReceiptDigest', intent.generation_receipt_digest,
            'desiredStateVersion', intent.desired_state_version,
            'trustFence', jsonb_build_object(
                'clusterIncarnationUid', intent.expected_cluster_incarnation_uid::text,
                'namespaceUid', intent.expected_namespace_uid::text,
                'bindingDigest', intent.trust_binding_digest,
                'configRevision', intent.trust_config_revision),
            'apiBindingDigest', intent.api_binding_digest,
            'apiConfigRevision', intent.api_config_revision,
            'configMapName', intent.config_map_name,
            'configMapUid', intent.config_map_uid::text,
            'expectedResourceVersion', intent.expected_resource_version,
            'expectedSnapshotDigest', intent.expected_snapshot_digest,
            'targetGeneration', intent.target_generation::text,
            'targetKid', intent.target_kid,
            'publicKeyFingerprint', intent.public_key_fingerprint,
            'expectedDurableActive', intent.expected_durable_active_json::jsonb,
            'expectedPublishedActive', intent.expected_published_active_json::jsonb,
            'publicDataDigest', intent.public_data_digest,
            'generationMarkerDigest', intent.generation_marker_digest,
            'intentDigest', intent.intent_digest),
        'receipt', jsonb_build_object(
            'operationId', receipt.operation_id::text,
            'intentDigest', receipt.intent_digest,
            'configMapUid', receipt.config_map_uid::text,
            'expectedResourceVersion', receipt.expected_resource_version,
            'observedResourceVersion', receipt.observed_resource_version,
            'publicDataDigest', receipt.public_data_digest,
            'receiptDigest', receipt.receipt_digest),
        'mount', jsonb_build_object(
            'operationId', mount.operation_id::text,
            'intentDigest', mount.intent_digest,
            'publicationReceiptDigest', mount.publication_receipt_digest,
            'generationMarkerDigest', mount.generation_marker_digest,
            'publicDataDigest', mount.public_data_digest,
            'publicKeyFingerprint', mount.public_key_fingerprint,
            'observationDigest', mount.observation_digest))
      INTO expected_publication
      FROM account_jwt_jwks_prepublication_intents AS intent
      JOIN account_jwt_jwks_publication_receipts AS receipt
        ON receipt.operation_id = intent.operation_id
       AND receipt.intent_digest = intent.intent_digest
      JOIN account_jwt_jwks_mount_observations AS mount
        ON mount.operation_id = intent.operation_id
     WHERE intent.operation_id = NEW.generation_operation_id
       AND intent.intent_digest = NEW.prepublication_intent_digest
       AND receipt.receipt_digest = NEW.prepublication_receipt_digest
       AND mount.observation_digest = NEW.mounted_observation_digest;

    SELECT jsonb_agg(jsonb_build_object(
        'rotationOperationId', entry.rotation_operation_id::text,
        'planDigest', entry.plan_digest,
        'validatorId', entry.validator_id,
        'tokenProfile', entry.token_profile,
        'audience', entry.audience,
        'probeKind', entry.probe_kind,
        'jti', entry.jti::text,
        'targetGeneration', entry.target_generation::text,
        'targetKid', entry.target_kid,
        'expectedActive', CASE WHEN entry.expected_active_generation IS NULL
            THEN jsonb_build_object('present', false)
            ELSE jsonb_build_object('present', true,
                'generation', entry.expected_active_generation::text,
                'kid', entry.expected_active_kid) END,
        'registryVersion', entry.registry_version,
        'entryVersion', entry.entry_version,
        'plannedIssuedAtEpochSecond', entry.planned_issued_at_epoch_seconds,
        'expiresAtEpochSecond', entry.expires_at_epoch_seconds,
        'compactTokenSha256', entry.compact_token_sha256,
        'verificationReceipt', jsonb_build_object(
            'receiptVersion', entry.verification_receipt_version,
            'sourceEntryVersion', entry.verification_source_entry_version,
            'resultEntryVersion', entry.entry_version,
            'observedAtEpochSecond', entry.verified_at_epoch_seconds,
            'receiptSha256', entry.verification_receipt_sha256,
            'verifiedKid', entry.verified_kid,
            'validatorInstanceId', entry.validator_instance_id,
            'validatorBindingDigest', entry.validator_binding_digest,
            'validatorConfigRevision', entry.validator_config_revision,
            'validatorPeerUri', entry.validator_peer_uri,
            'validatorPeerSpkiSha256', entry.validator_peer_spki_sha256,
            'podReceiptCount', entry.pod_receipt_count,
            'podReceiptClosureSha256', entry.pod_receipt_closure_sha256))
        ORDER BY entry.validator_id,
            CASE entry.token_profile WHEN 'account-jwt-readiness-canary' THEN 0 WHEN 'control-ui' THEN 1
                WHEN 'player-bootstrap' THEN 2
                WHEN 'game-session-account-delegation' THEN 3 ELSE 4 END,
            entry.token_profile, entry.audience, entry.probe_kind)
      INTO expected_verified_probes
      FROM account_jwt_readiness_probe_entries AS entry
     WHERE entry.rotation_operation_id = NEW.generation_operation_id
       AND entry.plan_digest = NEW.readiness_plan_digest
       AND entry.state = 'VERIFIED';

    IF expected_publication IS NULL
        OR proof_document->'planDigest' IS DISTINCT FROM to_jsonb(plan_row.plan_digest)
        OR proof_document->'plan' IS DISTINCT FROM expected_plan
        OR proof_document->'generation' IS DISTINCT FROM expected_generation
        OR proof_document->'publication' IS DISTINCT FROM expected_publication
        OR proof_document->'inventoryEvidenceReference' IS DISTINCT FROM
            to_jsonb('protected-validator-inventory:' || plan_row.inventory_snapshot_digest)
        OR proof_document->'inventoryEvidenceDigest' IS DISTINCT FROM
            to_jsonb(plan_row.inventory_snapshot_digest)
        OR proof_document->'verifiedProbes' IS DISTINCT FROM expected_verified_probes THEN
        RAISE EXCEPTION 'JWT promotion proof does not match complete immutable owner evidence'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_promotion_readiness_binding';
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

CREATE FUNCTION account_jwt_signer_readiness_preimage_immutable_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.readiness_evidence_preimage IS DISTINCT FROM OLD.readiness_evidence_preimage THEN
        RAISE EXCEPTION 'Account JWT signer readiness proof preimage is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_jwt_signer_promotion_immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_jwt_signer_readiness_preimage_immutable
    BEFORE UPDATE OF readiness_evidence_preimage ON account_jwt_signer_promotion_operations
    FOR EACH ROW EXECUTE FUNCTION account_jwt_signer_readiness_preimage_immutable_guard();
-- [jooq ignore stop]
