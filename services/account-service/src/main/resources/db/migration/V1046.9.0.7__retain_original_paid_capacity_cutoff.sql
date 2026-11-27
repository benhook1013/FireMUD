-- The existing evaluation guard makes this column immutable with the canonical evidence.
-- Earlier evaluations keep NULL and cannot authorize fresh paid capacity mutations.
ALTER TABLE account_tenant_capacity_evaluations
    ADD COLUMN original_paid_cutoff TIMESTAMPTZ,
    ADD CONSTRAINT account_tenant_capacity_original_paid_cutoff_check
        CHECK (original_paid_cutoff >= expires_at);
