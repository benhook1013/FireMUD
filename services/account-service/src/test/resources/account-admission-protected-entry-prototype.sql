-- Isolated feasibility fixture only. No production migration or privilege adoption.
-- __S__ is the actual Flyway schema; __P__ is a separate test receipt schema.
CREATE ROLE __P___owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE ROLE __P___caller LOGIN PASSWORD 'isolated-prototype-only' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE SCHEMA __P__ AUTHORIZATION __P___owner;
REVOKE ALL ON SCHEMA __P__ FROM PUBLIC;
GRANT USAGE ON SCHEMA __P__ TO __P___caller;
GRANT USAGE ON SCHEMA __S__ TO __P___owner;
GRANT SELECT ON __S__.accounts, __S__.account_gameplay_admission_lease_operations TO __P___owner;
-- PostgreSQL row locking requires UPDATE privilege; no caller receives it.
GRANT UPDATE(account_uuid) ON __S__.accounts TO __P___owner;
GRANT UPDATE(request_id) ON __S__.account_gameplay_admission_lease_operations TO __P___owner;

CREATE TABLE __P__.receipts (
    request_id UUID PRIMARY KEY REFERENCES __S__.account_gameplay_admission_lease_operations(request_id),
    original_operation JSONB NOT NULL,
    finalization_xid TEXT NOT NULL,
    confirmation_xid TEXT NOT NULL,
    snapshot TEXT NOT NULL,
    insert_lsn PG_LSN NOT NULL CHECK (insert_lsn > '0/0'),
    flush_lsn PG_LSN NOT NULL CHECK (flush_lsn >= insert_lsn),
    committed_before_ms BIGINT NOT NULL CHECK (committed_before_ms > 0),
    expires_at_ms BIGINT NOT NULL CHECK (expires_at_ms > committed_before_ms),
    CHECK (finalization_xid <> confirmation_xid)
);
ALTER TABLE __P__.receipts OWNER TO __P___owner;
REVOKE ALL ON __P__.receipts FROM PUBLIC, __P___caller;

CREATE FUNCTION __P__.immutable() RETURNS TRIGGER LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    RAISE EXCEPTION 'prototype receipt immutable' USING ERRCODE = '23514';
END;
$$;
ALTER FUNCTION __P__.immutable() OWNER TO __P___owner;
REVOKE ALL ON FUNCTION __P__.immutable() FROM PUBLIC, __P___caller;
CREATE TRIGGER immutable BEFORE UPDATE OR DELETE ON __P__.receipts
    FOR EACH ROW EXECUTE FUNCTION __P__.immutable();
CREATE TRIGGER immutable_truncate BEFORE TRUNCATE ON __P__.receipts
    FOR EACH STATEMENT EXECUTE FUNCTION __P__.immutable();

-- Private writer is never executable by the caller. Capture is not an external API.
CREATE FUNCTION __P__.write_receipt(p_receipt __P__.receipts) RETURNS JSONB
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE retained __P__.receipts%ROWTYPE;
BEGIN
    INSERT INTO __P__.receipts SELECT (p_receipt).* WHERE TRUE ON CONFLICT (request_id) DO NOTHING
        RETURNING * INTO retained;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'prototype stale receipt snapshot' USING ERRCODE = '40001';
    END IF;
    RETURN to_jsonb(retained);
END;
$$;
ALTER FUNCTION __P__.write_receipt(__P__.receipts) OWNER TO __P___owner;
REVOKE ALL ON FUNCTION __P__.write_receipt(__P__.receipts) FROM PUBLIC, __P___caller;

CREATE FUNCTION __P__.confirm(p_request UUID, p_sha TEXT, p_decision UUID) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE
    op __S__.account_gameplay_admission_lease_operations%ROWTYPE;
    locked_op __S__.account_gameplay_admission_lease_operations%ROWTYPE;
    retained __P__.receipts%ROWTYPE;
    candidate __P__.receipts%ROWTYPE;
    fixed_snapshot pg_snapshot;
    own_xid TEXT;
    upper_lsn pg_lsn;
    flush_lsn pg_lsn;
    observed_ms BIGINT;
    observation INTEGER;
    diagnostic_entry_insert pg_lsn;
    diagnostic_entry_flush pg_lsn;
    diagnostic_capture_flush pg_lsn;
    diagnostic_pid INTEGER;
BEGIN
    -- Observations only: even this first statement follows function resolution/planning.
    diagnostic_entry_insert := pg_current_wal_insert_lsn();
    diagnostic_entry_flush := pg_current_wal_flush_lsn();
    diagnostic_pid := pg_backend_pid();
    IF current_setting('transaction_isolation') <> 'serializable'
        OR current_setting('transaction_read_only') <> 'off'
        OR pg_is_in_recovery() OR current_setting('fsync') <> 'on' THEN
        RAISE EXCEPTION 'prototype durable primary SERIALIZABLE required' USING ERRCODE = '23514';
    END IF;
    -- Function entry itself can already require catalog/planning reads. Cold tests must expose
    -- resulting unflushed WAL rather than claiming these statements precede all backend reads.
    fixed_snapshot := pg_current_snapshot();
    own_xid := pg_current_xact_id_if_assigned()::text;
    upper_lsn := pg_current_wal_insert_lsn();
    diagnostic_capture_flush := pg_current_wal_flush_lsn();
    SELECT * INTO op FROM __S__.account_gameplay_admission_lease_operations WHERE request_id = p_request;
    IF NOT FOUND OR op.status <> 'COMMITTED' OR op.finalization_xid IS NULL
        OR op.evidence_sha256 IS DISTINCT FROM p_sha OR op.binding_decision_id IS DISTINCT FROM p_decision THEN
        RAISE EXCEPTION 'prototype exact original required' USING ERRCODE = '23514';
    END IF;
    IF op.finalization_xid = own_xid
        OR NOT pg_visible_in_snapshot(op.finalization_xid::xid8, fixed_snapshot) THEN
        RAISE EXCEPTION 'prototype independent original required' USING ERRCODE = '23514';
    END IF;
    PERFORM 1 FROM __S__.accounts WHERE account_uuid = op.account_uuid FOR UPDATE;
    SELECT * INTO locked_op FROM __S__.account_gameplay_admission_lease_operations
        WHERE request_id = p_request FOR UPDATE;
    IF NOT FOUND OR locked_op IS DISTINCT FROM op THEN
        RAISE EXCEPTION 'prototype original changed' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO retained FROM __P__.receipts WHERE request_id = p_request FOR UPDATE;
    IF FOUND THEN
        IF retained.original_operation IS DISTINCT FROM to_jsonb(op) THEN
            RAISE EXCEPTION 'prototype retained binding conflict' USING ERRCODE = '23514';
        END IF;
        RETURN to_jsonb(retained);
    END IF;
    IF pg_xact_status(op.finalization_xid::xid8) IS DISTINCT FROM 'committed' THEN
        RAISE EXCEPTION 'prototype independent original required' USING ERRCODE = '23514';
    END IF;
    -- Same-snapshot independent visibility places original COMMIT before snapshot acquisition;
    -- this later insertion upper bound covers that COMMIT, not merely its earlier row WAL.
    FOR observation IN 1..50 LOOP
        flush_lsn := pg_current_wal_flush_lsn();
        observed_ms := ceil(extract(epoch FROM clock_timestamp()) * 1000)::bigint;
        IF observed_ms <= 0 OR observed_ms >= op.expires_at_ms THEN
            RAISE EXCEPTION 'prototype unchanged deadline expired' USING ERRCODE = '23514';
        END IF;
        EXIT WHEN upper_lsn > '0/0' AND flush_lsn >= upper_lsn;
        IF observation = 50 THEN
            RAISE EXCEPTION 'prototype WAL coverage unavailable' USING ERRCODE = '23514',
                DETAIL = format('snapshot=%s own_xid=%s original_xid=%s upper_lsn=%s flush_lsn=%s observed_ms=%s expiry=%s diagnostic_pid=%s diagnostic_entry_insert=%s diagnostic_entry_flush=%s diagnostic_capture_flush=%s',
                    fixed_snapshot, own_xid, op.finalization_xid, upper_lsn, flush_lsn, observed_ms, op.expires_at_ms,
                    diagnostic_pid, diagnostic_entry_insert, diagnostic_entry_flush, diagnostic_capture_flush);
        END IF;
        PERFORM pg_sleep(0.01);
    END LOOP;
    candidate.request_id := p_request;
    candidate.original_operation := to_jsonb(op);
    candidate.finalization_xid := op.finalization_xid;
    candidate.confirmation_xid := pg_current_xact_id()::text;
    candidate.snapshot := fixed_snapshot::text;
    candidate.insert_lsn := upper_lsn;
    candidate.flush_lsn := flush_lsn;
    candidate.committed_before_ms := observed_ms;
    candidate.expires_at_ms := op.expires_at_ms;
    RETURN __P__.write_receipt(candidate);
END;
$$;
ALTER FUNCTION __P__.confirm(UUID, TEXT, UUID) OWNER TO __P___owner;
REVOKE ALL ON FUNCTION __P__.confirm(UUID, TEXT, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION __P__.confirm(UUID, TEXT, UUID) TO __P___caller;

CREATE FUNCTION __P__.read_receipt(p_request UUID, p_sha TEXT, p_decision UUID) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE
    op __S__.account_gameplay_admission_lease_operations%ROWTYPE;
    retained __P__.receipts%ROWTYPE;
    fixed_snapshot pg_snapshot;
    own_xid TEXT;
    upper_lsn pg_lsn;
    flush_lsn pg_lsn;
    observation INTEGER;
BEGIN
    IF current_setting('transaction_isolation') <> 'serializable'
        OR current_setting('transaction_read_only') <> 'off'
        OR pg_is_in_recovery() OR current_setting('fsync') <> 'on' THEN
        RAISE EXCEPTION 'prototype durable primary SERIALIZABLE required' USING ERRCODE = '23514';
    END IF;
    fixed_snapshot := pg_current_snapshot();
    own_xid := pg_current_xact_id_if_assigned()::text;
    upper_lsn := pg_current_wal_insert_lsn();
    SELECT * INTO op FROM __S__.account_gameplay_admission_lease_operations WHERE request_id = p_request;
    IF NOT FOUND OR op.status <> 'COMMITTED' OR op.evidence_sha256 IS DISTINCT FROM p_sha
        OR op.binding_decision_id IS DISTINCT FROM p_decision THEN
        RAISE EXCEPTION 'prototype exact original required' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO retained FROM __P__.receipts WHERE request_id = p_request;
    IF NOT FOUND OR retained.original_operation IS DISTINCT FROM to_jsonb(op) THEN
        RAISE EXCEPTION 'prototype exact receipt required' USING ERRCODE = '23514';
    END IF;
    IF retained.confirmation_xid = own_xid
        OR NOT pg_visible_in_snapshot(retained.confirmation_xid::xid8, fixed_snapshot) THEN
        RAISE EXCEPTION 'prototype independent receipt required' USING ERRCODE = '23514';
    END IF;
    FOR observation IN 1..50 LOOP
        flush_lsn := pg_current_wal_flush_lsn();
        EXIT WHEN upper_lsn > '0/0' AND flush_lsn >= upper_lsn;
        IF observation = 50 THEN
            RAISE EXCEPTION 'prototype receipt WAL coverage unavailable' USING ERRCODE = '23514',
                DETAIL = format('snapshot=%s receipt_xid=%s upper_lsn=%s flush_lsn=%s',
                    fixed_snapshot, retained.confirmation_xid, upper_lsn, flush_lsn);
        END IF;
        PERFORM pg_sleep(0.01);
    END LOOP;
    -- Historical recovery preserves the original deadline-qualified evidence after expiry.
    RETURN to_jsonb(retained);
END;
$$;
ALTER FUNCTION __P__.read_receipt(UUID, TEXT, UUID) OWNER TO __P___owner;
REVOKE ALL ON FUNCTION __P__.read_receipt(UUID, TEXT, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION __P__.read_receipt(UUID, TEXT, UUID) TO __P___caller;
