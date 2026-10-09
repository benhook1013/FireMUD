-- V1-V8 assignment and roster evidence remains immutable history. Older roster rows did not
-- retain these owner counters, so leave them NULL rather than inferring or fabricating values.
ALTER TABLE entity_canonical_gameplay_roster_snapshots
    ADD COLUMN pointer_version BIGINT;
ALTER TABLE entity_canonical_gameplay_roster_snapshots
    ADD COLUMN active_world_epoch BIGINT;

ALTER TABLE entity_canonical_gameplay_roster_snapshots
    ALTER COLUMN publication_evidence_format SET DEFAULT 'CANONICAL_UUID_V2';
ALTER TABLE entity_canonical_gameplay_roster_snapshots
    DROP CONSTRAINT ck_entity_canonical_roster_publication_evidence;
ALTER TABLE entity_canonical_gameplay_roster_snapshots
    ADD CONSTRAINT ck_entity_canonical_roster_publication_evidence
    CHECK (
        catalog_revision > 0 AND (
            (publication_evidence_format = 'LEGACY_NUMERIC'
                AND canonical_version_uuid IS NULL
                AND published_version_id > 0 AND published_version_number > 0
                AND pointer_version IS NULL AND active_world_epoch IS NULL)
            OR (publication_evidence_format = 'CANONICAL_UUID'
                AND canonical_version_uuid IS NOT NULL
                AND canonical_version_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
                AND published_version_id IS NULL AND published_version_number IS NULL
                AND pointer_version IS NULL AND active_world_epoch IS NULL)
            OR (publication_evidence_format = 'CANONICAL_UUID_V2'
                AND canonical_version_uuid IS NOT NULL
                AND canonical_version_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
                AND published_version_id IS NULL AND published_version_number IS NULL
                AND pointer_version IS NOT NULL AND pointer_version > 0
                AND active_world_epoch IS NOT NULL AND active_world_epoch > 0)
        )
    );

/* [jooq ignore start] */
DROP TRIGGER trg_entity_canonical_roster_current_publication
    ON entity_canonical_gameplay_roster_snapshots;

CREATE FUNCTION require_current_entity_canonical_roster_evidence()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.publication_evidence_format <> 'CANONICAL_UUID_V2'
       OR NEW.pointer_version IS NULL OR NEW.pointer_version <= 0
       OR NEW.active_world_epoch IS NULL OR NEW.active_world_epoch <= 0
       OR NEW.canonical_version_uuid IS NULL
       OR NEW.canonical_version_uuid = '00000000-0000-0000-0000-000000000000'::UUID
       OR NEW.published_version_id IS NOT NULL
       OR NEW.published_version_number IS NOT NULL THEN
        RAISE EXCEPTION 'new Entity roster evidence requires exact canonical owner counters'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_entity_canonical_roster_current_publication
    BEFORE INSERT ON entity_canonical_gameplay_roster_snapshots
    FOR EACH ROW
    EXECUTE FUNCTION require_current_entity_canonical_roster_evidence();

CREATE OR REPLACE FUNCTION protect_entity_canonical_gameplay_roster_snapshot()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
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
           NEW.canonical_version_uuid, NEW.publication_evidence_format,
           NEW.pointer_version, NEW.active_world_epoch,
           NEW.published_policy_digest, NEW.published_release_bundle_ref,
           NEW.admission_pointer_snapshot_digest, NEW.published_owner_proof_digest,
           NEW.playable_state_namespace_uuid, NEW.playable_state_scope, NEW.entry_policy,
           NEW.roster_count, NEW.created_at)
          IS DISTINCT FROM
          (OLD.snapshot_uuid, OLD.snapshot_digest, OLD.canonical_account_uuid, OLD.tenant_uuid,
           OLD.realm_uuid, OLD.world_slug, OLD.realm_slug, OLD.game_instance_uuid,
           OLD.catalog_revision, OLD.published_version_id, OLD.published_version_number,
           OLD.canonical_version_uuid, OLD.publication_evidence_format,
           OLD.pointer_version, OLD.active_world_epoch,
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
/* [jooq ignore stop] */
