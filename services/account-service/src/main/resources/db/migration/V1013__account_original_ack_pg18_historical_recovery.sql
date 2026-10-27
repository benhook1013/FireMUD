-- Qualify PostgreSQL 18 historical original-ACK recovery through the existing canonical reader.
-- Preserve V144/V146 and every original receipt/operation byte. PostgreSQL 16 remains supported;
-- other majors remain denied. This read observes durability and never reconstructs the original
-- physical-COMMIT acknowledgement, restamps its clock bound, writes a marker or forces WAL flush.
-- The private owner ends capture before observing coverage, without lowering or recapturing
-- the fence. The legacy direct SQL reader remains strict while holding its capture locks.
-- PostgreSQL 18 retains the required read-only WAL insert/flush observations, top-level XID
-- semantics and SERIALIZABLE snapshot isolation:
-- https://www.postgresql.org/docs/18/functions-admin.html#FUNCTIONS-ADMIN-BACKUP
-- https://www.postgresql.org/docs/18/functions-info.html#FUNCTIONS-TXID-SNAPSHOT
-- https://www.postgresql.org/docs/18/transaction-iso.html#XACT-SERIALIZABLE
-- [jooq ignore start]
CREATE FUNCTION account_gameplay_admission_capture_original_ack_receipt(
    requested_request_id UUID, expected_sha TEXT, expected_decision UUID)
RETURNS TABLE (
    captured_receipt account_gameplay_admission_original_commit_ack_receipts,
    wal_upper_fence pg_lsn,
    capture_snapshot pg_snapshot,
    capture_own_xid TEXT,
    capture_initial_flush pg_lsn)
LANGUAGE plpgsql AS $$
DECLARE
    operation account_gameplay_admission_lease_operations%ROWTYPE;
    receipt account_gameplay_admission_original_commit_ack_receipts%ROWTYPE;
    locked_operation account_gameplay_admission_lease_operations%ROWTYPE;
    locked_receipt account_gameplay_admission_original_commit_ack_receipts%ROWTYPE;
    own_xid TEXT;
    fixed_snapshot pg_snapshot;
    insert_fence pg_lsn;
    initial_flush_fence pg_lsn;
BEGIN
    IF current_setting('transaction_isolation') IS DISTINCT FROM 'serializable'
        OR current_setting('transaction_read_only') IS DISTINCT FROM 'off' THEN
        RAISE EXCEPTION 'Writable SERIALIZABLE Account acknowledgement receipt read required'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_transaction_required';
    END IF;
    IF NOT ((current_setting('server_version_num')::integer >= 160000
            AND current_setting('server_version_num')::integer < 170000)
        OR (current_setting('server_version_num')::integer >= 180000
            AND current_setting('server_version_num')::integer < 190000))
        OR pg_is_in_recovery() OR current_setting('fsync') IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'Supported durable PostgreSQL 16 or 18 primary required for Account acknowledgement receipt read'
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

    -- This is pending evidence only. The private owner must independently observe durable
    -- coverage of this exact frozen fence after the capture transaction successfully completes.
    RETURN QUERY SELECT receipt, insert_fence, fixed_snapshot, own_xid, initial_flush_fence;
END;
$$;

CREATE OR REPLACE FUNCTION account_gameplay_admission_read_original_ack_receipt_durably(
    requested_request_id UUID, expected_sha TEXT, expected_decision UUID)
RETURNS SETOF account_gameplay_admission_original_commit_ack_receipts LANGUAGE plpgsql AS $$
DECLARE
    captured RECORD;
    receipt account_gameplay_admission_original_commit_ack_receipts%ROWTYPE;
    flush_fence pg_lsn;
    observation INTEGER;
BEGIN
    SELECT * INTO STRICT captured FROM account_gameplay_admission_capture_original_ack_receipt(
        requested_request_id, expected_sha, expected_decision);
    receipt := captured.captured_receipt;
    -- Direct SQL callers retain the strict in-transaction proof. A capture by itself is never a
    -- durable result; private owner continuation observes this same fence outside its SQL locks.
    FOR observation IN 1..50 LOOP
        flush_fence := pg_current_wal_flush_lsn();
        EXIT WHEN flush_fence IS NOT NULL AND flush_fence >= captured.wal_upper_fence;
        IF observation = 50 THEN
            RAISE EXCEPTION 'Original Account acknowledgement receipt WAL coverage unavailable'
                USING ERRCODE = '23514', CONSTRAINT = 'account_admission_original_ack_receipt_wal_coverage',
                    DETAIL = format('snapshot=%s own_xid_if_assigned=%s finalization_xid=%s receipt_xid=%s insert_fence=%s initial_flush=%s final_flush=%s unchanged_expiry_ms=%s',
                        captured.capture_snapshot, captured.capture_own_xid,
                        receipt.finalization_xid, receipt.receipt_xid, captured.wal_upper_fence,
                        captured.capture_initial_flush, flush_fence, receipt.expires_at_ms);
        END IF;
        PERFORM pg_sleep(0.01);
    END LOOP;
    RETURN NEXT receipt;
END;
$$;
-- [jooq ignore stop]
