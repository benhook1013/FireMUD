-- Authored gameplay-rule inventory. No retained Draft receives an inferred empty baseline.
CREATE TABLE game_design_gameplay_rule_genesis (
    canonical_tenant_id UUID NOT NULL, canonical_version_id UUID NOT NULL,
    version_id BIGINT NOT NULL UNIQUE, receipt_id UUID NOT NULL UNIQUE,
    creation_transaction_id TEXT NOT NULL, target_json TEXT NOT NULL, inventory_json TEXT NOT NULL,
    PRIMARY KEY (canonical_tenant_id, canonical_version_id),
    FOREIGN KEY (version_id, canonical_tenant_id, canonical_version_id)
        REFERENCES version (id, canonical_tenant_id, canonical_version_id),
    FOREIGN KEY (version_id) REFERENCES game_design_realm_policy_version_insert (version_id),
    CHECK (receipt_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (creation_transaction_id ~ '^[1-9][0-9]*$')
);
CREATE TABLE game_design_gameplay_rule_application (
    canonical_tenant_id UUID NOT NULL, canonical_version_id UUID NOT NULL,
    request_id UUID NOT NULL, commit_id UUID NOT NULL, expected_epoch TEXT NOT NULL,
    snapshot_json TEXT NOT NULL, result_bytes BYTEA NOT NULL,
    PRIMARY KEY (canonical_tenant_id, canonical_version_id, commit_id),
    UNIQUE (canonical_tenant_id, canonical_version_id, request_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, request_id, commit_id)
        REFERENCES game_design_draft_commit (canonical_tenant_id, canonical_version_id, request_id, commit_id),
    CHECK (expected_epoch ~ '^(0|[1-9][0-9]*)$'), CHECK (octet_length(result_bytes) > 0)
);
CREATE TABLE game_design_gameplay_rule_revision (
    canonical_tenant_id UUID NOT NULL, canonical_version_id UUID NOT NULL,
    request_id UUID NOT NULL, commit_id UUID NOT NULL, revision_id UUID NOT NULL,
    revision_order INTEGER NOT NULL CHECK (revision_order >= 0), operation_kind VARCHAR(16) NOT NULL,
    family VARCHAR(64) NOT NULL, definition_key VARCHAR(128) NOT NULL, payload_json TEXT NOT NULL,
    PRIMARY KEY (canonical_tenant_id, canonical_version_id, revision_id),
    UNIQUE (canonical_tenant_id, canonical_version_id, commit_id, revision_order),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, request_id, commit_id)
        REFERENCES game_design_draft_commit (canonical_tenant_id, canonical_version_id, request_id, commit_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, commit_id)
        REFERENCES game_design_gameplay_rule_application (canonical_tenant_id, canonical_version_id, commit_id)
        DEFERRABLE INITIALLY DEFERRED,
    CHECK (operation_kind IN ('UPSERT', 'DELETE')),
    CHECK (definition_key ~ '^[A-Za-z][A-Za-z0-9_.:-]{0,127}$')
);
CREATE TABLE game_design_gameplay_rule_snapshot (
    canonical_tenant_id UUID NOT NULL, canonical_version_id UUID NOT NULL,
    request_id UUID NOT NULL, commit_id UUID NOT NULL, snapshot_json TEXT NOT NULL,
    snapshot_digest VARCHAR(71) NOT NULL CHECK (snapshot_digest ~ '^sha256:[0-9a-f]{64}$'),
    PRIMARY KEY (canonical_tenant_id, canonical_version_id, commit_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, request_id, commit_id)
        REFERENCES game_design_draft_commit_visibility_fence (canonical_tenant_id, canonical_version_id, request_id, commit_id)
);
CREATE TABLE game_design_gameplay_rule_head (
    canonical_tenant_id UUID NOT NULL, canonical_version_id UUID NOT NULL,
    source_epoch TEXT NOT NULL CHECK (source_epoch ~ '^(0|[1-9][0-9]*)$'),
    genesis_receipt_id UUID NOT NULL REFERENCES game_design_gameplay_rule_genesis (receipt_id),
    applied_commit_id UUID, visible_commit_id UUID,
    PRIMARY KEY (canonical_tenant_id, canonical_version_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id)
        REFERENCES game_design_gameplay_rule_genesis (canonical_tenant_id, canonical_version_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, applied_commit_id)
        REFERENCES game_design_gameplay_rule_application (canonical_tenant_id, canonical_version_id, commit_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, visible_commit_id)
        REFERENCES game_design_gameplay_rule_snapshot (canonical_tenant_id, canonical_version_id, commit_id)
);

-- [jooq ignore start]
CREATE FUNCTION gameplay_rule_families() RETURNS TEXT[] AS $$
    SELECT ARRAY['STATS', 'RESOURCES', 'CONDITIONS', 'EFFECTS', 'ABILITIES', 'ACTIONS', 'COMMANDS',
        'ADMISSION_TAGS', 'DISPOSITIONS', 'CONTINUOUS_OVERLAYS', 'OBSERVATION_POLICIES',
        'TARGETING_POLICIES', 'SELECTION_POLICIES', 'DEFAULT_BINDINGS', 'FEEDBACK']::TEXT[];
$$ LANGUAGE SQL IMMUTABLE;

CREATE FUNCTION gameplay_rule_definition_key(family TEXT, definition JSONB) RETURNS TEXT AS $$
    SELECT CASE family WHEN 'ABILITIES' THEN definition->>'abilityId'
        WHEN 'ACTIONS' THEN definition->>'actionSequenceId'
        WHEN 'STATS' THEN definition->>'statKey' WHEN 'RESOURCES' THEN definition->>'statKey'
        WHEN 'CONDITIONS' THEN definition->>'conditionKey' WHEN 'EFFECTS' THEN definition->>'effectKey'
        WHEN 'COMMANDS' THEN definition->>'commandId' WHEN 'ADMISSION_TAGS' THEN definition->>'tagKey'
        WHEN 'DISPOSITIONS' THEN definition->>'dispositionKey' WHEN 'CONTINUOUS_OVERLAYS' THEN definition->>'overlayKey'
        WHEN 'OBSERVATION_POLICIES' THEN definition->>'observationPolicyKey'
        WHEN 'TARGETING_POLICIES' THEN definition->>'targetingPolicyKey'
        WHEN 'SELECTION_POLICIES' THEN definition->>'targetSelectionPolicyKey'
        WHEN 'DEFAULT_BINDINGS' THEN definition->>'bindingKey' WHEN 'FEEDBACK' THEN definition->>'feedbackKey' ELSE NULL END;
$$ LANGUAGE SQL IMMUTABLE;

CREATE FUNCTION guard_gameplay_rule_source() RETURNS TRIGGER AS $$
DECLARE v version%ROWTYPE; g game_design_gameplay_rule_genesis%ROWTYPE;
    h game_design_gameplay_rule_head%ROWTYPE; c game_design_draft_commit%ROWTYPE;
    a game_design_gameplay_rule_application%ROWTYPE; s game_design_gameplay_rule_snapshot%ROWTYPE;
    revision JSONB; payload JSONB; snapshot JSONB; prior JSONB; manifest JSONB;
    expected_entries JSONB; replacement JSONB; r game_design_gameplay_rule_revision%ROWTYPE;
    family TEXT; family_values JSONB; present BOOLEAN; actual_count BIGINT; declared_count BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'gameplay source history is immutable' USING ERRCODE = '23514'; END IF;
    SELECT owner.* INTO STRICT v FROM version owner JOIN game source
        ON source.id = owner.identity_source_game_row_id AND source.tenant_id = owner.identity_source_game_tenant_key
        AND source.tenant_id = owner.tenant_id AND source.canonical_tenant_id = owner.canonical_tenant_id
        AND source.tenant_identity_provenance_kind = owner.identity_source_provenance_kind
        AND source.tenant_identity_source_game_id = source.id AND source.tenant_identity_source_legacy_tenant_id = source.tenant_id
        WHERE owner.canonical_tenant_id = NEW.canonical_tenant_id AND owner.canonical_version_id = NEW.canonical_version_id FOR UPDATE OF owner;
    IF v.version_state <> 'DRAFT' OR v.is_script_only OR v.base_version_id IS NOT NULL OR v.script_patch_version IS NOT NULL THEN
        RAISE EXCEPTION 'gameplay source requires exact canonical full Draft' USING ERRCODE = '23514'; END IF;
    IF TG_TABLE_NAME = 'game_design_gameplay_rule_genesis' THEN
        manifest := NEW.inventory_json::JSONB;
        IF TG_OP <> 'INSERT' OR v.id <> NEW.version_id OR v.version_state_epoch <> 1
            OR NEW.creation_transaction_id <> pg_current_xact_id()::TEXT
            OR NOT EXISTS (SELECT 1 FROM game_design_realm_policy_version_insert w
                WHERE w.version_id = v.id AND w.creation_transaction_id = NEW.creation_transaction_id)
            OR EXISTS (SELECT 1 FROM game_design_draft_commit old_commit WHERE old_commit.canonical_tenant_id = NEW.canonical_tenant_id
                AND old_commit.canonical_version_id = NEW.canonical_version_id)
            OR NEW.target_json::JSONB IS DISTINCT FROM jsonb_build_object('canonicalTenantId', v.canonical_tenant_id::TEXT,
                'canonicalVersionId', v.canonical_version_id::TEXT, 'gameDesignVersionRowId', v.id,
                'gameDesignVersionTenantKey', v.tenant_id, 'sourceGameRowId', v.identity_source_game_row_id,
                'sourceGameTenantKey', v.identity_source_game_tenant_key, 'sourceProvenanceKind', v.identity_source_provenance_kind)
            OR manifest->>'schema' IS DISTINCT FROM 'gameplay-rule-manifest/v1'
            OR (SELECT count(*) FROM jsonb_object_keys(manifest)) <> 2
            OR (SELECT count(*) FROM jsonb_object_keys(manifest->'families')) <> cardinality(gameplay_rule_families()) THEN
            RAISE EXCEPTION 'gameplay genesis requires actual fresh complete owner inventory' USING ERRCODE = '23514'; END IF;
        FOREACH family IN ARRAY gameplay_rule_families() LOOP
            IF manifest->'families'->family IS DISTINCT FROM '[]'::JSONB THEN
                RAISE EXCEPTION 'every fresh gameplay family must be explicitly empty' USING ERRCODE = '23514'; END IF;
        END LOOP;
        RETURN NEW;
    END IF;
    SELECT * INTO STRICT g FROM game_design_gameplay_rule_genesis WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id;
    IF TG_TABLE_NAME = 'game_design_gameplay_rule_head' AND TG_OP = 'INSERT' THEN
        IF NEW.source_epoch <> '0' OR NEW.applied_commit_id IS NOT NULL OR NEW.visible_commit_id IS NOT NULL
            OR NEW.genesis_receipt_id <> g.receipt_id THEN RAISE EXCEPTION 'gameplay head requires exact fresh genesis' USING ERRCODE = '23514'; END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO STRICT h FROM game_design_gameplay_rule_head WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM game_design_authored_draft_publish_selection p WHERE p.canonical_tenant_id = NEW.canonical_tenant_id
        AND p.canonical_version_id = NEW.canonical_version_id) THEN
        RAISE EXCEPTION 'selected gameplay source is frozen' USING ERRCODE = '23514'; END IF;
    IF TG_TABLE_NAME = 'game_design_gameplay_rule_head' THEN
        IF NEW.canonical_tenant_id IS DISTINCT FROM OLD.canonical_tenant_id OR NEW.canonical_version_id IS DISTINCT FROM OLD.canonical_version_id
            OR NEW.genesis_receipt_id IS DISTINCT FROM OLD.genesis_receipt_id THEN RAISE EXCEPTION 'gameplay head identity immutable' USING ERRCODE = '23514'; END IF;
        IF NEW.source_epoch IS DISTINCT FROM OLD.source_epoch THEN
            SELECT * INTO STRICT a FROM game_design_gameplay_rule_application WHERE canonical_tenant_id = NEW.canonical_tenant_id
                AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.applied_commit_id;
            IF NEW.source_epoch::NUMERIC <> OLD.source_epoch::NUMERIC + 1 OR a.expected_epoch <> OLD.source_epoch
                OR NEW.visible_commit_id IS DISTINCT FROM OLD.visible_commit_id THEN RAISE EXCEPTION 'gameplay epoch mismatch' USING ERRCODE = '23514'; END IF;
        ELSIF NEW.applied_commit_id IS DISTINCT FROM OLD.applied_commit_id THEN RAISE EXCEPTION 'gameplay application pointer mismatch' USING ERRCODE = '23514'; END IF;
        IF NEW.visible_commit_id IS DISTINCT FROM OLD.visible_commit_id THEN
            SELECT * INTO STRICT s FROM game_design_gameplay_rule_snapshot WHERE canonical_tenant_id = NEW.canonical_tenant_id
                AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.visible_commit_id;
            IF s.snapshot_json::JSONB->>'sourceEpoch' IS DISTINCT FROM NEW.source_epoch
                OR s.snapshot_json::JSONB->>'inheritedCommitId' IS DISTINCT FROM coalesce(OLD.visible_commit_id::TEXT, '') THEN
                RAISE EXCEPTION 'gameplay visibility pointer mismatch' USING ERRCODE = '23514'; END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP <> 'INSERT' THEN RAISE EXCEPTION 'gameplay source history is immutable' USING ERRCODE = '23514'; END IF;
    SELECT * INTO STRICT c FROM game_design_draft_commit WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND request_id = NEW.request_id AND commit_id = NEW.commit_id;
    IF TG_TABLE_NAME = 'game_design_gameplay_rule_revision' THEN
        SELECT x INTO STRICT revision FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') x
            WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' AND x->>'revisionId' = NEW.revision_id::TEXT
                AND (x->>'revisionOrder')::INTEGER = NEW.revision_order;
        payload := (revision->>'payload')::JSONB;
        IF payload IS DISTINCT FROM NEW.payload_json::JSONB OR payload->>'revisionKind' IS DISTINCT FROM 'GAMEPLAY_RULE'
            OR payload->'schemaVersion' IS DISTINCT FROM '1'::JSONB OR payload->>'operation' IS DISTINCT FROM NEW.operation_kind
            OR payload->>'family' IS DISTINCT FROM NEW.family OR NOT NEW.family = ANY(gameplay_rule_families())
            OR (NEW.operation_kind = 'UPSERT' AND (gameplay_rule_definition_key(NEW.family, (payload->>'definitionJson')::JSONB) IS DISTINCT FROM NEW.definition_key
                OR (SELECT count(*) FROM jsonb_object_keys(payload)) <> 5))
            OR (NEW.operation_kind = 'DELETE' AND (payload->>'key' IS DISTINCT FROM NEW.definition_key
                OR (SELECT count(*) FROM jsonb_object_keys(payload)) <> 5)) THEN
            RAISE EXCEPTION 'gameplay revision differs from original complete binding' USING ERRCODE = '23514'; END IF;
        RETURN NEW;
    END IF;
    snapshot := NEW.snapshot_json::JSONB;
    IF snapshot->>'schema' IS DISTINCT FROM 'game-design-gameplay-rule-source-snapshot/v1'
        OR snapshot->>'bindingJson' IS DISTINCT FROM c.binding_json OR snapshot->>'bindingDigest' IS DISTINCT FROM c.input_digest
        OR snapshot->>'genesisReceiptId' IS DISTINCT FROM g.receipt_id::TEXT
        OR snapshot->>'inheritedCommitId' IS DISTINCT FROM coalesce(h.visible_commit_id::TEXT, '')
        OR jsonb_typeof(snapshot->'entries') IS DISTINCT FROM 'array'
        OR (SELECT count(*) FROM jsonb_object_keys(snapshot)) <> 8 THEN
        RAISE EXCEPTION 'gameplay snapshot exact binding missing' USING ERRCODE = '23514'; END IF;
    SELECT old_snapshot.snapshot_json::JSONB->'entries' INTO prior FROM game_design_gameplay_rule_snapshot old_snapshot
        WHERE old_snapshot.canonical_tenant_id = NEW.canonical_tenant_id AND old_snapshot.canonical_version_id = NEW.canonical_version_id
            AND old_snapshot.commit_id = h.visible_commit_id;
    IF h.visible_commit_id IS NOT NULL AND prior IS NULL THEN RAISE EXCEPTION 'gameplay predecessor unavailable' USING ERRCODE = '23514'; END IF;
    expected_entries := coalesce(prior, '[]'::JSONB);
    IF TG_TABLE_NAME = 'game_design_gameplay_rule_application' THEN
        IF NEW.expected_epoch IS DISTINCT FROM h.source_epoch OR snapshot->>'sourceEpoch' IS DISTINCT FROM (h.source_epoch::NUMERIC + 1)::TEXT
            OR NOT EXISTS (SELECT 1 FROM game_design_draft_commit_application_slot slot WHERE slot.canonical_tenant_id = NEW.canonical_tenant_id
                AND slot.canonical_version_id = NEW.canonical_version_id AND slot.request_id = NEW.request_id AND slot.commit_id = NEW.commit_id)
            OR NOT (c.binding_json::JSONB->'affectedUnits' @> jsonb_build_array(jsonb_build_object('owner', 'GAME_DESIGN_CONTROL_PLANE',
                'aggregateType', 'GAMEPLAY_RULE_SET', 'aggregateId', NEW.canonical_version_id::TEXT, 'scopeType', 'GAMEPLAY_RULE_SET',
                'scopeId', 'effective', 'expectedEpoch', NEW.expected_epoch))) THEN
            RAISE EXCEPTION 'gameplay application scope epoch mismatch' USING ERRCODE = '23514'; END IF;
        FOR r IN SELECT * FROM game_design_gameplay_rule_revision WHERE canonical_tenant_id = NEW.canonical_tenant_id
            AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id ORDER BY revision_order LOOP
            SELECT EXISTS (SELECT 1 FROM jsonb_array_elements(expected_entries) item WHERE item->>'family' = r.family
                AND gameplay_rule_definition_key(r.family, (item->>'definitionJson')::JSONB) = r.definition_key) INTO present;
            IF r.operation_kind = 'DELETE' THEN
                IF NOT present THEN RAISE EXCEPTION 'gameplay DELETE requires existing key' USING ERRCODE = '23514'; END IF;
                SELECT coalesce(jsonb_agg(item ORDER BY ordinal), '[]'::JSONB) INTO expected_entries
                    FROM jsonb_array_elements(expected_entries) WITH ORDINALITY x(item, ordinal)
                    WHERE NOT (item->>'family' = r.family AND gameplay_rule_definition_key(r.family, (item->>'definitionJson')::JSONB) = r.definition_key);
            ELSE
                replacement := jsonb_build_object('family', r.family, 'definitionJson', r.payload_json::JSONB->>'definitionJson',
                    'sourceBindingJson', c.binding_json, 'sourceBindingDigest', c.input_digest,
                    'revisionOrder', r.revision_order::TEXT, 'revisionId', r.revision_id::TEXT);
                IF present THEN
                    SELECT jsonb_agg(CASE WHEN item->>'family' = r.family AND gameplay_rule_definition_key(r.family, (item->>'definitionJson')::JSONB) = r.definition_key
                        THEN replacement ELSE item END ORDER BY ordinal) INTO expected_entries
                        FROM jsonb_array_elements(expected_entries) WITH ORDINALITY x(item, ordinal);
                ELSE expected_entries := expected_entries || jsonb_build_array(replacement); END IF;
            END IF;
        END LOOP;
        SELECT coalesce(jsonb_agg(item ORDER BY array_position(gameplay_rule_families(), item->>'family'), ordinal), '[]'::JSONB)
            INTO expected_entries FROM jsonb_array_elements(expected_entries) WITH ORDINALITY x(item, ordinal);
    ELSE
        SELECT * INTO a FROM game_design_gameplay_rule_application WHERE canonical_tenant_id = NEW.canonical_tenant_id
            AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
        SELECT count(*) INTO declared_count FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') x
            WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' AND (x->>'payload')::JSONB->>'revisionKind' = 'GAMEPLAY_RULE';
        IF (declared_count > 0) IS DISTINCT FROM (a.commit_id IS NOT NULL) OR snapshot->>'sourceEpoch' IS DISTINCT FROM h.source_epoch
            OR (a.commit_id IS NOT NULL AND NEW.snapshot_json IS DISTINCT FROM a.snapshot_json)
            OR NEW.snapshot_digest IS DISTINCT FROM 'sha256:' || encode(sha256(convert_to(NEW.snapshot_json, 'UTF8')), 'hex')
            OR c.workflow_state <> 'SYNCHRONIZED'
            OR NOT EXISTS (SELECT 1 FROM game_design_draft_commit_visibility visible WHERE visible.canonical_tenant_id = NEW.canonical_tenant_id
                AND visible.canonical_version_id = NEW.canonical_version_id AND visible.request_id = NEW.request_id
                AND visible.commit_id = NEW.commit_id AND visible.input_digest = c.input_digest) THEN
            RAISE EXCEPTION 'gameplay snapshot lacks synchronized complete owner source' USING ERRCODE = '23514'; END IF;
        IF a.commit_id IS NOT NULL THEN expected_entries := a.snapshot_json::JSONB->'entries'; END IF;
    END IF;
    IF snapshot->'entries' IS DISTINCT FROM expected_entries THEN RAISE EXCEPTION 'gameplay snapshot must replay exact original revisions' USING ERRCODE = '23514'; END IF;
    manifest := (snapshot->>'manifestJson')::JSONB;
    IF manifest->>'schema' IS DISTINCT FROM 'gameplay-rule-manifest/v1'
        OR (SELECT count(*) FROM jsonb_object_keys(manifest)) <> 2
        OR (SELECT count(*) FROM jsonb_object_keys(manifest->'families')) <> cardinality(gameplay_rule_families()) THEN
        RAISE EXCEPTION 'gameplay snapshot inventory is incomplete' USING ERRCODE = '23514'; END IF;
    FOREACH family IN ARRAY gameplay_rule_families() LOOP
        SELECT coalesce(jsonb_agg((item->>'definitionJson')::JSONB ORDER BY ordinal), '[]'::JSONB) INTO family_values
            FROM jsonb_array_elements(expected_entries) WITH ORDINALITY x(item, ordinal) WHERE item->>'family' = family;
        IF manifest->'families'->family IS DISTINCT FROM family_values THEN
            RAISE EXCEPTION 'gameplay manifest differs from its complete original source' USING ERRCODE = '23514'; END IF;
    END LOOP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION verify_gameplay_rule_application_commit() RETURNS TRIGGER AS $$
DECLARE c game_design_draft_commit%ROWTYPE; r game_design_draft_commit_owner_result%ROWTYPE;
    h game_design_gameplay_rule_head%ROWTYPE; expected BYTEA; bytes BYTEA; components BYTEA;
    command_bytes BYTEA; asset_bytes BYTEA; policy_bytes BYTEA; declared BIGINT; retained BIGINT;
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
    FOR kind IN SELECT DISTINCT (x->>'payload')::JSONB->>'revisionKind' FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') x
        WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' LOOP
        expected_scope := CASE kind WHEN 'COMMAND_DEFINITION' THEN 'COMMAND_DEFINITION_SET'
            WHEN 'ASSET_REFERENCE' THEN 'ASSET_REFERENCE_SET' WHEN 'REALM_ENTRY_POLICY' THEN 'REALM_ENTRY_POLICY_SET'
            WHEN 'GAMEPLAY_RULE' THEN 'GAMEPLAY_RULE_SET' ELSE NULL END;
        IF expected_scope IS NULL OR (kind = 'COMMAND_DEFINITION' AND command_bytes IS NULL)
            OR (kind = 'ASSET_REFERENCE' AND asset_bytes IS NULL) OR (kind = 'REALM_ENTRY_POLICY' AND policy_bytes IS NULL)
            OR (SELECT count(*) FROM jsonb_array_elements(c.binding_json::JSONB->'affectedUnits') x
                WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE' AND x->>'aggregateType' = expected_scope) <> 1 THEN
            RAISE EXCEPTION 'gameplay mixed application requires all declared sibling sources' USING ERRCODE = '23514'; END IF;
        scope_count := scope_count + 1;
    END LOOP;
    FOR unit IN SELECT x FROM jsonb_array_elements(c.binding_json::JSONB->'affectedUnits') x WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE'
        ORDER BY x->>'aggregateType', x->>'aggregateId', x->>'scopeType', x->>'scopeId' LOOP
        IF unit->>'aggregateType' NOT IN ('COMMAND_DEFINITION_SET', 'ASSET_REFERENCE_SET', 'REALM_ENTRY_POLICY_SET', 'GAMEPLAY_RULE_SET')
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

CREATE TRIGGER gameplay_rule_genesis_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_gameplay_rule_genesis FOR EACH ROW EXECUTE FUNCTION guard_gameplay_rule_source();
CREATE TRIGGER gameplay_rule_head_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_gameplay_rule_head FOR EACH ROW EXECUTE FUNCTION guard_gameplay_rule_source();
CREATE TRIGGER gameplay_rule_revision_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_gameplay_rule_revision FOR EACH ROW EXECUTE FUNCTION guard_gameplay_rule_source();
CREATE TRIGGER gameplay_rule_application_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_gameplay_rule_application FOR EACH ROW EXECUTE FUNCTION guard_gameplay_rule_source();
CREATE TRIGGER gameplay_rule_snapshot_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_gameplay_rule_snapshot FOR EACH ROW EXECUTE FUNCTION guard_gameplay_rule_source();
CREATE CONSTRAINT TRIGGER gameplay_rule_application_commit_guard AFTER INSERT ON game_design_gameplay_rule_application DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_gameplay_rule_application_commit();
CREATE FUNCTION deny_gameplay_rule_truncate() RETURNS TRIGGER AS $$
BEGIN RAISE EXCEPTION 'gameplay source history cannot be truncated' USING ERRCODE = '23514'; END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER gameplay_rule_genesis_no_truncate BEFORE TRUNCATE ON game_design_gameplay_rule_genesis EXECUTE FUNCTION deny_gameplay_rule_truncate();
CREATE TRIGGER gameplay_rule_head_no_truncate BEFORE TRUNCATE ON game_design_gameplay_rule_head EXECUTE FUNCTION deny_gameplay_rule_truncate();
CREATE TRIGGER gameplay_rule_revision_no_truncate BEFORE TRUNCATE ON game_design_gameplay_rule_revision EXECUTE FUNCTION deny_gameplay_rule_truncate();
CREATE TRIGGER gameplay_rule_application_no_truncate BEFORE TRUNCATE ON game_design_gameplay_rule_application EXECUTE FUNCTION deny_gameplay_rule_truncate();
CREATE TRIGGER gameplay_rule_snapshot_no_truncate BEFORE TRUNCATE ON game_design_gameplay_rule_snapshot EXECUTE FUNCTION deny_gameplay_rule_truncate();
CREATE OR REPLACE FUNCTION verify_ordinary_asset_application_commit() RETURNS TRIGGER AS $$
DECLARE c game_design_draft_commit%ROWTYPE; r game_design_draft_commit_owner_result%ROWTYPE;
    h game_design_asset_source_head%ROWTYPE; expected BYTEA; bytes BYTEA; declared BIGINT; retained BIGINT;
    components BYTEA; command_bytes BYTEA; policy_bytes BYTEA; gameplay_bytes BYTEA; epochs JSONB; unit JSONB;
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
    SELECT result_bytes INTO policy_bytes FROM game_design_realm_policy_application
        WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') x
        WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE'
            AND coalesce((x->>'payload')::JSONB->>'revisionKind', '') NOT IN ('COMMAND_DEFINITION', 'REALM_ENTRY_POLICY', 'ASSET_REFERENCE', 'GAMEPLAY_RULE'))
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
        IF unit->>'aggregateType' NOT IN ('COMMAND_DEFINITION_SET', 'ASSET_REFERENCE_SET', 'REALM_ENTRY_POLICY_SET', 'GAMEPLAY_RULE_SET')
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
        OR jsonb_array_length(epochs) <> (1 + CASE WHEN command_bytes IS NULL THEN 0 ELSE 1 END + CASE WHEN policy_bytes IS NULL THEN 0 ELSE 1 END + CASE WHEN gameplay_bytes IS NULL THEN 0 ELSE 1 END) THEN
        RAISE EXCEPTION 'ordinary combined outcome must contain exact tagged components and complete epochs' USING ERRCODE = '23514'; END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
-- [jooq ignore stop]
