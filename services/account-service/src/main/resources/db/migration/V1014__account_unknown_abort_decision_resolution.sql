-- Resolve a retained unknown-decision abort without changing its original terminal evidence.
-- This linkage is pending Game Session cleanup evidence, never admission or completed cleanup.
CREATE TABLE account_gameplay_admission_abort_resolutions (
    request_id UUID PRIMARY KEY REFERENCES account_gameplay_admission_lease_operations(request_id),
    resolution_id UUID NOT NULL UNIQUE CHECK (resolution_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    original_lease_id UUID NOT NULL CHECK (original_lease_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    original_lease_fence BIGINT NOT NULL CHECK (original_lease_fence > 0),
    original_lease_sha256 VARCHAR(64) NOT NULL CHECK (original_lease_sha256 ~ '^[0-9a-f]{64}$'),
    orphan_cleanup_id UUID NOT NULL CHECK (orphan_cleanup_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    binding_decision_id UUID NOT NULL CHECK (binding_decision_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
);

-- [jooq ignore start]
CREATE FUNCTION account_gameplay_admission_abort_resolution_guard() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    operation account_gameplay_admission_lease_operations%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Account abort resolution is immutable' USING ERRCODE = '23514';
    END IF;
    IF current_setting('transaction_isolation') IS DISTINCT FROM 'serializable'
        OR current_setting('transaction_read_only') IS DISTINCT FROM 'off' THEN
        RAISE EXCEPTION 'Writable SERIALIZABLE Account abort resolution required' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO operation FROM account_gameplay_admission_lease_operations
        WHERE request_id = NEW.request_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Original Account abort operation missing' USING ERRCODE = '23514';
    END IF;
    PERFORM 1 FROM accounts WHERE account_uuid = operation.account_uuid FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Original Account abort owner missing' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO operation FROM account_gameplay_admission_lease_operations
        WHERE request_id = NEW.request_id FOR UPDATE;
    IF NOT FOUND OR operation.status IS DISTINCT FROM 'ABORTED'
        OR operation.binding_decision_id IS NOT NULL
        OR operation.lease_id IS DISTINCT FROM NEW.original_lease_id
        OR operation.lease_fence IS DISTINCT FROM NEW.original_lease_fence
        OR operation.evidence_sha256 IS DISTINCT FROM NEW.original_lease_sha256
        OR operation.orphan_cleanup_id IS DISTINCT FROM NEW.orphan_cleanup_id THEN
        RAISE EXCEPTION 'Exact original unknown-decision abort linkage required' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_abort_resolution_row_guard
    BEFORE INSERT OR UPDATE OR DELETE ON account_gameplay_admission_abort_resolutions
    FOR EACH ROW EXECUTE FUNCTION account_gameplay_admission_abort_resolution_guard();
CREATE TRIGGER account_abort_resolution_truncate_guard
    BEFORE TRUNCATE ON account_gameplay_admission_abort_resolutions
    FOR EACH STATEMENT EXECUTE FUNCTION account_gameplay_admission_abort_resolution_guard();
-- [jooq ignore stop]
