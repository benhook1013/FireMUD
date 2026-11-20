-- Keep the Account security source independently sequenced. Composite fence movement has
-- immutable, exact causal provenance; no membership transition is a security mutation.
CREATE TABLE account_leave_operations (
    request_id VARCHAR(256) PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    account_uuid UUID NOT NULL REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    tenant_uuid UUID NOT NULL,
    caller_binding VARCHAR(512) NOT NULL,
    request_digest TEXT NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'COMMITTED')),
    outcome TEXT CHECK (outcome IN ('LEFT', 'ALREADY_LEFT')),
    membership_id BIGINT REFERENCES account_tenant_membership(id) ON DELETE RESTRICT,
    before_membership_version BIGINT,
    before_membership_generation BIGINT,
    before_fence BIGINT,
    membership_version BIGINT,
    membership_authority_generation BIGINT,
    issuance_fence BIGINT,
    membership_event_stream_key TEXT,
    event_sequence BIGINT,
    event_id TEXT,
    event_digest TEXT,
    audit_event_id UUID REFERENCES account_audit_outbox(audit_event_id) ON DELETE RESTRICT,
    audit_payload_digest TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE account_issuance_fence_provenance (
    account_uuid UUID NOT NULL REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    issuance_fence BIGINT NOT NULL CHECK (issuance_fence > 0),
    source_version BIGINT NOT NULL CHECK (source_version = issuance_fence),
    prior_fence BIGINT NOT NULL CHECK (prior_fence >= 0),
    cause_kind TEXT NOT NULL CHECK (cause_kind IN ('BASELINE', 'CUTOVER', 'ACCOUNT', 'MEMBERSHIP')),
    tenant_uuid UUID,
    source_stream_key VARCHAR(2048) NOT NULL,
    event_sequence BIGINT NOT NULL CHECK (event_sequence >= 0),
    event_id TEXT,
    event_digest TEXT,
    prior_generation BIGINT NOT NULL CHECK (prior_generation >= 0),
    generation BIGINT NOT NULL CHECK (generation > 0),
    prior_authority_source_version BIGINT NOT NULL CHECK (prior_authority_source_version >= 0),
    authority_source_version BIGINT NOT NULL CHECK (authority_source_version > 0),
    created_transaction_id BIGINT NOT NULL DEFAULT txid_current(),
    PRIMARY KEY (account_uuid, issuance_fence),
    UNIQUE (source_stream_key, event_sequence),
    CHECK ((cause_kind = 'MEMBERSHIP' AND tenant_uuid IS NOT NULL)
        OR (cause_kind <> 'MEMBERSHIP' AND tenant_uuid IS NULL)),
    CHECK ((event_sequence = 0 AND event_id IS NULL AND event_digest IS NULL)
        OR (event_sequence > 0 AND event_id IS NOT NULL AND event_digest ~ '^sha256:[0-9a-f]{64}$'))
);

-- A cutover is not reconstructed history. Anchor only the exact currently verifiable V40
-- security source/fence position; preserve all original rows, event bytes, baselines and xmins.
-- [jooq ignore start]
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM account_authority_source_records s
        LEFT JOIN account_authority_issuance_fences f USING (account_uuid)
        LEFT JOIN account_authority_generations g ON g.scope_kind = 'ACCOUNT' AND g.account_uuid = s.account_uuid
        LEFT JOIN account_authority_outbox_streams stream ON stream.outbox_stream_key = s.outbox_stream_key
        WHERE s.scope_kind = 'ACCOUNT' AND (
            f.issuance_fence IS DISTINCT FROM s.current_issuance_fence
            OR f.source_version IS DISTINCT FROM s.current_issuance_fence_source_version
            OR g.generation IS DISTINCT FROM s.current_generation
            OR g.source_version IS DISTINCT FROM s.current_source_version
            OR stream.last_sequence IS DISTINCT FROM s.last_outbox_sequence
            OR (s.last_outbox_sequence > 0 AND NOT EXISTS (
                SELECT 1 FROM account_authority_outbox_events e
                WHERE (e.outbox_stream_key,e.outbox_sequence,e.event_id,e.event_digest)
                    = (s.outbox_stream_key,s.last_outbox_sequence,s.last_event_id,s.last_event_digest))))) THEN
        RAISE EXCEPTION 'Composite fence cutover lacks exact retained owner evidence' USING ERRCODE = '23514';
    END IF;
END;
$$;
INSERT INTO account_issuance_fence_provenance
    (account_uuid, issuance_fence, source_version, prior_fence, cause_kind,
     source_stream_key, event_sequence, event_id, event_digest,
     prior_generation, generation, prior_authority_source_version, authority_source_version)
SELECT account_uuid, current_issuance_fence, current_issuance_fence_source_version, 0, 'CUTOVER',
    outbox_stream_key, last_outbox_sequence, last_event_id, last_event_digest,
    0, current_generation, 0, current_source_version
FROM account_authority_source_records WHERE scope_kind = 'ACCOUNT';

CREATE FUNCTION account_fence_security_position_matches(
    account_id UUID, fence BIGINT, fence_version BIGINT, stream TEXT, sequence BIGINT,
    source_event_id TEXT, source_event_digest TEXT)
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM account_issuance_fence_provenance p
        WHERE p.account_uuid = account_id AND p.issuance_fence = fence AND p.source_version = fence_version
          AND p.cause_kind IN ('BASELINE','CUTOVER','ACCOUNT') AND p.tenant_uuid IS NULL
          AND p.source_stream_key = stream AND p.event_sequence = sequence
          AND p.event_id IS NOT DISTINCT FROM source_event_id
          AND p.event_digest IS NOT DISTINCT FROM source_event_digest);
$$;

CREATE FUNCTION account_has_current_fence_provenance(account_id UUID, fence BIGINT, fence_version BIGINT)
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM account_authority_issuance_fences f
        JOIN account_issuance_fence_provenance p ON p.account_uuid = f.account_uuid
          AND p.issuance_fence = f.issuance_fence AND p.source_version = f.source_version
        WHERE f.account_uuid = account_id AND f.issuance_fence = fence AND f.source_version = fence_version
          AND (p.event_sequence = 0 OR EXISTS (
              SELECT 1 FROM account_authority_outbox_events e
              WHERE (e.outbox_stream_key,e.outbox_sequence,e.event_id,e.event_digest)
                  = (p.source_stream_key,p.event_sequence,p.event_id,p.event_digest))));
$$;

CREATE FUNCTION account_fence_provenance_insert_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE previous account_issuance_fence_provenance%ROWTYPE;
BEGIN
    IF NEW.created_transaction_id <> txid_current() OR NEW.cause_kind = 'CUTOVER' THEN
        RAISE EXCEPTION 'Fence provenance requires its original owner transaction' USING ERRCODE = '23514';
    END IF;
    IF NEW.cause_kind = 'BASELINE' THEN
        IF NEW.issuance_fence <> 1 OR NEW.source_version <> 1 OR NEW.prior_fence <> 0
            OR NEW.event_sequence <> 0 OR NEW.prior_generation <> 0 OR NEW.generation <> 1
            OR NEW.prior_authority_source_version <> 0 OR NEW.authority_source_version <> 1
            OR NEW.source_stream_key <> 'account:auth-authority:v1:account/' || NEW.account_uuid::TEXT
            OR NOT EXISTS (SELECT 1 FROM account_authority_source_records s
                WHERE s.account_uuid = NEW.account_uuid AND s.scope_kind = 'ACCOUNT'
                  AND s.initialization_transaction_id = txid_current() AND s.last_outbox_sequence = 0) THEN
            RAISE EXCEPTION 'Fence baseline requires fresh exact Account source' USING ERRCODE = '23514';
        END IF;
    ELSE
        SELECT * INTO previous FROM account_issuance_fence_provenance
        WHERE account_uuid = NEW.account_uuid AND issuance_fence = NEW.prior_fence;
        IF previous.account_uuid IS NULL OR NEW.issuance_fence <> NEW.prior_fence + 1
            OR NEW.source_version <> previous.source_version + 1
            OR NEW.generation <> NEW.prior_generation + 1
            OR NEW.authority_source_version <> NEW.prior_authority_source_version + 1
            OR NEW.event_sequence <= 0 THEN
            RAISE EXCEPTION 'Fence provenance requires exact previous position and causal advance' USING ERRCODE = '23514';
        END IF;
        IF NEW.source_stream_key <> ('account:auth-authority:v1:' ||
            CASE WHEN NEW.cause_kind = 'ACCOUNT' THEN 'account/' || NEW.account_uuid::TEXT
            ELSE 'membership/' || NEW.account_uuid::TEXT || '/' || NEW.tenant_uuid::TEXT END) THEN
            RAISE EXCEPTION 'Fence causal stream differs from its exact scope' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_fence_provenance_insert BEFORE INSERT ON account_issuance_fence_provenance
FOR EACH ROW EXECUTE FUNCTION account_fence_provenance_insert_guard();

CREATE FUNCTION account_fence_provenance_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Fence provenance is immutable' USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER account_fence_provenance_no_change BEFORE UPDATE OR DELETE ON account_issuance_fence_provenance
FOR EACH ROW EXECUTE FUNCTION account_fence_provenance_immutable();
CREATE TRIGGER account_fence_provenance_no_truncate BEFORE TRUNCATE ON account_issuance_fence_provenance
FOR EACH STATEMENT EXECUTE FUNCTION account_fence_provenance_immutable();

CREATE FUNCTION account_source_record_fence_provenance() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.scope_kind = 'ACCOUNT' THEN
        INSERT INTO account_issuance_fence_provenance
            (account_uuid, issuance_fence, source_version, prior_fence, cause_kind,
             source_stream_key,event_sequence,event_id,event_digest,
             prior_generation,generation,prior_authority_source_version,authority_source_version)
        VALUES (NEW.account_uuid,NEW.current_issuance_fence,NEW.current_issuance_fence_source_version,
            CASE WHEN TG_OP = 'INSERT' THEN 0 ELSE NEW.current_issuance_fence - 1 END,
            CASE WHEN TG_OP = 'INSERT' THEN 'BASELINE' ELSE 'ACCOUNT' END,
            NEW.outbox_stream_key,NEW.last_outbox_sequence,NEW.last_event_id,NEW.last_event_digest,
            CASE WHEN TG_OP = 'INSERT' THEN 0 ELSE OLD.current_generation END,NEW.current_generation,
            CASE WHEN TG_OP = 'INSERT' THEN 0 ELSE OLD.current_source_version END,NEW.current_source_version);
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER account_source_record_provenance AFTER INSERT OR UPDATE ON account_authority_source_records
FOR EACH ROW EXECUTE FUNCTION account_source_record_fence_provenance();

CREATE FUNCTION account_fence_provenance_consistency() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE e account_authority_outbox_events%ROWTYPE; payload JSONB; wire TEXT; digest_field TEXT;
BEGIN
    IF NOT account_has_current_fence_provenance(NEW.account_uuid,NEW.issuance_fence,NEW.source_version) THEN
        RAISE EXCEPTION 'Fence provenance is not the exact committed current position' USING ERRCODE = '23514';
    END IF;
    IF NEW.cause_kind = 'BASELINE' THEN RETURN NULL; END IF;
    SELECT * INTO e FROM account_authority_outbox_events
    WHERE (outbox_stream_key,outbox_sequence,event_id,event_digest)
        = (NEW.source_stream_key,NEW.event_sequence,NEW.event_id,NEW.event_digest);
    IF e.outbox_stream_key IS NULL THEN
        RAISE EXCEPTION 'Fence provenance lacks exact causal event' USING ERRCODE = '23514';
    END IF;
    payload := convert_from(e.payload,'UTF8')::JSONB;
    IF payload->>'eventId' IS DISTINCT FROM NEW.event_id
        OR payload->>'eventDigest' IS DISTINCT FROM NEW.event_digest
        OR payload->>'outboxStreamKey' IS DISTINCT FROM NEW.source_stream_key
        OR payload->>'outboxSequence' IS DISTINCT FROM NEW.event_sequence::TEXT
        OR payload->>'accountId' IS DISTINCT FROM NEW.account_uuid::TEXT
        OR (NEW.cause_kind = 'MEMBERSHIP' AND payload->>'issuanceFence' IS DISTINCT FROM NEW.issuance_fence::TEXT) THEN
        RAISE EXCEPTION 'Fence causal event identity or position differs' USING ERRCODE = '23514';
    END IF;
    IF NEW.cause_kind = 'ACCOUNT' THEN
        IF payload->>'eventType' IS NULL OR payload->>'eventType' NOT IN ('ACCOUNT_AUTHORITY_CHANGED','ACCOUNT_SECURITY_STATE_CHANGED',
                'PASSWORD_RESET_COMMITTED','LOGOUT_ALL_COMMITTED','ACCOUNT_RESTRICTION_CHANGED')
            OR NOT EXISTS (SELECT 1 FROM account_authority_source_records s
                WHERE s.scope_kind = 'ACCOUNT' AND s.account_uuid = NEW.account_uuid
                  AND (s.current_generation,s.current_source_version,s.current_issuance_fence,
                       s.last_outbox_sequence,s.last_event_id,s.last_event_digest)
                    = (NEW.generation,NEW.authority_source_version,NEW.issuance_fence,
                       NEW.event_sequence,NEW.event_id,NEW.event_digest)) THEN
            RAISE EXCEPTION 'Security fence provenance differs from security source' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.cause_kind = 'MEMBERSHIP' THEN
        -- The existing V1 codec hashes the RFC8785 preimage without the eventDigest member.
        -- Its accountId is first, so eventDigest is exactly one comma-prefixed wire member.
        wire := convert_from(e.payload,'UTF8');
        digest_field := ',"eventDigest":' || to_json(NEW.event_digest)::TEXT;
        IF (length(wire) - length(replace(wire,digest_field,''))) / length(digest_field) <> 1
            OR 'sha256:' || encode(sha256(convert_to(replace(wire,digest_field,''),'UTF8')),'hex') <> NEW.event_digest THEN
            RAISE EXCEPTION 'Membership fence event digest differs from its exact wire preimage' USING ERRCODE = '23514';
        END IF;
        IF payload->>'requestId' IS DISTINCT FROM e.request_id
            OR payload->>'schemaVersion' IS DISTINCT FROM 'account-auth-authority-event/v1'
            OR payload->>'sourceScope' IS DISTINCT FROM 'membership/' || NEW.account_uuid::TEXT || '/' || NEW.tenant_uuid::TEXT
            OR payload->>'eventType' IS DISTINCT FROM 'MEMBERSHIP_CHANGED'
            OR payload->>'tenantId' IS DISTINCT FROM NEW.tenant_uuid::TEXT
            OR payload->'callerBoundAuthorityInvalidated' IS DISTINCT FROM 'true'::JSONB
            OR payload->>'membershipAuthorityGeneration' IS DISTINCT FROM NEW.generation::TEXT
            OR NOT EXISTS (SELECT 1 FROM account_authority_generations g
                WHERE g.scope_kind = 'MEMBERSHIP' AND g.account_uuid = NEW.account_uuid AND g.tenant_uuid = NEW.tenant_uuid
                  AND (g.generation,g.source_version) = (NEW.generation,NEW.authority_source_version))
            OR NOT EXISTS (SELECT 1 FROM account_leave_operations op
                WHERE 'leave:' || op.request_id = e.request_id AND op.account_uuid = NEW.account_uuid AND op.tenant_uuid = NEW.tenant_uuid
                  AND op.status = 'COMMITTED' AND op.outcome = 'LEFT'
                  AND (op.before_membership_generation,op.membership_authority_generation,op.before_fence,op.issuance_fence)
                    = (NEW.prior_generation,NEW.generation,NEW.prior_fence,NEW.issuance_fence)
                  AND (op.membership_event_stream_key,op.event_sequence,op.event_id,op.event_digest)
                    = (NEW.source_stream_key,NEW.event_sequence,NEW.event_id,NEW.event_digest)) THEN
            RAISE EXCEPTION 'Membership fence requires its exact atomic LEAVE receipt' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER account_fence_provenance_consistent AFTER INSERT ON account_issuance_fence_provenance
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_fence_provenance_consistency();

ALTER TABLE account_authority_source_records DROP CONSTRAINT account_authority_source_current_check;
ALTER TABLE account_authority_source_records ADD CONSTRAINT account_authority_source_current_check CHECK (
    current_generation > 0 AND current_source_version > 0 AND last_outbox_sequence >= 0
    AND current_generation = baseline_generation + last_outbox_sequence
    AND current_source_version = baseline_source_version + last_outbox_sequence
    AND (scope_kind <> 'ACCOUNT' OR (current_issuance_fence >= baseline_issuance_fence + last_outbox_sequence
        AND current_issuance_fence_source_version = current_issuance_fence)));

CREATE OR REPLACE FUNCTION account_authority_source_fence_consistency_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE s account_authority_source_records%ROWTYPE;
BEGIN
    SELECT * INTO s FROM account_authority_source_records
    WHERE scope_kind = 'ACCOUNT' AND account_uuid = NEW.account_uuid;
    IF NOT account_has_current_fence_provenance(NEW.account_uuid,NEW.issuance_fence,NEW.source_version)
        OR s.outbox_stream_key IS NULL OR s.current_issuance_fence > NEW.issuance_fence
        OR NOT account_fence_security_position_matches(NEW.account_uuid,s.current_issuance_fence,
            s.current_issuance_fence_source_version,s.outbox_stream_key,s.last_outbox_sequence,s.last_event_id,s.last_event_digest) THEN
        RAISE EXCEPTION 'Account issuance fence advance lacks exact owner source evidence'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_fence_required';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION account_leave_operation_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE expected_digest TEXT; segments TEXT[]; segment TEXT; framed TEXT := '';
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN
        RAISE EXCEPTION 'LEAVE operation history is immutable' USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'UPDATE' AND (
        OLD.status = 'COMMITTED'
        OR (NEW.request_id,NEW.account_id,NEW.account_uuid,NEW.tenant_uuid,NEW.caller_binding,NEW.request_digest,NEW.created_at)
            IS DISTINCT FROM (OLD.request_id,OLD.account_id,OLD.account_uuid,OLD.tenant_uuid,OLD.caller_binding,OLD.request_digest,OLD.created_at)) THEN
        RAISE EXCEPTION 'LEAVE operation identity or terminal result is immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.account_uuid = '00000000-0000-0000-0000-000000000000'::UUID
        OR NEW.tenant_uuid = '00000000-0000-0000-0000-000000000000'::UUID
        OR char_length(NEW.request_id) NOT BETWEEN 1 AND 256 OR NEW.request_id ~ '^[[:space:]]*$'
        OR char_length(NEW.caller_binding) NOT BETWEEN 1 AND 512 OR NEW.caller_binding ~ '^[[:space:]]*$'
        OR NOT EXISTS (SELECT 1 FROM accounts a WHERE a.id = NEW.account_id AND a.account_uuid = NEW.account_uuid
            AND a.account_uuid_source_numeric_id = a.id) THEN
        RAISE EXCEPTION 'LEAVE requires exact caller-bound canonical Account identity' USING ERRCODE = '23514';
    END IF;
    segments := ARRAY['account-leave-membership/v1','LEAVE',NEW.request_id,
        NEW.account_uuid::TEXT,NEW.tenant_uuid::TEXT,NEW.caller_binding];
    FOREACH segment IN ARRAY segments LOOP
        framed := framed || octet_length(convert_to(segment,'UTF8'))::TEXT || ':' || segment;
    END LOOP;
    expected_digest := 'sha256:' || encode(sha256(convert_to(framed,'UTF8')),'hex');
    IF NEW.request_digest <> expected_digest THEN
        RAISE EXCEPTION 'LEAVE request digest differs from exact identity' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.status <> 'PENDING' THEN
        RAISE EXCEPTION 'LEAVE must begin as an exact pending intent' USING ERRCODE = '23514';
    END IF;
    IF NEW.status = 'PENDING' THEN
        IF num_nonnulls(NEW.outcome,NEW.membership_id,NEW.before_membership_version,
            NEW.before_membership_generation,NEW.before_fence,NEW.membership_version,
            NEW.membership_authority_generation,NEW.issuance_fence,NEW.membership_event_stream_key,
            NEW.event_sequence,NEW.event_id,NEW.event_digest,NEW.audit_event_id,NEW.audit_payload_digest) <> 0 THEN
            RAISE EXCEPTION 'Pending LEAVE cannot carry terminal evidence' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.outcome IS NULL OR NEW.before_fence IS NULL OR NEW.issuance_fence IS NULL THEN
        RAISE EXCEPTION 'Committed LEAVE requires exact outcome and fence positions' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_leave_identity BEFORE INSERT OR UPDATE OR DELETE ON account_leave_operations
FOR EACH ROW EXECUTE FUNCTION account_leave_operation_guard();
CREATE TRIGGER account_leave_no_truncate BEFORE TRUNCATE ON account_leave_operations
FOR EACH STATEMENT EXECUTE FUNCTION account_leave_operation_guard();

CREATE FUNCTION account_leave_terminal_consistency() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE member account_tenant_membership%ROWTYPE;
    pair account_membership_pair_authority%ROWTYPE;
    event account_authority_outbox_events%ROWTYPE;
    prior account_authority_outbox_events%ROWTYPE;
    payload JSONB; prior_payload JSONB;
BEGIN
    -- The original PENDING insert may have reached its terminal result in this transaction.
    -- Check only the terminal transition, never reinterpret historical terminals on replay.
    IF NEW.status <> 'COMMITTED' THEN RETURN NULL; END IF;
    IF NOT account_has_current_fence_provenance(NEW.account_uuid,NEW.issuance_fence,NEW.issuance_fence) THEN
        RAISE EXCEPTION 'LEAVE terminal lacks the exact current composite fence' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO member FROM account_tenant_membership
    WHERE account_id = NEW.account_id AND tenant_uuid = NEW.tenant_uuid;
    IF NEW.outcome = 'ALREADY_LEFT' THEN
        IF NEW.before_fence <> NEW.issuance_fence
            OR num_nonnulls(NEW.membership_event_stream_key,NEW.event_sequence,NEW.event_id,NEW.event_digest,
                NEW.audit_event_id,NEW.audit_payload_digest) <> 0
            OR (member.id IS NULL AND num_nonnulls(NEW.membership_id,NEW.before_membership_version,
                NEW.before_membership_generation,NEW.membership_version,NEW.membership_authority_generation) <> 0)
            OR (member.id IS NOT NULL AND (
                member.lifecycle_state <> 'INACTIVE' OR member.gameplay_admission_allowed
                OR member.tenant_id IS NOT NULL OR member.tenant_provenance_kind <> 'FRESH_GAME_DESIGN'
                OR (NEW.membership_id,NEW.before_membership_version,NEW.membership_version,
                    NEW.before_membership_generation,NEW.membership_authority_generation)
                    IS DISTINCT FROM (member.id,member.membership_version,member.membership_version,
                        member.membership_authority_generation,member.membership_authority_generation))) THEN
            RAISE EXCEPTION 'Already-left outcome cannot move authority or invent a transition' USING ERRCODE = '23514';
        END IF;
        RETURN NULL;
    END IF;
    SELECT * INTO pair FROM account_membership_pair_authority
    WHERE account_uuid = NEW.account_uuid AND tenant_uuid = NEW.tenant_uuid;
    SELECT * INTO event FROM account_authority_outbox_events
    WHERE outbox_stream_key = NEW.membership_event_stream_key AND outbox_sequence = NEW.event_sequence;
    SELECT * INTO prior FROM account_authority_outbox_events
    WHERE outbox_stream_key = NEW.membership_event_stream_key AND outbox_sequence = NEW.event_sequence - 1;
    payload := convert_from(event.payload,'UTF8')::JSONB;
    prior_payload := convert_from(prior.payload,'UTF8')::JSONB;
    IF member.id IS NULL OR pair.account_uuid IS NULL OR event.event_id IS NULL OR prior.event_id IS NULL
        OR num_nonnulls(NEW.membership_id,NEW.before_membership_version,NEW.before_membership_generation,
            NEW.membership_version,NEW.membership_authority_generation,NEW.event_sequence,NEW.event_id,
            NEW.event_digest,NEW.audit_event_id,NEW.audit_payload_digest) <> 10
        OR NEW.membership_version <> NEW.before_membership_version + 1
        OR NEW.membership_authority_generation <> NEW.before_membership_generation + 1
        OR NEW.issuance_fence <> NEW.before_fence + 1
        OR member.tenant_id IS NOT NULL OR member.tenant_provenance_kind <> 'FRESH_GAME_DESIGN'
        OR member.lifecycle_state <> 'INACTIVE' OR member.gameplay_admission_allowed
        OR (member.id,member.membership_version,member.membership_authority_generation)
            IS DISTINCT FROM (NEW.membership_id,NEW.membership_version,NEW.membership_authority_generation)
        OR NOT pair.membership_exists OR NOT pair.last_transition_invalidated
        OR (pair.membership_version,pair.membership_authority_generation,pair.last_event_sequence,pair.last_event_id,pair.last_event_digest)
            IS DISTINCT FROM (NEW.membership_version,NEW.membership_authority_generation,NEW.event_sequence,NEW.event_id,NEW.event_digest)
        OR (pair.tenant_provenance_kind,pair.tenant_source_operation_id,pair.tenant_provenance_digest)
            IS DISTINCT FROM (member.tenant_provenance_kind,member.tenant_source_operation_id,member.tenant_provenance_digest)
        OR (event.event_id,event.event_digest,event.request_id)
            IS DISTINCT FROM (NEW.event_id,NEW.event_digest,'leave:' || NEW.request_id)
        OR payload->>'eventType' IS DISTINCT FROM 'MEMBERSHIP_CHANGED'
        OR payload->>'accountId' IS DISTINCT FROM NEW.account_uuid::TEXT
        OR payload->>'tenantId' IS DISTINCT FROM NEW.tenant_uuid::TEXT
        OR payload->>'membershipLifecycleState' IS DISTINCT FROM 'INACTIVE'
        OR payload->'membershipExists' IS DISTINCT FROM 'true'::JSONB
        OR payload->'gameplayAdmissionAllowed' IS DISTINCT FROM 'false'::JSONB
        OR payload->'callerBoundAuthorityInvalidated' IS DISTINCT FROM 'true'::JSONB
        OR payload->'roles' IS DISTINCT FROM '[]'::JSONB
        OR payload->'membershipVersion'->>NEW.tenant_uuid::TEXT IS DISTINCT FROM NEW.membership_version::TEXT
        OR payload->>'membershipAuthorityGeneration' IS DISTINCT FROM NEW.membership_authority_generation::TEXT
        OR payload->>'issuanceFence' IS DISTINCT FROM NEW.issuance_fence::TEXT
        OR prior_payload->>'accountId' IS DISTINCT FROM NEW.account_uuid::TEXT
        OR prior_payload->>'tenantId' IS DISTINCT FROM NEW.tenant_uuid::TEXT
        OR prior_payload->>'eventType' IS NULL
        OR prior_payload->>'eventType' NOT IN ('MEMBERSHIP_CHANGED','MEMBERSHIP_AUTHORITY_CHECKPOINT')
        OR prior_payload->>'membershipLifecycleState' IS DISTINCT FROM 'ACTIVE'
        OR prior_payload->'membershipVersion'->>NEW.tenant_uuid::TEXT IS DISTINCT FROM NEW.before_membership_version::TEXT
        OR prior_payload->>'membershipAuthorityGeneration' IS DISTINCT FROM NEW.before_membership_generation::TEXT
        OR NOT EXISTS (SELECT 1 FROM account_authority_outbox_streams s
            WHERE s.outbox_stream_key = NEW.membership_event_stream_key AND s.last_sequence = NEW.event_sequence)
        OR NOT EXISTS (SELECT 1 FROM account_tenant_membership_role_snapshots r
            WHERE r.membership_id = member.id AND r.snapshot_version = NEW.membership_version)
        OR EXISTS (SELECT 1 FROM account_tenant_membership_role_snapshot_roles r WHERE r.membership_id = member.id)
        OR NOT EXISTS (SELECT 1 FROM account_issuance_fence_provenance p
            WHERE p.account_uuid = NEW.account_uuid AND p.issuance_fence = NEW.issuance_fence
              AND p.prior_fence = NEW.before_fence AND p.cause_kind = 'MEMBERSHIP' AND p.tenant_uuid = NEW.tenant_uuid
              AND (p.source_stream_key,p.event_sequence,p.event_id,p.event_digest)
                = (NEW.membership_event_stream_key,NEW.event_sequence,NEW.event_id,NEW.event_digest))
        OR NOT EXISTS (SELECT 1 FROM account_audit_outbox a
            WHERE a.audit_event_id = NEW.audit_event_id AND a.payload_digest = NEW.audit_payload_digest
              AND a.scope = 'tenant' AND a.tenant_identity_version = 2 AND a.tenant_id IS NULL AND a.tenant_uuid = NEW.tenant_uuid
              AND a.producer_service = 'account-service' AND a.event_type = 'ACCOUNT_LEFT_TENANT_MEMBERSHIP'
              AND a.schema_version = 1 AND a.payload_digest_version = 1 AND a.delivery_status = 'PENDING'
              AND a.payload::JSONB->>'accountId' = NEW.account_uuid::TEXT
              AND a.payload::JSONB->>'tenantId' = NEW.tenant_uuid::TEXT
              AND a.payload::JSONB->>'requestId' = NEW.request_id
              AND a.payload::JSONB->>'requestDigest' = NEW.request_digest
              AND a.payload::JSONB->>'membershipEventId' = NEW.event_id
              AND a.payload::JSONB->>'membershipEventDigest' = NEW.event_digest) THEN
        RAISE EXCEPTION 'LEAVE terminal differs from its atomic committed owner evidence' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER account_leave_terminal AFTER INSERT OR UPDATE ON account_leave_operations
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_leave_terminal_consistency();

-- Patch only the named fence-position assumptions in their latest installed definitions.
-- In particular this preserves V1046.11's separately qualified paid-checkpoint changes.
DO $patch_0$
DECLARE definition TEXT; previous_clause TEXT := $old_0$NEW.current_issuance_fence <> OLD.current_issuance_fence + 1
            OR NEW.current_issuance_fence_source_version <> OLD.current_issuance_fence_source_version + 1$old_0$;
    next_clause TEXT := $new_0$NEW.current_issuance_fence <= OLD.current_issuance_fence
            OR NEW.current_issuance_fence_source_version <> NEW.current_issuance_fence
            OR NOT EXISTS (SELECT 1 FROM account_authority_issuance_fences f
                JOIN account_issuance_fence_provenance p ON p.account_uuid = f.account_uuid
                    AND p.issuance_fence = f.issuance_fence - 1 AND p.source_version = f.source_version - 1
                WHERE f.account_uuid = NEW.account_uuid
                    AND f.issuance_fence = NEW.current_issuance_fence
                    AND f.source_version = NEW.current_issuance_fence_source_version)$new_0$;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema()) || '.account_authority_source_record_update_guard()')::regprocedure)
        INTO STRICT definition;
    IF (length(definition) - length(replace(definition,previous_clause,''))) / length(previous_clause) <> 1 THEN
        RAISE EXCEPTION 'Expected exact single fence clause in account_authority_source_record_update_guard (0)';
    END IF;
    EXECUTE replace(definition,previous_clause,next_clause);
END;
$patch_0$;
DO $patch_1$
DECLARE definition TEXT; previous_clause TEXT := $old_1$IF fence_row.issuance_fence IS DISTINCT FROM NEW.current_issuance_fence
            OR fence_row.source_version IS DISTINCT FROM NEW.current_issuance_fence_source_version THEN$old_1$;
    next_clause TEXT := $new_1$IF fence_row.issuance_fence < NEW.current_issuance_fence
            OR NOT account_has_current_fence_provenance(NEW.account_uuid,fence_row.issuance_fence,fence_row.source_version)
            OR NOT account_fence_security_position_matches(NEW.account_uuid,NEW.current_issuance_fence,
                NEW.current_issuance_fence_source_version,NEW.outbox_stream_key,NEW.last_outbox_sequence,
                NEW.last_event_id,NEW.last_event_digest) THEN$new_1$;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema()) || '.account_authority_source_record_consistency_guard()')::regprocedure)
        INTO STRICT definition;
    IF (length(definition) - length(replace(definition,previous_clause,''))) / length(previous_clause) <> 1 THEN
        RAISE EXCEPTION 'Expected exact single fence clause in account_authority_source_record_consistency_guard (1)';
    END IF;
    EXECUTE replace(definition,previous_clause,next_clause);
END;
$patch_1$;
DO $patch_2$
DECLARE definition TEXT; previous_clause TEXT := $old_2$    account_source account_authority_source_records%ROWTYPE;$old_2$;
    next_clause TEXT := $new_2$    account_source account_authority_source_records%ROWTYPE;
    composite_fence account_authority_issuance_fences%ROWTYPE;$new_2$;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema()) || '.account_paid_membership_checkpoint_consistency()')::regprocedure)
        INTO STRICT definition;
    IF (length(definition) - length(replace(definition,previous_clause,''))) / length(previous_clause) <> 1 THEN
        RAISE EXCEPTION 'Expected exact single fence clause in account_paid_membership_checkpoint_consistency (2)';
    END IF;
    EXECUTE replace(definition,previous_clause,next_clause);
END;
$patch_2$;
DO $patch_3$
DECLARE definition TEXT; previous_clause TEXT := $old_3$    IF issuer_source.scope_kind IS DISTINCT FROM 'ISSUER'$old_3$;
    next_clause TEXT := $new_3$    SELECT * INTO composite_fence FROM account_authority_issuance_fences
    WHERE account_uuid = pair.account_uuid FOR SHARE;
    IF issuer_source.scope_kind IS DISTINCT FROM 'ISSUER' $new_3$;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema()) || '.account_paid_membership_checkpoint_consistency()')::regprocedure)
        INTO STRICT definition;
    IF (length(definition) - length(replace(definition,previous_clause,''))) / length(previous_clause) <> 1 THEN
        RAISE EXCEPTION 'Expected exact single fence clause in account_paid_membership_checkpoint_consistency (3)';
    END IF;
    EXECUTE replace(definition,previous_clause,next_clause);
END;
$patch_3$;
DO $patch_4$
DECLARE definition TEXT; previous_clause TEXT := $old_4$AND (fence.issuance_fence, fence.source_version)
                    = (account_source.current_issuance_fence, account_source.current_issuance_fence_source_version)$old_4$;
    next_clause TEXT := $new_4$AND account_has_current_fence_provenance(pair.account_uuid,fence.issuance_fence,fence.source_version)
                AND account_fence_security_position_matches(pair.account_uuid,account_source.current_issuance_fence,
                    account_source.current_issuance_fence_source_version,account_source.outbox_stream_key,
                    account_source.last_outbox_sequence,account_source.last_event_id,account_source.last_event_digest)$new_4$;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema()) || '.account_paid_membership_checkpoint_consistency()')::regprocedure)
        INTO STRICT definition;
    IF (length(definition) - length(replace(definition,previous_clause,''))) / length(previous_clause) <> 1 THEN
        RAISE EXCEPTION 'Expected exact single fence clause in account_paid_membership_checkpoint_consistency (4)';
    END IF;
    EXECUTE replace(definition,previous_clause,next_clause);
END;
$patch_4$;
DO $patch_5$
DECLARE definition TEXT; previous_clause TEXT := $old_5$        account_source.current_issuance_fence, pair.membership_authority_generation, member.lifecycle_state,$old_5$;
    next_clause TEXT := $new_5$        composite_fence.issuance_fence, pair.membership_authority_generation, member.lifecycle_state,$new_5$;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema()) || '.account_paid_membership_checkpoint_consistency()')::regprocedure)
        INTO STRICT definition;
    IF (length(definition) - length(replace(definition,previous_clause,''))) / length(previous_clause) <> 1 THEN
        RAISE EXCEPTION 'Expected exact single fence clause in account_paid_membership_checkpoint_consistency (5)';
    END IF;
    EXECUTE replace(definition,previous_clause,next_clause);
END;
$patch_5$;

-- V157 is the current gameplay delegation guard, replacing the earlier V147/V153 versions.
DO $patch_gameplay$
DECLARE definition TEXT; previous_clause TEXT := $old$        OR current_issuance_fence <> source_account_issuance_fence
        OR current_fence_source_version <> source_account_fence_source_version$old$;
    next_clause TEXT := $new$        OR NOT account_has_current_fence_provenance(NEW.account_uuid,current_issuance_fence,current_fence_source_version)
        OR NOT EXISTS (SELECT 1 FROM account_authority_source_records s
            WHERE s.scope_kind = 'ACCOUNT' AND s.account_uuid = NEW.account_uuid
              AND s.current_issuance_fence = source_account_issuance_fence
              AND s.current_issuance_fence_source_version = source_account_fence_source_version
              AND account_fence_security_position_matches(NEW.account_uuid,s.current_issuance_fence,
                  s.current_issuance_fence_source_version,s.outbox_stream_key,s.last_outbox_sequence,
                  s.last_event_id,s.last_event_digest))$new$;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema()) || '.account_gameplay_delegation_authority_guard()')::regprocedure)
        INTO STRICT definition;
    IF (length(definition) - length(replace(definition,previous_clause,''))) / length(previous_clause) <> 1 THEN
        RAISE EXCEPTION 'Expected exact current gameplay delegation fence equality';
    END IF;
    EXECUTE replace(definition,previous_clause,next_clause);
END;
$patch_gameplay$;

-- A later security operation may start from unchanged generation-one security history after
-- a genuine membership invalidation. This is NOT the original Account/birth insertion guard:
-- keep all pristine security stream/baseline identity checks, and prove the live fence separately.
DO $patch_security_claim$
DECLARE definition TEXT; previous_clause TEXT := $old$            IF NEW.expected_generation <> 1 OR NEW.expected_source_version <> 1
                OR NEW.expected_fence <> 1 OR NEW.expected_fence_source_version <> 1
                OR octet_length(NEW.checkpoint_payload) <> 0$old$;
    next_clause TEXT := $new$            IF NEW.expected_generation <> 1 OR NEW.expected_source_version <> 1
                OR NOT account_has_current_fence_provenance(NEW.account_uuid,NEW.expected_fence,NEW.expected_fence_source_version)
                OR NOT account_fence_security_position_matches(NEW.account_uuid,1,1,key,0,NULL,NULL)
                OR octet_length(NEW.checkpoint_payload) <> 0$new$;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema()) || '.account_security_state_operation_guard()')::regprocedure)
        INTO STRICT definition;
    IF (length(definition) - length(replace(definition,previous_clause,''))) / length(previous_clause) <> 1 THEN
        RAISE EXCEPTION 'Expected one later security-operation pristine source claim';
    END IF;
    EXECUTE replace(definition,previous_clause,next_clause);
END;
$patch_security_claim$;
-- [jooq ignore stop]
