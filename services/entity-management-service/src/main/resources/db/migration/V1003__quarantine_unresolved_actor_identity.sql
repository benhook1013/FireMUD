CREATE TABLE entity_playable_state_namespace_scopes (
    tenant_uuid UUID NOT NULL,
    playable_state_namespace_id UUID NOT NULL,
    playable_state_scope VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_entity_playable_state_namespace_scopes
        PRIMARY KEY (tenant_uuid, playable_state_namespace_id),
    CONSTRAINT ck_entity_playable_state_namespace_scope
        CHECK (playable_state_scope IN (
            'PLAYABLE_STATE_SCOPE_SHARED',
            'PLAYABLE_STATE_SCOPE_ISOLATED'
        )),
    CONSTRAINT ck_entity_playable_state_namespace_tenant_non_nil
        CHECK (tenant_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT ck_entity_playable_state_namespace_id_non_nil
        CHECK (playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT ux_entity_playable_state_namespace_scope
        UNIQUE (tenant_uuid, playable_state_namespace_id, playable_state_scope)
);

/* [jooq ignore start] */
CREATE FUNCTION reject_entity_playable_state_namespace_scope_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF (NEW.tenant_uuid, NEW.playable_state_namespace_id, NEW.playable_state_scope)
        IS DISTINCT FROM
       (OLD.tenant_uuid, OLD.playable_state_namespace_id, OLD.playable_state_scope) THEN
        RAISE EXCEPTION 'entity playable-state namespace identity is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_entity_playable_state_namespace_scopes_immutable
    BEFORE UPDATE OF tenant_uuid, playable_state_namespace_id, playable_state_scope
    ON entity_playable_state_namespace_scopes
    FOR EACH ROW
    EXECUTE FUNCTION reject_entity_playable_state_namespace_scope_change();
/* [jooq ignore stop] */

ALTER TABLE characters
    ADD COLUMN character_uuid UUID,
    ADD COLUMN account_uuid UUID,
    ADD COLUMN tenant_uuid UUID,
    ADD COLUMN playable_state_namespace_id UUID,
    ADD COLUMN playable_state_scope VARCHAR(40),
    ADD COLUMN actor_identity_status VARCHAR(24) NOT NULL DEFAULT 'QUARANTINED',
    ADD COLUMN actor_identity_quarantine_reason VARCHAR(80)
        DEFAULT 'OWNER_PROVENANCE_MISSING';

UPDATE characters
SET character_uuid = gen_random_uuid(),
    actor_identity_quarantine_reason = 'OWNER_PROVENANCE_MISSING';

-- This retained-row backfill updates every existing character in one migration transaction.
-- Quiesce Entity character writes for this migration; it is not an online activation procedure.
ALTER TABLE characters
    ALTER COLUMN character_uuid SET DEFAULT gen_random_uuid();

/* [jooq ignore start] */
ALTER TABLE characters
    ADD CONSTRAINT ck_characters_character_uuid_nonnull
        CHECK (character_uuid IS NOT NULL) NOT VALID;

ALTER TABLE characters
    ADD CONSTRAINT ck_characters_character_uuid_non_nil
        CHECK (character_uuid <> '00000000-0000-0000-0000-000000000000'::UUID) NOT VALID;

ALTER TABLE characters
    ADD CONSTRAINT ck_characters_actor_identity_status
        CHECK (actor_identity_status IN ('OWNER_RESOLVED', 'QUARANTINED')) NOT VALID;

ALTER TABLE characters
    ADD CONSTRAINT ck_characters_actor_identity_quarantine_reason
        CHECK (
            (actor_identity_status = 'QUARANTINED'
                AND actor_identity_quarantine_reason IS NOT NULL
                AND length(trim(actor_identity_quarantine_reason)) > 0)
            OR (actor_identity_status = 'OWNER_RESOLVED'
                AND actor_identity_quarantine_reason IS NULL)
        ) NOT VALID;

ALTER TABLE characters
    ADD CONSTRAINT ck_characters_actor_identity_uuid_non_nil
        CHECK (
            (account_uuid IS NULL OR account_uuid <> '00000000-0000-0000-0000-000000000000'::UUID)
            AND (tenant_uuid IS NULL OR tenant_uuid <> '00000000-0000-0000-0000-000000000000'::UUID)
            AND (playable_state_namespace_id IS NULL OR playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::UUID)
        ) NOT VALID;

ALTER TABLE characters
    ADD CONSTRAINT ck_characters_actor_identity_scope
        CHECK (
            playable_state_scope IS NULL
            OR playable_state_scope IN (
                'PLAYABLE_STATE_SCOPE_SHARED',
                'PLAYABLE_STATE_SCOPE_ISOLATED'
            )
        ) NOT VALID;

ALTER TABLE characters
    ADD CONSTRAINT ck_characters_owner_resolved_provenance
        CHECK (
            actor_identity_status <> 'OWNER_RESOLVED'
            OR (
                account_uuid IS NOT NULL
                AND tenant_uuid IS NOT NULL
                AND playable_state_namespace_id IS NOT NULL
                AND playable_state_scope IS NOT NULL
            )
        ) NOT VALID;

ALTER TABLE characters
    ADD CONSTRAINT fk_characters_owner_resolved_namespace_scope
        FOREIGN KEY (tenant_uuid, playable_state_namespace_id, playable_state_scope)
        REFERENCES entity_playable_state_namespace_scopes (
            tenant_uuid,
            playable_state_namespace_id,
            playable_state_scope
        ) NOT VALID;
/* [jooq ignore stop] */

/* [jooq ignore start] */
CREATE VIEW entity_quarantined_actor_item_instances AS
WITH RECURSIVE retained_item_instances(tenant_id, item_instance_id) AS (
    SELECT item_instance.tenant_id, item_instance.id
    FROM item_instances item_instance
    JOIN characters actor
        ON actor.id = item_instance.character_id
       AND actor.actor_identity_status = 'QUARANTINED'

    UNION

    SELECT container.tenant_id, container.item_instance_id
    FROM container_instances container
    JOIN characters actor
        ON actor.id = container.character_id
       AND actor.actor_identity_status = 'QUARANTINED'
    WHERE container.item_instance_id IS NOT NULL

    UNION

    SELECT child_item.tenant_id, child_item.id
    FROM retained_item_instances retained_parent
    JOIN container_instances parent_container
        ON parent_container.tenant_id = retained_parent.tenant_id
       AND parent_container.item_instance_id = retained_parent.item_instance_id
    JOIN item_instances child_item
        ON child_item.tenant_id = parent_container.tenant_id
       AND child_item.container_instance_id = parent_container.id
)
SELECT DISTINCT tenant_id, item_instance_id
FROM retained_item_instances;

CREATE VIEW entity_quarantined_actor_container_instances AS
SELECT DISTINCT container.tenant_id, container.id AS container_instance_id
FROM container_instances container
JOIN characters actor
    ON actor.id = container.character_id
   AND actor.actor_identity_status = 'QUARANTINED'

UNION

SELECT DISTINCT container.tenant_id, container.id AS container_instance_id
FROM container_instances container
JOIN entity_quarantined_actor_item_instances retained_item
    ON retained_item.tenant_id = container.tenant_id
   AND retained_item.item_instance_id = container.item_instance_id;
/* [jooq ignore stop] */
