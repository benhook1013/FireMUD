-- Correct verifier diagnostics while retaining the original migration history.
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
        RAISE EXCEPTION 'template config application requires exact atomic owner result' USING ERRCODE = '23514'; END IF;
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
            RAISE EXCEPTION 'template config mixed application requires all declared sibling sources' USING ERRCODE = '23514'; END IF;
        scope_count := scope_count + 1;
    END LOOP;
    FOR unit IN SELECT x FROM jsonb_array_elements(c.binding_json::JSONB->'affectedUnits') x WHERE x->>'owner' = 'GAME_DESIGN_CONTROL_PLANE'
        ORDER BY x->>'aggregateType', x->>'aggregateId', x->>'scopeType', x->>'scopeId' LOOP
        IF unit->>'aggregateType' NOT IN ('COMMAND_DEFINITION_SET', 'ASSET_REFERENCE_SET', 'REALM_ENTRY_POLICY_SET', 'GAMEPLAY_RULE_SET', 'TEMPLATE_CONFIG_SET', 'BRANDING_ASSET_REFERENCE_SET')
            OR unit->>'aggregateType' IS DISTINCT FROM unit->>'scopeType' OR unit->>'scopeId' IS DISTINCT FROM 'effective'
            OR unit->>'aggregateId' IS DISTINCT FROM NEW.canonical_version_id::TEXT THEN
            RAISE EXCEPTION 'template config combined scope mismatch' USING ERRCODE = '23514'; END IF;
        epochs := epochs || jsonb_build_array((unit - 'owner') || jsonb_build_object('resultingEpoch', ((unit->>'expectedEpoch')::NUMERIC + 1)::TEXT));
    END LOOP;
    IF jsonb_array_length(epochs) <> scope_count THEN RAISE EXCEPTION 'template config scope vector incomplete' USING ERRCODE = '23514'; END IF;
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
        RAISE EXCEPTION 'template config owner result must contain exact complete tagged source bytes' USING ERRCODE = '23514'; END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
-- [jooq ignore stop]
