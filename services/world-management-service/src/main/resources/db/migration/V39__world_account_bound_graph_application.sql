-- Preserve all V30 histories and bytes. A past unverified row has no application transaction.
ALTER TABLE world_topology_draft_commit ADD COLUMN application_transaction_id BIGINT;

CREATE TABLE world_draft_graph_application (
    operation_id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    commit_id UUID NOT NULL UNIQUE,
    authorization_fence_id UUID NOT NULL UNIQUE,
    operation_bytes BYTEA NOT NULL CHECK (octet_length(operation_bytes) > 0),
    account_binding_bytes BYTEA NOT NULL CHECK (octet_length(account_binding_bytes) > 0),
    account_binding_digest VARCHAR(71) NOT NULL CHECK (account_binding_digest ~ '^sha256:[0-9a-f]{64}$'),
    result_bytes BYTEA NOT NULL CHECK (octet_length(result_bytes) > 0),
    result_digest VARCHAR(71) NOT NULL CHECK (result_digest ~ '^sha256:[0-9a-f]{64}$'),
    application_transaction_id BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (request_id) REFERENCES world_topology_draft_commit(request_id),
    FOREIGN KEY (commit_id) REFERENCES world_topology_draft_commit(commit_id),
    CHECK (operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND authorization_fence_id <> '00000000-0000-0000-0000-000000000000'::UUID)
);

-- One immutable identity namespace across positive and no-commit outcomes, including altered
-- target bindings that would otherwise lock different V25 rows. Retained abort bytes are copied
-- exactly; old permission-unverified graphs never receive a positive terminal identity.
CREATE TABLE world_draft_graph_terminal_identity (
    operation_id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    commit_id UUID NOT NULL UNIQUE,
    authorization_fence_id UUID NOT NULL UNIQUE,
    account_binding_bytes BYTEA NOT NULL,
    account_binding_digest VARCHAR(71) NOT NULL,
    outcome VARCHAR(32) NOT NULL CHECK (outcome IN ('APPLIED', 'DEFINITIVELY_ABORTED'))
);
INSERT INTO world_draft_graph_terminal_identity
    (operation_id,request_id,commit_id,authorization_fence_id,account_binding_bytes,account_binding_digest,outcome)
    SELECT operation_id,request_id,commit_id,authorization_fence_id,account_binding_bytes,account_binding_digest,outcome
    FROM world_draft_terminal_outcome;

-- [jooq ignore start]
CREATE FUNCTION world_claim_graph_terminal_identity() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    retained "${serviceSchema}".world_draft_graph_terminal_identity%ROWTYPE;
    expected_outcome TEXT;
    identity_count BIGINT;
BEGIN
    expected_outcome := CASE TG_TABLE_NAME WHEN 'world_draft_graph_application'
        THEN 'APPLIED' ELSE 'DEFINITIVELY_ABORTED' END;
    INSERT INTO "${serviceSchema}".world_draft_graph_terminal_identity
        (operation_id,request_id,commit_id,authorization_fence_id,account_binding_bytes,account_binding_digest,outcome)
        VALUES (NEW.operation_id,NEW.request_id,NEW.commit_id,NEW.authorization_fence_id,
            NEW.account_binding_bytes,NEW.account_binding_digest,expected_outcome)
        ON CONFLICT DO NOTHING;
    SELECT count(*) INTO identity_count FROM "${serviceSchema}".world_draft_graph_terminal_identity i
        WHERE i.operation_id=NEW.operation_id OR i.request_id=NEW.request_id OR i.commit_id=NEW.commit_id
            OR i.authorization_fence_id=NEW.authorization_fence_id;
    IF identity_count <> 1 THEN
        RAISE EXCEPTION 'World terminal identities select conflicting retained original operations' USING ERRCODE='23514';
    END IF;
    SELECT * INTO STRICT retained FROM "${serviceSchema}".world_draft_graph_terminal_identity i
        WHERE i.operation_id=NEW.operation_id OR i.request_id=NEW.request_id OR i.commit_id=NEW.commit_id
            OR i.authorization_fence_id=NEW.authorization_fence_id FOR UPDATE;
    IF retained.operation_id IS DISTINCT FROM NEW.operation_id
        OR retained.request_id IS DISTINCT FROM NEW.request_id
        OR retained.commit_id IS DISTINCT FROM NEW.commit_id
        OR retained.authorization_fence_id IS DISTINCT FROM NEW.authorization_fence_id
        OR retained.account_binding_bytes IS DISTINCT FROM NEW.account_binding_bytes
        OR retained.account_binding_digest IS DISTINCT FROM NEW.account_binding_digest
        OR retained.outcome IS DISTINCT FROM expected_outcome THEN
        RAISE EXCEPTION 'World original terminal identity cannot be reused or relabeled' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE FUNCTION world_verify_graph_terminal_identity_receipt() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    IF NEW.outcome = 'APPLIED' THEN
        IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_draft_graph_application a
            WHERE a.operation_id=NEW.operation_id AND a.request_id=NEW.request_id AND a.commit_id=NEW.commit_id
                AND a.authorization_fence_id=NEW.authorization_fence_id
                AND a.account_binding_bytes=NEW.account_binding_bytes AND a.account_binding_digest=NEW.account_binding_digest) THEN
            RETURN NEW;
        END IF;
    ELSE
        IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_draft_terminal_outcome a
            WHERE a.operation_id=NEW.operation_id AND a.request_id=NEW.request_id AND a.commit_id=NEW.commit_id
                AND a.authorization_fence_id=NEW.authorization_fence_id
                AND a.account_binding_bytes=NEW.account_binding_bytes AND a.account_binding_digest=NEW.account_binding_digest) THEN
            RETURN NEW;
        END IF;
    END IF;
    RAISE EXCEPTION 'World terminal identity lacks its exact immutable owner receipt' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER trg_world_terminal_identity_immutable BEFORE UPDATE OR DELETE
    ON world_draft_graph_terminal_identity FOR EACH ROW EXECUTE FUNCTION world_reject_authored_source_history_mutation();
CREATE TRIGGER trg_world_terminal_identity_no_truncate BEFORE TRUNCATE
    ON world_draft_graph_terminal_identity FOR EACH STATEMENT EXECUTE FUNCTION world_reject_authored_source_history_mutation();
CREATE CONSTRAINT TRIGGER trg_world_terminal_identity_receipt AFTER INSERT ON world_draft_graph_terminal_identity
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION world_verify_graph_terminal_identity_receipt();
CREATE TRIGGER trg_world_graph_terminal_identity_claim BEFORE INSERT
    ON world_draft_graph_application FOR EACH ROW EXECUTE FUNCTION world_claim_graph_terminal_identity();
CREATE TRIGGER trg_world_abort_terminal_identity_claim BEFORE INSERT
    ON world_draft_terminal_outcome FOR EACH ROW EXECUTE FUNCTION world_claim_graph_terminal_identity();

CREATE FUNCTION world_stamp_topology_application_transaction() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    NEW.application_transaction_id := txid_current();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_world_topology_application_transaction BEFORE INSERT
    ON world_topology_draft_commit FOR EACH ROW
    EXECUTE FUNCTION world_stamp_topology_application_transaction();

CREATE FUNCTION world_guard_graph_application() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    graph_row "${serviceSchema}".world_topology_draft_commit%ROWTYPE;
    owner_row "${serviceSchema}".world_design_publication_fence_owner%ROWTYPE;
    unit JSONB;
    current_epoch BIGINT;
    mapped_key BIGINT;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'World graph APPLIED results are immutable and retained' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO graph_row FROM "${serviceSchema}".world_topology_draft_commit
        WHERE request_id = NEW.request_id AND commit_id = NEW.commit_id;
    IF NOT FOUND OR graph_row.application_transaction_id IS DISTINCT FROM txid_current() THEN
        RAISE EXCEPTION 'APPLIED requires a genuine fresh graph in the same transaction; old history cannot be promoted'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO owner_row FROM "${serviceSchema}".world_design_publication_fence_owner
        WHERE target_namespace = graph_row.target_namespace
          AND canonical_tenant_id = graph_row.canonical_tenant_id
          AND local_tenant_key = graph_row.local_tenant_key
          AND version_id = graph_row.local_version_key FOR UPDATE;
    IF NOT FOUND OR owner_row.owner_freeze_phase <> 'OPEN'
        OR owner_row.current_publication_fence IS NOT NULL THEN
        RAISE EXCEPTION 'Fresh graph APPLIED requires exact OPEN V25 owner' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_draft_terminal_outcome t
        WHERE t.operation_id = NEW.operation_id OR t.request_id = NEW.request_id
           OR t.commit_id = NEW.commit_id OR t.authorization_fence_id = NEW.authorization_fence_id) THEN
        RAISE EXCEPTION 'Definitively aborted World operation cannot apply' USING ERRCODE = '23514';
    END IF;
    IF (SELECT count(DISTINCT family) FROM "${serviceSchema}".world_authored_topology_identity
        WHERE request_id = NEW.request_id AND commit_id = NEW.commit_id) <> 6 THEN
        RAISE EXCEPTION 'APPLIED requires all six genuinely mutated World families' USING ERRCODE = '23514';
    END IF;
    FOR unit IN SELECT value FROM jsonb_array_elements(graph_row.binding_json::JSONB->'affectedUnits')
        WHERE value->>'owner' = 'WORLD_MANAGEMENT' LOOP
        IF unit->>'expectedEpoch' <> '0' THEN
            RAISE EXCEPTION 'Fresh graph APPLIED requires original zero epochs' USING ERRCODE = '23514';
        END IF;
        SELECT private_row_key INTO mapped_key FROM "${serviceSchema}".world_authored_topology_identity
            WHERE request_id = NEW.request_id AND commit_id = NEW.commit_id
              AND family = unit->>'aggregateType' AND template_id = (unit->>'aggregateId')::UUID;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'APPLIED affected unit lacks exact fresh mapping' USING ERRCODE = '23514';
        END IF;
        IF unit->>'scopeType' = 'AGGREGATE' THEN
            SELECT draft_revision_epoch INTO current_epoch FROM "${serviceSchema}".world_design_aggregate_epoch
                WHERE tenant_id = graph_row.local_tenant_key AND version_id = graph_row.local_version_key
                  AND aggregate_type = unit->>'aggregateType' AND aggregate_id = mapped_key;
        ELSE
            SELECT draft_scope_revision_epoch INTO current_epoch FROM "${serviceSchema}".world_design_scope_epoch
                WHERE tenant_id = graph_row.local_tenant_key AND version_id = graph_row.local_version_key
                  AND scope_type = unit->>'scopeType' AND scope_id = unit->>'scopeId';
        END IF;
        IF NOT FOUND OR current_epoch IS DISTINCT FROM 1::BIGINT THEN
            RAISE EXCEPTION 'APPLIED lacks complete resulting World epochs' USING ERRCODE = '23514';
        END IF;
    END LOOP;
    NEW.application_transaction_id := txid_current();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_world_graph_application_guard BEFORE INSERT OR UPDATE OR DELETE
    ON world_draft_graph_application FOR EACH ROW EXECUTE FUNCTION world_guard_graph_application();
CREATE TRIGGER trg_world_graph_application_no_truncate BEFORE TRUNCATE
    ON world_draft_graph_application FOR EACH STATEMENT
    EXECUTE FUNCTION world_reject_authored_source_history_mutation();
CREATE FUNCTION world_abort_excludes_graph_application() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    PERFORM 1 FROM "${serviceSchema}".world_design_publication_fence_owner
        WHERE target_namespace = NEW.target_namespace AND canonical_tenant_id = NEW.canonical_tenant_id
          AND local_tenant_key = NEW.local_tenant_key AND version_id = NEW.local_version_key FOR UPDATE;
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_draft_graph_application a
        WHERE a.operation_id = NEW.operation_id OR a.request_id = NEW.request_id
           OR a.commit_id = NEW.commit_id OR a.authorization_fence_id = NEW.authorization_fence_id) THEN
        RAISE EXCEPTION 'Committed World graph cannot be relabeled as definitive no-commit'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_world_abort_excludes_graph_application BEFORE INSERT
    ON world_draft_terminal_outcome FOR EACH ROW EXECUTE FUNCTION world_abort_excludes_graph_application();
REVOKE ALL ON world_draft_graph_application FROM PUBLIC;
REVOKE ALL ON world_draft_graph_terminal_identity FROM PUBLIC;
REVOKE ALL ON FUNCTION world_claim_graph_terminal_identity() FROM PUBLIC;
REVOKE ALL ON FUNCTION world_verify_graph_terminal_identity_receipt() FROM PUBLIC;
REVOKE ALL ON FUNCTION world_stamp_topology_application_transaction() FROM PUBLIC;
REVOKE ALL ON FUNCTION world_guard_graph_application() FROM PUBLIC;
REVOKE ALL ON FUNCTION world_abort_excludes_graph_application() FROM PUBLIC;
-- [jooq ignore stop]
