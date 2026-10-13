-- Template/config authored source only. These records never attest total asset-family completeness.
CREATE TABLE game_design_template_config_source_genesis (
    canonical_tenant_id UUID NOT NULL, canonical_version_id UUID NOT NULL,
    version_id BIGINT NOT NULL UNIQUE, receipt_id UUID NOT NULL UNIQUE,
    creation_transaction_id TEXT NOT NULL, target_json TEXT NOT NULL,
    PRIMARY KEY (canonical_tenant_id, canonical_version_id),
    FOREIGN KEY (version_id, canonical_tenant_id, canonical_version_id)
        REFERENCES version (id, canonical_tenant_id, canonical_version_id),
    FOREIGN KEY (version_id) REFERENCES game_design_realm_policy_version_insert (version_id),
    CHECK (receipt_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (creation_transaction_id ~ '^[1-9][0-9]*$')
);
CREATE TABLE game_design_template_config_source_application (
    canonical_tenant_id UUID NOT NULL, canonical_version_id UUID NOT NULL,
    request_id UUID NOT NULL, commit_id UUID NOT NULL, expected_epoch TEXT NOT NULL,
    snapshot_json TEXT NOT NULL, result_bytes BYTEA NOT NULL,
    PRIMARY KEY (canonical_tenant_id, canonical_version_id, commit_id),
    UNIQUE (canonical_tenant_id, canonical_version_id, request_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, request_id, commit_id)
        REFERENCES game_design_draft_commit (canonical_tenant_id, canonical_version_id, request_id, commit_id),
    CHECK (expected_epoch ~ '^(0|[1-9][0-9]*)$'), CHECK (octet_length(result_bytes) > 0)
);
CREATE TABLE game_design_template_config_source_revision (
    canonical_tenant_id UUID NOT NULL, canonical_version_id UUID NOT NULL,
    request_id UUID NOT NULL, commit_id UUID NOT NULL, revision_id UUID NOT NULL,
    revision_order INTEGER NOT NULL CHECK (revision_order >= 0), operation_kind VARCHAR(16) NOT NULL,
    template_id BIGINT NOT NULL REFERENCES game_templates (id), payload_json TEXT NOT NULL,
    PRIMARY KEY (canonical_tenant_id, canonical_version_id, revision_id),
    UNIQUE (canonical_tenant_id, canonical_version_id, commit_id, revision_order),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, request_id, commit_id)
        REFERENCES game_design_draft_commit (canonical_tenant_id, canonical_version_id, request_id, commit_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, commit_id)
        REFERENCES game_design_template_config_source_application (canonical_tenant_id, canonical_version_id, commit_id)
        DEFERRABLE INITIALLY DEFERRED,
    CHECK (operation_kind IN ('CREATE', 'UPSERT', 'DELETE'))
);
CREATE TABLE game_design_template_config_source_snapshot (
    canonical_tenant_id UUID NOT NULL, canonical_version_id UUID NOT NULL,
    request_id UUID NOT NULL, commit_id UUID NOT NULL, snapshot_json TEXT NOT NULL,
    snapshot_digest VARCHAR(71) NOT NULL CHECK (snapshot_digest ~ '^sha256:[0-9a-f]{64}$'),
    PRIMARY KEY (canonical_tenant_id, canonical_version_id, commit_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, request_id, commit_id)
        REFERENCES game_design_draft_commit_visibility_fence (canonical_tenant_id, canonical_version_id, request_id, commit_id)
);
CREATE TABLE game_design_template_config_source_head (
    canonical_tenant_id UUID NOT NULL, canonical_version_id UUID NOT NULL,
    source_epoch TEXT NOT NULL CHECK (source_epoch ~ '^(0|[1-9][0-9]*)$'),
    genesis_receipt_id UUID NOT NULL REFERENCES game_design_template_config_source_genesis (receipt_id),
    applied_commit_id UUID, visible_commit_id UUID,
    PRIMARY KEY (canonical_tenant_id, canonical_version_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id)
        REFERENCES game_design_template_config_source_genesis (canonical_tenant_id, canonical_version_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, applied_commit_id)
        REFERENCES game_design_template_config_source_application (canonical_tenant_id, canonical_version_id, commit_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, visible_commit_id)
        REFERENCES game_design_template_config_source_snapshot (canonical_tenant_id, canonical_version_id, commit_id)
);
CREATE TABLE game_design_template_config_source_capture (
    canonical_tenant_id UUID NOT NULL, canonical_version_id UUID NOT NULL,
    publish_workflow_id VARCHAR(1024) NOT NULL UNIQUE REFERENCES game_design_publication_operation (publish_workflow_id),
    commit_id UUID NOT NULL, operation_bytes BYTEA NOT NULL, capture_bytes BYTEA NOT NULL,
    capture_digest VARCHAR(71) NOT NULL CHECK (capture_digest ~ '^sha256:[0-9a-f]{64}$'),
    PRIMARY KEY (canonical_tenant_id, canonical_version_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, commit_id)
        REFERENCES game_design_template_config_source_snapshot (canonical_tenant_id, canonical_version_id, commit_id)
);

CREATE TABLE game_design_template_config_source_qualification (
    template_id BIGINT PRIMARY KEY REFERENCES game_templates (id),
    canonical_tenant_id UUID NOT NULL, canonical_version_id UUID NOT NULL,
    request_id UUID NOT NULL, commit_id UUID NOT NULL, revision_id UUID NOT NULL, revision_order INTEGER NOT NULL,
    UNIQUE (canonical_tenant_id, canonical_version_id, revision_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, request_id, commit_id)
        REFERENCES game_design_draft_commit (canonical_tenant_id, canonical_version_id, request_id, commit_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, revision_id)
        REFERENCES game_design_template_config_source_revision (canonical_tenant_id, canonical_version_id, revision_id)
        DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE game_design_template_config_source_ref (
    canonical_tenant_id UUID NOT NULL, canonical_version_id UUID NOT NULL, revision_id UUID NOT NULL,
    ref_kind VARCHAR(64) NOT NULL, ref_key VARCHAR(128) NOT NULL, referenced_revision_id UUID,
    PRIMARY KEY (canonical_tenant_id, canonical_version_id, revision_id, ref_kind, ref_key),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, revision_id)
        REFERENCES game_design_template_config_source_revision (canonical_tenant_id, canonical_version_id, revision_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, referenced_revision_id)
        REFERENCES game_design_gameplay_rule_revision (canonical_tenant_id, canonical_version_id, revision_id),
    CHECK ((ref_kind = 'BASE_VERSION' AND referenced_revision_id IS NULL AND ref_key = canonical_version_id::TEXT)
        OR (ref_kind <> 'BASE_VERSION' AND referenced_revision_id IS NOT NULL))
);
CREATE TABLE game_design_template_config_source_row_insert (
    template_id BIGINT PRIMARY KEY, creation_transaction_id TEXT NOT NULL
);
-- [jooq ignore start]
CREATE FUNCTION template_config_unique_json(value JSON) RETURNS BOOLEAN AS $$
DECLARE field RECORD; element JSON;
BEGIN
    IF json_typeof(value) = 'object' THEN
        IF (SELECT count(*) FROM json_each(value)) <> (SELECT count(DISTINCT key) FROM json_each(value)) THEN RETURN FALSE; END IF;
        FOR field IN SELECT * FROM json_each(value) LOOP
            IF NOT template_config_unique_json(field.value) THEN RETURN FALSE; END IF;
        END LOOP;
    ELSIF json_typeof(value) = 'array' THEN
        FOR element IN SELECT * FROM json_array_elements(value) LOOP
            IF NOT template_config_unique_json(element) THEN RETURN FALSE; END IF;
        END LOOP;
    END IF;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

CREATE FUNCTION template_config_supported(config_json TEXT, base_id UUID) RETURNS BOOLEAN AS $$
DECLARE cfg JSONB := config_json::JSONB; input JSONB;
BEGIN
    IF NOT template_config_unique_json(config_json::JSON)
        OR (SELECT count(*) FROM jsonb_object_keys(cfg)) <> 7
        OR config_json::JSON->>'schemaVersion' IS DISTINCT FROM '1'
        OR cfg->'schemaVersion' IS DISTINCT FROM '1'::JSONB OR cfg->>'baseVersionId' IS DISTINCT FROM base_id::TEXT
        OR (SELECT count(*) FROM jsonb_object_keys(cfg->'world')) <> 2
        OR cfg->'world'->'regions' IS DISTINCT FROM '[]'::JSONB OR cfg->'world'->'rooms' IS DISTINCT FROM '[]'::JSONB
        OR (SELECT count(*) FROM jsonb_object_keys(cfg->'entity')) <> 2
        OR cfg->'entity'->'items' IS DISTINCT FROM '[]'::JSONB OR cfg->'entity'->'npcs' IS DISTINCT FROM '[]'::JSONB
        OR (SELECT count(*) FROM jsonb_object_keys(cfg->'automation')) <> 2
        OR cfg->'automation'->'scripts' IS DISTINCT FROM '[]'::JSONB
        OR cfg->'automation'->'scriptPatch' IS DISTINCT FROM '{"presence":"ABSENT"}'::JSONB
        OR cfg->'supportedSettings' IS DISTINCT FROM '[]'::JSONB
        OR (SELECT count(*) FROM jsonb_object_keys(cfg->'gameLogic')) <> 1
        OR jsonb_typeof(cfg->'gameLogic'->'inputs') IS DISTINCT FROM 'array' THEN RETURN FALSE; END IF;
    FOR input IN SELECT * FROM jsonb_array_elements(cfg->'gameLogic'->'inputs') LOOP
        IF (SELECT count(*) FROM jsonb_object_keys(input)) <> 3
            OR NOT (input->>'family' = ANY(gameplay_rule_families()))
            OR jsonb_typeof(input->'family') IS DISTINCT FROM 'string'
            OR jsonb_typeof(input->'key') IS DISTINCT FROM 'string'
            OR (input->>'key') !~ '^[A-Za-z][A-Za-z0-9_.:-]{0,127}$'
            OR jsonb_typeof(input->'revisionId') IS DISTINCT FROM 'string'
            OR input->>'revisionId' IS DISTINCT FROM ((input->>'revisionId')::UUID)::TEXT
            OR (input->>'revisionId')::UUID = '00000000-0000-0000-0000-000000000000'::UUID THEN RETURN FALSE; END IF;
    END LOOP;
    RETURN (SELECT count(*) FROM jsonb_array_elements(cfg->'gameLogic'->'inputs')) =
        (SELECT count(DISTINCT (x->>'family', x->>'key')) FROM jsonb_array_elements(cfg->'gameLogic'->'inputs') x);
END;
$$ LANGUAGE plpgsql IMMUTABLE;

CREATE FUNCTION template_config_revision_item(r game_design_template_config_source_revision) RETURNS JSONB AS $$
    SELECT jsonb_build_object('templateId', r.template_id::TEXT, 'configJson', r.payload_json::JSONB->>'configJson',
        'sourceBindingJson', c.binding_json, 'sourceBindingDigest', c.input_digest,
        'revisionOrder', r.revision_order::TEXT, 'revisionId', r.revision_id::TEXT,
        'createdName', CASE WHEN r.operation_kind = 'CREATE' THEN r.payload_json::JSONB->>'templateName' ELSE '' END)
    FROM game_design_draft_commit c WHERE c.canonical_tenant_id = r.canonical_tenant_id
        AND c.canonical_version_id = r.canonical_version_id AND c.request_id = r.request_id AND c.commit_id = r.commit_id;
$$ LANGUAGE SQL STABLE;

CREATE FUNCTION template_config_selected_inputs(snapshot JSONB, p_tenant UUID, p_version UUID, p_commit UUID) RETURNS BOOLEAN AS $$
DECLARE gameplay game_design_gameplay_rule_snapshot%ROWTYPE; entry JSONB; input JSONB;
BEGIN
    SELECT * INTO STRICT gameplay FROM game_design_gameplay_rule_snapshot WHERE canonical_tenant_id = p_tenant
        AND canonical_version_id = p_version AND commit_id = p_commit;
    IF gameplay.snapshot_json::JSONB->>'bindingJson' IS DISTINCT FROM snapshot->>'bindingJson' THEN RETURN FALSE; END IF;
    FOR entry IN SELECT * FROM jsonb_array_elements(snapshot->'entries') LOOP
        FOR input IN SELECT * FROM jsonb_array_elements((entry->>'configJson')::JSONB->'gameLogic'->'inputs') LOOP
            IF NOT EXISTS (SELECT 1 FROM jsonb_array_elements(gameplay.snapshot_json::JSONB->'entries') e
                WHERE e->>'family' = input->>'family' AND e->>'revisionId' = input->>'revisionId'
                    AND gameplay_rule_definition_key(e->>'family', (e->>'definitionJson')::JSONB) = input->>'key') THEN RETURN FALSE; END IF;
        END LOOP;
    END LOOP;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql STABLE;
CREATE FUNCTION template_config_current_inventory(p_tenant UUID, p_version UUID, p_private_version BIGINT,
    p_legacy_tenant TEXT, snapshot JSONB) RETURNS BOOLEAN AS $$
    SELECT NOT EXISTS (
        SELECT 1 FROM game_templates t
        LEFT JOIN game_design_template_config_source_qualification q ON q.template_id = t.id
            AND q.canonical_tenant_id = p_tenant AND q.canonical_version_id = p_version
        LEFT JOIN LATERAL (
            SELECT r.operation_kind FROM game_design_template_config_source_revision r
            JOIN game_design_template_config_source_application a ON a.canonical_tenant_id = r.canonical_tenant_id
                AND a.canonical_version_id = r.canonical_version_id AND a.commit_id = r.commit_id
            WHERE r.canonical_tenant_id = p_tenant AND r.canonical_version_id = p_version AND r.template_id = t.id
            ORDER BY a.expected_epoch::NUMERIC DESC, r.revision_order DESC LIMIT 1
        ) latest ON TRUE
        WHERE t.tenant_id = p_legacy_tenant AND t.default_version_id = p_private_version
            AND (q.template_id IS NULL OR (latest.operation_kind IS DISTINCT FROM 'DELETE'
                AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(snapshot->'entries') e WHERE e->>'templateId' = t.id::TEXT)))
    );
$$ LANGUAGE SQL STABLE;

CREATE FUNCTION guard_template_config_source() RETURNS TRIGGER AS $$
DECLARE v version%ROWTYPE; g game_design_template_config_source_genesis%ROWTYPE;
    h game_design_template_config_source_head%ROWTYPE; c game_design_draft_commit%ROWTYPE;
    a game_design_template_config_source_application%ROWTYPE; op game_design_publication_operation%ROWTYPE;
    s game_design_template_config_source_snapshot%ROWTYPE; selected game_design_authored_draft_publish_selection%ROWTYPE;
    template game_templates%ROWTYPE; revision JSONB; payload JSONB; prior JSONB; expected_items JSONB;
    snapshot JSONB; expected BYTEA; bytes BYTEA; count_declared BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'template config source history is immutable' USING ERRCODE = '23514'; END IF;
    SELECT owner.* INTO STRICT v FROM version owner JOIN game game_source
        ON game_source.id = owner.identity_source_game_row_id
        AND game_source.tenant_id = owner.identity_source_game_tenant_key AND game_source.tenant_id = owner.tenant_id
        AND game_source.canonical_tenant_id = owner.canonical_tenant_id
        AND game_source.tenant_identity_provenance_kind = owner.identity_source_provenance_kind
        AND game_source.tenant_identity_source_game_id = game_source.id
        AND game_source.tenant_identity_source_legacy_tenant_id = game_source.tenant_id
        WHERE owner.canonical_tenant_id = NEW.canonical_tenant_id AND owner.canonical_version_id = NEW.canonical_version_id FOR UPDATE OF owner;
    IF v.version_state <> 'DRAFT' OR v.is_script_only OR v.base_version_id IS NOT NULL OR v.script_patch_version IS NOT NULL THEN
        RAISE EXCEPTION 'template config source requires exact canonical full Draft' USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'game_design_template_config_source_genesis' THEN
        IF TG_OP <> 'INSERT' OR v.id <> NEW.version_id OR v.version_state_epoch <> 1
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
            RAISE EXCEPTION 'template config genesis requires its actual fresh Version insertion' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO STRICT g FROM game_design_template_config_source_genesis WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id;
    IF TG_TABLE_NAME = 'game_design_template_config_source_head' AND TG_OP = 'INSERT' THEN
        IF NEW.source_epoch <> '0' OR NEW.applied_commit_id IS NOT NULL OR NEW.visible_commit_id IS NOT NULL OR NEW.genesis_receipt_id <> g.receipt_id THEN
            RAISE EXCEPTION 'template config head requires exact fresh baseline' USING ERRCODE = '23514'; END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO STRICT h FROM game_design_template_config_source_head WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id FOR UPDATE;
    IF TG_TABLE_NAME <> 'game_design_template_config_source_capture' AND (
        EXISTS (SELECT 1 FROM game_design_authored_draft_publish_selection x WHERE x.canonical_tenant_id = NEW.canonical_tenant_id AND x.canonical_version_id = NEW.canonical_version_id)
        OR EXISTS (SELECT 1 FROM version_asset_export_snapshot x WHERE x.tenant_id = v.tenant_id AND x.version_id = v.id)) THEN
        RAISE EXCEPTION 'template config source is frozen by selected publication or export' USING ERRCODE = '23514'; END IF;
    IF TG_TABLE_NAME = 'game_design_template_config_source_head' THEN
        IF NEW.canonical_tenant_id IS DISTINCT FROM OLD.canonical_tenant_id OR NEW.canonical_version_id IS DISTINCT FROM OLD.canonical_version_id
            OR NEW.genesis_receipt_id IS DISTINCT FROM OLD.genesis_receipt_id THEN
            RAISE EXCEPTION 'template config head identity immutable' USING ERRCODE = '23514'; END IF;
        IF NEW.source_epoch IS DISTINCT FROM OLD.source_epoch THEN
            SELECT * INTO STRICT a FROM game_design_template_config_source_application WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.applied_commit_id;
            IF NEW.source_epoch::NUMERIC <> OLD.source_epoch::NUMERIC + 1 OR a.expected_epoch <> OLD.source_epoch
                OR NEW.visible_commit_id IS DISTINCT FROM OLD.visible_commit_id THEN RAISE EXCEPTION 'template config staged epoch mismatch' USING ERRCODE = '23514'; END IF;
        ELSIF NEW.applied_commit_id IS DISTINCT FROM OLD.applied_commit_id THEN RAISE EXCEPTION 'template config staged pointer mismatch' USING ERRCODE = '23514'; END IF;
        IF NEW.visible_commit_id IS DISTINCT FROM OLD.visible_commit_id THEN
            SELECT * INTO STRICT s FROM game_design_template_config_source_snapshot WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.visible_commit_id;
            IF s.snapshot_json::JSONB->>'sourceEpoch' IS DISTINCT FROM NEW.source_epoch
                OR s.snapshot_json::JSONB->>'inheritedCommitId' IS DISTINCT FROM coalesce(OLD.visible_commit_id::TEXT, '') THEN
                RAISE EXCEPTION 'template config visible pointer mismatch' USING ERRCODE = '23514'; END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP <> 'INSERT' THEN RAISE EXCEPTION 'template config source history is immutable' USING ERRCODE = '23514'; END IF;
    IF TG_TABLE_NAME = 'game_design_template_config_source_capture' THEN
        SELECT * INTO STRICT op FROM game_design_publication_operation WHERE publish_workflow_id = NEW.publish_workflow_id FOR UPDATE;
        SELECT * INTO STRICT selected FROM game_design_authored_draft_publish_selection WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id;
        SELECT * INTO STRICT s FROM game_design_template_config_source_snapshot WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
        bytes := convert_to('game-design-template-config-source-capture/v1', 'UTF8'); expected := int4send(octet_length(bytes)) || bytes;
        expected := expected || int4send(octet_length(NEW.operation_bytes)) || NEW.operation_bytes;
        bytes := convert_to(s.snapshot_json, 'UTF8'); expected := expected || int4send(octet_length(bytes)) || bytes;
        IF op.outcome <> 'PENDING' OR op.request_bytes IS DISTINCT FROM NEW.operation_bytes OR op.version_id <> v.id OR op.tenant_id <> v.tenant_id
            OR op.selection_digest <> selected.selection_digest OR selected.selected_commit_id <> NEW.commit_id
            OR selected.version_state_epoch <> v.version_state_epoch OR h.visible_commit_id IS DISTINCT FROM NEW.commit_id
            OR h.source_epoch IS DISTINCT FROM s.snapshot_json::JSONB->>'sourceEpoch'
            OR NOT template_config_selected_inputs(s.snapshot_json::JSONB, NEW.canonical_tenant_id, NEW.canonical_version_id, NEW.commit_id)
            OR NOT template_config_current_inventory(NEW.canonical_tenant_id, NEW.canonical_version_id, v.id, v.tenant_id, s.snapshot_json::JSONB)
            OR EXISTS (SELECT 1 FROM jsonb_array_elements(s.snapshot_json::JSONB->'entries') e
                LEFT JOIN game_templates t ON t.id = (e->>'templateId')::BIGINT AND t.tenant_id = v.tenant_id
                WHERE t.id IS NULL OR t.config IS DISTINCT FROM (e->>'configJson')::JSONB
                    OR t.default_version_id IS DISTINCT FROM v.id OR t.default_script_patch_version IS NOT NULL
                    OR t.default_runtime_flags_json::JSONB IS DISTINCT FROM '{}'::JSONB)
            OR expected IS DISTINCT FROM NEW.capture_bytes OR NEW.capture_digest IS DISTINCT FROM 'sha256:' || encode(sha256(NEW.capture_bytes), 'hex') THEN
            RAISE EXCEPTION 'template config capture lacks exact selected operation/source' USING ERRCODE = '23514'; END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO STRICT c FROM game_design_draft_commit WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id AND request_id = NEW.request_id AND commit_id = NEW.commit_id;
    IF TG_TABLE_NAME IN ('game_design_template_config_source_qualification', 'game_design_template_config_source_revision', 'game_design_template_config_source_application') THEN
        IF NOT EXISTS (SELECT 1 FROM game_design_draft_commit_application_slot slot WHERE slot.canonical_tenant_id = NEW.canonical_tenant_id AND slot.canonical_version_id = NEW.canonical_version_id AND slot.request_id = NEW.request_id AND slot.commit_id = NEW.commit_id)
            OR NOT EXISTS (SELECT 1 FROM game_design_draft_commit_owner_result r WHERE r.canonical_tenant_id = NEW.canonical_tenant_id AND r.canonical_version_id = NEW.canonical_version_id AND r.request_id = NEW.request_id AND r.owner = 'GAME_DESIGN_CONTROL_PLANE' AND r.status IN ('IN_PROGRESS', 'UNKNOWN')) THEN
            RAISE EXCEPTION 'template config source write requires active coordinator application' USING ERRCODE = '23514'; END IF;
    END IF;
    IF TG_TABLE_NAME IN ('game_design_template_config_source_qualification', 'game_design_template_config_source_revision') THEN
        SELECT r INTO STRICT revision FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') r
            WHERE r->>'revisionId' = NEW.revision_id::TEXT AND r->>'revisionOrder' = NEW.revision_order::TEXT
                AND r->>'owner' = 'GAME_DESIGN_CONTROL_PLANE';
        payload := (revision->>'payload')::JSONB;
        SELECT * INTO STRICT template FROM game_templates WHERE id = NEW.template_id AND tenant_id = v.tenant_id FOR UPDATE;
        IF NOT template_config_unique_json((revision->>'payload')::JSON)
            OR (revision->>'payload')::JSON->>'schemaVersion' IS DISTINCT FROM '1'
            OR payload->'schemaVersion' IS DISTINCT FROM '1'::JSONB OR payload->>'revisionKind' IS DISTINCT FROM 'TEMPLATE_CONFIG' THEN
            RAISE EXCEPTION 'template revision requires closed exact owner payload' USING ERRCODE = '23514'; END IF;
        IF TG_TABLE_NAME = 'game_design_template_config_source_qualification' THEN
            IF payload->>'operation' IS DISTINCT FROM 'CREATE' OR template.name IS DISTINCT FROM payload->>'templateName'
                OR template.config IS DISTINCT FROM (payload->>'configJson')::JSONB OR template.default_version_id IS DISTINCT FROM v.id
                OR template.template_reference_phase IS DISTINCT FROM 'LEGACY'
                OR template.default_script_patch_version IS NOT NULL OR template.default_runtime_flags_json::JSONB IS DISTINCT FROM '{}'::JSONB
                OR NOT EXISTS (SELECT 1 FROM game_design_template_config_source_row_insert w
                    WHERE w.template_id = NEW.template_id AND w.creation_transaction_id = pg_current_xact_id()::TEXT) THEN
                RAISE EXCEPTION 'template qualification requires actual new authorized row' USING ERRCODE = '23514'; END IF;
            RETURN NEW;
        END IF;
        IF NEW.payload_json::JSONB IS DISTINCT FROM payload OR NOT template_config_unique_json(NEW.payload_json::JSON)
            OR payload->>'operation' IS DISTINCT FROM NEW.operation_kind
            OR NOT EXISTS (SELECT 1 FROM game_design_template_config_source_qualification q WHERE q.template_id = NEW.template_id
                AND q.canonical_tenant_id = NEW.canonical_tenant_id AND q.canonical_version_id = NEW.canonical_version_id)
            OR (NEW.operation_kind = 'CREATE' AND NOT EXISTS (SELECT 1 FROM game_design_template_config_source_qualification q
                WHERE q.template_id = NEW.template_id AND q.revision_id = NEW.revision_id AND q.request_id = NEW.request_id
                    AND q.commit_id = NEW.commit_id AND q.revision_order = NEW.revision_order))
            OR (NEW.operation_kind <> 'CREATE' AND payload->>'templateId' IS DISTINCT FROM NEW.template_id::TEXT) THEN
            RAISE EXCEPTION 'template revision or canonical association differs' USING ERRCODE = '23514'; END IF;
        IF NEW.operation_kind = 'DELETE' THEN
            IF (SELECT count(*) FROM jsonb_object_keys(payload)) <> 4 THEN
                RAISE EXCEPTION 'template DELETE fields invalid' USING ERRCODE = '23514'; END IF;
            IF NOT EXISTS (SELECT 1 FROM game_design_template_config_source_snapshot s0,
                LATERAL jsonb_array_elements(s0.snapshot_json::JSONB->'entries') e
                WHERE s0.canonical_tenant_id = NEW.canonical_tenant_id AND s0.canonical_version_id = NEW.canonical_version_id
                    AND s0.commit_id = h.visible_commit_id AND e->>'templateId' = NEW.template_id::TEXT)
                AND NOT EXISTS (SELECT 1 FROM game_design_template_config_source_revision r0
                    WHERE r0.canonical_tenant_id = NEW.canonical_tenant_id AND r0.canonical_version_id = NEW.canonical_version_id
                        AND r0.commit_id = NEW.commit_id AND r0.template_id = NEW.template_id
                        AND r0.revision_order < NEW.revision_order AND r0.operation_kind IN ('CREATE', 'UPSERT')) THEN
                RAISE EXCEPTION 'template DELETE requires actual existing authored entry' USING ERRCODE = '23514'; END IF;
        ELSIF (SELECT count(*) FROM jsonb_object_keys(payload)) <> 5
            OR jsonb_typeof(payload->'configJson') IS DISTINCT FROM 'string'
            OR NOT template_config_supported(payload->>'configJson', NEW.canonical_version_id)
            OR template.config IS DISTINCT FROM (payload->>'configJson')::JSONB THEN
            RAISE EXCEPTION 'template config owner inputs unavailable or closed config invalid' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    snapshot := NEW.snapshot_json::JSONB;
    IF snapshot->>'schema' IS DISTINCT FROM 'game-design-template-config-source-snapshot/v1'
        OR snapshot->>'bindingJson' IS DISTINCT FROM c.binding_json OR snapshot->>'bindingDigest' IS DISTINCT FROM c.input_digest
        OR snapshot->>'genesisReceiptId' IS DISTINCT FROM g.receipt_id::TEXT
        OR snapshot->>'inheritedCommitId' IS DISTINCT FROM coalesce(h.visible_commit_id::TEXT, '')
        OR jsonb_typeof(snapshot->'entries') IS DISTINCT FROM 'array'
        OR (SELECT count(*) FROM jsonb_object_keys(snapshot)) <> 7 THEN RAISE EXCEPTION 'template config snapshot binding invalid' USING ERRCODE = '23514'; END IF;
    SELECT s0.snapshot_json::JSONB->'entries' INTO prior FROM game_design_template_config_source_snapshot s0 WHERE s0.canonical_tenant_id = NEW.canonical_tenant_id AND s0.canonical_version_id = NEW.canonical_version_id AND s0.commit_id = h.visible_commit_id;
    prior := coalesce(prior, '[]'::JSONB);
    IF TG_TABLE_NAME = 'game_design_template_config_source_application' THEN
        IF NEW.expected_epoch IS DISTINCT FROM h.source_epoch OR snapshot->>'sourceEpoch' IS DISTINCT FROM (h.source_epoch::NUMERIC + 1)::TEXT
            OR NOT (c.binding_json::JSONB->'affectedUnits' @> jsonb_build_array(jsonb_build_object('owner', 'GAME_DESIGN_CONTROL_PLANE',
                'aggregateType', 'TEMPLATE_CONFIG_SET', 'aggregateId', NEW.canonical_version_id::TEXT,
                'scopeType', 'TEMPLATE_CONFIG_SET', 'scopeId', 'effective', 'expectedEpoch', NEW.expected_epoch))) THEN
            RAISE EXCEPTION 'template config application lacks exact scope epoch' USING ERRCODE = '23514'; END IF;
        WITH choices AS (
            SELECT (item->>'templateId')::BIGINT AS key, -1 AS ord, FALSE AS deleted, item FROM jsonb_array_elements(prior) item
            UNION ALL SELECT r.template_id, r.revision_order, r.operation_kind = 'DELETE', template_config_revision_item(r)
                FROM game_design_template_config_source_revision r WHERE r.canonical_tenant_id = NEW.canonical_tenant_id AND r.canonical_version_id = NEW.canonical_version_id AND r.commit_id = NEW.commit_id
        ), final AS (SELECT DISTINCT ON (key) * FROM choices ORDER BY key, ord DESC)
        SELECT coalesce(jsonb_agg(item ORDER BY key) FILTER (WHERE NOT deleted), '[]'::JSONB) INTO expected_items FROM final;
        IF expected_items IS DISTINCT FROM snapshot->'entries' THEN
            RAISE EXCEPTION 'template config application must replay exact source without aliases' USING ERRCODE = '23514'; END IF;
    ELSE
        SELECT * INTO a FROM game_design_template_config_source_application WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
        SELECT count(*) INTO count_declared FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') r WHERE r->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' AND (r->>'payload')::JSONB->>'revisionKind' = 'TEMPLATE_CONFIG';
        IF (count_declared > 0) IS DISTINCT FROM (a.commit_id IS NOT NULL)
            OR snapshot->>'sourceEpoch' IS DISTINCT FROM h.source_epoch
            OR (a.commit_id IS NOT NULL AND a.snapshot_json IS DISTINCT FROM NEW.snapshot_json)
            OR (a.commit_id IS NULL AND snapshot->'entries' IS DISTINCT FROM prior)
            OR NEW.snapshot_digest IS DISTINCT FROM 'sha256:' || encode(sha256(convert_to(NEW.snapshot_json, 'UTF8')), 'hex')
            OR NOT template_config_selected_inputs(snapshot, NEW.canonical_tenant_id, NEW.canonical_version_id, NEW.commit_id)
            OR NOT template_config_current_inventory(NEW.canonical_tenant_id, NEW.canonical_version_id, v.id, v.tenant_id, snapshot)
            OR c.workflow_state <> 'SYNCHRONIZED'
            OR NOT EXISTS (SELECT 1 FROM game_design_draft_commit_visibility f WHERE f.canonical_tenant_id = NEW.canonical_tenant_id AND f.canonical_version_id = NEW.canonical_version_id AND f.request_id = NEW.request_id AND f.commit_id = NEW.commit_id AND f.input_digest = c.input_digest) THEN
            RAISE EXCEPTION 'template config snapshot requires exact synchronized source inheritance' USING ERRCODE = '23514'; END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION template_config_row_insert_witness() RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO game_design_template_config_source_row_insert VALUES (NEW.id, pg_current_xact_id()::TEXT);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER template_config_row_insert_witness AFTER INSERT ON game_templates
    FOR EACH ROW EXECUTE FUNCTION template_config_row_insert_witness();

CREATE FUNCTION guard_template_config_insert_witness() RETURNS TRIGGER AS $$
BEGIN
    IF pg_trigger_depth() <> 2 OR NEW.creation_transaction_id IS DISTINCT FROM pg_current_xact_id()::TEXT
        OR NOT EXISTS (SELECT 1 FROM game_templates t WHERE t.id = NEW.template_id
            AND t.xmin::TEXT = (pg_current_xact_id()::TEXT::XID)::TEXT) THEN
        RAISE EXCEPTION 'template insert witness requires actual row insert trigger' USING ERRCODE = '23514'; END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER template_config_insert_witness_guard BEFORE INSERT ON game_design_template_config_source_row_insert
    FOR EACH ROW EXECUTE FUNCTION guard_template_config_insert_witness();

CREATE FUNCTION verify_template_config_explicit_target_row() RETURNS TRIGGER AS $$
DECLARE template game_templates%ROWTYPE; target version%ROWTYPE;
BEGIN
    SELECT * INTO template FROM game_templates WHERE id = NEW.id;
    IF NOT FOUND OR template.default_version_id IS NULL THEN RETURN NULL; END IF;
    SELECT v.* INTO target FROM version v JOIN game_design_template_config_source_genesis g
        ON g.version_id = v.id AND g.canonical_tenant_id = v.canonical_tenant_id AND g.canonical_version_id = v.canonical_version_id
        WHERE v.id = template.default_version_id AND v.tenant_id = template.tenant_id FOR UPDATE OF v;
    IF NOT FOUND THEN RETURN NULL; END IF;
    IF NOT EXISTS (SELECT 1 FROM game_design_template_config_source_qualification q
        JOIN game_design_template_config_source_revision r ON r.canonical_tenant_id = q.canonical_tenant_id
            AND r.canonical_version_id = q.canonical_version_id AND r.revision_id = q.revision_id
            AND r.template_id = q.template_id AND r.request_id = q.request_id AND r.commit_id = q.commit_id
            AND r.revision_order = q.revision_order AND r.operation_kind = 'CREATE'
        WHERE q.template_id = template.id AND q.canonical_tenant_id = target.canonical_tenant_id
            AND q.canonical_version_id = target.canonical_version_id) THEN
        RAISE EXCEPTION 'explicit template target requires actual authorized CREATE qualification' USING ERRCODE = '23514'; END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER template_config_explicit_target_row_guard AFTER INSERT OR UPDATE ON game_templates
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_template_config_explicit_target_row();

CREATE FUNCTION guard_template_config_live_row() RETURNS TRIGGER AS $$
DECLARE q game_design_template_config_source_qualification%ROWTYPE; c game_design_draft_commit%ROWTYPE;
    target version%ROWTYPE; payload JSONB;
BEGIN
    SELECT * INTO q FROM game_design_template_config_source_qualification WHERE template_id = OLD.id;
    IF q.template_id IS NULL THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
        IF (NEW.tenant_id, NEW.default_version_id) IS DISTINCT FROM (OLD.tenant_id, OLD.default_version_id) THEN
            SELECT v.* INTO target FROM version v JOIN game_design_template_config_source_genesis g
                ON g.version_id = v.id AND g.canonical_tenant_id = v.canonical_tenant_id AND g.canonical_version_id = v.canonical_version_id
                WHERE v.id = NEW.default_version_id AND v.tenant_id = NEW.tenant_id FOR UPDATE OF v;
            IF FOUND THEN RAISE EXCEPTION 'unqualified template cannot be reassigned into managed source target' USING ERRCODE = '23514'; END IF;
        END IF;
        RETURN NEW;
    END IF;
    PERFORM 1 FROM version WHERE canonical_tenant_id = q.canonical_tenant_id AND canonical_version_id = q.canonical_version_id FOR UPDATE;
    IF TG_OP = 'DELETE' OR NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.name IS DISTINCT FROM OLD.name OR NEW.default_version_id IS DISTINCT FROM OLD.default_version_id
        OR NEW.default_script_patch_version IS DISTINCT FROM OLD.default_script_patch_version
        OR NEW.default_runtime_flags_json IS DISTINCT FROM OLD.default_runtime_flags_json
        OR NEW.template_reference_phase IS DISTINCT FROM OLD.template_reference_phase THEN
        RAISE EXCEPTION 'qualified template identity and launch fields immutable' USING ERRCODE = '23514'; END IF;
    IF NEW.config IS NOT DISTINCT FROM OLD.config THEN RETURN NEW; END IF;
    SELECT owner.* INTO STRICT c FROM game_design_draft_commit owner JOIN game_design_draft_commit_application_slot slot
        ON slot.canonical_tenant_id = owner.canonical_tenant_id AND slot.canonical_version_id = owner.canonical_version_id
            AND slot.request_id = owner.request_id AND slot.commit_id = owner.commit_id
        WHERE owner.canonical_tenant_id = q.canonical_tenant_id AND owner.canonical_version_id = q.canonical_version_id;
    IF EXISTS (SELECT 1 FROM game_design_authored_draft_publish_selection p WHERE p.canonical_tenant_id = q.canonical_tenant_id
        AND p.canonical_version_id = q.canonical_version_id)
        OR NOT EXISTS (SELECT 1 FROM game_design_draft_commit_owner_result r WHERE r.canonical_tenant_id = q.canonical_tenant_id
            AND r.canonical_version_id = q.canonical_version_id AND r.request_id = c.request_id
            AND r.owner = 'GAME_DESIGN_CONTROL_PLANE' AND r.status IN ('IN_PROGRESS', 'UNKNOWN')) THEN
        RAISE EXCEPTION 'qualified template update requires unfrozen active coordinator' USING ERRCODE = '23514'; END IF;
    IF NOT EXISTS (SELECT 1 FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') r
        WHERE r->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' AND (r->>'payload')::JSONB->>'revisionKind' = 'TEMPLATE_CONFIG'
            AND (r->>'payload')::JSONB->>'operation' = 'UPSERT'
            AND (r->>'payload')::JSONB->>'templateId' = OLD.id::TEXT
            AND ((r->>'payload')::JSONB->>'configJson')::JSONB = NEW.config) THEN
        RAISE EXCEPTION 'qualified template update differs from bound revision' USING ERRCODE = '23514'; END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER template_config_live_row_guard BEFORE UPDATE OR DELETE ON game_templates
    FOR EACH ROW EXECUTE FUNCTION guard_template_config_live_row();

CREATE FUNCTION verify_template_config_references() RETURNS TRIGGER AS $$
DECLARE r game_design_template_config_source_revision%ROWTYPE; cfg JSONB; expected JSONB; actual JSONB;
    rules JSONB; input JSONB; visible UUID;
BEGIN
    SELECT * INTO STRICT r FROM game_design_template_config_source_revision WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND revision_id = NEW.revision_id;
    IF r.operation_kind = 'DELETE' THEN
        IF EXISTS (SELECT 1 FROM game_design_template_config_source_ref x WHERE x.canonical_tenant_id = r.canonical_tenant_id
            AND x.canonical_version_id = r.canonical_version_id AND x.revision_id = r.revision_id) THEN
            RAISE EXCEPTION 'deleted template has no references' USING ERRCODE = '23514'; END IF;
        RETURN NULL;
    END IF;
    cfg := (r.payload_json::JSONB->>'configJson')::JSONB;
    SELECT jsonb_agg(value ORDER BY value->>'refKind', value->>'refKey') INTO expected FROM (
        SELECT jsonb_build_object('refKind', 'BASE_VERSION', 'refKey', r.canonical_version_id::TEXT, 'revisionId', NULL) AS value
        UNION ALL SELECT jsonb_build_object('refKind', x->>'family', 'refKey', x->>'key', 'revisionId', x->>'revisionId')
            FROM jsonb_array_elements(cfg->'gameLogic'->'inputs') x
    ) ref_values;
    SELECT jsonb_agg(jsonb_build_object('refKind', x.ref_kind, 'refKey', x.ref_key, 'revisionId', x.referenced_revision_id::TEXT)
        ORDER BY x.ref_kind, x.ref_key) INTO actual FROM game_design_template_config_source_ref x
        WHERE x.canonical_tenant_id = r.canonical_tenant_id AND x.canonical_version_id = r.canonical_version_id AND x.revision_id = r.revision_id;
    IF expected IS DISTINCT FROM actual THEN RAISE EXCEPTION 'template normalized references differ from original config' USING ERRCODE = '23514'; END IF;
    SELECT snapshot_json::JSONB->'entries' INTO rules FROM game_design_gameplay_rule_application
        WHERE canonical_tenant_id = r.canonical_tenant_id AND canonical_version_id = r.canonical_version_id AND commit_id = r.commit_id;
    IF rules IS NULL THEN
        SELECT visible_commit_id INTO visible FROM game_design_gameplay_rule_head
            WHERE canonical_tenant_id = r.canonical_tenant_id AND canonical_version_id = r.canonical_version_id;
        SELECT snapshot_json::JSONB->'entries' INTO rules FROM game_design_gameplay_rule_snapshot
            WHERE canonical_tenant_id = r.canonical_tenant_id AND canonical_version_id = r.canonical_version_id AND commit_id = visible;
    END IF;
    FOR input IN SELECT * FROM jsonb_array_elements(cfg->'gameLogic'->'inputs') LOOP
        IF NOT EXISTS (SELECT 1 FROM jsonb_array_elements(rules) e WHERE e->>'family' = input->>'family'
            AND e->>'revisionId' = input->>'revisionId'
            AND gameplay_rule_definition_key(e->>'family', (e->>'definitionJson')::JSONB) = input->>'key') THEN
            RAISE EXCEPTION 'template gameplay input not in actual effective source' USING ERRCODE = '23514'; END IF;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER template_config_refs_revision_guard AFTER INSERT ON game_design_template_config_source_revision
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_template_config_references();
CREATE CONSTRAINT TRIGGER template_config_refs_projection_guard AFTER INSERT ON game_design_template_config_source_ref
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_template_config_references();

CREATE FUNCTION deny_template_config_history_change() RETURNS TRIGGER AS $$
BEGIN RAISE EXCEPTION 'template source history immutable' USING ERRCODE = '23514'; END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER template_config_ref_immutable BEFORE UPDATE OR DELETE ON game_design_template_config_source_ref
    FOR EACH ROW EXECUTE FUNCTION deny_template_config_history_change();
CREATE TRIGGER template_config_insert_witness_immutable BEFORE UPDATE OR DELETE ON game_design_template_config_source_row_insert
    FOR EACH ROW EXECUTE FUNCTION deny_template_config_history_change();
CREATE FUNCTION verify_template_config_application_commit() RETURNS TRIGGER AS $$
DECLARE branding_bytes BYTEA; c game_design_draft_commit%ROWTYPE; r game_design_draft_commit_owner_result%ROWTYPE;
    h game_design_template_config_source_head%ROWTYPE; expected BYTEA; bytes BYTEA; components BYTEA;
    gameplay_bytes BYTEA; command_bytes BYTEA; asset_bytes BYTEA; policy_bytes BYTEA; declared BIGINT; retained BIGINT;
    epochs JSONB := '[]'::JSONB; unit JSONB; kind TEXT; expected_scope TEXT; scope_count BIGINT := 0;
BEGIN
    SELECT result_bytes INTO branding_bytes FROM game_design_branding_source_application WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
    SELECT * INTO STRICT c FROM game_design_draft_commit WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND request_id = NEW.request_id AND commit_id = NEW.commit_id;
    SELECT * INTO STRICT r FROM game_design_draft_commit_owner_result WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND request_id = NEW.request_id AND owner = 'GAME_DESIGN_CONTROL_PLANE';
    SELECT * INTO STRICT h FROM game_design_template_config_source_head WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id;
    SELECT count(*) INTO declared FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') x
        WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' AND (x->>'payload')::JSONB->>'revisionKind' = 'TEMPLATE_CONFIG';
    SELECT count(*) INTO retained FROM game_design_template_config_source_revision WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
    bytes := convert_to('game-design-template-config-source-application/v1', 'UTF8'); expected := int4send(octet_length(bytes)) || bytes;
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
            WHEN 'TEMPLATE_CONFIG' THEN 'TEMPLATE_CONFIG_SET' WHEN 'GAMEPLAY_RULE' THEN 'GAMEPLAY_RULE_SET' WHEN 'BRANDING_ASSET_REFERENCE' THEN 'BRANDING_ASSET_REFERENCE_SET' ELSE NULL END;
        IF expected_scope IS NULL OR (kind = 'BRANDING_ASSET_REFERENCE' AND branding_bytes IS NULL) OR (kind = 'GAMEPLAY_RULE' AND gameplay_bytes IS NULL) OR (kind = 'COMMAND_DEFINITION' AND command_bytes IS NULL)
            OR (kind = 'ASSET_REFERENCE' AND asset_bytes IS NULL) OR (kind = 'REALM_ENTRY_POLICY' AND policy_bytes IS NULL)
            OR (SELECT count(*) FROM jsonb_array_elements(c.binding_json::JSONB->'affectedUnits') x
                WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' AND x->>'aggregateType' = expected_scope) <> 1 THEN
            RAISE EXCEPTION 'branding mixed application requires all declared sibling sources' USING ERRCODE = '23514'; END IF;
        scope_count := scope_count + 1;
    END LOOP;
    FOR unit IN SELECT x FROM jsonb_array_elements(c.binding_json::JSONB->'affectedUnits') x WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE'
        ORDER BY x->>'aggregateType', x->>'aggregateId', x->>'scopeType', x->>'scopeId' LOOP
        IF unit->>'aggregateType' NOT IN ('COMMAND_DEFINITION_SET', 'ASSET_REFERENCE_SET', 'REALM_ENTRY_POLICY_SET', 'GAMEPLAY_RULE_SET', 'TEMPLATE_CONFIG_SET', 'BRANDING_ASSET_REFERENCE_SET')
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
    IF branding_bytes IS NOT NULL THEN bytes := convert_to('BRANDING', 'UTF8');
        components := components || int4send(octet_length(bytes)) || bytes || int4send(octet_length(branding_bytes)) || branding_bytes; END IF;
    bytes := convert_to('TEMPLATE_CONFIG', 'UTF8');
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

CREATE OR REPLACE FUNCTION verify_branding_asset_application_commit() RETURNS TRIGGER AS $$
DECLARE config_bytes BYTEA; c game_design_draft_commit%ROWTYPE; r game_design_draft_commit_owner_result%ROWTYPE;
    h game_design_branding_source_head%ROWTYPE; expected BYTEA; bytes BYTEA; components BYTEA;
    gameplay_bytes BYTEA; command_bytes BYTEA; asset_bytes BYTEA; policy_bytes BYTEA; declared BIGINT; retained BIGINT;
    epochs JSONB := '[]'::JSONB; unit JSONB; kind TEXT; expected_scope TEXT; scope_count BIGINT := 0;
BEGIN
    SELECT result_bytes INTO config_bytes FROM game_design_template_config_source_application WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
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
            WHEN 'BRANDING_ASSET_REFERENCE' THEN 'BRANDING_ASSET_REFERENCE_SET' WHEN 'GAMEPLAY_RULE' THEN 'GAMEPLAY_RULE_SET' WHEN 'TEMPLATE_CONFIG' THEN 'TEMPLATE_CONFIG_SET' ELSE NULL END;
        IF expected_scope IS NULL OR (kind = 'TEMPLATE_CONFIG' AND config_bytes IS NULL) OR (kind = 'GAMEPLAY_RULE' AND gameplay_bytes IS NULL) OR (kind = 'COMMAND_DEFINITION' AND command_bytes IS NULL)
            OR (kind = 'ASSET_REFERENCE' AND asset_bytes IS NULL) OR (kind = 'REALM_ENTRY_POLICY' AND policy_bytes IS NULL)
            OR (SELECT count(*) FROM jsonb_array_elements(c.binding_json::JSONB->'affectedUnits') x
                WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' AND x->>'aggregateType' = expected_scope) <> 1 THEN
            RAISE EXCEPTION 'branding mixed application requires all declared sibling sources' USING ERRCODE = '23514'; END IF;
        scope_count := scope_count + 1;
    END LOOP;
    FOR unit IN SELECT x FROM jsonb_array_elements(c.binding_json::JSONB->'affectedUnits') x WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE'
        ORDER BY x->>'aggregateType', x->>'aggregateId', x->>'scopeType', x->>'scopeId' LOOP
        IF unit->>'aggregateType' NOT IN ('COMMAND_DEFINITION_SET', 'ASSET_REFERENCE_SET', 'REALM_ENTRY_POLICY_SET', 'GAMEPLAY_RULE_SET', 'BRANDING_ASSET_REFERENCE_SET', 'TEMPLATE_CONFIG_SET')
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
    IF (config_bytes IS NOT NULL) IS DISTINCT FROM EXISTS (SELECT 1 FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') x
        WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' AND (x->>'payload')::JSONB->>'revisionKind' = 'TEMPLATE_CONFIG') THEN
        RAISE EXCEPTION 'template config sibling application must match declared revisions' USING ERRCODE = '23514'; END IF;
    IF config_bytes IS NOT NULL THEN bytes := convert_to('TEMPLATE_CONFIG', 'UTF8');
        components := components || int4send(octet_length(bytes)) || bytes || int4send(octet_length(config_bytes)) || config_bytes; END IF;
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
DECLARE config_bytes BYTEA; c game_design_draft_commit%ROWTYPE; r game_design_draft_commit_owner_result%ROWTYPE;
    h game_design_gameplay_rule_head%ROWTYPE; expected BYTEA; bytes BYTEA; components BYTEA;
    branding_bytes BYTEA; command_bytes BYTEA; asset_bytes BYTEA; policy_bytes BYTEA; declared BIGINT; retained BIGINT;
    epochs JSONB := '[]'::JSONB; unit JSONB; kind TEXT; expected_scope TEXT; scope_count BIGINT := 0;
BEGIN
    SELECT result_bytes INTO config_bytes FROM game_design_template_config_source_application WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
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
            WHEN 'BRANDING_ASSET_REFERENCE' THEN 'BRANDING_ASSET_REFERENCE_SET' WHEN 'GAMEPLAY_RULE' THEN 'GAMEPLAY_RULE_SET' WHEN 'TEMPLATE_CONFIG' THEN 'TEMPLATE_CONFIG_SET' ELSE NULL END;
        IF expected_scope IS NULL OR (kind = 'TEMPLATE_CONFIG' AND config_bytes IS NULL) OR (kind = 'BRANDING_ASSET_REFERENCE' AND branding_bytes IS NULL) OR (kind = 'COMMAND_DEFINITION' AND command_bytes IS NULL)
            OR (kind = 'ASSET_REFERENCE' AND asset_bytes IS NULL) OR (kind = 'REALM_ENTRY_POLICY' AND policy_bytes IS NULL)
            OR (SELECT count(*) FROM jsonb_array_elements(c.binding_json::JSONB->'affectedUnits') x
                WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' AND x->>'aggregateType' = expected_scope) <> 1 THEN
            RAISE EXCEPTION 'gameplay mixed application requires all declared sibling sources' USING ERRCODE = '23514'; END IF;
        scope_count := scope_count + 1;
    END LOOP;
    FOR unit IN SELECT x FROM jsonb_array_elements(c.binding_json::JSONB->'affectedUnits') x WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE'
        ORDER BY x->>'aggregateType', x->>'aggregateId', x->>'scopeType', x->>'scopeId' LOOP
        IF unit->>'aggregateType' NOT IN ('COMMAND_DEFINITION_SET', 'ASSET_REFERENCE_SET', 'REALM_ENTRY_POLICY_SET', 'GAMEPLAY_RULE_SET', 'BRANDING_ASSET_REFERENCE_SET', 'TEMPLATE_CONFIG_SET')
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
    IF (config_bytes IS NOT NULL) IS DISTINCT FROM EXISTS (SELECT 1 FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') x
        WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' AND (x->>'payload')::JSONB->>'revisionKind' = 'TEMPLATE_CONFIG') THEN
        RAISE EXCEPTION 'template config sibling application must match declared revisions' USING ERRCODE = '23514'; END IF;
    IF config_bytes IS NOT NULL THEN bytes := convert_to('TEMPLATE_CONFIG', 'UTF8');
        components := components || int4send(octet_length(bytes)) || bytes || int4send(octet_length(config_bytes)) || config_bytes; END IF;
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
DECLARE config_bytes BYTEA; c game_design_draft_commit%ROWTYPE; r game_design_draft_commit_owner_result%ROWTYPE;
    h game_design_asset_source_head%ROWTYPE; expected BYTEA; bytes BYTEA; declared BIGINT; retained BIGINT;
    components BYTEA; command_bytes BYTEA; policy_bytes BYTEA; gameplay_bytes BYTEA; branding_bytes BYTEA; epochs JSONB; unit JSONB;
BEGIN
    SELECT result_bytes INTO config_bytes FROM game_design_template_config_source_application WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
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
            AND coalesce((x->>'payload')::JSONB->>'revisionKind', '') NOT IN ('COMMAND_DEFINITION', 'REALM_ENTRY_POLICY', 'ASSET_REFERENCE', 'GAMEPLAY_RULE', 'BRANDING_ASSET_REFERENCE', 'TEMPLATE_CONFIG'))
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
        IF unit->>'aggregateType' NOT IN ('COMMAND_DEFINITION_SET', 'ASSET_REFERENCE_SET', 'REALM_ENTRY_POLICY_SET', 'GAMEPLAY_RULE_SET', 'BRANDING_ASSET_REFERENCE_SET', 'TEMPLATE_CONFIG_SET')
            OR unit->>'aggregateType' IS DISTINCT FROM unit->>'scopeType'
            OR unit->>'scopeId' IS DISTINCT FROM 'effective'
            OR unit->>'aggregateId' IS DISTINCT FROM NEW.canonical_version_id::TEXT THEN
            RAISE EXCEPTION 'ordinary combined scope unknown' USING ERRCODE = '23514'; END IF;
        epochs := epochs || jsonb_build_array((unit - 'owner') || jsonb_build_object('resultingEpoch', ((unit->>'expectedEpoch')::NUMERIC + 1)::TEXT));
    END LOOP;
    IF (config_bytes IS NOT NULL) IS DISTINCT FROM EXISTS (SELECT 1 FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') x
        WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' AND (x->>'payload')::JSONB->>'revisionKind' = 'TEMPLATE_CONFIG') THEN
        RAISE EXCEPTION 'template config sibling application must match declared revisions' USING ERRCODE = '23514'; END IF;
    IF config_bytes IS NOT NULL THEN bytes := convert_to('TEMPLATE_CONFIG', 'UTF8');
        components := components || int4send(octet_length(bytes)) || bytes || int4send(octet_length(config_bytes)) || config_bytes; END IF;
    IF policy_bytes IS NULL THEN bytes := convert_to('game-design-control-plane-ordinary-source-application/v1', 'UTF8');
    ELSE bytes := convert_to('game-design-control-plane-source-application/v1', 'UTF8'); END IF;
    expected := int4send(octet_length(bytes)) || bytes;
    bytes := convert_to(c.binding_json, 'UTF8');
    expected := expected || int4send(octet_length(bytes)) || bytes;
    IF policy_bytes IS NOT NULL THEN expected := expected || int4send(octet_length(policy_bytes)) || policy_bytes; END IF;
    expected := expected || int4send(octet_length(components)) || components;
    IF r.result_bytes IS DISTINCT FROM expected OR r.applied_units_json::JSONB IS DISTINCT FROM epochs
        OR jsonb_array_length(epochs) <> (1 + CASE WHEN command_bytes IS NULL THEN 0 ELSE 1 END + CASE WHEN policy_bytes IS NULL THEN 0 ELSE 1 END + CASE WHEN gameplay_bytes IS NULL THEN 0 ELSE 1 END + CASE WHEN branding_bytes IS NULL THEN 0 ELSE 1 END + CASE WHEN config_bytes IS NULL THEN 0 ELSE 1 END) THEN
        RAISE EXCEPTION 'ordinary combined outcome must contain exact tagged components and complete epochs' USING ERRCODE = '23514'; END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER template_config_genesis_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_template_config_source_genesis
    FOR EACH ROW EXECUTE FUNCTION guard_template_config_source();
CREATE TRIGGER template_config_head_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_template_config_source_head
    FOR EACH ROW EXECUTE FUNCTION guard_template_config_source();
CREATE TRIGGER template_config_revision_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_template_config_source_revision
    FOR EACH ROW EXECUTE FUNCTION guard_template_config_source();
CREATE TRIGGER template_config_application_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_template_config_source_application
    FOR EACH ROW EXECUTE FUNCTION guard_template_config_source();
CREATE TRIGGER template_config_snapshot_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_template_config_source_snapshot
    FOR EACH ROW EXECUTE FUNCTION guard_template_config_source();
CREATE TRIGGER template_config_capture_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_template_config_source_capture
    FOR EACH ROW EXECUTE FUNCTION guard_template_config_source();
CREATE TRIGGER template_config_qualification_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_template_config_source_qualification
    FOR EACH ROW EXECUTE FUNCTION guard_template_config_source();
CREATE CONSTRAINT TRIGGER template_config_application_commit_guard AFTER INSERT ON game_design_template_config_source_application
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_template_config_application_commit();
CREATE TRIGGER template_config_genesis_no_truncate BEFORE TRUNCATE ON game_design_template_config_source_genesis
    EXECUTE FUNCTION deny_template_config_history_change();
CREATE TRIGGER template_config_head_no_truncate BEFORE TRUNCATE ON game_design_template_config_source_head
    EXECUTE FUNCTION deny_template_config_history_change();
CREATE TRIGGER template_config_revision_no_truncate BEFORE TRUNCATE ON game_design_template_config_source_revision
    EXECUTE FUNCTION deny_template_config_history_change();
CREATE TRIGGER template_config_application_no_truncate BEFORE TRUNCATE ON game_design_template_config_source_application
    EXECUTE FUNCTION deny_template_config_history_change();
CREATE TRIGGER template_config_snapshot_no_truncate BEFORE TRUNCATE ON game_design_template_config_source_snapshot
    EXECUTE FUNCTION deny_template_config_history_change();
CREATE TRIGGER template_config_capture_no_truncate BEFORE TRUNCATE ON game_design_template_config_source_capture
    EXECUTE FUNCTION deny_template_config_history_change();
CREATE TRIGGER template_config_qualification_no_truncate BEFORE TRUNCATE ON game_design_template_config_source_qualification
    EXECUTE FUNCTION deny_template_config_history_change();
CREATE TRIGGER template_config_ref_no_truncate BEFORE TRUNCATE ON game_design_template_config_source_ref
    EXECUTE FUNCTION deny_template_config_history_change();
CREATE TRIGGER template_config_row_insert_no_truncate BEFORE TRUNCATE ON game_design_template_config_source_row_insert
    EXECUTE FUNCTION deny_template_config_history_change();
-- [jooq ignore stop]
