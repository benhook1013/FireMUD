-- Explicit recovery of an uninstalled initial candidate only. Original intents and physical work
-- remain retained; strictly newer layout versions separate the old and new Redis key domains.
ALTER TABLE game_session_canonical_issuer_layout_bootstrap_intent
    ADD CONSTRAINT uq_gs_issuer_bootstrap_supersession_link UNIQUE (
        operation_id, issuer_id, inventory_snapshot_revision, operation_fence, coverage_fence);

CREATE TABLE game_session_canonical_issuer_bootstrap_supersession (
    issuer_id character varying(64) NOT NULL,
    old_operation_id uuid NOT NULL,
    new_operation_id uuid NOT NULL,
    old_inventory_revision numeric NOT NULL,
    new_inventory_revision numeric NOT NULL,
    old_operation_fence numeric NOT NULL,
    old_coverage_fence numeric NOT NULL,
    previous_last_issued_fence numeric NOT NULL,
    new_operation_fence numeric NOT NULL,
    new_coverage_fence numeric NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_issuer_bootstrap_supersession PRIMARY KEY (old_operation_id),
    CONSTRAINT uq_gs_issuer_bootstrap_supersession_new UNIQUE (new_operation_id),
    CONSTRAINT fk_gs_issuer_bootstrap_supersession_old FOREIGN KEY (
        old_operation_id, issuer_id, old_inventory_revision, old_operation_fence, old_coverage_fence)
        REFERENCES game_session_canonical_issuer_layout_bootstrap_intent (
            operation_id, issuer_id, inventory_snapshot_revision, operation_fence, coverage_fence),
    CONSTRAINT fk_gs_issuer_bootstrap_supersession_new FOREIGN KEY (
        new_operation_id, issuer_id, new_inventory_revision, new_operation_fence, new_coverage_fence)
        REFERENCES game_session_canonical_issuer_layout_bootstrap_intent (
            operation_id, issuer_id, inventory_snapshot_revision, operation_fence, coverage_fence),
    CONSTRAINT chk_gs_issuer_bootstrap_supersession CHECK (
        old_operation_id <> new_operation_id
        AND new_inventory_revision > old_inventory_revision
        AND previous_last_issued_fence >= old_operation_fence
        AND previous_last_issued_fence >= old_coverage_fence
        AND new_operation_fence = previous_last_issued_fence + 1
        AND new_coverage_fence = new_operation_fence + 1)
);

-- [jooq ignore start]
CREATE FUNCTION game_session_guard_issuer_bootstrap_supersession()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    current_revision numeric;
    current_control game_session_canonical_issuer_layout_bootstrap_control%ROWTYPE;
    original game_session_canonical_issuer_layout_bootstrap_intent%ROWTYPE;
    replacement game_session_canonical_issuer_layout_bootstrap_intent%ROWTYPE;
BEGIN
    -- Match the owner's inventory -> issuer control -> intent lock order.
    SELECT inventory_revision INTO current_revision
        FROM game_session_canonical_binding_inventory_clock WHERE singleton_id = 1 FOR UPDATE;
    SELECT * INTO current_control FROM game_session_canonical_issuer_layout_bootstrap_control
        WHERE issuer_id = NEW.issuer_id FOR UPDATE;
    SELECT * INTO original FROM game_session_canonical_issuer_layout_bootstrap_intent
        WHERE operation_id = NEW.old_operation_id FOR UPDATE;
    SELECT * INTO replacement FROM game_session_canonical_issuer_layout_bootstrap_intent
        WHERE operation_id = NEW.new_operation_id FOR UPDATE;
    IF current_control.current_operation_id IS DISTINCT FROM NEW.old_operation_id
        OR current_control.state IS DISTINCT FROM 'INITIAL_BOOTSTRAP_INTENT'
        OR current_control.control_epoch IS DISTINCT FROM NEW.old_operation_fence
        OR current_control.current_operation_fence IS DISTINCT FROM NEW.old_operation_fence
        OR current_control.current_coverage_fence IS DISTINCT FROM NEW.old_coverage_fence
        OR current_control.last_issued_fence IS DISTINCT FROM NEW.previous_last_issued_fence
        OR current_revision IS DISTINCT FROM NEW.new_inventory_revision
        OR original.issuer_id IS DISTINCT FROM NEW.issuer_id
        OR replacement.issuer_id IS DISTINCT FROM NEW.issuer_id
        OR original.inventory_snapshot_revision IS DISTINCT FROM NEW.old_inventory_revision
        OR replacement.inventory_snapshot_revision IS DISTINCT FROM NEW.new_inventory_revision
        OR original.operation_fence IS DISTINCT FROM NEW.old_operation_fence
        OR original.coverage_fence IS DISTINCT FROM NEW.old_coverage_fence
        OR replacement.operation_fence IS DISTINCT FROM NEW.new_operation_fence
        OR replacement.coverage_fence IS DISTINCT FROM NEW.new_coverage_fence
        OR replacement.layout_version <= original.layout_version
        OR replacement.request_id = original.request_id
        OR replacement.request_id = original.operation_id
        OR EXISTS (SELECT 1 FROM game_session_canonical_issuer_installed_anchor
            WHERE issuer_id = NEW.issuer_id) THEN
        RAISE EXCEPTION 'initial issuer bootstrap supersession requires exact stale uninstalled lineage'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_gs_issuer_bootstrap_supersession_guard
    BEFORE INSERT ON game_session_canonical_issuer_bootstrap_supersession
    FOR EACH ROW EXECUTE FUNCTION game_session_guard_issuer_bootstrap_supersession();
CREATE TRIGGER trg_gs_issuer_bootstrap_supersession_immutable
    BEFORE UPDATE OR DELETE ON game_session_canonical_issuer_bootstrap_supersession
    FOR EACH ROW EXECUTE FUNCTION game_session_reject_issuer_bootstrap_coverage_change();
CREATE TRIGGER trg_gs_issuer_bootstrap_supersession_no_truncate
    BEFORE TRUNCATE ON game_session_canonical_issuer_bootstrap_supersession
    FOR EACH STATEMENT EXECUTE FUNCTION game_session_reject_issuer_bootstrap_coverage_change();

CREATE FUNCTION game_session_require_issuer_bootstrap_supersession_control()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM game_session_canonical_issuer_layout_bootstrap_control control
        WHERE control.issuer_id = NEW.issuer_id
            AND control.state = 'INITIAL_BOOTSTRAP_INTENT'
            AND control.current_operation_id = NEW.new_operation_id
            AND control.control_epoch = NEW.new_operation_fence
            AND control.current_operation_fence = NEW.new_operation_fence
            AND control.current_coverage_fence = NEW.new_coverage_fence
            AND control.last_issued_fence = NEW.new_coverage_fence) THEN
        RAISE EXCEPTION 'initial issuer supersession must move exact current control in the same transaction'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_gs_issuer_bootstrap_supersession_control
    AFTER INSERT ON game_session_canonical_issuer_bootstrap_supersession
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION game_session_require_issuer_bootstrap_supersession_control();

CREATE FUNCTION game_session_guard_issuer_bootstrap_control_supersession()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.current_operation_id IS DISTINCT FROM OLD.current_operation_id THEN
        IF NEW.issuer_id IS DISTINCT FROM OLD.issuer_id
            OR NEW.state IS DISTINCT FROM 'INITIAL_BOOTSTRAP_INTENT'
            OR OLD.state IS DISTINCT FROM 'INITIAL_BOOTSTRAP_INTENT'
            OR NEW.control_epoch IS DISTINCT FROM NEW.current_operation_fence
            OR EXISTS (SELECT 1 FROM game_session_canonical_issuer_installed_anchor
                WHERE issuer_id = OLD.issuer_id)
            OR NOT EXISTS (
                SELECT 1 FROM game_session_canonical_issuer_bootstrap_supersession relation
                WHERE relation.issuer_id = OLD.issuer_id
                    AND relation.old_operation_id = OLD.current_operation_id
                    AND relation.new_operation_id = NEW.current_operation_id
                    AND relation.old_operation_fence = OLD.control_epoch
                    AND relation.old_operation_fence = OLD.current_operation_fence
                    AND relation.old_coverage_fence = OLD.current_coverage_fence
                    AND relation.previous_last_issued_fence = OLD.last_issued_fence
                    AND relation.new_operation_fence = NEW.current_operation_fence
                    AND relation.new_coverage_fence = NEW.current_coverage_fence
                    AND relation.new_coverage_fence = NEW.last_issued_fence) THEN
            RAISE EXCEPTION 'initial issuer control change requires exact retained supersession lineage'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_gs_issuer_bootstrap_control_supersession
    BEFORE UPDATE ON game_session_canonical_issuer_layout_bootstrap_control
    FOR EACH ROW EXECUTE FUNCTION game_session_guard_issuer_bootstrap_control_supersession();
-- [jooq ignore stop]
