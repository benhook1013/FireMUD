-- Policy source heads have two distinct legal transitions: owner application stages the next
-- epoch while retaining the visible pointer, then synchronized capture advances only the pointer.
-- [jooq ignore start]
CREATE FUNCTION enforce_realm_policy_source_head_transition() RETURNS trigger AS $$
BEGIN
    IF NEW.source_epoch IS DISTINCT FROM OLD.source_epoch THEN
        IF NEW.source_epoch::NUMERIC <> OLD.source_epoch::NUMERIC + 1
            OR NEW.visible_commit_id IS DISTINCT FROM OLD.visible_commit_id
            OR NOT EXISTS (
                SELECT 1
                FROM game_design_realm_policy_application a
                JOIN game_design_draft_commit c
                  ON c.canonical_tenant_id = a.canonical_tenant_id
                 AND c.canonical_version_id = a.canonical_version_id
                 AND c.request_id = a.request_id
                 AND c.commit_id = a.commit_id
                WHERE a.canonical_tenant_id = NEW.canonical_tenant_id
                  AND a.canonical_version_id = NEW.canonical_version_id
                  AND a.expected_epoch = OLD.source_epoch
                  AND a.inherited_commit_id IS NOT DISTINCT FROM OLD.visible_commit_id
                  AND a.genesis_receipt_id IS NOT DISTINCT FROM OLD.genesis_receipt_id
                  AND a.snapshot_json::JSONB->>'sourceEpoch' = NEW.source_epoch
                  AND a.snapshot_json::JSONB->>'bindingJson' = c.binding_json
                  AND a.snapshot_json::JSONB->>'bindingDigest' = c.input_digest
                  AND (c.binding_json::JSONB->'requiredOwners') @> '["GAME_DESIGN_CONTROL_PLANE"]'::JSONB
                  AND (
                      SELECT count(*)
                      FROM jsonb_array_elements(c.binding_json::JSONB->'affectedUnits') AS units(unit)
                      WHERE unit->>'owner' = 'GAME_DESIGN_CONTROL_PLANE'
                        AND unit->>'aggregateType' = 'REALM_ENTRY_POLICY_SET'
                  ) = 1
                  AND EXISTS (
                      SELECT 1
                      FROM jsonb_array_elements(c.binding_json::JSONB->'affectedUnits') AS units(unit)
                      WHERE unit->>'owner' = 'GAME_DESIGN_CONTROL_PLANE'
                        AND unit->>'aggregateType' = 'REALM_ENTRY_POLICY_SET'
                        AND unit->>'aggregateId' = NEW.canonical_version_id::TEXT
                        AND unit->>'scopeType' = 'REALM_ENTRY_POLICY_SET'
                        AND unit->>'scopeId' = 'effective'
                        AND unit->>'expectedEpoch' = OLD.source_epoch
                  )
                  AND EXISTS (
                      SELECT 1
                      FROM jsonb_array_elements(c.binding_json::JSONB->'revisions') AS revisions(revision)
                      WHERE revision->>'owner' = 'GAME_DESIGN_CONTROL_PLANE'
                        AND ((revision->>'payload')::JSONB)->>'revisionKind' = 'REALM_ENTRY_POLICY'
                  )
            ) THEN
            RAISE EXCEPTION 'realm policy source epoch must advance through its exact owner application'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    IF NEW.visible_commit_id IS DISTINCT FROM OLD.visible_commit_id THEN
        IF NEW.source_epoch IS DISTINCT FROM OLD.source_epoch
            OR NOT EXISTS (
                SELECT 1
                FROM game_design_realm_policy_snapshot s
                JOIN game_design_draft_commit_visibility_fence f
                  ON f.canonical_tenant_id = s.canonical_tenant_id
                 AND f.canonical_version_id = s.canonical_version_id
                 AND f.request_id = s.request_id
                 AND f.commit_id = s.commit_id
                JOIN game_design_draft_commit_visibility v
                  ON v.canonical_tenant_id = f.canonical_tenant_id
                 AND v.canonical_version_id = f.canonical_version_id
                 AND v.request_id = f.request_id
                 AND v.commit_id = f.commit_id
                JOIN game_design_draft_commit c
                  ON c.canonical_tenant_id = f.canonical_tenant_id
                 AND c.canonical_version_id = f.canonical_version_id
                 AND c.request_id = f.request_id
                 AND c.commit_id = f.commit_id
                WHERE s.canonical_tenant_id = NEW.canonical_tenant_id
                  AND s.canonical_version_id = NEW.canonical_version_id
                  AND s.commit_id = NEW.visible_commit_id
                  AND s.inherited_commit_id IS NOT DISTINCT FROM OLD.visible_commit_id
                  AND s.snapshot_json::JSONB->>'sourceEpoch' = NEW.source_epoch
                  AND s.snapshot_json::JSONB->>'bindingJson' = c.binding_json
                  AND s.snapshot_json::JSONB->>'bindingDigest' = c.input_digest
                  AND f.input_digest = c.input_digest
                  AND v.input_digest = f.input_digest
                  AND c.workflow_state = 'SYNCHRONIZED'
            ) THEN
            RAISE EXCEPTION 'realm policy source pointer must advance to its exact current synchronized snapshot'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER realm_policy_source_head_transition_guard
    BEFORE UPDATE ON game_design_realm_policy_source
    FOR EACH ROW EXECUTE FUNCTION enforce_realm_policy_source_head_transition();
-- [jooq ignore stop]
