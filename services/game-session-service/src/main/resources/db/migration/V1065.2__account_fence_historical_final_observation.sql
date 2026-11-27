-- Immutable historical FINAL evidence captured by the fence owner before its SQL intent.
CREATE TABLE game_session_account_fence_final_observation (
    operation_id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    transition_id UUID NOT NULL REFERENCES game_session_canonical_binding_transition(transition_id),
    admission_attempt_id UUID NOT NULL,
    execution_digest VARCHAR(71) NOT NULL REFERENCES game_session_original_new_capacity_execution(execution_digest),
    candidate_digest VARCHAR(71) NOT NULL CHECK (candidate_digest ~ '^sha256:[0-9a-f]{64}$'),
    final_session_sha256 CHAR(64) NOT NULL CHECK (final_session_sha256 ~ '^[0-9a-f]{64}$'),
    admission_at_ms BIGINT NOT NULL CHECK (admission_at_ms > 0),
    continuity_expires_at_ms BIGINT NOT NULL CHECK (continuity_expires_at_ms > admission_at_ms),
    inventory_snapshot_revision NUMERIC NOT NULL CHECK (inventory_snapshot_revision > 0),
    cohort_digest VARCHAR(71) NOT NULL CHECK (cohort_digest ~ '^sha256:[0-9a-f]{64}$'),
    barrier_target_digest VARCHAR(71) NOT NULL CHECK (barrier_target_digest ~ '^sha256:[0-9a-f]{64}$'),
    FOREIGN KEY (operation_id, account_id)
        REFERENCES game_session_canonical_account_admission_fence_intent(operation_id, account_id)
);

-- [jooq ignore start]
CREATE FUNCTION guard_gs_account_fence_final_observation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Historical FINAL observation is immutable' USING ERRCODE='23514';
    END IF;
    -- Intent insertion and the control transition are atomic. Only the acquiring transaction
    -- can see this new intent while the exact previous control epoch is still NO_ACTIVE.
    IF NOT EXISTS (
        SELECT 1
        FROM game_session_canonical_account_admission_fence_intent i
        JOIN game_session_canonical_account_coverage_control c ON c.account_id = i.account_id
        JOIN game_session_canonical_account_admission_fence_intent_cohort_row cohort
            ON cohort.operation_id = i.operation_id AND cohort.account_id = i.account_id
        JOIN game_session_original_new_capacity_execution e
            ON e.execution_digest = NEW.execution_digest
        WHERE i.operation_id = NEW.operation_id AND i.account_id = NEW.account_id
            AND i.intent_state = 'ACQUISITION_INTENT'
            AND i.inventory_snapshot_revision = NEW.inventory_snapshot_revision
            AND i.cohort_digest = NEW.cohort_digest
            AND i.barrier_target_digest = NEW.barrier_target_digest
            AND c.state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW'
            AND c.last_operation_fence + 1 = i.operation_fence
            AND cohort.source_kind = 'TRANSITION' AND cohort.source_key = NEW.transition_id::text
            AND cohort.source_row->>'status' = 'PROVISIONAL'
            AND cohort.source_row->>'original_new_candidate_digest' = NEW.candidate_digest
            AND e.transition_id = NEW.transition_id AND e.admission_attempt_id = NEW.admission_attempt_id
            AND e.tenant_id = cohort.tenant_id AND e.status = 'AUTHORIZED'
    ) THEN
        RAISE EXCEPTION 'Historical FINAL must bind the acquiring intent and exact frozen original' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_account_fence_final_observation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON game_session_account_fence_final_observation
    FOR EACH ROW EXECUTE FUNCTION guard_gs_account_fence_final_observation();
CREATE TRIGGER gs_account_fence_final_observation_no_truncate
    BEFORE TRUNCATE ON game_session_account_fence_final_observation
    FOR EACH STATEMENT EXECUTE FUNCTION guard_gs_account_fence_final_observation();

INSERT INTO game_session_runtime_family_classification
    (family_id,relation_name,family_role,state_class,fresh_disposition,retention_disposition,owner_consequence)
VALUES ('game_session_account_fence_final_observation','game_session_account_fence_final_observation','CONTROL','NOT_PAYLOAD','CONTROL_ONLY','OWNER_POLICY_RETAINED',
    'Retain the exact observed FINAL and its bound acquisition intent as historical evidence. No current admission, release or successor authority is conveyed.');

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1065.1'
    );$needle$;
    replacement := $replacement$WHERE version='1065.1'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.2__account_fence_historical_final_observation.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.2'
    );$replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog frontier'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1065.1'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.2''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1065.1','V1065.1__original_new_capacity_origin_custody.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.2'',''V1065.2__account_fence_historical_final_observation.sql''';
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
