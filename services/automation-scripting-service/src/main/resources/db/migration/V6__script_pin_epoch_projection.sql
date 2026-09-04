-- Carry the Game Session-owned pin generation in the Automation observation projection.
-- Absence is canonical for an unpinned projection and cannot authorize instance-scoped work.
ALTER TABLE script_patch_pin_projections
    ADD COLUMN script_pin_epoch BIGINT;

-- A retained pre-epoch row is not authoritative pin evidence.  Clear its legacy request identity
-- before enforcing the epoch/request pairing; a positive epoch must carry the owner identity.
UPDATE script_patch_pin_projections
SET last_observed_control_plane_request_id = ''
WHERE script_pin_epoch IS NULL OR script_pin_epoch = 0;

ALTER TABLE script_patch_pin_projections
    ADD CONSTRAINT ck_script_patch_pin_projections_pin_tuple CHECK (
        ((script_pin_epoch IS NULL OR script_pin_epoch = 0)
            AND NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NULL)
        OR (script_pin_epoch > 0
            AND NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NOT NULL)
    );
