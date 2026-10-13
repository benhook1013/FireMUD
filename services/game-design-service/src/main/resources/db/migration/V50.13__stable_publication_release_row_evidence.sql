-- V1 preserves the exact V50.12 row-JSON bytes while excluding future additive columns.
-- Keep this explicit projection aligned with RELEASE_ROW_EVIDENCE_V1 in
-- GameDesignPublicationOperationRepository. The projection is intentionally not widened.
-- [jooq ignore start]
CREATE FUNCTION publication_release_row_evidence_v1(release_row published_release_bundle) RETURNS TEXT AS $$
    SELECT jsonb_build_object(
        'artifact_digests_json', release_row.artifact_digests_json,
        'attestation_schema_version', release_row.attestation_schema_version,
        'canonical_tenant_id', release_row.canonical_tenant_id,
        'canonical_version_id', release_row.canonical_version_id,
        'command_definitions_json', release_row.command_definitions_json,
        'generation_config_revision', release_row.generation_config_revision,
        'id', release_row.id,
        'manifest_hash', release_row.manifest_hash,
        'manifest_schema_version', release_row.manifest_schema_version,
        'participant_digests_json', release_row.participant_digests_json,
        'publish_workflow_id', release_row.publish_workflow_id,
        'published_at', release_row.published_at,
        'published_release_bundle_ref', release_row.published_release_bundle_ref,
        'required_manifest_asset_keys_json', release_row.required_manifest_asset_keys_json,
        'script_only', release_row.script_only,
        'script_patch_version', release_row.script_patch_version,
        'tenant_id', release_row.tenant_id,
        'version_id', release_row.version_id,
        'version_number', release_row.version_number,
        'world_published_start_location_evidence_json', release_row.world_published_start_location_evidence_json
    )::TEXT;
$$ LANGUAGE SQL IMMUTABLE STRICT;

-- Keep the V50.6 receipt framing and all publication, attempt, and Version gates unchanged.
CREATE OR REPLACE FUNCTION verify_publication_operation_commit() RETURNS trigger AS $$
DECLARE operation game_design_publication_operation%ROWTYPE; release_json TEXT; attempt_status TEXT;
BEGIN
    SELECT * INTO operation FROM game_design_publication_operation WHERE publish_workflow_id = NEW.publish_workflow_id;
    IF NOT FOUND THEN RETURN NULL; END IF;
    SELECT status INTO attempt_status FROM publish_attempt WHERE publish_workflow_id = operation.publish_workflow_id;
    SELECT publication_release_row_evidence_v1(r) INTO release_json FROM published_release_bundle r
        WHERE r.tenant_id = operation.tenant_id AND r.version_id = operation.version_id;
    IF operation.outcome <> 'PENDING' AND operation.result_bytes IS DISTINCT FROM
        int4send(octet_length(convert_to('game-design-publication-owner-readback/v1', 'UTF8')))
        || convert_to('game-design-publication-owner-readback/v1', 'UTF8')
        || int4send(octet_length(operation.request_bytes)) || operation.request_bytes
        || int4send(octet_length(convert_to(operation.outcome, 'UTF8'))) || convert_to(operation.outcome, 'UTF8')
        || int4send(octet_length(convert_to(COALESCE(operation.release_row_json, ''), 'UTF8')))
        || convert_to(COALESCE(operation.release_row_json, ''), 'UTF8') THEN
        RAISE EXCEPTION 'publication result bytes are not the exact owner receipt' USING ERRCODE = 'check_violation';
    END IF;
    IF operation.outcome = 'PENDING' AND (release_json IS NOT NULL OR attempt_status <> 'PENDING') THEN
        RAISE EXCEPTION 'selected publication must commit receipt and owner result together' USING ERRCODE = 'check_violation';
    ELSIF operation.outcome = 'NO_PUBLICATION' AND (release_json IS NOT NULL OR attempt_status <> 'FAILED') THEN
        RAISE EXCEPTION 'no-publication seal contradicts owner commit' USING ERRCODE = 'check_violation';
    ELSIF operation.outcome = 'PUBLISHED' AND (release_json IS DISTINCT FROM operation.release_row_json
        OR attempt_status <> 'SUCCEEDED'
        OR NOT EXISTS (SELECT 1 FROM version WHERE id = operation.version_id AND tenant_id = operation.tenant_id AND version_state = 'PUBLISHED')
        OR NOT EXISTS (SELECT 1 FROM version_asset_artifact WHERE tenant_id = operation.tenant_id AND version_id = operation.version_id AND artifact_state = 'PUBLISHED')) THEN
        RAISE EXCEPTION 'publication receipt lacks atomic owner result' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Keep the V50.7 terminal framing, closed content projection, and committed-epoch checks unchanged.
CREATE OR REPLACE FUNCTION guard_publication_terminal_extension() RETURNS trigger AS $$
DECLARE release published_release_bundle%ROWTYPE; owner_version version%ROWTYPE;
    expected BYTEA; content BYTEA; freeze_epoch BIGINT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.terminal_evidence_bytes IS NOT NULL THEN
            RAISE EXCEPTION 'new publication operation must remain pending' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    -- This extension is captured only at the original seal, never backfilled onto V50 history.
    IF OLD.outcome <> 'PENDING' OR OLD.terminal_evidence_bytes IS NOT NULL
        OR NEW.outcome = 'PENDING' OR NEW.terminal_evidence_bytes IS NULL THEN
        RAISE EXCEPTION 'publication terminal extension is immutable and cannot be backfilled' USING ERRCODE = 'check_violation';
    END IF;
    expected := int4send(octet_length(convert_to('game-design-publication-terminal/v1', 'UTF8')))
        || convert_to('game-design-publication-terminal/v1', 'UTF8')
        || int4send(octet_length(NEW.request_bytes)) || NEW.request_bytes
        || int4send(octet_length(convert_to(NEW.outcome, 'UTF8'))) || convert_to(NEW.outcome, 'UTF8');
    IF NEW.outcome = 'PUBLISHED' THEN
        SELECT * INTO STRICT release FROM published_release_bundle
            WHERE tenant_id = NEW.tenant_id AND version_id = NEW.version_id;
        SELECT * INTO STRICT owner_version FROM version
            WHERE tenant_id = NEW.tenant_id AND id = NEW.version_id FOR UPDATE;
        freeze_epoch := ((convert_from(published_world_selector_frame(NEW.request_bytes, 2), 'UTF8')::JSONB)
            ->'request'->>'versionStateEpoch')::BIGINT;
        content := publication_release_content(release);
        IF owner_version.version_state <> 'PUBLISHED'
            OR owner_version.version_state_epoch <> freeze_epoch + 1
            OR NEW.publication_version_state_epoch IS DISTINCT FROM owner_version.version_state_epoch
            OR NEW.release_content_bytes IS DISTINCT FROM content
            OR NEW.published_release_bundle_digest IS DISTINCT FROM 'sha256:' || encode(sha256(content), 'hex')
            OR release.publish_workflow_id IS DISTINCT FROM NEW.publish_workflow_id
            OR publication_release_row_evidence_v1(release) IS DISTINCT FROM NEW.release_row_json THEN
            RAISE EXCEPTION 'publication terminal content or committed epoch differs from owner seal' USING ERRCODE = 'check_violation';
        END IF;
        expected := expected || int4send(octet_length(content)) || content
            || int4send(octet_length(convert_to(NEW.publication_version_state_epoch::TEXT, 'UTF8')))
            || convert_to(NEW.publication_version_state_epoch::TEXT, 'UTF8');
    END IF;
    IF NEW.terminal_evidence_bytes IS DISTINCT FROM expected THEN
        RAISE EXCEPTION 'publication terminal bytes differ from original owner result' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Preserve the complete V50.9 policy-set gate while using the same fixed release-row evidence.
CREATE OR REPLACE FUNCTION guard_published_realm_policy_set() RETURNS trigger AS $$
DECLARE owner_version version%ROWTYPE; owner_game game%ROWTYPE;
    operation game_design_publication_operation%ROWTYPE;
    capture game_design_realm_policy_capture%ROWTYPE;
    snapshot game_design_realm_policy_snapshot%ROWTYPE;
    release published_release_bundle%ROWTYPE;
    row_count BIGINT; realm_count BIGINT; production_count BIGINT; target_proof_key_count BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'published realm policy set is immutable' USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.sealed IS DISTINCT FROM FALSE OR NEW.sealed IS DISTINCT FROM TRUE
            OR (to_jsonb(NEW) - 'sealed') IS DISTINCT FROM (to_jsonb(OLD) - 'sealed') THEN
            RAISE EXCEPTION 'published realm policy set may only be sealed once' USING ERRCODE = 'check_violation';
        END IF;
        SELECT * INTO STRICT snapshot FROM game_design_realm_policy_snapshot
            WHERE canonical_tenant_id = NEW.canonical_tenant_id
                AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.source_commit_id;
        SELECT count(*), count(DISTINCT realm_slug),
            count(*) FILTER (WHERE visible AND public_production)
            INTO row_count, realm_count, production_count
            FROM game_design_published_realm_policy
            WHERE canonical_tenant_id = NEW.canonical_tenant_id
                AND canonical_version_id = NEW.canonical_version_id;
        IF row_count <> NEW.policy_count
            OR row_count <> jsonb_array_length(snapshot.snapshot_json::JSONB->'policies')
            OR realm_count <> row_count OR production_count <> 1
            OR NEW.policy_set_digest !~ '^sha256:[0-9a-f]{64}$' THEN
            RAISE EXCEPTION 'published realm policy set is incomplete or has invalid cardinality' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.sealed IS DISTINCT FROM FALSE
        OR jsonb_typeof(NEW.target_proof_json::JSONB) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'published realm policy set requires a closed complete TargetProof' USING ERRCODE = 'check_violation';
    END IF;
    SELECT count(*) INTO target_proof_key_count
        FROM jsonb_object_keys(NEW.target_proof_json::JSONB);
    IF target_proof_key_count <> 7
        OR (NEW.target_proof_json::JSONB - ARRAY['canonicalTenantId', 'canonicalVersionId', 'gameDesignVersionRowId',
            'gameDesignVersionTenantKey', 'sourceGameRowId', 'sourceGameTenantKey', 'sourceProvenanceKind'])
            IS DISTINCT FROM '{}'::JSONB
        OR jsonb_typeof(NEW.target_proof_json::JSONB->'canonicalTenantId') IS DISTINCT FROM 'string'
        OR jsonb_typeof(NEW.target_proof_json::JSONB->'canonicalVersionId') IS DISTINCT FROM 'string'
        OR jsonb_typeof(NEW.target_proof_json::JSONB->'gameDesignVersionRowId') IS DISTINCT FROM 'string'
        OR jsonb_typeof(NEW.target_proof_json::JSONB->'gameDesignVersionTenantKey') IS DISTINCT FROM 'string'
        OR jsonb_typeof(NEW.target_proof_json::JSONB->'sourceGameRowId') IS DISTINCT FROM 'string'
        OR jsonb_typeof(NEW.target_proof_json::JSONB->'sourceGameTenantKey') IS DISTINCT FROM 'string'
        OR jsonb_typeof(NEW.target_proof_json::JSONB->'sourceProvenanceKind') IS DISTINCT FROM 'string' THEN
        RAISE EXCEPTION 'published realm policy set requires a closed complete TargetProof' USING ERRCODE = 'check_violation';
    END IF;

    SELECT v.* INTO owner_version FROM version v JOIN game g
        ON g.id = v.identity_source_game_row_id
        AND g.tenant_id = v.identity_source_game_tenant_key
        AND g.canonical_tenant_id = v.canonical_tenant_id
        AND g.tenant_identity_provenance_kind = v.identity_source_provenance_kind
        AND g.tenant_identity_source_game_id = g.id
        AND g.tenant_identity_source_legacy_tenant_id = g.tenant_id
        WHERE v.id = NEW.game_design_version_row_id
            AND v.tenant_id = NEW.target_proof_json::JSONB->>'gameDesignVersionTenantKey'
            AND v.canonical_tenant_id = NEW.canonical_tenant_id
            AND v.canonical_version_id = NEW.canonical_version_id
            AND v.identity_source_game_row_id::TEXT = NEW.target_proof_json::JSONB->>'sourceGameRowId'
            AND v.identity_source_game_tenant_key = NEW.target_proof_json::JSONB->>'sourceGameTenantKey'
            AND v.identity_source_provenance_kind = NEW.target_proof_json::JSONB->>'sourceProvenanceKind';
    IF NOT FOUND OR owner_version.id IS DISTINCT FROM NEW.game_design_version_row_id
        OR owner_version.version_state IS DISTINCT FROM 'PUBLISHED'
        OR owner_version.version_state_epoch IS DISTINCT FROM NEW.publication_version_state_epoch
        OR owner_version.version_number IS DISTINCT FROM NEW.version_number
        OR owner_version.is_script_only IS DISTINCT FROM FALSE
        OR owner_version.script_patch_version IS NOT NULL
        OR owner_version.base_version_id IS NOT NULL
        OR NEW.target_proof_json::JSONB->>'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
        OR NEW.target_proof_json::JSONB->>'canonicalVersionId' IS DISTINCT FROM NEW.canonical_version_id::TEXT
        OR NEW.target_proof_json::JSONB->>'gameDesignVersionRowId' IS DISTINCT FROM owner_version.id::TEXT
        OR NEW.target_proof_json::JSONB->>'gameDesignVersionTenantKey' IS DISTINCT FROM owner_version.tenant_id
        OR NEW.target_proof_json::JSONB->>'sourceGameRowId' IS DISTINCT FROM owner_version.identity_source_game_row_id::TEXT
        OR NEW.target_proof_json::JSONB->>'sourceGameTenantKey' IS DISTINCT FROM owner_version.identity_source_game_tenant_key
        OR NEW.target_proof_json::JSONB->>'sourceProvenanceKind' IS DISTINCT FROM owner_version.identity_source_provenance_kind THEN
        RAISE EXCEPTION 'published realm policy set differs from exact full Version owner' USING ERRCODE = 'check_violation';
    END IF;
    SELECT g.* INTO STRICT owner_game FROM game g WHERE g.id = owner_version.identity_source_game_row_id;
    IF owner_game.id::TEXT IS DISTINCT FROM NEW.target_proof_json::JSONB->>'sourceGameRowId'
        OR owner_game.tenant_id IS DISTINCT FROM NEW.target_proof_json::JSONB->>'sourceGameTenantKey'
        OR owner_game.tenant_identity_provenance_kind IS DISTINCT FROM NEW.target_proof_json::JSONB->>'sourceProvenanceKind'
        OR owner_game.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id THEN
        RAISE EXCEPTION 'published realm policy set source provenance changed' USING ERRCODE = 'check_violation';
    END IF;

    SELECT * INTO operation FROM game_design_publication_operation
        WHERE publish_workflow_id = NEW.publish_workflow_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'published realm policy set operation is unavailable' USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO capture FROM game_design_realm_policy_capture
        WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'published realm policy set source capture is unavailable' USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO STRICT snapshot FROM game_design_realm_policy_snapshot
        WHERE canonical_tenant_id = NEW.canonical_tenant_id
            AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.source_commit_id;
    SELECT * INTO release FROM published_release_bundle
        WHERE tenant_id = owner_version.tenant_id AND version_id = owner_version.id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'published realm policy set release bundle is unavailable' USING ERRCODE = 'check_violation';
    END IF;
    IF operation.outcome IS DISTINCT FROM 'PUBLISHED'
        OR operation.request_bytes IS DISTINCT FROM NEW.operation_bytes
        OR operation.terminal_evidence_bytes IS DISTINCT FROM NEW.terminal_evidence_bytes
        OR operation.release_content_bytes IS DISTINCT FROM NEW.release_content_bytes
        OR operation.published_release_bundle_digest IS DISTINCT FROM NEW.published_release_bundle_digest
        OR operation.publication_version_state_epoch IS DISTINCT FROM NEW.publication_version_state_epoch
        OR operation.tenant_id IS DISTINCT FROM owner_version.tenant_id
        OR operation.version_id IS DISTINCT FROM owner_version.id
        OR operation.publish_workflow_id IS DISTINCT FROM NEW.publish_workflow_id
        OR capture.publish_workflow_id IS DISTINCT FROM NEW.publish_workflow_id
        OR capture.operation_bytes IS DISTINCT FROM NEW.operation_bytes
        OR capture.capture_bytes IS DISTINCT FROM NEW.capture_bytes
        OR capture.commit_id IS DISTINCT FROM NEW.source_commit_id
        OR snapshot.snapshot_json::JSONB->>'sourceEpoch' IS DISTINCT FROM NEW.source_epoch
        OR publication_release_content(release) IS DISTINCT FROM NEW.release_content_bytes
        OR 'sha256:' || encode(sha256(publication_release_content(release)), 'hex')
            IS DISTINCT FROM NEW.published_release_bundle_digest
        OR release.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
        OR release.canonical_version_id IS DISTINCT FROM NEW.canonical_version_id
        OR release.version_number IS DISTINCT FROM NEW.version_number
        OR release.published_release_bundle_ref IS DISTINCT FROM NEW.published_release_bundle_ref
        OR release.publish_workflow_id IS DISTINCT FROM NEW.publish_workflow_id
        OR release.manifest_hash IS DISTINCT FROM NEW.manifest_hash
        OR publication_release_row_evidence_v1(release) IS DISTINCT FROM operation.release_row_json
        OR NOT EXISTS (SELECT 1 FROM game_design_realm_policy_source s
            WHERE s.canonical_tenant_id = NEW.canonical_tenant_id
                AND s.canonical_version_id = NEW.canonical_version_id
                AND s.visible_commit_id = NEW.source_commit_id AND s.source_epoch = NEW.source_epoch)
        OR NOT EXISTS (SELECT 1 FROM game_design_draft_commit_visibility v
            WHERE v.canonical_tenant_id = NEW.canonical_tenant_id
                AND v.canonical_version_id = NEW.canonical_version_id
                AND v.commit_id = NEW.source_commit_id)
        OR jsonb_array_length(snapshot.snapshot_json::JSONB->'policies') <> NEW.policy_count THEN
        RAISE EXCEPTION 'published realm policy set is not bound to exact sealed operation, capture, source, and release' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
-- [jooq ignore stop]
