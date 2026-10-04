-- Retain historical numeric owner keys exactly; no Account UUID reverse mapping is available.
ALTER TABLE game_instances
    ALTER COLUMN owner_account_id DROP NOT NULL;

ALTER TABLE game_instances
    ADD COLUMN owner_account_uuid uuid;

ALTER TABLE game_instances
    ADD CONSTRAINT game_instances_owner_identity_present_and_valid
        CHECK (
            (owner_account_id IS NOT NULL OR owner_account_uuid IS NOT NULL)
            AND (
                owner_account_uuid IS NULL
                OR owner_account_uuid <> '00000000-0000-0000-0000-000000000000'::uuid
            )
        );

CREATE UNIQUE INDEX uq_game_instances_running_tenant_owner_uuid
    ON game_instances (tenant_id, owner_account_uuid)
    WHERE status = 'RUNNING' AND owner_account_uuid IS NOT NULL;
