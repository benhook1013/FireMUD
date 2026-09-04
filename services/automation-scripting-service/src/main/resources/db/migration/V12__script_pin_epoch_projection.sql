-- Carry the Game Session-owned pin generation in the Automation observation projection.
-- Existing rows intentionally receive the fail-closed sentinel 0: they remain observable for
-- diagnostics, but schedule reconciliation must keep them pending until a positive epoch is
-- observed from the authoritative runtime state.
ALTER TABLE script_patch_pin_projections
    ADD COLUMN script_pin_epoch BIGINT NOT NULL DEFAULT 0;
