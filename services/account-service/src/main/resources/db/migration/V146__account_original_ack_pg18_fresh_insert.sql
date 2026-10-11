-- Converge only the fresh original-ACK receipt insertion guard to PostgreSQL 18. Historical
-- readDurably recovery remains fenced to its existing PostgreSQL 16 contract and is not widened.
-- [jooq ignore start]
DO $migration$
DECLARE
    guard_definition TEXT;
    reader_definition TEXT;
    guard_oid REGPROCEDURE := to_regprocedure(
        'account_gameplay_admission_original_ack_receipt_guard()');
BEGIN
    IF current_setting('server_version_num')::integer < 180000
        OR current_setting('server_version_num')::integer >= 190000 THEN
        RAISE EXCEPTION 'PostgreSQL 18 required for Account fresh original-ACK insert convergence';
    END IF;
    IF guard_oid IS NULL THEN
        RAISE EXCEPTION 'Account original-ACK insert guard missing before V139';
    END IF;
    SELECT pg_get_functiondef(guard_oid) INTO guard_definition;
    IF guard_definition IS NULL
        OR position('version_num < 160000 OR version_num >= 170000' IN guard_definition) = 0
        OR position('account_admission_original_ack_receipt_durable_primary' IN guard_definition) = 0
        OR position('pg_xact_status(locked_operation.finalization_xid::xid8)' IN guard_definition) = 0
        OR position('account_gameplay_admission_original_commit_ack_receipts' IN guard_definition) = 0
        OR NOT EXISTS (
            SELECT 1 FROM pg_trigger
            WHERE tgname = 'account_admission_original_ack_receipt_guard'
                AND tgfoid = guard_oid AND NOT tgisinternal) THEN
        RAISE EXCEPTION 'Account V122 original-ACK insert guard drifted before V139';
    END IF;
    SELECT pg_get_functiondef(
        to_regprocedure('account_gameplay_admission_read_original_ack_receipt_durably(uuid,text,uuid)'))
        INTO reader_definition;
    IF reader_definition IS NULL
        OR position('current_setting(''server_version_num'')::integer < 160000' IN reader_definition) = 0
        OR position('current_setting(''server_version_num'')::integer >= 170000' IN reader_definition) = 0
        OR position('Supported durable PostgreSQL 16 primary required for Account acknowledgement receipt read' IN reader_definition) = 0 THEN
        RAISE EXCEPTION 'Account V122 historical original-ACK reader drifted before V139';
    END IF;
END;
$migration$;

CREATE OR REPLACE FUNCTION account_gameplay_admission_original_ack_receipt_guard()
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
    IF version_num < 180000 OR version_num >= 190000
        OR pg_is_in_recovery() OR current_setting('fsync') IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'Supported durable PostgreSQL 18 primary required for Account acknowledgement receipt'
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
-- [jooq ignore stop]
