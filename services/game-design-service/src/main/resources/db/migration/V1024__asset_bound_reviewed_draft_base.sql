-- Fresh reviews bind the actual ordinary asset owner baseline alongside policy and command.
-- Original v1 review rows and their exact evidence bytes remain immutable; no history is backfilled.
-- PostgreSQL owns the original unnamed CHECK name; jOOQ's H2 interpreter assigns another.
-- This changes checks, not generated columns; native migration proof validates the exact guard.
-- [jooq ignore start]
ALTER TABLE game_design_reviewed_draft_base
    DROP CONSTRAINT game_design_reviewed_draft_base_evidence_schema_check;
ALTER TABLE game_design_reviewed_draft_base
    ADD CONSTRAINT game_design_reviewed_draft_base_evidence_schema_check
        CHECK (evidence_schema IN ('game-design-reviewed-draft-base/v1', 'game-design-reviewed-draft-base/v2'));

CREATE OR REPLACE FUNCTION protect_game_design_reviewed_base() RETURNS trigger AS $$
DECLARE
    claim game_design_draft_commit%ROWTYPE;
    owner_version version%ROWTYPE;
    policy_genesis game_design_realm_policy_genesis%ROWTYPE;
    command_genesis game_design_command_source_baseline%ROWTYPE;
    asset_genesis game_design_asset_source_genesis%ROWTYPE;
    asset_genesis_bytes BYTEA;
    witness game_design_realm_policy_version_insert%ROWTYPE;
    base game_design_draft_commit%ROWTYPE;
    policy_snapshot game_design_realm_policy_snapshot%ROWTYPE;
    command_snapshot game_design_command_source_snapshot%ROWTYPE;
    asset_snapshot game_design_asset_source_snapshot%ROWTYPE;
    fence game_design_draft_commit_visibility_fence%ROWTYPE;
    owner_result game_design_draft_commit_owner_result%ROWTYPE;
    owner_name TEXT;
    outcome JSONB;
    epoch JSONB;
    source_bytes BYTEA;
    expected_bytes BYTEA;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Reviewed Draft base evidence is immutable';
    END IF;
    IF NEW.evidence_schema IS DISTINCT FROM 'game-design-reviewed-draft-base/v2' THEN
        RAISE EXCEPTION 'Fresh reviewed base requires complete asset-bound v2 owner evidence';
    END IF;
    SELECT * INTO STRICT claim FROM game_design_draft_commit
        WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id
            AND request_id = NEW.request_id AND commit_id = NEW.commit_id;
    SELECT * INTO STRICT owner_version FROM version WHERE id = claim.game_design_version_row_id FOR UPDATE;
    IF claim.binding_json IS DISTINCT FROM NEW.binding_json OR claim.input_digest IS DISTINCT FROM NEW.input_digest
        OR claim.base_commit_id IS DISTINCT FROM NEW.base_reference OR claim.workflow_state <> 'QUEUED'
        OR EXISTS (SELECT 1 FROM game_design_draft_commit_owner_result r
            WHERE r.canonical_tenant_id = NEW.canonical_tenant_id AND r.canonical_version_id = NEW.canonical_version_id
                AND r.request_id = NEW.request_id AND r.status <> 'NOT_ATTEMPTED')
        OR EXISTS (SELECT 1 FROM game_design_draft_commit_application_slot s
            WHERE s.canonical_tenant_id = NEW.canonical_tenant_id AND s.canonical_version_id = NEW.canonical_version_id
                AND s.request_id = NEW.request_id)
        OR owner_version.version_state <> 'DRAFT'
        OR owner_version.is_script_only OR owner_version.base_version_id IS NOT NULL
        OR owner_version.script_patch_version IS NOT NULL THEN
        RAISE EXCEPTION 'Reviewed base requires exact new full Draft binding';
    END IF;
    IF NEW.base_kind = 'GENESIS' THEN
        SELECT * INTO STRICT policy_genesis FROM game_design_realm_policy_genesis
            WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id
                AND receipt_id = NEW.base_identity AND version_id = claim.game_design_version_row_id;
        SELECT * INTO STRICT command_genesis FROM game_design_command_source_baseline
            WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id
                AND receipt_id = NEW.base_identity AND game_design_version_row_id = claim.game_design_version_row_id;
        SELECT * INTO STRICT witness FROM game_design_realm_policy_version_insert
            WHERE version_id = claim.game_design_version_row_id;
        IF policy_genesis.creation_transaction_id IS DISTINCT FROM witness.creation_transaction_id
            OR command_genesis.creation_transaction_id IS DISTINCT FROM witness.creation_transaction_id
            OR command_genesis.baseline_kind <> 'NEW_DRAFT_EMPTY' THEN
            RAISE EXCEPTION 'Reviewed genesis must retain original shared receipt and insertion witness';
        END IF;
        SELECT * INTO STRICT asset_genesis FROM game_design_asset_source_genesis
            WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id
                AND version_id = claim.game_design_version_row_id;
        IF asset_genesis.creation_transaction_id IS DISTINCT FROM witness.creation_transaction_id
            OR asset_genesis.target_json::JSONB IS DISTINCT FROM jsonb_build_object(
                'canonicalTenantId', claim.canonical_tenant_id::TEXT,
                'canonicalVersionId', claim.canonical_version_id::TEXT,
                'gameDesignVersionRowId', claim.game_design_version_row_id,
                'gameDesignVersionTenantKey', claim.game_design_version_tenant_key,
                'sourceGameRowId', claim.source_game_row_id,
                'sourceGameTenantKey', claim.source_game_tenant_key,
                'sourceProvenanceKind', claim.source_provenance_kind) THEN
            RAISE EXCEPTION 'Reviewed asset genesis must retain the exact target and original insertion witness';
        END IF;
        asset_genesis_bytes := game_design_reviewed_base_frame('game-design-ordinary-asset-source-genesis/v1'::TEXT)
            || game_design_reviewed_base_frame(asset_genesis.target_json)
            || game_design_reviewed_base_frame(asset_genesis.receipt_id::TEXT)
            || game_design_reviewed_base_frame(asset_genesis.creation_transaction_id);
        source_bytes := game_design_reviewed_base_frame('shared-policy-command-asset-genesis/v2'::TEXT)
            || game_design_reviewed_base_frame(witness.creation_transaction_id)
            || game_design_reviewed_base_frame(convert_to(policy_genesis.receipt_json, 'UTF8'))
            || game_design_reviewed_base_frame(command_genesis.receipt_bytes)
            || game_design_reviewed_base_frame(asset_genesis_bytes);
    ELSE
        SELECT * INTO STRICT base FROM game_design_draft_commit
            WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id
                AND commit_id = NEW.base_identity;
        SELECT * INTO STRICT fence FROM game_design_draft_commit_visibility_fence
            WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id
                AND commit_id = NEW.base_identity AND request_id = base.request_id;
        SELECT * INTO STRICT policy_snapshot FROM game_design_realm_policy_snapshot
            WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id
                AND commit_id = NEW.base_identity AND request_id = base.request_id;
        SELECT * INTO STRICT command_snapshot FROM game_design_command_source_snapshot
            WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id
                AND commit_id = NEW.base_identity AND request_id = base.request_id;
        SELECT * INTO STRICT asset_snapshot FROM game_design_asset_source_snapshot
            WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id
                AND commit_id = NEW.base_identity AND request_id = base.request_id;
        IF base.workflow_state <> 'SYNCHRONIZED' OR fence.input_digest IS DISTINCT FROM base.input_digest
            OR command_snapshot.binding_json IS DISTINCT FROM base.binding_json
            OR command_snapshot.input_digest IS DISTINCT FROM base.input_digest
            OR policy_snapshot.snapshot_json::JSONB->>'bindingJson' IS DISTINCT FROM base.binding_json
            OR policy_snapshot.snapshot_json::JSONB->>'bindingDigest' IS DISTINCT FROM base.input_digest
            OR asset_snapshot.snapshot_json::JSONB->>'bindingJson' IS DISTINCT FROM base.binding_json
            OR asset_snapshot.snapshot_json::JSONB->>'bindingDigest' IS DISTINCT FROM base.input_digest
            OR asset_snapshot.snapshot_digest IS DISTINCT FROM
                'sha256:' || encode(sha256(convert_to(asset_snapshot.snapshot_json, 'UTF8')), 'hex')
            OR (base.game_design_version_row_id, base.game_design_version_tenant_key, base.source_game_row_id,
                base.source_game_tenant_key, base.source_provenance_kind) IS DISTINCT FROM
                (claim.game_design_version_row_id, claim.game_design_version_tenant_key, claim.source_game_row_id,
                claim.source_game_tenant_key, claim.source_provenance_kind)
            OR jsonb_array_length(fence.result_vector_json::JSONB) <>
                jsonb_array_length(base.binding_json::JSONB->'requiredOwners') THEN
            RAISE EXCEPTION 'Reviewed authored base requires complete exact retained synchronized source';
        END IF;
        source_bytes := game_design_reviewed_base_frame('retained-authored-policy-command-asset-base/v2'::TEXT)
            || game_design_reviewed_base_frame(convert_to(base.binding_json, 'UTF8'))
            || game_design_reviewed_base_frame(convert_to(policy_snapshot.snapshot_json, 'UTF8'))
            || game_design_reviewed_base_frame(convert_to(command_snapshot.snapshot_json, 'UTF8'))
            || game_design_reviewed_base_frame(convert_to(asset_snapshot.snapshot_json, 'UTF8'))
            || game_design_reviewed_base_frame(jsonb_array_length(base.binding_json::JSONB->'requiredOwners')::TEXT);
        FOR owner_name IN SELECT jsonb_array_elements_text(base.binding_json::JSONB->'requiredOwners') LOOP
            SELECT * INTO STRICT owner_result FROM game_design_draft_commit_owner_result
                WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id
                    AND request_id = base.request_id AND owner = owner_name;
            SELECT item INTO STRICT outcome FROM jsonb_array_elements(fence.result_vector_json::JSONB) item
                WHERE item->>'owner' = owner_name;
            IF owner_result.status <> 'APPLIED' OR owner_result.result_commit_id IS DISTINCT FROM base.commit_id
                OR owner_result.result_binding_digest IS DISTINCT FROM base.input_digest
                OR outcome IS DISTINCT FROM jsonb_build_object('owner', owner_name, 'status', owner_result.status,
                    'commitId', owner_result.result_commit_id::TEXT, 'bindingDigest', owner_result.result_binding_digest,
                    'resultIdentity', owner_result.result_identity,
                    'resultBytesBase64', replace(encode(owner_result.result_bytes, 'base64'), E'\n', ''),
                    'appliedEpochs', owner_result.applied_units_json::JSONB) THEN
                RAISE EXCEPTION 'Reviewed authored owner vector differs from retained exact outcome';
            END IF;
            source_bytes := source_bytes || game_design_reviewed_base_frame(owner_name)
                || game_design_reviewed_base_frame(owner_result.status::TEXT)
                || game_design_reviewed_base_frame(base.commit_id::TEXT)
                || game_design_reviewed_base_frame(base.input_digest::TEXT)
                || game_design_reviewed_base_frame(owner_result.result_identity::TEXT)
                || game_design_reviewed_base_frame(owner_result.result_bytes)
                || game_design_reviewed_base_frame(jsonb_array_length(outcome->'appliedEpochs')::TEXT);
            FOR epoch IN SELECT jsonb_array_elements(outcome->'appliedEpochs') LOOP
                source_bytes := source_bytes || game_design_reviewed_base_frame(epoch->>'aggregateType')
                    || game_design_reviewed_base_frame(epoch->>'aggregateId')
                    || game_design_reviewed_base_frame(epoch->>'scopeType')
                    || game_design_reviewed_base_frame(epoch->>'scopeId')
                    || game_design_reviewed_base_frame(epoch->>'expectedEpoch')
                    || game_design_reviewed_base_frame(epoch->>'resultingEpoch');
            END LOOP;
        END LOOP;
    END IF;
    expected_bytes := game_design_reviewed_base_frame(NEW.evidence_schema::TEXT)
        || game_design_reviewed_base_frame('game-design-draft-base-reference/v1'::TEXT)
        || game_design_reviewed_base_frame(NEW.base_kind::TEXT)
        || game_design_reviewed_base_frame(NEW.base_reference::TEXT)
        || game_design_reviewed_base_frame(NEW.canonical_tenant_id::TEXT)
        || game_design_reviewed_base_frame(NEW.canonical_version_id::TEXT)
        || game_design_reviewed_base_frame(claim.game_design_version_row_id::TEXT)
        || game_design_reviewed_base_frame(claim.game_design_version_tenant_key::TEXT)
        || game_design_reviewed_base_frame(claim.source_game_row_id::TEXT)
        || game_design_reviewed_base_frame(claim.source_game_tenant_key::TEXT)
        || game_design_reviewed_base_frame(claim.source_provenance_kind::TEXT)
        || game_design_reviewed_base_frame(source_bytes);
    IF NEW.evidence_bytes IS DISTINCT FROM expected_bytes
        OR NEW.evidence_digest IS DISTINCT FROM 'sha256:' || encode(sha256(expected_bytes), 'hex') THEN
        RAISE EXCEPTION 'Reviewed base bytes or digest differ from exact owner provenance';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- [jooq ignore stop]
