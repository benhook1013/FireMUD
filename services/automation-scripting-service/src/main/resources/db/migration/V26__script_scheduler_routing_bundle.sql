ALTER TABLE script_schedule_instances
    ADD COLUMN world_slug VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN realm_slug VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN pointer_version VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE script_patch_pin_projections
    ADD COLUMN world_slug VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN realm_slug VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN pointer_version VARCHAR(64) NOT NULL DEFAULT '';
