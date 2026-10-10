-- Branding source only. These records never attest total asset-family completeness.
CREATE TABLE game_design_branding_source_genesis (
    canonical_tenant_id UUID NOT NULL, canonical_version_id UUID NOT NULL,
    version_id BIGINT NOT NULL UNIQUE, receipt_id UUID NOT NULL UNIQUE,
    creation_transaction_id TEXT NOT NULL, target_json TEXT NOT NULL, roles_json TEXT NOT NULL,
    PRIMARY KEY (canonical_tenant_id, canonical_version_id),
    FOREIGN KEY (version_id, canonical_tenant_id, canonical_version_id)
        REFERENCES version (id, canonical_tenant_id, canonical_version_id),
    FOREIGN KEY (version_id) REFERENCES game_design_realm_policy_version_insert (version_id),
    CHECK (receipt_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (creation_transaction_id ~ '^[1-9][0-9]*$')
);
CREATE TABLE game_design_branding_source_application (
    canonical_tenant_id UUID NOT NULL, canonical_version_id UUID NOT NULL,
    request_id UUID NOT NULL, commit_id UUID NOT NULL, expected_epoch TEXT NOT NULL,
    snapshot_json TEXT NOT NULL, result_bytes BYTEA NOT NULL,
    PRIMARY KEY (canonical_tenant_id, canonical_version_id, commit_id),
    UNIQUE (canonical_tenant_id, canonical_version_id, request_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, request_id, commit_id)
        REFERENCES game_design_draft_commit (canonical_tenant_id, canonical_version_id, request_id, commit_id),
    CHECK (expected_epoch ~ '^(0|[1-9][0-9]*)$'), CHECK (octet_length(result_bytes) > 0)
);
CREATE TABLE game_design_branding_source_revision (
    canonical_tenant_id UUID NOT NULL, canonical_version_id UUID NOT NULL,
    request_id UUID NOT NULL, commit_id UUID NOT NULL, revision_id UUID NOT NULL,
    revision_order INTEGER NOT NULL CHECK (revision_order >= 0), operation_kind VARCHAR(16) NOT NULL,
    usage_key VARCHAR(255) NOT NULL, role VARCHAR(16) NOT NULL CHECK (role IN ('RESOURCE', 'LOGO', 'FAVICON', 'THEME')), asset_id BIGINT, requiredness VARCHAR(16),
    content_type VARCHAR(100), content_digest VARCHAR(71), byte_size BIGINT,
    PRIMARY KEY (canonical_tenant_id, canonical_version_id, revision_id),
    UNIQUE (canonical_tenant_id, canonical_version_id, commit_id, revision_order),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, request_id, commit_id)
        REFERENCES game_design_draft_commit (canonical_tenant_id, canonical_version_id, request_id, commit_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, commit_id)
        REFERENCES game_design_branding_source_application (canonical_tenant_id, canonical_version_id, commit_id)
        DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (asset_id) REFERENCES game_assets (id),
    CHECK (usage_key !~ '^[[:space:]]*$' AND usage_key <> 'manifest.json'),
    CHECK ((operation_kind = 'UPSERT' AND asset_id > 0 AND requiredness = 'REQUIRED'
        AND content_type IS NOT NULL AND content_type !~ '^[[:space:]]*$'
        AND content_digest ~ '^sha256:[0-9a-f]{64}$' AND byte_size >= 0)
        OR (operation_kind = 'DELETE' AND asset_id IS NULL AND requiredness IS NULL
            AND content_type IS NULL AND content_digest IS NULL AND byte_size IS NULL))
);
CREATE TABLE game_design_branding_source_snapshot (
    canonical_tenant_id UUID NOT NULL, canonical_version_id UUID NOT NULL,
    request_id UUID NOT NULL, commit_id UUID NOT NULL, snapshot_json TEXT NOT NULL,
    snapshot_digest VARCHAR(71) NOT NULL CHECK (snapshot_digest ~ '^sha256:[0-9a-f]{64}$'),
    PRIMARY KEY (canonical_tenant_id, canonical_version_id, commit_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, request_id, commit_id)
        REFERENCES game_design_draft_commit_visibility_fence (canonical_tenant_id, canonical_version_id, request_id, commit_id)
);
CREATE TABLE game_design_branding_source_head (
    canonical_tenant_id UUID NOT NULL, canonical_version_id UUID NOT NULL,
    source_epoch TEXT NOT NULL CHECK (source_epoch ~ '^(0|[1-9][0-9]*)$'),
    genesis_receipt_id UUID NOT NULL REFERENCES game_design_branding_source_genesis (receipt_id),
    applied_commit_id UUID, visible_commit_id UUID,
    PRIMARY KEY (canonical_tenant_id, canonical_version_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id)
        REFERENCES game_design_branding_source_genesis (canonical_tenant_id, canonical_version_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, applied_commit_id)
        REFERENCES game_design_branding_source_application (canonical_tenant_id, canonical_version_id, commit_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, visible_commit_id)
        REFERENCES game_design_branding_source_snapshot (canonical_tenant_id, canonical_version_id, commit_id)
);
CREATE TABLE game_design_branding_source_capture (
    canonical_tenant_id UUID NOT NULL, canonical_version_id UUID NOT NULL,
    publish_workflow_id VARCHAR(1024) NOT NULL UNIQUE REFERENCES game_design_publication_operation (publish_workflow_id),
    commit_id UUID NOT NULL, operation_bytes BYTEA NOT NULL, capture_bytes BYTEA NOT NULL,
    capture_digest VARCHAR(71) NOT NULL CHECK (capture_digest ~ '^sha256:[0-9a-f]{64}$'),
    PRIMARY KEY (canonical_tenant_id, canonical_version_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, commit_id)
        REFERENCES game_design_branding_source_snapshot (canonical_tenant_id, canonical_version_id, commit_id)
);

-- [jooq ignore start]
CREATE FUNCTION branding_asset_revision_item(r game_design_branding_source_revision) RETURNS JSONB AS $$
    SELECT jsonb_build_object('family', 'BRANDING', 'role', r.role, 'usageKey', r.usage_key,
        'assetRowId', r.asset_id::TEXT, 'requiredness', r.requiredness,
        'sourceBindingJson', c.binding_json, 'sourceBindingDigest', c.input_digest,
        'revisionOrder', r.revision_order::TEXT, 'revisionId', r.revision_id::TEXT,
        'contentType', r.content_type, 'contentDigest', r.content_digest, 'byteSize', r.byte_size::TEXT)
    FROM game_design_draft_commit c WHERE c.canonical_tenant_id = r.canonical_tenant_id
        AND c.canonical_version_id = r.canonical_version_id AND c.request_id = r.request_id AND c.commit_id = r.commit_id;
$$ LANGUAGE SQL STABLE;

CREATE FUNCTION guard_branding_asset_source() RETURNS TRIGGER AS $$
DECLARE v version%ROWTYPE; g game_design_branding_source_genesis%ROWTYPE;
    h game_design_branding_source_head%ROWTYPE; c game_design_draft_commit%ROWTYPE;
    a game_design_branding_source_application%ROWTYPE; op game_design_publication_operation%ROWTYPE;
    s game_design_branding_source_snapshot%ROWTYPE; selected game_design_authored_draft_publish_selection%ROWTYPE;
    asset game_assets%ROWTYPE; revision JSONB; payload JSONB; prior JSONB; expected_items JSONB;
    snapshot JSONB; expected BYTEA; bytes BYTEA; count_declared BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'branding asset source history is immutable' USING ERRCODE = '23514'; END IF;
    SELECT owner.* INTO STRICT v FROM version owner JOIN game game_source
        ON game_source.id = owner.identity_source_game_row_id
        AND game_source.tenant_id = owner.identity_source_game_tenant_key AND game_source.tenant_id = owner.tenant_id
        AND game_source.canonical_tenant_id = owner.canonical_tenant_id
        AND game_source.tenant_identity_provenance_kind = owner.identity_source_provenance_kind
        AND game_source.tenant_identity_source_game_id = game_source.id
        AND game_source.tenant_identity_source_legacy_tenant_id = game_source.tenant_id
        WHERE owner.canonical_tenant_id = NEW.canonical_tenant_id AND owner.canonical_version_id = NEW.canonical_version_id FOR UPDATE OF owner;
    IF v.version_state <> 'DRAFT' OR v.is_script_only OR v.base_version_id IS NOT NULL OR v.script_patch_version IS NOT NULL THEN
        RAISE EXCEPTION 'branding source requires exact canonical full Draft' USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'game_design_branding_source_genesis' THEN
        IF TG_OP <> 'INSERT' OR v.id <> NEW.version_id OR v.version_state_epoch <> 1
            OR NEW.roles_json::JSONB IS DISTINCT FROM '{"RESOURCE":"EMPTY","LOGO":"EMPTY","FAVICON":"EMPTY","THEME":"EMPTY"}'::JSONB
            OR NEW.creation_transaction_id <> pg_current_xact_id()::TEXT
            OR NOT EXISTS (SELECT 1 FROM game_design_realm_policy_version_insert w WHERE w.version_id = v.id
                AND w.creation_transaction_id = NEW.creation_transaction_id)
            OR NEW.target_json::JSONB IS DISTINCT FROM jsonb_build_object(
                'canonicalTenantId', v.canonical_tenant_id::TEXT, 'canonicalVersionId', v.canonical_version_id::TEXT,
                'gameDesignVersionRowId', v.id, 'gameDesignVersionTenantKey', v.tenant_id,
                'sourceGameRowId', v.identity_source_game_row_id,
                'sourceGameTenantKey', v.identity_source_game_tenant_key, 'sourceProvenanceKind', v.identity_source_provenance_kind)
            OR EXISTS (SELECT 1 FROM version_asset va WHERE va.tenant_id = v.tenant_id AND va.version_id = v.id)
            OR EXISTS (SELECT 1 FROM game_design_draft_commit c0 WHERE c0.canonical_tenant_id = NEW.canonical_tenant_id AND c0.canonical_version_id = NEW.canonical_version_id) THEN
            RAISE EXCEPTION 'branding genesis requires its actual fresh Version insertion' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO STRICT g FROM game_design_branding_source_genesis WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id;
    IF TG_TABLE_NAME = 'game_design_branding_source_head' AND TG_OP = 'INSERT' THEN
        IF NEW.source_epoch <> '0' OR NEW.applied_commit_id IS NOT NULL OR NEW.visible_commit_id IS NOT NULL OR NEW.genesis_receipt_id <> g.receipt_id THEN
            RAISE EXCEPTION 'branding head requires exact fresh baseline' USING ERRCODE = '23514'; END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO STRICT h FROM game_design_branding_source_head WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id FOR UPDATE;
    IF TG_TABLE_NAME <> 'game_design_branding_source_capture' AND (
        EXISTS (SELECT 1 FROM game_design_authored_draft_publish_selection x WHERE x.canonical_tenant_id = NEW.canonical_tenant_id AND x.canonical_version_id = NEW.canonical_version_id)
        OR EXISTS (SELECT 1 FROM version_asset_export_snapshot x WHERE x.tenant_id = v.tenant_id AND x.version_id = v.id)) THEN
        RAISE EXCEPTION 'branding source is frozen by selected publication or export' USING ERRCODE = '23514'; END IF;
    IF TG_TABLE_NAME = 'game_design_branding_source_head' THEN
        IF NEW.canonical_tenant_id IS DISTINCT FROM OLD.canonical_tenant_id OR NEW.canonical_version_id IS DISTINCT FROM OLD.canonical_version_id
            OR NEW.genesis_receipt_id IS DISTINCT FROM OLD.genesis_receipt_id THEN
            RAISE EXCEPTION 'branding head identity immutable' USING ERRCODE = '23514'; END IF;
        IF NEW.source_epoch IS DISTINCT FROM OLD.source_epoch THEN
            SELECT * INTO STRICT a FROM game_design_branding_source_application WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.applied_commit_id;
            IF NEW.source_epoch::NUMERIC <> OLD.source_epoch::NUMERIC + 1 OR a.expected_epoch <> OLD.source_epoch
                OR NEW.visible_commit_id IS DISTINCT FROM OLD.visible_commit_id THEN RAISE EXCEPTION 'branding staged epoch mismatch' USING ERRCODE = '23514'; END IF;
        ELSIF NEW.applied_commit_id IS DISTINCT FROM OLD.applied_commit_id THEN RAISE EXCEPTION 'branding staged pointer mismatch' USING ERRCODE = '23514'; END IF;
        IF NEW.visible_commit_id IS DISTINCT FROM OLD.visible_commit_id THEN
            SELECT * INTO STRICT s FROM game_design_branding_source_snapshot WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.visible_commit_id;
            IF s.snapshot_json::JSONB->>'sourceEpoch' IS DISTINCT FROM NEW.source_epoch
                OR s.snapshot_json::JSONB->>'inheritedCommitId' IS DISTINCT FROM coalesce(OLD.visible_commit_id::TEXT, '') THEN
                RAISE EXCEPTION 'branding visible pointer mismatch' USING ERRCODE = '23514'; END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP <> 'INSERT' THEN RAISE EXCEPTION 'branding source history is immutable' USING ERRCODE = '23514'; END IF;
    IF TG_TABLE_NAME = 'game_design_branding_source_capture' THEN
        SELECT * INTO STRICT op FROM game_design_publication_operation WHERE publish_workflow_id = NEW.publish_workflow_id FOR UPDATE;
        SELECT * INTO STRICT selected FROM game_design_authored_draft_publish_selection WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id;
        SELECT * INTO STRICT s FROM game_design_branding_source_snapshot WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
        bytes := convert_to('game-design-branding-asset-source-capture/v1', 'UTF8'); expected := int4send(octet_length(bytes)) || bytes;
        expected := expected || int4send(octet_length(NEW.operation_bytes)) || NEW.operation_bytes;
        bytes := convert_to(s.snapshot_json, 'UTF8'); expected := expected || int4send(octet_length(bytes)) || bytes;
        IF op.outcome <> 'PENDING' OR op.request_bytes IS DISTINCT FROM NEW.operation_bytes OR op.version_id <> v.id OR op.tenant_id <> v.tenant_id
            OR op.selection_digest <> selected.selection_digest OR selected.selected_commit_id <> NEW.commit_id
            OR selected.version_state_epoch <> v.version_state_epoch OR h.visible_commit_id IS DISTINCT FROM NEW.commit_id
            OR h.source_epoch IS DISTINCT FROM s.snapshot_json::JSONB->>'sourceEpoch'
            OR expected IS DISTINCT FROM NEW.capture_bytes OR NEW.capture_digest IS DISTINCT FROM 'sha256:' || encode(sha256(NEW.capture_bytes), 'hex') THEN
            RAISE EXCEPTION 'branding capture lacks exact selected operation/source' USING ERRCODE = '23514'; END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO STRICT c FROM game_design_draft_commit WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id AND request_id = NEW.request_id AND commit_id = NEW.commit_id;
    IF TG_TABLE_NAME IN ('game_design_branding_source_revision', 'game_design_branding_source_application') THEN
        IF NOT EXISTS (SELECT 1 FROM game_design_draft_commit_application_slot slot WHERE slot.canonical_tenant_id = NEW.canonical_tenant_id AND slot.canonical_version_id = NEW.canonical_version_id AND slot.request_id = NEW.request_id AND slot.commit_id = NEW.commit_id)
            OR NOT EXISTS (SELECT 1 FROM game_design_draft_commit_owner_result r WHERE r.canonical_tenant_id = NEW.canonical_tenant_id AND r.canonical_version_id = NEW.canonical_version_id AND r.request_id = NEW.request_id AND r.owner = 'GAME_DESIGN_CONTROL_PLANE' AND r.status IN ('IN_PROGRESS', 'UNKNOWN')) THEN
            RAISE EXCEPTION 'branding source write requires active coordinator application' USING ERRCODE = '23514'; END IF;
    END IF;
    IF TG_TABLE_NAME = 'game_design_branding_source_revision' THEN
        SELECT r INTO STRICT revision FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') r WHERE r->>'revisionId' = NEW.revision_id::TEXT AND r->>'revisionOrder' = NEW.revision_order::TEXT AND r->>'owner' = 'GAME_DESIGN_CONTROL_PLANE';
        payload := (revision->>'payload')::JSONB;
        IF (SELECT count(*) FROM json_object_keys((revision->>'payload')::JSON)) <>
                (SELECT count(*) FROM jsonb_object_keys(payload))
            OR (revision->>'payload')::JSON->>'schemaVersion' IS DISTINCT FROM '1'
            OR payload->>'revisionKind' IS DISTINCT FROM 'BRANDING_ASSET_REFERENCE' OR payload->'schemaVersion' IS DISTINCT FROM '1'::JSONB
            OR payload->>'family' IS DISTINCT FROM 'BRANDING' OR payload->>'role' IS DISTINCT FROM NEW.role
            OR payload->>'operation' IS DISTINCT FROM NEW.operation_kind OR payload->>'usageKey' IS DISTINCT FROM NEW.usage_key
            OR jsonb_typeof(payload->'usageKey') IS DISTINCT FROM 'string' THEN RAISE EXCEPTION 'branding revision differs from exact payload' USING ERRCODE = '23514'; END IF;
        IF NEW.operation_kind = 'UPSERT' THEN
            SELECT * INTO STRICT asset FROM game_assets WHERE id = NEW.asset_id AND tenant_id = v.tenant_id FOR UPDATE;
            IF (SELECT count(*) FROM jsonb_object_keys(payload)) <> 8 OR jsonb_typeof(payload->'assetRowId') IS DISTINCT FROM 'string'
                OR payload->>'assetRowId' IS DISTINCT FROM NEW.asset_id::TEXT OR payload->>'requiredness' IS DISTINCT FROM 'REQUIRED'
                OR NEW.requiredness IS DISTINCT FROM 'REQUIRED' OR asset.file_name IS DISTINCT FROM NEW.usage_key
                OR asset.content_type IS DISTINCT FROM NEW.content_type OR asset.data IS NULL
                OR NEW.content_digest IS DISTINCT FROM 'sha256:' || encode(sha256(asset.data), 'hex') OR NEW.byte_size IS DISTINCT FROM octet_length(asset.data)::BIGINT THEN
                RAISE EXCEPTION 'branding RESOURCE requires exact required source bytes' USING ERRCODE = '23514'; END IF;
        ELSIF (SELECT count(*) FROM jsonb_object_keys(payload)) <> 6 THEN RAISE EXCEPTION 'branding DELETE fields invalid' USING ERRCODE = '23514'; END IF;
        RETURN NEW;
    END IF;
    snapshot := NEW.snapshot_json::JSONB;
    IF snapshot->>'schema' IS DISTINCT FROM 'game-design-branding-asset-source-snapshot/v1'
        OR snapshot->>'bindingJson' IS DISTINCT FROM c.binding_json OR snapshot->>'bindingDigest' IS DISTINCT FROM c.input_digest
        OR snapshot->>'genesisReceiptId' IS DISTINCT FROM g.receipt_id::TEXT
        OR snapshot->>'inheritedCommitId' IS DISTINCT FROM coalesce(h.visible_commit_id::TEXT, '')
        OR jsonb_typeof(snapshot->'items') IS DISTINCT FROM 'array'
        OR (SELECT count(*) FROM jsonb_object_keys(snapshot)) <> 8 THEN RAISE EXCEPTION 'branding snapshot binding invalid' USING ERRCODE = '23514'; END IF;
    IF snapshot->'roles' IS DISTINCT FROM (
        SELECT jsonb_object_agg(role, CASE WHEN EXISTS (
            SELECT 1 FROM jsonb_array_elements(snapshot->'items') item WHERE item->>'role' = role
        ) THEN 'PRESENT' ELSE 'EMPTY' END)
        FROM unnest(ARRAY['RESOURCE', 'LOGO', 'FAVICON', 'THEME']) role
    ) THEN RAISE EXCEPTION 'branding snapshot requires every explicit role declaration' USING ERRCODE = '23514'; END IF;
    SELECT s0.snapshot_json::JSONB->'items' INTO prior FROM game_design_branding_source_snapshot s0 WHERE s0.canonical_tenant_id = NEW.canonical_tenant_id AND s0.canonical_version_id = NEW.canonical_version_id AND s0.commit_id = h.visible_commit_id;
    prior := coalesce(prior, '[]'::JSONB);
    IF TG_TABLE_NAME = 'game_design_branding_source_application' THEN
        IF NEW.expected_epoch IS DISTINCT FROM h.source_epoch OR snapshot->>'sourceEpoch' IS DISTINCT FROM (h.source_epoch::NUMERIC + 1)::TEXT
            OR NOT (c.binding_json::JSONB->'affectedUnits' @> jsonb_build_array(jsonb_build_object('owner', 'GAME_DESIGN_CONTROL_PLANE',
                'aggregateType', 'BRANDING_ASSET_REFERENCE_SET', 'aggregateId', NEW.canonical_version_id::TEXT,
                'scopeType', 'BRANDING_ASSET_REFERENCE_SET', 'scopeId', 'effective', 'expectedEpoch', NEW.expected_epoch))) THEN
            RAISE EXCEPTION 'branding application lacks exact scope epoch' USING ERRCODE = '23514'; END IF;
        WITH choices AS (
            SELECT item->>'usageKey' AS key, -1 AS ord, FALSE AS deleted, item FROM jsonb_array_elements(prior) item
            UNION ALL SELECT r.usage_key, r.revision_order, r.operation_kind = 'DELETE', branding_asset_revision_item(r)
                FROM game_design_branding_source_revision r WHERE r.canonical_tenant_id = NEW.canonical_tenant_id AND r.canonical_version_id = NEW.canonical_version_id AND r.commit_id = NEW.commit_id
        ), final AS (SELECT DISTINCT ON (key) * FROM choices ORDER BY key, ord DESC)
        SELECT coalesce(jsonb_agg(item ORDER BY convert_to(key, 'UTF8')) FILTER (WHERE NOT deleted), '[]'::JSONB) INTO expected_items FROM final;
        IF expected_items IS DISTINCT FROM snapshot->'items'
            OR (SELECT count(DISTINCT item->>'assetRowId') FROM jsonb_array_elements(expected_items) item) <> jsonb_array_length(expected_items) THEN
            RAISE EXCEPTION 'branding application must replay exact source without aliases' USING ERRCODE = '23514'; END IF;
    ELSE
        SELECT * INTO a FROM game_design_branding_source_application WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
        SELECT count(*) INTO count_declared FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') r WHERE r->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' AND (r->>'payload')::JSONB->>'revisionKind' = 'BRANDING_ASSET_REFERENCE';
        IF (count_declared > 0) IS DISTINCT FROM (a.commit_id IS NOT NULL)
            OR snapshot->>'sourceEpoch' IS DISTINCT FROM h.source_epoch
            OR (a.commit_id IS NOT NULL AND a.snapshot_json IS DISTINCT FROM NEW.snapshot_json)
            OR (a.commit_id IS NULL AND snapshot->'items' IS DISTINCT FROM prior)
            OR NEW.snapshot_digest IS DISTINCT FROM 'sha256:' || encode(sha256(convert_to(NEW.snapshot_json, 'UTF8')), 'hex')
            OR c.workflow_state <> 'SYNCHRONIZED'
            OR NOT EXISTS (SELECT 1 FROM game_design_draft_commit_visibility f WHERE f.canonical_tenant_id = NEW.canonical_tenant_id AND f.canonical_version_id = NEW.canonical_version_id AND f.request_id = NEW.request_id AND f.commit_id = NEW.commit_id AND f.input_digest = c.input_digest) THEN
            RAISE EXCEPTION 'branding snapshot requires exact synchronized source inheritance' USING ERRCODE = '23514'; END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION verify_branding_asset_application_commit() RETURNS TRIGGER AS $$
DECLARE c game_design_draft_commit%ROWTYPE; r game_design_draft_commit_owner_result%ROWTYPE;
    h game_design_branding_source_head%ROWTYPE; expected BYTEA; bytes BYTEA; components BYTEA;
    gameplay_bytes BYTEA; command_bytes BYTEA; asset_bytes BYTEA; policy_bytes BYTEA; declared BIGINT; retained BIGINT;
    epochs JSONB := '[]'::JSONB; unit JSONB; kind TEXT; expected_scope TEXT; scope_count BIGINT := 0;
BEGIN
    SELECT * INTO STRICT c FROM game_design_draft_commit WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND request_id = NEW.request_id AND commit_id = NEW.commit_id;
    SELECT * INTO STRICT r FROM game_design_draft_commit_owner_result WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND request_id = NEW.request_id AND owner = 'GAME_DESIGN_CONTROL_PLANE';
    SELECT * INTO STRICT h FROM game_design_branding_source_head WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id;
    SELECT count(*) INTO declared FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') x
        WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' AND (x->>'payload')::JSONB->>'revisionKind' = 'BRANDING_ASSET_REFERENCE';
    SELECT count(*) INTO retained FROM game_design_branding_source_revision WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
    bytes := convert_to('game-design-branding-asset-source-application/v1', 'UTF8'); expected := int4send(octet_length(bytes)) || bytes;
    bytes := convert_to(NEW.expected_epoch, 'UTF8'); expected := expected || int4send(octet_length(bytes)) || bytes;
    bytes := convert_to(NEW.snapshot_json, 'UTF8'); expected := expected || int4send(octet_length(bytes)) || bytes;
    IF declared = 0 OR declared <> retained OR NEW.result_bytes IS DISTINCT FROM expected OR r.status <> 'APPLIED'
        OR r.result_commit_id <> NEW.commit_id OR r.result_binding_digest <> c.input_digest
        OR h.applied_commit_id IS DISTINCT FROM NEW.commit_id OR h.source_epoch <> (NEW.expected_epoch::NUMERIC + 1)::TEXT THEN
        RAISE EXCEPTION 'branding application requires exact atomic owner result' USING ERRCODE = '23514'; END IF;
    SELECT result_bytes INTO command_bytes FROM game_design_command_source_application WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
    SELECT result_bytes INTO asset_bytes FROM game_design_asset_source_application WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
    SELECT result_bytes INTO policy_bytes FROM game_design_realm_policy_application WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
    SELECT result_bytes INTO gameplay_bytes FROM game_design_gameplay_rule_application WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
    FOR kind IN SELECT DISTINCT (x->>'payload')::JSONB->>'revisionKind' FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') x
        WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' LOOP
        expected_scope := CASE kind WHEN 'COMMAND_DEFINITION' THEN 'COMMAND_DEFINITION_SET'
            WHEN 'ASSET_REFERENCE' THEN 'ASSET_REFERENCE_SET' WHEN 'REALM_ENTRY_POLICY' THEN 'REALM_ENTRY_POLICY_SET'
            WHEN 'BRANDING_ASSET_REFERENCE' THEN 'BRANDING_ASSET_REFERENCE_SET' WHEN 'GAMEPLAY_RULE' THEN 'GAMEPLAY_RULE_SET' ELSE NULL END;
        IF expected_scope IS NULL OR (kind = 'GAMEPLAY_RULE' AND gameplay_bytes IS NULL) OR (kind = 'COMMAND_DEFINITION' AND command_bytes IS NULL)
            OR (kind = 'ASSET_REFERENCE' AND asset_bytes IS NULL) OR (kind = 'REALM_ENTRY_POLICY' AND policy_bytes IS NULL)
            OR (SELECT count(*) FROM jsonb_array_elements(c.binding_json::JSONB->'affectedUnits') x
                WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' AND x->>'aggregateType' = expected_scope) <> 1 THEN
            RAISE EXCEPTION 'branding mixed application requires all declared sibling sources' USING ERRCODE = '23514'; END IF;
        scope_count := scope_count + 1;
    END LOOP;
    FOR unit IN SELECT x FROM jsonb_array_elements(c.binding_json::JSONB->'affectedUnits') x WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE'
        ORDER BY x->>'aggregateType', x->>'aggregateId', x->>'scopeType', x->>'scopeId' LOOP
        IF unit->>'aggregateType' NOT IN ('COMMAND_DEFINITION_SET', 'ASSET_REFERENCE_SET', 'REALM_ENTRY_POLICY_SET', 'GAMEPLAY_RULE_SET', 'BRANDING_ASSET_REFERENCE_SET')
            OR unit->>'aggregateType' IS DISTINCT FROM unit->>'scopeType' OR unit->>'scopeId' IS DISTINCT FROM 'effective'
            OR unit->>'aggregateId' IS DISTINCT FROM NEW.canonical_version_id::TEXT THEN
            RAISE EXCEPTION 'branding combined scope mismatch' USING ERRCODE = '23514'; END IF;
        epochs := epochs || jsonb_build_array((unit - 'owner') || jsonb_build_object('resultingEpoch', ((unit->>'expectedEpoch')::NUMERIC + 1)::TEXT));
    END LOOP;
    IF jsonb_array_length(epochs) <> scope_count THEN RAISE EXCEPTION 'branding scope vector incomplete' USING ERRCODE = '23514'; END IF;
    bytes := convert_to('game-design-control-plane-sibling-components/v1', 'UTF8'); components := int4send(octet_length(bytes)) || bytes;
    IF command_bytes IS NOT NULL THEN bytes := convert_to('COMMAND', 'UTF8');
        components := components || int4send(octet_length(bytes)) || bytes || int4send(octet_length(command_bytes)) || command_bytes; END IF;
    IF asset_bytes IS NOT NULL THEN bytes := convert_to('ASSET', 'UTF8');
        components := components || int4send(octet_length(bytes)) || bytes || int4send(octet_length(asset_bytes)) || asset_bytes; END IF;
    IF gameplay_bytes IS NOT NULL THEN bytes := convert_to('GAMEPLAY', 'UTF8');
        components := components || int4send(octet_length(bytes)) || bytes || int4send(octet_length(gameplay_bytes)) || gameplay_bytes; END IF;
    bytes := convert_to('BRANDING', 'UTF8');
    components := components || int4send(octet_length(bytes)) || bytes || int4send(octet_length(NEW.result_bytes)) || NEW.result_bytes;
    IF policy_bytes IS NULL THEN bytes := convert_to('game-design-control-plane-ordinary-source-application/v1', 'UTF8');
    ELSE bytes := convert_to('game-design-control-plane-source-application/v1', 'UTF8'); END IF;
    expected := int4send(octet_length(bytes)) || bytes;
    bytes := convert_to(c.binding_json, 'UTF8'); expected := expected || int4send(octet_length(bytes)) || bytes;
    IF policy_bytes IS NOT NULL THEN expected := expected || int4send(octet_length(policy_bytes)) || policy_bytes; END IF;
    expected := expected || int4send(octet_length(components)) || components;
    IF r.result_bytes IS DISTINCT FROM expected OR r.applied_units_json::JSONB IS DISTINCT FROM epochs THEN
        RAISE EXCEPTION 'branding owner result must contain exact complete tagged source bytes' USING ERRCODE = '23514'; END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION verify_gameplay_rule_application_commit() RETURNS TRIGGER AS $$
DECLARE c game_design_draft_commit%ROWTYPE; r game_design_draft_commit_owner_result%ROWTYPE;
    h game_design_gameplay_rule_head%ROWTYPE; expected BYTEA; bytes BYTEA; components BYTEA;
    branding_bytes BYTEA; command_bytes BYTEA; asset_bytes BYTEA; policy_bytes BYTEA; declared BIGINT; retained BIGINT;
    epochs JSONB := '[]'::JSONB; unit JSONB; kind TEXT; expected_scope TEXT; scope_count BIGINT := 0;
BEGIN
    SELECT * INTO STRICT c FROM game_design_draft_commit WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND request_id = NEW.request_id AND commit_id = NEW.commit_id;
    SELECT * INTO STRICT r FROM game_design_draft_commit_owner_result WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND request_id = NEW.request_id AND owner = 'GAME_DESIGN_CONTROL_PLANE';
    SELECT * INTO STRICT h FROM game_design_gameplay_rule_head WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id;
    SELECT count(*) INTO declared FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') x
        WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' AND (x->>'payload')::JSONB->>'revisionKind' = 'GAMEPLAY_RULE';
    SELECT count(*) INTO retained FROM game_design_gameplay_rule_revision WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
    bytes := convert_to('game-design-gameplay-rule-source-application/v1', 'UTF8'); expected := int4send(octet_length(bytes)) || bytes;
    bytes := convert_to(NEW.expected_epoch, 'UTF8'); expected := expected || int4send(octet_length(bytes)) || bytes;
    bytes := convert_to(NEW.snapshot_json, 'UTF8'); expected := expected || int4send(octet_length(bytes)) || bytes;
    IF declared = 0 OR declared <> retained OR NEW.result_bytes IS DISTINCT FROM expected OR r.status <> 'APPLIED'
        OR r.result_commit_id <> NEW.commit_id OR r.result_binding_digest <> c.input_digest
        OR h.applied_commit_id IS DISTINCT FROM NEW.commit_id OR h.source_epoch <> (NEW.expected_epoch::NUMERIC + 1)::TEXT THEN
        RAISE EXCEPTION 'gameplay application requires exact atomic owner result' USING ERRCODE = '23514'; END IF;
    SELECT result_bytes INTO command_bytes FROM game_design_command_source_application WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
    SELECT result_bytes INTO asset_bytes FROM game_design_asset_source_application WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
    SELECT result_bytes INTO policy_bytes FROM game_design_realm_policy_application WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
    SELECT result_bytes INTO branding_bytes FROM game_design_branding_source_application WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
    FOR kind IN SELECT DISTINCT (x->>'payload')::JSONB->>'revisionKind' FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') x
        WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' LOOP
        expected_scope := CASE kind WHEN 'COMMAND_DEFINITION' THEN 'COMMAND_DEFINITION_SET'
            WHEN 'ASSET_REFERENCE' THEN 'ASSET_REFERENCE_SET' WHEN 'REALM_ENTRY_POLICY' THEN 'REALM_ENTRY_POLICY_SET'
            WHEN 'BRANDING_ASSET_REFERENCE' THEN 'BRANDING_ASSET_REFERENCE_SET' WHEN 'GAMEPLAY_RULE' THEN 'GAMEPLAY_RULE_SET' ELSE NULL END;
        IF expected_scope IS NULL OR (kind = 'BRANDING_ASSET_REFERENCE' AND branding_bytes IS NULL) OR (kind = 'COMMAND_DEFINITION' AND command_bytes IS NULL)
            OR (kind = 'ASSET_REFERENCE' AND asset_bytes IS NULL) OR (kind = 'REALM_ENTRY_POLICY' AND policy_bytes IS NULL)
            OR (SELECT count(*) FROM jsonb_array_elements(c.binding_json::JSONB->'affectedUnits') x
                WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' AND x->>'aggregateType' = expected_scope) <> 1 THEN
            RAISE EXCEPTION 'gameplay mixed application requires all declared sibling sources' USING ERRCODE = '23514'; END IF;
        scope_count := scope_count + 1;
    END LOOP;
    FOR unit IN SELECT x FROM jsonb_array_elements(c.binding_json::JSONB->'affectedUnits') x WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE'
        ORDER BY x->>'aggregateType', x->>'aggregateId', x->>'scopeType', x->>'scopeId' LOOP
        IF unit->>'aggregateType' NOT IN ('COMMAND_DEFINITION_SET', 'ASSET_REFERENCE_SET', 'REALM_ENTRY_POLICY_SET', 'GAMEPLAY_RULE_SET', 'BRANDING_ASSET_REFERENCE_SET')
            OR unit->>'aggregateType' IS DISTINCT FROM unit->>'scopeType' OR unit->>'scopeId' IS DISTINCT FROM 'effective'
            OR unit->>'aggregateId' IS DISTINCT FROM NEW.canonical_version_id::TEXT THEN
            RAISE EXCEPTION 'gameplay combined scope mismatch' USING ERRCODE = '23514'; END IF;
        epochs := epochs || jsonb_build_array((unit - 'owner') || jsonb_build_object('resultingEpoch', ((unit->>'expectedEpoch')::NUMERIC + 1)::TEXT));
    END LOOP;
    IF jsonb_array_length(epochs) <> scope_count THEN RAISE EXCEPTION 'gameplay scope vector incomplete' USING ERRCODE = '23514'; END IF;
    bytes := convert_to('game-design-control-plane-sibling-components/v1', 'UTF8'); components := int4send(octet_length(bytes)) || bytes;
    IF command_bytes IS NOT NULL THEN bytes := convert_to('COMMAND', 'UTF8');
        components := components || int4send(octet_length(bytes)) || bytes || int4send(octet_length(command_bytes)) || command_bytes; END IF;
    IF asset_bytes IS NOT NULL THEN bytes := convert_to('ASSET', 'UTF8');
        components := components || int4send(octet_length(bytes)) || bytes || int4send(octet_length(asset_bytes)) || asset_bytes; END IF;
    bytes := convert_to('GAMEPLAY', 'UTF8');
    components := components || int4send(octet_length(bytes)) || bytes || int4send(octet_length(NEW.result_bytes)) || NEW.result_bytes;
    IF branding_bytes IS NOT NULL THEN bytes := convert_to('BRANDING', 'UTF8');
        components := components || int4send(octet_length(bytes)) || bytes || int4send(octet_length(branding_bytes)) || branding_bytes; END IF;
    IF policy_bytes IS NULL THEN bytes := convert_to('game-design-control-plane-ordinary-source-application/v1', 'UTF8');
    ELSE bytes := convert_to('game-design-control-plane-source-application/v1', 'UTF8'); END IF;
    expected := int4send(octet_length(bytes)) || bytes;
    bytes := convert_to(c.binding_json, 'UTF8'); expected := expected || int4send(octet_length(bytes)) || bytes;
    IF policy_bytes IS NOT NULL THEN expected := expected || int4send(octet_length(policy_bytes)) || policy_bytes; END IF;
    expected := expected || int4send(octet_length(components)) || components;
    IF r.result_bytes IS DISTINCT FROM expected OR r.applied_units_json::JSONB IS DISTINCT FROM epochs THEN
        RAISE EXCEPTION 'gameplay owner result must contain exact complete tagged source bytes' USING ERRCODE = '23514'; END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION verify_ordinary_asset_application_commit() RETURNS TRIGGER AS $$
DECLARE c game_design_draft_commit%ROWTYPE; r game_design_draft_commit_owner_result%ROWTYPE;
    h game_design_asset_source_head%ROWTYPE; expected BYTEA; bytes BYTEA; declared BIGINT; retained BIGINT;
    components BYTEA; command_bytes BYTEA; policy_bytes BYTEA; gameplay_bytes BYTEA; branding_bytes BYTEA; epochs JSONB; unit JSONB;
BEGIN
    SELECT * INTO STRICT c FROM game_design_draft_commit WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id AND request_id = NEW.request_id AND commit_id = NEW.commit_id;
    SELECT * INTO STRICT r FROM game_design_draft_commit_owner_result WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id AND request_id = NEW.request_id AND owner = 'GAME_DESIGN_CONTROL_PLANE';
    SELECT * INTO STRICT h FROM game_design_asset_source_head WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id;
    SELECT count(*) INTO declared FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') x WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' AND (x->>'payload')::JSONB->>'revisionKind' = 'ASSET_REFERENCE';
    SELECT count(*) INTO retained FROM game_design_asset_source_revision x WHERE x.canonical_tenant_id = NEW.canonical_tenant_id AND x.canonical_version_id = NEW.canonical_version_id AND x.commit_id = NEW.commit_id;
    bytes := convert_to('game-design-ordinary-asset-source-application/v1', 'UTF8'); expected := int4send(octet_length(bytes)) || bytes;
    bytes := convert_to(NEW.expected_epoch, 'UTF8'); expected := expected || int4send(octet_length(bytes)) || bytes;
    bytes := convert_to(NEW.snapshot_json, 'UTF8'); expected := expected || int4send(octet_length(bytes)) || bytes;
    IF declared = 0 OR declared <> retained OR r.status <> 'APPLIED' OR r.result_commit_id <> NEW.commit_id OR r.result_binding_digest <> c.input_digest
        OR h.source_epoch <> (NEW.expected_epoch::NUMERIC + 1)::TEXT OR h.applied_commit_id IS DISTINCT FROM NEW.commit_id
        OR expected IS DISTINCT FROM NEW.result_bytes OR NOT (r.applied_units_json::JSONB @> jsonb_build_array(jsonb_build_object(
            'aggregateType', 'ASSET_REFERENCE_SET', 'aggregateId', NEW.canonical_version_id::TEXT, 'scopeType', 'ASSET_REFERENCE_SET',
            'scopeId', 'effective', 'expectedEpoch', NEW.expected_epoch, 'resultingEpoch', h.source_epoch))) THEN
        RAISE EXCEPTION 'ordinary application requires atomic combined owner outcome' USING ERRCODE = '23514'; END IF;
    bytes := convert_to('game-design-control-plane-sibling-components/v1', 'UTF8');
    components := int4send(octet_length(bytes)) || bytes;
    epochs := '[]'::JSONB;
    SELECT result_bytes INTO command_bytes FROM game_design_command_source_application
        WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
    IF command_bytes IS NOT NULL THEN
        bytes := convert_to('COMMAND', 'UTF8');
        components := components || int4send(octet_length(bytes)) || bytes || int4send(octet_length(command_bytes)) || command_bytes;
    END IF;
    bytes := convert_to('ASSET', 'UTF8');
    components := components || int4send(octet_length(bytes)) || bytes || int4send(octet_length(NEW.result_bytes)) || NEW.result_bytes;
    SELECT result_bytes INTO gameplay_bytes FROM game_design_gameplay_rule_application
        WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
    IF gameplay_bytes IS NOT NULL THEN
        bytes := convert_to('GAMEPLAY', 'UTF8');
        components := components || int4send(octet_length(bytes)) || bytes || int4send(octet_length(gameplay_bytes)) || gameplay_bytes;
    END IF;
    SELECT result_bytes INTO branding_bytes FROM game_design_branding_source_application
        WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
    IF branding_bytes IS NOT NULL THEN bytes := convert_to('BRANDING', 'UTF8');
        components := components || int4send(octet_length(bytes)) || bytes || int4send(octet_length(branding_bytes)) || branding_bytes; END IF;
    SELECT result_bytes INTO policy_bytes FROM game_design_realm_policy_application
        WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') x
        WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE'
            AND coalesce((x->>'payload')::JSONB->>'revisionKind', '') NOT IN ('COMMAND_DEFINITION', 'REALM_ENTRY_POLICY', 'ASSET_REFERENCE', 'GAMEPLAY_RULE', 'BRANDING_ASSET_REFERENCE'))
        OR (branding_bytes IS NOT NULL) IS DISTINCT FROM EXISTS (
            SELECT 1 FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') x
            WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' AND (x->>'payload')::JSONB->>'revisionKind' = 'BRANDING_ASSET_REFERENCE')
        OR (command_bytes IS NOT NULL) IS DISTINCT FROM EXISTS (
            SELECT 1 FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') x
            WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' AND (x->>'payload')::JSONB->>'revisionKind' = 'COMMAND_DEFINITION')
        OR (policy_bytes IS NOT NULL) IS DISTINCT FROM EXISTS (
            SELECT 1 FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') x
            WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' AND (x->>'payload')::JSONB->>'revisionKind' = 'REALM_ENTRY_POLICY') THEN
        RAISE EXCEPTION 'ordinary mixed application requires every declared supported sibling source' USING ERRCODE = '23514'; END IF;
    IF (gameplay_bytes IS NOT NULL) IS DISTINCT FROM EXISTS (
        SELECT 1 FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') x
        WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' AND (x->>'payload')::JSONB->>'revisionKind' = 'GAMEPLAY_RULE') THEN
        RAISE EXCEPTION 'ordinary mixed application lacks exact declared gameplay source' USING ERRCODE = '23514'; END IF;
    -- The vector has one entry per exact declared local scope, ordered as the Java composition.
    FOR unit IN SELECT x FROM jsonb_array_elements(c.binding_json::JSONB->'affectedUnits') x
        WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE'
        ORDER BY x->>'aggregateType', x->>'aggregateId', x->>'scopeType', x->>'scopeId'
    LOOP
        IF unit->>'aggregateType' NOT IN ('COMMAND_DEFINITION_SET', 'ASSET_REFERENCE_SET', 'REALM_ENTRY_POLICY_SET', 'GAMEPLAY_RULE_SET', 'BRANDING_ASSET_REFERENCE_SET')
            OR unit->>'aggregateType' IS DISTINCT FROM unit->>'scopeType'
            OR unit->>'scopeId' IS DISTINCT FROM 'effective'
            OR unit->>'aggregateId' IS DISTINCT FROM NEW.canonical_version_id::TEXT THEN
            RAISE EXCEPTION 'ordinary combined scope unknown' USING ERRCODE = '23514'; END IF;
        epochs := epochs || jsonb_build_array((unit - 'owner') || jsonb_build_object('resultingEpoch', ((unit->>'expectedEpoch')::NUMERIC + 1)::TEXT));
    END LOOP;
    IF policy_bytes IS NULL THEN bytes := convert_to('game-design-control-plane-ordinary-source-application/v1', 'UTF8');
    ELSE bytes := convert_to('game-design-control-plane-source-application/v1', 'UTF8'); END IF;
    expected := int4send(octet_length(bytes)) || bytes;
    bytes := convert_to(c.binding_json, 'UTF8');
    expected := expected || int4send(octet_length(bytes)) || bytes;
    IF policy_bytes IS NOT NULL THEN expected := expected || int4send(octet_length(policy_bytes)) || policy_bytes; END IF;
    expected := expected || int4send(octet_length(components)) || components;
    IF r.result_bytes IS DISTINCT FROM expected OR r.applied_units_json::JSONB IS DISTINCT FROM epochs
        OR jsonb_array_length(epochs) <> (1 + CASE WHEN command_bytes IS NULL THEN 0 ELSE 1 END + CASE WHEN policy_bytes IS NULL THEN 0 ELSE 1 END + CASE WHEN gameplay_bytes IS NULL THEN 0 ELSE 1 END + CASE WHEN branding_bytes IS NULL THEN 0 ELSE 1 END) THEN
        RAISE EXCEPTION 'ordinary combined outcome must contain exact tagged components and complete epochs' USING ERRCODE = '23514'; END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER branding_asset_genesis_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_branding_source_genesis FOR EACH ROW EXECUTE FUNCTION guard_branding_asset_source();
CREATE TRIGGER branding_asset_head_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_branding_source_head FOR EACH ROW EXECUTE FUNCTION guard_branding_asset_source();
CREATE TRIGGER branding_asset_revision_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_branding_source_revision FOR EACH ROW EXECUTE FUNCTION guard_branding_asset_source();
CREATE TRIGGER branding_asset_application_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_branding_source_application FOR EACH ROW EXECUTE FUNCTION guard_branding_asset_source();
CREATE TRIGGER branding_asset_snapshot_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_branding_source_snapshot FOR EACH ROW EXECUTE FUNCTION guard_branding_asset_source();
CREATE TRIGGER branding_asset_capture_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_branding_source_capture FOR EACH ROW EXECUTE FUNCTION guard_branding_asset_source();
CREATE CONSTRAINT TRIGGER branding_asset_application_commit_guard AFTER INSERT ON game_design_branding_source_application DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_branding_asset_application_commit();
CREATE FUNCTION protect_branding_asset_bytes() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM game_design_branding_source_revision r WHERE r.asset_id = OLD.id) AND
        (TG_OP = 'DELETE' OR NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'branding asset source bytes are immutable from source history' USING ERRCODE = '23514'; END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER branding_asset_byte_history_guard BEFORE UPDATE OR DELETE ON game_assets FOR EACH ROW EXECUTE FUNCTION protect_branding_asset_bytes();

CREATE FUNCTION deny_branding_asset_truncate() RETURNS TRIGGER AS $$
BEGIN RAISE EXCEPTION 'branding source history cannot be truncated' USING ERRCODE = '23514'; END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER branding_asset_genesis_no_truncate BEFORE TRUNCATE ON game_design_branding_source_genesis EXECUTE FUNCTION deny_branding_asset_truncate();
CREATE TRIGGER branding_asset_head_no_truncate BEFORE TRUNCATE ON game_design_branding_source_head EXECUTE FUNCTION deny_branding_asset_truncate();
CREATE TRIGGER branding_asset_revision_no_truncate BEFORE TRUNCATE ON game_design_branding_source_revision EXECUTE FUNCTION deny_branding_asset_truncate();
CREATE TRIGGER branding_asset_application_no_truncate BEFORE TRUNCATE ON game_design_branding_source_application EXECUTE FUNCTION deny_branding_asset_truncate();
CREATE TRIGGER branding_asset_snapshot_no_truncate BEFORE TRUNCATE ON game_design_branding_source_snapshot EXECUTE FUNCTION deny_branding_asset_truncate();
CREATE TRIGGER branding_asset_capture_no_truncate BEFORE TRUNCATE ON game_design_branding_source_capture EXECUTE FUNCTION deny_branding_asset_truncate();
-- [jooq ignore stop]
