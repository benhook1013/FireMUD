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
