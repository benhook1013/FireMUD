-- Validate retained-row provenance checks separately from V3.1's column and
-- constraint installation so the migration does not scan retained tables while
-- holding each ADD COLUMN transaction's ACCESS EXCLUSIVE lock. The NOT VALID
-- checks still enforce new and changed rows until this bounded validation pass
-- completes.
/* [jooq ignore start] */
ALTER TABLE scripts
    VALIDATE CONSTRAINT ck_scripts_positive_base_version;
/* [jooq ignore stop] */

/* [jooq ignore start] */
ALTER TABLE script_event_bindings
    VALIDATE CONSTRAINT ck_script_event_bindings_positive_base_version;
/* [jooq ignore stop] */

/* [jooq ignore start] */
ALTER TABLE script_schedule_definitions
    VALIDATE CONSTRAINT ck_script_schedule_definitions_positive_base_version;
/* [jooq ignore stop] */

/* [jooq ignore start] */
ALTER TABLE script_patch_readiness_projections
    VALIDATE CONSTRAINT ck_script_patch_readiness_positive_base_version;
/* [jooq ignore stop] */

/* [jooq ignore start] */
ALTER TABLE script_work_items
    VALIDATE CONSTRAINT ck_script_work_items_positive_patch_base;
/* [jooq ignore stop] */

/* [jooq ignore start] */
ALTER TABLE script_event_ingress_audit
    VALIDATE CONSTRAINT ck_script_event_ingress_positive_patch_base;
/* [jooq ignore stop] */

/* [jooq ignore start] */
ALTER TABLE script_event_audit
    VALIDATE CONSTRAINT ck_script_event_audit_positive_patch_base;
/* [jooq ignore stop] */

/* [jooq ignore start] */
ALTER TABLE script_schedule_instances
    VALIDATE CONSTRAINT ck_script_schedule_instances_positive_patch_base;
/* [jooq ignore stop] */

/* [jooq ignore start] */
ALTER TABLE script_patch_pin_projections
    VALIDATE CONSTRAINT ck_script_patch_pin_projections_positive_patch_base;
/* [jooq ignore stop] */
