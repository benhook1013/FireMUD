/* [jooq ignore start] */
CREATE UNIQUE INDEX CONCURRENTLY ux_characters_character_uuid_idx
    ON characters (character_uuid);

CREATE INDEX CONCURRENTLY idx_characters_owner_resolved_roster
    ON characters (
        tenant_uuid,
        account_uuid,
        playable_state_namespace_id,
        playable_state_scope,
        character_uuid
    )
    WHERE actor_identity_status = 'OWNER_RESOLVED';
/* [jooq ignore stop] */

/* [jooq ignore start] */
ALTER TABLE characters
    ADD CONSTRAINT ux_characters_character_uuid
        UNIQUE USING INDEX ux_characters_character_uuid_idx;
/* [jooq ignore stop] */
