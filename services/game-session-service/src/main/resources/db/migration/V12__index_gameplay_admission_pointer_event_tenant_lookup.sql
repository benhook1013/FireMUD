CREATE INDEX idx_gameplay_admission_pointer_event_tenant_world_realm_id
    ON gameplay_admission_pointer_event USING btree (tenant_id, world_slug, realm_slug, id DESC);
