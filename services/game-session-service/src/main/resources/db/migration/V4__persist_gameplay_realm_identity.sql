-- A tenant's shared playable-state namespace is allocated once and reused by every
-- SHARED realm. Isolated realms receive independent namespace UUIDs.
CREATE TABLE gameplay_tenant_shared_playable_state_namespace (
    tenant_id bigint PRIMARY KEY,
    playable_state_namespace_id uuid NOT NULL UNIQUE,
    allocated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO gameplay_tenant_shared_playable_state_namespace (
    tenant_id,
    playable_state_namespace_id
)
SELECT shared_tenants.tenant_id, gen_random_uuid()
FROM (
    SELECT DISTINCT pointer.tenant_id
    FROM gameplay_admission_pointer pointer
    WHERE pointer.state_scope = 'SHARED'
) shared_tenants;

ALTER TABLE gameplay_admission_pointer
    ADD COLUMN realm_id uuid,
    ADD COLUMN playable_state_namespace_id uuid;

-- Retained rows with an exact known state scope can be classified without using
-- the replaceable game_instance_id as identity. Unknown legacy values remain
-- explicitly unresolved below.
UPDATE gameplay_admission_pointer
SET realm_id = gen_random_uuid(),
    playable_state_namespace_id = (
        SELECT shared_namespace.playable_state_namespace_id
        FROM gameplay_tenant_shared_playable_state_namespace shared_namespace
        WHERE shared_namespace.tenant_id = gameplay_admission_pointer.tenant_id
    )
WHERE state_scope = 'SHARED';

UPDATE gameplay_admission_pointer
SET realm_id = gen_random_uuid(),
    playable_state_namespace_id = gen_random_uuid()
WHERE state_scope = 'ISOLATED';

CREATE TABLE gameplay_admission_pointer_identity_backfill_issue (
    pointer_id bigint PRIMARY KEY,
    tenant_id bigint NOT NULL,
    world_slug character varying(120) NOT NULL,
    realm_slug character varying(120) NOT NULL,
    prior_state_scope character varying(32),
    issue_code character varying(64) NOT NULL,
    recorded_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO gameplay_admission_pointer_identity_backfill_issue (
    pointer_id,
    tenant_id,
    world_slug,
    realm_slug,
    prior_state_scope,
    issue_code
)
SELECT id,
       tenant_id,
       world_slug,
       realm_slug,
       state_scope,
       'UNKNOWN_PLAYABLE_STATE_SCOPE'
FROM gameplay_admission_pointer
WHERE state_scope IS NULL OR state_scope NOT IN ('SHARED', 'ISOLATED');

ALTER TABLE gameplay_admission_pointer
    ADD CONSTRAINT gameplay_admission_pointer_identity_pair_complete
        CHECK ((realm_id IS NULL) = (playable_state_namespace_id IS NULL));

CREATE UNIQUE INDEX uq_gameplay_admission_pointer_realm_id
    ON gameplay_admission_pointer (realm_id)
    WHERE realm_id IS NOT NULL;
