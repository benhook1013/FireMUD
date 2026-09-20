-- Existing duplicate active tenant rows make this migration fail closed. Reconcile them under
-- owner authority; the migration neither chooses a winner nor rewrites retained readiness.
CREATE UNIQUE INDEX uq_script_patch_readiness_active_tenant
    ON script_patch_readiness_projections (tenant_id)
    WHERE readiness_status IN ('PENDING_VALIDATION', 'ONLOAD_RUNNING');
