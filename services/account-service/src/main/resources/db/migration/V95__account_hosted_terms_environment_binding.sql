-- Account-owned immutable selection of the official hosted-terms source for one existing
-- environment boundary. The environment identity itself comes from the authenticated owner; this
-- migration creates no environment registry, backfill, route, bean, or production publisher.
CREATE TABLE account_hosted_terms_environment_binding_heads (
    environment_boundary VARCHAR(512) PRIMARY KEY
        CHECK (length(btrim(environment_boundary)) > 0),
    current_binding_id UUID,
    current_source_version BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((current_binding_id IS NULL) = (current_source_version IS NULL)),
    CHECK (current_source_version IS NULL OR current_source_version > 0)
);

CREATE TABLE account_hosted_terms_environment_bindings (
    binding_id UUID PRIMARY KEY CHECK (binding_id <> '00000000-0000-0000-0000-000000000000'),
    publication_request_id UUID NOT NULL UNIQUE
        CHECK (publication_request_id <> '00000000-0000-0000-0000-000000000000'),
    environment_boundary VARCHAR(512) NOT NULL
        REFERENCES account_hosted_terms_environment_binding_heads (environment_boundary),
    hosted_scope_id UUID NOT NULL REFERENCES account_hosted_terms_scopes (hosted_scope_id),
    operator_legal_identity VARCHAR(2048) NOT NULL
        CHECK (length(btrim(operator_legal_identity)) > 0),
    operator_identity_version BIGINT NOT NULL CHECK (operator_identity_version > 0),
    catalog_version_id UUID NOT NULL,
    catalog_source_version BIGINT NOT NULL CHECK (catalog_source_version > 0),
    authenticated_publisher_identity VARCHAR(512) NOT NULL
        CHECK (length(btrim(authenticated_publisher_identity)) > 0),
    publication_event_identity VARCHAR(512) NOT NULL
        CHECK (length(btrim(publication_event_identity)) > 0),
    publication_evidence_digest VARCHAR(71) NOT NULL
        CHECK (publication_evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    predecessor_binding_id UUID,
    predecessor_source_version BIGINT,
    source_version BIGINT NOT NULL CHECK (source_version > 0),
    binding_payload BYTEA NOT NULL CHECK (octet_length(binding_payload) > 0),
    binding_digest VARCHAR(71) NOT NULL CHECK (binding_digest ~ '^sha256:[0-9a-f]{64}$'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (environment_boundary, binding_id),
    UNIQUE (environment_boundary, source_version),
    FOREIGN KEY (environment_boundary, predecessor_binding_id)
        REFERENCES account_hosted_terms_environment_bindings (environment_boundary, binding_id),
    FOREIGN KEY (hosted_scope_id, catalog_version_id)
        REFERENCES account_hosted_terms_catalog_versions (hosted_scope_id, version_id),
    FOREIGN KEY (hosted_scope_id, catalog_source_version)
        REFERENCES account_hosted_terms_catalog_versions (hosted_scope_id, source_version),
    CHECK ((predecessor_binding_id IS NULL) = (predecessor_source_version IS NULL)),
    CHECK ((predecessor_binding_id IS NULL AND source_version = 1
            AND predecessor_source_version IS NULL)
        OR (predecessor_binding_id IS NOT NULL AND predecessor_source_version > 0
            AND source_version = predecessor_source_version + 1)),
    CHECK (binding_digest = 'sha256:' || encode(sha256(binding_payload), 'hex'))
);

ALTER TABLE account_hosted_terms_environment_binding_heads
    ADD CONSTRAINT account_hosted_terms_environment_binding_current_fk
        FOREIGN KEY (environment_boundary, current_binding_id)
        REFERENCES account_hosted_terms_environment_bindings (environment_boundary, binding_id)
        DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE account_hosted_terms_environment_binding_publications (
    request_id UUID PRIMARY KEY CHECK (request_id <> '00000000-0000-0000-0000-000000000000'),
    environment_boundary VARCHAR(512) NOT NULL
        REFERENCES account_hosted_terms_environment_binding_heads (environment_boundary),
    request_payload BYTEA NOT NULL CHECK (octet_length(request_payload) > 0),
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('RECEIVED', 'PENDING_OWNER_SETTLEMENT', 'COMMITTED')),
    candidate_binding_id UUID,
    source_change_binding BYTEA,
    result_payload BYTEA,
    result_digest VARCHAR(71),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    committed_at TIMESTAMPTZ,
    UNIQUE (request_id, environment_boundary),
    UNIQUE (candidate_binding_id),
    FOREIGN KEY (environment_boundary, candidate_binding_id)
        REFERENCES account_hosted_terms_environment_bindings (environment_boundary, binding_id),
    CHECK (request_digest = 'sha256:' || encode(sha256(request_payload), 'hex')),
    CHECK ((candidate_binding_id IS NULL) = (status = 'RECEIVED')),
    CHECK (status <> 'PENDING_OWNER_SETTLEMENT' OR source_change_binding IS NOT NULL),
    CHECK ((status = 'COMMITTED') = (result_payload IS NOT NULL)),
    CHECK ((result_payload IS NULL) = (result_digest IS NULL)),
    CHECK ((result_digest IS NULL) = (committed_at IS NULL)),
    CHECK (result_digest IS NULL OR result_digest = 'sha256:' || encode(sha256(result_payload), 'hex'))
);

ALTER TABLE account_hosted_terms_environment_bindings
    ADD CONSTRAINT account_hosted_terms_environment_binding_publication_fk
        FOREIGN KEY (publication_request_id, environment_boundary)
        REFERENCES account_hosted_terms_environment_binding_publications
            (request_id, environment_boundary)
        DEFERRABLE INITIALLY DEFERRED;

CREATE UNIQUE INDEX account_hosted_terms_environment_one_unsettled_binding
    ON account_hosted_terms_environment_binding_publications (environment_boundary)
    WHERE status = 'PENDING_OWNER_SETTLEMENT';

-- [jooq ignore start]
CREATE FUNCTION account_hosted_terms_environment_binding_immutable_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Environment binding receipt history is immutable';
END;
$$;
CREATE TRIGGER account_hosted_terms_environment_binding_immutable
    BEFORE UPDATE OR DELETE ON account_hosted_terms_environment_bindings
    FOR EACH ROW EXECUTE FUNCTION account_hosted_terms_environment_binding_immutable_guard();

CREATE FUNCTION account_hosted_terms_environment_binding_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE head account_hosted_terms_environment_binding_heads%ROWTYPE;
DECLARE terms account_hosted_terms_catalog_versions%ROWTYPE;
DECLARE scope account_hosted_terms_scopes%ROWTYPE;
DECLARE publication account_hosted_terms_environment_binding_publications%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Environment binding receipts are append-only';
    END IF;
    SELECT * INTO head FROM account_hosted_terms_environment_binding_heads
        WHERE environment_boundary = NEW.environment_boundary FOR UPDATE;
    IF head.environment_boundary IS NULL THEN
        RAISE EXCEPTION 'Environment binding receipt requires canonical environment head lock';
    END IF;
    SELECT * INTO publication FROM account_hosted_terms_environment_binding_publications
        WHERE request_id = NEW.publication_request_id FOR UPDATE;
    IF publication.request_id IS NULL OR publication.environment_boundary <> NEW.environment_boundary
        OR publication.status <> 'RECEIVED'
        OR publication.request_digest <> NEW.publication_evidence_digest THEN
        RAISE EXCEPTION 'Binding receipt requires exact trusted publication intent';
    END IF;
    IF head.current_binding_id IS NULL THEN
        IF NEW.predecessor_binding_id IS NOT NULL OR NEW.predecessor_source_version IS NOT NULL
            OR NEW.source_version <> 1
            OR EXISTS (SELECT 1 FROM account_hosted_terms_environment_bindings
                WHERE environment_boundary = NEW.environment_boundary) THEN
            RAISE EXCEPTION 'Initial environment binding cannot fabricate predecessor state';
        END IF;
    ELSE
        IF head.current_binding_id IS DISTINCT FROM NEW.predecessor_binding_id
            OR head.current_source_version IS DISTINCT FROM NEW.predecessor_source_version
            OR NEW.source_version <> head.current_source_version + 1
            OR EXISTS (SELECT 1 FROM account_hosted_terms_environment_bindings
                WHERE environment_boundary = NEW.environment_boundary
                  AND predecessor_binding_id = head.current_binding_id) THEN
            RAISE EXCEPTION 'Environment binding must extend the exact current predecessor';
        END IF;
    END IF;
    SELECT * INTO scope FROM account_hosted_terms_scopes
        WHERE hosted_scope_id = NEW.hosted_scope_id FOR UPDATE;
    SELECT * INTO terms FROM account_hosted_terms_catalog_versions
        WHERE hosted_scope_id = NEW.hosted_scope_id AND version_id = NEW.catalog_version_id;
    IF scope.current_version_id IS DISTINCT FROM NEW.catalog_version_id
        OR scope.current_source_version IS DISTINCT FROM NEW.catalog_source_version
        OR terms.version_id IS NULL OR terms.source_version <> NEW.catalog_source_version
        OR terms.operator_legal_identity <> NEW.operator_legal_identity
        OR terms.operator_identity_version <> NEW.operator_identity_version
        OR terms.effective_at > clock_timestamp() THEN
        RAISE EXCEPTION 'Binding must name exact current due catalog and legal operator';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_hosted_terms_environment_binding_insert
    BEFORE INSERT ON account_hosted_terms_environment_bindings
    FOR EACH ROW EXECUTE FUNCTION account_hosted_terms_environment_binding_insert_guard();

CREATE FUNCTION account_hosted_terms_environment_binding_head_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE next_binding account_hosted_terms_environment_bindings%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Environment binding head cannot be deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.current_binding_id IS NOT NULL OR NEW.current_source_version IS NOT NULL THEN
            RAISE EXCEPTION 'New environment binding head cannot fabricate prior source';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.environment_boundary <> OLD.environment_boundary OR NEW.current_binding_id IS NULL
        OR NEW.current_source_version IS NULL THEN
        RAISE EXCEPTION 'Environment binding head update must retain boundary and positive source';
    END IF;
    SELECT * INTO next_binding FROM account_hosted_terms_environment_bindings
        WHERE environment_boundary = NEW.environment_boundary
          AND binding_id = NEW.current_binding_id;
    IF next_binding.binding_id IS NULL OR next_binding.source_version <> NEW.current_source_version
        OR NOT EXISTS (SELECT 1 FROM account_hosted_terms_environment_binding_publications publication
            WHERE publication.request_id = next_binding.publication_request_id
              AND publication.environment_boundary = NEW.environment_boundary
              AND publication.candidate_binding_id = NEW.current_binding_id
              AND publication.status = 'COMMITTED') THEN
        RAISE EXCEPTION 'Environment binding head must match exact publication candidate';
    END IF;
    IF OLD.current_binding_id IS NULL THEN
        IF next_binding.predecessor_binding_id IS NOT NULL OR next_binding.source_version <> 1 THEN
            RAISE EXCEPTION 'Initial environment binding head cannot invent a predecessor';
        END IF;
    ELSIF next_binding.predecessor_binding_id <> OLD.current_binding_id
        OR next_binding.source_version <> OLD.current_source_version + 1 THEN
        RAISE EXCEPTION 'Environment binding head must advance from its exact predecessor';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_hosted_terms_environment_binding_head
    BEFORE INSERT OR UPDATE OR DELETE ON account_hosted_terms_environment_binding_heads
    FOR EACH ROW EXECUTE FUNCTION account_hosted_terms_environment_binding_head_guard();

CREATE FUNCTION account_hosted_terms_environment_binding_publication_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE candidate account_hosted_terms_environment_bindings%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Environment binding publication history cannot be deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'RECEIVED' OR NEW.candidate_binding_id IS NOT NULL
            OR NEW.source_change_binding IS NOT NULL OR NEW.result_payload IS NOT NULL THEN
            RAISE EXCEPTION 'Binding publication must begin as exact received intent';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.request_id <> OLD.request_id OR NEW.environment_boundary <> OLD.environment_boundary
        OR NEW.request_payload <> OLD.request_payload OR NEW.request_digest <> OLD.request_digest
        OR NEW.requested_at <> OLD.requested_at THEN
        RAISE EXCEPTION 'Binding publication request intent is immutable';
    END IF;
    IF OLD.status = 'COMMITTED' THEN
        RAISE EXCEPTION 'Committed binding publication result is immutable';
    END IF;
    IF NOT ((OLD.status = 'RECEIVED' AND NEW.status IN ('PENDING_OWNER_SETTLEMENT', 'COMMITTED'))
        OR (OLD.status = 'PENDING_OWNER_SETTLEMENT' AND NEW.status = 'COMMITTED')) THEN
        RAISE EXCEPTION 'Invalid binding publication transition';
    END IF;
    IF NEW.candidate_binding_id IS NULL THEN
        RAISE EXCEPTION 'Binding publication transition requires exact candidate';
    END IF;
    IF OLD.status = 'PENDING_OWNER_SETTLEMENT'
        AND (NEW.candidate_binding_id IS DISTINCT FROM OLD.candidate_binding_id
            OR NEW.source_change_binding IS DISTINCT FROM OLD.source_change_binding) THEN
        RAISE EXCEPTION 'Pending binding publication cannot replace its immutable candidate';
    END IF;
    IF NEW.status = 'PENDING_OWNER_SETTLEMENT' AND NEW.source_change_binding IS NULL THEN
        RAISE EXCEPTION 'Pending binding publication requires durable source-change intent';
    END IF;
    IF NEW.status IN ('PENDING_OWNER_SETTLEMENT', 'COMMITTED') THEN
        SELECT * INTO candidate FROM account_hosted_terms_environment_bindings
            WHERE environment_boundary = NEW.environment_boundary
              AND binding_id = NEW.candidate_binding_id;
        IF candidate.binding_id IS NULL OR candidate.publication_request_id <> NEW.request_id
            OR ((candidate.predecessor_binding_id IS NOT NULL)
                <> (NEW.source_change_binding IS NOT NULL)) THEN
            RAISE EXCEPTION 'Binding operation must retain its exact predecessor source change';
        END IF;
    END IF;
    IF NEW.status = 'COMMITTED' THEN
        IF NEW.result_payload <> candidate.binding_payload
            OR NEW.result_digest <> candidate.binding_digest
            OR NEW.committed_at IS NULL THEN
            RAISE EXCEPTION 'Committed binding result must read back exact candidate and fence intent';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_hosted_terms_environment_binding_publication
    BEFORE INSERT OR UPDATE OR DELETE ON account_hosted_terms_environment_binding_publications
    FOR EACH ROW EXECUTE FUNCTION account_hosted_terms_environment_binding_publication_guard();

CREATE FUNCTION account_hosted_terms_environment_binding_complete_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.current_binding_id IS DISTINCT FROM OLD.current_binding_id
        AND NOT EXISTS (SELECT 1 FROM account_hosted_terms_environment_binding_publications publication
            WHERE publication.environment_boundary = NEW.environment_boundary
              AND publication.candidate_binding_id = NEW.current_binding_id
              AND publication.status = 'COMMITTED') THEN
        RAISE EXCEPTION 'Environment binding head requires committed exact publication result';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER account_hosted_terms_environment_binding_complete
    AFTER UPDATE ON account_hosted_terms_environment_binding_heads
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_hosted_terms_environment_binding_complete_guard();
-- [jooq ignore stop]
