-- Canonical World activation is one immutable owner operation. A row can become ACTIVE only
-- through the exact PREPARING epoch/row-version CAS retained here.
CREATE TABLE world_canonical_instance_activation_operation (
    activation_request_id UUID PRIMARY KEY,
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    request_bytes BYTEA NOT NULL CHECK (octet_length(request_bytes) > 0),
    preparing_evidence_bytes BYTEA NOT NULL CHECK (octet_length(preparing_evidence_bytes) > 0),
    canonical_game_instance_id UUID NOT NULL,
    world_instance_id BIGINT NOT NULL,
    expected_lifecycle_epoch BIGINT NOT NULL CHECK (expected_lifecycle_epoch > 0),
    expected_row_version BIGINT NOT NULL CHECK (expected_row_version >= 0),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('COMMITTED', 'ABORTED')),
    terminal_code VARCHAR(64),
    result_lifecycle_epoch BIGINT NOT NULL CHECK (result_lifecycle_epoch > 0),
    result_row_version BIGINT NOT NULL CHECK (result_row_version >= 0),
    result_bytes BYTEA NOT NULL CHECK (octet_length(result_bytes) > 0),
    result_digest VARCHAR(71) NOT NULL CHECK (result_digest ~ '^sha256:[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_world_activation_association
        FOREIGN KEY (canonical_game_instance_id)
        REFERENCES world_canonical_instance_association(canonical_game_instance_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_world_activation_outcome_shape CHECK (
        (outcome = 'COMMITTED' AND terminal_code IS NULL
            AND result_lifecycle_epoch = expected_lifecycle_epoch + 1
            AND result_row_version = expected_row_version + 1)
        OR (outcome = 'ABORTED' AND terminal_code IS NOT NULL
            AND length(btrim(terminal_code)) BETWEEN 1 AND 64)
    ),
    CONSTRAINT ck_world_activation_request_id_non_nil
        CHECK (activation_request_id <> '00000000-0000-0000-0000-000000000000'::UUID)
);
CREATE UNIQUE INDEX uq_world_activation_one_commit_per_instance
    ON world_canonical_instance_activation_operation(canonical_game_instance_id)
    WHERE outcome = 'COMMITTED';
CREATE INDEX idx_world_activation_world_row
    ON world_canonical_instance_activation_operation(world_instance_id, activation_request_id);

-- [jooq ignore start]
REVOKE ALL ON world_canonical_instance_activation_operation FROM PUBLIC;

CREATE FUNCTION world_validate_canonical_activation_operation()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    association_row "${serviceSchema}".world_canonical_instance_association%ROWTYPE;
    instance "${serviceSchema}".world_instance%ROWTYPE;
    preparing JSONB;
    normalized_request JSONB;
    result JSONB;
    lifecycle JSONB;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Canonical World activation operation evidence is immutable'
            USING ERRCODE = '55000';
    END IF;
    preparing := convert_from(NEW.preparing_evidence_bytes, 'UTF8')::JSONB;
    normalized_request := convert_from(NEW.request_bytes, 'UTF8')::JSONB;
    result := convert_from(NEW.result_bytes, 'UTF8')::JSONB;
    IF jsonb_typeof(preparing) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(preparing)) <> 11
        OR (SELECT count(*) FROM jsonb_object_keys(preparing->'request')) <> 14
        OR preparing->>'schema' IS DISTINCT FROM 'world-canonical-instance-lifecycle-evidence/v1'
        OR preparing->>'lifecycleStatus' IS DISTINCT FROM 'PREPARING'
        OR (preparing->'request'->>'canonicalGameInstanceId') IS DISTINCT FROM NEW.canonical_game_instance_id::TEXT
        OR (preparing->>'lifecycleEpoch') IS DISTINCT FROM NEW.expected_lifecycle_epoch::TEXT
        OR (preparing->>'rowVersion') IS DISTINCT FROM NEW.expected_row_version::TEXT
        OR jsonb_typeof(normalized_request) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(normalized_request)) <> 3
        OR (SELECT count(*) FROM jsonb_object_keys(normalized_request->'preparingEvidence')) <> 11
        OR (SELECT count(*) FROM jsonb_object_keys(normalized_request->'preparingEvidence'->'request')) <> 13
        OR normalized_request->>'schema' IS DISTINCT FROM 'world-canonical-instance-activation-request/v1'
        OR normalized_request->>'activationRequestId' IS DISTINCT FROM NEW.activation_request_id::TEXT
        OR NEW.request_digest IS DISTINCT FROM
            ('sha256:' || encode(sha256(NEW.request_bytes), 'hex'))
        OR NEW.result_digest IS DISTINCT FROM
            ('sha256:' || encode(sha256(NEW.result_bytes), 'hex'))
        OR normalized_request->'preparingEvidence' IS DISTINCT FROM
            (preparing - 'request' || jsonb_build_object(
                'request', (preparing->'request') - 'readRequestId')) THEN
        RAISE EXCEPTION 'Canonical activation request differs from its exact retained PREPARING evidence'
            USING ERRCODE = '23514';
    END IF;
    IF jsonb_typeof(result) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(result)) <> 8
        OR result->>'schema' IS DISTINCT FROM 'world-canonical-instance-activation-result/v1'
        OR result->>'activationRequestId' IS DISTINCT FROM NEW.activation_request_id::TEXT
        OR result->>'requestDigest' IS DISTINCT FROM NEW.request_digest
        OR result->>'outcome' IS DISTINCT FROM NEW.outcome
        OR result->>'terminalCode' IS DISTINCT FROM NEW.terminal_code
        OR (NEW.terminal_code IS NULL AND jsonb_typeof(result->'terminalCode') IS DISTINCT FROM 'null')
        OR (NEW.terminal_code IS NOT NULL AND jsonb_typeof(result->'terminalCode') IS DISTINCT FROM 'string')
        OR decode(result->>'requestBytesBase64', 'base64') IS DISTINCT FROM NEW.request_bytes
        OR decode(result->>'preparingEvidenceBytesBase64', 'base64') IS DISTINCT FROM NEW.preparing_evidence_bytes THEN
        RAISE EXCEPTION 'Canonical activation result differs from its immutable request outcome'
            USING ERRCODE = '23514';
    END IF;
    lifecycle := convert_from(decode(result->>'lifecycleEvidenceBytesBase64', 'base64'), 'UTF8')::JSONB;
    IF jsonb_typeof(lifecycle) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(lifecycle)) <> 11
        OR (SELECT count(*) FROM jsonb_object_keys(lifecycle->'request')) <> 14
        OR lifecycle->>'schema' IS DISTINCT FROM 'world-canonical-instance-lifecycle-evidence/v1'
        OR lifecycle->'request' IS DISTINCT FROM preparing->'request'
        OR lifecycle->>'lifecycleEpoch' IS DISTINCT FROM NEW.result_lifecycle_epoch::TEXT
        OR lifecycle->>'rowVersion' IS DISTINCT FROM NEW.result_row_version::TEXT
        OR (lifecycle - 'lifecycleStatus' - 'lifecycleEpoch' - 'rowVersion')
            IS DISTINCT FROM
            (preparing - 'lifecycleStatus' - 'lifecycleEpoch' - 'rowVersion') THEN
        RAISE EXCEPTION 'Canonical activation result does not retain its exact owner lifecycle snapshot'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO association_row FROM "${serviceSchema}".world_canonical_instance_association
        WHERE canonical_game_instance_id = NEW.canonical_game_instance_id;
    IF NOT FOUND OR association_row.world_instance_id IS DISTINCT FROM NEW.world_instance_id
        OR lifecycle->'request'->>'canonicalGameInstanceId' IS DISTINCT FROM association_row.canonical_game_instance_id::TEXT THEN
        RAISE EXCEPTION 'Canonical activation operation does not resolve through its exact World association'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO instance FROM "${serviceSchema}".world_instance WHERE id=association_row.world_instance_id FOR UPDATE;
    IF NOT FOUND OR instance.canonical_game_instance_id IS DISTINCT FROM NEW.canonical_game_instance_id THEN
        RAISE EXCEPTION 'Canonical activation operation lost its associated World row'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM "${serviceSchema}".world_canonical_instance_association a
        JOIN "${serviceSchema}".world_instance w
            ON w.id=a.world_instance_id
        JOIN "${serviceSchema}".world_complete_launch_binding b
            ON b.binding_operation_id=a.canonical_launch_binding_operation_id
        JOIN "${serviceSchema}".world_authored_source_intake s
            ON s.operation_id=a.intake_operation_id
        JOIN "${serviceSchema}".world_authored_version_identity v
            ON v.operation_id=a.version_identity_operation_id
        JOIN "${serviceSchema}".world_canonical_instance_preparation p
            ON p.canonical_game_instance_id=a.canonical_game_instance_id
        JOIN "${serviceSchema}".world_canonical_preparation_start_location start_row
            ON start_row.canonical_game_instance_id=a.canonical_game_instance_id
        JOIN "${serviceSchema}".world_canonical_instance_topology_identity m
            ON m.world_instance_id=a.world_instance_id
            AND m.canonical_game_instance_id=a.canonical_game_instance_id
            AND m.family='ROOM' AND m.template_id=start_row.room_template_id
        JOIN "${serviceSchema}".room_instance r
            ON r.id=m.runtime_row_id AND r.room_instance_row_id=start_row.runtime_room_instance_id
        JOIN "${serviceSchema}".region_instance runtime_region
            ON runtime_region.id=r.region_instance_id AND runtime_region.world_instance_id=a.world_instance_id
        WHERE a.canonical_game_instance_id=NEW.canonical_game_instance_id
            AND a.world_instance_id=NEW.world_instance_id
            AND a.schema_version=1
            AND a.playable_state_scope='SHARED'
            AND a.public_production IS TRUE
            AND a.playtest_lifecycle_id IS NULL
            AND a.playtest_state_generation IS NULL
            AND w.canonical_game_instance_id=a.canonical_game_instance_id
            AND w.canonical_target_namespace=a.canonical_target_namespace
            AND w.canonical_tenant_id=a.canonical_tenant_id
            AND w.canonical_world_slug=a.canonical_world_slug
            AND w.playable_state_namespace_id=a.playable_state_namespace_id
            AND w.playable_state_scope=a.playable_state_scope
            AND w.public_production=a.public_production
            AND w.control_plane_request_id=a.control_plane_request_id
            AND w.canonical_launch_binding_operation_id=a.canonical_launch_binding_operation_id
            AND w.tenant_id=a.local_tenant_key
            AND w.game_instance_id=a.private_game_instance_key
            AND b.target_namespace=a.canonical_target_namespace
            AND b.canonical_tenant_id=a.canonical_tenant_id
            AND b.canonical_version_id=a.canonical_version_id
            AND b.world_slug=a.canonical_world_slug
            AND b.control_plane_request_id=a.control_plane_request_id
            AND b.intake_operation_id=a.intake_operation_id
            AND b.intake_request_id=a.intake_request_id
            AND b.local_tenant_key=a.local_tenant_key
            AND b.source_operation_id=a.source_operation_id
            AND b.source_evidence_digest=a.source_evidence_digest
            AND b.intake_receipt_digest=a.intake_receipt_digest
            AND b.descriptor_request_digest=a.descriptor_request_digest
            AND b.descriptor_result_digest=a.descriptor_result_digest
            AND b.release_attestation_digest=a.release_attestation_digest
            AND b.descriptor_json::JSONB->>'launchDescriptorId'=a.launch_descriptor_id
            AND b.descriptor_json::JSONB->>'versionId'=v.game_design_version_id::TEXT
            AND b.descriptor_json::JSONB->>'gameTemplateId'=a.game_template_id::TEXT
            AND b.descriptor_json::JSONB->>'targetNamespace'=a.canonical_target_namespace
            AND b.descriptor_json::JSONB->>'canonicalTenantId'=a.canonical_tenant_id::TEXT
            AND b.descriptor_json::JSONB->>'worldSlug'=a.canonical_world_slug
            AND b.descriptor_json::JSONB->>'controlPlaneRequestId'=a.control_plane_request_id
            AND b.descriptor_json::JSONB->>'authoredWorldSourceOperationId'=a.source_operation_id::TEXT
            AND b.descriptor_json::JSONB->>'authoredWorldSourceEvidenceDigest'=a.source_evidence_digest
            AND b.descriptor_json::JSONB->>'requestDigest'=a.descriptor_request_digest
            AND b.descriptor_json::JSONB->>'resultDigest'=a.descriptor_result_digest
            AND (CASE WHEN b.descriptor_json::JSONB->>'scriptPatchVersionPresent'='true'
                THEN b.descriptor_json::JSONB->>'scriptPatchVersion' ELSE NULL END)
                IS NOT DISTINCT FROM a.script_patch_version
            AND b.descriptor_json::JSONB->>'runtimeFlagsJson' IS NOT DISTINCT FROM a.runtime_flags_json
            AND b.descriptor_json::JSONB->>'generationConfigRevision'=a.generation_config_revision
            AND b.descriptor_json::JSONB->>'versionStateEpoch'=a.version_state_epoch::TEXT
            AND b.descriptor_json::JSONB->>'releaseBundleId'=a.release_bundle_id::TEXT
            AND b.descriptor_json::JSONB->>'publishedReleaseBundleRef'=a.published_release_bundle_ref
            AND (CASE WHEN b.descriptor_json::JSONB->>'remapSetIdPresent'='true'
                THEN b.descriptor_json::JSONB->>'remapSetId' ELSE NULL END)
                IS NOT DISTINCT FROM a.remap_set_id
            AND b.release_attestation_json::JSONB->>'targetNamespace'=a.canonical_target_namespace
            AND b.release_attestation_json::JSONB->>'descriptorResultDigest'=a.descriptor_result_digest
            AND b.release_attestation_json::JSONB->>'canonicalTenantId'=a.canonical_tenant_id::TEXT
            AND b.release_attestation_json::JSONB->>'canonicalVersionId'=a.canonical_version_id::TEXT
            AND b.release_attestation_json::JSONB->>'worldSlug'=a.canonical_world_slug
            AND b.release_attestation_json::JSONB->>'authoredWorldSourceOperationId'=a.source_operation_id::TEXT
            AND b.release_attestation_json::JSONB->>'authoredWorldSourceEvidenceDigest'=a.source_evidence_digest
            AND b.release_attestation_json::JSONB->>'launchDescriptorId'=a.launch_descriptor_id
            AND b.release_attestation_json::JSONB->>'publishedReleaseBundleRef'=a.published_release_bundle_ref
            AND b.release_attestation_json::JSONB->>'versionStateEpoch'=a.version_state_epoch::TEXT
            AND b.release_attestation_json::JSONB->>'generationConfigRevision'=a.generation_config_revision
            AND b.release_attestation_json::JSONB->>'evidenceDigest'=a.release_attestation_digest
            AND s.target_namespace=a.canonical_target_namespace
            AND s.canonical_tenant_id=a.canonical_tenant_id
            AND s.world_slug=a.canonical_world_slug
            AND s.local_tenant_key=a.local_tenant_key
            AND s.intake_request_id=a.intake_request_id
            AND s.source_operation_id=a.source_operation_id
            AND s.source_evidence_digest=a.source_evidence_digest
            AND s.request_digest=a.intake_request_digest
            AND s.receipt_digest=a.intake_receipt_digest
            AND v.target_namespace=a.canonical_target_namespace
            AND v.canonical_tenant_id=a.canonical_tenant_id
            AND v.world_slug=a.canonical_world_slug
            AND v.canonical_version_id=a.canonical_version_id
            AND v.local_tenant_key=a.local_tenant_key
            AND v.local_version_key=a.local_version_key
            AND v.intake_operation_id=a.intake_operation_id
            AND v.intake_request_id=a.intake_request_id
            AND v.intake_request_digest=a.intake_request_digest
            AND v.source_operation_id=a.source_operation_id
            AND v.source_evidence_digest=a.source_evidence_digest
            AND v.intake_receipt_digest=a.intake_receipt_digest
            AND v.game_design_version_id::TEXT=b.descriptor_json::JSONB->>'versionId'
            AND v.version_state_epoch=a.version_state_epoch
            AND p.target_namespace=a.canonical_target_namespace
            AND p.canonical_tenant_id=a.canonical_tenant_id
            AND p.world_slug=a.canonical_world_slug
            AND p.control_plane_request_id=a.control_plane_request_id
            AND p.canonical_version_id=a.canonical_version_id
            AND p.version_identity_operation_id=a.version_identity_operation_id
            AND p.world_instance_id=a.world_instance_id
            AND p.private_game_instance_key=a.private_game_instance_key
            AND p.capture_id::TEXT=lifecycle->>'captureId'
            AND p.graph_sha256=lifecycle->>'graphSha256'
            AND p.graph_sha256=encode(sha256(p.graph_bytes),'hex')
            AND p.input_digest=lifecycle->>'preparationInputDigest'
            AND p.input_digest=('sha256:' || encode(sha256(convert_to(p.input_json,'UTF8')),'hex'))
            AND p.input_json::JSONB->>'schemaVersion'='2'
            AND p.input_json::JSONB->'identity'->>'canonicalGameInstanceId'=a.canonical_game_instance_id::TEXT
            AND p.input_json::JSONB->'identity'->>'targetNamespace'=a.canonical_target_namespace
            AND p.input_json::JSONB->'identity'->>'canonicalTenantId'=a.canonical_tenant_id::TEXT
            AND p.input_json::JSONB->'identity'->>'worldSlug'=a.canonical_world_slug
            AND p.input_json::JSONB->'identity'->>'playableStateNamespaceId'=a.playable_state_namespace_id::TEXT
            AND p.input_json::JSONB->'identity'->>'playableStateScope'=a.playable_state_scope
            AND p.input_json::JSONB->'identity'->'publicProduction'=to_jsonb(a.public_production)
            AND p.input_json::JSONB->'identity'->>'controlPlaneRequestId'=a.control_plane_request_id
            AND p.input_json::JSONB->'launchBinding'->>'operationId'=a.canonical_launch_binding_operation_id::TEXT
            AND p.input_json::JSONB->'launchBinding'->>'targetNamespace'=b.target_namespace
            AND p.input_json::JSONB->'launchBinding'->>'canonicalTenantId'=b.canonical_tenant_id::TEXT
            AND p.input_json::JSONB->'launchBinding'->>'worldSlug'=b.world_slug
            AND p.input_json::JSONB->'launchBinding'->>'controlPlaneRequestId'=b.control_plane_request_id
            AND p.input_json::JSONB->'launchBinding'->>'canonicalVersionId'=b.canonical_version_id::TEXT
            AND p.input_json::JSONB->'launchBinding'->>'descriptorRequestDigest'=b.descriptor_request_digest
            AND p.input_json::JSONB->'launchBinding'->>'descriptorResultDigest'=b.descriptor_result_digest
            AND p.input_json::JSONB->'launchBinding'->>'releaseAttestationDigest'=b.release_attestation_digest
            AND p.input_json::JSONB->'launchBinding'->>'localTenantKey'=b.local_tenant_key::TEXT
            AND p.input_json::JSONB->'launchBinding'->>'intakeOperationId'=b.intake_operation_id::TEXT
            AND p.input_json::JSONB->'launchBinding'->>'intakeRequestId'=b.intake_request_id::TEXT
            AND p.input_json::JSONB->'launchBinding'->>'sourceOperationId'=b.source_operation_id::TEXT
            AND p.input_json::JSONB->'launchBinding'->>'sourceEvidenceDigest'=b.source_evidence_digest
            AND p.input_json::JSONB->'launchBinding'->>'intakeRequestDigest'=s.request_digest
            AND p.input_json::JSONB->'launchBinding'->>'intakeReceiptDigest'=s.receipt_digest
            AND p.input_json::JSONB->'versionIdentity'->>'operationId'=v.operation_id::TEXT
            AND p.input_json::JSONB->'versionIdentity'->>'canonicalVersionId'=v.canonical_version_id::TEXT
            AND p.input_json::JSONB->'versionIdentity'->>'localVersionKey'=v.local_version_key::TEXT
            AND p.input_json::JSONB->'versionIdentity'->>'gameDesignVersionId'=v.game_design_version_id::TEXT
            AND p.input_json::JSONB->'versionIdentity'->>'versionState'=v.version_state_state
            AND p.input_json::JSONB->'versionIdentity'->>'versionStateEpoch'=v.version_state_epoch::TEXT
            AND p.input_json::JSONB->'versionIdentity'->>'evidenceDigest'=v.version_state_evidence_digest
            AND p.input_json::JSONB->'sourceIntake'->>'schemaVersion'=s.schema_version::TEXT
            AND p.input_json::JSONB->'sourceIntake'->>'targetNamespace'=s.target_namespace
            AND p.input_json::JSONB->'sourceIntake'->>'intakeRequestId'=s.intake_request_id::TEXT
            AND p.input_json::JSONB->'sourceIntake'->>'operationId'=s.operation_id::TEXT
            AND p.input_json::JSONB->'sourceIntake'->>'canonicalTenantId'=s.canonical_tenant_id::TEXT
            AND p.input_json::JSONB->'sourceIntake'->>'worldSlug'=s.world_slug
            AND p.input_json::JSONB->'sourceIntake'->>'sourceOperationId'=s.source_operation_id::TEXT
            AND p.input_json::JSONB->'sourceIntake'->>'sourceEvidenceDigest'=s.source_evidence_digest
            AND p.input_json::JSONB->'sourceIntake'->>'requestDigest'=s.request_digest
            AND p.input_json::JSONB->'sourceIntake'->>'receiptDigest'=s.receipt_digest
            AND p.input_json::JSONB->'sourceIntake'->>'localTenantKey'=s.local_tenant_key::TEXT
            AND p.input_json::JSONB->'sourceIntake'->'sourceEvidence'->>'schemaVersion'=s.source_schema_version::TEXT
            AND p.input_json::JSONB->'sourceIntake'->'sourceEvidence'->>'registrationRequestId'=s.source_registration_request_id::TEXT
            AND p.input_json::JSONB->'sourceIntake'->'sourceEvidence'->>'sourceOperationId'=s.source_operation_id::TEXT
            AND p.input_json::JSONB->'sourceIntake'->'sourceEvidence'->>'requestDigest'=s.source_request_digest
            AND p.input_json::JSONB->'sourceIntake'->'sourceEvidence'->>'canonicalTenantId'=s.canonical_tenant_id::TEXT
            AND p.input_json::JSONB->'sourceIntake'->'sourceEvidence'->>'tenantSlug'=s.tenant_slug
            AND p.input_json::JSONB->'sourceIntake'->'sourceEvidence'->>'worldSlug'=s.world_slug
            AND p.input_json::JSONB->'sourceIntake'->'sourceEvidence'->>'worldDisplayName'=s.world_display_name
            AND p.input_json::JSONB->'sourceIntake'->'sourceEvidence'->>'sourceGameRowId'=s.source_game_row_id::TEXT
            AND p.input_json::JSONB->'sourceIntake'->'sourceEvidence'->>'sourceGameTenantKey'=s.source_game_tenant_key
            AND p.input_json::JSONB->'sourceIntake'->'sourceEvidence'->>'provenanceKind'=s.source_provenance_kind
            AND p.input_json::JSONB->'sourceIntake'->'sourceEvidence'->>'evidenceDigest'=s.source_evidence_digest
            AND p.input_json::JSONB->'topology'->>'captureId'=p.capture_id::TEXT
            AND p.input_json::JSONB->'topology'->>'targetNamespace'=a.canonical_target_namespace
            AND p.input_json::JSONB->'topology'->>'canonicalTenantId'=a.canonical_tenant_id::TEXT
            AND p.input_json::JSONB->'topology'->>'canonicalVersionId'=a.canonical_version_id::TEXT
            AND p.input_json::JSONB->'topology'->>'versionIdentityOperationId'=a.version_identity_operation_id::TEXT
            AND lifecycle->'request'->>'schemaVersion'='1'
            AND lifecycle->'request'->>'targetNamespace'=a.canonical_target_namespace
            AND lifecycle->'request'->>'canonicalTenantId'=a.canonical_tenant_id::TEXT
            AND lifecycle->'request'->>'worldSlug'=a.canonical_world_slug
            AND lifecycle->'request'->>'canonicalGameInstanceId'=a.canonical_game_instance_id::TEXT
            AND lifecycle->'request'->>'playableStateNamespaceId'=a.playable_state_namespace_id::TEXT
            AND lifecycle->'request'->>'playableStateScope'=a.playable_state_scope
            AND lifecycle->'request'->'publicProduction'=to_jsonb(a.public_production)
            AND lifecycle->'request'->>'controlPlaneRequestId'=a.control_plane_request_id
            AND lifecycle->'request'->>'canonicalVersionId'=a.canonical_version_id::TEXT
            AND lifecycle->'request'->>'expectedDescriptorRequestDigest'=a.descriptor_request_digest
            AND lifecycle->'request'->>'expectedDescriptorResultDigest'=a.descriptor_result_digest
            AND lifecycle->'request'->>'expectedReleaseAttestationDigest'=a.release_attestation_digest
            AND decode(preparing->>'launchBindingBytesBase64','base64') IS NOT NULL
            AND convert_from(decode(preparing->>'launchBindingBytesBase64','base64'),'UTF8')::JSONB
                IS NOT DISTINCT FROM jsonb_build_object(
                    'descriptor', b.descriptor_json::JSONB,
                    'releaseAttestation', b.release_attestation_json::JSONB)
            AND start_row.world_instance_id=a.world_instance_id
            AND start_row.canonical_tenant_id=a.canonical_tenant_id
            AND start_row.canonical_version_id=a.canonical_version_id
            AND lifecycle->'startLocation'->>'tenantId'=start_row.canonical_tenant_id::TEXT
            AND lifecycle->'startLocation'->>'versionId'=start_row.canonical_version_id::TEXT
            AND lifecycle->'startLocation'->>'roomTemplateId'=start_row.room_template_id::TEXT
            AND lifecycle->>'runtimeRoomInstanceId'=start_row.runtime_room_instance_id::TEXT
            AND lifecycle->>'captureId'=p.capture_id::TEXT
            AND lifecycle->>'graphSha256'=p.graph_sha256
            AND lifecycle->>'preparationInputDigest'=p.input_digest
            AND start_row.graph_digest=('sha256:' || p.graph_sha256)
            AND start_row.evidence_bytes=decode(p.input_json::JSONB->>'worldStartLocationEvidenceBase64','base64')
            AND m.runtime_room_instance_id=start_row.runtime_room_instance_id
            AND m.runtime_row_id=r.id
            AND r.template_room_id=m.template_private_row_key
            AND r.tenant_id=a.local_tenant_key
            AND r.game_instance_id=a.private_game_instance_key
            AND runtime_region.tenant_id=a.local_tenant_key
            AND runtime_region.game_instance_id=a.private_game_instance_key
    ) THEN
        RAISE EXCEPTION 'Canonical activation proof differs from immutable World association, launch, preparation, or ROOM bindings'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.outcome = 'COMMITTED' THEN
        IF instance.status IS DISTINCT FROM 'PREPARING'
            OR instance.lifecycle_epoch IS DISTINCT FROM NEW.expected_lifecycle_epoch
            OR instance.row_version IS DISTINCT FROM NEW.expected_row_version
            OR lifecycle->>'lifecycleStatus' IS DISTINCT FROM 'ACTIVE' THEN
            RAISE EXCEPTION 'Committed canonical activation lacks the exact current PREPARING CAS'
                USING ERRCODE = '23514';
        END IF;
    ELSIF lifecycle->>'lifecycleStatus' IS DISTINCT FROM instance.status
        OR lifecycle->>'lifecycleEpoch' IS DISTINCT FROM instance.lifecycle_epoch::TEXT
        OR lifecycle->>'rowVersion' IS DISTINCT FROM instance.row_version::TEXT THEN
        RAISE EXCEPTION 'Aborted canonical activation must retain the actual current lifecycle state'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION world_validate_canonical_activation_operation() FROM PUBLIC;
CREATE TRIGGER trg_world_canonical_activation_operation
    BEFORE INSERT OR UPDATE OR DELETE ON world_canonical_instance_activation_operation
    FOR EACH ROW EXECUTE FUNCTION world_validate_canonical_activation_operation();

CREATE FUNCTION world_reject_canonical_activation_truncate()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    RAISE EXCEPTION 'Canonical World activation operation evidence is immutable and retained'
        USING ERRCODE = '55000';
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION world_reject_canonical_activation_truncate() FROM PUBLIC;
CREATE TRIGGER trg_world_canonical_activation_operation_no_truncate
    BEFORE TRUNCATE ON world_canonical_instance_activation_operation
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_canonical_activation_truncate();

CREATE FUNCTION world_require_committed_activation_at_commit()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    instance "${serviceSchema}".world_instance%ROWTYPE;
    result JSONB;
BEGIN
    IF NEW.outcome <> 'COMMITTED' THEN RETURN NULL; END IF;
    SELECT * INTO instance FROM "${serviceSchema}".world_instance
        WHERE id=NEW.world_instance_id AND canonical_game_instance_id=NEW.canonical_game_instance_id;
    result := convert_from(NEW.result_bytes, 'UTF8')::JSONB;
    IF NOT FOUND
        OR instance.status IS DISTINCT FROM 'ACTIVE'
        OR instance.lifecycle_epoch IS DISTINCT FROM NEW.result_lifecycle_epoch
        OR instance.row_version IS DISTINCT FROM NEW.result_row_version
        OR result->>'outcome' IS DISTINCT FROM 'COMMITTED'
        OR result->>'requestDigest' IS DISTINCT FROM NEW.request_digest
        OR convert_from(decode(result->>'lifecycleEvidenceBytesBase64','base64'),'UTF8')::JSONB->>'lifecycleStatus'
            IS DISTINCT FROM 'ACTIVE' THEN
        RAISE EXCEPTION 'Committed canonical activation must include its exact ACTIVE lifecycle CAS before commit'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION world_require_committed_activation_at_commit() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER trg_world_canonical_activation_commit_cas
    AFTER INSERT ON world_canonical_instance_activation_operation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION world_require_committed_activation_at_commit();

CREATE FUNCTION world_guard_canonical_activation_transition()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    IF OLD.canonical_game_instance_id IS NOT NULL AND NEW.status = 'ACTIVE'
        AND (OLD.status IS DISTINCT FROM NEW.status
            OR OLD.lifecycle_epoch IS DISTINCT FROM NEW.lifecycle_epoch
            OR OLD.row_version IS DISTINCT FROM NEW.row_version) THEN
        IF OLD.status IS DISTINCT FROM 'PREPARING'
            OR NEW.lifecycle_epoch IS DISTINCT FROM OLD.lifecycle_epoch + 1
            OR NEW.row_version IS DISTINCT FROM OLD.row_version + 1
            OR NOT EXISTS (
                SELECT 1 FROM "${serviceSchema}".world_canonical_instance_activation_operation o
                WHERE o.canonical_game_instance_id = OLD.canonical_game_instance_id
                    AND o.world_instance_id = OLD.id
                    AND o.outcome = 'COMMITTED'
                    AND o.expected_lifecycle_epoch = OLD.lifecycle_epoch
                    AND o.expected_row_version = OLD.row_version
                    AND o.result_lifecycle_epoch = NEW.lifecycle_epoch
                    AND o.result_row_version = NEW.row_version
                    AND convert_from(o.result_bytes, 'UTF8')::JSONB->>'outcome' = 'COMMITTED') THEN
            RAISE EXCEPTION 'Canonical World PREPARING-to-ACTIVE transition requires its exact activation operation'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION world_guard_canonical_activation_transition() FROM PUBLIC;
CREATE TRIGGER trg_world_canonical_activation_transition
    BEFORE UPDATE ON world_instance
    FOR EACH ROW EXECUTE FUNCTION world_guard_canonical_activation_transition();
-- [jooq ignore stop]
