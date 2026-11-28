-- [jooq ignore start]
-- Use the same immutable transition receipt that normal final admission stores before
-- settling the execution. An orphaned terminal execution must not leave transfer custody.
CREATE OR REPLACE FUNCTION reject_gs_capacity_close_after_final_receipt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status='AUTHORIZED' AND NEW.status='CLOSED' AND EXISTS (
        SELECT 1 FROM game_session_canonical_binding_transition t
        WHERE t.transition_id=OLD.transition_id
          AND t.original_new_final_session_sha256 IS NOT NULL
          AND t.original_new_capacity_execution_digest=OLD.execution_digest) THEN
        RAISE EXCEPTION 'Original NEW execution cannot close after retained final receipt' USING ERRCODE='23514';
    END IF;
    IF OLD.status='AUTHORIZED' AND NEW.status='COMMITTED' AND NOT EXISTS (
        SELECT 1 FROM game_session_canonical_binding_transition t
        WHERE t.transition_id=OLD.transition_id
          AND t.tenant_id=OLD.tenant_id
          AND t.admission_request_id=OLD.request_id
          AND t.transition_id=OLD.admission_id
          AND t.status='COMMITTED'
          AND t.original_new_final_session_sha256 IS NOT NULL
          AND t.original_new_capacity_execution_digest=OLD.execution_digest) THEN
        RAISE EXCEPTION 'Original NEW execution requires its exact committed final receipt' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

DO $retained$
BEGIN
    IF EXISTS (
        SELECT 1 FROM game_session_original_new_capacity_execution e
        WHERE e.status='COMMITTED' AND NOT EXISTS (
            SELECT 1 FROM game_session_canonical_binding_transition t
            WHERE t.transition_id=e.transition_id
              AND t.tenant_id=e.tenant_id
              AND t.admission_request_id=e.request_id
              AND t.transition_id=e.admission_id
              AND t.status='COMMITTED'
              AND t.original_new_final_session_sha256 IS NOT NULL
              AND t.original_new_capacity_execution_digest=e.execution_digest)) THEN
        RAISE EXCEPTION 'Retained original NEW execution is missing its exact committed final receipt' USING ERRCODE='23514';
    END IF;
END;
$retained$;

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1065.0.11.1'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1065.0.11.1'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.11.2__require_exact_capacity_commit_receipt.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.11.2'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1065.0.11.1'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.11.2''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1065.0.11.1','V1065.0.11.1__retain_typed_capacity_evidence_denial.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.11.2'',''V1065.0.11.2__require_exact_capacity_commit_receipt.sql''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog marker'; END IF;
    EXECUTE replace(definition,original,replacement);
END;
$migration$;

INSERT INTO game_session_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT epoch,bytes,'sha256:'||encode(sha256(bytes),'hex') FROM (
    SELECT epoch,gs_runtime_family_catalog_bytes(epoch) AS bytes FROM (
        SELECT coalesce(max(catalog_epoch),0)+1 AS epoch FROM game_session_runtime_family_catalog_epoch
    ) next_epoch
) seal;
-- [jooq ignore stop]
