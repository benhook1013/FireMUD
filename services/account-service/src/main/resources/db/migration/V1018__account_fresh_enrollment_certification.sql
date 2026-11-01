-- Internal fresh-insert certification only. No defaults, retained-row enrollment, registration,
-- moderation/recovery grant or producer activation. Historical migrations and owner branches remain.
CREATE TABLE account_fresh_enrollment_policy_publications (
    publication_id UUID PRIMARY KEY CHECK (publication_id <> '00000000-0000-0000-0000-000000000000'),
    category VARCHAR(64) NOT NULL CHECK (category IN ('account_security_lock', 'platform_access_ban')),
    revision BIGINT NOT NULL CHECK (revision > 0),
    policy_id UUID NOT NULL CHECK (policy_id <> '00000000-0000-0000-0000-000000000000'),
    closure_id UUID NOT NULL CHECK (closure_id <> '00000000-0000-0000-0000-000000000000'),
    policy_rule VARCHAR(64) NOT NULL CHECK (policy_rule IN ('CERTIFY_NEW_ACTIVE_ACCOUNT', 'DENY_NEW_ACCOUNT')),
    closure_rule VARCHAR(64) NOT NULL CHECK (closure_rule IN ('EXACT_FRESH_INSERT_ONLY', 'CLOSED')),
    source_identity VARCHAR(128) NOT NULL,
    source_root TEXT NOT NULL CHECK (source_root ~ '^/' AND source_root !~ E'[\n\r]'),
    publication_digest VARCHAR(71) NOT NULL CHECK (publication_digest ~ '^sha256:[0-9a-f]{64}$'),
    publication_bytes BYTEA NOT NULL CHECK (octet_length(publication_bytes) BETWEEN 1 AND 16384),
    CONSTRAINT account_fresh_policy_owner CHECK (
        (category = 'account_security_lock' AND source_identity = 'account-service/account-security-enrollment-policy/v1')
        OR (category = 'platform_access_ban' AND source_identity = 'logging-admin-service/platform-access-enrollment-policy/v1')),
    UNIQUE (category, revision),
    UNIQUE (category, publication_id, revision)
);
CREATE TABLE account_fresh_enrollment_policy_current (
    category VARCHAR(64) PRIMARY KEY,
    publication_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    FOREIGN KEY (category, publication_id, revision)
        REFERENCES account_fresh_enrollment_policy_publications(category, publication_id, revision)
);
CREATE TABLE account_fresh_enrollments (
    enrollment_id UUID PRIMARY KEY CHECK (enrollment_id <> '00000000-0000-0000-0000-000000000000'),
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    account_uuid UUID NOT NULL UNIQUE,
    account_id BIGINT NOT NULL CHECK (account_id > 0),
    insertion_xid BIGINT NOT NULL CHECK (insertion_xid > 0),
    FOREIGN KEY (account_uuid) REFERENCES accounts(account_uuid)
);
CREATE TABLE account_fresh_enrollment_decisions (
    decision_id UUID PRIMARY KEY CHECK (decision_id <> '00000000-0000-0000-0000-000000000000'),
    enrollment_id UUID NOT NULL REFERENCES account_fresh_enrollments(enrollment_id),
    account_uuid UUID NOT NULL,
    account_id BIGINT NOT NULL CHECK (account_id > 0),
    insertion_xid BIGINT NOT NULL CHECK (insertion_xid > 0),
    category VARCHAR(64) NOT NULL CHECK (category IN ('account_security_lock', 'platform_access_ban')),
    publication_id UUID NOT NULL REFERENCES account_fresh_enrollment_policy_publications(publication_id),
    publication_digest VARCHAR(71) NOT NULL CHECK (publication_digest ~ '^sha256:[0-9a-f]{64}$'),
    policy_id UUID NOT NULL,
    closure_id UUID NOT NULL,
    request_id UUID NOT NULL UNIQUE CHECK (request_id <> '00000000-0000-0000-0000-000000000000'),
    expected_generation BIGINT NOT NULL CHECK (expected_generation > 0),
    expected_source_version BIGINT NOT NULL CHECK (expected_source_version > 0),
    expected_fence BIGINT NOT NULL CHECK (expected_fence > 0),
    expected_fence_source_version BIGINT NOT NULL CHECK (expected_fence_source_version > 0),
    expected_revision BIGINT NOT NULL CHECK (expected_revision > 0),
    expected_epoch BIGINT NOT NULL CHECK (expected_epoch > 0),
    expected_result_id UUID NOT NULL,
    decision_digest VARCHAR(71) NOT NULL CHECK (decision_digest ~ '^sha256:[0-9a-f]{64}$'),
    UNIQUE (enrollment_id, category),
    FOREIGN KEY (request_id) REFERENCES account_platform_restriction_operations(request_id)
        DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE account_fresh_enrollment_results (
    enrollment_id UUID PRIMARY KEY REFERENCES account_fresh_enrollments(enrollment_id),
    security_request_id UUID NOT NULL UNIQUE REFERENCES account_platform_restriction_operations(request_id),
    ban_request_id UUID NOT NULL UNIQUE REFERENCES account_platform_restriction_operations(request_id),
    CHECK (security_request_id <> ban_request_id)
);

-- [jooq ignore start]
CREATE FUNCTION account_fresh_enrollment_policy_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    payload JSONB;
    fields TEXT[] := ARRAY['schemaVersion', 'category', 'sourceIdentity', 'publicationId', 'revision',
        'policyId', 'policyRule', 'closureId', 'closureRule'];
    field TEXT;
BEGIN
    payload := convert_from(NEW.publication_bytes, 'UTF8')::JSONB;
    IF jsonb_typeof(payload) IS DISTINCT FROM 'object' OR (payload - fields) <> '{}'::JSONB
        OR NOT (payload ?& fields)
        OR NEW.publication_digest IS DISTINCT FROM 'sha256:' || encode(sha256(NEW.publication_bytes), 'hex')
        OR payload->>'schemaVersion' IS DISTINCT FROM 'account-fresh-enrollment-policy/v1'
        OR payload->>'category' IS DISTINCT FROM NEW.category
        OR payload->>'sourceIdentity' IS DISTINCT FROM NEW.source_identity
        OR payload->>'publicationId' IS DISTINCT FROM NEW.publication_id::TEXT
        OR payload->>'revision' IS DISTINCT FROM NEW.revision::TEXT
        OR payload->>'policyId' IS DISTINCT FROM NEW.policy_id::TEXT
        OR payload->>'policyRule' IS DISTINCT FROM NEW.policy_rule
        OR payload->>'closureId' IS DISTINCT FROM NEW.closure_id::TEXT
        OR payload->>'closureRule' IS DISTINCT FROM NEW.closure_rule THEN
        RAISE EXCEPTION 'Exact category-owner publication bytes and closure required' USING ERRCODE = '23514';
    END IF;
    FOREACH field IN ARRAY fields LOOP
        IF jsonb_typeof(payload->field) IS DISTINCT FROM 'string' THEN
            RAISE EXCEPTION 'Policy publication requires closed string fields' USING ERRCODE = '23514';
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_fresh_enrollment_policy_insert_guard BEFORE INSERT ON account_fresh_enrollment_policy_publications
    FOR EACH ROW EXECUTE FUNCTION account_fresh_enrollment_policy_insert_guard();

-- Lock before PostgreSQL takes any pointer tuple lock, including direct SQL publishers. A row
-- trigger alone would acquire its advisory lock too late and invert a reader's lock order.
CREATE FUNCTION account_fresh_enrollment_policy_writer_locks()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('account-fresh-enrollment-policy:account_security_lock', 0));
    PERFORM pg_advisory_xact_lock(hashtextextended('account-fresh-enrollment-policy:platform_access_ban', 0));
    RETURN NULL;
END;
$$;
CREATE TRIGGER account_fresh_enrollment_policy_writer_locks BEFORE INSERT OR UPDATE OR DELETE ON account_fresh_enrollment_policy_current
    FOR EACH STATEMENT EXECUTE FUNCTION account_fresh_enrollment_policy_writer_locks();

CREATE FUNCTION account_fresh_enrollment_policy_pointer_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Current owner policy/closure cannot be removed' USING ERRCODE = '23514';
    END IF;
    -- Matches the reader lock, including initially absent current pointers. Publishers lock no
    -- Account rows. Enrollment readers acquire both category locks before creating their Account.
    PERFORM pg_advisory_xact_lock(hashtextextended('account-fresh-enrollment-policy:' || NEW.category, 0));
    IF TG_OP = 'UPDATE' AND (NEW.category IS DISTINCT FROM OLD.category OR NEW.revision <= OLD.revision) THEN
        RAISE EXCEPTION 'Current owner policy/closure must advance monotonically' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_fresh_enrollment_policy_pointer_guard BEFORE INSERT OR UPDATE OR DELETE ON account_fresh_enrollment_policy_current
    FOR EACH ROW EXECUTE FUNCTION account_fresh_enrollment_policy_pointer_guard();

CREATE FUNCTION account_fresh_enrollment_decision_digest(value account_fresh_enrollment_decisions)
RETURNS TEXT LANGUAGE SQL IMMUTABLE STRICT AS $$
    SELECT 'sha256:' || encode(sha256(convert_to(concat_ws(E'\n',
        'account-fresh-enrollment-decision/v1', value.decision_id::TEXT, value.enrollment_id::TEXT,
        value.account_uuid::TEXT, value.account_id::TEXT, 'ACCOUNT_REPOSITORY_INSERT', value.insertion_xid::TEXT,
        value.category, value.publication_id::TEXT, value.publication_digest, value.policy_id::TEXT,
        value.closure_id::TEXT, 'NONRESTRICTED', value.request_id::TEXT, value.expected_generation::TEXT,
        value.expected_source_version::TEXT, value.expected_fence::TEXT, value.expected_fence_source_version::TEXT,
        value.expected_revision::TEXT, value.expected_epoch::TEXT, value.expected_result_id::TEXT) || E'\n', 'UTF8')), 'hex')
$$;

CREATE FUNCTION account_fresh_enrollment_require_decision(decision_uuid UUID, require_current BOOLEAN)
RETURNS account_fresh_enrollment_decisions LANGUAGE plpgsql AS $$
DECLARE
    decision account_fresh_enrollment_decisions%ROWTYPE;
    enrollment account_fresh_enrollments%ROWTYPE;
    publication account_fresh_enrollment_policy_publications%ROWTYPE;
    account_row RECORD;
    current_pointer RECORD;
BEGIN
    SELECT * INTO decision FROM account_fresh_enrollment_decisions WHERE decision_id = decision_uuid;
    SELECT * INTO enrollment FROM account_fresh_enrollments WHERE enrollment_id = decision.enrollment_id;
    SELECT * INTO publication FROM account_fresh_enrollment_policy_publications WHERE publication_id = decision.publication_id;
    SELECT id, account_uuid_source_numeric_id, account_uuid_provenance, account_repository_insert_transaction_id, lifecycle_state
        INTO account_row FROM accounts WHERE account_uuid = decision.account_uuid;
    IF decision.decision_id IS NULL OR enrollment.enrollment_id IS NULL OR publication.publication_id IS NULL
        OR enrollment.account_uuid IS DISTINCT FROM decision.account_uuid
        OR enrollment.account_id IS DISTINCT FROM decision.account_id
        OR enrollment.insertion_xid IS DISTINCT FROM decision.insertion_xid
        OR account_row.id IS DISTINCT FROM decision.account_id
        OR account_row.account_uuid_source_numeric_id IS DISTINCT FROM decision.account_id
        OR account_row.account_uuid_provenance IS DISTINCT FROM 'ACCOUNT_REPOSITORY_INSERT'
        OR account_row.account_repository_insert_transaction_id IS DISTINCT FROM decision.insertion_xid
        OR decision.decision_digest IS DISTINCT FROM account_fresh_enrollment_decision_digest(decision)
        OR publication.category IS DISTINCT FROM decision.category
        OR publication.publication_digest IS DISTINCT FROM decision.publication_digest
        OR publication.policy_id IS DISTINCT FROM decision.policy_id
        OR publication.closure_id IS DISTINCT FROM decision.closure_id
        OR publication.policy_rule IS DISTINCT FROM 'CERTIFY_NEW_ACTIVE_ACCOUNT'
        OR publication.closure_rule IS DISTINCT FROM 'EXACT_FRESH_INSERT_ONLY' THEN
        RAISE EXCEPTION 'Original category policy/closure and fresh insertion certification unavailable' USING ERRCODE = '23514';
    END IF;
    IF require_current THEN
        PERFORM pg_advisory_xact_lock_shared(hashtextextended('account-fresh-enrollment-policy:' || decision.category, 0));
        SELECT * INTO current_pointer FROM account_fresh_enrollment_policy_current
            WHERE category = decision.category FOR SHARE;
        IF decision.insertion_xid IS DISTINCT FROM txid_current()
            OR account_row.lifecycle_state IS DISTINCT FROM 'active'
            OR current_pointer.publication_id IS DISTINCT FROM decision.publication_id
            OR current_pointer.revision IS DISTINCT FROM publication.revision THEN
            RAISE EXCEPTION 'Fresh certification requires its current locked owner policy and closure' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN decision;
END;
$$;

CREATE FUNCTION account_fresh_enrollment_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    account_row RECORD;
BEGIN
    SELECT id, account_uuid_source_numeric_id, account_uuid_provenance,
        account_repository_insert_transaction_id, lifecycle_state INTO account_row
        FROM accounts WHERE account_uuid = NEW.account_uuid FOR UPDATE;
    IF account_row.id IS DISTINCT FROM NEW.account_id
        OR account_row.account_uuid_source_numeric_id IS DISTINCT FROM NEW.account_id
        OR account_row.account_uuid_provenance IS DISTINCT FROM 'ACCOUNT_REPOSITORY_INSERT'
        OR account_row.account_repository_insert_transaction_id IS DISTINCT FROM NEW.insertion_xid
        OR NEW.insertion_xid IS DISTINCT FROM txid_current()
        OR account_row.lifecycle_state IS DISTINCT FROM 'active'
        OR EXISTS (SELECT 1 FROM account_platform_restriction_current_projections WHERE account_uuid = NEW.account_uuid)
        OR NOT EXISTS (SELECT 1 FROM account_authority_source_records WHERE scope_kind = 'ACCOUNT'
            AND account_uuid = NEW.account_uuid AND current_generation = 1 AND current_source_version = 1
            AND current_issuance_fence = 1 AND current_issuance_fence_source_version = 1 AND last_outbox_sequence = 0) THEN
        RAISE EXCEPTION 'Enrollment requires the actual pristine Account insertion transaction' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_fresh_enrollment_insert_guard BEFORE INSERT ON account_fresh_enrollments
    FOR EACH ROW EXECUTE FUNCTION account_fresh_enrollment_insert_guard();

CREATE FUNCTION account_fresh_enrollment_decision_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    account_row RECORD;
    enrollment account_fresh_enrollments%ROWTYPE;
    publication account_fresh_enrollment_policy_publications%ROWTYPE;
    current_pointer RECORD;
    source_row RECORD;
    birth_row RECORD;
BEGIN
    SELECT * INTO enrollment FROM account_fresh_enrollments WHERE enrollment_id = NEW.enrollment_id;
    SELECT id, lifecycle_state, account_uuid_provenance, account_repository_insert_transaction_id
        INTO account_row FROM accounts WHERE account_uuid = NEW.account_uuid FOR UPDATE;
    PERFORM pg_advisory_xact_lock_shared(hashtextextended('account-fresh-enrollment-policy:' || NEW.category, 0));
    SELECT * INTO current_pointer FROM account_fresh_enrollment_policy_current WHERE category = NEW.category FOR SHARE;
    SELECT * INTO publication FROM account_fresh_enrollment_policy_publications WHERE publication_id = current_pointer.publication_id;
    SELECT * INTO source_row FROM account_authority_source_records WHERE scope_kind = 'ACCOUNT' AND account_uuid = NEW.account_uuid;
    SELECT * INTO birth_row FROM account_platform_restriction_births WHERE account_uuid = NEW.account_uuid AND category = NEW.category;
    IF enrollment.enrollment_id IS NULL OR account_row.id IS DISTINCT FROM NEW.account_id
        OR account_row.account_uuid_provenance IS DISTINCT FROM 'ACCOUNT_REPOSITORY_INSERT'
        OR account_row.account_repository_insert_transaction_id IS DISTINCT FROM NEW.insertion_xid
        OR NEW.insertion_xid IS DISTINCT FROM txid_current()
        OR account_row.lifecycle_state IS DISTINCT FROM 'active'
        OR enrollment.account_uuid IS DISTINCT FROM NEW.account_uuid OR enrollment.account_id IS DISTINCT FROM NEW.account_id
        OR enrollment.insertion_xid IS DISTINCT FROM NEW.insertion_xid
        OR publication.publication_id IS NULL OR publication.publication_id IS DISTINCT FROM NEW.publication_id
        OR publication.category IS DISTINCT FROM NEW.category OR publication.revision IS DISTINCT FROM current_pointer.revision
        OR publication.publication_digest IS DISTINCT FROM NEW.publication_digest
        OR publication.policy_id IS DISTINCT FROM NEW.policy_id OR publication.closure_id IS DISTINCT FROM NEW.closure_id
        OR publication.policy_rule IS DISTINCT FROM 'CERTIFY_NEW_ACTIVE_ACCOUNT'
        OR publication.closure_rule IS DISTINCT FROM 'EXACT_FRESH_INSERT_ONLY'
        OR source_row.current_generation IS DISTINCT FROM NEW.expected_generation
        OR source_row.current_source_version IS DISTINCT FROM NEW.expected_source_version
        OR source_row.current_issuance_fence IS DISTINCT FROM NEW.expected_fence
        OR source_row.current_issuance_fence_source_version IS DISTINCT FROM NEW.expected_fence_source_version
        OR birth_row.revision IS DISTINCT FROM NEW.expected_revision OR birth_row.enforcement_epoch IS DISTINCT FROM NEW.expected_epoch
        OR birth_row.result_id IS DISTINCT FROM NEW.expected_result_id
        OR birth_row.account_insert_transaction_id IS DISTINCT FROM NEW.insertion_xid
        OR EXISTS (SELECT 1 FROM account_platform_restriction_current_projections WHERE account_uuid = NEW.account_uuid AND category = NEW.category)
        OR NEW.expected_generation IS DISTINCT FROM (CASE NEW.category WHEN 'account_security_lock' THEN 1 ELSE 2 END)
        OR NEW.expected_source_version IS DISTINCT FROM NEW.expected_generation
        OR NEW.expected_fence IS DISTINCT FROM NEW.expected_generation
        OR NEW.expected_fence_source_version IS DISTINCT FROM NEW.expected_generation THEN
        RAISE EXCEPTION 'Explicit fresh category decision is outside current owner policy/closure or predecessor' USING ERRCODE = '23514';
    END IF;
    IF NEW.category = 'platform_access_ban' AND NOT EXISTS (
        SELECT 1 FROM account_fresh_enrollment_decisions d
        JOIN account_platform_restriction_operations o ON o.request_id = d.request_id
        JOIN account_platform_restriction_current_projections c ON c.request_id = o.request_id
        WHERE d.enrollment_id = NEW.enrollment_id AND d.category = 'account_security_lock'
            AND o.source_kind = 'ACCOUNT_FRESH_ENROLLMENT' AND o.result_state = 'NONRESTRICTED'
            AND o.result_generation = NEW.expected_generation AND o.result_source_version = NEW.expected_source_version) THEN
        RAISE EXCEPTION 'Second category must follow the exact advanced first category certification' USING ERRCODE = '23514';
    END IF;
    NEW.decision_digest := account_fresh_enrollment_decision_digest(NEW);
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_fresh_enrollment_decision_insert_guard BEFORE INSERT ON account_fresh_enrollment_decisions
    FOR EACH ROW EXECUTE FUNCTION account_fresh_enrollment_decision_insert_guard();

CREATE FUNCTION account_fresh_enrollment_require_operation(request_uuid UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    operation account_platform_restriction_operations%ROWTYPE;
    decision account_fresh_enrollment_decisions%ROWTYPE;
BEGIN
    SELECT * INTO operation FROM account_platform_restriction_operations WHERE request_id = request_uuid;
    IF operation.request_id IS NULL OR operation.source_kind IS DISTINCT FROM 'ACCOUNT_FRESH_ENROLLMENT' THEN
        RAISE EXCEPTION 'Original enrollment operation missing' USING ERRCODE = '23514';
    END IF;
    decision := account_fresh_enrollment_require_decision(operation.source_request_id, false);
    IF decision.request_id IS DISTINCT FROM operation.request_id
        OR decision.account_uuid IS DISTINCT FROM operation.account_uuid OR decision.account_id IS DISTINCT FROM operation.account_id
        OR operation.account_provenance IS DISTINCT FROM 'ACCOUNT_REPOSITORY_INSERT'
        OR decision.category IS DISTINCT FROM operation.category OR operation.desired_state IS DISTINCT FROM 'NONRESTRICTED'
        OR operation.result_state IS DISTINCT FROM 'NONRESTRICTED'
        OR decision.decision_digest IS DISTINCT FROM operation.source_digest
        OR decision.expected_generation IS DISTINCT FROM operation.expected_account_generation
        OR decision.expected_source_version IS DISTINCT FROM operation.expected_account_source_version
        OR decision.expected_fence IS DISTINCT FROM operation.expected_fence
        OR decision.expected_fence_source_version IS DISTINCT FROM operation.expected_fence_source_version
        OR decision.expected_revision IS DISTINCT FROM operation.expected_category_revision
        OR decision.expected_epoch IS DISTINCT FROM operation.expected_enforcement_epoch
        OR decision.expected_result_id IS DISTINCT FROM operation.expected_result_id
        OR (decision.insertion_xid IS DISTINCT FROM txid_current() AND NOT EXISTS (
            SELECT 1 FROM account_fresh_enrollment_results r WHERE r.enrollment_id = decision.enrollment_id
                AND CASE decision.category WHEN 'account_security_lock' THEN r.security_request_id ELSE r.ban_request_id END = request_uuid)) THEN
        RAISE EXCEPTION 'Enrollment operation differs from its original owner decision receipt' USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION account_fresh_enrollment_operation_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE decision account_fresh_enrollment_decisions%ROWTYPE;
BEGIN
    IF NEW.source_kind IS DISTINCT FROM 'ACCOUNT_FRESH_ENROLLMENT' THEN RETURN NEW; END IF;
    decision := account_fresh_enrollment_require_decision(NEW.source_request_id, true);
    IF decision.request_id IS DISTINCT FROM NEW.request_id OR decision.account_uuid IS DISTINCT FROM NEW.account_uuid
        OR decision.account_id IS DISTINCT FROM NEW.account_id OR decision.category IS DISTINCT FROM NEW.category
        OR NEW.account_provenance IS DISTINCT FROM 'ACCOUNT_REPOSITORY_INSERT'
        OR NEW.desired_state IS DISTINCT FROM 'NONRESTRICTED' OR NEW.result_state IS DISTINCT FROM 'NONRESTRICTED'
        OR decision.decision_digest IS DISTINCT FROM NEW.source_digest
        OR decision.expected_generation IS DISTINCT FROM NEW.expected_account_generation
        OR decision.expected_source_version IS DISTINCT FROM NEW.expected_account_source_version
        OR decision.expected_fence IS DISTINCT FROM NEW.expected_fence
        OR decision.expected_fence_source_version IS DISTINCT FROM NEW.expected_fence_source_version
        OR decision.expected_revision IS DISTINCT FROM NEW.expected_category_revision
        OR decision.expected_epoch IS DISTINCT FROM NEW.expected_enforcement_epoch
        OR decision.expected_result_id IS DISTINCT FROM NEW.expected_result_id THEN
        RAISE EXCEPTION 'Fresh enrollment cannot use caller correlation as category authorization' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_fresh_enrollment_operation_insert_guard BEFORE INSERT ON account_platform_restriction_operations
    FOR EACH ROW EXECUTE FUNCTION account_fresh_enrollment_operation_insert_guard();

CREATE FUNCTION account_fresh_enrollment_require_complete(enrollment_uuid UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    enrollment account_fresh_enrollments%ROWTYPE;
    result account_fresh_enrollment_results%ROWTYPE;
    decision account_fresh_enrollment_decisions%ROWTYPE;
BEGIN
    SELECT * INTO enrollment FROM account_fresh_enrollments WHERE enrollment_id = enrollment_uuid;
    SELECT * INTO result FROM account_fresh_enrollment_results WHERE enrollment_id = enrollment_uuid;
    IF enrollment.enrollment_id IS NULL OR result.enrollment_id IS NULL
        OR (SELECT count(*) FROM account_fresh_enrollment_decisions WHERE enrollment_id = enrollment_uuid) <> 2 THEN
        RAISE EXCEPTION 'Both explicit category certifications and exact enrollment result must commit atomically' USING ERRCODE = '23514';
    END IF;
    FOR decision IN SELECT * FROM account_fresh_enrollment_decisions WHERE enrollment_id = enrollment_uuid LOOP
        IF decision.request_id IS DISTINCT FROM (CASE decision.category
            WHEN 'account_security_lock' THEN result.security_request_id ELSE result.ban_request_id END) THEN
            RAISE EXCEPTION 'Enrollment result does not reciprocate its exact two owner decisions' USING ERRCODE = '23514';
        END IF;
        PERFORM account_fresh_enrollment_require_operation(decision.request_id);
        IF NOT EXISTS (SELECT 1 FROM account_platform_restriction_operations o
            JOIN account_platform_restriction_revisions r ON r.request_id = o.request_id
                AND r.account_uuid = o.account_uuid AND r.category = o.category
                AND r.result_id = o.result_id AND r.revision = o.result_category_revision
            JOIN account_authority_outbox_events e ON e.outbox_stream_key = o.outbox_stream_key
                AND e.outbox_sequence = o.event_sequence AND e.event_id = o.event_id
                AND e.event_digest = o.event_digest AND e.payload = o.event_payload
            WHERE o.request_id = decision.request_id AND o.result_category_revision = 2
                AND o.result_enforcement_epoch = 2 AND o.result_generation = decision.expected_generation + 1) THEN
            RAISE EXCEPTION 'Enrollment requires original complete revision and outbox receipts' USING ERRCODE = '23514';
        END IF;
        IF enrollment.insertion_xid = txid_current() THEN
            PERFORM account_fresh_enrollment_require_decision(decision.decision_id, true);
            IF NOT EXISTS (SELECT 1 FROM account_platform_restriction_current_projections c
                WHERE c.account_uuid = enrollment.account_uuid AND c.category = decision.category
                    AND c.request_id = decision.request_id AND c.revision = 2 AND c.enforcement_epoch = 2
                    AND c.restriction_state = 'NONRESTRICTED') THEN
                RAISE EXCEPTION 'Fresh enrollment must finish with both explicit current category results' USING ERRCODE = '23514';
            END IF;
        END IF;
    END LOOP;
    IF enrollment.insertion_xid = txid_current() AND NOT EXISTS (
        SELECT 1 FROM account_authority_source_records s
        JOIN account_platform_restriction_operations o ON o.request_id = result.ban_request_id
        WHERE s.scope_kind = 'ACCOUNT' AND s.account_uuid = enrollment.account_uuid
            AND s.current_generation = 3 AND s.current_source_version = 3
            AND s.current_issuance_fence = 3 AND s.current_issuance_fence_source_version = 3
            AND s.last_outbox_sequence = 2 AND s.last_event_id = o.event_id AND s.last_event_digest = o.event_digest) THEN
        RAISE EXCEPTION 'Fresh enrollment final source head must name its second certified category receipt' USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION account_fresh_enrollment_complete_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE enrollment_uuid UUID;
BEGIN
    IF TG_TABLE_NAME = 'account_platform_restriction_operations' THEN
        IF NEW.source_kind IS DISTINCT FROM 'ACCOUNT_FRESH_ENROLLMENT' THEN RETURN NULL; END IF;
        SELECT enrollment_id INTO enrollment_uuid FROM account_fresh_enrollment_decisions WHERE decision_id = NEW.source_request_id;
    ELSE enrollment_uuid := NEW.enrollment_id;
    END IF;
    PERFORM account_fresh_enrollment_require_complete(enrollment_uuid);
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER account_fresh_enrollment_complete AFTER INSERT ON account_fresh_enrollments
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_fresh_enrollment_complete_guard();
CREATE CONSTRAINT TRIGGER account_fresh_enrollment_decision_complete AFTER INSERT ON account_fresh_enrollment_decisions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_fresh_enrollment_complete_guard();
CREATE CONSTRAINT TRIGGER account_fresh_enrollment_result_complete AFTER INSERT ON account_fresh_enrollment_results
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_fresh_enrollment_complete_guard();
CREATE CONSTRAINT TRIGGER account_fresh_enrollment_operation_complete AFTER INSERT ON account_platform_restriction_operations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_fresh_enrollment_complete_guard();

-- All publications, enrollment inputs, owner decisions and results are immutable original receipts.
DO $$
DECLARE relation_name TEXT;
BEGIN
    FOREACH relation_name IN ARRAY ARRAY['account_fresh_enrollment_policy_publications', 'account_fresh_enrollments',
        'account_fresh_enrollment_decisions', 'account_fresh_enrollment_results'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION account_platform_restriction_immutable_guard()',
            relation_name || '_immutable', relation_name);
    END LOOP;
    FOREACH relation_name IN ARRAY ARRAY['account_fresh_enrollment_policy_publications', 'account_fresh_enrollment_policy_current',
        'account_fresh_enrollments', 'account_fresh_enrollment_decisions', 'account_fresh_enrollment_results'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION account_platform_restriction_truncate_guard()',
            relation_name || '_no_truncate', relation_name);
    END LOOP;
END;
$$;
-- [jooq ignore end]

ALTER TABLE account_platform_restriction_operations DROP CONSTRAINT account_platform_restriction_operation_identity_check;
ALTER TABLE account_platform_restriction_operations ADD CONSTRAINT account_platform_restriction_operation_identity_check CHECK (
    category IN ('account_security_lock', 'platform_access_ban')
    AND desired_state IN ('RESTRICTED', 'NONRESTRICTED') AND result_state = desired_state
    AND result_category_revision = expected_category_revision + 1
    AND result_enforcement_epoch = expected_enforcement_epoch + 1
    AND result_generation = expected_account_generation + 1 AND result_source_version = expected_account_source_version + 1
    AND result_fence = expected_fence + 1 AND result_fence_source_version = expected_fence_source_version + 1
    AND outbox_stream_key = 'account:auth-authority:v1:account/' || account_uuid::TEXT
    AND event_sequence = result_generation - 1 AND event_id = 'account-restriction-event-v1:' || request_id::TEXT
    AND ((category = 'account_security_lock'
            AND ((source_kind = 'ACCOUNT_SECURITY_POLICY' AND desired_state = 'RESTRICTED')
                OR (source_kind = 'ACCOUNT_SECURITY_RECOVERY' AND desired_state = 'NONRESTRICTED')))
        OR (category = 'platform_access_ban' AND source_kind = 'LOGGING_ADMIN_MODERATION')
        OR (source_kind = 'ACCOUNT_FRESH_ENROLLMENT' AND desired_state = 'NONRESTRICTED')));

-- Preserve all V137 validator branches and add only this closed source arm and exact original
-- enrollment receipt readback. No historical function or applied migration is edited.
-- [jooq ignore start]
DO $$
DECLARE definition TEXT;
    old_arm TEXT := $old$OR NOT ((restriction_category_value = 'account_security_lock'$old$;
    new_arm TEXT := $new$OR NOT ((source_kind = 'ACCOUNT_FRESH_ENROLLMENT' AND restriction_state = 'NONRESTRICTED')
            OR (restriction_category_value = 'account_security_lock'$new$;
    old_return TEXT := $old$    RETURN operation_row;$old$;
    new_return TEXT := $new$    IF source_kind = 'ACCOUNT_FRESH_ENROLLMENT' THEN
        PERFORM account_fresh_enrollment_require_operation(request_uuid);
    END IF;
    RETURN operation_row;$new$;
BEGIN
    SELECT pg_get_functiondef('account_platform_restriction_require_source_event(account_authority_source_records,jsonb,bytea)'::regprocedure)
        INTO definition;
    IF length(definition) - length(replace(definition, old_arm, '')) <> length(old_arm)
        OR length(definition) - length(replace(definition, old_return, '')) <> length(old_return) THEN
        RAISE EXCEPTION 'Expected exact original V137 owner arm and return were not found once';
    END IF;
    EXECUTE replace(replace(definition, old_arm, new_arm), old_return, new_return);
END;
$$;
-- [jooq ignore end]
