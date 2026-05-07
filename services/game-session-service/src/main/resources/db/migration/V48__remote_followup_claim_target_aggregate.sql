ALTER TABLE remote_followup
ADD COLUMN claim_target_aggregate VARCHAR(128);

UPDATE remote_followup
SET claim_target_aggregate =
    CASE
        WHEN target_entity_id IS NOT NULL AND btrim(target_entity_id) <> ''
            THEN 'entity:' || btrim(target_entity_id)
        ELSE 'game-instance:' || target_game_instance_id::text
    END
WHERE claim_target_aggregate IS NULL OR btrim(claim_target_aggregate) = '';

ALTER TABLE remote_followup
ALTER COLUMN claim_target_aggregate SET NOT NULL;

CREATE INDEX idx_remote_followup_claim_target_due
ON remote_followup (tenant_id, target_region_id, status, claim_target_aggregate, due_tick_id);
