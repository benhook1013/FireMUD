-- Carry the Game Session-owned pin generation in the Automation observation projection.
-- Absence is canonical for an unpinned projection and cannot authorize instance-scoped work.
ALTER TABLE script_patch_pin_projections
    ADD COLUMN script_pin_epoch BIGINT;

-- A retained pre-epoch row is not authoritative pin evidence. Clear its legacy observed owner
-- tuple before enforcing the canonical unpinned/pinned states.
UPDATE script_patch_pin_projections
SET observed_pinned_script_patch_version = '',
    last_observed_control_plane_request_id = ''
WHERE script_pin_epoch IS NULL OR script_pin_epoch = 0;

ALTER TABLE script_patch_pin_projections
    ADD CONSTRAINT ck_script_patch_pin_projections_pin_tuple CHECK (
        ((script_pin_epoch IS NULL OR script_pin_epoch = 0)
            AND NULLIF(BTRIM(observed_pinned_script_patch_version), '') IS NULL
            AND NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NULL)
        OR (script_pin_epoch > 0
            AND NULLIF(BTRIM(observed_pinned_script_patch_version), '') IS NOT NULL
            AND NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NOT NULL)
    );
