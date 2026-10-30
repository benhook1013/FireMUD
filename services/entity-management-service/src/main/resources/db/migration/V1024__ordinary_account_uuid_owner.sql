-- Canonical Account ownership already has its own column. Retain private numeric
-- source evidence unchanged; fresh ordinary characters do not fabricate that key.
ALTER TABLE characters ALTER COLUMN account_id DROP NOT NULL;

CREATE INDEX idx_characters_ordinary_account_uuid
    ON characters (tenant_id, account_uuid, playable_state_key, id)
    WHERE tenant_uuid IS NULL AND account_uuid IS NOT NULL;

-- Preserve every existing core/provenance requirement. Ordinary UUID ownership
-- alone carries no canonical tenant, namespace, scope, or actor-promotion evidence.
/* [jooq ignore start] */
ALTER TABLE characters DROP CONSTRAINT ck_character_created_core;

ALTER TABLE characters ADD CONSTRAINT ck_character_created_core CHECK (
    (actor_entry_policy IS NULL AND creation_target_namespace IS NULL
        AND creation_operation_id IS NULL AND authored_initial_core_json IS NULL
        AND creation_descriptor_json IS NULL AND creation_descriptor_digest IS NULL
        AND (account_id IS NOT NULL OR (
            account_uuid IS NOT NULL
            AND account_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND tenant_uuid IS NULL AND playable_state_namespace_id IS NULL
            AND playable_state_scope IS NULL AND actor_identity_status = 'QUARANTINED'
        )) AND playable_state_key IS NOT NULL
        AND level IS NOT NULL AND experience IS NOT NULL AND strength IS NOT NULL
        AND agility IS NOT NULL AND intelligence IS NOT NULL AND stamina IS NOT NULL
        AND health IS NOT NULL AND mana IS NOT NULL AND body_layout_key IS NOT NULL)
    OR (actor_entry_policy = 'PLAYER_CREATED' AND creation_target_namespace IS NOT NULL
        AND creation_operation_id IS NOT NULL
        AND actor_identity_status IN ('OWNER_RESOLVED', 'QUARANTINED')
        AND account_id IS NULL AND playable_state_key IS NULL
        AND level IS NULL AND experience IS NULL AND strength IS NULL AND agility IS NULL
        AND intelligence IS NULL AND stamina IS NULL AND health IS NULL AND mana IS NULL
        AND body_layout_key IS NULL AND authored_initial_core_json IS NOT NULL
        AND creation_descriptor_json IS NOT NULL
        AND creation_descriptor_digest IS NOT NULL
        AND creation_descriptor_digest ~ '^sha256:[0-9a-f]{64}$'
        AND name = authored_initial_core_json::JSONB ->> 'displayName')
);
/* [jooq ignore stop] */
