ALTER TABLE entity_preseeded_actor_assignment_operations
    ADD COLUMN account_identity_schema_version SMALLINT NOT NULL,
    ADD COLUMN account_identity_target_namespace VARCHAR(63) NOT NULL,
    ADD COLUMN source_account_row_id BIGINT NOT NULL,
    ADD COLUMN eligibility VARCHAR(16) NOT NULL,
    ADD COLUMN account_authority_snapshot_digest VARCHAR(64) NOT NULL,
    ADD COLUMN account_purpose VARCHAR(40) NOT NULL,
    ADD COLUMN account_currentness VARCHAR(40) NOT NULL,
    ADD COLUMN published_owner_proof_digest VARCHAR(64) NOT NULL,
    ADD COLUMN published_release_bundle_ref VARCHAR(1024) NOT NULL;

ALTER TABLE entity_preseeded_actor_assignment_operations
    ADD CONSTRAINT ck_entity_preseeded_assignment_owner_snapshot
        CHECK (
            account_identity_schema_version = 1
            AND account_identity_target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
            AND source_account_row_id > 0
            AND eligibility = 'ELIGIBLE'
            AND account_authority_snapshot_digest ~ '^[0-9a-f]{64}$'
            AND account_purpose = 'PRESEEDED_ACTOR_STAGING'
            AND account_currentness = 'CURRENT_AT_REVALIDATION'
            AND published_owner_proof_digest ~ '^[0-9a-f]{64}$'
            AND length(btrim(published_release_bundle_ref)) > 0
        );

/* [jooq ignore start] */
CREATE OR REPLACE FUNCTION protect_entity_preseeded_actor_assignment_operation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF (NEW.assignment_uuid, NEW.intent_digest, NEW.account_uuid, NEW.account_uuid_provenance,
        NEW.account_identity_schema_version, NEW.account_identity_target_namespace,
        NEW.source_account_row_id, NEW.eligibility,
        NEW.eligibility_evaluated_at, NEW.membership_authority_generation,
        NEW.eligibility_evidence_digest, NEW.tenant_uuid,
        NEW.realm_uuid, NEW.world_slug, NEW.realm_slug, NEW.game_instance_id, NEW.catalog_revision,
        NEW.published_version_id, NEW.published_version_number, NEW.frozen_policy_digest,
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
        OLD.published_version_id, OLD.published_version_number, OLD.frozen_policy_digest,
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
/* [jooq ignore stop] */
