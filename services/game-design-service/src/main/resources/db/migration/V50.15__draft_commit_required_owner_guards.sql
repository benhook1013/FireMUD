-- Validate owner-set shape only for new bindings. Retained commits receive no inferred owners or rewrite.
-- [jooq ignore start]
CREATE FUNCTION enforce_gd_draft_commit_required_owners() RETURNS trigger AS $$
DECLARE
    required_owners JSONB;
BEGIN
    required_owners := NEW.binding_json::JSONB->'requiredOwners';
    IF jsonb_typeof(required_owners) IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'Draft commit requiredOwners must be a non-empty array of distinct known owner strings'
            USING ERRCODE = 'check_violation';
    END IF;
    IF jsonb_array_length(required_owners) = 0 THEN
        RAISE EXCEPTION 'Draft commit requiredOwners must be a non-empty array of distinct known owner strings'
            USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM jsonb_array_elements(required_owners) AS required(owner)
        WHERE jsonb_typeof(required.owner) IS DISTINCT FROM 'string'
            OR required.owner #>> '{}' NOT IN (
                'WORLD_MANAGEMENT',
                'ENTITY_MANAGEMENT',
                'GAME_LOGIC',
                'AUTOMATION_SCRIPTING',
                'GAME_DESIGN_CONTROL_PLANE'
            )
    ) OR EXISTS (
        SELECT 1
        FROM jsonb_array_elements(required_owners) AS required(owner)
        GROUP BY required.owner
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Draft commit requiredOwners must be a non-empty array of distinct known owner strings'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_draft_commit_required_owners
    BEFORE INSERT ON game_design_draft_commit
    FOR EACH ROW
    EXECUTE FUNCTION enforce_gd_draft_commit_required_owners();

CREATE OR REPLACE FUNCTION enforce_gd_draft_commit_owner_result() RETURNS trigger AS $$
DECLARE
    parent_record RECORD;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Game Design Draft owner results are retained'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT commit_id, input_digest, binding_json
      INTO parent_record
    FROM game_design_draft_commit
    WHERE canonical_tenant_id = NEW.canonical_tenant_id
      AND canonical_version_id = NEW.canonical_version_id
      AND request_id = NEW.request_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Game Design Draft owner result has no exact commit binding'
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NOT COALESCE((parent_record.binding_json::jsonb -> 'requiredOwners') ? NEW.owner, FALSE) THEN
        RAISE EXCEPTION 'Game Design Draft owner result is outside the bound required owner set'
            USING ERRCODE = 'check_violation';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'NOT_ATTEMPTED'
            OR NEW.result_commit_id IS NOT NULL
            OR NEW.result_binding_digest IS NOT NULL
            OR NEW.result_identity IS NOT NULL
            OR NEW.result_bytes IS NOT NULL
            OR NEW.applied_units_json IS NOT NULL THEN
            RAISE EXCEPTION 'Draft commit owner result must begin NOT_ATTEMPTED without evidence'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.canonical_tenant_id IS DISTINCT FROM OLD.canonical_tenant_id
        OR NEW.canonical_version_id IS DISTINCT FROM OLD.canonical_version_id
        OR NEW.request_id IS DISTINCT FROM OLD.request_id
        OR NEW.owner IS DISTINCT FROM OLD.owner
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Game Design Draft owner result identity is immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.status = OLD.status THEN
        IF NEW.result_commit_id IS DISTINCT FROM OLD.result_commit_id
            OR NEW.result_binding_digest IS DISTINCT FROM OLD.result_binding_digest
            OR NEW.result_identity IS DISTINCT FROM OLD.result_identity
            OR NEW.result_bytes IS DISTINCT FROM OLD.result_bytes
            OR NEW.applied_units_json IS DISTINCT FROM OLD.applied_units_json
            OR NEW.updated_at IS DISTINCT FROM OLD.updated_at THEN
            RAISE EXCEPTION 'Exact Draft owner result retries do not rewrite persisted evidence'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NOT (
        (OLD.status = 'NOT_ATTEMPTED' AND NEW.status = 'IN_PROGRESS')
        OR (OLD.status = 'IN_PROGRESS' AND NEW.status IN ('APPLIED', 'REJECTED', 'UNKNOWN'))
        OR (OLD.status = 'UNKNOWN' AND NEW.status IN ('APPLIED', 'REJECTED'))
    ) THEN
        RAISE EXCEPTION 'Invalid Game Design Draft owner result transition'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.status IN ('APPLIED', 'REJECTED')
        AND (NEW.result_commit_id IS DISTINCT FROM parent_record.commit_id
            OR NEW.result_binding_digest IS DISTINCT FROM parent_record.input_digest) THEN
        RAISE EXCEPTION 'Game Design Draft owner result does not match its exact commit binding'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
-- [jooq ignore stop]
