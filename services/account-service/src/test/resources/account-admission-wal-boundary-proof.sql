-- Disposable-container experiment only. Never a Flyway migration or production authority.
-- __S__ is a generated, validated test schema. No historical locator is backfilled.
CREATE ROLE __S___owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE ROLE __S___reader LOGIN PASSWORD 'isolated-proof-only' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
REVOKE ALL ON SCHEMA __S__ FROM PUBLIC;
GRANT USAGE ON SCHEMA __S__ TO __S___owner, __S___reader;
GRANT CREATE ON SCHEMA __S__ TO __S___owner;
GRANT USAGE ON SCHEMA wal_proof_extension TO __S___owner;
GRANT EXECUTE ON FUNCTION wal_proof_extension.pg_get_wal_records_info(pg_lsn, pg_lsn) TO __S___owner;
GRANT EXECUTE ON FUNCTION pg_catalog.pg_control_system() TO __S___owner;
GRANT SELECT ON __S__.account_gameplay_admission_lease_operations,
    __S__.account_gameplay_admission_original_commit_ack_receipts TO __S___owner;

CREATE TABLE __S__.wal_proof_locators (
    request_id UUID NOT NULL,
    phase TEXT NOT NULL CHECK (phase IN ('original', 'receipt')),
    account_uuid UUID NOT NULL,
    lease_id UUID NOT NULL,
    lease_fence BIGINT NOT NULL,
    evidence_sha256 TEXT NOT NULL,
    binding_decision_id UUID NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    finalization_xid TEXT NOT NULL,
    target_xid TEXT NOT NULL,
    search_lower_lsn PG_LSN NOT NULL,
    system_identifier TEXT NOT NULL,
    insertion_timeline BIGINT NOT NULL,
    PRIMARY KEY (request_id, phase)
);
ALTER TABLE __S__.wal_proof_locators OWNER TO __S___owner;

CREATE FUNCTION __S__.wal_proof_immutable() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Test WAL locator is immutable' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER wal_proof_immutable BEFORE UPDATE OR DELETE ON __S__.wal_proof_locators
    FOR EACH ROW EXECUTE FUNCTION __S__.wal_proof_immutable();
CREATE TRIGGER wal_proof_no_truncate BEFORE TRUNCATE ON __S__.wal_proof_locators
    FOR EACH STATEMENT EXECUTE FUNCTION __S__.wal_proof_immutable();

CREATE FUNCTION __S__.wal_proof_capture() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE
    phase_value TEXT;
    target_value TEXT;
    lower_value PG_LSN;
    cluster_value TEXT;
    timeline_value BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'account_gameplay_admission_lease_operations' THEN
        phase_value := 'original';
        target_value := NEW.finalization_xid;
    ELSE
        phase_value := 'receipt';
        target_value := NEW.receipt_xid;
    END IF;
    IF target_value IS DISTINCT FROM pg_current_xact_id_if_assigned()::text THEN
        RAISE EXCEPTION 'Test WAL locator requires exact own top-level XID' USING ERRCODE = '23514';
    END IF;
    -- This is only a lower search bound. The target COMMIT does not exist yet.
    lower_value := pg_current_wal_insert_lsn();
    SELECT c.system_identifier::text INTO cluster_value FROM pg_control_system() c;
    SELECT f.timeline_id INTO timeline_value
        FROM pg_split_walfile_name(pg_walfile_name(lower_value)) f;
    INSERT INTO __S__.wal_proof_locators VALUES (
        NEW.request_id, phase_value, NEW.account_uuid, NEW.lease_id, NEW.lease_fence,
        NEW.evidence_sha256, NEW.binding_decision_id, NEW.expires_at_ms,
        NEW.finalization_xid, target_value, lower_value, cluster_value, timeline_value);
    RETURN NEW;
END;
$$;
ALTER FUNCTION __S__.wal_proof_capture() OWNER TO __S___owner;
REVOKE ALL ON FUNCTION __S__.wal_proof_capture() FROM PUBLIC;
CREATE TRIGGER wal_proof_original_capture AFTER UPDATE ON __S__.account_gameplay_admission_lease_operations
    FOR EACH ROW WHEN (OLD.status = 'PENDING' AND NEW.status = 'COMMITTED')
    EXECUTE FUNCTION __S__.wal_proof_capture();
CREATE TRIGGER wal_proof_receipt_capture AFTER INSERT ON __S__.account_gameplay_admission_original_commit_ack_receipts
    FOR EACH ROW EXECUTE FUNCTION __S__.wal_proof_capture();

-- These two classifiers are called by the real reader. Direct synthetic calls in tests
-- exercise denial decisions, not physical promotion, wraparound, recycle or flush failures.
CREATE FUNCTION __S__.wal_proof_environment(
    version_value INTEGER, recovery_value BOOLEAN, fsync_value TEXT, extension_value TEXT,
    expected_cluster TEXT, observed_cluster TEXT, expected_timeline BIGINT, observed_timeline BIGINT)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF version_value IS NULL OR version_value < 160000 OR version_value >= 170000
        OR recovery_value IS DISTINCT FROM FALSE OR fsync_value IS DISTINCT FROM 'on'
        OR extension_value IS DISTINCT FROM '1.1'
        OR expected_cluster IS NULL OR expected_cluster IS DISTINCT FROM observed_cluster
        OR expected_timeline IS NULL OR expected_timeline IS DISTINCT FROM observed_timeline THEN
        RAISE EXCEPTION 'WAL proof environment unavailable or mismatched' USING ERRCODE = '23514';
    END IF;
END;
$$;
ALTER FUNCTION __S__.wal_proof_environment(INTEGER, BOOLEAN, TEXT, TEXT, TEXT, TEXT, BIGINT, BIGINT)
    OWNER TO __S___owner;
REVOKE ALL ON FUNCTION __S__.wal_proof_environment(INTEGER, BOOLEAN, TEXT, TEXT, TEXT, TEXT, BIGINT, BIGINT)
    FROM PUBLIC;

CREATE FUNCTION __S__.wal_proof_classify(
    expected_xid TEXT, snapshot_xmax TEXT, lower_value PG_LSN, upper_value PG_LSN,
    independent_flush PG_LSN, records_value JSONB)
RETURNS TABLE(record_start PG_LSN, record_end PG_LSN) LANGUAGE plpgsql AS $$
DECLARE
    expected_number NUMERIC;
    xmax_number NUMERIC;
    wal_xid TEXT;
    item JSONB;
    candidate_count INTEGER := 0;
    start_value PG_LSN;
    end_value PG_LSN;
BEGIN
    expected_number := expected_xid::numeric;
    xmax_number := snapshot_xmax::numeric;
    -- Deliberately bounded same-epoch probe. Never infer an epoch from a truncated WAL XID.
    IF expected_number <= 0 OR expected_number > 18446744073709551615
        OR xmax_number <= expected_number OR xmax_number - expected_number >= 2147483648
        OR floor(expected_number / 4294967296) <> floor(xmax_number / 4294967296)
        OR lower_value IS NULL OR lower_value <= '0/0'::pg_lsn
        OR upper_value IS NULL OR upper_value < lower_value
        OR upper_value - lower_value > 1048576
        OR independent_flush IS NULL
        OR records_value IS NULL OR jsonb_typeof(records_value) <> 'array'
        OR jsonb_array_length(records_value) > 256 THEN
        RAISE EXCEPTION 'WAL proof search bound or full XID unavailable' USING ERRCODE = '23514';
    END IF;
    wal_xid := mod(expected_number, 4294967296)::text;
    FOR item IN SELECT value FROM jsonb_array_elements(records_value) LOOP
        IF (item->>'start_lsn')::pg_lsn IS NULL OR (item->>'end_lsn')::pg_lsn IS NULL
            OR (item->>'start_lsn')::pg_lsn < lower_value
            OR (item->>'end_lsn')::pg_lsn <= (item->>'start_lsn')::pg_lsn
            OR (item->>'end_lsn')::pg_lsn > upper_value THEN
            RAISE EXCEPTION 'WAL proof record outside search bound' USING ERRCODE = '23514';
        END IF;
        IF item->>'xid' = wal_xid AND item->>'resource_manager' = 'Transaction' THEN
            IF item->>'record_type' IS DISTINCT FROM 'COMMIT' THEN
                RAISE EXCEPTION 'WAL proof transaction record unsupported' USING ERRCODE = '23514';
            END IF;
            candidate_count := candidate_count + 1;
            start_value := (item->>'start_lsn')::pg_lsn;
            end_value := (item->>'end_lsn')::pg_lsn;
        END IF;
    END LOOP;
    IF candidate_count <> 1 OR end_value > independent_flush THEN
        RAISE EXCEPTION 'WAL proof exact covered COMMIT unavailable or ambiguous' USING ERRCODE = '23514';
    END IF;
    RETURN QUERY SELECT start_value, end_value;
END;
$$;
ALTER FUNCTION __S__.wal_proof_classify(TEXT, TEXT, PG_LSN, PG_LSN, PG_LSN, JSONB) OWNER TO __S___owner;
REVOKE ALL ON FUNCTION __S__.wal_proof_classify(TEXT, TEXT, PG_LSN, PG_LSN, PG_LSN, JSONB) FROM PUBLIC;

CREATE FUNCTION __S__.wal_proof_read(
    requested_request UUID, requested_phase TEXT, expected_sha TEXT, expected_decision UUID)
RETURNS TABLE(target_xid TEXT, commit_start PG_LSN, commit_end PG_LSN,
    observed_flush PG_LSN, original_bound BIGINT, unchanged_expiry BIGINT)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE
    locator __S__.wal_proof_locators%ROWTYPE;
    operation __S__.account_gameplay_admission_lease_operations%ROWTYPE;
    receipt __S__.account_gameplay_admission_original_commit_ack_receipts%ROWTYPE;
    fixed_snapshot PG_SNAPSHOT;
    upper_value PG_LSN;
    flush_value PG_LSN;
    cluster_value TEXT;
    timeline_value BIGINT;
    extension_value TEXT;
    records_value JSONB;
    boundary RECORD;
BEGIN
    IF current_setting('transaction_isolation') <> 'serializable' THEN
        RAISE EXCEPTION 'Independent SERIALIZABLE test WAL reader required' USING ERRCODE = '23514';
    END IF;
    fixed_snapshot := pg_current_snapshot();
    SELECT * INTO locator FROM __S__.wal_proof_locators l
        WHERE l.request_id = requested_request AND l.phase = requested_phase;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'WAL proof locator unavailable; no historical backfill' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO operation FROM __S__.account_gameplay_admission_lease_operations o
        WHERE o.request_id = requested_request;
    IF NOT FOUND OR operation.status <> 'COMMITTED'
        OR operation.account_uuid IS DISTINCT FROM locator.account_uuid
        OR operation.lease_id IS DISTINCT FROM locator.lease_id
        OR operation.lease_fence IS DISTINCT FROM locator.lease_fence
        OR operation.evidence_sha256 IS DISTINCT FROM locator.evidence_sha256
        OR operation.binding_decision_id IS DISTINCT FROM locator.binding_decision_id
        OR operation.expires_at_ms IS DISTINCT FROM locator.expires_at_ms
        OR operation.finalization_xid IS DISTINCT FROM locator.finalization_xid
        OR expected_sha IS DISTINCT FROM locator.evidence_sha256
        OR expected_decision IS DISTINCT FROM locator.binding_decision_id
        OR locator.target_xid = pg_current_xact_id_if_assigned()::text
        OR NOT pg_visible_in_snapshot(locator.target_xid::xid8, fixed_snapshot) THEN
        RAISE EXCEPTION 'WAL proof exact independent identity unavailable' USING ERRCODE = '23514';
    END IF;
    IF requested_phase = 'receipt' THEN
        SELECT * INTO receipt FROM __S__.account_gameplay_admission_original_commit_ack_receipts r
            WHERE r.request_id = requested_request;
        IF NOT FOUND OR receipt.receipt_xid IS DISTINCT FROM locator.target_xid
            OR receipt.finalization_xid IS DISTINCT FROM locator.finalization_xid
            OR receipt.account_uuid IS DISTINCT FROM locator.account_uuid
            OR receipt.lease_id IS DISTINCT FROM locator.lease_id
            OR receipt.lease_fence IS DISTINCT FROM locator.lease_fence
            OR receipt.evidence_sha256 IS DISTINCT FROM locator.evidence_sha256
            OR receipt.binding_decision_id IS DISTINCT FROM locator.binding_decision_id
            OR receipt.expires_at_ms IS DISTINCT FROM locator.expires_at_ms
            OR receipt.committed_before_ms <= 0 OR receipt.committed_before_ms >= locator.expires_at_ms THEN
            RAISE EXCEPTION 'WAL proof exact original ACK receipt unavailable' USING ERRCODE = '23514';
        END IF;
    ELSIF requested_phase <> 'original' OR locator.target_xid IS DISTINCT FROM locator.finalization_xid THEN
        RAISE EXCEPTION 'WAL proof phase unavailable' USING ERRCODE = '23514';
    END IF;
    SELECT c.system_identifier::text INTO cluster_value FROM pg_control_system() c;
    SELECT f.timeline_id INTO timeline_value
        FROM pg_split_walfile_name(pg_walfile_name(pg_current_wal_insert_lsn())) f;
    SELECT e.extversion INTO extension_value FROM pg_extension e
        JOIN pg_namespace n ON n.oid = e.extnamespace
        WHERE e.extname = 'pg_walinspect' AND n.nspname = 'wal_proof_extension';
    PERFORM __S__.wal_proof_environment(current_setting('server_version_num')::integer,
        pg_is_in_recovery(), current_setting('fsync'), extension_value,
        locator.system_identifier, cluster_value, locator.insertion_timeline, timeline_value);
    -- Independent statement; no marker, target flush request, clock substitution or widened retry.
    upper_value := pg_current_wal_flush_lsn();
    IF upper_value < locator.search_lower_lsn OR upper_value - locator.search_lower_lsn > 1048576 THEN
        RAISE EXCEPTION 'WAL proof search budget unavailable' USING ERRCODE = '23514';
    END IF;
    SELECT coalesce(jsonb_agg(to_jsonb(r)), '[]'::jsonb) INTO records_value
        FROM wal_proof_extension.pg_get_wal_records_info(locator.search_lower_lsn, upper_value) r;
    flush_value := pg_current_wal_flush_lsn();
    SELECT * INTO boundary FROM __S__.wal_proof_classify(locator.target_xid,
        pg_snapshot_xmax(fixed_snapshot)::text, locator.search_lower_lsn, upper_value,
        flush_value, records_value);
    SELECT c.system_identifier::text INTO cluster_value FROM pg_control_system() c;
    SELECT f.timeline_id INTO timeline_value
        FROM pg_split_walfile_name(pg_walfile_name(pg_current_wal_insert_lsn())) f;
    PERFORM __S__.wal_proof_environment(current_setting('server_version_num')::integer,
        pg_is_in_recovery(), current_setting('fsync'), extension_value,
        locator.system_identifier, cluster_value, locator.insertion_timeline, timeline_value);
    -- original phase is diagnostic only: WAL never reconstructs a missing typed original ACK.
    RETURN QUERY SELECT locator.target_xid, boundary.record_start, boundary.record_end,
        flush_value, receipt.committed_before_ms, locator.expires_at_ms;
END;
$$;
ALTER FUNCTION __S__.wal_proof_read(UUID, TEXT, TEXT, UUID) OWNER TO __S___owner;
REVOKE ALL ON FUNCTION __S__.wal_proof_read(UUID, TEXT, TEXT, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION __S__.wal_proof_read(UUID, TEXT, TEXT, UUID) TO __S___reader;
REVOKE CREATE ON SCHEMA __S__ FROM __S___owner;
