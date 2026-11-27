-- Audit retained inventory once while excluding all usage/reservation writers. Never repair,
-- initialize or rewrite an existing owner row from an assumed baseline.
-- [jooq ignore start]
LOCK TABLE account_tenant_capacity_usage, account_tenant_capacity_reservations
    IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM account_tenant_capacity_usage usage
        FULL JOIN (
            SELECT tenant_uuid, owner_service,
                COALESCE(sum(capacity_delta) FILTER (WHERE state IN
                    ('RESERVED', 'QUARANTINED_REQUIRES_AUTHORITY_REMEDIATION')), 0) AS held_units,
                COALESCE(sum(capacity_delta) FILTER (WHERE state = 'FINALIZED'), 0) AS used_units
            FROM account_tenant_capacity_reservations
            GROUP BY tenant_uuid, owner_service
        ) inventory USING (tenant_uuid, owner_service)
        WHERE usage.tenant_uuid IS NULL
            OR usage.held_units IS DISTINCT FROM COALESCE(inventory.held_units, 0)
            OR usage.used_units IS DISTINCT FROM COALESCE(inventory.used_units, 0)
    ) THEN
        RAISE EXCEPTION 'Capacity conservation upgrade refuses inconsistent durable reservation inventory'
            USING ERRCODE = '23514';
    END IF;
END;
$$;
-- [jooq ignore stop]

CREATE TABLE account_tenant_capacity_conservation (
    tenant_uuid UUID NOT NULL,
    owner_service VARCHAR(40) NOT NULL,
    held_units BIGINT NOT NULL CHECK (held_units >= 0),
    used_units BIGINT NOT NULL CHECK (used_units >= 0),
    PRIMARY KEY (tenant_uuid, owner_service),
    FOREIGN KEY (tenant_uuid, owner_service)
        REFERENCES account_tenant_capacity_usage(tenant_uuid, owner_service) ON DELETE RESTRICT,
    CHECK (held_units <= 9223372036854775807 - used_units)
);

-- The lock and audit above make these exact retained values a proven inventory, not an
-- application assertion. Source data and Flyway history, including their xmin, stay untouched.
INSERT INTO account_tenant_capacity_conservation (tenant_uuid, owner_service, held_units, used_units)
    SELECT tenant_uuid, owner_service, held_units, used_units FROM account_tenant_capacity_usage;

-- [jooq ignore start]
CREATE FUNCTION account_tenant_capacity_conservation_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    -- Within the existing DML boundary only the usage-birth and reservation-delta triggers
    -- below write this table. Direct DML cannot supply trigger nesting. DDL/disabled triggers
    -- are not an alternate owner API. A bootstrap update nests birth at depth three.
    IF TG_OP IN ('DELETE', 'TRUNCATE') OR pg_trigger_depth() < 2 THEN
        RAISE EXCEPTION 'Capacity conservation is writable only by its nested owner triggers'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.held_units <> 0 OR NEW.used_units <> 0 OR NOT EXISTS (
            SELECT 1 FROM account_tenant_capacity_usage usage
            JOIN account_tenant_creation_bootstrap_operations bootstrap
                ON bootstrap.request_id = usage.bootstrap_request_id
                AND bootstrap.tenant_uuid = usage.tenant_uuid
            JOIN account_fresh_tenant_identity_associations birth
                ON birth.canonical_tenant_id = usage.tenant_uuid
            WHERE usage.tenant_uuid = NEW.tenant_uuid AND usage.owner_service = NEW.owner_service
                AND usage.held_units = 0 AND usage.used_units = 0 AND usage.usage_revision = 1
                AND bootstrap.status = 'COMMITTED' AND bootstrap.gameplay_admission_allowed IS FALSE
                AND bootstrap.baseline_event_sequence = 0
                AND bootstrap.creation_operation_id = birth.operation_id
                AND birth.evidence_digest = usage.creation_evidence_digest
                AND birth.capacity_birth_transaction = pg_current_xact_id()::TEXT) THEN
            RAISE EXCEPTION 'Capacity conservation birth requires exact same-transaction zero usage'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.tenant_uuid IS DISTINCT FROM OLD.tenant_uuid
        OR NEW.owner_service IS DISTINCT FROM OLD.owner_service
        OR NEW.used_units < OLD.used_units
        OR (NEW.held_units > OLD.held_units AND NEW.used_units <> OLD.used_units)
        OR (NEW.used_units > OLD.used_units
            AND OLD.held_units::NUMERIC - NEW.held_units <> NEW.used_units::NUMERIC - OLD.used_units) THEN
        RAISE EXCEPTION 'Capacity conservation permits only immutable-key reservation deltas'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_tenant_capacity_conservation_owner
    BEFORE INSERT OR UPDATE OR DELETE ON account_tenant_capacity_conservation
    FOR EACH ROW EXECUTE FUNCTION account_tenant_capacity_conservation_guard();
CREATE TRIGGER account_tenant_capacity_conservation_no_truncate
    BEFORE TRUNCATE ON account_tenant_capacity_conservation
    FOR EACH STATEMENT EXECUTE FUNCTION account_tenant_capacity_conservation_guard();

CREATE FUNCTION account_tenant_capacity_conservation_birth() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    -- The existing BEFORE INSERT usage guard supplies the exact fresh-tenant authority.
    INSERT INTO account_tenant_capacity_conservation (tenant_uuid, owner_service, held_units, used_units)
        VALUES (NEW.tenant_uuid, NEW.owner_service, 0, 0);
    RETURN NULL;
END;
$$;
CREATE TRIGGER account_tenant_capacity_usage_conservation_birth
    AFTER INSERT ON account_tenant_capacity_usage
    FOR EACH ROW EXECUTE FUNCTION account_tenant_capacity_conservation_birth();

CREATE FUNCTION account_tenant_capacity_conservation_delta() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE held_delta BIGINT; used_delta BIGINT;
BEGIN
    -- Existing BEFORE reservation guards retain sole transition/identity/fence authority.
    held_delta := CASE WHEN NEW.state IN ('RESERVED', 'QUARANTINED_REQUIRES_AUTHORITY_REMEDIATION')
        THEN NEW.capacity_delta ELSE 0 END;
    used_delta := CASE WHEN NEW.state = 'FINALIZED' THEN NEW.capacity_delta ELSE 0 END;
    IF TG_OP = 'UPDATE' THEN
        held_delta := held_delta - CASE WHEN OLD.state IN
            ('RESERVED', 'QUARANTINED_REQUIRES_AUTHORITY_REMEDIATION') THEN OLD.capacity_delta ELSE 0 END;
        used_delta := used_delta - CASE WHEN OLD.state = 'FINALIZED' THEN OLD.capacity_delta ELSE 0 END;
    END IF;

    -- Serialize with usage writes before touching conservation. The usage identity is immutable,
    -- so NO KEY UPDATE suffices and avoids lock-upgrade deadlocks with reservation FK KEY SHARE.
    -- No historical reservation is visited.
    PERFORM 1 FROM account_tenant_capacity_usage
        WHERE tenant_uuid = NEW.tenant_uuid AND owner_service = NEW.owner_service FOR NO KEY UPDATE;
    IF held_delta <> 0 OR used_delta <> 0 THEN
        UPDATE account_tenant_capacity_conservation
            SET held_units = held_units + held_delta, used_units = used_units + used_delta
            WHERE tenant_uuid = NEW.tenant_uuid AND owner_service = NEW.owner_service;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Capacity reservation requires its protected conservation baseline'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER account_tenant_capacity_reservation_conservation_insert
    AFTER INSERT ON account_tenant_capacity_reservations
    FOR EACH ROW EXECUTE FUNCTION account_tenant_capacity_conservation_delta();
CREATE TRIGGER account_tenant_capacity_reservation_conservation_update
    AFTER UPDATE ON account_tenant_capacity_reservations
    FOR EACH ROW WHEN (OLD.state IS DISTINCT FROM NEW.state)
    EXECUTE FUNCTION account_tenant_capacity_conservation_delta();

-- Preserve the existing deferred constraint triggers and their state-change-only scheduling.
-- Each invocation now uses two primary-key point lookups, independent of FINALIZED history.
CREATE OR REPLACE FUNCTION account_tenant_capacity_exact_usage() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE actual account_tenant_capacity_usage%ROWTYPE;
    conserved account_tenant_capacity_conservation%ROWTYPE;
BEGIN
    SELECT * INTO actual FROM account_tenant_capacity_usage
        WHERE tenant_uuid = NEW.tenant_uuid AND owner_service = NEW.owner_service FOR NO KEY UPDATE;
    SELECT * INTO conserved FROM account_tenant_capacity_conservation
        WHERE tenant_uuid = NEW.tenant_uuid AND owner_service = NEW.owner_service;
    IF actual.tenant_uuid IS NULL OR conserved.tenant_uuid IS NULL
        OR actual.held_units IS DISTINCT FROM conserved.held_units
        OR actual.used_units IS DISTINCT FROM conserved.used_units THEN
        RAISE EXCEPTION 'Capacity usage differs from its protected reservation conservation'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
-- [jooq ignore stop]

COMMENT ON TABLE account_tenant_capacity_conservation IS
    'Audited once, then trigger-maintained exact held/used conservation; no retirement or deletion authority.';
