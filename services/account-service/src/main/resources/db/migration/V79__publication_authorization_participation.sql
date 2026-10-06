-- Unwired publication source participation only. No operative legal authority or terminal
-- producer is manufactured. Every affected publication remains unresolved and source-blocking.
CREATE TABLE account_publication_authorization_fences (
    operation_id UUID PRIMARY KEY CHECK (operation_id <> '00000000-0000-0000-0000-000000000000'),
    tenant_id UUID NOT NULL CHECK (tenant_id <> '00000000-0000-0000-0000-000000000000'),
    publish_request_id VARCHAR(256) NOT NULL CHECK (octet_length(publish_request_id) BETWEEN 1 AND 256),
    fence_id UUID NOT NULL UNIQUE CHECK (fence_id <> '00000000-0000-0000-0000-000000000000'),
    actor_account_id UUID NOT NULL CHECK (actor_account_id <> '00000000-0000-0000-0000-000000000000'),
    selection_json TEXT NOT NULL,
    selection_digest VARCHAR(71) NOT NULL CHECK (selection_digest ~ '^sha256:[0-9a-f]{64}$'),
    source_vector JSONB NOT NULL,
    binding BYTEA NOT NULL,
    ordering VARCHAR(24) NOT NULL CHECK (ordering IN ('RESERVED', 'PUBLICATION_ORDER', 'REVOKE_ORDER')),
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ordered_at TIMESTAMPTZ,
    UNIQUE (tenant_id, publish_request_id),
    CHECK ((ordering = 'RESERVED') = (ordered_at IS NULL))
);

CREATE TABLE account_publication_authorization_sources (
    operation_id UUID NOT NULL REFERENCES account_publication_authorization_fences(operation_id),
    source_key VARCHAR(2048) NOT NULL REFERENCES account_draft_authorization_source_locks(source_key),
    source_evidence BYTEA NOT NULL CHECK (octet_length(source_evidence) > 0),
    PRIMARY KEY (operation_id, source_key)
);
CREATE INDEX account_publication_authorization_sources_by_scope
    ON account_publication_authorization_sources(source_key, operation_id);

-- [jooq ignore start]
ALTER TABLE account_publication_authorization_fences
    ADD CONSTRAINT account_publication_authorization_nonempty_sources
    CHECK (jsonb_typeof(source_vector) = 'array' AND jsonb_array_length(source_vector) > 0);

-- PostgreSQL's UTF-8 collation need not equal Java String ordering for supplementary code points.
-- The existing owner sorts shared lock keys by UTF-16 code units; preserve that exact order in SQL.
CREATE FUNCTION account_publication_authorization_source_sort_key(value TEXT)
RETURNS INTEGER[] LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
    units INTEGER[] := ARRAY[]::INTEGER[];
    codepoint INTEGER;
    character_index INTEGER;
BEGIN
    FOR character_index IN 1..char_length(value) LOOP
        codepoint := ascii(substring(value FROM character_index FOR 1));
        IF codepoint > 65535 THEN
            units := array_append(units, 55296 + ((codepoint - 65536) / 1024));
            units := array_append(units, 56320 + ((codepoint - 65536) % 1024));
        ELSE
            units := array_append(units, codepoint);
        END IF;
    END LOOP;
    RETURN units;
END;
$$;

CREATE FUNCTION account_publication_authorization_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    source JSONB;
    source_bytes BYTEA;
    source_prefix BYTEA;
    input_bytes BYTEA;
    expected_binding BYTEA;
    selection JSONB;
    keys TEXT[] := ARRAY[]::TEXT[];
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Publication authorization cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.ordering <> 'RESERVED'
            OR NEW.ordering NOT IN ('PUBLICATION_ORDER', 'REVOKE_ORDER') OR NEW.ordered_at IS NULL
            OR (to_jsonb(NEW) - ARRAY['ordering','ordered_at']) IS DISTINCT FROM
               (to_jsonb(OLD) - ARRAY['ordering','ordered_at']) THEN
            RAISE EXCEPTION 'Publication identity, capture and finalized order are immutable'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.ordering <> 'RESERVED' OR NEW.ordered_at IS NOT NULL THEN
        RAISE EXCEPTION 'Publication must begin reserved' USING ERRCODE = '23514';
    END IF;
    selection := NEW.selection_json::JSONB;
    IF NEW.selection_digest IS DISTINCT FROM
            'sha256:' || encode(sha256(convert_to(NEW.selection_json, 'UTF8')), 'hex')
        OR selection->>'schemaVersion' IS DISTINCT FROM '1'
        OR selection->'intent'->>'canonicalTenantId' IS DISTINCT FROM NEW.tenant_id::TEXT
        OR selection->'intent'->>'publishRequestId' IS DISTINCT FROM NEW.publish_request_id THEN
        RAISE EXCEPTION 'Publication requires the exact original selection digest and request scope'
            USING ERRCODE = '23514';
    END IF;
    input_bytes := account_tenant_creation_authorization_frame(convert_to('account-publication-input/v1', 'UTF8'))
        || account_tenant_creation_authorization_frame(convert_to(NEW.actor_account_id::TEXT, 'UTF8'))
        || account_tenant_creation_authorization_frame(convert_to(NEW.selection_json, 'UTF8'))
        || account_tenant_creation_authorization_frame(convert_to(NEW.selection_digest, 'UTF8'));
    expected_binding := account_tenant_creation_authorization_frame(convert_to('account-publication-authorization/v1', 'UTF8'))
        || account_tenant_creation_authorization_frame(convert_to(NEW.operation_id::TEXT, 'UTF8'))
        || account_tenant_creation_authorization_frame(convert_to(NEW.fence_id::TEXT, 'UTF8'))
        || account_tenant_creation_authorization_frame(input_bytes)
        || account_tenant_creation_authorization_frame(convert_to(jsonb_array_length(NEW.source_vector)::TEXT, 'UTF8'));
    FOR source IN SELECT value FROM jsonb_array_elements(NEW.source_vector) LOOP
        IF jsonb_typeof(source) <> 'object' OR (SELECT count(*) FROM jsonb_object_keys(source)) <> 2
            OR jsonb_typeof(source->'key') IS DISTINCT FROM 'string'
            OR jsonb_typeof(source->'evidence') IS DISTINCT FROM 'string'
            OR source->>'key' = ANY(keys) OR source->>'evidence' !~ '^([0-9a-f]{2})+$'
            OR position(':' IN source->>'key') < 2 THEN
            RAISE EXCEPTION 'Exact distinct publication source vector required' USING ERRCODE = '23514';
        END IF;
        keys := array_append(keys, source->>'key');
        source_bytes := decode(source->>'evidence', 'hex');
        source_prefix := account_tenant_creation_authorization_frame(convert_to('account-draft-source-evidence/v1', 'UTF8'))
            || account_tenant_creation_authorization_frame(convert_to(split_part(source->>'key', ':', 1), 'UTF8'))
            || account_tenant_creation_authorization_frame(convert_to(substring(source->>'key' FROM position(':' IN source->>'key') + 1), 'UTF8'));
        IF substring(source_bytes FROM 1 FOR octet_length(source_prefix)) IS DISTINCT FROM source_prefix THEN
            RAISE EXCEPTION 'Publication source key must bind its original source evidence' USING ERRCODE = '23514';
        END IF;
        expected_binding := expected_binding || account_tenant_creation_authorization_frame(source_bytes);
    END LOOP;
    IF NEW.binding IS DISTINCT FROM expected_binding THEN
        RAISE EXCEPTION 'Publication columns must bind the complete original operation bytes' USING ERRCODE = '23514';
    END IF;
    IF keys IS DISTINCT FROM (SELECT array_agg(key ORDER BY account_publication_authorization_source_sort_key(key))
            FROM unnest(keys) key) THEN
        RAISE EXCEPTION 'Publication sources must retain canonical shared lock order' USING ERRCODE = '23514';
    END IF;
    -- The vector is retained in the owner's canonical sorted order. Use that same order for locks.
    FOR source IN SELECT value FROM jsonb_array_elements(NEW.source_vector) LOOP
        INSERT INTO account_draft_authorization_source_locks(source_key) VALUES (source->>'key') ON CONFLICT DO NOTHING;
        PERFORM source_key FROM account_draft_authorization_source_locks WHERE source_key = source->>'key' FOR UPDATE;
    END LOOP;
    IF (TG_OP = 'INSERT' OR NEW.ordering = 'PUBLICATION_ORDER') AND EXISTS (
        SELECT 1 FROM account_draft_authorization_changed_scopes changed
        JOIN account_draft_authorization_source_changes change USING (change_id)
        WHERE changed.source_key = ANY(keys) AND change.status = 'WAITING'
    ) THEN
        RAISE EXCEPTION 'Waiting source change precedes publication' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_publication_authorization_source_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    fence account_publication_authorization_fences%ROWTYPE;
BEGIN
    SELECT * INTO fence FROM account_publication_authorization_fences
        WHERE operation_id = NEW.operation_id FOR UPDATE;
    IF fence.ordering IS DISTINCT FROM 'RESERVED' OR NOT EXISTS (
        SELECT 1 FROM jsonb_array_elements(fence.source_vector) source
        WHERE source->>'key' = NEW.source_key
          AND decode(source->>'evidence', 'hex') = NEW.source_evidence
    ) THEN
        RAISE EXCEPTION 'Publication source must match the original reserved operation' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_publication_authorization_complete_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    original_vector JSONB;
    operation UUID := NEW.operation_id;
BEGIN
    SELECT source_vector INTO original_vector FROM account_publication_authorization_fences
        WHERE operation_id = operation;
    IF (SELECT count(*) FROM account_publication_authorization_sources WHERE operation_id = operation)
        <> jsonb_array_length(original_vector) THEN
        RAISE EXCEPTION 'Complete immutable publication source participation required' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION account_publication_authorization_source_transition_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    key TEXT;
BEGIN
    -- Also serialize direct SQL terminal attempts with reservation/order, using the same existing
    -- source participation rows and exact Java key order, before inspecting affected publication.
    FOR key IN SELECT source_key FROM account_draft_authorization_changed_scopes
        WHERE change_id = OLD.change_id ORDER BY account_publication_authorization_source_sort_key(source_key) LOOP
        PERFORM source_key FROM account_draft_authorization_source_locks WHERE source_key = key FOR UPDATE;
    END LOOP;
    -- Deliberately no terminal-result path. Draft/creation outcomes, expiry, absence or FAILED
    -- cannot prove definitive publication settlement. Both commit and source abort stay WAITING.
    IF EXISTS (
        SELECT 1 FROM account_draft_authorization_changed_scopes changed
        JOIN account_publication_authorization_sources source USING (source_key)
        WHERE changed.change_id = OLD.change_id
    ) THEN
        RAISE EXCEPTION 'Publication-specific authenticated terminal evidence is unavailable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_publication_authorization_fence_guard
    BEFORE INSERT OR UPDATE OR DELETE ON account_publication_authorization_fences
    FOR EACH ROW EXECUTE FUNCTION account_publication_authorization_guard();
CREATE TRIGGER account_publication_authorization_sources_insert_guard
    BEFORE INSERT ON account_publication_authorization_sources
    FOR EACH ROW EXECUTE FUNCTION account_publication_authorization_source_guard();
CREATE TRIGGER account_publication_authorization_sources_immutable
    BEFORE UPDATE OR DELETE ON account_publication_authorization_sources
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE CONSTRAINT TRIGGER account_publication_authorization_complete
    AFTER INSERT OR UPDATE ON account_publication_authorization_fences
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_publication_authorization_complete_guard();
CREATE TRIGGER account_publication_authorization_fences_no_truncate
    BEFORE TRUNCATE ON account_publication_authorization_fences
    FOR EACH STATEMENT EXECUTE FUNCTION account_tenant_creation_authorization_no_truncate();
CREATE TRIGGER account_publication_authorization_sources_no_truncate
    BEFORE TRUNCATE ON account_publication_authorization_sources
    FOR EACH STATEMENT EXECUTE FUNCTION account_tenant_creation_authorization_no_truncate();
CREATE TRIGGER account_publication_authorization_source_transition
    BEFORE UPDATE ON account_draft_authorization_source_changes
    FOR EACH ROW EXECUTE FUNCTION account_publication_authorization_source_transition_guard();
-- [jooq ignore stop]
