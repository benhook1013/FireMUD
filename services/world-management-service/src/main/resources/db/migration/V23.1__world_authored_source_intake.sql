CREATE TABLE world_authored_source_tenant_association (
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    tenant_slug VARCHAR(120) NOT NULL,
    source_game_row_id BIGINT NOT NULL,
    source_game_tenant_key VARCHAR(72) NOT NULL,
    source_provenance_kind VARCHAR(32) NOT NULL,
    local_tenant_key BIGINT NOT NULL,
    CONSTRAINT pk_world_authored_source_tenant_association
        PRIMARY KEY (target_namespace, canonical_tenant_id),
    CONSTRAINT uq_world_authored_source_tenant_local_key UNIQUE (local_tenant_key),
    CONSTRAINT uq_world_authored_source_tenant_selector UNIQUE (target_namespace, tenant_slug),
    CONSTRAINT uq_world_authored_source_game_row UNIQUE (target_namespace, source_game_row_id),
    CONSTRAINT uq_world_authored_source_tenant_binding
        UNIQUE (target_namespace, canonical_tenant_id, local_tenant_key),
    CONSTRAINT ck_world_authored_source_tenant_canonical_id
        CHECK (canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT ck_world_authored_source_tenant_game_row CHECK (source_game_row_id > 0),
    CONSTRAINT ck_world_authored_source_tenant_local_key CHECK (local_tenant_key > 0),
    CONSTRAINT ck_world_authored_source_tenant_provenance
        CHECK (source_provenance_kind = 'NEW_GAME_ROW')
);

CREATE TABLE world_authored_source_tenant_key_reservation (
    tenant_key BIGINT NOT NULL,
    claim_kind VARCHAR(32) NOT NULL,
    target_namespace VARCHAR(63),
    canonical_tenant_id UUID,
    CONSTRAINT pk_world_authored_source_tenant_key_reservation PRIMARY KEY (tenant_key),
    CONSTRAINT fk_world_authored_source_tenant_key_canonical_binding
        FOREIGN KEY (target_namespace, canonical_tenant_id, tenant_key)
        REFERENCES world_authored_source_tenant_association
            (target_namespace, canonical_tenant_id, local_tenant_key),
    CONSTRAINT ck_world_authored_source_tenant_key_claim_kind CHECK (
        (claim_kind = 'LEGACY_NUMERIC'
            AND target_namespace IS NULL
            AND canonical_tenant_id IS NULL)
        OR
        (claim_kind = 'CANONICAL_AUTHORED_SOURCE'
            AND target_namespace IS NOT NULL
            AND canonical_tenant_id IS NOT NULL
            AND tenant_key > 0)
    )
);

INSERT INTO world_authored_source_tenant_key_reservation (tenant_key, claim_kind)
SELECT DISTINCT tenant_id, 'LEGACY_NUMERIC'
FROM (
    SELECT tenant_id FROM generation_rule
    UNION ALL SELECT tenant_id FROM instance
    UNION ALL SELECT tenant_id FROM region
    UNION ALL SELECT tenant_id FROM region_instance
    UNION ALL SELECT tenant_id FROM room
    UNION ALL SELECT tenant_id FROM room_exit
    UNION ALL SELECT tenant_id FROM room_instance
    UNION ALL SELECT tenant_id FROM room_instance_exit
    UNION ALL SELECT tenant_id FROM world_design_aggregate_epoch
    UNION ALL SELECT tenant_id FROM world_design_revision_ledger
    UNION ALL SELECT tenant_id FROM world_design_scope_epoch
    UNION ALL SELECT tenant_id FROM world_entity_spawn_binding
    UNION ALL SELECT tenant_id FROM world_event
    UNION ALL SELECT tenant_id FROM world_instance
    UNION ALL SELECT tenant_id FROM zone
    UNION ALL SELECT tenant_id FROM zone_instance
) AS existing_world_tenant_keys;

-- [jooq ignore start]
CREATE FUNCTION world_claim_legacy_numeric_tenant_key()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    claimed_key BIGINT;
BEGIN
    INSERT INTO world_authored_source_tenant_key_reservation AS reservation
        (tenant_key, claim_kind)
    VALUES (NEW.tenant_id, 'LEGACY_NUMERIC')
    ON CONFLICT (tenant_key) DO UPDATE
        SET tenant_key = EXCLUDED.tenant_key
        WHERE reservation.claim_kind = 'LEGACY_NUMERIC'
    RETURNING reservation.tenant_key INTO claimed_key;

    IF claimed_key IS NULL THEN
        RAISE EXCEPTION 'World tenant key % is reserved for a canonical authored source', NEW.tenant_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION world_reject_authored_source_history_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'World authored-source association and receipt history is immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE FUNCTION world_protect_tenant_key_reservation_history()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.tenant_key IS NOT DISTINCT FROM NEW.tenant_key
            AND OLD.claim_kind IS NOT DISTINCT FROM NEW.claim_kind
            AND OLD.target_namespace IS NOT DISTINCT FROM NEW.target_namespace
            AND OLD.canonical_tenant_id IS NOT DISTINCT FROM NEW.canonical_tenant_id
        THEN
            RETURN NEW;
        END IF;
    END IF;
    RAISE EXCEPTION 'World tenant-key reservation history cannot be changed or deleted'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_world_tenant_key_reservation_immutable
    BEFORE UPDATE OR DELETE ON world_authored_source_tenant_key_reservation
    FOR EACH ROW EXECUTE FUNCTION world_protect_tenant_key_reservation_history();

CREATE TRIGGER trg_world_tenant_key_reservation_no_truncate
    BEFORE TRUNCATE ON world_authored_source_tenant_key_reservation
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_authored_source_history_mutation();

CREATE TRIGGER trg_world_authored_source_association_immutable
    BEFORE UPDATE OR DELETE ON world_authored_source_tenant_association
    FOR EACH ROW EXECUTE FUNCTION world_reject_authored_source_history_mutation();

CREATE TRIGGER trg_world_authored_source_association_no_truncate
    BEFORE TRUNCATE ON world_authored_source_tenant_association
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_authored_source_history_mutation();

CREATE TRIGGER trg_reserve_generation_rule_tenant_key
    BEFORE INSERT OR UPDATE OF tenant_id ON generation_rule
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();
CREATE TRIGGER trg_reserve_instance_tenant_key
    BEFORE INSERT OR UPDATE OF tenant_id ON instance
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();
CREATE TRIGGER trg_reserve_region_tenant_key
    BEFORE INSERT OR UPDATE OF tenant_id ON region
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();
CREATE TRIGGER trg_reserve_region_instance_tenant_key
    BEFORE INSERT OR UPDATE OF tenant_id ON region_instance
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();
CREATE TRIGGER trg_reserve_room_tenant_key
    BEFORE INSERT OR UPDATE OF tenant_id ON room
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();
CREATE TRIGGER trg_reserve_room_exit_tenant_key
    BEFORE INSERT OR UPDATE OF tenant_id ON room_exit
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();
CREATE TRIGGER trg_reserve_room_instance_tenant_key
    BEFORE INSERT OR UPDATE OF tenant_id ON room_instance
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();
CREATE TRIGGER trg_reserve_room_instance_exit_tenant_key
    BEFORE INSERT OR UPDATE OF tenant_id ON room_instance_exit
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();
CREATE TRIGGER trg_reserve_world_design_aggregate_epoch_tenant_key
    BEFORE INSERT OR UPDATE OF tenant_id ON world_design_aggregate_epoch
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();
CREATE TRIGGER trg_reserve_world_design_revision_ledger_tenant_key
    BEFORE INSERT OR UPDATE OF tenant_id ON world_design_revision_ledger
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();
CREATE TRIGGER trg_reserve_world_design_scope_epoch_tenant_key
    BEFORE INSERT OR UPDATE OF tenant_id ON world_design_scope_epoch
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();
CREATE TRIGGER trg_reserve_world_entity_spawn_binding_tenant_key
    BEFORE INSERT OR UPDATE OF tenant_id ON world_entity_spawn_binding
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();
CREATE TRIGGER trg_reserve_world_event_tenant_key
    BEFORE INSERT OR UPDATE OF tenant_id ON world_event
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();
CREATE TRIGGER trg_reserve_world_instance_tenant_key
    BEFORE INSERT OR UPDATE OF tenant_id ON world_instance
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();
CREATE TRIGGER trg_reserve_zone_tenant_key
    BEFORE INSERT OR UPDATE OF tenant_id ON zone
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();
CREATE TRIGGER trg_reserve_zone_instance_tenant_key
    BEFORE INSERT OR UPDATE OF tenant_id ON zone_instance
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();
-- [jooq ignore stop]

CREATE TABLE world_authored_source_intake (
    operation_id UUID NOT NULL,
    schema_version SMALLINT NOT NULL,
    target_namespace VARCHAR(63) NOT NULL,
    intake_request_id UUID NOT NULL,
    request_digest VARCHAR(71) NOT NULL,
    local_tenant_key BIGINT NOT NULL,
    source_schema_version SMALLINT NOT NULL,
    source_registration_request_id UUID NOT NULL,
    source_operation_id UUID NOT NULL,
    source_request_digest VARCHAR(71) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    tenant_slug VARCHAR(120) NOT NULL,
    world_slug VARCHAR(120) NOT NULL,
    world_display_name VARCHAR(200) NOT NULL,
    source_game_row_id BIGINT NOT NULL,
    source_game_tenant_key VARCHAR(72) NOT NULL,
    source_provenance_kind VARCHAR(32) NOT NULL,
    source_evidence_digest VARCHAR(71) NOT NULL,
    receipt_digest VARCHAR(71) NOT NULL,
    CONSTRAINT pk_world_authored_source_intake PRIMARY KEY (operation_id),
    CONSTRAINT uq_world_authored_source_intake_request
        UNIQUE (target_namespace, intake_request_id),
    CONSTRAINT uq_world_authored_source_intake_source_operation
        UNIQUE (target_namespace, source_operation_id),
    CONSTRAINT uq_world_authored_source_intake_source_request
        UNIQUE (target_namespace, source_registration_request_id),
    CONSTRAINT uq_world_authored_source_intake_world
        UNIQUE (target_namespace, canonical_tenant_id, world_slug),
    CONSTRAINT fk_world_authored_source_intake_tenant_binding
        FOREIGN KEY (target_namespace, canonical_tenant_id, local_tenant_key)
        REFERENCES world_authored_source_tenant_association
            (target_namespace, canonical_tenant_id, local_tenant_key),
    CONSTRAINT ck_world_authored_source_intake_schema CHECK (schema_version = 1),
    CONSTRAINT ck_world_authored_source_intake_source_schema CHECK (source_schema_version = 1),
    CONSTRAINT ck_world_authored_source_intake_local_key CHECK (local_tenant_key > 0),
    CONSTRAINT ck_world_authored_source_intake_source_game_row CHECK (source_game_row_id > 0),
    CONSTRAINT ck_world_authored_source_intake_provenance
        CHECK (source_provenance_kind = 'NEW_GAME_ROW'),
    CONSTRAINT ck_world_authored_source_intake_request_digest
        CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_world_authored_source_intake_source_request_digest
        CHECK (source_request_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_world_authored_source_intake_source_evidence_digest
        CHECK (source_evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_world_authored_source_intake_receipt_digest
        CHECK (receipt_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_world_authored_source_intake_non_nil_ids CHECK (
        operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND intake_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND source_registration_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND source_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
    )
);

-- [jooq ignore start]
CREATE TRIGGER trg_world_authored_source_intake_immutable
    BEFORE UPDATE OR DELETE ON world_authored_source_intake
    FOR EACH ROW EXECUTE FUNCTION world_reject_authored_source_history_mutation();

CREATE TRIGGER trg_world_authored_source_intake_no_truncate
    BEFORE TRUNCATE ON world_authored_source_intake
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_authored_source_history_mutation();
-- [jooq ignore stop]
