-- [jooq ignore start]
CREATE OR REPLACE FUNCTION world_claim_legacy_numeric_tenant_key()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    claimed_key BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF EXISTS (
            SELECT 1
            FROM world_authored_source_tenant_key_reservation
            WHERE tenant_key = OLD.tenant_id
                AND claim_kind = 'CANONICAL_AUTHORED_SOURCE'
        ) THEN
            RAISE EXCEPTION
                'World tenant key % is reserved for a canonical authored source', OLD.tenant_id
                USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE' AND EXISTS (
        SELECT 1
        FROM world_authored_source_tenant_key_reservation
        WHERE tenant_key = OLD.tenant_id
            AND claim_kind = 'CANONICAL_AUTHORED_SOURCE'
    ) THEN
        RAISE EXCEPTION
            'World tenant key % is reserved for a canonical authored source', OLD.tenant_id
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM world_authored_source_tenant_key_reservation
        WHERE tenant_key = NEW.tenant_id
            AND claim_kind = 'CANONICAL_AUTHORED_SOURCE'
    ) THEN
        RAISE EXCEPTION
            'World tenant key % is reserved for a canonical authored source', NEW.tenant_id
            USING ERRCODE = '23514';
    END IF;

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

CREATE FUNCTION world_reject_tenant_table_truncate()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'World tenant-bearing tables require owner-qualified row mutation; TRUNCATE is forbidden'
        USING ERRCODE = '23514';
END;
$$;

DROP TRIGGER trg_reserve_generation_rule_tenant_key ON generation_rule;
CREATE TRIGGER trg_reserve_generation_rule_tenant_key
    BEFORE INSERT OR UPDATE OR DELETE ON generation_rule
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();

DROP TRIGGER trg_reserve_instance_tenant_key ON instance;
CREATE TRIGGER trg_reserve_instance_tenant_key
    BEFORE INSERT OR UPDATE OR DELETE ON instance
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();

DROP TRIGGER trg_reserve_region_tenant_key ON region;
CREATE TRIGGER trg_reserve_region_tenant_key
    BEFORE INSERT OR UPDATE OR DELETE ON region
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();

DROP TRIGGER trg_reserve_region_instance_tenant_key ON region_instance;
CREATE TRIGGER trg_reserve_region_instance_tenant_key
    BEFORE INSERT OR UPDATE OR DELETE ON region_instance
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();

DROP TRIGGER trg_reserve_room_tenant_key ON room;
CREATE TRIGGER trg_reserve_room_tenant_key
    BEFORE INSERT OR UPDATE OR DELETE ON room
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();

DROP TRIGGER trg_reserve_room_exit_tenant_key ON room_exit;
CREATE TRIGGER trg_reserve_room_exit_tenant_key
    BEFORE INSERT OR UPDATE OR DELETE ON room_exit
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();

DROP TRIGGER trg_reserve_room_instance_tenant_key ON room_instance;
CREATE TRIGGER trg_reserve_room_instance_tenant_key
    BEFORE INSERT OR UPDATE OR DELETE ON room_instance
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();

DROP TRIGGER trg_reserve_room_instance_exit_tenant_key ON room_instance_exit;
CREATE TRIGGER trg_reserve_room_instance_exit_tenant_key
    BEFORE INSERT OR UPDATE OR DELETE ON room_instance_exit
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();

DROP TRIGGER trg_reserve_world_design_aggregate_epoch_tenant_key
    ON world_design_aggregate_epoch;
CREATE TRIGGER trg_reserve_world_design_aggregate_epoch_tenant_key
    BEFORE INSERT OR UPDATE OR DELETE ON world_design_aggregate_epoch
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();

DROP TRIGGER trg_reserve_world_design_revision_ledger_tenant_key
    ON world_design_revision_ledger;
CREATE TRIGGER trg_reserve_world_design_revision_ledger_tenant_key
    BEFORE INSERT OR UPDATE OR DELETE ON world_design_revision_ledger
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();

DROP TRIGGER trg_reserve_world_design_scope_epoch_tenant_key
    ON world_design_scope_epoch;
CREATE TRIGGER trg_reserve_world_design_scope_epoch_tenant_key
    BEFORE INSERT OR UPDATE OR DELETE ON world_design_scope_epoch
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();

DROP TRIGGER trg_reserve_world_entity_spawn_binding_tenant_key
    ON world_entity_spawn_binding;
CREATE TRIGGER trg_reserve_world_entity_spawn_binding_tenant_key
    BEFORE INSERT OR UPDATE OR DELETE ON world_entity_spawn_binding
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();

DROP TRIGGER trg_reserve_world_event_tenant_key ON world_event;
CREATE TRIGGER trg_reserve_world_event_tenant_key
    BEFORE INSERT OR UPDATE OR DELETE ON world_event
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();

DROP TRIGGER trg_reserve_world_instance_tenant_key ON world_instance;
CREATE TRIGGER trg_reserve_world_instance_tenant_key
    BEFORE INSERT OR UPDATE OR DELETE ON world_instance
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();

DROP TRIGGER trg_reserve_zone_tenant_key ON zone;
CREATE TRIGGER trg_reserve_zone_tenant_key
    BEFORE INSERT OR UPDATE OR DELETE ON zone
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();

DROP TRIGGER trg_reserve_zone_instance_tenant_key ON zone_instance;
CREATE TRIGGER trg_reserve_zone_instance_tenant_key
    BEFORE INSERT OR UPDATE OR DELETE ON zone_instance
    FOR EACH ROW EXECUTE FUNCTION world_claim_legacy_numeric_tenant_key();

CREATE TRIGGER trg_generation_rule_no_truncate
    BEFORE TRUNCATE ON generation_rule
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_tenant_table_truncate();

CREATE TRIGGER trg_instance_no_truncate
    BEFORE TRUNCATE ON instance
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_tenant_table_truncate();

CREATE TRIGGER trg_region_no_truncate
    BEFORE TRUNCATE ON region
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_tenant_table_truncate();

CREATE TRIGGER trg_region_instance_no_truncate
    BEFORE TRUNCATE ON region_instance
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_tenant_table_truncate();

CREATE TRIGGER trg_room_no_truncate
    BEFORE TRUNCATE ON room
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_tenant_table_truncate();

CREATE TRIGGER trg_room_exit_no_truncate
    BEFORE TRUNCATE ON room_exit
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_tenant_table_truncate();

CREATE TRIGGER trg_room_instance_no_truncate
    BEFORE TRUNCATE ON room_instance
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_tenant_table_truncate();

CREATE TRIGGER trg_room_instance_exit_no_truncate
    BEFORE TRUNCATE ON room_instance_exit
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_tenant_table_truncate();

CREATE TRIGGER trg_world_design_aggregate_epoch_no_truncate
    BEFORE TRUNCATE ON world_design_aggregate_epoch
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_tenant_table_truncate();

CREATE TRIGGER trg_world_design_revision_ledger_no_truncate
    BEFORE TRUNCATE ON world_design_revision_ledger
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_tenant_table_truncate();

CREATE TRIGGER trg_world_design_scope_epoch_no_truncate
    BEFORE TRUNCATE ON world_design_scope_epoch
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_tenant_table_truncate();

CREATE TRIGGER trg_world_entity_spawn_binding_no_truncate
    BEFORE TRUNCATE ON world_entity_spawn_binding
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_tenant_table_truncate();

CREATE TRIGGER trg_world_event_no_truncate
    BEFORE TRUNCATE ON world_event
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_tenant_table_truncate();

CREATE TRIGGER trg_world_instance_no_truncate
    BEFORE TRUNCATE ON world_instance
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_tenant_table_truncate();

CREATE TRIGGER trg_zone_no_truncate
    BEFORE TRUNCATE ON zone
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_tenant_table_truncate();

CREATE TRIGGER trg_zone_instance_no_truncate
    BEFORE TRUNCATE ON zone_instance
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_tenant_table_truncate();
-- [jooq ignore stop]
