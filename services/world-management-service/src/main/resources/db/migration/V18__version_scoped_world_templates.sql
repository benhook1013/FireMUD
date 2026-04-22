ALTER TABLE region
    ADD COLUMN version_id BIGINT NOT NULL DEFAULT 1;

ALTER TABLE zone
    ADD COLUMN version_id BIGINT NOT NULL DEFAULT 1;

ALTER TABLE room
    ADD COLUMN version_id BIGINT NOT NULL DEFAULT 1;

ALTER TABLE room_exit
    ADD COLUMN version_id BIGINT NOT NULL DEFAULT 1;

ALTER TABLE generation_rule
    ADD COLUMN version_id BIGINT NOT NULL DEFAULT 1;

CREATE INDEX idx_region_tenant_version_id ON region (tenant_id, version_id, id);
CREATE INDEX idx_zone_tenant_version_id ON zone (tenant_id, version_id, id);
CREATE INDEX idx_room_tenant_version_id ON room (tenant_id, version_id, id);
CREATE INDEX idx_room_exit_tenant_version_id ON room_exit (tenant_id, version_id, id);
CREATE INDEX idx_generation_rule_tenant_version_id ON generation_rule (tenant_id, version_id, id);
