-- Retain the precise outcome of an authenticated Account denial.
-- Untyped historical receipts remain quota denials and need no row rewrite.
ALTER TABLE game_session_capacity_evidence_charge
    ADD COLUMN account_denial_kind VARCHAR(40);

ALTER TABLE game_session_capacity_evidence_charge
    ADD CONSTRAINT gs_capacity_evidence_charge_account_denial_kind CHECK (
        account_denial_kind IS NULL OR (
            account_denial_id IS NOT NULL
            AND account_denial_kind IN ('CAPACITY_QUOTA_EXCEEDED', 'CAPACITY_EVALUATION_EXPIRED')
        )
    );

-- [jooq ignore start]
CREATE OR REPLACE FUNCTION settle_gs_capacity_evidence_charge() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'UPDATE' OR current_setting('transaction_isolation') <> 'read committed'
        OR OLD.account_denial_id IS NOT NULL OR NEW.account_denial_id IS NULL
        OR NEW.account_denial_kind IS NULL
        OR (to_jsonb(NEW) - 'account_denial_id' - 'account_denial_kind')
            IS DISTINCT FROM (to_jsonb(OLD) - 'account_denial_id' - 'account_denial_kind') THEN
        RAISE EXCEPTION 'Only exact original NEW capacity denial settlement may change the charge'
            USING ERRCODE='23514';
    END IF;
    PERFORM 1 FROM game_session_capacity_evidence_budget WHERE tenant_id=OLD.tenant_id FOR UPDATE;
    IF NOT FOUND OR EXISTS (
        SELECT 1 FROM game_session_original_new_capacity_execution
        WHERE admission_attempt_id=OLD.admission_attempt_id
    ) THEN
        RAISE EXCEPTION 'Execution custody prevents capacity evidence settlement' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1065.0.11'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1065.0.11'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.11.1__retain_typed_capacity_evidence_denial.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.11.1'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1065.0.11'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.11.1''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1065.0.11','V1065.0.11__bind_fence_receipt_dispatch_incarnation.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.11.1'',''V1065.0.11.1__retain_typed_capacity_evidence_denial.sql''';
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
