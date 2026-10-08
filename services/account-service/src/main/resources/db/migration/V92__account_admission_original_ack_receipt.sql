-- Persist only an opaque trusted-JVM acknowledgement of the original physical COMMIT and its
-- subsequent database-clock bound. The bound is not SQL-derived or authenticated by this schema.
-- This receipt supplies no current authority or gameplay-admission result.
CREATE TABLE account_gameplay_admission_original_commit_ack_receipts (
    schema_version SMALLINT NOT NULL DEFAULT 1 CHECK (schema_version = 1),
    request_id UUID PRIMARY KEY,
    account_uuid UUID NOT NULL,
    lease_id UUID NOT NULL,
    lease_fence BIGINT NOT NULL CHECK (lease_fence > 0),
    evidence_sha256 VARCHAR(64) NOT NULL CHECK (evidence_sha256 ~ '^[0-9a-f]{64}$'),
    binding_decision_id UUID NOT NULL,
    expires_at_ms BIGINT NOT NULL CHECK (expires_at_ms > 0),
    finalization_xid VARCHAR(20) NOT NULL,
    committed_before_ms BIGINT NOT NULL CHECK (
        committed_before_ms > 0 AND committed_before_ms < expires_at_ms),
    receipt_xid VARCHAR(20) NOT NULL,
    FOREIGN KEY (request_id, account_uuid, lease_id, lease_fence, evidence_sha256,
        binding_decision_id, expires_at_ms, finalization_xid)
        REFERENCES account_gameplay_admission_lease_operations
            (request_id, account_uuid, lease_id, lease_fence, evidence_sha256,
                binding_decision_id, expires_at_ms, finalization_xid)
);

-- The typed JVM acknowledgement supplies committed_before_ms. SQL validates its shape and exact
-- immutable operation binding, but cannot authenticate that opaque capability or prove its clock.
-- [jooq ignore start]
ALTER TABLE account_gameplay_admission_original_commit_ack_receipts
    ADD CONSTRAINT account_admission_original_ack_receipt_xid_check CHECK (
        CASE WHEN finalization_xid ~ '^[1-9][0-9]{0,19}$'
                AND receipt_xid ~ '^[1-9][0-9]{0,19}$' THEN
            finalization_xid::numeric <= 18446744073709551615
                AND receipt_xid::numeric <= 18446744073709551615
                AND finalization_xid <> receipt_xid
            ELSE FALSE END);

CREATE FUNCTION account_gameplay_admission_original_ack_receipt_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    operation account_gameplay_admission_lease_operations%ROWTYPE;
    locked_operation account_gameplay_admission_lease_operations%ROWTYPE;
    receipt account_gameplay_admission_original_commit_ack_receipts%ROWTYPE;
    locked_receipt account_gameplay_admission_original_commit_ack_receipts%ROWTYPE;
    own_xid TEXT;
    finalization_status TEXT;
    version_num INTEGER;
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'Original Account COMMIT acknowledgement receipt is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_immutable';
    END IF;
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Original Account COMMIT acknowledgement receipt is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_immutable';
    END IF;
    IF NEW.schema_version IS DISTINCT FROM 1
        OR NEW.account_uuid IS NOT NULL OR NEW.lease_id IS NOT NULL
        OR NEW.lease_fence IS NOT NULL OR NEW.expires_at_ms IS NOT NULL
        OR NEW.receipt_xid IS NOT NULL THEN
        RAISE EXCEPTION 'Original Account COMMIT acknowledgement receipt binding is database derived'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_db_binding';
    END IF;
    IF current_setting('transaction_isolation') IS DISTINCT FROM 'serializable'
        OR current_setting('transaction_read_only') IS DISTINCT FROM 'off' THEN
        RAISE EXCEPTION 'Writable SERIALIZABLE Account acknowledgement receipt transaction required'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_transaction_required';
    END IF;
    version_num := current_setting('server_version_num')::integer;
    IF version_num < 160000 OR version_num >= 170000
        OR pg_is_in_recovery() OR current_setting('fsync') IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'Supported durable PostgreSQL 16 primary required for Account acknowledgement receipt'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_durable_primary';
    END IF;

    -- The request lookup only discovers the owning Account. All locked reads follow Account first.
    SELECT * INTO operation FROM account_gameplay_admission_lease_operations
        WHERE request_id = NEW.request_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Original Account COMMIT acknowledgement operation missing'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_operation_required';
    END IF;
    PERFORM 1 FROM accounts WHERE account_uuid = operation.account_uuid FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Original Account COMMIT acknowledgement owner missing'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_owner_required';
    END IF;
    SELECT * INTO locked_operation FROM account_gameplay_admission_lease_operations
        WHERE request_id = NEW.request_id FOR UPDATE;
    IF NOT FOUND OR locked_operation IS DISTINCT FROM operation
        OR locked_operation.status IS DISTINCT FROM 'COMMITTED'
        OR locked_operation.finalization_xid IS NULL
        OR NEW.evidence_sha256 IS DISTINCT FROM locked_operation.evidence_sha256
        OR NEW.binding_decision_id IS DISTINCT FROM locked_operation.binding_decision_id
        OR NEW.finalization_xid IS DISTINCT FROM locked_operation.finalization_xid
        OR NEW.committed_before_ms <= 0
        OR NEW.committed_before_ms >= locked_operation.expires_at_ms THEN
        RAISE EXCEPTION 'Exact original Account COMMIT acknowledgement required'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_binding_required';
    END IF;

    SELECT * INTO receipt FROM account_gameplay_admission_original_commit_ack_receipts
        WHERE request_id = NEW.request_id;
    IF FOUND THEN
        SELECT * INTO locked_receipt FROM account_gameplay_admission_original_commit_ack_receipts
            WHERE request_id = NEW.request_id FOR UPDATE;
        IF NOT FOUND OR locked_receipt IS DISTINCT FROM receipt
            OR receipt.schema_version IS DISTINCT FROM 1
            OR receipt.account_uuid IS DISTINCT FROM locked_operation.account_uuid
            OR receipt.lease_id IS DISTINCT FROM locked_operation.lease_id
            OR receipt.lease_fence IS DISTINCT FROM locked_operation.lease_fence
            OR receipt.evidence_sha256 IS DISTINCT FROM locked_operation.evidence_sha256
            OR receipt.binding_decision_id IS DISTINCT FROM locked_operation.binding_decision_id
            OR receipt.expires_at_ms IS DISTINCT FROM locked_operation.expires_at_ms
            OR receipt.finalization_xid IS DISTINCT FROM locked_operation.finalization_xid
            OR receipt.committed_before_ms IS DISTINCT FROM NEW.committed_before_ms THEN
            RAISE EXCEPTION 'Original Account COMMIT acknowledgement receipt retry conflicts'
                USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_conflict';
        END IF;
        -- An exact retained ACK retry never rewrites or restamps the original receipt.
        RETURN NULL;
    END IF;

    own_xid := pg_current_xact_id()::text;
    IF own_xid = locked_operation.finalization_xid THEN
        RAISE EXCEPTION 'Original Account COMMIT acknowledgement receipt requires independent transaction'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_independent';
    END IF;
    BEGIN
        finalization_status := pg_xact_status(locked_operation.finalization_xid::xid8);
    EXCEPTION WHEN OTHERS THEN
        RAISE EXCEPTION 'Original Account finalization status unavailable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_finalization_unavailable';
    END;
    IF finalization_status IS DISTINCT FROM 'committed' THEN
        RAISE EXCEPTION 'Original Account finalization is not independently committed'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_independent';
    END IF;

    NEW.account_uuid := locked_operation.account_uuid;
    NEW.lease_id := locked_operation.lease_id;
    NEW.lease_fence := locked_operation.lease_fence;
    NEW.expires_at_ms := locked_operation.expires_at_ms;
    NEW.receipt_xid := own_xid;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_admission_original_ack_receipt_guard
    BEFORE INSERT OR UPDATE OR DELETE
    ON account_gameplay_admission_original_commit_ack_receipts FOR EACH ROW
    EXECUTE FUNCTION account_gameplay_admission_original_ack_receipt_guard();
CREATE TRIGGER account_admission_original_ack_receipt_truncate_guard
    BEFORE TRUNCATE ON account_gameplay_admission_original_commit_ack_receipts FOR EACH STATEMENT
    EXECUTE FUNCTION account_gameplay_admission_original_ack_receipt_guard();

CREATE FUNCTION account_gameplay_admission_read_original_ack_receipt_durably(
    requested_request_id UUID, expected_sha TEXT, expected_decision UUID)
RETURNS SETOF account_gameplay_admission_original_commit_ack_receipts LANGUAGE plpgsql AS $$
DECLARE
    operation account_gameplay_admission_lease_operations%ROWTYPE;
    receipt account_gameplay_admission_original_commit_ack_receipts%ROWTYPE;
    locked_operation account_gameplay_admission_lease_operations%ROWTYPE;
    locked_receipt account_gameplay_admission_original_commit_ack_receipts%ROWTYPE;
    own_xid TEXT;
    fixed_snapshot pg_snapshot;
    insert_fence pg_lsn;
    initial_flush_fence pg_lsn;
    flush_fence pg_lsn;
    observation INTEGER;
BEGIN
    IF current_setting('transaction_isolation') IS DISTINCT FROM 'serializable'
        OR current_setting('transaction_read_only') IS DISTINCT FROM 'off' THEN
        RAISE EXCEPTION 'Writable SERIALIZABLE Account acknowledgement receipt read required'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_transaction_required';
    END IF;
    IF current_setting('server_version_num')::integer < 160000
        OR current_setting('server_version_num')::integer >= 170000
        OR pg_is_in_recovery() OR current_setting('fsync') IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'Supported durable PostgreSQL 16 primary required for Account acknowledgement receipt read'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_durable_primary';
    END IF;
    -- Establish one fixed snapshot and its WAL upper fence before any heap read or row lock.
    -- A receipt visible in this snapshot committed independently before this fence.
    fixed_snapshot := pg_current_snapshot();
    own_xid := pg_current_xact_id_if_assigned()::text;
    insert_fence := pg_current_wal_insert_lsn();
    initial_flush_fence := pg_current_wal_flush_lsn();
    IF insert_fence IS NULL OR insert_fence <= '0/0'::pg_lsn THEN
        RAISE EXCEPTION 'Original Account acknowledgement receipt WAL coverage unavailable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_wal_coverage';
    END IF;

    SELECT * INTO operation FROM account_gameplay_admission_lease_operations
        WHERE request_id = requested_request_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Original Account acknowledgement operation missing'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_operation_required';
    END IF;
    SELECT * INTO receipt FROM account_gameplay_admission_original_commit_ack_receipts
        WHERE request_id = requested_request_id;
    IF NOT FOUND OR operation.status IS DISTINCT FROM 'COMMITTED'
        OR operation.finalization_xid IS NULL
        OR expected_sha IS DISTINCT FROM operation.evidence_sha256
        OR expected_decision IS DISTINCT FROM operation.binding_decision_id
        OR receipt.schema_version IS DISTINCT FROM 1
        OR receipt.account_uuid IS DISTINCT FROM operation.account_uuid
        OR receipt.lease_id IS DISTINCT FROM operation.lease_id
        OR receipt.lease_fence IS DISTINCT FROM operation.lease_fence
        OR receipt.evidence_sha256 IS DISTINCT FROM operation.evidence_sha256
        OR receipt.binding_decision_id IS DISTINCT FROM operation.binding_decision_id
        OR receipt.expires_at_ms IS DISTINCT FROM operation.expires_at_ms
        OR receipt.finalization_xid IS DISTINCT FROM operation.finalization_xid
        OR receipt.committed_before_ms <= 0
        OR receipt.committed_before_ms >= operation.expires_at_ms THEN
        RAISE EXCEPTION 'Exact durable original Account acknowledgement receipt required'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_required';
    END IF;
    -- receipt_xid is the database-stamped top-level XID, so this excludes the current transaction
    -- and any released subtransaction while retaining an independently committed historical row.
    IF receipt.receipt_xid = own_xid THEN
        RAISE EXCEPTION 'Original Account acknowledgement receipt requires independent commit'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_independent';
    END IF;

    PERFORM 1 FROM accounts WHERE account_uuid = operation.account_uuid FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Original Account acknowledgement owner missing'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_owner_required';
    END IF;
    SELECT * INTO locked_operation FROM account_gameplay_admission_lease_operations
        WHERE request_id = requested_request_id FOR UPDATE;
    IF NOT FOUND OR locked_operation IS DISTINCT FROM operation THEN
        RAISE EXCEPTION 'Exact durable original Account acknowledgement receipt required'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_required';
    END IF;
    SELECT * INTO locked_receipt FROM account_gameplay_admission_original_commit_ack_receipts
        WHERE request_id = requested_request_id FOR UPDATE;
    IF NOT FOUND OR locked_receipt IS DISTINCT FROM receipt THEN
        RAISE EXCEPTION 'Exact durable original Account acknowledgement receipt required'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_required';
    END IF;

    -- Bounded observation checks current WAL coverage only. It creates no marker, forces no flush,
    -- consults no new clock, and never changes the original committed_before_ms or expiry.
    FOR observation IN 1..50 LOOP
        flush_fence := pg_current_wal_flush_lsn();
        EXIT WHEN flush_fence IS NOT NULL AND flush_fence >= insert_fence;
        IF observation = 50 THEN
            RAISE EXCEPTION 'Original Account acknowledgement receipt WAL coverage unavailable'
                USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_wal_coverage',
                    DETAIL = format('snapshot=%s own_xid_if_assigned=%s finalization_xid=%s receipt_xid=%s insert_fence=%s initial_flush=%s final_flush=%s unchanged_expiry_ms=%s',
                        fixed_snapshot, own_xid, operation.finalization_xid, receipt.receipt_xid,
                        insert_fence, initial_flush_fence, flush_fence, operation.expires_at_ms);
        END IF;
        PERFORM pg_sleep(0.01);
    END LOOP;
    RETURN NEXT receipt;
END;
$$;
-- [jooq ignore stop]
