-- V4/V6/V7 add these checks without immediate row validation so constraint installation does not
-- hold a validation-strength lock while older rows are scanned. Validate them separately
-- after the additive migrations have committed.
/* [jooq ignore start] */
ALTER TABLE script_work_items
    VALIDATE CONSTRAINT ck_script_work_items_pin_tuple;
/* [jooq ignore stop] */

/* [jooq ignore start] */
ALTER TABLE script_schedule_instances
    VALIDATE CONSTRAINT ck_script_schedule_instances_pin_tuple;
/* [jooq ignore stop] */

/* [jooq ignore start] */
ALTER TABLE script_patch_pin_projections
    VALIDATE CONSTRAINT ck_script_patch_pin_projections_pin_tuple;
/* [jooq ignore stop] */

/* [jooq ignore start] */
ALTER TABLE script_patch_instance_rollout_projections
    VALIDATE CONSTRAINT ck_script_patch_instance_rollout_projections_pin_tuple;
/* [jooq ignore stop] */

/* [jooq ignore start] */
ALTER TABLE script_patch_instance_rollout_events
    VALIDATE CONSTRAINT ck_script_patch_instance_rollout_events_pin_tuple;
/* [jooq ignore stop] */
