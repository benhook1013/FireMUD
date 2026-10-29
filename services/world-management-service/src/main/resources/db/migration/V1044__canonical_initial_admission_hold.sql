-- Canonical initial-admission holds fence every lifecycle mutation of their actual World row.
-- Legacy numeric rows remain unmapped and continue through their existing lifecycle checks.
-- [jooq ignore start]
CREATE FUNCTION world_guard_canonical_initial_admission_hold_lifecycle()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    IF (OLD.status IS DISTINCT FROM NEW.status
            OR OLD.lifecycle_epoch IS DISTINCT FROM NEW.lifecycle_epoch)
        AND EXISTS (
            SELECT 1
            FROM "${serviceSchema}".initial_admission_bind_hold h
            WHERE h.tenant_id = OLD.tenant_id
                AND h.game_instance_id = OLD.game_instance_id
                AND h.canonical_request_bytes IS NOT NULL
                AND h.status IN ('PENDING', 'RECONCILIATION_REQUIRED')
        ) THEN
        RAISE EXCEPTION 'World lifecycle is fenced by a canonical initial-admission hold'
            USING ERRCODE = '40001';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER aaa_world_canonical_initial_admission_hold_lifecycle
    BEFORE UPDATE OF status, lifecycle_epoch ON world_instance
    FOR EACH ROW EXECUTE FUNCTION world_guard_canonical_initial_admission_hold_lifecycle();

REVOKE ALL ON FUNCTION world_guard_canonical_initial_admission_hold_lifecycle() FROM PUBLIC;
-- [jooq ignore stop]
