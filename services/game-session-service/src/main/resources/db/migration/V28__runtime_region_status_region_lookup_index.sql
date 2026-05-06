CREATE UNIQUE INDEX idx_runtime_region_status_tenant_region
    ON runtime_region_status (tenant_id, region_id);
