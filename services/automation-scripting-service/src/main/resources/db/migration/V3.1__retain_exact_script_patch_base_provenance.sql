-- Exact script-patch base provenance is owner data, not a value inferred from the
-- publication request or a later mutable Game Session pin read. Retained rows stay
-- nullable because their original base cannot be reconstructed safely.
ALTER TABLE scripts
    ADD COLUMN base_version_id BIGINT;

ALTER TABLE script_event_bindings
    ADD COLUMN base_version_id BIGINT;

ALTER TABLE script_schedule_definitions
    ADD COLUMN base_version_id BIGINT;

ALTER TABLE script_patch_readiness_projections
    ADD COLUMN base_version_id BIGINT;

ALTER TABLE script_work_items
    ADD COLUMN script_patch_base_version_id BIGINT;

ALTER TABLE script_event_ingress_audit
    ADD COLUMN script_patch_base_version_id BIGINT;

ALTER TABLE script_event_audit
    ADD COLUMN script_patch_base_version_id BIGINT;

ALTER TABLE script_schedule_instances
    ADD COLUMN script_patch_base_version_id BIGINT;

ALTER TABLE script_patch_pin_projections
    ADD COLUMN pinned_script_patch_base_version_id BIGINT;

-- New authored patches bind once to one exact base. No historical binding is
-- synthesized by this migration because the prior schema retained patch ids only.
CREATE TABLE script_patch_base_bindings (
    tenant_id VARCHAR(64) NOT NULL,
    script_patch_version VARCHAR(128) NOT NULL,
    base_version_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT pk_script_patch_base_bindings
        PRIMARY KEY (tenant_id, script_patch_version),
    CONSTRAINT ck_script_patch_base_bindings_positive_base
        CHECK (base_version_id > 0)
);

/* [jooq ignore start] */
ALTER TABLE scripts
    ADD CONSTRAINT ck_scripts_positive_base_version
        CHECK (base_version_id IS NULL OR base_version_id > 0)
        NOT VALID;
/* [jooq ignore stop] */

/* [jooq ignore start] */
ALTER TABLE script_event_bindings
    ADD CONSTRAINT ck_script_event_bindings_positive_base_version
        CHECK (base_version_id IS NULL OR base_version_id > 0)
        NOT VALID;
/* [jooq ignore stop] */

/* [jooq ignore start] */
ALTER TABLE script_schedule_definitions
    ADD CONSTRAINT ck_script_schedule_definitions_positive_base_version
        CHECK (base_version_id IS NULL OR base_version_id > 0)
        NOT VALID;
/* [jooq ignore stop] */

/* [jooq ignore start] */
ALTER TABLE script_patch_readiness_projections
    ADD CONSTRAINT ck_script_patch_readiness_positive_base_version
        CHECK (base_version_id IS NULL OR base_version_id > 0)
        NOT VALID;
/* [jooq ignore stop] */

/* [jooq ignore start] */
ALTER TABLE script_work_items
    ADD CONSTRAINT ck_script_work_items_positive_patch_base
        CHECK (script_patch_base_version_id IS NULL OR script_patch_base_version_id > 0)
        NOT VALID;
/* [jooq ignore stop] */

/* [jooq ignore start] */
ALTER TABLE script_event_ingress_audit
    ADD CONSTRAINT ck_script_event_ingress_positive_patch_base
        CHECK (script_patch_base_version_id IS NULL OR script_patch_base_version_id > 0)
        NOT VALID;
/* [jooq ignore stop] */

/* [jooq ignore start] */
ALTER TABLE script_event_audit
    ADD CONSTRAINT ck_script_event_audit_positive_patch_base
        CHECK (script_patch_base_version_id IS NULL OR script_patch_base_version_id > 0)
        NOT VALID;
/* [jooq ignore stop] */

/* [jooq ignore start] */
ALTER TABLE script_schedule_instances
    ADD CONSTRAINT ck_script_schedule_instances_positive_patch_base
        CHECK (script_patch_base_version_id IS NULL OR script_patch_base_version_id > 0)
        NOT VALID;
/* [jooq ignore stop] */

/* [jooq ignore start] */
ALTER TABLE script_patch_pin_projections
    ADD CONSTRAINT ck_script_patch_pin_projections_positive_patch_base
        CHECK (pinned_script_patch_base_version_id IS NULL
            OR pinned_script_patch_base_version_id > 0)
        NOT VALID;
/* [jooq ignore stop] */
