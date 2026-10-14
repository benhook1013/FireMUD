-- Preserve the V1035 deferred settlement contract when a caller omits the World schema from
-- search_path. CREATE OR REPLACE keeps the original function identity and grants; this remains an
-- invoker trigger and performs no data rewrite.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION world_require_canonical_instance_execution_terminal()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog
AS $$
DECLARE
    final_state TEXT;
BEGIN
    SELECT operation_state INTO final_state
    FROM "${serviceSchema}".world_canonical_instance_execution
    WHERE target_namespace = NEW.target_namespace
        AND canonical_tenant_id = NEW.canonical_tenant_id
        AND control_plane_request_id = NEW.control_plane_request_id;
    IF final_state IS NULL OR final_state = 'EXECUTING' THEN
        RAISE EXCEPTION 'World EXECUTING state cannot commit without exact terminal settlement'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
-- [jooq ignore stop]
