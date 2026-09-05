-- Carry the authoritative Game Session pin generation through the rollout projection.
-- Existing or unobserved rows receive zero and remain fail-closed until refreshed from Game Session.
ALTER TABLE script_patch_instance_rollout_projections
    ADD COLUMN script_pin_epoch BIGINT NOT NULL DEFAULT 0;

ALTER TABLE script_patch_instance_rollout_projections
    ADD COLUMN last_observed_control_plane_request_id VARCHAR(256) NOT NULL DEFAULT '';

ALTER TABLE script_patch_instance_rollout_projections
    ADD CONSTRAINT ck_script_patch_instance_rollout_projections_pin_tuple CHECK (
        (script_pin_epoch = 0
            AND NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NULL)
        OR (script_pin_epoch > 0
            AND NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NOT NULL)
    );

ALTER TABLE script_patch_instance_rollout_events
    ADD COLUMN script_pin_epoch BIGINT NOT NULL DEFAULT 0;

ALTER TABLE script_patch_instance_rollout_events
    ADD COLUMN last_observed_control_plane_request_id VARCHAR(256) NOT NULL DEFAULT '';

ALTER TABLE script_patch_instance_rollout_events
    ADD CONSTRAINT ck_script_patch_instance_rollout_events_pin_tuple CHECK (
        (script_pin_epoch = 0
            AND NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NULL)
        OR (script_pin_epoch > 0
            AND NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NOT NULL)
    );
