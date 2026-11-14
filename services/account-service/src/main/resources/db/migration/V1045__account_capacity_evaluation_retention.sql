-- Read-only retention preparation. No policy is approved and no cleanup is enabled here.
-- Expiry is not retention authority; preserve all existing immutable records and guards.
CREATE INDEX account_capacity_evaluation_oldest_retention_idx
    ON account_tenant_capacity_evaluations (evaluated_at, evaluation_identity);

-- Rearm moves the exact evaluation FK. Conservatively preserve every evaluation in a retained
-- reservation scope, including prior evaluations named only by immutable historical payloads.
CREATE INDEX account_capacity_reservation_retention_scope_idx
    ON account_tenant_capacity_reservations (tenant_uuid, owner_service);
