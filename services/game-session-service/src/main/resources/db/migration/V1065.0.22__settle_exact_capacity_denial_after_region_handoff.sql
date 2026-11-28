-- Retain the original charge and record which installed successor settled an exact Account denial.
-- [jooq ignore start]
ALTER TABLE game_session_capacity_evidence_charge
    ADD COLUMN denial_settlement_owner_id varchar(120),
    ADD COLUMN denial_settlement_owner_fence bigint,
    ADD COLUMN denial_settlement_lease_correlation varchar(64),
    ADD CONSTRAINT chk_gs_capacity_denial_successor CHECK (
        (denial_settlement_owner_id IS NULL AND denial_settlement_owner_fence IS NULL
            AND denial_settlement_lease_correlation IS NULL)
        OR (denial_settlement_owner_id IS NOT NULL AND denial_settlement_owner_fence IS NOT NULL
            AND denial_settlement_owner_fence > selected_owner_fence
            AND denial_settlement_lease_correlation IS NOT NULL
            AND denial_settlement_lease_correlation ~ '^[0-9a-f]{64}$'
            AND account_denial_id IS NOT NULL AND account_denial_kind IS NOT NULL)
    );

-- Take the same region lock that serializes dispatch and replacement before locking the budget.
CREATE FUNCTION require_gs_capacity_denial_successor(
    p_tenant UUID, p_request UUID, p_admission UUID, p_candidate TEXT, p_original_fence BIGINT,
    p_owner TEXT, p_fence BIGINT, p_correlation TEXT
) RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF p_fence IS NULL OR p_fence <= p_original_fence OR p_correlation IS NULL THEN
        RAISE EXCEPTION 'Successor denial requires an advancing installed region owner' USING ERRCODE='23514';
    END IF;
    PERFORM require_gs_capacity_send_owner(p_tenant,p_admission,p_owner,p_fence);
    PERFORM 1 FROM game_session_canonical_binding_transition t
        JOIN game_session_canonical_binding_region_bridge_obligation b ON b.transition_id=t.transition_id
        JOIN game_session_region_runtime_status r ON r.tenant_id=b.tenant_id
            AND r.game_instance_id=b.game_instance_id AND r.region_id=b.region_id
            AND r.region_epoch=b.region_epoch
        WHERE t.transition_id=p_admission AND t.tenant_id=p_tenant AND b.tenant_id=p_tenant
            AND t.admission_request_id=p_request AND t.original_new_candidate_digest=p_candidate
            AND t.status='PROVISIONAL' AND b.status='ACKNOWLEDGED'
            AND b.executor_fence=p_original_fence
            AND r.lease_token_correlation=p_correlation
            AND r.canonical_region_id=b.canonical_region_id AND r.lifecycle_epoch=b.world_lifecycle_epoch
            AND r.lifecycle_row_version=b.world_lifecycle_row_version AND r.capture_id=b.world_capture_id
            AND r.graph_sha256=b.world_graph_sha256
            AND r.preparation_input_digest=b.world_preparation_input_digest
        FOR UPDATE OF t;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Successor denial requires the exact original bridge and current region source' USING ERRCODE='23514';
    END IF;
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
        OR (to_jsonb(NEW) - 'account_send_state' - 'account_denial_id' - 'account_denial_kind'
            - 'denial_settlement_owner_id' - 'denial_settlement_owner_fence'
            - 'denial_settlement_lease_correlation')
            IS DISTINCT FROM (to_jsonb(OLD) - 'account_send_state' - 'account_denial_id' - 'account_denial_kind'
            - 'denial_settlement_owner_id' - 'denial_settlement_owner_fence'
            - 'denial_settlement_lease_correlation') THEN
        RAISE EXCEPTION 'Capacity evidence charge identity and settlement history are immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.denial_settlement_owner_id IS NOT NULL THEN
        IF NEW.account_denial_id IS NULL
            OR NEW.account_denial_kind IS NULL
            OR NEW.account_denial_kind NOT IN ('CAPACITY_QUOTA_EXCEEDED', 'CAPACITY_EVALUATION_EXPIRED')
            OR NEW.account_send_state IS DISTINCT FROM OLD.account_send_state
            OR (OLD.account_send_state IS NOT NULL AND OLD.account_send_state <> 'MAY_HAVE_SENT') THEN
            RAISE EXCEPTION 'Successor can settle only an exact denial of a possible send' USING ERRCODE='23514';
        END IF;
        PERFORM require_gs_capacity_denial_successor(
            OLD.tenant_id,OLD.request_id,OLD.admission_id,OLD.candidate_digest,
            OLD.selected_owner_fence,NEW.denial_settlement_owner_id,
            NEW.denial_settlement_owner_fence,NEW.denial_settlement_lease_correlation);
        PERFORM 1 FROM game_session_capacity_evidence_budget WHERE tenant_id=OLD.tenant_id FOR UPDATE;
        IF NOT FOUND OR EXISTS (
            SELECT 1 FROM game_session_original_new_capacity_execution
            WHERE admission_attempt_id=OLD.admission_attempt_id OR transition_id=OLD.admission_id
        ) THEN
            RAISE EXCEPTION 'Successor denial requires a charge without execution custody' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    -- Once the original send window expires, the Game Session tenant budget can return a
    -- still-unclaimed slot even after its original region owner moved. No Account decision
    -- is inferred. The terminal expiry and exact SQL state exclude a first or concurrent send.
    IF OLD.account_send_state='UNCLAIMED' AND NEW.account_send_state='RELEASED_UNSENT'
        AND NEW.account_denial_id IS NULL AND NEW.account_denial_kind IS NULL
        AND OLD.account_send_evidence_expires_at <= clock_timestamp() THEN
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
        AND NEW.account_denial_kind IN ('CAPACITY_QUOTA_EXCEEDED', 'CAPACITY_EVALUATION_EXPIRED')
        AND NEW.account_send_state IS NOT DISTINCT FROM OLD.account_send_state
        AND (OLD.account_send_state IS NULL OR OLD.account_send_state='MAY_HAVE_SENT') THEN
        -- Only the owner supplies an exact authenticated durable Account denial.
        RETURN NEW;
    END IF;
    IF NEW.account_denial_id IS NOT NULL OR NEW.account_denial_kind IS NOT NULL
        OR limits.maximum_retained_records IS NULL THEN
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
                AND t.admission_request_id=OLD.request_id
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

-- Runtime-family catalog registration
DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1065.0.21'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1065.0.21'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.22__settle_exact_capacity_denial_after_region_handoff.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.22'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1065.0.21'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.22''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1065.0.21','V1065.0.21__bind_capacity_evidence_charge_request.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.22'',''V1065.0.22__settle_exact_capacity_denial_after_region_handoff.sql''';
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
