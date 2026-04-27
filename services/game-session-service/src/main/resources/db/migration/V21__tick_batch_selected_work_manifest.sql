ALTER TABLE tick_batch
    ADD COLUMN expected_effect_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN selected_work_manifest_digest VARCHAR(64),
    ADD COLUMN selected_work_manifest_json TEXT;

UPDATE tick_batch
SET expected_effect_count = command_count
WHERE expected_effect_count = 0;
