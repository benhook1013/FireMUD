-- PRIVATE RECEIVING DDL ONLY. Not in Flyway discovery. No migration version or activation.
CREATE TABLE account_capacity_origin_decisions (
  tenant_uuid uuid NOT NULL,
  request_id uuid NOT NULL,
  admission_id uuid NOT NULL,
  capacity_rearm_fence bigint NOT NULL CHECK (capacity_rearm_fence > 0),
  admission_attempt_id uuid NOT NULL,
  mutation_digest text NOT NULL CHECK (mutation_digest ~ '^[0-9a-f]{64}$'),
  canonical_payload bytea NOT NULL CHECK (octet_length(canonical_payload) BETWEEN 1 AND 524288),
  decision text NOT NULL CHECK (decision IN ('ACCEPTED', 'NO_ACCEPT')),
  decision_id uuid NOT NULL UNIQUE CHECK (decision_id <> '00000000-0000-0000-0000-000000000000'),
  PRIMARY KEY (tenant_uuid, request_id, admission_id, capacity_rearm_fence),
  CHECK (decision <> 'NO_ACCEPT' OR capacity_rearm_fence = 1)
);

CREATE FUNCTION account_capacity_origin_decision_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Account capacity origin decisions are immutable';
END
$$;
CREATE TRIGGER account_capacity_origin_decision_immutable
BEFORE UPDATE OR DELETE ON account_capacity_origin_decisions
FOR EACH ROW EXECUTE FUNCTION account_capacity_origin_decision_immutable();
CREATE TRIGGER account_capacity_origin_decision_no_truncate
BEFORE TRUNCATE ON account_capacity_origin_decisions
FOR EACH STATEMENT EXECUTE FUNCTION account_capacity_origin_decision_immutable();

CREATE FUNCTION account_capacity_origin_reservation_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  -- Existing reservation recovery does not need invented historical origin evidence.
  IF TG_OP = 'UPDATE' AND NEW.tenant_uuid = OLD.tenant_uuid
      AND NEW.request_id = OLD.request_id AND NEW.admission_id = OLD.admission_id
      AND NEW.mutation_digest = OLD.mutation_digest
      AND NEW.admission_attempt_id = OLD.admission_attempt_id
      AND NEW.capacity_rearm_fence = OLD.capacity_rearm_fence
      AND NEW.canonical_payload = OLD.canonical_payload THEN
    RETURN NEW;
  END IF;
  IF EXISTS (
      SELECT 1 FROM account_capacity_origin_decisions d
      WHERE d.tenant_uuid = NEW.tenant_uuid AND d.request_id = NEW.request_id
        AND d.admission_id = NEW.admission_id AND d.decision = 'NO_ACCEPT') THEN
    RAISE EXCEPTION 'Retained Account refusal excludes reservation creation or rearm';
  END IF;
  IF NOT EXISTS (
      SELECT 1 FROM account_capacity_origin_decisions d
      WHERE d.tenant_uuid = NEW.tenant_uuid AND d.request_id = NEW.request_id
        AND d.admission_id = NEW.admission_id
        AND d.capacity_rearm_fence = NEW.capacity_rearm_fence
        AND d.admission_attempt_id = NEW.admission_attempt_id
        AND d.mutation_digest = NEW.mutation_digest
        AND d.canonical_payload = NEW.canonical_payload AND d.decision = 'ACCEPTED') THEN
    RAISE EXCEPTION 'Exact Account ACCEPTED origin decision required';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER account_capacity_origin_reservation_guard
BEFORE INSERT OR UPDATE ON account_tenant_capacity_reservations
FOR EACH ROW EXECUTE FUNCTION account_capacity_origin_reservation_guard();

CREATE FUNCTION account_capacity_origin_decision_completion_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.decision = 'ACCEPTED' AND NOT EXISTS (
      SELECT 1 FROM account_tenant_capacity_reservations r
      WHERE r.tenant_uuid = NEW.tenant_uuid AND r.request_id = NEW.request_id
        AND r.admission_id = NEW.admission_id
        AND r.capacity_rearm_fence = NEW.capacity_rearm_fence
        AND r.admission_attempt_id = NEW.admission_attempt_id
        AND r.mutation_digest = NEW.mutation_digest
        AND r.canonical_payload = NEW.canonical_payload) THEN
    RAISE EXCEPTION 'ACCEPTED origin decision must commit with exact reservation';
  END IF;
  IF NEW.decision = 'NO_ACCEPT' AND EXISTS (
      SELECT 1 FROM account_tenant_capacity_reservations r
      WHERE r.tenant_uuid = NEW.tenant_uuid AND r.request_id = NEW.request_id
        AND r.admission_id = NEW.admission_id) THEN
    RAISE EXCEPTION 'NO_ACCEPT cannot contradict retained reservation';
  END IF;
  RETURN NULL;
END
$$;
CREATE CONSTRAINT TRIGGER account_capacity_origin_decision_completion_guard
AFTER INSERT ON account_capacity_origin_decisions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION account_capacity_origin_decision_completion_guard();
