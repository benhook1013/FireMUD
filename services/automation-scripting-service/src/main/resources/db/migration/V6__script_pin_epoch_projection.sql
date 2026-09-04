-- Carry the Game Session-owned pin generation in the Automation observation projection.
-- Absence is canonical for an unpinned projection and cannot authorize instance-scoped work.
ALTER TABLE script_patch_pin_projections
    ADD COLUMN script_pin_epoch BIGINT;
