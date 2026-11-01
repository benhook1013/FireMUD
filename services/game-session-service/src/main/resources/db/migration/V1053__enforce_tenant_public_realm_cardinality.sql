CREATE UNIQUE INDEX uq_gameplay_admission_pointer_tenant_visible_public_production
    ON gameplay_admission_pointer (tenant_id)
    WHERE visible = true AND public_production_realm = true;
