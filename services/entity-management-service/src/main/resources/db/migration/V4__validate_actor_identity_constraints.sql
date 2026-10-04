/* [jooq ignore start] */
ALTER TABLE characters
    VALIDATE CONSTRAINT ck_characters_character_uuid_nonnull;
/* [jooq ignore stop] */

ALTER TABLE characters
    ALTER COLUMN character_uuid SET NOT NULL;

/* [jooq ignore start] */
ALTER TABLE characters
    DROP CONSTRAINT ck_characters_character_uuid_nonnull;

ALTER TABLE characters
    VALIDATE CONSTRAINT ck_characters_character_uuid_non_nil;

ALTER TABLE characters
    VALIDATE CONSTRAINT ck_characters_actor_identity_status;

ALTER TABLE characters
    VALIDATE CONSTRAINT ck_characters_actor_identity_quarantine_reason;

ALTER TABLE characters
    VALIDATE CONSTRAINT ck_characters_actor_identity_uuid_non_nil;

ALTER TABLE characters
    VALIDATE CONSTRAINT ck_characters_actor_identity_scope;

ALTER TABLE characters
    VALIDATE CONSTRAINT ck_characters_owner_resolved_provenance;

ALTER TABLE characters
    VALIDATE CONSTRAINT fk_characters_owner_resolved_namespace_scope;
/* [jooq ignore stop] */
