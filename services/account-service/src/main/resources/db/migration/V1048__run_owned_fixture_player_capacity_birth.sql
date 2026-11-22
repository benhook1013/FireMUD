-- The run-owned smoke fixture is created by Game Design without a human creator Account.
-- Only the Account transaction importing that exact fresh fixture can establish player usage.
CREATE TABLE account_run_owned_fixture_capacity_birth (
    tenant_uuid UUID PRIMARY KEY
        REFERENCES account_fresh_tenant_identity_associations(canonical_tenant_id),
    fixture_operation_id UUID NOT NULL UNIQUE,
    run_id VARCHAR(128) NOT NULL CHECK (run_id ~ '^[a-z0-9][a-z0-9-]*$'),
    compose_project VARCHAR(128) NOT NULL UNIQUE,
    creation_evidence_digest VARCHAR(71) NOT NULL
        CHECK (creation_evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    fixture_policy_evidence_digest VARCHAR(71) NOT NULL
        CHECK (fixture_policy_evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    CHECK (compose_project = 'firemud-smoke-' || run_id
        OR (run_id ~ '^gha-[0-9a-f]{64}$' AND compose_project ~ '^smoke-full-[0-9]+-[0-9]+$'))
);

ALTER TABLE account_tenant_capacity_usage
    ALTER COLUMN bootstrap_request_id DROP NOT NULL;
ALTER TABLE account_tenant_capacity_usage
    ADD COLUMN fixture_birth_tenant_uuid UUID
        REFERENCES account_run_owned_fixture_capacity_birth(tenant_uuid);
ALTER TABLE account_tenant_capacity_usage
    ADD CONSTRAINT account_tenant_capacity_has_one_birth_source CHECK (
        (bootstrap_request_id IS NOT NULL AND fixture_birth_tenant_uuid IS NULL)
        OR (bootstrap_request_id IS NULL AND fixture_birth_tenant_uuid IS NOT NULL
            AND fixture_birth_tenant_uuid = tenant_uuid AND owner_service = 'game-session-service')
    );

-- [jooq ignore start]
CREATE FUNCTION account_tenant_capacity_has_current_birth(usage_row account_tenant_capacity_usage)
RETURNS BOOLEAN LANGUAGE SQL AS $$
    SELECT EXISTS (
        SELECT 1 FROM account_fresh_tenant_identity_associations birth
        WHERE birth.canonical_tenant_id = usage_row.tenant_uuid
          AND birth.evidence_digest = usage_row.creation_evidence_digest
          AND birth.capacity_birth_transaction = pg_current_xact_id()::TEXT
          AND (
              (usage_row.fixture_birth_tenant_uuid IS NULL AND EXISTS (
                  SELECT 1 FROM account_tenant_creation_bootstrap_operations bootstrap
                  WHERE bootstrap.request_id = usage_row.bootstrap_request_id
                    AND bootstrap.tenant_uuid = usage_row.tenant_uuid
                    AND bootstrap.status = 'COMMITTED'
                    AND bootstrap.gameplay_admission_allowed IS FALSE
                    AND bootstrap.baseline_event_sequence = 0
                    AND bootstrap.creation_operation_id = birth.operation_id
              ))
              OR (usage_row.bootstrap_request_id IS NULL
                  AND usage_row.fixture_birth_tenant_uuid = birth.canonical_tenant_id
                  AND usage_row.owner_service = 'game-session-service'
                  AND birth.target_namespace = 'dev'
                  AND EXISTS (
                      SELECT 1 FROM account_run_owned_fixture_capacity_birth fixture
                      WHERE fixture.tenant_uuid = birth.canonical_tenant_id
                        AND fixture.fixture_operation_id = birth.creation_request_id
                        AND fixture.creation_evidence_digest = birth.evidence_digest
                  ))
          )
    );
$$;

CREATE OR REPLACE FUNCTION account_tenant_capacity_usage_insert_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.held_units <> 0 OR NEW.used_units <> 0 OR NEW.usage_revision <> 1
        OR NOT account_tenant_capacity_has_current_birth(NEW) THEN
        RAISE EXCEPTION 'Capacity usage initialization requires same-transaction new-tenant birth'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION account_tenant_capacity_usage_update_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.tenant_uuid IS DISTINCT FROM OLD.tenant_uuid OR NEW.owner_service IS DISTINCT FROM OLD.owner_service
        OR NEW.bootstrap_request_id IS DISTINCT FROM OLD.bootstrap_request_id
        OR NEW.fixture_birth_tenant_uuid IS DISTINCT FROM OLD.fixture_birth_tenant_uuid
        OR NEW.creation_evidence_digest IS DISTINCT FROM OLD.creation_evidence_digest
        OR NEW.usage_revision <> OLD.usage_revision + 1 THEN
        RAISE EXCEPTION 'Capacity usage identity is immutable and revision must advance once' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION account_tenant_capacity_conservation_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') OR pg_trigger_depth() < 2 THEN
        RAISE EXCEPTION 'Capacity conservation is writable only by its nested owner triggers'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.held_units <> 0 OR NEW.used_units <> 0 OR NOT EXISTS (
            SELECT 1 FROM account_tenant_capacity_usage usage
            WHERE usage.tenant_uuid = NEW.tenant_uuid AND usage.owner_service = NEW.owner_service
                AND usage.held_units = 0 AND usage.used_units = 0 AND usage.usage_revision = 1
                AND account_tenant_capacity_has_current_birth(usage)) THEN
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

CREATE FUNCTION account_run_owned_fixture_capacity_birth_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Run-owned fixture capacity birth is immutable' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM account_tenant_membership
        WHERE tenant_uuid = NEW.tenant_uuid AND gameplay_admission_allowed IS TRUE) THEN
        RAISE EXCEPTION 'Run-owned fixture player usage is not demonstrably empty' USING ERRCODE = '23514';
    END IF;

    -- The existing usage and conservation triggers check the same-transaction owner source.
    -- The fixture does not establish a World Management capacity baseline.
    INSERT INTO account_tenant_capacity_usage
        (tenant_uuid, owner_service, fixture_birth_tenant_uuid, creation_evidence_digest,
         held_units, used_units, usage_revision)
        VALUES (NEW.tenant_uuid, 'game-session-service', NEW.tenant_uuid, NEW.creation_evidence_digest, 0, 0, 1);
    RETURN NULL;
END;
$$;
CREATE TRIGGER account_run_owned_fixture_capacity_birth_owner
    AFTER INSERT OR UPDATE OR DELETE ON account_run_owned_fixture_capacity_birth
    FOR EACH ROW EXECUTE FUNCTION account_run_owned_fixture_capacity_birth_guard();
CREATE TRIGGER account_run_owned_fixture_capacity_birth_no_truncate
    BEFORE TRUNCATE ON account_run_owned_fixture_capacity_birth
    FOR EACH STATEMENT EXECUTE FUNCTION account_run_owned_fixture_capacity_birth_guard();
-- [jooq ignore stop]
