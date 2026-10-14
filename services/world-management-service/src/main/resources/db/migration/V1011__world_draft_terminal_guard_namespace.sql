-- V36's terminal guard remains installed for retained rows and trigger bindings. Replace only its
-- trigger function body so the PL/pgSQL variable cannot collide with the terminal table column.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION world_reject_aborted_draft_attempt() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    v_request_identity UUID;
    v_commit_identity UUID;
    v_target_tenant UUID;
    v_target_version UUID;
    v_target_namespace TEXT;
BEGIN
    IF TG_TABLE_NAME = 'world_region_draft_execution_manifest' THEN
        v_request_identity := (NEW.complete_binding->>'requestId')::UUID;
        v_commit_identity := (NEW.complete_binding->>'commitId')::UUID;
        v_target_tenant := (NEW.complete_binding->>'canonicalTenantId')::UUID;
        v_target_version := (NEW.complete_binding->>'canonicalVersionId')::UUID;
        v_target_namespace := NEW.owner_binding->>'targetNamespace';
    ELSE
        v_request_identity := NEW.request_id;
        v_commit_identity := NEW.commit_id;
        v_target_tenant := NEW.canonical_tenant_id;
        v_target_version := NEW.canonical_version_id;
        v_target_namespace := NEW.target_namespace;
    END IF;
    IF EXISTS (
        SELECT 1 FROM "${serviceSchema}".world_draft_terminal_outcome terminal
        WHERE terminal.target_namespace = v_target_namespace
          AND terminal.canonical_tenant_id = v_target_tenant
          AND terminal.canonical_version_id = v_target_version
          AND (terminal.request_id = v_request_identity
              OR terminal.commit_id = v_commit_identity)
    ) THEN
        RAISE EXCEPTION 'World Draft operation has immutable definitive no-commit evidence'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION world_reject_aborted_draft_attempt() FROM PUBLIC;
-- [jooq ignore stop]
