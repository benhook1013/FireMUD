CREATE TABLE entity_canonical_gameplay_roster_snapshots (
    snapshot_uuid UUID PRIMARY KEY,
    snapshot_digest VARCHAR(64) NOT NULL UNIQUE,
    canonical_account_uuid UUID NOT NULL,
    tenant_uuid UUID NOT NULL,
    realm_uuid UUID NOT NULL,
    world_slug VARCHAR(64) NOT NULL,
    realm_slug VARCHAR(64) NOT NULL,
    game_instance_uuid UUID NOT NULL,
    catalog_revision BIGINT NOT NULL,
    published_version_id BIGINT NOT NULL,
    published_version_number INTEGER NOT NULL,
    published_policy_digest VARCHAR(64) NOT NULL,
    published_release_bundle_ref VARCHAR(1024) NOT NULL,
    admission_pointer_snapshot_digest VARCHAR(64) NOT NULL,
    published_owner_proof_digest VARCHAR(64) NOT NULL,
    playable_state_namespace_uuid UUID NOT NULL,
    playable_state_scope VARCHAR(40) NOT NULL,
    entry_policy VARCHAR(32) NOT NULL,
    roster_count SMALLINT NOT NULL,
    construction_state VARCHAR(8) NOT NULL DEFAULT 'BUILDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_entity_canonical_roster_snapshot_uuid_non_nil
        CHECK (snapshot_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT ck_entity_canonical_roster_account_non_nil
        CHECK (canonical_account_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT ck_entity_canonical_roster_tenant_non_nil
        CHECK (tenant_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT ck_entity_canonical_roster_realm_non_nil
        CHECK (realm_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT ck_entity_canonical_roster_instance_non_nil
        CHECK (game_instance_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT ck_entity_canonical_roster_namespace_non_nil
        CHECK (playable_state_namespace_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT ck_entity_canonical_roster_digests
        CHECK (snapshot_digest ~ '^[0-9a-f]{64}$'
            AND published_policy_digest ~ '^[0-9a-f]{64}$'
            AND admission_pointer_snapshot_digest ~ '^[0-9a-f]{64}$'
            AND published_owner_proof_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_entity_canonical_roster_slug
        CHECK (world_slug ~ '^[a-z0-9][a-z0-9-]{0,63}$'
            AND realm_slug ~ '^[a-z0-9][a-z0-9-]{0,63}$'),
    CONSTRAINT ck_entity_canonical_roster_release_ref
        CHECK (length(btrim(published_release_bundle_ref)) > 0),
    CONSTRAINT ck_entity_canonical_roster_scope
        CHECK (playable_state_scope IN (
            'PLAYABLE_STATE_SCOPE_SHARED', 'PLAYABLE_STATE_SCOPE_ISOLATED')),
    CONSTRAINT ck_entity_canonical_roster_policy
        CHECK (entry_policy = 'PRESEEDED_ONLY'),
    CONSTRAINT ck_entity_canonical_roster_versions
        CHECK (catalog_revision > 0 AND published_version_id > 0
            AND published_version_number > 0),
    CONSTRAINT ck_entity_canonical_roster_count
        CHECK (roster_count BETWEEN 0 AND 100),
    CONSTRAINT ck_entity_canonical_roster_construction_state
        CHECK (construction_state IN ('BUILDING', 'SEALED'))
);

CREATE TABLE entity_canonical_gameplay_roster_snapshot_actors (
    snapshot_uuid UUID NOT NULL,
    ordinal_position SMALLINT NOT NULL,
    character_uuid UUID NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    actor_kind VARCHAR(16) NOT NULL,
    CONSTRAINT pk_entity_canonical_roster_snapshot_actors
        PRIMARY KEY (snapshot_uuid, ordinal_position),
    CONSTRAINT ux_entity_canonical_roster_snapshot_actor
        UNIQUE (snapshot_uuid, character_uuid),
    CONSTRAINT ck_entity_canonical_roster_actor_ordinal
        CHECK (ordinal_position BETWEEN 0 AND 99),
    CONSTRAINT ck_entity_canonical_roster_actor_uuid_non_nil
        CHECK (character_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT ck_entity_canonical_roster_actor_name
        CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT ck_entity_canonical_roster_actor_kind
        CHECK (actor_kind = 'PLAYER')
);

/* [jooq ignore start] */
ALTER TABLE entity_canonical_gameplay_roster_snapshots
    ADD CONSTRAINT fk_entity_canonical_roster_tenant
        FOREIGN KEY (tenant_uuid)
        REFERENCES entity_tenant_identities (canonical_tenant_uuid)
        DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE entity_canonical_gameplay_roster_snapshots
    ADD CONSTRAINT fk_entity_canonical_roster_namespace_scope
        FOREIGN KEY (tenant_uuid, playable_state_namespace_uuid, playable_state_scope)
        REFERENCES entity_playable_state_namespace_scopes (
            tenant_uuid, playable_state_namespace_id, playable_state_scope
        )
        DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE entity_canonical_gameplay_roster_snapshot_actors
    ADD CONSTRAINT fk_entity_canonical_roster_snapshot
        FOREIGN KEY (snapshot_uuid)
        REFERENCES entity_canonical_gameplay_roster_snapshots (snapshot_uuid)
        DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE entity_canonical_gameplay_roster_snapshot_actors
    ADD CONSTRAINT fk_entity_canonical_roster_character
        FOREIGN KEY (character_uuid)
        REFERENCES characters (character_uuid)
        DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION protect_entity_canonical_gameplay_roster_snapshot()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    actor_count INTEGER;
    minimum_position SMALLINT;
    maximum_position SMALLINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'canonical gameplay roster snapshots cannot be removed'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.construction_state <> 'BUILDING'
       OR NEW.construction_state <> 'SEALED'
       OR (NEW.snapshot_uuid, NEW.snapshot_digest, NEW.canonical_account_uuid, NEW.tenant_uuid,
           NEW.realm_uuid, NEW.world_slug, NEW.realm_slug, NEW.game_instance_uuid,
           NEW.catalog_revision, NEW.published_version_id, NEW.published_version_number,
           NEW.published_policy_digest, NEW.published_release_bundle_ref,
           NEW.admission_pointer_snapshot_digest, NEW.published_owner_proof_digest,
           NEW.playable_state_namespace_uuid, NEW.playable_state_scope, NEW.entry_policy,
           NEW.roster_count, NEW.created_at)
          IS DISTINCT FROM
          (OLD.snapshot_uuid, OLD.snapshot_digest, OLD.canonical_account_uuid, OLD.tenant_uuid,
           OLD.realm_uuid, OLD.world_slug, OLD.realm_slug, OLD.game_instance_uuid,
           OLD.catalog_revision, OLD.published_version_id, OLD.published_version_number,
           OLD.published_policy_digest, OLD.published_release_bundle_ref,
           OLD.admission_pointer_snapshot_digest, OLD.published_owner_proof_digest,
           OLD.playable_state_namespace_uuid, OLD.playable_state_scope, OLD.entry_policy,
           OLD.roster_count, OLD.created_at) THEN
        RAISE EXCEPTION 'canonical gameplay roster snapshot is immutable'
            USING ERRCODE = '23514';
    END IF;

    SELECT count(*), min(ordinal_position), max(ordinal_position)
    INTO actor_count, minimum_position, maximum_position
    FROM entity_canonical_gameplay_roster_snapshot_actors
    WHERE snapshot_uuid = OLD.snapshot_uuid;
    IF actor_count <> OLD.roster_count
       OR (actor_count = 0 AND (minimum_position IS NOT NULL OR maximum_position IS NOT NULL))
       OR (actor_count > 0 AND (minimum_position <> 0 OR maximum_position <> actor_count - 1)) THEN
        RAISE EXCEPTION 'canonical gameplay roster snapshot is incomplete'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION protect_entity_canonical_gameplay_roster_snapshot_actor()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    snapshot_row entity_canonical_gameplay_roster_snapshots%ROWTYPE;
    actor_row characters%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'canonical gameplay roster snapshot actors are immutable'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO snapshot_row
    FROM entity_canonical_gameplay_roster_snapshots
    WHERE snapshot_uuid = NEW.snapshot_uuid
    FOR UPDATE;
    IF NOT FOUND OR snapshot_row.construction_state <> 'BUILDING' THEN
        RAISE EXCEPTION 'canonical gameplay roster snapshot is not being constructed'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO actor_row
    FROM characters
    WHERE character_uuid = NEW.character_uuid
    FOR SHARE;
    IF NOT FOUND
       OR actor_row.actor_identity_status <> 'OWNER_RESOLVED'
       OR actor_row.actor_identity_quarantine_reason IS NOT NULL
       OR actor_row.account_uuid <> snapshot_row.canonical_account_uuid
       OR actor_row.tenant_uuid <> snapshot_row.tenant_uuid
       OR actor_row.playable_state_namespace_id <> snapshot_row.playable_state_namespace_uuid
       OR actor_row.playable_state_scope <> snapshot_row.playable_state_scope
       OR actor_row.name <> NEW.display_name
       OR NEW.actor_kind <> 'PLAYER' THEN
        RAISE EXCEPTION 'canonical gameplay roster actor provenance mismatch'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_entity_canonical_roster_snapshot_guard
    BEFORE UPDATE OR DELETE ON entity_canonical_gameplay_roster_snapshots
    FOR EACH ROW
    EXECUTE FUNCTION protect_entity_canonical_gameplay_roster_snapshot();

CREATE TRIGGER trg_entity_canonical_roster_snapshot_no_truncate
    BEFORE TRUNCATE ON entity_canonical_gameplay_roster_snapshots
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_entity_assignment_history_removal();

CREATE TRIGGER trg_entity_canonical_roster_actor_guard
    BEFORE INSERT OR UPDATE OR DELETE ON entity_canonical_gameplay_roster_snapshot_actors
    FOR EACH ROW
    EXECUTE FUNCTION protect_entity_canonical_gameplay_roster_snapshot_actor();

CREATE TRIGGER trg_entity_canonical_roster_actor_no_truncate
    BEFORE TRUNCATE ON entity_canonical_gameplay_roster_snapshot_actors
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_entity_assignment_history_removal();
/* [jooq ignore stop] */

CREATE INDEX idx_entity_canonical_roster_account_target
    ON entity_canonical_gameplay_roster_snapshots (
        tenant_uuid, canonical_account_uuid, playable_state_namespace_uuid, created_at
    );
