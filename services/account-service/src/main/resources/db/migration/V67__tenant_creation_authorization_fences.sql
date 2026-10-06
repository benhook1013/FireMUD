-- Versionless CREATE_TENANT storage only. No authenticated producer or owner is enabled.
-- Source participation uses V57's existing locks and source-change journal, not a second engine.
CREATE TABLE account_tenant_creation_authorization_fences (
    operation_id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    fence_id UUID NOT NULL UNIQUE,
    actor_account_id UUID NOT NULL,
    tenant_id UUID NOT NULL UNIQUE,
    creation_operation_id UUID NOT NULL UNIQUE,
    creation_request_digest VARCHAR(71) NOT NULL
        CHECK (creation_request_digest ~ '^sha256:[0-9a-f]{64}$'),
    binding BYTEA NOT NULL CHECK (octet_length(binding) > 0),
    ordering VARCHAR(20) NOT NULL CHECK (ordering IN ('RESERVED', 'COMMIT_ORDER', 'REVOKE_ORDER')),
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ordered_at TIMESTAMPTZ,
    CHECK ((ordering = 'RESERVED') = (ordered_at IS NULL)),
    CHECK (operation_id <> '00000000-0000-0000-0000-000000000000'
        AND request_id <> '00000000-0000-0000-0000-000000000000'
        AND fence_id <> '00000000-0000-0000-0000-000000000000'
        AND actor_account_id <> '00000000-0000-0000-0000-000000000000'
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'
        AND creation_operation_id <> '00000000-0000-0000-0000-000000000000')
);

CREATE TABLE account_tenant_creation_authorization_sources (
    operation_id UUID NOT NULL REFERENCES account_tenant_creation_authorization_fences(operation_id),
    source_key VARCHAR(2048) NOT NULL REFERENCES account_draft_authorization_source_locks(source_key),
    source_evidence BYTEA NOT NULL CHECK (octet_length(source_evidence) > 0),
    PRIMARY KEY (operation_id, source_key)
);
CREATE INDEX account_tenant_creation_authorization_sources_by_scope
    ON account_tenant_creation_authorization_sources(source_key, operation_id);

CREATE TABLE account_tenant_creation_authorization_readbacks (
    operation_id UUID NOT NULL REFERENCES account_tenant_creation_authorization_fences(operation_id),
    owner VARCHAR(16) NOT NULL CHECK (owner IN ('GAME_DESIGN', 'ACCOUNT')),
    owner_operation_id UUID NOT NULL,
    outcome VARCHAR(24) NOT NULL CHECK (outcome IN ('COMMITTED', 'DEFINITIVELY_ABORTED')),
    readback BYTEA NOT NULL CHECK (octet_length(readback) > 0),
    owner_result BYTEA NOT NULL CHECK (octet_length(owner_result) > 0),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (operation_id, owner)
);

-- [jooq ignore start]
CREATE FUNCTION account_tenant_creation_authorization_frame(value BYTEA)
RETURNS BYTEA LANGUAGE SQL IMMUTABLE STRICT AS $$
    SELECT int4send(octet_length(value)) || value;
$$;

CREATE FUNCTION account_tenant_creation_authorization_order_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'CREATE_TENANT ordering cannot be deleted' USING ERRCODE = '23514';
    ELSIF TG_OP = 'INSERT' THEN
        IF NEW.ordering <> 'RESERVED' OR NEW.ordered_at IS NOT NULL THEN
            RAISE EXCEPTION 'CREATE_TENANT must begin reserved' USING ERRCODE = '23514';
        END IF;
    ELSIF OLD.ordering <> 'RESERVED'
        OR NEW.ordering NOT IN ('COMMIT_ORDER', 'REVOKE_ORDER') OR NEW.ordered_at IS NULL
        OR (to_jsonb(NEW) - ARRAY['ordering','ordered_at']) IS DISTINCT FROM
           (to_jsonb(OLD) - ARRAY['ordering','ordered_at']) THEN
        RAISE EXCEPTION 'CREATE_TENANT binding and finalized order are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_tenant_creation_authorization_insert_phase_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    fence account_tenant_creation_authorization_fences%ROWTYPE;
    expected_readback BYTEA;
BEGIN
    SELECT * INTO fence FROM account_tenant_creation_authorization_fences
        WHERE operation_id = NEW.operation_id FOR UPDATE;
    IF TG_TABLE_NAME = 'account_tenant_creation_authorization_sources' THEN
        IF fence.ordering IS DISTINCT FROM 'RESERVED' THEN
            RAISE EXCEPTION 'Ordered CREATE_TENANT cannot acquire sources' USING ERRCODE = '23514';
        END IF;
    ELSE
        IF fence.ordering IS NULL OR fence.ordering = 'RESERVED'
            OR NEW.owner_operation_id IS DISTINCT FROM (CASE
                WHEN NEW.owner = 'GAME_DESIGN' THEN fence.creation_operation_id
                ELSE fence.operation_id END) THEN
            RAISE EXCEPTION 'Exact ordered CREATE_TENANT owner operation required' USING ERRCODE = '23514';
        END IF;
        expected_readback :=
            account_tenant_creation_authorization_frame(convert_to('account-create-tenant-owner-readback/v1', 'UTF8'))
            || account_tenant_creation_authorization_frame(convert_to(NEW.owner, 'UTF8'))
            || account_tenant_creation_authorization_frame(convert_to(NEW.outcome, 'UTF8'))
            || account_tenant_creation_authorization_frame(convert_to(NEW.owner_operation_id::TEXT, 'UTF8'))
            || account_tenant_creation_authorization_frame(fence.binding)
            || account_tenant_creation_authorization_frame(NEW.owner_result);
        IF NEW.readback IS DISTINCT FROM expected_readback THEN
            RAISE EXCEPTION 'CREATE_TENANT readback must bind the original exact fence and outcome'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_tenant_creation_authorization_no_truncate()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'CREATE_TENANT evidence cannot be truncated' USING ERRCODE = '23514';
    RETURN NULL;
END;
$$;

-- Compose with V61's unchanged Draft settlement/terminal guard. Both commit and no-mutation
-- abort retain their WAITING state until all affected CREATE_TENANT owners settle exactly.
CREATE FUNCTION account_tenant_creation_authorization_source_transition_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM account_draft_authorization_changed_scopes changed
        JOIN account_tenant_creation_authorization_sources source USING (source_key)
        JOIN account_tenant_creation_authorization_fences fence USING (operation_id)
        WHERE changed.change_id = OLD.change_id
          AND NOT ((fence.ordering = 'REVOKE_ORDER' AND (
              SELECT count(*) FROM account_tenant_creation_authorization_readbacks readback
              WHERE readback.operation_id = fence.operation_id
                AND readback.outcome = 'DEFINITIVELY_ABORTED'
          ) = 2) OR (fence.ordering = 'COMMIT_ORDER' AND (
              SELECT count(*) FROM account_tenant_creation_authorization_readbacks readback
              WHERE readback.operation_id = fence.operation_id
          ) = 2))
    ) THEN
        RAISE EXCEPTION 'Both exact CREATE_TENANT outcomes required before source transition'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_tenant_creation_authorization_order_guard_trigger
    BEFORE INSERT OR UPDATE OR DELETE ON account_tenant_creation_authorization_fences
    FOR EACH ROW EXECUTE FUNCTION account_tenant_creation_authorization_order_guard();
CREATE TRIGGER account_tenant_creation_authorization_sources_phase_trigger
    BEFORE INSERT ON account_tenant_creation_authorization_sources
    FOR EACH ROW EXECUTE FUNCTION account_tenant_creation_authorization_insert_phase_guard();
CREATE TRIGGER account_tenant_creation_authorization_readbacks_phase_trigger
    BEFORE INSERT ON account_tenant_creation_authorization_readbacks
    FOR EACH ROW EXECUTE FUNCTION account_tenant_creation_authorization_insert_phase_guard();
CREATE TRIGGER account_tenant_creation_authorization_sources_immutable_trigger
    BEFORE UPDATE OR DELETE ON account_tenant_creation_authorization_sources
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_tenant_creation_authorization_readbacks_immutable_trigger
    BEFORE UPDATE OR DELETE ON account_tenant_creation_authorization_readbacks
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_tenant_creation_authorization_fences_no_truncate_trigger
    BEFORE TRUNCATE ON account_tenant_creation_authorization_fences
    FOR EACH STATEMENT EXECUTE FUNCTION account_tenant_creation_authorization_no_truncate();
CREATE TRIGGER account_tenant_creation_authorization_sources_no_truncate_trigger
    BEFORE TRUNCATE ON account_tenant_creation_authorization_sources
    FOR EACH STATEMENT EXECUTE FUNCTION account_tenant_creation_authorization_no_truncate();
CREATE TRIGGER account_tenant_creation_authorization_readbacks_no_truncate_trigger
    BEFORE TRUNCATE ON account_tenant_creation_authorization_readbacks
    FOR EACH STATEMENT EXECUTE FUNCTION account_tenant_creation_authorization_no_truncate();
CREATE TRIGGER account_tenant_creation_authorization_source_transition_trigger
    BEFORE UPDATE ON account_draft_authorization_source_changes
    FOR EACH ROW EXECUTE FUNCTION account_tenant_creation_authorization_source_transition_guard();
-- [jooq ignore stop]
