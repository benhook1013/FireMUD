-- Extend the typed owner declaration from Automation to the approved empty-only Entity content.
-- Existing Automation records and payloads are retained without rewriting their original bytes.
-- [jooq ignore start]
DO $$
DECLARE owner_constraint TEXT;
BEGIN
    SELECT conname INTO STRICT owner_constraint
        FROM pg_constraint
        WHERE conrelid = 'game_design_template_config_owner_source_inventory_declaration'::REGCLASS
          AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%AUTOMATION_SCRIPTING%';
    EXECUTE format(
        'ALTER TABLE game_design_template_config_owner_source_inventory_declaration DROP CONSTRAINT %I',
        owner_constraint);
END;
$$;

CREATE FUNCTION entity_authored_source_inventory_supported(value JSONB) RETURNS BOOLEAN AS $$
DECLARE family_name TEXT;
BEGIN
    IF jsonb_typeof(value) IS DISTINCT FROM 'object' THEN
        RETURN FALSE;
    END IF;
    IF (SELECT count(*) FROM jsonb_object_keys(value)) <> 3 THEN
        RETURN FALSE;
    END IF;
    IF value->>'schema' IS DISTINCT FROM 'entity-authored-source-inventory/v1'
        OR jsonb_typeof(value->'schema') IS DISTINCT FROM 'string'
        OR value->>'equipmentApplicability' IS DISTINCT FROM 'NOT_APPLICABLE'
        OR jsonb_typeof(value->'equipmentApplicability') IS DISTINCT FROM 'string'
        OR jsonb_typeof(value->'families') IS DISTINCT FROM 'object' THEN
        RETURN FALSE;
    END IF;
    IF (SELECT count(*) FROM jsonb_object_keys(value->'families')) <> 23 THEN
        RETURN FALSE;
    END IF;
    FOREACH family_name IN ARRAY ARRAY[
        'ACTOR_BODY_LAYOUT_ASSIGNMENTS',
        'ARCHETYPE_ASSIGNMENTS',
        'ARCHETYPE_CONSTRAINTS',
        'ARCHETYPE_ROOTS',
        'BALANCE_CURVE_ATTACHMENTS',
        'BALANCE_CURVE_ROOTS',
        'BODY_LAYOUT_MEMBERSHIPS',
        'BODY_LAYOUT_ROOTS',
        'CRAFTING_INGREDIENT_BINDINGS',
        'CRAFTING_RECIPE_RESULT_BINDINGS',
        'CRAFTING_RECIPE_ROOTS',
        'EQUIPMENT_ATTACHMENT_RULES',
        'EQUIPMENT_CAPABILITIES',
        'EQUIPMENT_COMPATIBILITY_RULES',
        'EQUIPMENT_OCCUPANCY_RULES',
        'EQUIPMENT_SLOT_GROUPS',
        'EQUIPMENT_SLOT_ROOTS',
        'INBOUND_LOOT_BINDINGS',
        'ITEM_TEMPLATE_ROOTS',
        'LOOT_ITEM_MAPPINGS',
        'LOOT_TABLE_ROOTS',
        'NPC_TEMPLATE_ROOTS',
        'OTHER_ACTOR_TEMPLATE_ROOTS'
    ] LOOP
        IF jsonb_typeof(value->'families'->family_name) IS DISTINCT FROM 'array' THEN
            RETURN FALSE;
        END IF;
        IF jsonb_array_length(value->'families'->family_name) <> 0 THEN
            RETURN FALSE;
        END IF;
    END LOOP;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql IMMUTABLE;
-- [jooq ignore stop]

ALTER TABLE game_design_template_config_owner_source_inventory_declaration
    ADD CONSTRAINT game_design_template_config_owner_source_inventory_owner_supported_check
    CHECK (owner IN ('AUTOMATION_SCRIPTING', 'ENTITY_MANAGEMENT'));

-- [jooq ignore start]
CREATE OR REPLACE FUNCTION guard_template_config_owner_source_inventory_declaration() RETURNS TRIGGER AS $$
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
        RAISE EXCEPTION 'template owner inventory requires exact supported owner declaration' USING ERRCODE = '23514';
    END IF;
    IF NOT template_config_unique_json((revision->>'payload')::JSON)
        OR (SELECT count(*) FROM jsonb_object_keys(payload)) <> 5
        OR payload->>'revisionKind' IS DISTINCT FROM 'TEMPLATE_CONFIG'
        OR payload->>'operation' IS DISTINCT FROM 'DECLARE_OWNER_SOURCE_INVENTORY'
        OR NEW.owner IS DISTINCT FROM payload->>'owner'
        OR jsonb_typeof(payload->'inventory') IS DISTINCT FROM 'object'
        OR NEW.inventory_json::JSONB IS DISTINCT FROM payload->'inventory'
        OR NOT template_config_unique_json(NEW.inventory_json::JSON)
        OR NEW.payload_json IS DISTINCT FROM revision->>'payload'
        OR NEW.payload_json::JSONB IS DISTINCT FROM payload
        OR NEW.owner NOT IN ('AUTOMATION_SCRIPTING', 'ENTITY_MANAGEMENT')
        OR (NEW.owner = 'AUTOMATION_SCRIPTING'
            AND (payload->'schemaVersion' IS DISTINCT FROM '2'::JSONB
                OR payload->>'owner' IS DISTINCT FROM 'AUTOMATION_SCRIPTING'
                OR NOT automation_authored_source_inventory_supported(NEW.inventory_json::JSONB)))
        OR (NEW.owner = 'ENTITY_MANAGEMENT'
            AND (payload->'schemaVersion' IS DISTINCT FROM '3'::JSONB
                OR payload->>'owner' IS DISTINCT FROM 'ENTITY_MANAGEMENT'
                OR NOT entity_authored_source_inventory_supported(NEW.inventory_json::JSONB))) THEN
        RAISE EXCEPTION 'template owner inventory requires exact supported owner declaration' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
-- [jooq ignore stop]

-- Keep the deferred atomic Game Design result check, but count owner-inventory operations in their
-- own exact retained table instead of requiring a fabricated template row revision.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION verify_template_config_application_commit() RETURNS TRIGGER AS $$
DECLARE branding_bytes BYTEA; c game_design_draft_commit%ROWTYPE; r game_design_draft_commit_owner_result%ROWTYPE;
    h game_design_template_config_source_head%ROWTYPE; expected BYTEA; bytes BYTEA; components BYTEA;
    gameplay_bytes BYTEA; command_bytes BYTEA; asset_bytes BYTEA; policy_bytes BYTEA;
    declared BIGINT; retained BIGINT; declared_inventory BIGINT; retained_inventory BIGINT;
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
    SELECT count(*) INTO declared_inventory FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') x
        WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE'
          AND (x->>'payload')::JSONB->>'revisionKind' = 'TEMPLATE_CONFIG'
          AND (x->>'payload')::JSONB->>'operation' = 'DECLARE_OWNER_SOURCE_INVENTORY';
    SELECT count(*) INTO retained FROM game_design_template_config_source_revision WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND request_id = NEW.request_id AND commit_id = NEW.commit_id;
    SELECT count(*) INTO retained_inventory FROM game_design_template_config_owner_source_inventory_declaration
        WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id
          AND request_id = NEW.request_id AND commit_id = NEW.commit_id;
    bytes := convert_to('game-design-template-config-source-application/v1', 'UTF8'); expected := int4send(octet_length(bytes)) || bytes;
    bytes := convert_to(NEW.expected_epoch, 'UTF8'); expected := expected || int4send(octet_length(bytes)) || bytes;
    bytes := convert_to(NEW.snapshot_json, 'UTF8'); expected := expected || int4send(octet_length(bytes)) || bytes;
    IF declared = 0 OR declared <> retained + retained_inventory OR declared_inventory <> retained_inventory
        OR NEW.result_bytes IS DISTINCT FROM expected OR r.status <> 'APPLIED'
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
-- [jooq ignore stop]
