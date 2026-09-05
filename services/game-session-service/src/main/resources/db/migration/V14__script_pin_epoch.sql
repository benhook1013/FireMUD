ALTER TABLE game_instances
    ADD COLUMN script_pin_epoch bigint;

UPDATE game_instances
SET script_pin_epoch = CASE
    WHEN NULLIF(BTRIM(script_patch_version), '') IS NULL THEN NULL
    ELSE 1
END
WHERE script_pin_epoch IS NULL OR script_pin_epoch <= 0;

ALTER TABLE game_instances
    ADD CONSTRAINT game_instances_script_pin_epoch_positive
    CHECK (script_pin_epoch IS NULL OR script_pin_epoch > 0);
