-- Recheck complete reservation inventory only when the reservation state changes.
-- Metadata-only claims, recovery observations, and unchanged-state rearm bookkeeping do not
-- alter held or used capacity and must not scan the lifetime reservation inventory.
-- [jooq ignore start]
DROP TRIGGER account_tenant_capacity_reservation_complete
    ON account_tenant_capacity_reservations;

CREATE CONSTRAINT TRIGGER account_tenant_capacity_reservation_complete_insert
    AFTER INSERT ON account_tenant_capacity_reservations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_tenant_capacity_exact_usage();

CREATE CONSTRAINT TRIGGER account_tenant_capacity_reservation_complete_update
    AFTER UPDATE ON account_tenant_capacity_reservations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (OLD.state IS DISTINCT FROM NEW.state)
    EXECUTE FUNCTION account_tenant_capacity_exact_usage();
-- [jooq ignore stop]
