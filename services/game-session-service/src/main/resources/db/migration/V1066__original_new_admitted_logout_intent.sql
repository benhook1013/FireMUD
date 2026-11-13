-- Durable intent for the exact original admitted generation. Completion requires a separate
-- protected teardown receipt after actual serving and resume authority have been fenced.
CREATE TABLE game_session_original_new_admitted_logout_intent (
    logout_id UUID PRIMARY KEY CHECK (logout_id <> '00000000-0000-0000-0000-000000000000'),
    execution_digest VARCHAR(71) NOT NULL UNIQUE
        REFERENCES game_session_original_new_capacity_execution(execution_digest),
    transition_id UUID NOT NULL REFERENCES game_session_canonical_binding_transition(transition_id),
    binding_generation NUMERIC NOT NULL CHECK (binding_generation > 0),
    original_final_session_sha256 CHAR(64) NOT NULL
        CHECK (original_final_session_sha256 ~ '^[0-9a-f]{64}$'),
    original_session_sha1 CHAR(40) NOT NULL CHECK (original_session_sha1 ~ '^[0-9a-f]{40}$')
);

-- [jooq ignore start]
CREATE FUNCTION guard_gs_original_new_admitted_logout_intent() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Original NEW admitted logout intent is immutable' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM game_session_original_new_capacity_execution admitted_execution
        JOIN game_session_canonical_binding_transition admission_transition
            ON admission_transition.transition_id = admitted_execution.transition_id
        JOIN game_session_canonical_gameplay_binding_inventory active_binding
            ON active_binding.binding_ref = admission_transition.candidate_binding_ref
            AND active_binding.transition_id = admission_transition.transition_id
        WHERE admitted_execution.execution_digest = NEW.execution_digest
            AND admitted_execution.transition_id = NEW.transition_id
            AND admitted_execution.status = 'COMMITTED'
            AND admission_transition.status = 'COMMITTED'
            AND admission_transition.original_new_capacity_execution_digest = admitted_execution.execution_digest
            AND admission_transition.original_new_final_session_sha256 = NEW.original_final_session_sha256
            AND admission_transition.candidate_binding_generation = NEW.binding_generation
            AND active_binding.binding_generation = NEW.binding_generation
            AND active_binding.lifecycle = 'ACTIVE'
        FOR SHARE OF admission_transition, active_binding
    ) THEN
        RAISE EXCEPTION 'Logout intent requires the exact active original NEW final admission'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER gs_original_new_admitted_logout_intent_guard
    BEFORE INSERT OR UPDATE OR DELETE ON game_session_original_new_admitted_logout_intent
    FOR EACH ROW EXECUTE FUNCTION guard_gs_original_new_admitted_logout_intent();
CREATE TRIGGER gs_original_new_admitted_logout_intent_no_truncate
    BEFORE TRUNCATE ON game_session_original_new_admitted_logout_intent
    FOR EACH STATEMENT EXECUTE FUNCTION guard_gs_original_new_admitted_logout_intent();

INSERT INTO game_session_runtime_family_classification
    (family_id,relation_name,family_role,state_class,fresh_disposition,retention_disposition,owner_consequence)
VALUES ('game_session_original_new_admitted_logout_intent','game_session_original_new_admitted_logout_intent',
    'CONTROL','NOT_PAYLOAD','CONTROL_ONLY','OWNER_POLICY_RETAINED',
    'Retain exact admitted-generation logout intent and original FINAL binding. An intent supplies no terminal, replay erasure, or Account usage retirement authority.');

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1065.0.3'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1065.0.3'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1066__original_new_admitted_logout_intent.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1066'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1065.0.3'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1066''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1065.0.3','V1065.0.3__require_initial_capacity_execution_dispatch.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1066'',''V1066__original_new_admitted_logout_intent.sql''';
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
