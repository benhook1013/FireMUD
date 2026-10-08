-- Storage foundation only. No row, fence or terminal status establishes authenticated admission.
-- The lease fence is an independent per-Account domain, never an issuance/token fence.
CREATE TABLE account_gameplay_admission_lease_fences (
    account_uuid UUID PRIMARY KEY REFERENCES accounts(account_uuid)
        CHECK (account_uuid::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    last_fence BIGINT NOT NULL CHECK (last_fence > 0)
);

CREATE TABLE account_gameplay_admission_lease_allocations (
    account_uuid UUID NOT NULL REFERENCES accounts(account_uuid)
        CHECK (account_uuid::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    request_id UUID PRIMARY KEY CHECK (request_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    lease_id UUID NOT NULL UNIQUE CHECK (lease_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    lease_fence BIGINT NOT NULL CHECK (lease_fence > 0),
    UNIQUE (account_uuid, lease_fence),
    UNIQUE (account_uuid, request_id, lease_id, lease_fence)
);

CREATE TABLE account_gameplay_admission_lease_operations (
    account_uuid UUID NOT NULL,
    request_id UUID PRIMARY KEY,
    lease_id UUID NOT NULL UNIQUE,
    lease_fence BIGINT NOT NULL,
    caller_workload TEXT NOT NULL,
    evidence_json TEXT NOT NULL CHECK (octet_length(evidence_json) BETWEEN 1 AND 65536),
    evidence_sha256 VARCHAR(64) NOT NULL CHECK (evidence_sha256 ~ '^[0-9a-f]{64}$'),
    evaluated_at_ms BIGINT NOT NULL CHECK (evaluated_at_ms > 0),
    expires_at_ms BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'COMMITTED', 'ABORTED')),
    binding_decision_id UUID CHECK (binding_decision_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    orphan_cleanup_id UUID UNIQUE CHECK (orphan_cleanup_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    FOREIGN KEY (account_uuid, request_id, lease_id, lease_fence)
        REFERENCES account_gameplay_admission_lease_allocations(account_uuid, request_id, lease_id, lease_fence),
    CHECK (expires_at_ms > evaluated_at_ms AND expires_at_ms - evaluated_at_ms <= 15000),
    CHECK ((status = 'PENDING' AND binding_decision_id IS NULL AND orphan_cleanup_id IS NULL)
        OR (status = 'COMMITTED' AND binding_decision_id IS NOT NULL AND orphan_cleanup_id IS NULL)
        OR (status = 'ABORTED' AND binding_decision_id IS NOT NULL AND orphan_cleanup_id IS NOT NULL))
);

-- PostgreSQL executes these exact digest and JSON identity constraints. The jOOQ/H2 schema
-- simulation cannot interpret their PostgreSQL expressions; omission from that simulation
-- does not replace physical PostgreSQL migration and negative-case proof.
-- [jooq ignore start]
ALTER TABLE account_gameplay_admission_lease_operations
    ADD CONSTRAINT account_admission_lease_evidence_digest_check
        CHECK (evidence_sha256 = encode(sha256(convert_to(evidence_json, 'UTF8')), 'hex'));
ALTER TABLE account_gameplay_admission_lease_operations
    ADD CONSTRAINT account_admission_lease_evidence_identity_check
        CHECK (COALESCE((evidence_json::jsonb ->> 'requestId') = request_id::text
            AND (evidence_json::jsonb ->> 'leaseId') = lease_id::text
            AND (evidence_json::jsonb ->> 'leaseFence') = lease_fence::text
            AND (evidence_json::jsonb ->> 'callerWorkload') = caller_workload
            AND (evidence_json::jsonb -> 'bindingScope' ->> 'accountId') = account_uuid::text
            AND (evidence_json::jsonb ->> 'evaluatedAt') = evaluated_at_ms::text
            AND (evidence_json::jsonb ->> 'expiresAt') = expires_at_ms::text, FALSE));
-- [jooq ignore stop]

-- ABORTED's immutable original evidence_json includes every exact orphan binding and token
-- coordinate. orphan_cleanup_id identifies durable PENDING work, not delivery/retirement success.
-- [jooq ignore start]
CREATE FUNCTION account_gameplay_admission_storage_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
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
        IF TG_OP = 'UPDATE' AND (OLD.status <> 'PENDING'
            OR (to_jsonb(NEW) - ARRAY['status', 'binding_decision_id', 'orphan_cleanup_id'])
                IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'binding_decision_id', 'orphan_cleanup_id'])) THEN
            RAISE EXCEPTION 'Exact lease evidence and terminal decisions are immutable' USING ERRCODE = '23514';
        END IF;
        IF (TG_OP = 'INSERT' OR NEW.status = 'COMMITTED')
            AND (NEW.evaluated_at_ms > floor(extract(epoch FROM clock_timestamp()) * 1000)
                OR NEW.expires_at_ms <= floor(extract(epoch FROM clock_timestamp()) * 1000)) THEN
            RAISE EXCEPTION 'Lease operation storage deadline expired' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_admission_fence_guard BEFORE INSERT OR UPDATE OR DELETE
    ON account_gameplay_admission_lease_fences FOR EACH ROW EXECUTE FUNCTION account_gameplay_admission_storage_guard();
CREATE TRIGGER account_admission_allocation_guard BEFORE INSERT OR UPDATE OR DELETE
    ON account_gameplay_admission_lease_allocations FOR EACH ROW EXECUTE FUNCTION account_gameplay_admission_storage_guard();
CREATE TRIGGER account_admission_operation_guard BEFORE INSERT OR UPDATE OR DELETE
    ON account_gameplay_admission_lease_operations FOR EACH ROW EXECUTE FUNCTION account_gameplay_admission_storage_guard();
CREATE TRIGGER account_admission_fence_truncate_guard BEFORE TRUNCATE
    ON account_gameplay_admission_lease_fences FOR EACH STATEMENT EXECUTE FUNCTION account_gameplay_admission_storage_guard();
CREATE TRIGGER account_admission_allocation_truncate_guard BEFORE TRUNCATE
    ON account_gameplay_admission_lease_allocations FOR EACH STATEMENT EXECUTE FUNCTION account_gameplay_admission_storage_guard();
CREATE TRIGGER account_admission_operation_truncate_guard BEFORE TRUNCATE
    ON account_gameplay_admission_lease_operations FOR EACH STATEMENT EXECUTE FUNCTION account_gameplay_admission_storage_guard();
-- [jooq ignore stop]
