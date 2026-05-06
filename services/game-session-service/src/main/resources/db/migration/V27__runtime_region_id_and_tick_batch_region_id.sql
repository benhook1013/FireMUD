ALTER TABLE runtime_region_status
    ADD COLUMN region_id VARCHAR(64) NOT NULL DEFAULT '';

UPDATE runtime_region_status
SET region_id = CAST(game_instance_id AS VARCHAR)
WHERE region_id = '';

ALTER TABLE tick_batch
    ADD COLUMN region_id VARCHAR(64) NOT NULL DEFAULT '';

UPDATE tick_batch
SET region_id = CAST(game_instance_id AS VARCHAR)
WHERE region_id = '';
