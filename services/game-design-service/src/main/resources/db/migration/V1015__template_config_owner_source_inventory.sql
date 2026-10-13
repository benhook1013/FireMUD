CREATE TABLE game_design_template_config_owner_source_inventory_declaration (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    request_id UUID NOT NULL,
    commit_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    revision_order INTEGER NOT NULL CHECK (revision_order >= 0),
    owner VARCHAR(32) NOT NULL CHECK (owner = 'AUTOMATION_SCRIPTING'),
    inventory_json TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    PRIMARY KEY (canonical_tenant_id, canonical_version_id, revision_id),
    UNIQUE (canonical_tenant_id, canonical_version_id, request_id, commit_id, revision_id, revision_order, owner),
    UNIQUE (canonical_tenant_id, canonical_version_id, commit_id, revision_order),
    UNIQUE (canonical_tenant_id, canonical_version_id, request_id, commit_id, owner),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, request_id, commit_id)
        REFERENCES game_design_draft_commit (canonical_tenant_id, canonical_version_id, request_id, commit_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, commit_id)
        REFERENCES game_design_template_config_source_application
            (canonical_tenant_id, canonical_version_id, commit_id)
        DEFERRABLE INITIALLY DEFERRED,
    CHECK (canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (commit_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (revision_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (octet_length(inventory_json) BETWEEN 1 AND 4096),
    CHECK (octet_length(payload_json) > 0)
);

-- [jooq ignore start]
CREATE FUNCTION automation_authored_source_inventory_supported(value JSONB) RETURNS BOOLEAN AS $$
BEGIN
    IF jsonb_typeof(value) IS DISTINCT FROM 'object' THEN RETURN FALSE; END IF;
    IF (SELECT count(*) FROM jsonb_object_keys(value)) <> 2
        OR value->>'schema' IS DISTINCT FROM 'automation-authored-source-inventory/v1'
        OR jsonb_typeof(value->'schema') IS DISTINCT FROM 'string'
        OR jsonb_typeof(value->'families') IS DISTINCT FROM 'object' THEN RETURN FALSE; END IF;
    IF (SELECT count(*) FROM jsonb_object_keys(value->'families')) <> 3
        OR jsonb_typeof(value->'families'->'SCRIPT_DEFINITIONS') IS DISTINCT FROM 'array'
        OR jsonb_typeof(value->'families'->'EVENT_BINDINGS') IS DISTINCT FROM 'array'
        OR jsonb_typeof(value->'families'->'SCRIPT_PATCH_SOURCES') IS DISTINCT FROM 'array' THEN RETURN FALSE; END IF;
    IF jsonb_array_length(value->'families'->'SCRIPT_DEFINITIONS') <> 0
        OR jsonb_array_length(value->'families'->'EVENT_BINDINGS') <> 0
        OR jsonb_array_length(value->'families'->'SCRIPT_PATCH_SOURCES') <> 0 THEN RETURN FALSE; END IF;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

CREATE FUNCTION guard_template_config_owner_source_inventory_declaration() RETURNS TRIGGER AS $$
DECLARE v version%ROWTYPE; c game_design_draft_commit%ROWTYPE; revision JSONB; payload JSONB;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'template owner source inventory history is immutable' USING ERRCODE = '23514';
    END IF;
    SELECT owner.* INTO STRICT v FROM version owner JOIN game game_source
        ON game_source.id = owner.identity_source_game_row_id
        AND game_source.tenant_id = owner.identity_source_game_tenant_key AND game_source.tenant_id = owner.tenant_id
        AND game_source.canonical_tenant_id = owner.canonical_tenant_id
        AND game_source.tenant_identity_provenance_kind = owner.identity_source_provenance_kind
        AND game_source.tenant_identity_source_game_id = game_source.id
        AND game_source.tenant_identity_source_legacy_tenant_id = game_source.tenant_id
        WHERE owner.canonical_tenant_id = NEW.canonical_tenant_id
          AND owner.canonical_version_id = NEW.canonical_version_id FOR UPDATE OF owner;
    IF v.version_state <> 'DRAFT' OR v.is_script_only OR v.base_version_id IS NOT NULL
        OR v.script_patch_version IS NOT NULL
        OR NOT EXISTS (SELECT 1 FROM game_design_template_config_source_genesis g
            WHERE g.canonical_tenant_id = NEW.canonical_tenant_id
              AND g.canonical_version_id = NEW.canonical_version_id AND g.version_id = v.id) THEN
        RAISE EXCEPTION 'template owner inventory requires exact canonical full Draft' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM game_design_authored_draft_publish_selection x
            WHERE x.canonical_tenant_id = NEW.canonical_tenant_id AND x.canonical_version_id = NEW.canonical_version_id)
        OR EXISTS (SELECT 1 FROM version_asset_export_snapshot x WHERE x.tenant_id = v.tenant_id AND x.version_id = v.id) THEN
        RAISE EXCEPTION 'template owner inventory is frozen by selected publication or export' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT c FROM game_design_draft_commit
        WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id
          AND request_id = NEW.request_id AND commit_id = NEW.commit_id;
    IF NOT EXISTS (SELECT 1 FROM game_design_draft_commit_application_slot slot
            WHERE slot.canonical_tenant_id = NEW.canonical_tenant_id AND slot.canonical_version_id = NEW.canonical_version_id
              AND slot.request_id = NEW.request_id AND slot.commit_id = NEW.commit_id)
        OR NOT EXISTS (SELECT 1 FROM game_design_draft_commit_owner_result r
            WHERE r.canonical_tenant_id = NEW.canonical_tenant_id AND r.canonical_version_id = NEW.canonical_version_id
              AND r.request_id = NEW.request_id AND r.owner = 'GAME_DESIGN_CONTROL_PLANE'
              AND r.status IN ('IN_PROGRESS', 'UNKNOWN')) THEN
        RAISE EXCEPTION 'template owner inventory requires active coordinator application' USING ERRCODE = '23514';
    END IF;
    SELECT r INTO STRICT revision FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') r
        WHERE r->>'revisionId' = NEW.revision_id::TEXT AND r->>'revisionOrder' = NEW.revision_order::TEXT
          AND r->>'owner' = 'GAME_DESIGN_CONTROL_PLANE';
    payload := (revision->>'payload')::JSONB;
    IF jsonb_typeof(payload) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'template owner inventory requires exact supported Automation declaration' USING ERRCODE = '23514';
    END IF;
    IF NOT template_config_unique_json((revision->>'payload')::JSON)
        OR (SELECT count(*) FROM jsonb_object_keys(payload)) <> 5
        OR payload->'schemaVersion' IS DISTINCT FROM '2'::JSONB
        OR payload->>'revisionKind' IS DISTINCT FROM 'TEMPLATE_CONFIG'
        OR payload->>'operation' IS DISTINCT FROM 'DECLARE_OWNER_SOURCE_INVENTORY'
        OR payload->>'owner' IS DISTINCT FROM 'AUTOMATION_SCRIPTING'
        OR jsonb_typeof(payload->'inventory') IS DISTINCT FROM 'object'
        OR NEW.owner IS DISTINCT FROM payload->>'owner'
        OR NEW.inventory_json::JSONB IS DISTINCT FROM payload->'inventory'
        OR NOT template_config_unique_json(NEW.inventory_json::JSON)
        OR NOT automation_authored_source_inventory_supported(NEW.inventory_json::JSONB)
        OR NEW.payload_json IS DISTINCT FROM revision->>'payload'
        OR NEW.payload_json::JSONB IS DISTINCT FROM payload THEN
        RAISE EXCEPTION 'template owner inventory requires exact supported Automation declaration' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION guard_template_config_v1_snapshot_declaration_presence() RETURNS TRIGGER AS $$
DECLARE source_epoch TEXT;
BEGIN
    IF TG_OP <> 'INSERT' THEN RETURN NEW; END IF;
    IF NEW.snapshot_json::JSONB->>'schema' IS DISTINCT FROM 'game-design-template-config-source-snapshot/v1' THEN
        RETURN NEW;
    END IF;
    source_epoch := NEW.snapshot_json::JSONB->>'sourceEpoch';
    IF EXISTS (SELECT 1 FROM game_design_template_config_owner_source_inventory_declaration d
        JOIN game_design_template_config_source_application a ON a.canonical_tenant_id = d.canonical_tenant_id
            AND a.canonical_version_id = d.canonical_version_id AND a.request_id = d.request_id AND a.commit_id = d.commit_id
        WHERE d.canonical_tenant_id = NEW.canonical_tenant_id AND d.canonical_version_id = NEW.canonical_version_id
            AND a.expected_epoch::NUMERIC < source_epoch::NUMERIC) THEN
        RAISE EXCEPTION 'template config v1 snapshot cannot omit retained owner declaration' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION guard_template_config_source_v2() RETURNS TRIGGER AS $$
DECLARE v version%ROWTYPE; g game_design_template_config_source_genesis%ROWTYPE;
    h game_design_template_config_source_head%ROWTYPE; c game_design_draft_commit%ROWTYPE;
    a game_design_template_config_source_application%ROWTYPE; snapshot JSONB; prior JSONB;
    expected_items JSONB; expected_declarations JSONB; declared BIGINT; predecessor_commit_id UUID;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'template config source history is immutable' USING ERRCODE = '23514';
    END IF;
    SELECT owner.* INTO STRICT v FROM version owner JOIN game game_source
        ON game_source.id = owner.identity_source_game_row_id
        AND game_source.tenant_id = owner.identity_source_game_tenant_key AND game_source.tenant_id = owner.tenant_id
        AND game_source.canonical_tenant_id = owner.canonical_tenant_id
        AND game_source.tenant_identity_provenance_kind = owner.identity_source_provenance_kind
        AND game_source.tenant_identity_source_game_id = game_source.id
        AND game_source.tenant_identity_source_legacy_tenant_id = game_source.tenant_id
        WHERE owner.canonical_tenant_id = NEW.canonical_tenant_id
          AND owner.canonical_version_id = NEW.canonical_version_id FOR UPDATE OF owner;
    IF v.version_state <> 'DRAFT' OR v.is_script_only OR v.base_version_id IS NOT NULL
        OR v.script_patch_version IS NOT NULL THEN
        RAISE EXCEPTION 'template config source requires exact canonical full Draft' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT g FROM game_design_template_config_source_genesis
        WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id;
    SELECT * INTO STRICT h FROM game_design_template_config_source_head
        WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM game_design_authored_draft_publish_selection x
            WHERE x.canonical_tenant_id = NEW.canonical_tenant_id AND x.canonical_version_id = NEW.canonical_version_id)
        OR EXISTS (SELECT 1 FROM version_asset_export_snapshot x WHERE x.tenant_id = v.tenant_id AND x.version_id = v.id) THEN
        RAISE EXCEPTION 'template config source is frozen by selected publication or export' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT c FROM game_design_draft_commit
        WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id
          AND request_id = NEW.request_id AND commit_id = NEW.commit_id;
    snapshot := NEW.snapshot_json::JSONB;
    IF jsonb_typeof(snapshot) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'template config v2 snapshot binding invalid' USING ERRCODE = '23514';
    END IF;
    IF NOT template_config_unique_json(NEW.snapshot_json::JSON)
        OR snapshot->>'schema' IS DISTINCT FROM 'game-design-template-config-source-snapshot/v2'
        OR snapshot->>'bindingJson' IS DISTINCT FROM c.binding_json
        OR snapshot->>'bindingDigest' IS DISTINCT FROM c.input_digest
        OR snapshot->>'genesisReceiptId' IS DISTINCT FROM g.receipt_id::TEXT
        OR (SELECT count(*) FROM jsonb_object_keys(snapshot)) <> 8 THEN
        RAISE EXCEPTION 'template config v2 snapshot binding invalid' USING ERRCODE = '23514';
    END IF;
    IF jsonb_typeof(snapshot->'entries') IS DISTINCT FROM 'array'
        OR jsonb_typeof(snapshot->'ownerSourceInventoryDeclarations') IS DISTINCT FROM 'array'
        OR jsonb_array_length(snapshot->'ownerSourceInventoryDeclarations') = 0 THEN
        RAISE EXCEPTION 'template config v2 snapshot declarations are required' USING ERRCODE = '23514';
    END IF;

    -- The application is checked against the pre-application head; its later synchronized
    -- snapshot inherits from that immutable application, not a head pointer that may advance.
    IF TG_TABLE_NAME = 'game_design_template_config_source_application' THEN
        predecessor_commit_id := h.visible_commit_id;
    ELSE
        SELECT count(*) INTO declared FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') r
            WHERE r->>'owner' = 'GAME_DESIGN_CONTROL_PLANE'
              AND (r->>'payload')::JSONB->>'revisionKind' = 'TEMPLATE_CONFIG';
        SELECT * INTO a FROM game_design_template_config_source_application
            WHERE canonical_tenant_id = NEW.canonical_tenant_id
              AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
        IF (declared > 0) IS DISTINCT FROM (a.commit_id IS NOT NULL)
            OR (a.commit_id IS NOT NULL AND a.snapshot_json IS DISTINCT FROM NEW.snapshot_json) THEN
            RAISE EXCEPTION 'template config v2 snapshot differs from its validated application' USING ERRCODE = '23514';
        END IF;
        IF a.commit_id IS NOT NULL THEN
            predecessor_commit_id := NULLIF(a.snapshot_json::JSONB->>'inheritedCommitId', '')::UUID;
        ELSE
            predecessor_commit_id := h.visible_commit_id;
        END IF;
    END IF;
    IF snapshot->>'inheritedCommitId' IS DISTINCT FROM coalesce(predecessor_commit_id::TEXT, '') THEN
        RAISE EXCEPTION 'template config v2 predecessor binding invalid' USING ERRCODE = '23514';
    END IF;
    SELECT s0.snapshot_json::JSONB INTO prior
        FROM (SELECT 1) seed
        LEFT JOIN game_design_template_config_source_snapshot s0 ON s0.canonical_tenant_id = NEW.canonical_tenant_id
            AND s0.canonical_version_id = NEW.canonical_version_id
            AND s0.commit_id = predecessor_commit_id;
    IF predecessor_commit_id IS NOT NULL AND prior IS NULL THEN
        RAISE EXCEPTION 'template config v2 predecessor snapshot is unavailable' USING ERRCODE = '23514';
    END IF;
    IF prior IS NULL THEN
        prior := jsonb_build_object(
            'entries', '[]'::JSONB,
            'ownerSourceInventoryDeclarations', '[]'::JSONB);
    END IF;

    IF TG_TABLE_NAME = 'game_design_template_config_source_application' THEN
        IF NOT EXISTS (SELECT 1 FROM game_design_draft_commit_application_slot slot
                WHERE slot.canonical_tenant_id = NEW.canonical_tenant_id AND slot.canonical_version_id = NEW.canonical_version_id
                  AND slot.request_id = NEW.request_id AND slot.commit_id = NEW.commit_id)
            OR NOT EXISTS (SELECT 1 FROM game_design_draft_commit_owner_result r
                WHERE r.canonical_tenant_id = NEW.canonical_tenant_id AND r.canonical_version_id = NEW.canonical_version_id
                  AND r.request_id = NEW.request_id AND r.owner = 'GAME_DESIGN_CONTROL_PLANE'
                  AND r.status IN ('IN_PROGRESS', 'UNKNOWN'))
            OR NEW.expected_epoch IS DISTINCT FROM h.source_epoch
            OR snapshot->>'sourceEpoch' IS DISTINCT FROM (h.source_epoch::NUMERIC + 1)::TEXT
            OR (SELECT count(*) FROM jsonb_array_elements(c.binding_json::JSONB->'affectedUnits') unit
                WHERE unit->>'owner' = 'GAME_DESIGN_CONTROL_PLANE'
                    AND unit->>'aggregateType' = 'TEMPLATE_CONFIG_SET'
                    AND unit->>'aggregateId' = NEW.canonical_version_id::TEXT
                    AND unit->>'scopeType' = 'TEMPLATE_CONFIG_SET'
                    AND unit->>'scopeId' = 'effective'
                    AND unit->>'expectedEpoch' = NEW.expected_epoch) <> 1 THEN
            RAISE EXCEPTION 'template config v2 application lacks coordinator scope epoch' USING ERRCODE = '23514';
        END IF;
        WITH choices AS (
            SELECT (item->>'templateId')::BIGINT AS key, -1 AS ord, FALSE AS deleted, item
                FROM jsonb_array_elements(prior->'entries') item
            UNION ALL
            SELECT r.template_id, r.revision_order, r.operation_kind = 'DELETE', template_config_revision_item(r)
                FROM game_design_template_config_source_revision r
                WHERE r.canonical_tenant_id = NEW.canonical_tenant_id AND r.canonical_version_id = NEW.canonical_version_id
                  AND r.commit_id = NEW.commit_id
        ), final AS (SELECT DISTINCT ON (key) * FROM choices ORDER BY key, ord DESC)
        SELECT coalesce(jsonb_agg(item ORDER BY key) FILTER (WHERE NOT deleted), '[]'::JSONB)
            INTO expected_items FROM final;
        WITH choices AS (
            SELECT item->>'owner' AS owner, -1 AS ord, item
                FROM jsonb_array_elements(prior->'ownerSourceInventoryDeclarations') item
            UNION ALL
            SELECT d.owner, d.revision_order,
                jsonb_build_object('owner', d.owner, 'inventoryJson', d.inventory_json,
                    'sourceBindingJson', source.binding_json, 'sourceBindingDigest', source.input_digest,
                    'revisionOrder', d.revision_order::TEXT, 'revisionId', d.revision_id::TEXT)
                FROM game_design_template_config_owner_source_inventory_declaration d
                JOIN game_design_draft_commit source ON source.canonical_tenant_id = d.canonical_tenant_id
                    AND source.canonical_version_id = d.canonical_version_id
                    AND source.request_id = d.request_id AND source.commit_id = d.commit_id
                WHERE d.canonical_tenant_id = NEW.canonical_tenant_id AND d.canonical_version_id = NEW.canonical_version_id
                  AND d.commit_id = NEW.commit_id
        ), final AS (SELECT DISTINCT ON (owner) owner, ord, item FROM choices ORDER BY owner, ord DESC)
        SELECT coalesce(jsonb_agg(item ORDER BY owner), '[]'::JSONB)
            INTO expected_declarations FROM final;
        IF expected_items IS DISTINCT FROM snapshot->'entries'
            OR expected_declarations IS DISTINCT FROM snapshot->'ownerSourceInventoryDeclarations'
            OR NEW.expected_epoch IS DISTINCT FROM h.source_epoch THEN
            RAISE EXCEPTION 'template config v2 application must replay exact source and declarations' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF snapshot->>'sourceEpoch' IS DISTINCT FROM h.source_epoch
        OR (a.commit_id IS NULL AND snapshot->'entries' IS DISTINCT FROM prior->'entries')
        OR (a.commit_id IS NULL AND snapshot->'ownerSourceInventoryDeclarations' IS DISTINCT FROM prior->'ownerSourceInventoryDeclarations')
        OR NEW.snapshot_digest IS DISTINCT FROM 'sha256:' || encode(sha256(convert_to(NEW.snapshot_json, 'UTF8')), 'hex')
        OR NOT template_config_selected_inputs(snapshot, NEW.canonical_tenant_id, NEW.canonical_version_id, NEW.commit_id)
        OR NOT template_config_current_inventory(NEW.canonical_tenant_id, NEW.canonical_version_id, v.id, v.tenant_id, snapshot)
        OR c.workflow_state <> 'SYNCHRONIZED'
        OR NOT EXISTS (SELECT 1 FROM game_design_draft_commit_visibility f
            WHERE f.canonical_tenant_id = NEW.canonical_tenant_id AND f.canonical_version_id = NEW.canonical_version_id
              AND f.request_id = NEW.request_id AND f.commit_id = NEW.commit_id AND f.input_digest = c.input_digest) THEN
        RAISE EXCEPTION 'template config v2 snapshot requires exact synchronized source inheritance' USING ERRCODE = '23514';
    END IF;
    WITH choices AS (
        SELECT item->>'owner' AS owner, -1::NUMERIC AS source_epoch, -1 AS ord, item
            FROM jsonb_array_elements(prior->'ownerSourceInventoryDeclarations') item
        UNION ALL
        SELECT d.owner, owner_application.expected_epoch::NUMERIC, d.revision_order,
            jsonb_build_object('owner', d.owner, 'inventoryJson', d.inventory_json,
                'sourceBindingJson', source.binding_json, 'sourceBindingDigest', source.input_digest,
                'revisionOrder', d.revision_order::TEXT, 'revisionId', d.revision_id::TEXT)
            FROM game_design_template_config_owner_source_inventory_declaration d
            JOIN game_design_draft_commit source ON source.canonical_tenant_id = d.canonical_tenant_id
                AND source.canonical_version_id = d.canonical_version_id
                AND source.request_id = d.request_id AND source.commit_id = d.commit_id
            JOIN game_design_template_config_source_application owner_application
                ON owner_application.canonical_tenant_id = d.canonical_tenant_id
                AND owner_application.canonical_version_id = d.canonical_version_id
                AND owner_application.request_id = d.request_id AND owner_application.commit_id = d.commit_id
            WHERE d.canonical_tenant_id = NEW.canonical_tenant_id AND d.canonical_version_id = NEW.canonical_version_id
              AND owner_application.expected_epoch::NUMERIC < (snapshot->>'sourceEpoch')::NUMERIC
    ), final AS (
        SELECT DISTINCT ON (owner) owner, source_epoch, ord, item
            FROM choices ORDER BY owner, source_epoch DESC, ord DESC
    )
    SELECT coalesce(jsonb_agg(item ORDER BY owner), '[]'::JSONB) INTO expected_declarations FROM final;
    IF expected_declarations IS DISTINCT FROM snapshot->'ownerSourceInventoryDeclarations' THEN
        RAISE EXCEPTION 'template config v2 snapshot omits or changes authored owner inventory' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION deny_template_config_owner_inventory_truncate() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'template owner source inventory history is immutable' USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER template_config_owner_inventory_guard
    BEFORE INSERT OR UPDATE OR DELETE ON game_design_template_config_owner_source_inventory_declaration
    FOR EACH ROW EXECUTE FUNCTION guard_template_config_owner_source_inventory_declaration();
CREATE TRIGGER template_config_owner_inventory_no_truncate
    BEFORE TRUNCATE ON game_design_template_config_owner_source_inventory_declaration
    FOR EACH STATEMENT EXECUTE FUNCTION deny_template_config_owner_inventory_truncate();

DROP TRIGGER template_config_application_guard ON game_design_template_config_source_application;
CREATE TRIGGER template_config_application_v1_guard
    BEFORE INSERT ON game_design_template_config_source_application
    FOR EACH ROW WHEN (NEW.snapshot_json::JSONB->>'schema' IS DISTINCT FROM 'game-design-template-config-source-snapshot/v2')
    EXECUTE FUNCTION guard_template_config_source();
CREATE TRIGGER template_config_application_v2_guard
    BEFORE INSERT ON game_design_template_config_source_application
    FOR EACH ROW WHEN (NEW.snapshot_json::JSONB->>'schema' = 'game-design-template-config-source-snapshot/v2')
    EXECUTE FUNCTION guard_template_config_source_v2();
CREATE TRIGGER template_config_application_immutable_guard
    BEFORE UPDATE OR DELETE ON game_design_template_config_source_application
    FOR EACH ROW EXECUTE FUNCTION guard_template_config_source();

DROP TRIGGER template_config_snapshot_guard ON game_design_template_config_source_snapshot;
CREATE TRIGGER template_config_snapshot_v1_guard
    BEFORE INSERT ON game_design_template_config_source_snapshot
    FOR EACH ROW WHEN (NEW.snapshot_json::JSONB->>'schema' IS DISTINCT FROM 'game-design-template-config-source-snapshot/v2')
    EXECUTE FUNCTION guard_template_config_source();
CREATE TRIGGER template_config_snapshot_v2_guard
    BEFORE INSERT ON game_design_template_config_source_snapshot
    FOR EACH ROW WHEN (NEW.snapshot_json::JSONB->>'schema' = 'game-design-template-config-source-snapshot/v2')
    EXECUTE FUNCTION guard_template_config_source_v2();
CREATE TRIGGER template_config_snapshot_immutable_guard
    BEFORE UPDATE OR DELETE ON game_design_template_config_source_snapshot
    FOR EACH ROW EXECUTE FUNCTION guard_template_config_source();

CREATE TRIGGER template_config_application_v1_declaration_presence_guard
    BEFORE INSERT ON game_design_template_config_source_application
    FOR EACH ROW EXECUTE FUNCTION guard_template_config_v1_snapshot_declaration_presence();
CREATE TRIGGER template_config_snapshot_v1_declaration_presence_guard
    BEFORE INSERT ON game_design_template_config_source_snapshot
    FOR EACH ROW EXECUTE FUNCTION guard_template_config_v1_snapshot_declaration_presence();
-- [jooq ignore stop]
