-- Game Session owns this storage envelope. It grants no Account capacity or admission authority.
CREATE TABLE game_session_capacity_evidence_budget (
    tenant_id UUID PRIMARY KEY REFERENCES game_session_canonical_tenant_gameplay_tag(tenant_id),
    maximum_records INTEGER NOT NULL CHECK (maximum_records > 0),
    maximum_binding_bytes BIGINT NOT NULL CHECK (maximum_binding_bytes >= 1048576)
);
CREATE TABLE game_session_capacity_evidence_charge (
    admission_attempt_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES game_session_capacity_evidence_budget(tenant_id),
    request_id UUID NOT NULL,
    admission_id UUID NOT NULL REFERENCES game_session_canonical_binding_transition(transition_id),
    capacity_rearm_fence BIGINT NOT NULL CHECK (capacity_rearm_fence > 0),
    mutation_digest CHAR(64) NOT NULL CHECK (mutation_digest ~ '^[0-9a-f]{64}$'),
    candidate_digest VARCHAR(71) NOT NULL CHECK (candidate_digest ~ '^sha256:[0-9a-f]{64}$'),
    selected_owner_id VARCHAR(120) NOT NULL CHECK (selected_owner_id ~ '^[A-Za-z0-9._:-]{1,120}$'),
    selected_owner_fence BIGINT NOT NULL CHECK (selected_owner_fence > 0),
    reserved_binding_bytes INTEGER NOT NULL DEFAULT 1048576 CHECK (reserved_binding_bytes = 1048576)
);
CREATE INDEX gs_capacity_evidence_charge_tenant
    ON game_session_capacity_evidence_charge(tenant_id, admission_attempt_id);
CREATE INDEX gs_capacity_evidence_execution_tenant
    ON game_session_original_new_capacity_execution(tenant_id, admission_attempt_id);

-- [jooq ignore start]
CREATE FUNCTION guard_gs_capacity_evidence_charge() RETURNS TRIGGER LANGUAGE plpgsql AS $$
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
        WHERE tenant_id=NEW.tenant_id LIMIT record_limit
    ) retained;
    IF used_records >= record_limit THEN
        RAISE EXCEPTION 'Capacity evidence budget is exhausted' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_capacity_evidence_charge_bounded BEFORE INSERT ON game_session_capacity_evidence_charge
    FOR EACH ROW EXECUTE FUNCTION guard_gs_capacity_evidence_charge();

CREATE FUNCTION require_gs_capacity_evidence_charge() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM game_session_capacity_evidence_charge c
        JOIN game_session_canonical_tenant_gameplay_tag t ON t.tenant_id=c.tenant_id
        WHERE c.admission_attempt_id=NEW.admission_attempt_id AND c.tenant_id=NEW.tenant_id
            AND c.request_id=NEW.request_id AND c.admission_id=NEW.admission_id
            AND NEW.transition_id=c.admission_id AND c.capacity_rearm_fence=NEW.capacity_rearm_fence
            AND c.mutation_digest=NEW.mutation_digest AND c.selected_owner_id=NEW.selected_owner_id
            AND c.selected_owner_fence=NEW.selected_owner_fence
            AND t.tenant_gameplay_tag=NEW.tenant_gameplay_tag
            AND octet_length(NEW.canonical_execution)<=c.reserved_binding_bytes
            AND (convert_from(NEW.canonical_execution, 'UTF8')::jsonb)->>'candidateDigest'=c.candidate_digest
    ) THEN
        RAISE EXCEPTION 'Exact retained capacity evidence charge required' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_capacity_execution_evidence_charge BEFORE INSERT ON game_session_original_new_capacity_execution
    FOR EACH ROW EXECUTE FUNCTION require_gs_capacity_evidence_charge();

CREATE FUNCTION reject_gs_capacity_evidence_budget_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Original NEW capacity evidence budget and charges are retained' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER gs_capacity_evidence_budget_retained BEFORE UPDATE OR DELETE ON game_session_capacity_evidence_budget
    FOR EACH ROW EXECUTE FUNCTION reject_gs_capacity_evidence_budget_mutation();
CREATE TRIGGER gs_capacity_evidence_budget_no_truncate BEFORE TRUNCATE ON game_session_capacity_evidence_budget
    FOR EACH STATEMENT EXECUTE FUNCTION reject_gs_capacity_evidence_budget_mutation();
CREATE TRIGGER gs_capacity_evidence_charge_retained BEFORE UPDATE OR DELETE ON game_session_capacity_evidence_charge
    FOR EACH ROW EXECUTE FUNCTION reject_gs_capacity_evidence_budget_mutation();
CREATE TRIGGER gs_capacity_evidence_charge_no_truncate BEFORE TRUNCATE ON game_session_capacity_evidence_charge
    FOR EACH STATEMENT EXECUTE FUNCTION reject_gs_capacity_evidence_budget_mutation();

INSERT INTO game_session_runtime_family_classification
    (family_id,relation_name,family_role,state_class,fresh_disposition,retention_disposition,owner_consequence)
VALUES ('game_session_capacity_evidence_budget','game_session_capacity_evidence_budget','CONTROL','NOT_PAYLOAD',
    'CONTROL_ONLY','OWNER_POLICY_RETAINED','Retain the owner-defined finite tenant evidence budget across recovery. Missing budget refuses new charges.'),
    ('game_session_capacity_evidence_charge','game_session_capacity_evidence_charge','CONTROL','NOT_PAYLOAD',
    'CONTROL_ONLY','OWNER_POLICY_RETAINED','Retain the exact original attempt and its reserved evidence bytes. Do not use a charge as admission, Account, or physical execution authority.');

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1064'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1064'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.5__bound_original_new_capacity_evidence.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.5'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1064'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.5''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1064','V1064__restore_final_human_completion_lower_bound.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.5'',''V1065.0.5__bound_original_new_capacity_evidence.sql''';
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
