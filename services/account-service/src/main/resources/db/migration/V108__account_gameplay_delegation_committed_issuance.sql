-- A signed candidate becomes durably COMMITTED only after Account has verified the signer-owned
-- candidate proof, exact pinned Coordination Redis receipt, current authority projection and
-- current source/evidence rows. COMMITTED remains non-authorizing; this migration adds no active
-- registry state or token recovery surface.
ALTER TABLE account_gameplay_delegation_issuance_operations
    DROP CONSTRAINT account_gameplay_delegation_status_check;

ALTER TABLE account_gameplay_delegation_issuance_operations
    ADD COLUMN commit_proof_version SMALLINT,
    ADD COLUMN commit_proof_sha256 VARCHAR(64),
    ADD COLUMN commit_proof_canonical_bytes BYTEA,
    ADD COLUMN committed_at TIMESTAMPTZ;

ALTER TABLE account_gameplay_delegation_issuance_operations
    ADD CONSTRAINT account_gameplay_delegation_status_check
        CHECK (status IN ('PENDING', 'COMMITTED'));

-- [jooq ignore start]
ALTER TABLE account_gameplay_delegation_issuance_operations
    ADD CONSTRAINT account_gameplay_delegation_commit_proof_check
        CHECK (
            (status = 'PENDING'
                AND commit_proof_version IS NULL
                AND commit_proof_sha256 IS NULL
                AND commit_proof_canonical_bytes IS NULL
                AND committed_at IS NULL)
            OR
            (status = 'COMMITTED'
                AND commit_proof_version IS NOT NULL
                AND commit_proof_version = 1
                AND commit_proof_sha256 IS NOT NULL
                AND commit_proof_sha256 ~ '^[0-9a-f]{64}$'
                AND commit_proof_canonical_bytes IS NOT NULL
                AND octet_length(commit_proof_canonical_bytes) BETWEEN 1 AND 32768
                AND committed_at IS NOT NULL
                AND jsonb_typeof(convert_from(commit_proof_canonical_bytes, 'UTF8')::jsonb) = 'object'
                AND (convert_from(commit_proof_canonical_bytes, 'UTF8')::jsonb) ?& ARRAY[
                    'schema', 'identity', 'tokenSha256', 'registryRecordSha256',
                    'registry', 'bundle', 'authority', 'signer', 'envelope']
                AND convert_from(commit_proof_canonical_bytes, 'UTF8')::jsonb->>'schema' =
                    'account-game-session-delegation-commit-proof/v1')
        );
-- [jooq ignore stop]

-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_gameplay_delegation_operation_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    proof JSONB;
    bundle_row RECORD;
    envelope_row RECORD;
    identity_proof JSONB;
    registry JSONB;
    bundle JSONB;
    authority JSONB;
    signer JSONB;
    envelope JSONB;
    proof_key_count INTEGER;
    identity_key_count INTEGER;
    registry_key_count INTEGER;
    bundle_key_count INTEGER;
    authority_key_count INTEGER;
    signer_key_count INTEGER;
    envelope_key_count INTEGER;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status IS DISTINCT FROM 'PENDING'
            OR NEW.token_hash IS NOT NULL
            OR NEW.signer_kid IS NOT NULL
            OR NEW.signer_generation IS NOT NULL
            OR NEW.pending_registry_candidate_bytes IS NOT NULL
            OR NEW.commit_proof_version IS NOT NULL
            OR NEW.commit_proof_sha256 IS NOT NULL
            OR NEW.commit_proof_canonical_bytes IS NOT NULL
            OR NEW.committed_at IS NOT NULL THEN
            RAISE EXCEPTION 'Account gameplay delegation issuance must begin as unbound PENDING intent'
                USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_initial_pending_only';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Account gameplay delegation issuance evidence cannot be deleted'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_operation_immutable';
    END IF;

    IF NEW.operation_id IS DISTINCT FROM OLD.operation_id
        OR NEW.request_id IS DISTINCT FROM OLD.request_id
        OR NEW.account_uuid IS DISTINCT FROM OLD.account_uuid
        OR NEW.caller_workload IS DISTINCT FROM OLD.caller_workload
        OR NEW.caller_context_id IS DISTINCT FROM OLD.caller_context_id
        OR NEW.request_digest_version IS DISTINCT FROM OLD.request_digest_version
        OR NEW.request_digest IS DISTINCT FROM OLD.request_digest
        OR NEW.token_jti IS DISTINCT FROM OLD.token_jti
        OR NEW.token_generation IS DISTINCT FROM OLD.token_generation
        OR NEW.issued_at_epoch_second IS DISTINCT FROM OLD.issued_at_epoch_second
        OR NEW.not_before_epoch_second IS DISTINCT FROM OLD.not_before_epoch_second
        OR NEW.expires_at_epoch_second IS DISTINCT FROM OLD.expires_at_epoch_second
        OR NEW.authority_issuer_generation IS DISTINCT FROM OLD.authority_issuer_generation
        OR NEW.authority_issuer_source_version IS DISTINCT FROM OLD.authority_issuer_source_version
        OR NEW.authority_account_generation IS DISTINCT FROM OLD.authority_account_generation
        OR NEW.authority_account_source_version IS DISTINCT FROM OLD.authority_account_source_version
        OR NEW.issuance_fence IS DISTINCT FROM OLD.issuance_fence
        OR NEW.issuance_fence_source_version IS DISTINCT FROM OLD.issuance_fence_source_version
        OR NEW.authority_tuple_canonical_bytes IS DISTINCT FROM OLD.authority_tuple_canonical_bytes
        OR NEW.membership_version_canonical_bytes IS DISTINCT FROM OLD.membership_version_canonical_bytes
        OR NEW.authority_source_versions_canonical_bytes IS DISTINCT FROM OLD.authority_source_versions_canonical_bytes
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Account gameplay delegation intent is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_operation_immutable';
    END IF;

    IF OLD.status = 'COMMITTED'
        OR OLD.commit_proof_version IS NOT NULL
        OR OLD.commit_proof_sha256 IS NOT NULL
        OR OLD.commit_proof_canonical_bytes IS NOT NULL
        OR OLD.committed_at IS NOT NULL THEN
        RAISE EXCEPTION 'Account gameplay delegation commit evidence is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_commit_immutable';
    END IF;

    IF NEW.status = 'PENDING' THEN
        IF NEW.commit_proof_version IS NOT NULL
            OR NEW.commit_proof_sha256 IS NOT NULL
            OR NEW.commit_proof_canonical_bytes IS NOT NULL
            OR NEW.committed_at IS NOT NULL THEN
            RAISE EXCEPTION 'Pending issuance cannot carry commit proof'
                USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_commit_shape';
        END IF;
        IF OLD.token_hash IS NOT NULL OR OLD.signer_kid IS NOT NULL
            OR OLD.signer_generation IS NOT NULL OR OLD.pending_registry_candidate_bytes IS NOT NULL
            OR NEW.token_hash IS NULL OR NEW.signer_kid IS NULL
            OR NEW.signer_generation IS NULL OR NEW.pending_registry_candidate_bytes IS NULL THEN
            RAISE EXCEPTION 'Account gameplay delegation token identity may be bound exactly once'
                USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_candidate_one_way';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.status IS DISTINCT FROM 'COMMITTED' OR OLD.status IS DISTINCT FROM 'PENDING'
        OR OLD.token_hash IS NULL OR OLD.signer_kid IS NULL
        OR OLD.signer_generation IS NULL OR OLD.pending_registry_candidate_bytes IS NULL
        OR NEW.token_hash IS DISTINCT FROM OLD.token_hash
        OR NEW.signer_kid IS DISTINCT FROM OLD.signer_kid
        OR NEW.signer_generation IS DISTINCT FROM OLD.signer_generation
        OR NEW.pending_registry_candidate_bytes IS DISTINCT FROM OLD.pending_registry_candidate_bytes
        OR NEW.commit_proof_version IS DISTINCT FROM 1
        OR NEW.commit_proof_sha256 IS NULL
        OR NEW.commit_proof_sha256 !~ '^[0-9a-f]{64}$'
        OR NEW.commit_proof_canonical_bytes IS NULL
        OR NEW.committed_at IS NULL THEN
        RAISE EXCEPTION 'Account gameplay delegation COMMITTED transition is malformed'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_commit_shape';
    END IF;

    proof := convert_from(NEW.commit_proof_canonical_bytes, 'UTF8')::jsonb;
    IF jsonb_typeof(proof) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'Account gameplay delegation commit proof is not a canonical object'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_commit_shape';
    END IF;
    SELECT count(*) INTO proof_key_count FROM jsonb_object_keys(proof);
    IF proof_key_count <> 9 THEN
        RAISE EXCEPTION 'Account gameplay delegation commit proof has unknown or missing fields'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_commit_shape';
    END IF;

    identity_proof := proof->'identity';
    registry := proof->'registry';
    bundle := proof->'bundle';
    authority := proof->'authority';
    signer := proof->'signer';
    envelope := proof->'envelope';

    IF jsonb_typeof(identity_proof) IS DISTINCT FROM 'object'
        OR jsonb_typeof(registry) IS DISTINCT FROM 'object'
        OR jsonb_typeof(bundle) IS DISTINCT FROM 'object'
        OR jsonb_typeof(authority) IS DISTINCT FROM 'object'
        OR jsonb_typeof(signer) IS DISTINCT FROM 'object'
        OR jsonb_typeof(envelope) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'Account gameplay delegation commit proof fields must be objects'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_commit_shape';
    END IF;
    SELECT count(*) INTO identity_key_count FROM jsonb_object_keys(identity_proof);
    SELECT count(*) INTO registry_key_count FROM jsonb_object_keys(registry);
    SELECT count(*) INTO bundle_key_count FROM jsonb_object_keys(bundle);
    SELECT count(*) INTO authority_key_count FROM jsonb_object_keys(authority);
    SELECT count(*) INTO signer_key_count FROM jsonb_object_keys(signer);
    SELECT count(*) INTO envelope_key_count FROM jsonb_object_keys(envelope);

    IF identity_key_count <> 12
        OR NOT (identity_proof ?& ARRAY[
            'operationId', 'requestId', 'accountId', 'requestDigest', 'callerWorkload',
            'callerContextId', 'tokenJti', 'tokenGeneration', 'issuedAtEpochSecond',
            'notBeforeEpochSecond', 'expiresAtEpochSecond', 'tokenSha256'])
        OR registry_key_count <> 16
        OR NOT (registry ?& ARRAY[
            'state', 'registryVersion', 'tokenKey', 'tokenHash', 'kid', 'signerGeneration',
            'operationId', 'requestId', 'requestDigest', 'absoluteExpiryMillis',
            'localAofCount', 'replicaAofCount', 'outcome', 'canonicalRecordSha256',
            'evidenceBundleSha256', 'aclIdentity'])
        OR bundle_key_count <> 4
        OR NOT (bundle ?& ARRAY['bundleVersion', 'sourceVersion', 'linearization', 'canonicalSha256'])
        OR authority_key_count <> 12
        OR NOT (authority ?& ARRAY[
            'issuerGeneration', 'issuerSourceVersion', 'accountGeneration',
            'accountSourceVersion', 'issuanceFence', 'issuanceFenceSourceVersion',
            'authorityTuple', 'authoritySourceVersions', 'issuerProjectionGeneration',
            'accountProjectionGeneration', 'issuerProjectionSha256', 'accountProjectionSha256'])
        OR signer_key_count <> 7
        OR NOT (signer ?& ARRAY[
            'promotionStatus', 'promotionOperationId', 'generationOperationId',
            'targetKid', 'targetGeneration', 'correspondenceSha256', 'correspondence'])
        OR envelope_key_count <> 10
        OR NOT (envelope ?& ARRAY[
            'operationId', 'requestId', 'tokenHash', 'kid', 'signerGeneration',
            'authorityEvidenceBundleSha256', 'issuanceFence',
            'responseRecoveryExpiryEpochMillis', 'envelopeSha256', 'envelopeBytesLength'])
        OR proof->>'tokenSha256' IS DISTINCT FROM NEW.token_hash
        OR proof->>'registryRecordSha256' IS DISTINCT FROM registry->>'canonicalRecordSha256'
        OR identity_proof->>'operationId' IS DISTINCT FROM NEW.operation_id::TEXT
        OR identity_proof->>'requestId' IS DISTINCT FROM NEW.request_id::TEXT
        OR identity_proof->>'accountId' IS DISTINCT FROM NEW.account_uuid::TEXT
        OR identity_proof->>'requestDigest' IS DISTINCT FROM NEW.request_digest
        OR identity_proof->>'callerWorkload' IS DISTINCT FROM NEW.caller_workload
        OR identity_proof->>'callerContextId' IS DISTINCT FROM NEW.caller_context_id::TEXT
        OR identity_proof->>'tokenJti' IS DISTINCT FROM NEW.token_jti::TEXT
        OR identity_proof->>'tokenGeneration' IS DISTINCT FROM NEW.token_generation::TEXT
        OR identity_proof->>'issuedAtEpochSecond' IS DISTINCT FROM NEW.issued_at_epoch_second::TEXT
        OR identity_proof->>'notBeforeEpochSecond' IS DISTINCT FROM NEW.not_before_epoch_second::TEXT
        OR identity_proof->>'expiresAtEpochSecond' IS DISTINCT FROM NEW.expires_at_epoch_second::TEXT
        OR identity_proof->>'tokenSha256' IS DISTINCT FROM NEW.token_hash
        OR registry->>'state' IS DISTINCT FROM 'pending'
        OR registry->>'registryVersion' IS DISTINCT FROM '1'
        OR registry->>'tokenKey' IS DISTINCT FROM ('session:auth:token:' || NEW.token_hash)
        OR registry->>'tokenHash' IS DISTINCT FROM NEW.token_hash
        OR registry->>'kid' IS DISTINCT FROM NEW.signer_kid
        OR registry->>'signerGeneration' IS DISTINCT FROM NEW.signer_generation
        OR registry->>'operationId' IS DISTINCT FROM NEW.operation_id::TEXT
        OR registry->>'requestId' IS DISTINCT FROM NEW.request_id::TEXT
        OR registry->>'requestDigest' IS DISTINCT FROM NEW.request_digest
        OR registry->>'aclIdentity' IS DISTINCT FROM 'account_coord_app'
        OR registry->>'absoluteExpiryMillis' IS NULL
        OR registry->>'absoluteExpiryMillis' !~ '^[1-9][0-9]{0,18}$'
        OR registry->>'localAofCount' IS NULL
        OR registry->>'localAofCount' !~ '^[1-9][0-9]{0,3}$'
        OR registry->>'replicaAofCount' IS NULL
        OR registry->>'replicaAofCount' !~ '^[1-9][0-9]{0,3}$'
        OR registry->>'outcome' IS NULL
        OR registry->>'outcome' NOT IN ('CREATED', 'EXACT_RETRY')
        OR registry->>'canonicalRecordSha256' IS NULL
        OR registry->>'canonicalRecordSha256' !~ '^[0-9a-f]{64}$'
        OR registry->>'evidenceBundleSha256' IS DISTINCT FROM bundle->>'canonicalSha256'
        OR bundle->>'canonicalSha256' IS NULL
        OR bundle->>'canonicalSha256' !~ '^[0-9a-f]{64}$'
        OR authority->>'issuerGeneration' IS DISTINCT FROM NEW.authority_issuer_generation::TEXT
        OR authority->>'issuerSourceVersion' IS DISTINCT FROM NEW.authority_issuer_source_version::TEXT
        OR authority->>'accountGeneration' IS DISTINCT FROM NEW.authority_account_generation::TEXT
        OR authority->>'accountSourceVersion' IS DISTINCT FROM NEW.authority_account_source_version::TEXT
        OR authority->>'issuanceFence' IS DISTINCT FROM NEW.issuance_fence::TEXT
        OR authority->>'issuanceFenceSourceVersion' IS DISTINCT FROM NEW.issuance_fence_source_version::TEXT
        OR authority->'authorityTuple' IS DISTINCT FROM
            convert_from(NEW.authority_tuple_canonical_bytes, 'UTF8')::jsonb
        OR authority->'authoritySourceVersions' IS DISTINCT FROM
            convert_from(NEW.authority_source_versions_canonical_bytes, 'UTF8')::jsonb
        OR authority->>'issuerProjectionGeneration' IS DISTINCT FROM NEW.authority_issuer_generation::TEXT
        OR authority->>'accountProjectionGeneration' IS DISTINCT FROM NEW.authority_account_generation::TEXT
        OR authority->>'issuerProjectionSha256' IS NULL
        OR authority->>'issuerProjectionSha256' !~ '^[0-9a-f]{64}$'
        OR authority->>'accountProjectionSha256' IS NULL
        OR authority->>'accountProjectionSha256' !~ '^[0-9a-f]{64}$'
        OR signer->>'promotionStatus' IS DISTINCT FROM 'COMMITTED'
        OR signer->>'promotionOperationId' IS NULL
        OR signer->>'promotionOperationId' !~ '^[0-9a-f-]{36}$'
        OR signer->>'generationOperationId' IS NULL
        OR signer->>'generationOperationId' !~ '^[0-9a-f-]{36}$'
        OR signer->>'targetKid' IS DISTINCT FROM NEW.signer_kid
        OR signer->>'targetGeneration' IS DISTINCT FROM NEW.signer_generation
        OR signer->>'correspondenceSha256' IS NULL
        OR signer->>'correspondenceSha256' !~ '^[0-9a-f]{64}$'
        OR envelope->>'operationId' IS DISTINCT FROM NEW.operation_id::TEXT
        OR envelope->>'requestId' IS DISTINCT FROM NEW.request_id::TEXT
        OR envelope->>'tokenHash' IS DISTINCT FROM NEW.token_hash
        OR envelope->>'kid' IS DISTINCT FROM NEW.signer_kid
        OR envelope->>'signerGeneration' IS DISTINCT FROM NEW.signer_generation THEN
        RAISE EXCEPTION 'Account gameplay delegation commit proof does not match its immutable intent'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_commit_evidence_match';
    END IF;

    IF (registry->>'absoluteExpiryMillis')::BIGINT < NEW.expires_at_epoch_second * 1000
        OR (registry->>'absoluteExpiryMillis')::BIGINT > NEW.expires_at_epoch_second * 1000 + 300000 THEN
        RAISE EXCEPTION 'Account gameplay delegation Redis receipt expiry is outside the immutable token bound'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_commit_evidence_match';
    END IF;

    SELECT request_id, account_uuid, bundle_schema, bundle_version, source_version,
        issuance_fence, linearization, canonical_sha256, canonical_bundle_bytes
      INTO bundle_row
      FROM account_gameplay_delegation_auth_evidence_bundles
      WHERE operation_id = NEW.operation_id;
    IF bundle_row.request_id IS NULL
        OR bundle_row.request_id <> NEW.request_id
        OR bundle_row.account_uuid <> NEW.account_uuid
        OR bundle_row.bundle_schema <> 'account-auth-evidence-bundle/v1'
        OR bundle_row.bundle_version <> bundle->>'bundleVersion'
        OR bundle_row.source_version <> bundle->>'sourceVersion'
        OR bundle_row.linearization <> bundle->>'linearization'
        OR bundle_row.canonical_sha256 <> bundle->>'canonicalSha256'
        OR convert_from(bundle_row.canonical_bundle_bytes, 'UTF8')::jsonb->'authorityTuple' IS DISTINCT FROM
            convert_from(NEW.authority_tuple_canonical_bytes, 'UTF8')::jsonb
        OR convert_from(bundle_row.canonical_bundle_bytes, 'UTF8')::jsonb->'membershipVersion' IS DISTINCT FROM
            convert_from(NEW.membership_version_canonical_bytes, 'UTF8')::jsonb
        OR convert_from(bundle_row.canonical_bundle_bytes, 'UTF8')::jsonb->'authoritySourceVersions' IS DISTINCT FROM
            convert_from(NEW.authority_source_versions_canonical_bytes, 'UTF8')::jsonb THEN
        RAISE EXCEPTION 'Account gameplay delegation commit proof has no exact owner evidence bundle'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_commit_evidence_match';
    END IF;

    SELECT operation_id, request_id, token_hash, signer_kid, signer_generation,
        authority_evidence_bundle_sha256, issuance_fence, response_recovery_expiry_epoch_ms,
        envelope_sha256, octet_length(envelope_bytes) AS envelope_bytes_length
      INTO envelope_row
      FROM account_gameplay_delegation_response_envelopes
      WHERE operation_id = NEW.operation_id;
    IF envelope_row.operation_id IS NULL
        OR envelope_row.request_id <> NEW.request_id
        OR envelope_row.token_hash <> NEW.token_hash
        OR envelope_row.signer_kid <> NEW.signer_kid
        OR envelope_row.signer_generation <> NEW.signer_generation
        OR envelope_row.authority_evidence_bundle_sha256 <> bundle_row.canonical_sha256
        OR envelope_row.issuance_fence <> NEW.issuance_fence
        OR envelope_row.response_recovery_expiry_epoch_ms / 1000 <> NEW.expires_at_epoch_second
        OR envelope->>'authorityEvidenceBundleSha256' IS DISTINCT FROM envelope_row.authority_evidence_bundle_sha256
        OR envelope->>'issuanceFence' IS DISTINCT FROM envelope_row.issuance_fence::TEXT
        OR envelope->>'responseRecoveryExpiryEpochMillis' IS DISTINCT FROM envelope_row.response_recovery_expiry_epoch_ms::TEXT
        OR envelope->>'envelopeSha256' IS DISTINCT FROM envelope_row.envelope_sha256
        OR envelope->>'envelopeBytesLength' IS DISTINCT FROM envelope_row.envelope_bytes_length::TEXT THEN
        RAISE EXCEPTION 'Account gameplay delegation commit proof has no exact sealed owner envelope'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_commit_evidence_match';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER account_gameplay_delegation_operation_guard
    ON account_gameplay_delegation_issuance_operations;
CREATE TRIGGER account_gameplay_delegation_operation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON account_gameplay_delegation_issuance_operations
    FOR EACH ROW EXECUTE FUNCTION account_gameplay_delegation_operation_guard();
-- [jooq ignore stop]
