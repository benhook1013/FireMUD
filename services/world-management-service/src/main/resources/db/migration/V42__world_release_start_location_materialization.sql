-- Keep V1 materialization history inert and byte-for-byte retryable. V2 retains the authored
-- ROOM reference separately from its exact World-owned runtime mapping; neither activates it.
CREATE TABLE world_canonical_preparation_start_location (
    canonical_game_instance_id UUID PRIMARY KEY REFERENCES world_canonical_instance_preparation(canonical_game_instance_id) ON DELETE RESTRICT,
    world_instance_id BIGINT NOT NULL REFERENCES world_instance(id) ON DELETE RESTRICT,
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    room_template_id UUID NOT NULL,
    runtime_room_instance_id BIGINT NOT NULL CHECK (runtime_room_instance_id > 0),
    receipt_digest VARCHAR(71) NOT NULL CHECK (receipt_digest ~ '^sha256:[0-9a-f]{64}$'),
    graph_digest VARCHAR(71) NOT NULL CHECK (graph_digest ~ '^sha256:[0-9a-f]{64}$'),
    evidence_bytes BYTEA NOT NULL CHECK (octet_length(evidence_bytes) > 0),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- [jooq ignore start]
REVOKE ALL ON world_canonical_preparation_start_location FROM PUBLIC;

-- This independently checks immutable producer evidence before allocation and on every retry.
CREATE FUNCTION world_require_preparation_start_location(input JSONB, binding JSONB, capture UUID)
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    frozen "${serviceSchema}".world_canonical_frozen_topology%ROWTYPE;
    receipt "${serviceSchema}".world_draft_start_location_receipt%ROWTYPE;
    application "${serviceSchema}".world_draft_graph_application%ROWTYPE;
    graph "${serviceSchema}".world_topology_draft_commit%ROWTYPE;
    evidence JSONB;
    selected JSONB;
    freeze JSONB;
    retained JSONB;
    expected_tuples JSONB;
BEGIN
    IF input->>'schemaVersion' = '1' THEN
        IF binding->>'schemaVersion' IS DISTINCT FROM '1'
            OR binding ? 'worldStartLocationEvidence' OR input ? 'worldStartLocationEvidenceBase64' THEN
            RAISE EXCEPTION 'V1 preparation cannot acquire selector evidence' USING ERRCODE = '23514';
        END IF;
        RETURN;
    END IF;
    IF input->>'schemaVersion' IS DISTINCT FROM '2' OR binding->>'schemaVersion' IS DISTINCT FROM '2'
        OR input->>'worldStartLocationEvidenceBase64' IS NULL
        OR jsonb_typeof(binding->'worldStartLocationEvidence') IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'V2 preparation requires original complete World selector evidence' USING ERRCODE = '23514';
    END IF;
    evidence := convert_from(decode(input->>'worldStartLocationEvidenceBase64', 'base64'), 'UTF8')::JSONB;
    selected := evidence->'request';
    retained := binding->'worldStartLocationEvidence';
    SELECT * INTO STRICT frozen FROM "${serviceSchema}".world_canonical_frozen_topology WHERE capture_id=capture;
    freeze := frozen.freeze_request_json::JSONB;
    SELECT coalesce(jsonb_agg(value ORDER BY value->>'owner',value->>'aggregateType',value->>'aggregateId',
        value->>'scopeType',value->>'scopeId',value->>'expectedEpoch'), '[]'::JSONB)
        INTO expected_tuples FROM jsonb_array_elements(freeze->'suppliedOwnedAffectedTuples');
    IF evidence->>'schema' IS DISTINCT FROM 'world-published-start-location-evidence/v1'
        OR (SELECT count(*) FROM jsonb_object_keys(evidence)) <> 5
        OR selected IS DISTINCT FROM retained->'request'
        OR (SELECT count(*) FROM jsonb_object_keys(selected)) <> 13
        OR selected->>'targetNamespace' IS DISTINCT FROM freeze->>'targetNamespace'
        OR selected->>'canonicalTenantId' IS DISTINCT FROM freeze->>'canonicalTenantId'
        OR selected->>'canonicalVersionId' IS DISTINCT FROM freeze->>'canonicalVersionId'
        OR selected->>'intakeRequestId' IS DISTINCT FROM freeze->>'intakeRequestId'
        OR selected->>'publicationFence' IS DISTINCT FROM freeze->>'publicationFence'
        OR selected->>'publicationRequestId' IS DISTINCT FROM freeze->>'publicationRequestId'
        OR selected->>'requestDigest' IS DISTINCT FROM freeze->>'requestDigest'
        OR selected->>'versionStateEpoch' IS DISTINCT FROM freeze->>'versionStateEpoch'
        OR selected->>'publishWorkflowId' IS DISTINCT FROM freeze->>'publishWorkflowId'
        OR selected->>'appliedCommitId' IS DISTINCT FROM freeze->>'appliedCommitId'
        OR selected->>'contentDigest' IS DISTINCT FROM freeze->>'contentDigest'
        OR selected->>'digestSchemaVersion' IS DISTINCT FROM '3'
        OR selected->>'digestSchemaVersion' IS DISTINCT FROM freeze->>'digestSchemaVersion'
        OR selected->'worldAffectedTuples' IS DISTINCT FROM expected_tuples
        OR decode(evidence->>'selectorReceiptBytesBase64','base64') IS DISTINCT FROM decode(retained->>'selectorReceiptBytes','base64')
        OR decode(evidence->>'originalAccountBindingBytesBase64','base64') IS DISTINCT FROM decode(retained->>'originalAccountBindingBytes','base64')
        OR decode(evidence->>'appliedResultBytesBase64','base64') IS DISTINCT FROM decode(retained->>'appliedResultBytes','base64') THEN
        RAISE EXCEPTION 'Preparation selector differs from complete frozen request or retained release bytes' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT receipt FROM "${serviceSchema}".world_draft_start_location_receipt
        WHERE request_id=frozen.request_id AND commit_id=frozen.commit_id;
    SELECT * INTO STRICT application FROM "${serviceSchema}".world_draft_graph_application
        WHERE operation_id=receipt.operation_id AND request_id=receipt.request_id AND commit_id=receipt.commit_id
            AND authorization_fence_id=receipt.authorization_fence_id;
    SELECT * INTO STRICT graph FROM "${serviceSchema}".world_topology_draft_commit
        WHERE request_id=frozen.request_id AND commit_id=frozen.commit_id;
    IF receipt.target_namespace IS DISTINCT FROM selected->>'targetNamespace'
        OR receipt.canonical_tenant_id::TEXT IS DISTINCT FROM selected->>'canonicalTenantId'
        OR receipt.canonical_version_id::TEXT IS DISTINCT FROM selected->>'canonicalVersionId'
        OR receipt.binding_digest IS DISTINCT FROM graph.binding_digest
        OR receipt.graph_digest IS DISTINCT FROM ('sha256:' || frozen.graph_sha256)
        OR graph.graph_bytes IS DISTINCT FROM frozen.graph_bytes
        OR graph.binding_json IS DISTINCT FROM frozen.binding_json
        OR graph.owner_binding_json IS DISTINCT FROM frozen.owner_binding_json
        OR receipt.account_binding_bytes IS DISTINCT FROM application.account_binding_bytes
        OR receipt.account_binding_digest IS DISTINCT FROM application.account_binding_digest
        OR receipt.receipt_bytes IS DISTINCT FROM decode(evidence->>'selectorReceiptBytesBase64','base64')
        OR receipt.account_binding_bytes IS DISTINCT FROM decode(evidence->>'originalAccountBindingBytesBase64','base64')
        OR application.result_bytes IS DISTINCT FROM decode(evidence->>'appliedResultBytesBase64','base64')
        OR convert_from(application.result_bytes,'UTF8')::JSONB->>'schema' IS DISTINCT FROM 'world-draft-graph-applied/v2'
        OR convert_from(application.result_bytes,'UTF8')::JSONB->>'status' IS DISTINCT FROM 'APPLIED'
        OR NOT EXISTS (SELECT 1 FROM "${serviceSchema}".world_authored_topology_identity m
            JOIN "${serviceSchema}".room r ON r.id=m.private_row_key
            WHERE m.request_id=frozen.request_id AND m.commit_id=frozen.commit_id AND m.family='ROOM'
                AND m.template_id=receipt.room_template_id AND m.canonical_tenant_id=receipt.canonical_tenant_id
                AND m.canonical_version_id=receipt.canonical_version_id AND m.target_namespace=receipt.target_namespace
                AND r.tenant_id=graph.local_tenant_key AND r.version_id=graph.local_version_key
                AND m.tenant_id=r.tenant_id AND m.version_id=r.version_id) THEN
        RAISE EXCEPTION 'Preparation selector lacks exact original Account/APPLIED/graph/ROOM evidence' USING ERRCODE = '23514';
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION world_require_preparation_start_location(JSONB, JSONB, UUID) FROM PUBLIC;

-- Amend only the input-schema gate and insert additional guards. Preserve the complete existing
-- V35/V40 guard body, grants, ownership, materialization writes and V1 historical input bytes.
DO $migration$
DECLARE
    original TEXT;
    schema_anchor TEXT := $anchor$    IF p->>'schemaVersion' IS DISTINCT FROM '1' THEN$anchor$;
    join_anchor TEXT := $anchor$    IF identity_row.operation_id::TEXT IS DISTINCT FROM topology->>'versionIdentityOperationId'$anchor$;
    retry_anchor TEXT := $anchor$    IF FOUND THEN
        IF prior.input_json IS DISTINCT FROM p_input_json$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_prepare_canonical_instance(text,text)'::REGPROCEDURE) INTO STRICT original;
    IF (length(original)-length(replace(original,schema_anchor,'')))/length(schema_anchor) <> 1
        OR (length(original)-length(replace(original,join_anchor,'')))/length(join_anchor) <> 1
        OR (length(original)-length(replace(original,retry_anchor,'')))/length(retry_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one original preparation schema/join/retry guard';
    END IF;
    original := replace(original,schema_anchor,$replacement$    IF p->>'schemaVersion' IS NULL OR p->>'schemaVersion' NOT IN ('1','2') THEN$replacement$);
    original := replace(original,join_anchor,$replacement$    PERFORM "${serviceSchema}".world_require_preparation_start_location(p, binding_row.release_attestation_json::JSONB, capture_row.capture_id);

$replacement$ || join_anchor);
    original := replace(original,retry_anchor,$replacement$    IF FOUND THEN
        IF p->>'schemaVersion' = '2' AND NOT EXISTS (
            SELECT 1 FROM "${serviceSchema}".world_canonical_preparation_start_location s
            WHERE s.canonical_game_instance_id=prior.canonical_game_instance_id
                AND s.world_instance_id=prior.world_instance_id
                AND s.evidence_bytes=decode(p->>'worldStartLocationEvidenceBase64','base64')) THEN
            RAISE EXCEPTION 'V2 preparation retry lacks original selector readback' USING ERRCODE='23514';
        END IF;
        IF prior.input_json IS DISTINCT FROM p_input_json$replacement$);
    EXECUTE original;
END;
$migration$;

CREATE FUNCTION world_retain_preparation_start_location() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,"${serviceSchema}"
AS $$
DECLARE
    receipt "${serviceSchema}".world_draft_start_location_receipt%ROWTYPE;
    mapping "${serviceSchema}".world_canonical_instance_topology_identity%ROWTYPE;
    frozen "${serviceSchema}".world_canonical_frozen_topology%ROWTYPE;
BEGIN
    IF NEW.input_json::JSONB->>'schemaVersion' = '1' THEN RETURN NEW; END IF;
    SELECT * INTO STRICT frozen FROM "${serviceSchema}".world_canonical_frozen_topology WHERE capture_id=NEW.capture_id;
    SELECT * INTO STRICT receipt FROM "${serviceSchema}".world_draft_start_location_receipt
        WHERE request_id=frozen.request_id AND commit_id=frozen.commit_id;
    SELECT * INTO STRICT mapping FROM "${serviceSchema}".world_canonical_instance_topology_identity
        WHERE world_instance_id=NEW.world_instance_id AND canonical_game_instance_id=NEW.canonical_game_instance_id
            AND family='ROOM' AND template_id=receipt.room_template_id;
    INSERT INTO "${serviceSchema}".world_canonical_preparation_start_location
        (canonical_game_instance_id,world_instance_id,canonical_tenant_id,canonical_version_id,room_template_id,
         runtime_room_instance_id,receipt_digest,graph_digest,evidence_bytes)
    VALUES (NEW.canonical_game_instance_id,NEW.world_instance_id,receipt.canonical_tenant_id,receipt.canonical_version_id,
        receipt.room_template_id,mapping.runtime_room_instance_id,receipt.receipt_digest,receipt.graph_digest,
        decode(NEW.input_json::JSONB->>'worldStartLocationEvidenceBase64','base64'));
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_world_preparation_retain_start_location AFTER INSERT ON world_canonical_instance_preparation
    FOR EACH ROW EXECUTE FUNCTION world_retain_preparation_start_location();

CREATE FUNCTION world_guard_preparation_start_location() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,"${serviceSchema}"
AS $$
DECLARE
    prepared "${serviceSchema}".world_canonical_instance_preparation%ROWTYPE;
    binding TEXT;
    original JSONB;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Original canonical preparation selector is immutable and retained' USING ERRCODE='23514';
    END IF;
    SELECT p.* INTO STRICT prepared FROM "${serviceSchema}".world_canonical_instance_preparation p
        WHERE p.canonical_game_instance_id=NEW.canonical_game_instance_id
            AND p.xmin::TEXT=(txid_current() % 4294967296)::TEXT;
    original := prepared.input_json::JSONB;
    SELECT release_attestation_json INTO STRICT binding FROM "${serviceSchema}".world_complete_launch_binding
        WHERE binding_operation_id=(original->'launchBinding'->>'operationId')::UUID;
    IF original->>'schemaVersion' IS DISTINCT FROM '2'
        OR prepared.world_instance_id IS DISTINCT FROM NEW.world_instance_id
        OR prepared.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
        OR prepared.canonical_version_id IS DISTINCT FROM NEW.canonical_version_id
        OR NEW.evidence_bytes IS DISTINCT FROM decode(original->>'worldStartLocationEvidenceBase64','base64')
        OR NOT EXISTS (SELECT 1 FROM "${serviceSchema}".world_draft_start_location_receipt s
            JOIN "${serviceSchema}".world_canonical_frozen_topology f ON f.request_id=s.request_id AND f.commit_id=s.commit_id
            WHERE f.capture_id=prepared.capture_id AND s.canonical_tenant_id=NEW.canonical_tenant_id
                AND s.canonical_version_id=NEW.canonical_version_id AND s.room_template_id=NEW.room_template_id
                AND s.receipt_digest=NEW.receipt_digest AND s.graph_digest=NEW.graph_digest)
        OR NOT EXISTS (SELECT 1 FROM "${serviceSchema}".world_canonical_instance_topology_identity m
            JOIN "${serviceSchema}".room_instance r ON r.id=m.runtime_row_id
            JOIN "${serviceSchema}".world_instance w ON w.id=m.world_instance_id
            WHERE m.world_instance_id=prepared.world_instance_id AND m.canonical_game_instance_id=NEW.canonical_game_instance_id
                AND m.family='ROOM' AND m.template_id=NEW.room_template_id
                AND m.runtime_room_instance_id=NEW.runtime_room_instance_id AND r.room_instance_id=NEW.runtime_room_instance_id
                AND r.tenant_id=w.tenant_id AND r.game_instance_id=w.game_instance_id) THEN
        RAISE EXCEPTION 'Original preparation selector differs from exact authored/runtime ROOM mapping' USING ERRCODE='23514';
    END IF;
    PERFORM "${serviceSchema}".world_require_preparation_start_location(original,binding::JSONB,prepared.capture_id);
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_world_preparation_start_location_guard BEFORE INSERT OR UPDATE OR DELETE ON world_canonical_preparation_start_location
    FOR EACH ROW EXECUTE FUNCTION world_guard_preparation_start_location();
CREATE TRIGGER trg_world_preparation_start_location_no_truncate BEFORE TRUNCATE ON world_canonical_preparation_start_location
    FOR EACH STATEMENT EXECUTE FUNCTION world_guard_preparation_start_location();
REVOKE ALL ON FUNCTION world_retain_preparation_start_location() FROM PUBLIC;
REVOKE ALL ON FUNCTION world_guard_preparation_start_location() FROM PUBLIC;
-- [jooq ignore stop]
