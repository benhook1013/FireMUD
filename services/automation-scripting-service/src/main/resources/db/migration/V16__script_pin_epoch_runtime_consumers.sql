-- Carry the authoritative Game Session pin generation through every Automation runtime consumer.
-- Existing rows receive zero and remain fail-closed until refreshed from Game Session.
ALTER TABLE script_patch_instance_rollout_projections
    ADD COLUMN script_pin_epoch BIGINT NOT NULL DEFAULT 0;
