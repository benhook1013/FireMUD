-- Admission-pointer rows are player-addressable realms. The partial index gives concurrent
-- producers an atomic at-most-one guard while excluding hidden and non-public realms.
-- Existing duplicates make index creation fail with PostgreSQL tenant-key evidence; no row is
-- rewritten or discarded.
CREATE UNIQUE INDEX uq_gameplay_admission_pointer_visible_public_tenant
    ON gameplay_admission_pointer (tenant_id)
    WHERE visible AND public_production_realm;
