-- Carry the authoritative Game Session pin generation through the rollout projection.
-- Existing or unobserved rows receive zero and remain fail-closed until refreshed from Game Session.
ALTER TABLE script_patch_instance_rollout_projections
    ADD COLUMN script_pin_epoch BIGINT NOT NULL DEFAULT 0;

ALTER TABLE script_patch_instance_rollout_events
    ADD COLUMN script_pin_epoch BIGINT NOT NULL DEFAULT 0;
