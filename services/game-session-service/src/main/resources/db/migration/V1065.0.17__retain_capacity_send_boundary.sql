-- The charge remains the Game Session storage budget and grants no Account settlement authority.
-- Existing null send states mean transmission was possible. Never infer an unsent legacy charge.
-- Keep existing budget rows immutable. New rows must explicitly qualify retained history.
-- [jooq ignore start]
ALTER TABLE game_session_capacity_evidence_budget
    ADD CONSTRAINT gs_capacity_evidence_budget_retained_limit_required
    CHECK (maximum_retained_records IS NOT NULL) NOT VALID;
-- [jooq ignore stop]

ALTER TABLE game_session_capacity_evidence_charge
    ADD COLUMN account_send_state TEXT,
    ADD COLUMN account_send_evidence_expires_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN account_unsent_released_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT gs_capacity_evidence_send_state CHECK (
        (account_send_state IS NULL AND account_send_evidence_expires_at IS NULL
            AND account_unsent_released_at IS NULL)
        OR (account_send_state IS NOT NULL
            AND account_send_state IN ('UNCLAIMED','MAY_HAVE_SENT','RELEASED_UNSENT')
            AND account_send_evidence_expires_at IS NOT NULL AND isfinite(account_send_evidence_expires_at)
            AND (account_unsent_released_at IS NULL OR isfinite(account_unsent_released_at))
            AND (account_send_state <> 'UNCLAIMED' OR account_unsent_released_at IS NULL)
            AND (account_send_state <> 'RELEASED_UNSENT' OR account_unsent_released_at IS NOT NULL)
            AND (account_denial_id IS NULL OR account_send_state='MAY_HAVE_SENT'))
    );

DROP INDEX gs_capacity_evidence_charge_active;
CREATE INDEX gs_capacity_evidence_charge_active
    ON game_session_capacity_evidence_charge(tenant_id, admission_attempt_id)
    WHERE account_denial_id IS NULL AND account_send_state IS DISTINCT FROM 'RELEASED_UNSENT';
CREATE INDEX gs_capacity_evidence_charge_unclaimed_expiry
    ON game_session_capacity_evidence_charge(tenant_id, account_send_evidence_expires_at, admission_attempt_id)
    WHERE account_send_state='UNCLAIMED';

-- [jooq ignore start]
-- Callers lock the current region owner before the tenant budget and before the exact charge CAS.
CREATE FUNCTION require_gs_capacity_send_owner(
    p_tenant UUID, p_admission UUID, p_owner TEXT, p_fence BIGINT
) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE scope RECORD; current_owner RECORD;
BEGIN
    SELECT DISTINCT b.tenant_id,b.game_instance_id,b.region_id,b.region_epoch
        INTO STRICT scope
        FROM game_session_canonical_binding_region_bridge_obligation b
        WHERE b.transition_id=p_admission;
    SELECT r.owner_instance_id,r.executor_fence,r.owner_service,r.paused
        INTO current_owner FROM game_session_region_runtime_status r
        WHERE r.tenant_id=scope.tenant_id AND r.game_instance_id=scope.game_instance_id
            AND r.region_id=scope.region_id AND r.region_epoch=scope.region_epoch
        FOR UPDATE;
    IF NOT FOUND OR scope.tenant_id IS DISTINCT FROM p_tenant
        OR current_owner.owner_service IS DISTINCT FROM 'game-session-service'
        OR current_owner.paused IS DISTINCT FROM false
        OR current_owner.owner_instance_id IS DISTINCT FROM p_owner
        OR current_owner.executor_fence IS DISTINCT FROM p_fence
        OR EXISTS (SELECT 1 FROM game_session_region_owner_transfer t
            WHERE t.tenant_id=scope.tenant_id AND t.game_instance_id=scope.game_instance_id
                AND t.region_id=scope.region_id AND (t.state='FROZEN'
                    OR (t.state='COMPLETED' AND t.old_owner_id=p_owner AND t.old_owner_fence=p_fence))) THEN
        RAISE EXCEPTION 'Capacity send boundary requires the exact current selected owner' USING ERRCODE='23514';
    END IF;
END;
$$;

-- The active bound is shared by fresh insert and same-row reacquisition.
CREATE FUNCTION require_gs_capacity_evidence_active_room(
    p_tenant UUID, p_records INTEGER, p_binding_bytes BIGINT, p_reserved_bytes INTEGER
) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE used_records BIGINT; record_limit BIGINT;
BEGIN
    record_limit := least(p_records,p_binding_bytes / p_reserved_bytes);
    SELECT count(*) INTO used_records FROM (
        SELECT 1 FROM game_session_capacity_evidence_charge
        WHERE tenant_id=p_tenant AND account_denial_id IS NULL
            AND account_send_state IS DISTINCT FROM 'RELEASED_UNSENT'
        LIMIT record_limit
    ) active;
    IF used_records >= record_limit THEN
        RAISE EXCEPTION 'Capacity evidence budget is exhausted' USING ERRCODE='23514';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION guard_gs_capacity_evidence_charge() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE limits game_session_capacity_evidence_budget%ROWTYPE; used_records BIGINT; checked_at TIMESTAMP WITH TIME ZONE;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed'
        OR NEW.reserved_binding_bytes IS DISTINCT FROM 1048576
        OR NEW.account_send_state IS DISTINCT FROM 'UNCLAIMED'
        OR NEW.account_unsent_released_at IS NOT NULL OR NEW.account_denial_id IS NOT NULL THEN
        RAISE EXCEPTION 'Capacity evidence requires a fresh unclaimed charge and READ COMMITTED' USING ERRCODE='23514';
    END IF;
    PERFORM require_gs_capacity_send_owner(NEW.tenant_id,NEW.admission_id,NEW.selected_owner_id,NEW.selected_owner_fence);
    SELECT * INTO limits FROM game_session_capacity_evidence_budget
        WHERE tenant_id=NEW.tenant_id FOR UPDATE;
    IF NOT FOUND OR limits.maximum_retained_records IS NULL THEN
        RAISE EXCEPTION 'Active and retained capacity evidence budgets are required' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM game_session_canonical_binding_transition t
        WHERE t.transition_id=NEW.admission_id AND t.tenant_id=NEW.tenant_id
            AND t.status='PROVISIONAL' AND t.original_new_candidate_digest=NEW.candidate_digest)
        OR EXISTS (SELECT 1 FROM game_session_original_new_capacity_execution e
        WHERE e.tenant_id=NEW.tenant_id AND NOT EXISTS (
            SELECT 1 FROM game_session_capacity_evidence_charge c
            WHERE c.admission_attempt_id=e.admission_attempt_id)) THEN
        RAISE EXCEPTION 'Capacity evidence source or predecessor coverage is unavailable' USING ERRCODE='23514';
    END IF;
    checked_at := clock_timestamp();
    IF NEW.account_send_evidence_expires_at IS NULL
        OR NEW.account_send_evidence_expires_at <= checked_at
        OR NEW.account_send_evidence_expires_at > checked_at + INTERVAL '15 seconds' THEN
        RAISE EXCEPTION 'Fresh capacity evidence expiry is outside its original freshness bound' USING ERRCODE='23514';
    END IF;
    PERFORM require_gs_capacity_evidence_active_room(
        NEW.tenant_id,limits.maximum_records,limits.maximum_binding_bytes,NEW.reserved_binding_bytes);
    SELECT count(*) INTO used_records FROM (
        SELECT 1 FROM game_session_capacity_evidence_charge
        WHERE tenant_id=NEW.tenant_id LIMIT limits.maximum_retained_records
    ) retained;
    IF used_records >= limits.maximum_retained_records THEN
        RAISE EXCEPTION 'Retained capacity evidence history is exhausted' USING ERRCODE='23514';
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
            AND (c.account_send_state IS NULL OR c.account_send_state='MAY_HAVE_SENT')
            AND octet_length(NEW.canonical_execution)<=c.reserved_binding_bytes
            AND (convert_from(NEW.canonical_execution, 'UTF8')::jsonb)->>'candidateDigest'=c.candidate_digest
    ) THEN
        RAISE EXCEPTION 'Exact retained capacity evidence charge required' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION settle_gs_capacity_evidence_charge() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE limits game_session_capacity_evidence_budget%ROWTYPE;
BEGIN
    IF TG_OP <> 'UPDATE' OR current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Capacity evidence charge history is retained' USING ERRCODE='23514';
    END IF;
    IF OLD.account_denial_id IS NOT NULL
        OR NEW.account_unsent_released_at IS DISTINCT FROM OLD.account_unsent_released_at
        OR (to_jsonb(NEW) - 'account_send_state' - 'account_denial_id')
            IS DISTINCT FROM (to_jsonb(OLD) - 'account_send_state' - 'account_denial_id') THEN
        RAISE EXCEPTION 'Capacity evidence charge identity and settlement history are immutable' USING ERRCODE='23514';
    END IF;
    -- Once the original send window expires, the Game Session tenant budget can return a
    -- still-unclaimed slot even after its original region owner moved. No Account decision
    -- is inferred. The terminal expiry and exact SQL state exclude a first or concurrent send.
    IF OLD.account_send_state='UNCLAIMED' AND NEW.account_send_state='RELEASED_UNSENT'
        AND NEW.account_denial_id IS NULL AND OLD.account_send_evidence_expires_at <= clock_timestamp() THEN
        PERFORM 1 FROM game_session_capacity_evidence_budget WHERE tenant_id=OLD.tenant_id FOR UPDATE;
        IF NOT FOUND OR EXISTS (
            SELECT 1 FROM game_session_original_new_capacity_execution
            WHERE admission_attempt_id=OLD.admission_attempt_id
        ) THEN
            RAISE EXCEPTION 'Execution custody prevents capacity evidence charge changes' USING ERRCODE='23514';
        END IF;
        NEW.account_unsent_released_at := clock_timestamp();
        RETURN NEW;
    END IF;
    PERFORM require_gs_capacity_send_owner(OLD.tenant_id,OLD.admission_id,OLD.selected_owner_id,OLD.selected_owner_fence);
    SELECT * INTO limits FROM game_session_capacity_evidence_budget
        WHERE tenant_id=OLD.tenant_id FOR UPDATE;
    IF NOT FOUND OR EXISTS (
        SELECT 1 FROM game_session_original_new_capacity_execution
        WHERE admission_attempt_id=OLD.admission_attempt_id
    ) THEN
        RAISE EXCEPTION 'Execution custody prevents capacity evidence charge changes' USING ERRCODE='23514';
    END IF;

    IF NEW.account_denial_id IS NOT NULL
        AND NEW.account_send_state IS NOT DISTINCT FROM OLD.account_send_state
        AND (OLD.account_send_state IS NULL OR OLD.account_send_state='MAY_HAVE_SENT') THEN
        -- Only the owner supplies an exact authenticated durable Account denial.
        RETURN NEW;
    END IF;
    IF NEW.account_denial_id IS NOT NULL OR limits.maximum_retained_records IS NULL THEN
        RAISE EXCEPTION 'Invalid capacity evidence send transition' USING ERRCODE='23514';
    END IF;
    IF OLD.account_send_state='UNCLAIMED' AND NEW.account_send_state='RELEASED_UNSENT' THEN
        -- Winning this CAS excludes the first permission to invoke Account on this charge.
        NEW.account_unsent_released_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.account_send_state IN ('UNCLAIMED','RELEASED_UNSENT') AND NEW.account_send_state='MAY_HAVE_SENT' THEN
        IF OLD.account_send_evidence_expires_at <= clock_timestamp()
            OR NOT EXISTS (SELECT 1 FROM game_session_canonical_binding_transition t
            WHERE t.tenant_id=OLD.tenant_id AND t.transition_id=OLD.admission_id
                AND t.status='PROVISIONAL' AND t.original_new_candidate_digest=OLD.candidate_digest) THEN
            RAISE EXCEPTION 'Capacity send requires still-current original evidence' USING ERRCODE='23514';
        END IF;
        IF OLD.account_send_state='RELEASED_UNSENT' THEN
            PERFORM require_gs_capacity_evidence_active_room(
                OLD.tenant_id,limits.maximum_records,limits.maximum_binding_bytes,OLD.reserved_binding_bytes);
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Invalid capacity evidence send transition' USING ERRCODE='23514';
END;
$$;
-- [jooq ignore stop]

-- Runtime-family catalog registration
-- [jooq ignore start]
DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1065.0.16'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1065.0.16'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.17__retain_capacity_send_boundary.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.17'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1065.0.16'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.17''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1065.0.16','V1065.0.16__retain_exact_initial_timeline_no_write_release.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.17'',''V1065.0.17__retain_capacity_send_boundary.sql''';
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
