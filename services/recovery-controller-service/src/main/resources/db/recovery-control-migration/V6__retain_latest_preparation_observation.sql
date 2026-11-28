-- Keep the original preparation acknowledgement and advance its observation separately.
-- Validate pending rows without rewriting retained outcome evidence.
ALTER TABLE recovery_control.initial_gameplay_release_outbox
    ADD COLUMN preparation_observation_payload jsonb,
    ADD COLUMN preparation_observation_digest character(64),
    ADD CONSTRAINT ck_release_outbox_pending_outcome
        CHECK (state <> 'pending' OR (last_outcome_code IS NULL AND send_outcome_uncertain IS NOT TRUE)),
    ADD CONSTRAINT ck_release_outbox_preparation_observation
        CHECK (
            (preparation_observation_payload IS NULL AND preparation_observation_digest IS NULL)
            OR
            (preparation_observation_payload IS NOT NULL AND preparation_observation_digest IS NOT NULL
                AND effect_kind = 'prepare_playable_state_fenced' AND state = 'acknowledged')
        );
