-- V5-V7 evidence remains immutable history. Its numeric publication identifiers do not
-- establish a canonical version UUID and must never be upgraded by inference.
ALTER TABLE entity_preseeded_actor_assignment_operations
    ADD COLUMN canonical_version_uuid UUID;
ALTER TABLE entity_preseeded_actor_assignment_operations
    ADD COLUMN publication_evidence_format VARCHAR(24) NOT NULL DEFAULT 'LEGACY_NUMERIC';

ALTER TABLE entity_canonical_gameplay_roster_snapshots
    ADD COLUMN canonical_version_uuid UUID;
ALTER TABLE entity_canonical_gameplay_roster_snapshots
    ADD COLUMN publication_evidence_format VARCHAR(24) NOT NULL DEFAULT 'LEGACY_NUMERIC';

ALTER TABLE entity_preseeded_actor_assignment_operations
    ALTER COLUMN published_version_id DROP NOT NULL;
ALTER TABLE entity_preseeded_actor_assignment_operations
    ALTER COLUMN published_version_number DROP NOT NULL;
ALTER TABLE entity_preseeded_actor_assignment_operations
    ALTER COLUMN publication_evidence_format SET DEFAULT 'CANONICAL_UUID';
ALTER TABLE entity_preseeded_actor_assignment_operations
    DROP CONSTRAINT ck_entity_preseeded_assignment_positive_versions;
ALTER TABLE entity_preseeded_actor_assignment_operations
    ADD CONSTRAINT ck_entity_preseeded_assignment_publication_evidence
    CHECK (
        catalog_revision > 0 AND (
            (publication_evidence_format = 'LEGACY_NUMERIC'
                AND canonical_version_uuid IS NULL
                AND published_version_id > 0 AND published_version_number > 0)
            OR (publication_evidence_format = 'CANONICAL_UUID'
                AND canonical_version_uuid IS NOT NULL
                AND canonical_version_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
                AND published_version_id IS NULL AND published_version_number IS NULL)
        )
    );

ALTER TABLE entity_canonical_gameplay_roster_snapshots
    ALTER COLUMN published_version_id DROP NOT NULL;
ALTER TABLE entity_canonical_gameplay_roster_snapshots
    ALTER COLUMN published_version_number DROP NOT NULL;
ALTER TABLE entity_canonical_gameplay_roster_snapshots
    ALTER COLUMN publication_evidence_format SET DEFAULT 'CANONICAL_UUID';
ALTER TABLE entity_canonical_gameplay_roster_snapshots
    DROP CONSTRAINT ck_entity_canonical_roster_versions;
ALTER TABLE entity_canonical_gameplay_roster_snapshots
    ADD CONSTRAINT ck_entity_canonical_roster_publication_evidence
    CHECK (
        catalog_revision > 0 AND (
            (publication_evidence_format = 'LEGACY_NUMERIC'
                AND canonical_version_uuid IS NULL
                AND published_version_id > 0 AND published_version_number > 0)
            OR (publication_evidence_format = 'CANONICAL_UUID'
                AND canonical_version_uuid IS NOT NULL
                AND canonical_version_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
                AND published_version_id IS NULL AND published_version_number IS NULL)
        )
    );

/* [jooq ignore start] */
CREATE FUNCTION require_current_entity_publication_evidence()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.publication_evidence_format <> 'CANONICAL_UUID' THEN
        RAISE EXCEPTION 'new Entity publication evidence requires canonical UUID format'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_entity_preseeded_assignment_current_publication
    BEFORE INSERT ON entity_preseeded_actor_assignment_operations
    FOR EACH ROW
    EXECUTE FUNCTION require_current_entity_publication_evidence();

CREATE TRIGGER trg_entity_canonical_roster_current_publication
    BEFORE INSERT ON entity_canonical_gameplay_roster_snapshots
    FOR EACH ROW
    EXECUTE FUNCTION require_current_entity_publication_evidence();

CREATE OR REPLACE FUNCTION protect_entity_preseeded_actor_assignment_operation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.assignment_uuid, NEW.intent_digest, NEW.account_uuid, NEW.account_uuid_provenance,
        NEW.account_identity_schema_version, NEW.account_identity_target_namespace,
        NEW.source_account_row_id, NEW.eligibility,
        NEW.eligibility_evaluated_at, NEW.membership_authority_generation,
        NEW.eligibility_evidence_digest, NEW.tenant_uuid,
        NEW.realm_uuid, NEW.world_slug, NEW.realm_slug, NEW.game_instance_id, NEW.catalog_revision,
        NEW.published_version_id, NEW.published_version_number, NEW.canonical_version_uuid,
        NEW.publication_evidence_format, NEW.frozen_policy_digest,
        NEW.playable_state_namespace_id, NEW.playable_state_scope, NEW.entry_policy,
        NEW.actor_kind, NEW.display_name, NEW.created_at,
        NEW.account_authority_snapshot_digest, NEW.account_purpose, NEW.account_currentness,
        NEW.published_owner_proof_digest, NEW.published_release_bundle_ref)
        IS DISTINCT FROM
       (OLD.assignment_uuid, OLD.intent_digest, OLD.account_uuid, OLD.account_uuid_provenance,
        OLD.account_identity_schema_version, OLD.account_identity_target_namespace,
        OLD.source_account_row_id, OLD.eligibility,
        OLD.eligibility_evaluated_at, OLD.membership_authority_generation,
        OLD.eligibility_evidence_digest, OLD.tenant_uuid,
        OLD.realm_uuid, OLD.world_slug, OLD.realm_slug, OLD.game_instance_id, OLD.catalog_revision,
        OLD.published_version_id, OLD.published_version_number, OLD.canonical_version_uuid,
        OLD.publication_evidence_format, OLD.frozen_policy_digest,
        OLD.playable_state_namespace_id, OLD.playable_state_scope, OLD.entry_policy,
        OLD.actor_kind, OLD.display_name, OLD.created_at,
        OLD.account_authority_snapshot_digest, OLD.account_purpose, OLD.account_currentness,
        OLD.published_owner_proof_digest, OLD.published_release_bundle_ref)
       OR OLD.status <> 'PENDING'
       OR NEW.status NOT IN ('ASSIGNED', 'IDEMPOTENCY_CONFLICT') THEN
        RAISE EXCEPTION 'pre-seeded actor assignment intent is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

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
           NEW.published_policy_digest, NEW.published_release_bundle_ref,
           NEW.admission_pointer_snapshot_digest, NEW.published_owner_proof_digest,
           NEW.playable_state_namespace_uuid, NEW.playable_state_scope, NEW.entry_policy,
           NEW.roster_count, NEW.created_at)
          IS DISTINCT FROM
          (OLD.snapshot_uuid, OLD.snapshot_digest, OLD.canonical_account_uuid, OLD.tenant_uuid,
           OLD.realm_uuid, OLD.world_slug, OLD.realm_slug, OLD.game_instance_uuid,
           OLD.catalog_revision, OLD.published_version_id, OLD.published_version_number,
           OLD.canonical_version_uuid, OLD.publication_evidence_format,
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
