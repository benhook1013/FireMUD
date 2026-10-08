CREATE TABLE game_design_published_realm_policy_set (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    game_design_version_row_id BIGINT NOT NULL CHECK (game_design_version_row_id > 0),
    version_number INTEGER NOT NULL CHECK (version_number > 0),
    source_commit_id UUID NOT NULL,
    source_epoch TEXT NOT NULL CHECK (source_epoch ~ '^(0|[1-9][0-9]*)$'),
    target_proof_json TEXT NOT NULL,
    operation_bytes BYTEA NOT NULL CHECK (octet_length(operation_bytes) > 0),
    capture_bytes BYTEA NOT NULL CHECK (octet_length(capture_bytes) > 0),
    terminal_evidence_bytes BYTEA NOT NULL CHECK (octet_length(terminal_evidence_bytes) > 0),
    release_content_bytes BYTEA NOT NULL CHECK (octet_length(release_content_bytes) > 0),
    published_release_bundle_ref VARCHAR(1024) NOT NULL CHECK (char_length(published_release_bundle_ref) > 0),
    published_release_bundle_digest VARCHAR(71) NOT NULL CHECK (published_release_bundle_digest ~ '^sha256:[0-9a-f]{64}$'),
    publish_workflow_id VARCHAR(1024) NOT NULL UNIQUE,
    manifest_hash VARCHAR(71) NOT NULL CHECK (manifest_hash ~ '^sha256:[0-9a-f]{64}$'),
    publication_version_state_epoch BIGINT NOT NULL CHECK (publication_version_state_epoch > 0),
    policy_count INTEGER NOT NULL CHECK (policy_count BETWEEN 1 AND 128),
    policy_set_digest VARCHAR(71) NOT NULL CHECK (policy_set_digest ~ '^sha256:[0-9a-f]{64}$'),
    sealed BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (canonical_tenant_id, canonical_version_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, source_commit_id)
        REFERENCES game_design_realm_policy_snapshot (canonical_tenant_id, canonical_version_id, commit_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id)
        REFERENCES game_design_realm_policy_capture (canonical_tenant_id, canonical_version_id)
);

CREATE TABLE game_design_published_realm_policy (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 0 AND 127),
    realm_policy_id UUID NOT NULL UNIQUE,
    source_commit_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    logical_revision_id VARCHAR(128) NOT NULL CHECK (char_length(logical_revision_id) BETWEEN 1 AND 128),
    world_slug VARCHAR(64) NOT NULL,
    realm_slug VARCHAR(64) NOT NULL,
    policy_json TEXT NOT NULL CHECK (char_length(policy_json) BETWEEN 2 AND 4096),
    policy_digest VARCHAR(71) NOT NULL CHECK (policy_digest ~ '^sha256:[0-9a-f]{64}$'),
    visible BOOLEAN NOT NULL,
    public_production BOOLEAN NOT NULL,
    PRIMARY KEY (canonical_tenant_id, canonical_version_id, realm_policy_id),
    UNIQUE (canonical_tenant_id, canonical_version_id, ordinal),
    UNIQUE (canonical_tenant_id, canonical_version_id, world_slug, realm_slug),
    UNIQUE (canonical_tenant_id, canonical_version_id, realm_slug),
    UNIQUE (canonical_tenant_id, canonical_version_id, source_revision_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id)
        REFERENCES game_design_published_realm_policy_set (canonical_tenant_id, canonical_version_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, source_commit_id)
        REFERENCES game_design_realm_policy_snapshot (canonical_tenant_id, canonical_version_id, commit_id)
);

-- [jooq ignore start]
CREATE FUNCTION guard_published_realm_policy_set() RETURNS trigger AS $$
DECLARE owner_version version%ROWTYPE; owner_game game%ROWTYPE;
    operation game_design_publication_operation%ROWTYPE;
    capture game_design_realm_policy_capture%ROWTYPE;
    snapshot game_design_realm_policy_snapshot%ROWTYPE;
    release published_release_bundle%ROWTYPE;
    row_count BIGINT; realm_count BIGINT; production_count BIGINT; target_proof_key_count BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'published realm policy set is immutable' USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.sealed IS DISTINCT FROM FALSE OR NEW.sealed IS DISTINCT FROM TRUE
            OR (to_jsonb(NEW) - 'sealed') IS DISTINCT FROM (to_jsonb(OLD) - 'sealed') THEN
            RAISE EXCEPTION 'published realm policy set may only be sealed once' USING ERRCODE = 'check_violation';
        END IF;
        SELECT * INTO STRICT snapshot FROM game_design_realm_policy_snapshot
            WHERE canonical_tenant_id = NEW.canonical_tenant_id
                AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.source_commit_id;
        SELECT count(*), count(DISTINCT realm_slug),
            count(*) FILTER (WHERE visible AND public_production)
            INTO row_count, realm_count, production_count
            FROM game_design_published_realm_policy
            WHERE canonical_tenant_id = NEW.canonical_tenant_id
                AND canonical_version_id = NEW.canonical_version_id;
        IF row_count <> NEW.policy_count
            OR row_count <> jsonb_array_length(snapshot.snapshot_json::JSONB->'policies')
            OR realm_count <> row_count OR production_count <> 1
            OR NEW.policy_set_digest !~ '^sha256:[0-9a-f]{64}$' THEN
            RAISE EXCEPTION 'published realm policy set is incomplete or has invalid cardinality' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.sealed IS DISTINCT FROM FALSE
        OR jsonb_typeof(NEW.target_proof_json::JSONB) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'published realm policy set requires a closed complete TargetProof' USING ERRCODE = 'check_violation';
    END IF;
    SELECT count(*) INTO target_proof_key_count
        FROM jsonb_object_keys(NEW.target_proof_json::JSONB);
    IF target_proof_key_count <> 7
        OR (NEW.target_proof_json::JSONB - ARRAY['canonicalTenantId', 'canonicalVersionId', 'gameDesignVersionRowId',
            'gameDesignVersionTenantKey', 'sourceGameRowId', 'sourceGameTenantKey', 'sourceProvenanceKind'])
            IS DISTINCT FROM '{}'::JSONB
        OR jsonb_typeof(NEW.target_proof_json::JSONB->'canonicalTenantId') IS DISTINCT FROM 'string'
        OR jsonb_typeof(NEW.target_proof_json::JSONB->'canonicalVersionId') IS DISTINCT FROM 'string'
        OR jsonb_typeof(NEW.target_proof_json::JSONB->'gameDesignVersionRowId') IS DISTINCT FROM 'string'
        OR jsonb_typeof(NEW.target_proof_json::JSONB->'gameDesignVersionTenantKey') IS DISTINCT FROM 'string'
        OR jsonb_typeof(NEW.target_proof_json::JSONB->'sourceGameRowId') IS DISTINCT FROM 'string'
        OR jsonb_typeof(NEW.target_proof_json::JSONB->'sourceGameTenantKey') IS DISTINCT FROM 'string'
        OR jsonb_typeof(NEW.target_proof_json::JSONB->'sourceProvenanceKind') IS DISTINCT FROM 'string' THEN
        RAISE EXCEPTION 'published realm policy set requires a closed complete TargetProof' USING ERRCODE = 'check_violation';
    END IF;

    SELECT v.* INTO owner_version FROM version v JOIN game g
        ON g.id = v.identity_source_game_row_id
        AND g.tenant_id = v.identity_source_game_tenant_key
        AND g.canonical_tenant_id = v.canonical_tenant_id
        AND g.tenant_identity_provenance_kind = v.identity_source_provenance_kind
        AND g.tenant_identity_source_game_id = g.id
        AND g.tenant_identity_source_legacy_tenant_id = g.tenant_id
        WHERE v.id = NEW.game_design_version_row_id
            AND v.tenant_id = NEW.target_proof_json::JSONB->>'gameDesignVersionTenantKey'
            AND v.canonical_tenant_id = NEW.canonical_tenant_id
            AND v.canonical_version_id = NEW.canonical_version_id
            AND v.identity_source_game_row_id::TEXT = NEW.target_proof_json::JSONB->>'sourceGameRowId'
            AND v.identity_source_game_tenant_key = NEW.target_proof_json::JSONB->>'sourceGameTenantKey'
            AND v.identity_source_provenance_kind = NEW.target_proof_json::JSONB->>'sourceProvenanceKind';
    IF NOT FOUND OR owner_version.id IS DISTINCT FROM NEW.game_design_version_row_id
        OR owner_version.version_state IS DISTINCT FROM 'PUBLISHED'
        OR owner_version.version_state_epoch IS DISTINCT FROM NEW.publication_version_state_epoch
        OR owner_version.version_number IS DISTINCT FROM NEW.version_number
        OR owner_version.is_script_only IS DISTINCT FROM FALSE
        OR owner_version.script_patch_version IS NOT NULL
        OR owner_version.base_version_id IS NOT NULL
        OR NEW.target_proof_json::JSONB->>'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
        OR NEW.target_proof_json::JSONB->>'canonicalVersionId' IS DISTINCT FROM NEW.canonical_version_id::TEXT
        OR NEW.target_proof_json::JSONB->>'gameDesignVersionRowId' IS DISTINCT FROM owner_version.id::TEXT
        OR NEW.target_proof_json::JSONB->>'gameDesignVersionTenantKey' IS DISTINCT FROM owner_version.tenant_id
        OR NEW.target_proof_json::JSONB->>'sourceGameRowId' IS DISTINCT FROM owner_version.identity_source_game_row_id::TEXT
        OR NEW.target_proof_json::JSONB->>'sourceGameTenantKey' IS DISTINCT FROM owner_version.identity_source_game_tenant_key
        OR NEW.target_proof_json::JSONB->>'sourceProvenanceKind' IS DISTINCT FROM owner_version.identity_source_provenance_kind THEN
        RAISE EXCEPTION 'published realm policy set differs from exact full Version owner' USING ERRCODE = 'check_violation';
    END IF;
    SELECT g.* INTO STRICT owner_game FROM game g WHERE g.id = owner_version.identity_source_game_row_id;
    IF owner_game.id::TEXT IS DISTINCT FROM NEW.target_proof_json::JSONB->>'sourceGameRowId'
        OR owner_game.tenant_id IS DISTINCT FROM NEW.target_proof_json::JSONB->>'sourceGameTenantKey'
        OR owner_game.tenant_identity_provenance_kind IS DISTINCT FROM NEW.target_proof_json::JSONB->>'sourceProvenanceKind'
        OR owner_game.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id THEN
        RAISE EXCEPTION 'published realm policy set source provenance changed' USING ERRCODE = 'check_violation';
    END IF;

    SELECT * INTO operation FROM game_design_publication_operation
        WHERE publish_workflow_id = NEW.publish_workflow_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'published realm policy set operation is unavailable' USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO capture FROM game_design_realm_policy_capture
        WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'published realm policy set source capture is unavailable' USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO STRICT snapshot FROM game_design_realm_policy_snapshot
        WHERE canonical_tenant_id = NEW.canonical_tenant_id
            AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.source_commit_id;
    SELECT * INTO release FROM published_release_bundle
        WHERE tenant_id = owner_version.tenant_id AND version_id = owner_version.id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'published realm policy set release bundle is unavailable' USING ERRCODE = 'check_violation';
    END IF;
    IF operation.outcome IS DISTINCT FROM 'PUBLISHED'
        OR operation.request_bytes IS DISTINCT FROM NEW.operation_bytes
        OR operation.terminal_evidence_bytes IS DISTINCT FROM NEW.terminal_evidence_bytes
        OR operation.release_content_bytes IS DISTINCT FROM NEW.release_content_bytes
        OR operation.published_release_bundle_digest IS DISTINCT FROM NEW.published_release_bundle_digest
        OR operation.publication_version_state_epoch IS DISTINCT FROM NEW.publication_version_state_epoch
        OR operation.tenant_id IS DISTINCT FROM owner_version.tenant_id
        OR operation.version_id IS DISTINCT FROM owner_version.id
        OR operation.publish_workflow_id IS DISTINCT FROM NEW.publish_workflow_id
        OR capture.publish_workflow_id IS DISTINCT FROM NEW.publish_workflow_id
        OR capture.operation_bytes IS DISTINCT FROM NEW.operation_bytes
        OR capture.capture_bytes IS DISTINCT FROM NEW.capture_bytes
        OR capture.commit_id IS DISTINCT FROM NEW.source_commit_id
        OR snapshot.snapshot_json::JSONB->>'sourceEpoch' IS DISTINCT FROM NEW.source_epoch
        OR publication_release_content(release) IS DISTINCT FROM NEW.release_content_bytes
        OR 'sha256:' || encode(sha256(publication_release_content(release)), 'hex')
            IS DISTINCT FROM NEW.published_release_bundle_digest
        OR release.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
        OR release.canonical_version_id IS DISTINCT FROM NEW.canonical_version_id
        OR release.version_number IS DISTINCT FROM NEW.version_number
        OR release.published_release_bundle_ref IS DISTINCT FROM NEW.published_release_bundle_ref
        OR release.publish_workflow_id IS DISTINCT FROM NEW.publish_workflow_id
        OR release.manifest_hash IS DISTINCT FROM NEW.manifest_hash
        OR to_jsonb(release)::TEXT IS DISTINCT FROM operation.release_row_json
        OR NOT EXISTS (SELECT 1 FROM game_design_realm_policy_source s
            WHERE s.canonical_tenant_id = NEW.canonical_tenant_id
                AND s.canonical_version_id = NEW.canonical_version_id
                AND s.visible_commit_id = NEW.source_commit_id AND s.source_epoch = NEW.source_epoch)
        OR NOT EXISTS (SELECT 1 FROM game_design_draft_commit_visibility v
            WHERE v.canonical_tenant_id = NEW.canonical_tenant_id
                AND v.canonical_version_id = NEW.canonical_version_id
                AND v.commit_id = NEW.source_commit_id)
        OR jsonb_array_length(snapshot.snapshot_json::JSONB->'policies') <> NEW.policy_count THEN
        RAISE EXCEPTION 'published realm policy set is not bound to exact sealed operation, capture, source, and release' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER published_realm_policy_set_guard BEFORE INSERT OR UPDATE OR DELETE
    ON game_design_published_realm_policy_set FOR EACH ROW EXECUTE FUNCTION guard_published_realm_policy_set();

CREATE FUNCTION guard_published_realm_policy_row() RETURNS trigger AS $$
DECLARE set_row game_design_published_realm_policy_set%ROWTYPE;
    snapshot game_design_realm_policy_snapshot%ROWTYPE;
    matching JSONB;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'published realm policy rows are immutable' USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO set_row FROM game_design_published_realm_policy_set
        WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id;
    IF NOT FOUND OR set_row.sealed THEN
        RAISE EXCEPTION 'published realm policy row requires its open atomic set' USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO STRICT snapshot FROM game_design_realm_policy_snapshot
        WHERE canonical_tenant_id = NEW.canonical_tenant_id
            AND canonical_version_id = NEW.canonical_version_id AND commit_id = set_row.source_commit_id;
    SELECT p INTO matching FROM jsonb_array_elements(snapshot.snapshot_json::JSONB->'policies')
        WITH ORDINALITY AS entries(p, source_ordinal)
        WHERE source_ordinal = NEW.ordinal + 1
            AND p->>'commitId' = NEW.source_commit_id::TEXT
            AND p->>'revisionId' = NEW.source_revision_id::TEXT
            AND p->>'logicalRevisionId' = NEW.logical_revision_id
            AND p->'policy' = NEW.policy_json::JSONB;
    IF matching IS NULL
        OR NEW.world_slug IS DISTINCT FROM matching->'policy'->>'worldSlug'
        OR NEW.realm_slug IS DISTINCT FROM matching->'policy'->>'realmSlug'
        OR NEW.visible IS DISTINCT FROM (matching->'policy'->>'visible')::BOOLEAN
        OR NEW.public_production IS DISTINCT FROM (matching->'policy'->>'publicProduction')::BOOLEAN
        OR jsonb_typeof(NEW.policy_json::JSONB) IS DISTINCT FROM 'object'
        OR NEW.policy_digest !~ '^sha256:[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'published realm policy row differs from exact captured source revision' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER published_realm_policy_row_guard BEFORE INSERT OR UPDATE OR DELETE
    ON game_design_published_realm_policy FOR EACH ROW EXECUTE FUNCTION guard_published_realm_policy_row();

CREATE FUNCTION verify_published_realm_policy_set_commit() RETURNS trigger AS $$
DECLARE set_row game_design_published_realm_policy_set%ROWTYPE; row_count BIGINT;
    realm_count BIGINT; production_count BIGINT;
BEGIN
    SELECT * INTO STRICT set_row FROM game_design_published_realm_policy_set
        WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id;
    SELECT count(*), count(DISTINCT realm_slug),
        count(*) FILTER (WHERE visible AND public_production)
        INTO row_count, realm_count, production_count
        FROM game_design_published_realm_policy
        WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id;
    IF NOT set_row.sealed OR row_count <> set_row.policy_count OR realm_count <> row_count
        OR production_count <> 1 OR row_count NOT BETWEEN 1 AND 128 THEN
        RAISE EXCEPTION 'published realm policy set did not commit as one complete canonical set' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER published_realm_policy_set_commit_guard
    AFTER INSERT OR UPDATE ON game_design_published_realm_policy_set
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_published_realm_policy_set_commit();

CREATE FUNCTION deny_published_realm_policy_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'published realm policy evidence cannot be truncated' USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER published_realm_policy_set_no_truncate BEFORE TRUNCATE
    ON game_design_published_realm_policy_set FOR EACH STATEMENT EXECUTE FUNCTION deny_published_realm_policy_truncate();
CREATE TRIGGER published_realm_policy_no_truncate BEFORE TRUNCATE
    ON game_design_published_realm_policy FOR EACH STATEMENT EXECUTE FUNCTION deny_published_realm_policy_truncate();
-- [jooq ignore stop]
