-- Immediate row-level conservation checks must observe their reservation deltas.
-- PostgreSQL fires same-event AFTER ROW triggers by name. Preserve the existing
-- named constraints and their default deferral so ordinary paired writes are unchanged.
-- [jooq ignore start]
ALTER TRIGGER account_tenant_capacity_reservation_conservation_insert
    ON account_tenant_capacity_reservations
    RENAME TO account_tenant_capacity_reservation_aggregate_insert;
ALTER TRIGGER account_tenant_capacity_reservation_conservation_update
    ON account_tenant_capacity_reservations
    RENAME TO account_tenant_capacity_reservation_aggregate_update;
-- [jooq ignore stop]
