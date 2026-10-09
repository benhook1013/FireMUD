-- Account-owned immutable hosted terms and individual affirmative acceptance workflow.
-- This schema is deliberately unregistered: it creates no production route, bean or live legal
-- publication. Existing Accounts and creator parties are not enrolled or backfilled.
CREATE TABLE account_hosted_terms_scopes (
    hosted_scope_id UUID PRIMARY KEY CHECK (hosted_scope_id <> '00000000-0000-0000-0000-000000000000'),
    current_version_id UUID,
    current_source_version BIGINT,
    current_material_generation BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((current_version_id IS NULL) = (current_source_version IS NULL)),
    CHECK ((current_version_id IS NULL) = (current_material_generation IS NULL)),
    CHECK (current_source_version IS NULL OR current_source_version > 0),
    CHECK (current_material_generation IS NULL OR current_material_generation > 0)
);

CREATE TABLE account_hosted_terms_catalog_versions (
    version_id UUID PRIMARY KEY CHECK (version_id <> '00000000-0000-0000-0000-000000000000'),
    hosted_scope_id UUID NOT NULL REFERENCES account_hosted_terms_scopes (hosted_scope_id),
    predecessor_version_id UUID,
    operator_legal_identity VARCHAR(2048) NOT NULL CHECK (length(btrim(operator_legal_identity)) > 0),
    operator_identity_version BIGINT NOT NULL CHECK (operator_identity_version > 0),
    document_bytes BYTEA NOT NULL CHECK (octet_length(document_bytes) > 0),
    document_digest VARCHAR(71) NOT NULL CHECK (document_digest ~ '^sha256:[0-9a-f]{64}$'),
    source_version BIGINT NOT NULL CHECK (source_version > 0),
    material_generation BIGINT NOT NULL CHECK (material_generation > 0),
    materiality VARCHAR(16) NOT NULL CHECK (materiality IN ('INITIAL', 'MATERIAL', 'NONMATERIAL')),
    materiality_evidence_reference VARCHAR(512),
    materiality_evidence_version BIGINT,
    publication_evidence_reference VARCHAR(512) NOT NULL CHECK (length(btrim(publication_evidence_reference)) > 0),
    publication_evidence_version BIGINT NOT NULL CHECK (publication_evidence_version > 0),
    notice_evidence_reference VARCHAR(512) NOT NULL CHECK (length(btrim(notice_evidence_reference)) > 0),
    notice_evidence_version BIGINT NOT NULL CHECK (notice_evidence_version > 0),
    effective_at TIMESTAMPTZ NOT NULL,
    version_payload BYTEA NOT NULL CHECK (octet_length(version_payload) > 0),
    version_digest VARCHAR(71) NOT NULL CHECK (version_digest ~ '^sha256:[0-9a-f]{64}$'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (hosted_scope_id, source_version),
    UNIQUE (hosted_scope_id, version_id),
    FOREIGN KEY (hosted_scope_id, predecessor_version_id)
        REFERENCES account_hosted_terms_catalog_versions (hosted_scope_id, version_id),
    CHECK ((predecessor_version_id IS NULL AND materiality = 'INITIAL'
            AND source_version = 1 AND material_generation = 1
            AND materiality_evidence_reference IS NULL AND materiality_evidence_version IS NULL)
        OR (predecessor_version_id IS NOT NULL AND materiality IN ('MATERIAL', 'NONMATERIAL')
            AND materiality_evidence_reference IS NOT NULL
            AND length(btrim(materiality_evidence_reference)) > 0
            AND materiality_evidence_version > 0)),
    CHECK ((materiality_evidence_reference IS NULL) = (materiality_evidence_version IS NULL)),
    CHECK (document_digest = 'sha256:' || encode(sha256(document_bytes), 'hex')),
    CHECK (version_digest = 'sha256:' || encode(sha256(version_payload), 'hex'))
);

ALTER TABLE account_hosted_terms_scopes
    ADD CONSTRAINT account_hosted_terms_current_version_fk
        FOREIGN KEY (hosted_scope_id, current_version_id)
        REFERENCES account_hosted_terms_catalog_versions (hosted_scope_id, version_id);

CREATE TABLE account_hosted_terms_publication_operations (
    request_id UUID PRIMARY KEY CHECK (request_id <> '00000000-0000-0000-0000-000000000000'),
    hosted_scope_id UUID NOT NULL REFERENCES account_hosted_terms_scopes (hosted_scope_id),
    request_payload BYTEA NOT NULL CHECK (octet_length(request_payload) > 0),
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    status VARCHAR(32) NOT NULL CHECK (status IN ('RECEIVED', 'SCHEDULED', 'PENDING_OWNER_SETTLEMENT', 'COMMITTED')),
    candidate_version_id UUID,
    source_change_binding BYTEA,
    result_payload BYTEA,
    result_digest VARCHAR(71),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    committed_at TIMESTAMPTZ,
    UNIQUE (request_id, hosted_scope_id),
    UNIQUE (candidate_version_id),
    FOREIGN KEY (hosted_scope_id, candidate_version_id)
        REFERENCES account_hosted_terms_catalog_versions (hosted_scope_id, version_id),
    CHECK (request_digest = 'sha256:' || encode(sha256(request_payload), 'hex')),
    CHECK ((candidate_version_id IS NULL) = (status = 'RECEIVED')),
    CHECK (status <> 'PENDING_OWNER_SETTLEMENT' OR source_change_binding IS NOT NULL),
    CHECK ((status = 'COMMITTED') = (result_payload IS NOT NULL)),
    CHECK ((result_payload IS NULL) = (result_digest IS NULL)),
    CHECK ((result_digest IS NULL) = (committed_at IS NULL)),
    CHECK (result_digest IS NULL OR result_digest = 'sha256:' || encode(sha256(result_payload), 'hex'))
);

-- One pending/scheduled candidate per hosted scope preserves an unambiguous next independent
-- source version. A due candidate remains durable even while owner settlement is unresolved.
CREATE UNIQUE INDEX account_hosted_terms_one_unsettled_publication
    ON account_hosted_terms_publication_operations (hosted_scope_id)
    WHERE status IN ('SCHEDULED', 'PENDING_OWNER_SETTLEMENT');

CREATE TABLE account_individual_hosted_terms_acceptances (
    evidence_id UUID PRIMARY KEY CHECK (evidence_id <> '00000000-0000-0000-0000-000000000000'),
    action_request_id UUID NOT NULL UNIQUE CHECK (action_request_id <> '00000000-0000-0000-0000-000000000000'),
    creator_party_id UUID NOT NULL,
    account_uuid UUID NOT NULL REFERENCES accounts (account_uuid),
    hosted_scope_id UUID NOT NULL,
    terms_version_id UUID NOT NULL,
    document_digest VARCHAR(71) NOT NULL CHECK (document_digest ~ '^sha256:[0-9a-f]{64}$'),
    operator_legal_identity VARCHAR(2048) NOT NULL CHECK (length(btrim(operator_legal_identity)) > 0),
    operator_identity_version BIGINT NOT NULL CHECK (operator_identity_version > 0),
    source_version BIGINT NOT NULL CHECK (source_version > 0),
    material_generation BIGINT NOT NULL CHECK (material_generation > 0),
    individual_party_source BYTEA NOT NULL CHECK (octet_length(individual_party_source) > 0),
    individual_party_source_digest VARCHAR(71) NOT NULL CHECK (individual_party_source_digest ~ '^sha256:[0-9a-f]{64}$'),
    affirmative_action_evidence BYTEA NOT NULL CHECK (octet_length(affirmative_action_evidence) > 0),
    affirmative_action_digest VARCHAR(71) NOT NULL CHECK (affirmative_action_digest ~ '^sha256:[0-9a-f]{64}$'),
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (creator_party_id, account_uuid)
        REFERENCES account_individual_creator_party_sources (creator_party_id, account_uuid),
    FOREIGN KEY (hosted_scope_id, terms_version_id)
        REFERENCES account_hosted_terms_catalog_versions (hosted_scope_id, version_id),
    CHECK (individual_party_source_digest = 'sha256:' || encode(sha256(individual_party_source), 'hex')),
    CHECK (affirmative_action_digest = 'sha256:' || encode(sha256(affirmative_action_evidence), 'hex'))
);

CREATE INDEX account_individual_hosted_terms_current_acceptance
    ON account_individual_hosted_terms_acceptances
        (creator_party_id, hosted_scope_id, accepted_at DESC, evidence_id);

-- [jooq ignore start]
CREATE FUNCTION account_hosted_terms_immutable_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Account hosted terms catalog and acceptance evidence is immutable';
END;
$$;
CREATE TRIGGER account_hosted_terms_catalog_immutable
    BEFORE UPDATE OR DELETE ON account_hosted_terms_catalog_versions
    FOR EACH ROW EXECUTE FUNCTION account_hosted_terms_immutable_guard();
CREATE TRIGGER account_hosted_terms_acceptance_immutable
    BEFORE UPDATE OR DELETE ON account_individual_hosted_terms_acceptances
    FOR EACH ROW EXECUTE FUNCTION account_hosted_terms_immutable_guard();

CREATE FUNCTION account_hosted_terms_version_insert_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE prior account_hosted_terms_catalog_versions%ROWTYPE;
DECLARE scope account_hosted_terms_scopes%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Hosted terms versions are append-only';
    END IF;
    SELECT * INTO scope FROM account_hosted_terms_scopes
        WHERE hosted_scope_id = NEW.hosted_scope_id FOR UPDATE;
    IF scope.hosted_scope_id IS NULL THEN
        RAISE EXCEPTION 'Hosted terms version requires canonical scope lock';
    END IF;
    IF NEW.predecessor_version_id IS NULL THEN
        IF EXISTS (SELECT 1 FROM account_hosted_terms_catalog_versions
            WHERE hosted_scope_id = NEW.hosted_scope_id)
            OR scope.current_version_id IS NOT NULL THEN
            RAISE EXCEPTION 'Initial hosted terms cannot replace prior source state';
        END IF;
        RETURN NEW;
    END IF;
    IF scope.current_version_id IS DISTINCT FROM NEW.predecessor_version_id
        OR EXISTS (SELECT 1 FROM account_hosted_terms_catalog_versions
            WHERE hosted_scope_id = NEW.hosted_scope_id
              AND predecessor_version_id = NEW.predecessor_version_id) THEN
        RAISE EXCEPTION 'Hosted terms candidate must extend the sole current predecessor';
    END IF;
    SELECT * INTO prior FROM account_hosted_terms_catalog_versions
        WHERE hosted_scope_id = NEW.hosted_scope_id
          AND version_id = NEW.predecessor_version_id;
    IF prior.version_id IS NULL OR NEW.source_version <> prior.source_version + 1 THEN
        RAISE EXCEPTION 'Hosted terms source version must advance from exact predecessor';
    END IF;
    IF NEW.operator_legal_identity <> prior.operator_legal_identity
        OR NEW.operator_identity_version <> prior.operator_identity_version THEN
        IF NEW.materiality <> 'MATERIAL'
            OR NEW.material_generation <> prior.material_generation + 1 THEN
            RAISE EXCEPTION 'Operator identity change requires material acceptance-generation advance';
        END IF;
    ELSIF NEW.materiality = 'MATERIAL' THEN
        IF NEW.material_generation <> prior.material_generation + 1 THEN
            RAISE EXCEPTION 'Material terms must advance acceptance generation exactly once';
        END IF;
    ELSIF NEW.materiality = 'NONMATERIAL' THEN
        IF NEW.material_generation <> prior.material_generation THEN
            RAISE EXCEPTION 'Nonmaterial terms must preserve acceptance generation';
        END IF;
    ELSE
        RAISE EXCEPTION 'Only audited materiality classifications may follow a predecessor';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_hosted_terms_version_insert
    BEFORE INSERT ON account_hosted_terms_catalog_versions
    FOR EACH ROW EXECUTE FUNCTION account_hosted_terms_version_insert_guard();

CREATE FUNCTION account_hosted_terms_scope_head_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE next_version account_hosted_terms_catalog_versions%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Hosted terms source scope cannot be deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.current_version_id IS NOT NULL
            OR NEW.current_source_version IS NOT NULL
            OR NEW.current_material_generation IS NOT NULL THEN
            RAISE EXCEPTION 'New hosted terms scope cannot fabricate a prior current source';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.hosted_scope_id <> OLD.hosted_scope_id
        OR NEW.current_version_id IS NULL
        OR NEW.current_source_version IS NULL
        OR NEW.current_material_generation IS NULL THEN
        RAISE EXCEPTION 'Hosted terms head update must retain canonical scope and positive source';
    END IF;
    SELECT * INTO next_version FROM account_hosted_terms_catalog_versions
        WHERE hosted_scope_id = NEW.hosted_scope_id AND version_id = NEW.current_version_id;
    IF next_version.version_id IS NULL
        OR next_version.source_version <> NEW.current_source_version
        OR next_version.material_generation <> NEW.current_material_generation
        OR next_version.effective_at > clock_timestamp() THEN
        RAISE EXCEPTION 'Only an exact due catalog version may become operative';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM account_hosted_terms_publication_operations publication
        WHERE publication.hosted_scope_id = NEW.hosted_scope_id
          AND publication.candidate_version_id = NEW.current_version_id
          AND publication.status IN ('SCHEDULED', 'PENDING_OWNER_SETTLEMENT')) THEN
        RAISE EXCEPTION 'Operative terms must match a durable Account publication intent';
    END IF;
    IF OLD.current_version_id IS NULL THEN
        IF next_version.predecessor_version_id IS NOT NULL
            OR next_version.source_version <> 1 OR next_version.material_generation <> 1 THEN
            RAISE EXCEPTION 'Initial operative source cannot invent predecessor state';
        END IF;
    ELSE
        IF next_version.predecessor_version_id <> OLD.current_version_id
            OR next_version.source_version <> OLD.current_source_version + 1 THEN
            RAISE EXCEPTION 'Operative terms must advance from the exact prior head';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_hosted_terms_scope_head
    BEFORE INSERT OR UPDATE OR DELETE ON account_hosted_terms_scopes
    FOR EACH ROW EXECUTE FUNCTION account_hosted_terms_scope_head_guard();

CREATE FUNCTION account_hosted_terms_scope_head_complete_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.current_version_id IS DISTINCT FROM OLD.current_version_id
        AND NOT EXISTS (SELECT 1 FROM account_hosted_terms_publication_operations publication
            WHERE publication.hosted_scope_id = NEW.hosted_scope_id
              AND publication.candidate_version_id = NEW.current_version_id
              AND publication.status = 'COMMITTED') THEN
        RAISE EXCEPTION 'Operative catalog head requires committed publication readback';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER account_hosted_terms_scope_head_complete
    AFTER UPDATE ON account_hosted_terms_scopes DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_hosted_terms_scope_head_complete_guard();

CREATE FUNCTION account_hosted_terms_publication_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Hosted terms publication request history cannot be deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'RECEIVED' OR NEW.candidate_version_id IS NOT NULL
            OR NEW.source_change_binding IS NOT NULL OR NEW.result_payload IS NOT NULL THEN
            RAISE EXCEPTION 'Publication operation must begin as exact received intent';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.request_id <> OLD.request_id OR NEW.hosted_scope_id <> OLD.hosted_scope_id
        OR NEW.request_payload <> OLD.request_payload OR NEW.request_digest <> OLD.request_digest
        OR (OLD.candidate_version_id IS NOT NULL
            AND NEW.candidate_version_id IS DISTINCT FROM OLD.candidate_version_id)
        OR (OLD.source_change_binding IS NOT NULL
            AND NEW.source_change_binding IS DISTINCT FROM OLD.source_change_binding)
        OR OLD.status = 'COMMITTED' THEN
        RAISE EXCEPTION 'Publication request identity and candidate are immutable';
    END IF;
    IF NOT ((OLD.status = 'RECEIVED' AND NEW.status IN ('SCHEDULED', 'PENDING_OWNER_SETTLEMENT', 'COMMITTED'))
        OR (OLD.status = 'SCHEDULED' AND NEW.status IN ('PENDING_OWNER_SETTLEMENT', 'COMMITTED'))
        OR (OLD.status = 'PENDING_OWNER_SETTLEMENT' AND NEW.status = 'COMMITTED')) THEN
        RAISE EXCEPTION 'Invalid hosted terms publication transition';
    END IF;
    IF NEW.status = 'COMMITTED' THEN
        IF NEW.candidate_version_id IS NULL OR NEW.result_payload IS NULL
            OR NEW.result_digest IS NULL OR NEW.committed_at IS NULL
            OR NOT EXISTS (SELECT 1 FROM account_hosted_terms_scopes scope
                WHERE scope.hosted_scope_id = NEW.hosted_scope_id
                  AND scope.current_version_id = NEW.candidate_version_id)
            OR NOT EXISTS (SELECT 1 FROM account_hosted_terms_catalog_versions version
                WHERE version.hosted_scope_id = NEW.hosted_scope_id
                  AND version.version_id = NEW.candidate_version_id
                  AND version.version_payload = NEW.result_payload) THEN
            RAISE EXCEPTION 'Committed publication requires exact operative catalog readback';
        END IF;
    ELSIF NEW.result_payload IS NOT NULL OR NEW.result_digest IS NOT NULL OR NEW.committed_at IS NOT NULL THEN
        RAISE EXCEPTION 'Unsettled publication cannot have a committed result';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_hosted_terms_publication_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON account_hosted_terms_publication_operations
    FOR EACH ROW EXECUTE FUNCTION account_hosted_terms_publication_guard();

CREATE FUNCTION account_hosted_terms_publication_fence_complete_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE predecessor UUID;
BEGIN
    IF NEW.status = 'COMMITTED' THEN
        SELECT predecessor_version_id INTO predecessor
            FROM account_hosted_terms_catalog_versions
            WHERE hosted_scope_id = NEW.hosted_scope_id
              AND version_id = NEW.candidate_version_id;
        IF predecessor IS NOT NULL AND (NEW.source_change_binding IS NULL OR NOT EXISTS (
            SELECT 1 FROM account_draft_authorization_source_changes change
            JOIN account_draft_authorization_changed_scopes changed USING (change_id)
            WHERE change.change_id = NEW.request_id
              AND change.binding = NEW.source_change_binding
              AND change.status = 'SOURCE_COMMITTED'
              AND changed.source_key = 'HOSTED_TERMS:' || NEW.hosted_scope_id::TEXT)) THEN
            RAISE EXCEPTION 'Updated hosted terms require fully settled exact Draft source change';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER account_hosted_terms_publication_fence_complete
    AFTER INSERT OR UPDATE ON account_hosted_terms_publication_operations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_hosted_terms_publication_fence_complete_guard();

CREATE FUNCTION account_hosted_terms_acceptance_insert_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE source account_individual_creator_party_sources%ROWTYPE;
DECLARE terms account_hosted_terms_catalog_versions%ROWTYPE;
DECLARE scope account_hosted_terms_scopes%ROWTYPE;
DECLARE action JSONB;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Hosted terms acceptances are append-only';
    END IF;
    SELECT * INTO source FROM account_individual_creator_party_sources
        WHERE creator_party_id = NEW.creator_party_id AND account_uuid = NEW.account_uuid FOR UPDATE;
    SELECT * INTO terms FROM account_hosted_terms_catalog_versions
        WHERE hosted_scope_id = NEW.hosted_scope_id AND version_id = NEW.terms_version_id;
    SELECT * INTO scope FROM account_hosted_terms_scopes
        WHERE hosted_scope_id = NEW.hosted_scope_id FOR UPDATE;
    action := convert_from(NEW.affirmative_action_evidence, 'UTF8')::JSONB;
    IF source.creator_party_id IS NULL OR source.verification_status <> 'VERIFIED'
        OR source.source_payload <> NEW.individual_party_source
        OR terms.version_id IS NULL
        OR terms.document_digest <> NEW.document_digest
        OR terms.operator_legal_identity <> NEW.operator_legal_identity
        OR terms.operator_identity_version <> NEW.operator_identity_version
        OR terms.source_version <> NEW.source_version
        OR terms.material_generation <> NEW.material_generation
        OR NEW.accepted_at IS DISTINCT FROM CURRENT_TIMESTAMP
        OR action->>'schema' IS DISTINCT FROM 'account-hosted-terms-affirmative-action/v1'
        OR action->>'actionRequestId' IS DISTINCT FROM NEW.action_request_id::TEXT
        OR action->>'affirmative' IS DISTINCT FROM 'true'
        OR action->>'accountId' IS DISTINCT FROM NEW.account_uuid::TEXT
        OR action->>'creatorPartyId' IS DISTINCT FROM NEW.creator_party_id::TEXT
        OR action->>'hostedScopeId' IS DISTINCT FROM NEW.hosted_scope_id::TEXT
        OR action->>'shownVersionId' IS DISTINCT FROM NEW.terms_version_id::TEXT
        OR action->>'shownDocumentDigest' IS DISTINCT FROM NEW.document_digest
        OR action->>'shownOperatorLegalIdentity' IS DISTINCT FROM NEW.operator_legal_identity
        OR action->>'shownOperatorIdentityVersion' IS DISTINCT FROM NEW.operator_identity_version::TEXT
        OR (scope.current_version_id IS DISTINCT FROM NEW.terms_version_id
            AND NOT EXISTS (SELECT 1 FROM account_hosted_terms_publication_operations publication
                WHERE publication.hosted_scope_id = NEW.hosted_scope_id
                  AND publication.candidate_version_id = NEW.terms_version_id
                  AND publication.status IN ('SCHEDULED', 'PENDING_OWNER_SETTLEMENT'))) THEN
        RAISE EXCEPTION 'Acceptance must bind exact locally verified party and shown catalog evidence';
    END IF;
    -- Acceptance may be recorded in advance for one disclosed scheduled version. It remains
    -- append-only and is not current until that exact version is on the operative ancestry.
    IF scope.current_version_id IS NULL AND terms.predecessor_version_id IS NOT NULL THEN
        RAISE EXCEPTION 'Hosted terms acceptance requires an existing operative source';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_hosted_terms_acceptance_insert
    BEFORE INSERT OR UPDATE OR DELETE ON account_individual_hosted_terms_acceptances
    FOR EACH ROW EXECUTE FUNCTION account_hosted_terms_acceptance_insert_guard();
-- [jooq ignore stop]
