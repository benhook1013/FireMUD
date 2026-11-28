-- An Account denial frees the active execution reservation. Exact retained history stays bounded.
ALTER TABLE game_session_capacity_evidence_budget
    ADD COLUMN maximum_retained_records INTEGER CHECK (maximum_retained_records >= maximum_records);
ALTER TABLE game_session_capacity_evidence_charge
    ADD COLUMN account_denial_id UUID UNIQUE CHECK (account_denial_id <> '00000000-0000-0000-0000-000000000000');
CREATE INDEX gs_capacity_evidence_charge_active
    ON game_session_capacity_evidence_charge(tenant_id, admission_attempt_id)
    WHERE account_denial_id IS NULL;

-- [jooq ignore start]
CREATE OR REPLACE FUNCTION guard_gs_capacity_evidence_charge() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE limits game_session_capacity_evidence_budget%ROWTYPE; used_records BIGINT; record_limit BIGINT;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed'
        OR NEW.reserved_binding_bytes IS DISTINCT FROM 1048576 THEN
        RAISE EXCEPTION 'Capacity evidence charge requires READ COMMITTED and the fixed record reservation' USING ERRCODE='23514';
    END IF;
    SELECT * INTO limits FROM game_session_capacity_evidence_budget
        WHERE tenant_id=NEW.tenant_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Capacity evidence budget is unavailable' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM game_session_canonical_binding_transition t
        WHERE t.transition_id=NEW.admission_id AND t.tenant_id=NEW.tenant_id
            AND t.status='PROVISIONAL' AND t.original_new_candidate_digest=NEW.candidate_digest)
        OR EXISTS (SELECT 1 FROM game_session_original_new_capacity_execution e
        WHERE e.tenant_id=NEW.tenant_id AND NOT EXISTS (
            SELECT 1 FROM game_session_capacity_evidence_charge c
            WHERE c.admission_attempt_id=e.admission_attempt_id)) THEN
        RAISE EXCEPTION 'Capacity evidence source or predecessor coverage is unavailable'
            USING ERRCODE='23514';
    END IF;
    record_limit := least(limits.maximum_records, limits.maximum_binding_bytes / NEW.reserved_binding_bytes);
    SELECT count(*) INTO used_records FROM (
        SELECT 1 FROM game_session_capacity_evidence_charge
        WHERE tenant_id=NEW.tenant_id AND account_denial_id IS NULL LIMIT record_limit
    ) retained;
    IF used_records >= record_limit THEN
        RAISE EXCEPTION 'Capacity evidence budget is exhausted' USING ERRCODE='23514';
    END IF;
    record_limit := coalesce(limits.maximum_retained_records, limits.maximum_records);
    SELECT count(*) INTO used_records FROM (
        SELECT 1 FROM game_session_capacity_evidence_charge
        WHERE tenant_id=NEW.tenant_id LIMIT record_limit
    ) retained;
    IF used_records >= record_limit OR NEW.account_denial_id IS NOT NULL THEN
        RAISE EXCEPTION 'Retained capacity evidence history is exhausted or invalid' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE OR REPLACE FUNCTION require_gs_capacity_evidence_charge() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Capacity evidence dispatch requires READ COMMITTED' USING ERRCODE='23514';
    END IF;
    PERFORM 1 FROM game_session_capacity_evidence_budget WHERE tenant_id=NEW.tenant_id FOR UPDATE;
    IF NOT FOUND OR NOT EXISTS (
        SELECT 1 FROM game_session_capacity_evidence_charge c
        JOIN game_session_canonical_tenant_gameplay_tag t ON t.tenant_id=c.tenant_id
        WHERE c.admission_attempt_id=NEW.admission_attempt_id AND c.tenant_id=NEW.tenant_id
            AND c.request_id=NEW.request_id AND c.admission_id=NEW.admission_id
            AND NEW.transition_id=c.admission_id AND c.capacity_rearm_fence=NEW.capacity_rearm_fence
            AND c.mutation_digest=NEW.mutation_digest AND c.selected_owner_id=NEW.selected_owner_id
            AND c.selected_owner_fence=NEW.selected_owner_fence
            AND c.account_denial_id IS NULL AND t.tenant_gameplay_tag=NEW.tenant_gameplay_tag
            AND octet_length(NEW.canonical_execution)<=c.reserved_binding_bytes
            AND (convert_from(NEW.canonical_execution, 'UTF8')::jsonb)->>'candidateDigest'=c.candidate_digest
    ) THEN
        RAISE EXCEPTION 'Exact retained capacity evidence charge required' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE FUNCTION settle_gs_capacity_evidence_charge() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'UPDATE' OR current_setting('transaction_isolation') <> 'read committed'
        OR OLD.account_denial_id IS NOT NULL OR NEW.account_denial_id IS NULL
        OR (to_jsonb(NEW) - 'account_denial_id') IS DISTINCT FROM (to_jsonb(OLD) - 'account_denial_id') THEN
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
DROP TRIGGER gs_capacity_evidence_charge_retained ON game_session_capacity_evidence_charge;
CREATE TRIGGER gs_capacity_evidence_charge_retained BEFORE UPDATE OR DELETE ON game_session_capacity_evidence_charge
    FOR EACH ROW EXECUTE FUNCTION settle_gs_capacity_evidence_charge();

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1065.0.5'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1065.0.5'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.6__settle_denied_capacity_evidence_charges.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.6'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1065.0.5'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.6''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1065.0.5','V1065.0.5__bound_original_new_capacity_evidence.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.6'',''V1065.0.6__settle_denied_capacity_evidence_charges.sql''';
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
