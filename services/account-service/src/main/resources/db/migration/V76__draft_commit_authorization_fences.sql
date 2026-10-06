-- Internal storage only. No source writer or authenticated authorization producer is enabled.
CREATE TABLE account_draft_authorization_source_locks (
    source_key VARCHAR(512) PRIMARY KEY CHECK (length(source_key) > 0 AND octet_length(source_key) <= 512)
);

CREATE TABLE account_draft_authorization_fences (
    operation_id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    commit_id UUID NOT NULL UNIQUE,
    fence_id UUID NOT NULL UNIQUE,
    binding BYTEA NOT NULL CHECK (octet_length(binding) > 0),
    ordering VARCHAR(20) NOT NULL CHECK (ordering IN ('RESERVED', 'COMMIT_ORDER', 'REVOKE_ORDER')),
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ordered_at TIMESTAMPTZ,
    CHECK ((ordering = 'RESERVED') = (ordered_at IS NULL))
);

CREATE TABLE account_draft_authorization_sources (
    operation_id UUID NOT NULL REFERENCES account_draft_authorization_fences(operation_id),
    source_key VARCHAR(512) NOT NULL REFERENCES account_draft_authorization_source_locks(source_key),
    source_evidence BYTEA NOT NULL CHECK (octet_length(source_evidence) > 0),
    PRIMARY KEY (operation_id, source_key)
);
CREATE INDEX account_draft_authorization_sources_by_scope
    ON account_draft_authorization_sources(source_key, operation_id);

CREATE TABLE account_draft_authorization_owner_readbacks (
    operation_id UUID NOT NULL REFERENCES account_draft_authorization_fences(operation_id),
    owner VARCHAR(16) NOT NULL CHECK (owner IN ('GAME_DESIGN', 'WORLD')),
    outcome VARCHAR(24) NOT NULL CHECK (outcome IN ('COMMITTED', 'DEFINITIVELY_ABORTED')),
    readback BYTEA NOT NULL CHECK (octet_length(readback) > 0),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (operation_id, owner)
);

CREATE TABLE account_draft_authorization_source_changes (
    change_id UUID PRIMARY KEY,
    binding BYTEA NOT NULL CHECK (octet_length(binding) > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('WAITING', 'SOURCE_COMMITTED')),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    committed_at TIMESTAMPTZ,
    CHECK ((status = 'WAITING') = (committed_at IS NULL))
);

CREATE TABLE account_draft_authorization_changed_scopes (
    change_id UUID NOT NULL REFERENCES account_draft_authorization_source_changes(change_id),
    source_key VARCHAR(512) NOT NULL REFERENCES account_draft_authorization_source_locks(source_key),
    PRIMARY KEY (change_id, source_key)
);
CREATE INDEX account_draft_authorization_changes_by_scope
    ON account_draft_authorization_changed_scopes(source_key, change_id);

-- [jooq ignore start]
CREATE FUNCTION account_draft_authorization_immutable_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Draft authorization evidence is immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION account_draft_authorization_order_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Draft authorization fence cannot be deleted';
    END IF;
    IF OLD.ordering <> 'RESERVED' OR NEW.ordering NOT IN ('COMMIT_ORDER', 'REVOKE_ORDER')
        OR NEW.operation_id IS DISTINCT FROM OLD.operation_id
        OR NEW.request_id IS DISTINCT FROM OLD.request_id
        OR NEW.commit_id IS DISTINCT FROM OLD.commit_id
        OR NEW.fence_id IS DISTINCT FROM OLD.fence_id
        OR NEW.binding IS DISTINCT FROM OLD.binding
        OR NEW.reserved_at IS DISTINCT FROM OLD.reserved_at
        OR NEW.ordered_at IS NULL THEN
        RAISE EXCEPTION 'Draft commit/revoke ordering is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_draft_authorization_source_change_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Draft source change cannot be deleted';
    END IF;
    IF OLD.status <> 'WAITING' OR NEW.status <> 'SOURCE_COMMITTED'
        OR NEW.change_id IS DISTINCT FROM OLD.change_id
        OR NEW.binding IS DISTINCT FROM OLD.binding
        OR NEW.requested_at IS DISTINCT FROM OLD.requested_at
        OR NEW.committed_at IS NULL THEN
        RAISE EXCEPTION 'Draft source change result is immutable';
    END IF;
    IF EXISTS (
        SELECT 1 FROM account_draft_authorization_changed_scopes changed
        JOIN account_draft_authorization_sources source USING (source_key)
        JOIN account_draft_authorization_fences fence USING (operation_id)
        WHERE changed.change_id = OLD.change_id
          AND NOT ((fence.ordering = 'REVOKE_ORDER' AND (
              SELECT count(*) FROM account_draft_authorization_owner_readbacks readback
              WHERE readback.operation_id = fence.operation_id
                AND readback.outcome = 'DEFINITIVELY_ABORTED'
          ) = 2) OR (fence.ordering = 'COMMIT_ORDER' AND (
              SELECT count(*) FROM account_draft_authorization_owner_readbacks readback
              WHERE readback.operation_id = fence.operation_id
          ) = 2))
    ) THEN
        RAISE EXCEPTION 'Both definitive owner outcomes are required before source commit';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_draft_authorization_insert_phase_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    phase TEXT;
BEGIN
    IF TG_TABLE_NAME = 'account_draft_authorization_changed_scopes' THEN
        SELECT status INTO phase FROM account_draft_authorization_source_changes
            WHERE change_id = NEW.change_id FOR UPDATE;
        IF phase IS DISTINCT FROM 'WAITING' THEN
            RAISE EXCEPTION 'Completed source change cannot acquire new scopes';
        END IF;
    ELSE
        SELECT ordering INTO phase FROM account_draft_authorization_fences
            WHERE operation_id = NEW.operation_id FOR UPDATE;
        IF TG_TABLE_NAME = 'account_draft_authorization_sources' AND phase IS DISTINCT FROM 'RESERVED' THEN
            RAISE EXCEPTION 'Ordered Draft operation cannot acquire new sources';
        ELSIF TG_TABLE_NAME = 'account_draft_authorization_owner_readbacks'
            AND (phase IS NULL OR phase = 'RESERVED') THEN
            RAISE EXCEPTION 'Reservation alone cannot produce owner terminal evidence';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_draft_authorization_fence_guard
    BEFORE UPDATE OR DELETE ON account_draft_authorization_fences
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_order_guard();
CREATE TRIGGER account_draft_authorization_change_guard
    BEFORE UPDATE OR DELETE ON account_draft_authorization_source_changes
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_source_change_guard();
CREATE TRIGGER account_draft_authorization_sources_immutable
    BEFORE UPDATE OR DELETE ON account_draft_authorization_sources
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_draft_authorization_readbacks_immutable
    BEFORE UPDATE OR DELETE ON account_draft_authorization_owner_readbacks
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_draft_authorization_scopes_immutable
    BEFORE UPDATE OR DELETE ON account_draft_authorization_changed_scopes
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_draft_authorization_source_locks_immutable
    BEFORE UPDATE OR DELETE ON account_draft_authorization_source_locks
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_draft_authorization_source_insert_phase
    BEFORE INSERT ON account_draft_authorization_sources
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_insert_phase_guard();
CREATE TRIGGER account_draft_authorization_readback_insert_phase
    BEFORE INSERT ON account_draft_authorization_owner_readbacks
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_insert_phase_guard();
CREATE TRIGGER account_draft_authorization_scope_insert_phase
    BEFORE INSERT ON account_draft_authorization_changed_scopes
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_insert_phase_guard();
-- [jooq ignore stop]
