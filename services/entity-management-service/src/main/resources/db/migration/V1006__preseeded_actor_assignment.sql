CREATE TABLE entity_tenant_identities (
    entity_tenant_row_id BIGSERIAL PRIMARY KEY,
    canonical_tenant_uuid UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_entity_tenant_identity_non_nil
        CHECK (canonical_tenant_uuid <> '00000000-0000-0000-0000-000000000000'::UUID)
);

-- Legacy owner-resolved fixture/data rows are not trusted when one account has multiple actors
-- in one durable namespace. Quarantine every member of an ambiguous group; never pick a winner.
/* [jooq ignore start] */
WITH ambiguous_account_namespaces AS (
    SELECT tenant_uuid, account_uuid, playable_state_namespace_id
    FROM characters
    WHERE actor_identity_status = 'OWNER_RESOLVED'
    GROUP BY tenant_uuid, account_uuid, playable_state_namespace_id
    HAVING COUNT(*) > 1
)
UPDATE characters AS actor
SET actor_identity_status = 'QUARANTINED',
    actor_identity_quarantine_reason = 'AMBIGUOUS_ACCOUNT_NAMESPACE'
FROM ambiguous_account_namespaces AS ambiguous
WHERE actor.actor_identity_status = 'OWNER_RESOLVED'
  AND actor.tenant_uuid = ambiguous.tenant_uuid
  AND actor.account_uuid = ambiguous.account_uuid
  AND actor.playable_state_namespace_id = ambiguous.playable_state_namespace_id;

CREATE UNIQUE INDEX ux_characters_owner_resolved_account_namespace
    ON characters (tenant_uuid, account_uuid, playable_state_namespace_id)
    WHERE actor_identity_status = 'OWNER_RESOLVED';
/* [jooq ignore stop] */

CREATE TABLE entity_preseeded_actor_assignment_operations (
    assignment_uuid UUID PRIMARY KEY,
    intent_digest VARCHAR(64) NOT NULL,
    account_uuid UUID NOT NULL,
    account_uuid_provenance VARCHAR(32) NOT NULL,
    eligibility_evaluated_at TIMESTAMPTZ NOT NULL,
    membership_authority_generation BIGINT NOT NULL,
    eligibility_evidence_digest VARCHAR(64) NOT NULL,
    tenant_uuid UUID NOT NULL,
    realm_uuid UUID NOT NULL,
    world_slug VARCHAR(64) NOT NULL,
    realm_slug VARCHAR(64) NOT NULL,
    game_instance_id VARCHAR(255) NOT NULL,
    catalog_revision BIGINT NOT NULL,
    published_version_id BIGINT NOT NULL,
    published_version_number INTEGER NOT NULL,
    frozen_policy_digest VARCHAR(64) NOT NULL,
    playable_state_namespace_id UUID NOT NULL,
    playable_state_scope VARCHAR(40) NOT NULL,
    entry_policy VARCHAR(32) NOT NULL DEFAULT 'PRESEEDED_ONLY',
    actor_kind VARCHAR(16) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    character_uuid UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_entity_preseeded_assignment_uuid_non_nil
        CHECK (assignment_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT ck_entity_preseeded_assignment_account_non_nil
        CHECK (account_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT ck_entity_preseeded_assignment_tenant_non_nil
        CHECK (tenant_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT ck_entity_preseeded_assignment_realm_non_nil
        CHECK (realm_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT ck_entity_preseeded_assignment_namespace_non_nil
        CHECK (playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT ck_entity_preseeded_assignment_digest
        CHECK (intent_digest ~ '^[0-9a-f]{64}$'
            AND eligibility_evidence_digest ~ '^[0-9a-f]{64}$'
            AND frozen_policy_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_entity_preseeded_assignment_account_provenance
        CHECK (account_uuid_provenance IN (
            'ACCOUNT_V29_MIGRATION',
            'ACCOUNT_REPOSITORY_INSERT',
            'ACCOUNT_DATABASE_INSERT'
        )),
    CONSTRAINT ck_entity_preseeded_assignment_authority_generation
        CHECK (membership_authority_generation > 0),
    CONSTRAINT ck_entity_preseeded_assignment_scope
        CHECK (playable_state_scope IN (
            'PLAYABLE_STATE_SCOPE_SHARED',
            'PLAYABLE_STATE_SCOPE_ISOLATED'
        )),
    CONSTRAINT ck_entity_preseeded_assignment_policy
        CHECK (entry_policy = 'PRESEEDED_ONLY'),
    CONSTRAINT ck_entity_preseeded_assignment_actor_kind
        CHECK (actor_kind = 'PLAYER'),
    CONSTRAINT ck_entity_preseeded_assignment_status
        CHECK (status IN ('PENDING', 'ASSIGNED', 'IDEMPOTENCY_CONFLICT')),
    CONSTRAINT ck_entity_preseeded_assignment_result
        CHECK (
            (status = 'PENDING' AND character_uuid IS NULL AND completed_at IS NULL)
            OR (status = 'ASSIGNED' AND character_uuid IS NOT NULL AND completed_at IS NOT NULL)
            OR (status = 'IDEMPOTENCY_CONFLICT' AND character_uuid IS NULL
                AND completed_at IS NOT NULL)
        ),
    CONSTRAINT ck_entity_preseeded_assignment_positive_versions
        CHECK (catalog_revision > 0 AND published_version_id > 0
            AND published_version_number > 0),
    CONSTRAINT ck_entity_preseeded_assignment_slugs
        CHECK (world_slug ~ '^[a-z0-9][a-z0-9-]{0,63}$'
            AND realm_slug ~ '^[a-z0-9][a-z0-9-]{0,63}$'),
    CONSTRAINT ck_entity_preseeded_assignment_game_instance
        CHECK (length(game_instance_id) > 0 AND game_instance_id = btrim(game_instance_id)),
    CONSTRAINT ck_entity_preseeded_assignment_display_name
        CHECK (length(display_name) > 0 AND display_name = btrim(display_name)),
    CONSTRAINT ux_entity_preseeded_assignment_character UNIQUE (character_uuid)
);

/* [jooq ignore start] */
ALTER TABLE entity_preseeded_actor_assignment_operations
    ADD CONSTRAINT fk_entity_preseeded_assignment_tenant_identity
        FOREIGN KEY (tenant_uuid)
        REFERENCES entity_tenant_identities (canonical_tenant_uuid)
        DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE entity_preseeded_actor_assignment_operations
    ADD CONSTRAINT fk_entity_preseeded_assignment_namespace
        FOREIGN KEY (tenant_uuid, playable_state_namespace_id, playable_state_scope)
        REFERENCES entity_playable_state_namespace_scopes (
            tenant_uuid, playable_state_namespace_id, playable_state_scope
        )
        DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE entity_preseeded_actor_assignment_operations
    ADD CONSTRAINT fk_entity_preseeded_assignment_character
        FOREIGN KEY (character_uuid)
        REFERENCES characters (character_uuid)
        DEFERRABLE INITIALLY DEFERRED;
/* [jooq ignore stop] */

CREATE INDEX idx_entity_preseeded_assignment_account_target
    ON entity_preseeded_actor_assignment_operations (
        tenant_uuid, playable_state_namespace_id, account_uuid, created_at
    );

/* [jooq ignore start] */
CREATE FUNCTION reject_entity_tenant_identity_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Entity canonical tenant mapping is immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_entity_tenant_identity_immutable
    BEFORE UPDATE OR DELETE ON entity_tenant_identities
    FOR EACH ROW
    EXECUTE FUNCTION reject_entity_tenant_identity_change();

CREATE FUNCTION reject_entity_assignment_history_removal()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Entity assignment evidence cannot be removed'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_entity_tenant_identity_no_truncate
    BEFORE TRUNCATE ON entity_tenant_identities
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_entity_assignment_history_removal();

CREATE FUNCTION protect_entity_preseeded_actor_assignment_operation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF (NEW.assignment_uuid, NEW.intent_digest, NEW.account_uuid, NEW.account_uuid_provenance,
        NEW.eligibility_evaluated_at, NEW.membership_authority_generation,
        NEW.eligibility_evidence_digest, NEW.tenant_uuid,
        NEW.realm_uuid, NEW.world_slug, NEW.realm_slug, NEW.game_instance_id, NEW.catalog_revision,
        NEW.published_version_id, NEW.published_version_number, NEW.frozen_policy_digest,
        NEW.playable_state_namespace_id, NEW.playable_state_scope, NEW.entry_policy,
        NEW.actor_kind, NEW.display_name, NEW.created_at)
        IS DISTINCT FROM
       (OLD.assignment_uuid, OLD.intent_digest, OLD.account_uuid, OLD.account_uuid_provenance,
        OLD.eligibility_evaluated_at, OLD.membership_authority_generation,
        OLD.eligibility_evidence_digest, OLD.tenant_uuid,
        OLD.realm_uuid, OLD.world_slug, OLD.realm_slug, OLD.game_instance_id, OLD.catalog_revision,
        OLD.published_version_id, OLD.published_version_number, OLD.frozen_policy_digest,
        OLD.playable_state_namespace_id, OLD.playable_state_scope, OLD.entry_policy,
        OLD.actor_kind, OLD.display_name, OLD.created_at)
       OR OLD.status <> 'PENDING'
       OR NEW.status NOT IN ('ASSIGNED', 'IDEMPOTENCY_CONFLICT') THEN
        RAISE EXCEPTION 'pre-seeded actor assignment intent is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_entity_preseeded_actor_assignment_operation_immutable
    BEFORE UPDATE ON entity_preseeded_actor_assignment_operations
    FOR EACH ROW
    EXECUTE FUNCTION protect_entity_preseeded_actor_assignment_operation();

CREATE TRIGGER trg_entity_preseeded_assignment_no_removal
    BEFORE DELETE OR TRUNCATE ON entity_preseeded_actor_assignment_operations
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_entity_assignment_history_removal();
/* [jooq ignore stop] */
