-- Account-owned exact intent only. No legal authority verifier, dispatch RPC, or consumer is
-- enabled by this journal.
CREATE TABLE account_hosted_terms_disclosure_handoffs (
    handoff_id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    kind VARCHAR(32) NOT NULL CHECK (kind IN ('CATALOG', 'ENVIRONMENT_BINDING')),
    source_key VARCHAR(2048) NOT NULL CHECK (length(source_key) > 0 AND octet_length(source_key) <= 2048),
    predecessor_digest VARCHAR(71) NOT NULL CHECK (predecessor_digest ~ '^sha256:[0-9a-f]{64}$'),
    candidate_digest VARCHAR(71) NOT NULL CHECK (candidate_digest ~ '^sha256:[0-9a-f]{64}$'),
    effective_at TIMESTAMPTZ NOT NULL,
    binding BYTEA NOT NULL CHECK (octet_length(binding) > 0),
    binding_digest VARCHAR(71) NOT NULL CHECK (binding_digest ~ '^sha256:[0-9a-f]{64}$'),
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('PREPARED', 'DISPATCH_AUTHORIZED', 'AMBIGUOUS', 'DISCLOSED', 'DEFINITIVELY_NOT_DISCLOSED')),
    dispatch_attempts INTEGER NOT NULL DEFAULT 0 CHECK (dispatch_attempts >= 0),
    result_outcome VARCHAR(32) CHECK (result_outcome IN ('DISCLOSED', 'DEFINITIVELY_NOT_DISCLOSED')),
    result_payload BYTEA,
    result_digest VARCHAR(71) CHECK (result_digest ~ '^sha256:[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    result_recorded_at TIMESTAMPTZ,
    CHECK (
        (status = 'PREPARED' AND dispatch_attempts = 0
            AND result_outcome IS NULL AND result_payload IS NULL
            AND result_digest IS NULL AND result_recorded_at IS NULL)
        OR (status IN ('DISPATCH_AUTHORIZED', 'AMBIGUOUS') AND dispatch_attempts > 0
            AND result_outcome IS NULL AND result_payload IS NULL
            AND result_digest IS NULL AND result_recorded_at IS NULL)
        OR (status = 'DISCLOSED' AND dispatch_attempts > 0
            AND result_outcome = 'DISCLOSED' AND result_payload IS NOT NULL
            AND octet_length(result_payload) > 0
            AND result_digest IS NOT NULL AND result_recorded_at IS NOT NULL)
        OR (status = 'DEFINITIVELY_NOT_DISCLOSED' AND dispatch_attempts > 0
            AND result_outcome = 'DEFINITIVELY_NOT_DISCLOSED'
            AND result_payload IS NOT NULL AND octet_length(result_payload) > 0
            AND result_digest IS NOT NULL
            AND result_recorded_at IS NOT NULL)
    ),
    CHECK (result_digest IS NULL OR result_digest = 'sha256:' || encode(sha256(result_payload), 'hex'))
);

CREATE TABLE account_hosted_terms_disclosure_sources (
    handoff_id UUID NOT NULL REFERENCES account_hosted_terms_disclosure_handoffs(handoff_id),
    source_key VARCHAR(2048) NOT NULL
        CHECK (length(source_key) > 0 AND octet_length(source_key) <= 2048)
        REFERENCES account_draft_authorization_source_locks(source_key),
    source_evidence BYTEA NOT NULL CHECK (octet_length(source_evidence) > 0),
    source_evidence_digest VARCHAR(71) NOT NULL
        CHECK (source_evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    PRIMARY KEY (handoff_id, source_key)
);
CREATE INDEX account_hosted_terms_disclosure_sources_by_key
    ON account_hosted_terms_disclosure_sources(source_key, handoff_id);

-- [jooq ignore start]
CREATE FUNCTION account_hosted_terms_disclosure_handoff_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Hosted terms disclosure handoff cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status IS DISTINCT FROM 'PREPARED'
            OR NEW.dispatch_attempts IS DISTINCT FROM 0
            OR NEW.result_outcome IS NOT NULL
            OR NEW.result_payload IS NOT NULL
            OR NEW.result_digest IS NOT NULL
            OR NEW.result_recorded_at IS NOT NULL THEN
            RAISE EXCEPTION 'Hosted terms disclosure handoff must begin in its empty prepared state'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.handoff_id IS DISTINCT FROM OLD.handoff_id
        OR NEW.request_id IS DISTINCT FROM OLD.request_id
        OR NEW.kind IS DISTINCT FROM OLD.kind
        OR NEW.source_key IS DISTINCT FROM OLD.source_key
        OR NEW.predecessor_digest IS DISTINCT FROM OLD.predecessor_digest
        OR NEW.candidate_digest IS DISTINCT FROM OLD.candidate_digest
        OR NEW.effective_at IS DISTINCT FROM OLD.effective_at
        OR NEW.binding IS DISTINCT FROM OLD.binding
        OR NEW.binding_digest IS DISTINCT FROM OLD.binding_digest
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Hosted terms disclosure handoff binding is immutable'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.result_payload IS NOT NULL THEN
        IF NEW.status IS DISTINCT FROM OLD.status
            OR NEW.dispatch_attempts IS DISTINCT FROM OLD.dispatch_attempts
            OR NEW.result_outcome IS DISTINCT FROM OLD.result_outcome
            OR NEW.result_payload IS DISTINCT FROM OLD.result_payload
            OR NEW.result_digest IS DISTINCT FROM OLD.result_digest
            OR NEW.result_recorded_at IS DISTINCT FROM OLD.result_recorded_at THEN
            RAISE EXCEPTION 'Hosted terms disclosure result is immutable'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status = 'PREPARED' AND NEW.status = 'DISPATCH_AUTHORIZED'
        AND OLD.dispatch_attempts = 0 AND NEW.dispatch_attempts = 1
        AND NEW.result_outcome IS NULL AND NEW.result_payload IS NULL
        AND NEW.result_digest IS NULL AND NEW.result_recorded_at IS NULL THEN
        RETURN NEW;
    ELSIF OLD.status = 'DISPATCH_AUTHORIZED' AND NEW.status = 'AMBIGUOUS'
        AND NEW.dispatch_attempts = OLD.dispatch_attempts
        AND NEW.result_outcome IS NULL AND NEW.result_payload IS NULL
        AND NEW.result_digest IS NULL AND NEW.result_recorded_at IS NULL THEN
        RETURN NEW;
    ELSIF OLD.status = 'AMBIGUOUS' AND NEW.status = 'DISPATCH_AUTHORIZED'
        AND NEW.dispatch_attempts = OLD.dispatch_attempts + 1
        AND NEW.result_outcome IS NULL AND NEW.result_payload IS NULL
        AND NEW.result_digest IS NULL AND NEW.result_recorded_at IS NULL THEN
        RETURN NEW;
    ELSIF OLD.status IN ('DISPATCH_AUTHORIZED', 'AMBIGUOUS')
        AND NEW.status IN ('DISCLOSED', 'DEFINITIVELY_NOT_DISCLOSED')
        AND NEW.dispatch_attempts = OLD.dispatch_attempts
        AND NEW.result_payload IS NOT NULL AND octet_length(NEW.result_payload) > 0
        AND NEW.result_digest IS NOT NULL AND NEW.result_recorded_at IS NOT NULL
        AND ((NEW.status = 'DISCLOSED' AND NEW.result_outcome = 'DISCLOSED')
            OR (NEW.status = 'DEFINITIVELY_NOT_DISCLOSED'
                AND NEW.result_outcome = 'DEFINITIVELY_NOT_DISCLOSED')) THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'Invalid hosted terms disclosure handoff transition'
        USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION account_hosted_terms_disclosure_immutable_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Hosted terms disclosure source evidence is immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION account_hosted_terms_disclosure_source_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    phase TEXT;
BEGIN
    SELECT status INTO phase
        FROM account_hosted_terms_disclosure_handoffs
        WHERE handoff_id = NEW.handoff_id FOR UPDATE;
    IF phase IS DISTINCT FROM 'PREPARED' THEN
        RAISE EXCEPTION 'Only a newly prepared handoff may acquire source evidence'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_hosted_terms_disclosure_handoff_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON account_hosted_terms_disclosure_handoffs
    FOR EACH ROW EXECUTE FUNCTION account_hosted_terms_disclosure_handoff_guard();
CREATE TRIGGER account_hosted_terms_disclosure_sources_immutable
    BEFORE UPDATE OR DELETE ON account_hosted_terms_disclosure_sources
    FOR EACH ROW EXECUTE FUNCTION account_hosted_terms_disclosure_immutable_guard();
CREATE TRIGGER account_hosted_terms_disclosure_sources_insert_phase
    BEFORE INSERT ON account_hosted_terms_disclosure_sources
    FOR EACH ROW EXECUTE FUNCTION account_hosted_terms_disclosure_source_insert_guard();
CREATE TRIGGER account_hosted_terms_disclosure_handoffs_no_truncate
    BEFORE TRUNCATE ON account_hosted_terms_disclosure_handoffs
    FOR EACH STATEMENT EXECUTE FUNCTION account_hosted_terms_disclosure_immutable_guard();
CREATE TRIGGER account_hosted_terms_disclosure_sources_no_truncate
    BEFORE TRUNCATE ON account_hosted_terms_disclosure_sources
    FOR EACH STATEMENT EXECUTE FUNCTION account_hosted_terms_disclosure_immutable_guard();
-- [jooq ignore stop]
