-- Temporal storage durability only: neither COMMITTED nor this confirmation grants admission.
-- Retained terminal rows are deliberately not backfilled from xmin or inferred transaction time.
ALTER TABLE account_gameplay_admission_lease_operations ADD COLUMN finalization_xid VARCHAR(20);

-- [jooq ignore start]
ALTER TABLE account_gameplay_admission_lease_operations
    ADD CONSTRAINT account_admission_finalization_xid_check CHECK (
        CASE WHEN finalization_xid IS NULL THEN TRUE
            WHEN finalization_xid ~ '^[1-9][0-9]{0,19}$' THEN
                status = 'COMMITTED' AND finalization_xid::numeric <= 18446744073709551615
            ELSE FALSE END);
-- [jooq ignore stop]

ALTER TABLE account_gameplay_admission_lease_operations
    ADD CONSTRAINT account_admission_confirmation_binding_unique
    UNIQUE (request_id, account_uuid, lease_id, lease_fence, evidence_sha256,
        binding_decision_id, expires_at_ms, finalization_xid);

CREATE TABLE account_gameplay_admission_commit_confirmations (
    confirmation_version SMALLINT NOT NULL DEFAULT 1 CHECK (confirmation_version = 1),
    request_id UUID PRIMARY KEY,
    account_uuid UUID NOT NULL,
    lease_id UUID NOT NULL,
    lease_fence BIGINT NOT NULL CHECK (lease_fence > 0),
    evidence_sha256 VARCHAR(64) NOT NULL CHECK (evidence_sha256 ~ '^[0-9a-f]{64}$'),
    binding_decision_id UUID NOT NULL,
    expires_at_ms BIGINT NOT NULL CHECK (expires_at_ms > 0),
    finalization_xid VARCHAR(20) NOT NULL,
    wal_insert_lsn TEXT NOT NULL,
    wal_flush_lsn TEXT NOT NULL,
    committed_before_ms BIGINT NOT NULL CHECK (committed_before_ms > 0 AND committed_before_ms < expires_at_ms),
    confirmation_xid VARCHAR(20) NOT NULL,
    FOREIGN KEY (request_id, account_uuid, lease_id, lease_fence, evidence_sha256,
        binding_decision_id, expires_at_ms, finalization_xid)
        REFERENCES account_gameplay_admission_lease_operations
            (request_id, account_uuid, lease_id, lease_fence, evidence_sha256,
                binding_decision_id, expires_at_ms, finalization_xid)
);

-- Only PostgreSQL-specific expressions and procedural enforcement are omitted from simulation.
-- All receipt columns and the exact original-operation foreign key remain visible to jOOQ.
-- [jooq ignore start]
ALTER TABLE account_gameplay_admission_commit_confirmations
    ADD CONSTRAINT account_admission_confirmation_proof_shape_check CHECK (
        CASE WHEN finalization_xid ~ '^[1-9][0-9]{0,19}$'
                AND confirmation_xid ~ '^[1-9][0-9]{0,19}$'
                AND wal_insert_lsn ~ '^(0|[1-9A-F][0-9A-F]{0,7})/(0|[1-9A-F][0-9A-F]{0,7})$'
                AND wal_flush_lsn ~ '^(0|[1-9A-F][0-9A-F]{0,7})/(0|[1-9A-F][0-9A-F]{0,7})$' THEN
            finalization_xid::numeric <= 18446744073709551615
                AND confirmation_xid::numeric <= 18446744073709551615
                AND finalization_xid <> confirmation_xid
                AND wal_insert_lsn::pg_lsn > '0/0'::pg_lsn
                AND wal_flush_lsn::pg_lsn >= wal_insert_lsn::pg_lsn
            ELSE FALSE END);

-- Preserve every V87 guard and the V89 terminal shape; stamp only the original transition.
CREATE OR REPLACE FUNCTION account_gameplay_admission_storage_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'Account admission storage evidence cannot be removed' USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'account_gameplay_admission_lease_fences' THEN
        PERFORM 1 FROM accounts WHERE account_uuid = NEW.account_uuid FOR UPDATE;
        IF TG_OP = 'INSERT' THEN
            IF NEW.last_fence <> 1 THEN RAISE EXCEPTION 'Lease fence must start at one' USING ERRCODE = '23514'; END IF;
        ELSIF NEW.account_uuid IS DISTINCT FROM OLD.account_uuid OR NEW.last_fence <> OLD.last_fence + 1 THEN
            RAISE EXCEPTION 'Lease fence must advance exactly once' USING ERRCODE = '23514';
        END IF;
    ELSIF TG_TABLE_NAME = 'account_gameplay_admission_lease_allocations' THEN
        IF TG_OP = 'UPDATE' THEN
            RAISE EXCEPTION 'Lease allocation is immutable' USING ERRCODE = '23514';
        END IF;
        PERFORM 1 FROM accounts WHERE account_uuid = NEW.account_uuid FOR UPDATE;
        IF NOT EXISTS (SELECT 1 FROM account_gameplay_admission_lease_fences
            WHERE account_uuid = NEW.account_uuid AND last_fence = NEW.lease_fence) THEN
            RAISE EXCEPTION 'Lease allocation must bind current Account allocator' USING ERRCODE = '23514';
        END IF;
    ELSE
        IF TG_OP = 'INSERT' AND NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION 'Lease operation must start pending' USING ERRCODE = '23514';
        END IF;
        IF (TG_OP = 'INSERT' AND NEW.finalization_xid IS NOT NULL)
            OR (TG_OP = 'UPDATE' AND NEW.finalization_xid IS DISTINCT FROM OLD.finalization_xid) THEN
            RAISE EXCEPTION 'Account admission finalization transaction is database stamped'
                USING ERRCODE = '23514', CONSTRAINT = 'account_admission_finalization_xid_db_stamp';
        END IF;
        IF TG_OP = 'UPDATE' AND (OLD.status <> 'PENDING'
            OR (to_jsonb(NEW) - ARRAY['status', 'binding_decision_id', 'orphan_cleanup_id', 'finalization_xid'])
                IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'binding_decision_id', 'orphan_cleanup_id', 'finalization_xid'])) THEN
            RAISE EXCEPTION 'Exact lease evidence and terminal decisions are immutable' USING ERRCODE = '23514';
        END IF;
        IF (TG_OP = 'INSERT' OR NEW.status = 'COMMITTED')
            AND (NEW.evaluated_at_ms > floor(extract(epoch FROM clock_timestamp()) * 1000)
                OR NEW.expires_at_ms <= floor(extract(epoch FROM clock_timestamp()) * 1000)) THEN
            RAISE EXCEPTION 'Lease operation storage deadline expired' USING ERRCODE = '23514';
        END IF;
        IF TG_OP = 'UPDATE' AND NEW.status = 'COMMITTED' THEN
            NEW.finalization_xid := pg_current_xact_id()::text;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_gameplay_admission_confirmation_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    operation account_gameplay_admission_lease_operations%ROWTYPE;
    locked_operation account_gameplay_admission_lease_operations%ROWTYPE;
    receipt account_gameplay_admission_commit_confirmations%ROWTYPE;
    locked_receipt account_gameplay_admission_commit_confirmations%ROWTYPE;
    own_xid TEXT;
    finalization_status TEXT;
    insert_fence pg_lsn;
    flush_fence pg_lsn;
    observed_before_ms BIGINT;
    observation INTEGER;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Account admission confirmation is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_immutable';
    END IF;
    IF NEW.confirmation_version IS DISTINCT FROM 1 OR NEW.account_uuid IS NOT NULL
        OR NEW.lease_id IS NOT NULL OR NEW.lease_fence IS NOT NULL OR NEW.expires_at_ms IS NOT NULL
        OR NEW.finalization_xid IS NOT NULL OR NEW.wal_insert_lsn IS NOT NULL
        OR NEW.wal_flush_lsn IS NOT NULL OR NEW.committed_before_ms IS NOT NULL
        OR NEW.confirmation_xid IS NOT NULL THEN
        RAISE EXCEPTION 'Account admission confirmation proof is database derived'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_db_proof';
    END IF;
    IF current_setting('transaction_isolation') IS DISTINCT FROM 'serializable'
        OR current_setting('transaction_read_only') IS DISTINCT FROM 'off' THEN
        RAISE EXCEPTION 'Account admission confirmation writable SERIALIZABLE transaction required'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_transaction_required';
    END IF;
    IF pg_is_in_recovery() OR current_setting('fsync') IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'Account admission confirmation durable primary required'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_durable_primary';
    END IF;
    -- Establish the fixed transaction snapshot before the fence and before any heap read.
    -- A subsequently visible independent row committed before this snapshot, hence before
    -- this WAL upper bound. Even a SELECT can emit heap-pruning WAL; capture must precede it.
    PERFORM pg_current_snapshot();
    own_xid := pg_current_xact_id_if_assigned()::text;
    insert_fence := pg_current_wal_insert_lsn();
    IF insert_fence IS NULL OR insert_fence <= '0/0'::pg_lsn THEN
        RAISE EXCEPTION 'Account admission confirmation WAL coverage unavailable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_wal_coverage';
    END IF;
    SELECT * INTO operation FROM account_gameplay_admission_lease_operations WHERE request_id = NEW.request_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account admission confirmation operation missing'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_operation_required';
    END IF;
    IF operation.status <> 'COMMITTED' OR operation.finalization_xid IS NULL
        OR NEW.evidence_sha256 IS DISTINCT FROM operation.evidence_sha256
        OR NEW.binding_decision_id IS DISTINCT FROM operation.binding_decision_id THEN
        RAISE EXCEPTION 'Account admission confirmation exact committed binding required'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_binding_required';
    END IF;
    -- The stamp is top-level even when finalization ran in a released subtransaction.
    IF operation.finalization_xid = own_xid THEN
        RAISE EXCEPTION 'Account admission confirmation requires independent finalization commit'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_finalization_independent';
    END IF;
    SELECT * INTO receipt FROM account_gameplay_admission_commit_confirmations WHERE request_id = NEW.request_id;
    PERFORM 1 FROM accounts WHERE account_uuid = operation.account_uuid FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account admission confirmation owner missing'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_owner_required';
    END IF;
    SELECT * INTO locked_operation FROM account_gameplay_admission_lease_operations
        WHERE request_id = NEW.request_id FOR UPDATE;
    IF NOT FOUND OR locked_operation IS DISTINCT FROM operation THEN
        RAISE EXCEPTION 'Account admission confirmation exact committed binding required'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_binding_required';
    END IF;
    IF receipt.request_id IS NOT NULL THEN
        SELECT * INTO locked_receipt FROM account_gameplay_admission_commit_confirmations
            WHERE request_id = NEW.request_id FOR UPDATE;
        IF NOT FOUND OR locked_receipt IS DISTINCT FROM receipt
            OR receipt.account_uuid IS DISTINCT FROM operation.account_uuid
            OR receipt.lease_id IS DISTINCT FROM operation.lease_id
            OR receipt.lease_fence IS DISTINCT FROM operation.lease_fence
            OR receipt.evidence_sha256 IS DISTINCT FROM operation.evidence_sha256
            OR receipt.binding_decision_id IS DISTINCT FROM operation.binding_decision_id
            OR receipt.expires_at_ms IS DISTINCT FROM operation.expires_at_ms
            OR receipt.finalization_xid IS DISTINCT FROM operation.finalization_xid THEN
            RAISE EXCEPTION 'Account admission confirmation immutable binding conflict'
                USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_binding_conflict';
        END IF;
        -- Suppress an exact replay INSERT, including after expiry. The caller reads the retained
        -- receipt; no new proof, XID, deadline or timing bound replaces its original evidence.
        RETURN NULL;
    END IF;
    BEGIN
        finalization_status := pg_xact_status(operation.finalization_xid::xid8);
    EXCEPTION WHEN OTHERS THEN
        RAISE EXCEPTION 'Account admission confirmation finalization proof unavailable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_finalization_unavailable';
    END;
    IF finalization_status IS DISTINCT FROM 'committed' THEN
        RAISE EXCEPTION 'Account admission confirmation requires independent finalization commit'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_finalization_independent';
    END IF;
    -- Account-first locking serializes first receipt creation, and exact locked revalidation
    -- preserves the independently observed immutable operation. Only the pre-lock fence needs
    -- coverage; this bounded polling creates no marker, forces no flush and renews no deadline.
    FOR observation IN 1..50 LOOP
        flush_fence := pg_current_wal_flush_lsn();
        -- A separate statement AFTER each flush observation is essential: SQL expression order
        -- is not a temporal guarantee. CEIL supplies a conservative DB wall-clock upper bound.
        observed_before_ms := ceil(extract(epoch FROM clock_timestamp()) * 1000)::bigint;
        IF observed_before_ms <= 0 OR observed_before_ms >= operation.expires_at_ms THEN
            RAISE EXCEPTION 'Account admission confirmation original deadline expired'
                USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_original_deadline';
        END IF;
        EXIT WHEN flush_fence IS NOT NULL AND flush_fence >= insert_fence;
        IF observation = 50 THEN
            RAISE EXCEPTION 'Account admission confirmation WAL coverage unavailable'
                USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_wal_coverage';
        END IF;
        PERFORM pg_sleep(0.01);
    END LOOP;
    NEW.account_uuid := operation.account_uuid;
    NEW.lease_id := operation.lease_id;
    NEW.lease_fence := operation.lease_fence;
    NEW.expires_at_ms := operation.expires_at_ms;
    NEW.finalization_xid := operation.finalization_xid;
    NEW.wal_insert_lsn := insert_fence::text;
    NEW.wal_flush_lsn := flush_fence::text;
    NEW.committed_before_ms := observed_before_ms;
    NEW.confirmation_xid := pg_current_xact_id()::text;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_admission_confirmation_guard BEFORE INSERT OR UPDATE OR DELETE
    ON account_gameplay_admission_commit_confirmations FOR EACH ROW
    EXECUTE FUNCTION account_gameplay_admission_confirmation_guard();
CREATE TRIGGER account_admission_confirmation_truncate_guard BEFORE TRUNCATE
    ON account_gameplay_admission_commit_confirmations FOR EACH STATEMENT
    EXECUTE FUNCTION account_gameplay_admission_confirmation_guard();

CREATE FUNCTION account_gameplay_admission_confirm_committed(
    requested_request_id UUID, expected_sha TEXT, expected_decision UUID)
RETURNS SETOF account_gameplay_admission_commit_confirmations LANGUAGE plpgsql AS $$
DECLARE
    operation account_gameplay_admission_lease_operations%ROWTYPE;
    receipt account_gameplay_admission_commit_confirmations%ROWTYPE;
BEGIN
    -- Enter the guard before either operation/receipt heap read. It owns the snapshot/fence
    -- and suppresses exact retained replay without renewing proof. A stale SERIALIZABLE
    -- conflict must surface as 40001 rather than a raw uniqueness failure.
    INSERT INTO account_gameplay_admission_commit_confirmations(request_id, evidence_sha256, binding_decision_id)
        VALUES (requested_request_id, expected_sha, expected_decision)
        ON CONFLICT (request_id) DO NOTHING RETURNING * INTO receipt;
    IF FOUND THEN
        RETURN NEXT receipt;
        RETURN;
    END IF;
    SELECT * INTO operation FROM account_gameplay_admission_lease_operations WHERE request_id = requested_request_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account admission confirmation operation missing'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_operation_required';
    END IF;
    IF operation.status <> 'COMMITTED' OR operation.finalization_xid IS NULL
        OR expected_sha IS DISTINCT FROM operation.evidence_sha256
        OR expected_decision IS DISTINCT FROM operation.binding_decision_id THEN
        RAISE EXCEPTION 'Account admission confirmation exact committed binding required'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_binding_required';
    END IF;
    -- Exact retained replay may occur after expiry. It locks Account first and revalidates the
    -- immutable original decision, but neither re-proves nor replaces the receipt's timing bound.
    PERFORM 1 FROM accounts WHERE account_uuid = operation.account_uuid FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account admission confirmation owner missing'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_owner_required';
    END IF;
    SELECT * INTO operation FROM account_gameplay_admission_lease_operations
        WHERE request_id = requested_request_id FOR UPDATE;
    IF NOT FOUND OR operation.status <> 'COMMITTED' OR operation.finalization_xid IS NULL
        OR expected_sha IS DISTINCT FROM operation.evidence_sha256
        OR expected_decision IS DISTINCT FROM operation.binding_decision_id THEN
        RAISE EXCEPTION 'Account admission confirmation exact committed binding required'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_binding_required';
    END IF;
    SELECT * INTO receipt FROM account_gameplay_admission_commit_confirmations
        WHERE request_id = requested_request_id FOR UPDATE;
    IF NOT FOUND OR receipt.account_uuid IS DISTINCT FROM operation.account_uuid
        OR receipt.lease_id IS DISTINCT FROM operation.lease_id
        OR receipt.lease_fence IS DISTINCT FROM operation.lease_fence
        OR receipt.evidence_sha256 IS DISTINCT FROM operation.evidence_sha256
        OR receipt.binding_decision_id IS DISTINCT FROM operation.binding_decision_id
        OR receipt.expires_at_ms IS DISTINCT FROM operation.expires_at_ms
        OR receipt.finalization_xid IS DISTINCT FROM operation.finalization_xid THEN
        RAISE EXCEPTION 'Account admission confirmation immutable binding conflict'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_binding_conflict';
    END IF;
    RETURN NEXT receipt;
END;
$$;

CREATE FUNCTION account_gameplay_admission_read_commit_confirmation(
    requested_request_id UUID, expected_sha TEXT, expected_decision UUID)
RETURNS SETOF account_gameplay_admission_commit_confirmations LANGUAGE plpgsql AS $$
DECLARE
    operation account_gameplay_admission_lease_operations%ROWTYPE;
    receipt account_gameplay_admission_commit_confirmations%ROWTYPE;
    locked_operation account_gameplay_admission_lease_operations%ROWTYPE;
    locked_receipt account_gameplay_admission_commit_confirmations%ROWTYPE;
    own_xid TEXT;
    insert_fence pg_lsn;
    flush_fence pg_lsn;
    observation INTEGER;
BEGIN
    IF current_setting('transaction_isolation') IS DISTINCT FROM 'serializable'
        OR current_setting('transaction_read_only') IS DISTINCT FROM 'off' THEN
        RAISE EXCEPTION 'Account admission confirmation writable SERIALIZABLE transaction required'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_transaction_required';
    END IF;
    IF pg_is_in_recovery() OR current_setting('fsync') IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'Account admission confirmation durable primary required'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_durable_primary';
    END IF;
    -- Fix the snapshot and WAL upper bound before heap reads or assigning our own XID.
    -- Independent receipt visibility in that same snapshot covers its prior COMMIT.
    PERFORM pg_current_snapshot();
    own_xid := pg_current_xact_id_if_assigned()::text;
    insert_fence := pg_current_wal_insert_lsn();
    IF insert_fence IS NULL OR insert_fence <= '0/0'::pg_lsn THEN
        RAISE EXCEPTION 'Account admission confirmation WAL coverage unavailable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_wal_coverage';
    END IF;
    SELECT * INTO operation FROM account_gameplay_admission_lease_operations WHERE request_id = requested_request_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account admission confirmation operation missing'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_operation_required';
    END IF;
    SELECT * INTO receipt FROM account_gameplay_admission_commit_confirmations WHERE request_id = requested_request_id;
    IF NOT FOUND OR operation.status IS DISTINCT FROM 'COMMITTED'
        OR expected_sha IS DISTINCT FROM operation.evidence_sha256
        OR expected_decision IS DISTINCT FROM operation.binding_decision_id
        OR receipt.account_uuid IS DISTINCT FROM operation.account_uuid
        OR receipt.lease_id IS DISTINCT FROM operation.lease_id
        OR receipt.lease_fence IS DISTINCT FROM operation.lease_fence
        OR receipt.evidence_sha256 IS DISTINCT FROM operation.evidence_sha256
        OR receipt.binding_decision_id IS DISTINCT FROM operation.binding_decision_id
        OR receipt.expires_at_ms IS DISTINCT FROM operation.expires_at_ms
        OR receipt.finalization_xid IS DISTINCT FROM operation.finalization_xid THEN
        RAISE EXCEPTION 'Account admission confirmation exact durable receipt required'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_receipt_required';
    END IF;
    -- MVCC visibility plus exclusion of our own top-level XID also excludes our subtransactions.
    -- Historical receipts need not retain pg_xact_status data forever: fresh WAL coverage below
    -- covers their independently visible original COMMIT without consulting vacuum-pruned status.
    IF receipt.confirmation_xid = own_xid THEN
        RAISE EXCEPTION 'Account admission confirmation requires independent receipt commit'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_receipt_independent';
    END IF;
    PERFORM 1 FROM accounts WHERE account_uuid = operation.account_uuid FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account admission confirmation owner missing'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_owner_required';
    END IF;
    SELECT * INTO locked_operation FROM account_gameplay_admission_lease_operations
        WHERE request_id = requested_request_id FOR UPDATE;
    IF NOT FOUND OR locked_operation IS DISTINCT FROM operation THEN
        RAISE EXCEPTION 'Account admission confirmation exact durable receipt required'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_receipt_required';
    END IF;
    SELECT * INTO locked_receipt FROM account_gameplay_admission_commit_confirmations
        WHERE request_id = requested_request_id FOR UPDATE;
    IF NOT FOUND OR locked_receipt IS DISTINCT FROM receipt THEN
        RAISE EXCEPTION 'Account admission confirmation exact durable receipt required'
            USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_receipt_required';
    END IF;
    FOR observation IN 1..50 LOOP
        flush_fence := pg_current_wal_flush_lsn();
        EXIT WHEN flush_fence IS NOT NULL AND flush_fence >= insert_fence;
        IF observation = 50 THEN
            RAISE EXCEPTION 'Account admission confirmation WAL coverage unavailable'
                USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_wal_coverage';
        END IF;
        PERFORM pg_sleep(0.01);
    END LOOP;
    -- Return immutable historical timing evidence even after expiry; never restamp or renew it.
    RETURN NEXT receipt;
END;
$$;
-- [jooq ignore stop]
