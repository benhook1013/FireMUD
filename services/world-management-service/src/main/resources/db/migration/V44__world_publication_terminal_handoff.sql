-- Retain the authenticated Game Design terminal result before World leaves FROZEN. The owner
-- transition and immutable receipt are committed atomically; absence or uncertainty never settles.
CREATE TABLE world_design_publication_terminal (
    publication_fence UUID PRIMARY KEY
        REFERENCES world_design_publication_fence_attempt(publication_fence) ON DELETE RESTRICT,
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    publication_request_id VARCHAR(256) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    publish_workflow_id VARCHAR(537) NOT NULL,
    freeze_version_state_epoch BIGINT NOT NULL CHECK (freeze_version_state_epoch > 0),
    applied_commit_id VARCHAR(256) NOT NULL,
    content_digest VARCHAR(64) NOT NULL,
    digest_schema_version INTEGER NOT NULL CHECK (digest_schema_version > 0),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('PUBLISHED', 'NO_PUBLICATION')),
    publication_version_state_epoch BIGINT,
    published_release_bundle_ref VARCHAR(512),
    published_release_bundle_digest VARCHAR(71),
    operation_bytes BYTEA NOT NULL CHECK (octet_length(operation_bytes) > 0),
    terminal_evidence_bytes BYTEA NOT NULL CHECK (octet_length(terminal_evidence_bytes) > 0),
    terminal_evidence_digest VARCHAR(64) NOT NULL CHECK (terminal_evidence_digest ~ '^[0-9a-f]{64}$'),
    world_evidence_bytes BYTEA NOT NULL CHECK (octet_length(world_evidence_bytes) > 0),
    world_request_json TEXT NOT NULL,
    selector_receipt_bytes BYTEA NOT NULL CHECK (octet_length(selector_receipt_bytes) > 0),
    original_account_binding_bytes BYTEA NOT NULL CHECK (octet_length(original_account_binding_bytes) > 0),
    applied_result_bytes BYTEA NOT NULL CHECK (octet_length(applied_result_bytes) > 0),
    release_content_bytes BYTEA NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_world_publication_terminal_scope_request UNIQUE
        (target_namespace, canonical_tenant_id, canonical_version_id, publication_request_id),
    CONSTRAINT ck_world_publication_terminal_identity CHECK (
        target_namespace ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'
        AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND octet_length(publication_request_id) BETWEEN 1 AND 256
        AND position(':' IN publication_request_id) = 0
        AND request_digest ~ '^[0-9a-f]{64}$'
        AND publish_workflow_id = 'publish:' || canonical_tenant_id::TEXT
            || ':publish-request:' || publication_request_id
        AND octet_length(applied_commit_id) BETWEEN 1 AND 256
        AND content_digest ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_world_publication_terminal_outcome_shape CHECK (
        (outcome = 'PUBLISHED'
            AND publication_version_state_epoch IS NOT NULL
            AND publication_version_state_epoch = freeze_version_state_epoch + 1
            AND published_release_bundle_ref IS NOT NULL
            AND octet_length(published_release_bundle_ref) BETWEEN 1 AND 512
            AND published_release_bundle_digest IS NOT NULL
            AND published_release_bundle_digest ~ '^sha256:[0-9a-f]{64}$'
            AND octet_length(release_content_bytes) > 0)
        OR
        (outcome = 'NO_PUBLICATION'
            AND publication_version_state_epoch IS NULL
            AND published_release_bundle_ref IS NULL
            AND published_release_bundle_digest IS NULL
            AND octet_length(release_content_bytes) = 0)
    )
);

-- [jooq ignore start]
REVOKE ALL ON world_design_publication_terminal FROM PUBLIC;

CREATE FUNCTION world_validate_publication_terminal()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    attempt "${serviceSchema}".world_design_publication_fence_attempt%ROWTYPE;
    owner_row "${serviceSchema}".world_design_publication_fence_owner%ROWTYPE;
    frozen "${serviceSchema}".world_canonical_frozen_topology%ROWTYPE;
    receipt "${serviceSchema}".world_draft_start_location_receipt%ROWTYPE;
    application "${serviceSchema}".world_draft_graph_application%ROWTYPE;
    graph "${serviceSchema}".world_topology_draft_commit%ROWTYPE;
    identity_row "${serviceSchema}".world_authored_version_identity%ROWTYPE;
    intake_row "${serviceSchema}".world_authored_source_intake%ROWTYPE;
    world_request JSONB;
    world_evidence JSONB;
    freeze_request JSONB;
    expected_tuples JSONB;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'World publication terminal evidence is immutable' USING ERRCODE = '55000';
    END IF;
    IF current_setting('transaction_isolation') <> 'read committed'
        OR current_setting('transaction_read_only') <> 'off' THEN
        RAISE EXCEPTION 'World publication terminal requires writable READ COMMITTED'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT attempt FROM "${serviceSchema}".world_design_publication_fence_attempt
        WHERE publication_fence=NEW.publication_fence;
    SELECT * INTO STRICT owner_row FROM "${serviceSchema}".world_design_publication_fence_owner
        WHERE target_namespace=attempt.target_namespace
            AND canonical_tenant_id=attempt.canonical_tenant_id
            AND local_tenant_key=attempt.local_tenant_key AND version_id=attempt.version_id
        FOR UPDATE;
    SELECT * INTO STRICT frozen FROM "${serviceSchema}".world_canonical_frozen_topology
        WHERE publication_fence=NEW.publication_fence;
    SELECT * INTO STRICT identity_row FROM "${serviceSchema}".world_authored_version_identity
        WHERE operation_id=attempt.version_identity_operation_id;
    SELECT * INTO STRICT intake_row FROM "${serviceSchema}".world_authored_source_intake
        WHERE operation_id=attempt.intake_operation_id;
    SELECT * INTO STRICT graph FROM "${serviceSchema}".world_topology_draft_commit
        WHERE request_id=frozen.request_id AND commit_id=frozen.commit_id;
    SELECT * INTO STRICT receipt FROM "${serviceSchema}".world_draft_start_location_receipt
        WHERE request_id=frozen.request_id AND commit_id=frozen.commit_id;
    SELECT * INTO STRICT application FROM "${serviceSchema}".world_draft_graph_application
        WHERE request_id=frozen.request_id AND commit_id=frozen.commit_id
            AND operation_id=receipt.operation_id;

    world_request := NEW.world_request_json::JSONB;
    world_evidence := convert_from(NEW.world_evidence_bytes, 'UTF8')::JSONB;
    freeze_request := frozen.freeze_request_json::JSONB;
    SELECT coalesce(jsonb_agg(value ORDER BY value->>'owner',value->>'aggregateType',value->>'aggregateId',
        value->>'scopeType',value->>'scopeId',value->>'expectedEpoch'), '[]'::JSONB)
        INTO expected_tuples FROM jsonb_array_elements(freeze_request->'suppliedOwnedAffectedTuples');

    IF attempt.owner_binding_schema_version IS DISTINCT FROM 1
        OR owner_row.owner_freeze_phase IS DISTINCT FROM 'FROZEN'
        OR owner_row.current_publication_fence IS DISTINCT FROM NEW.publication_fence
        OR NEW.target_namespace IS DISTINCT FROM attempt.target_namespace
        OR NEW.canonical_tenant_id IS DISTINCT FROM attempt.canonical_tenant_id
        OR NEW.canonical_version_id IS DISTINCT FROM attempt.canonical_version_id
        OR NEW.publication_request_id IS DISTINCT FROM attempt.publication_request_id
        OR NEW.request_digest IS DISTINCT FROM attempt.request_digest
        OR NEW.publish_workflow_id IS DISTINCT FROM attempt.publish_workflow_id
        OR NEW.freeze_version_state_epoch IS DISTINCT FROM attempt.version_state_epoch
        OR NEW.applied_commit_id IS DISTINCT FROM attempt.applied_commit_id
        OR NEW.content_digest IS DISTINCT FROM attempt.content_digest
        OR NEW.digest_schema_version IS DISTINCT FROM attempt.digest_schema_version THEN
        RAISE EXCEPTION 'World terminal differs from exact current V25 publication owner'
            USING ERRCODE = '23514';
    END IF;

    IF encode(sha256(NEW.terminal_evidence_bytes), 'hex') IS DISTINCT FROM NEW.terminal_evidence_digest
        OR jsonb_typeof(world_request) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(world_request)) <> 13
        OR jsonb_typeof(world_evidence) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(world_evidence)) <> 5
        OR world_evidence->>'schema' IS DISTINCT FROM 'world-published-start-location-evidence/v1'
        OR world_evidence->'request' IS DISTINCT FROM world_request
        OR decode(world_evidence->>'selectorReceiptBytesBase64','base64') IS DISTINCT FROM NEW.selector_receipt_bytes
        OR decode(world_evidence->>'originalAccountBindingBytesBase64','base64') IS DISTINCT FROM NEW.original_account_binding_bytes
        OR decode(world_evidence->>'appliedResultBytesBase64','base64') IS DISTINCT FROM NEW.applied_result_bytes
        OR world_request->>'targetNamespace' IS DISTINCT FROM attempt.target_namespace
        OR world_request->>'canonicalTenantId' IS DISTINCT FROM attempt.canonical_tenant_id::TEXT
        OR world_request->>'canonicalVersionId' IS DISTINCT FROM attempt.canonical_version_id::TEXT
        OR world_request->>'intakeRequestId' IS DISTINCT FROM attempt.intake_request_id::TEXT
        OR world_request->>'publicationFence' IS DISTINCT FROM attempt.publication_fence::TEXT
        OR world_request->>'publicationRequestId' IS DISTINCT FROM attempt.publication_request_id
        OR world_request->>'requestDigest' IS DISTINCT FROM attempt.request_digest
        OR world_request->>'versionStateEpoch' IS DISTINCT FROM attempt.version_state_epoch::TEXT
        OR world_request->>'publishWorkflowId' IS DISTINCT FROM attempt.publish_workflow_id
        OR world_request->>'appliedCommitId' IS DISTINCT FROM attempt.applied_commit_id
        OR world_request->>'contentDigest' IS DISTINCT FROM attempt.content_digest
        OR world_request->>'digestSchemaVersion' IS DISTINCT FROM attempt.digest_schema_version::TEXT
        OR world_request->'worldAffectedTuples' IS DISTINCT FROM expected_tuples
        OR freeze_request->>'targetNamespace' IS DISTINCT FROM attempt.target_namespace
        OR freeze_request->>'canonicalTenantId' IS DISTINCT FROM attempt.canonical_tenant_id::TEXT
        OR freeze_request->>'canonicalVersionId' IS DISTINCT FROM attempt.canonical_version_id::TEXT
        OR freeze_request->>'publicationFence' IS DISTINCT FROM attempt.publication_fence::TEXT
        OR freeze_request->>'publicationRequestId' IS DISTINCT FROM attempt.publication_request_id
        OR freeze_request->>'requestDigest' IS DISTINCT FROM attempt.request_digest
        OR freeze_request->>'versionStateEpoch' IS DISTINCT FROM attempt.version_state_epoch::TEXT
        OR freeze_request->>'publishWorkflowId' IS DISTINCT FROM attempt.publish_workflow_id
        OR freeze_request->>'appliedCommitId' IS DISTINCT FROM attempt.applied_commit_id
        OR freeze_request->>'contentDigest' IS DISTINCT FROM attempt.content_digest
        OR freeze_request->>'digestSchemaVersion' IS DISTINCT FROM attempt.digest_schema_version::TEXT
        OR identity_row.target_namespace IS DISTINCT FROM attempt.target_namespace
        OR identity_row.canonical_tenant_id IS DISTINCT FROM attempt.canonical_tenant_id
        OR identity_row.canonical_version_id IS DISTINCT FROM attempt.canonical_version_id
        OR identity_row.local_tenant_key IS DISTINCT FROM attempt.local_tenant_key
        OR identity_row.local_version_key IS DISTINCT FROM attempt.version_id
        OR identity_row.intake_operation_id IS DISTINCT FROM attempt.intake_operation_id
        OR identity_row.intake_request_id IS DISTINCT FROM attempt.intake_request_id
        OR identity_row.source_operation_id IS DISTINCT FROM attempt.source_operation_id
        OR identity_row.source_evidence_digest IS DISTINCT FROM attempt.source_evidence_digest
        OR identity_row.intake_receipt_digest IS DISTINCT FROM attempt.intake_receipt_digest
        OR intake_row.target_namespace IS DISTINCT FROM attempt.target_namespace
        OR intake_row.canonical_tenant_id IS DISTINCT FROM attempt.canonical_tenant_id
        OR intake_row.local_tenant_key IS DISTINCT FROM attempt.local_tenant_key
        OR intake_row.intake_request_id IS DISTINCT FROM attempt.intake_request_id
        OR intake_row.source_operation_id IS DISTINCT FROM attempt.source_operation_id
        OR intake_row.source_evidence_digest IS DISTINCT FROM attempt.source_evidence_digest
        OR intake_row.receipt_digest IS DISTINCT FROM attempt.intake_receipt_digest
        OR frozen.request_id IS DISTINCT FROM graph.request_id
        OR frozen.commit_id IS DISTINCT FROM graph.commit_id
        OR frozen.graph_bytes IS DISTINCT FROM graph.graph_bytes
        OR application.account_binding_bytes IS DISTINCT FROM NEW.original_account_binding_bytes
        OR receipt.account_binding_bytes IS DISTINCT FROM NEW.original_account_binding_bytes
        OR receipt.receipt_bytes IS DISTINCT FROM NEW.selector_receipt_bytes
        OR application.result_bytes IS DISTINCT FROM NEW.applied_result_bytes
        OR graph.binding_digest IS DISTINCT FROM receipt.binding_digest
        OR receipt.graph_digest IS DISTINCT FROM ('sha256:' || frozen.graph_sha256) THEN
        RAISE EXCEPTION 'World terminal does not bind the exact immutable source, selector, APPLIED result and freeze checkpoint'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.outcome='PUBLISHED'
        AND (NEW.publication_version_state_epoch IS DISTINCT FROM attempt.version_state_epoch + 1
            OR NEW.published_release_bundle_digest IS DISTINCT FROM
                ('sha256:' || encode(sha256(NEW.release_content_bytes), 'hex'))) THEN
        RAISE EXCEPTION 'PUBLISHED World terminal lacks the exact next Game Design publication epoch'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_world_publication_terminal_validate
    BEFORE INSERT ON world_design_publication_terminal FOR EACH ROW
    EXECUTE FUNCTION world_validate_publication_terminal();
CREATE TRIGGER trg_world_publication_terminal_immutable
    BEFORE UPDATE OR DELETE ON world_design_publication_terminal FOR EACH ROW
    EXECUTE FUNCTION world_reject_publication_attempt_history_mutation();
CREATE TRIGGER trg_world_publication_terminal_no_truncate
    BEFORE TRUNCATE ON world_design_publication_terminal FOR EACH STATEMENT
    EXECUTE FUNCTION world_reject_publication_attempt_history_mutation();

-- A valid-looking receipt is not a terminal result until the same transaction commits the
-- matching owner phase. This deferred check observes the post-CAS row at transaction commit.
CREATE FUNCTION world_require_publication_terminal_owner_phase()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    expected_phase TEXT;
    actual_phase TEXT;
BEGIN
    expected_phase := CASE WHEN NEW.outcome='PUBLISHED' THEN 'PUBLISHED' ELSE 'ABORTED' END;
    SELECT o.owner_freeze_phase INTO actual_phase
    FROM "${serviceSchema}".world_design_publication_fence_attempt a
    JOIN "${serviceSchema}".world_design_publication_fence_owner o
        ON o.target_namespace=a.target_namespace
        AND o.canonical_tenant_id=a.canonical_tenant_id
        AND o.local_tenant_key=a.local_tenant_key AND o.version_id=a.version_id
    WHERE a.publication_fence=NEW.publication_fence
        AND a.owner_binding_schema_version=1
        AND a.target_namespace=NEW.target_namespace
        AND a.canonical_tenant_id=NEW.canonical_tenant_id
        AND a.canonical_version_id=NEW.canonical_version_id
        AND a.publication_request_id=NEW.publication_request_id
        AND a.request_digest=NEW.request_digest
        AND a.publish_workflow_id=NEW.publish_workflow_id
        AND a.version_state_epoch=NEW.freeze_version_state_epoch
        AND a.applied_commit_id=NEW.applied_commit_id
        AND a.content_digest=NEW.content_digest
        AND a.digest_schema_version=NEW.digest_schema_version
        AND o.current_publication_fence=NEW.publication_fence;
    IF NOT FOUND OR actual_phase IS DISTINCT FROM expected_phase THEN
        RAISE EXCEPTION 'World terminal receipt must commit with its exact owner phase'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_world_publication_terminal_owner_phase_commit
    AFTER INSERT ON world_design_publication_terminal
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION world_require_publication_terminal_owner_phase();

-- Preserve the original OPEN-to-FROZEN writer while allowing only a just-inserted immutable V44
-- terminal row to authorize FROZEN-to-PUBLISHED/ABORTED. No reopen or reconciliation transition
-- is introduced by this migration.
CREATE OR REPLACE FUNCTION world_protect_publication_fence_owner()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'World publication-fence owner rows cannot be deleted' USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.owner_freeze_phase = 'OPEN' AND NEW.current_publication_fence IS NULL THEN RETURN NEW; END IF;
        RAISE EXCEPTION 'World publication-fence owner rows must be created OPEN without a fence' USING ERRCODE = '55000';
    END IF;
    IF OLD.target_namespace IS NOT DISTINCT FROM NEW.target_namespace
        AND OLD.canonical_tenant_id IS NOT DISTINCT FROM NEW.canonical_tenant_id
        AND OLD.local_tenant_key IS NOT DISTINCT FROM NEW.local_tenant_key
        AND OLD.version_id IS NOT DISTINCT FROM NEW.version_id
        AND OLD.owner_freeze_phase='OPEN' AND NEW.owner_freeze_phase='FROZEN'
        AND OLD.current_publication_fence IS NULL AND NEW.current_publication_fence IS NOT NULL THEN
        RETURN NEW;
    END IF;
    IF OLD.target_namespace IS NOT DISTINCT FROM NEW.target_namespace
        AND OLD.canonical_tenant_id IS NOT DISTINCT FROM NEW.canonical_tenant_id
        AND OLD.local_tenant_key IS NOT DISTINCT FROM NEW.local_tenant_key
        AND OLD.version_id IS NOT DISTINCT FROM NEW.version_id
        AND OLD.owner_freeze_phase='FROZEN'
        AND OLD.current_publication_fence IS NOT DISTINCT FROM NEW.current_publication_fence
        AND NEW.owner_freeze_phase IN ('PUBLISHED','ABORTED')
        AND EXISTS (
            SELECT 1 FROM "${serviceSchema}".world_design_publication_terminal t
            WHERE t.publication_fence=OLD.current_publication_fence
                AND t.target_namespace=OLD.target_namespace
                AND t.canonical_tenant_id=OLD.canonical_tenant_id
                AND t.canonical_version_id=(SELECT a.canonical_version_id
                    FROM "${serviceSchema}".world_design_publication_fence_attempt a
                    WHERE a.publication_fence=OLD.current_publication_fence)
                AND ((NEW.owner_freeze_phase='PUBLISHED' AND t.outcome='PUBLISHED')
                    OR (NEW.owner_freeze_phase='ABORTED' AND t.outcome='NO_PUBLICATION'))
        ) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'World publication-fence owner binding or phase transition is immutable'
        USING ERRCODE = '55000';
END;
$$;

-- Fresh allocation now requires the completed terminal handoff, not merely the earlier freeze.
-- All other V35/V40 guards, source checks and historical input bytes remain unchanged.
DO $migration$
DECLARE
    original TEXT;
    phase_anchor TEXT := $anchor$    IF publication_owner.owner_freeze_phase IS DISTINCT FROM 'FROZEN'
        OR publication_owner.current_publication_fence IS DISTINCT FROM capture_row.publication_fence THEN
        RAISE EXCEPTION 'Canonical preparation requires the exact still-FROZEN V25 source owner'
            USING ERRCODE = '23514';
    END IF;$anchor$;
    replacement TEXT := $replacement$    IF publication_owner.owner_freeze_phase IS DISTINCT FROM 'PUBLISHED'
        OR publication_owner.current_publication_fence IS DISTINCT FROM capture_row.publication_fence
        OR NOT EXISTS (
            SELECT 1 FROM "${serviceSchema}".world_design_publication_terminal t
            WHERE t.publication_fence=capture_row.publication_fence
                AND t.target_namespace=identity_row.target_namespace
                AND t.canonical_tenant_id=identity_row.canonical_tenant_id
                AND t.canonical_version_id=identity_row.canonical_version_id
                AND t.outcome='PUBLISHED'
                AND t.publication_request_id=capture_row.freeze_request_json::JSONB->>'publicationRequestId'
                AND t.request_digest=capture_row.freeze_request_json::JSONB->>'requestDigest'
                AND t.publish_workflow_id=capture_row.freeze_request_json::JSONB->>'publishWorkflowId'
                AND t.applied_commit_id=capture_row.freeze_request_json::JSONB->>'appliedCommitId'
                AND t.content_digest=capture_row.freeze_request_json::JSONB->>'contentDigest'
                AND t.digest_schema_version=(capture_row.freeze_request_json::JSONB->>'digestSchemaVersion')::INTEGER
                AND t.published_release_bundle_ref=binding_row.release_attestation_json::JSONB->>'publishedReleaseBundleRef'
                AND t.publication_version_state_epoch<=(binding_row.release_attestation_json::JSONB->>'versionStateEpoch')::BIGINT
                AND t.world_request_json::JSONB=jsonb_set(
                    binding_row.release_attestation_json::JSONB->'worldStartLocationEvidence'->'request',
                    '{versionStateEpoch}',to_jsonb(t.freeze_version_state_epoch::TEXT),FALSE)
        ) THEN
        RAISE EXCEPTION 'Canonical preparation requires the exact published V44 terminal for this retained release and frozen World source'
            USING ERRCODE = '23514';
    END IF;$replacement$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_prepare_canonical_instance(text,text)'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,phase_anchor,'')))/length(phase_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V35 FROZEN preparation phase guard';
    END IF;
    EXECUTE replace(original,phase_anchor,replacement);
END;
$migration$;
-- [jooq ignore stop]
