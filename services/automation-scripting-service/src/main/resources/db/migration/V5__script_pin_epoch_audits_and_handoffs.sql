-- Persist the exact observed owner pin epoch with ingress, execution, and handoff evidence.
-- Audit rows may omit the epoch for tenant-readiness or rejected pre-pin requests; handoff rows
-- remain fail-closed at zero until an instance-scoped pin is observed.
ALTER TABLE script_event_ingress_audit
    ADD COLUMN script_pin_epoch BIGINT;

ALTER TABLE script_event_ingress_audit
    ADD COLUMN script_pin_control_plane_request_id VARCHAR(256);

-- A bounded claim lease lets a later retry recover a crashed claimant while optimistic row
-- versioning fences the abandoned owner. Keep created_at as the retention anchor.
ALTER TABLE script_event_ingress_audit
    ADD COLUMN claim_started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE script_event_ingress_audit
    ALTER COLUMN playable_state_scope DROP NOT NULL;
ALTER TABLE script_event_ingress_audit
    ALTER COLUMN world_slug DROP NOT NULL;
ALTER TABLE script_event_ingress_audit
    ALTER COLUMN realm_slug DROP NOT NULL;
ALTER TABLE script_event_ingress_audit
    ALTER COLUMN pointer_version DROP NOT NULL;

ALTER TABLE script_event_ingress_audit
    DROP CONSTRAINT uq_script_event_ingress_audit_identity;

-- Runtime-scoped identities use explicit empty/zero sentinels for unspecified scope values.
-- Normalize pre-existing nullable runtime rows before the unique indexes are created; otherwise
-- PostgreSQL treats NULL identity values as distinct and permits duplicate identities. Keep
-- pre-instance onLoad rows nullable because they use a separate identity branch below.
UPDATE script_event_ingress_audit
SET region_id = COALESCE(region_id, ''),
    region_epoch = COALESCE(region_epoch, 0),
    playable_state_scope = COALESCE(playable_state_scope, '')
WHERE game_instance_id IS NOT NULL
  AND (region_id IS NULL OR region_epoch IS NULL OR playable_state_scope IS NULL);

-- The normalization above can make a legacy nullable row collide with a canonical row (or make
-- two legacy rows collide). Retain a deterministic winner before building either runtime index;
-- completed evidence wins over an abandoned in-progress claim, then the lowest id wins ties.
DELETE FROM script_event_ingress_audit
WHERE id IN (
    SELECT ranked.id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY tenant_id,
                                game_instance_id,
                                region_id,
                                region_epoch,
                                entity_id,
                                playable_state_scope,
                                event_type,
                                event_schema_version,
                                script_patch_version,
                                script_pin_epoch,
                                script_pin_control_plane_request_id,
                                script_event_id,
                                dry_run,
                                source_service
                   ORDER BY CASE WHEN source_state = 'IN_PROGRESS' THEN 1 ELSE 0 END, id
               ) AS duplicate_rank
        FROM script_event_ingress_audit
        WHERE game_instance_id IS NOT NULL
          AND script_pin_epoch IS NOT NULL
    ) AS ranked
    WHERE ranked.duplicate_rank > 1
);

DELETE FROM script_event_ingress_audit
WHERE id IN (
    SELECT ranked.id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY tenant_id,
                                game_instance_id,
                                region_id,
                                region_epoch,
                                entity_id,
                                playable_state_scope,
                                event_type,
                                event_schema_version,
                                script_patch_version,
                                script_event_id,
                                dry_run,
                                source_service
                   ORDER BY CASE WHEN source_state = 'IN_PROGRESS' THEN 1 ELSE 0 END, id
               ) AS duplicate_rank
        FROM script_event_ingress_audit
        WHERE game_instance_id IS NOT NULL
          AND script_pin_epoch IS NULL
    ) AS ranked
    WHERE ranked.duplicate_rank > 1
);

CREATE UNIQUE INDEX uq_script_event_ingress_audit_runtime_identity ON script_event_ingress_audit (
    tenant_id,
    game_instance_id,
    region_id,
    region_epoch,
    entity_id,
    playable_state_scope,
    event_type,
    event_schema_version,
    script_patch_version,
    script_pin_epoch,
    script_pin_control_plane_request_id,
    script_event_id,
    dry_run,
    source_service
) WHERE game_instance_id IS NOT NULL AND script_pin_epoch IS NOT NULL;

ALTER TABLE script_event_ingress_audit
    ADD CONSTRAINT ck_script_event_ingress_audit_pin_tuple CHECK (
        (script_pin_epoch IS NULL
            AND NULLIF(BTRIM(script_pin_control_plane_request_id), '') IS NULL)
        OR (script_pin_epoch > 0
            AND NULLIF(BTRIM(script_pin_control_plane_request_id), '') IS NOT NULL)
    );

-- Rejected instance-scoped requests may omit the epoch; keep that explicit null branch
-- atomically idempotent without collapsing it into a sentinel value.
CREATE UNIQUE INDEX uq_script_event_ingress_audit_runtime_unpinned_identity
ON script_event_ingress_audit (
    tenant_id,
    game_instance_id,
    region_id,
    region_epoch,
    entity_id,
    playable_state_scope,
    event_type,
    event_schema_version,
    script_patch_version,
    script_event_id,
    dry_run,
    source_service
) WHERE game_instance_id IS NOT NULL AND script_pin_epoch IS NULL;

CREATE UNIQUE INDEX uq_script_event_ingress_audit_onload_identity ON script_event_ingress_audit (
    tenant_id,
    script_id,
    event_type,
    event_schema_version,
    script_patch_version,
    script_event_id,
    dry_run,
    source_service
) WHERE game_instance_id IS NULL AND script_pin_epoch IS NULL;

ALTER TABLE script_event_audit
    ADD COLUMN script_pin_epoch BIGINT;

ALTER TABLE script_event_audit
    ADD COLUMN script_pin_control_plane_request_id VARCHAR(256);

ALTER TABLE script_event_audit
    DROP CONSTRAINT uq_script_event_audit_handler_identity;

CREATE UNIQUE INDEX uq_script_event_audit_handler_identity ON script_event_audit (
        tenant_id,
        game_instance_id,
        region_id,
        region_epoch,
        entity_id,
        playable_state_scope,
        world_slug,
        realm_slug,
        pointer_version,
        script_id,
        event_type,
        event_schema_version,
        script_patch_version,
        script_pin_epoch,
        script_pin_control_plane_request_id,
        script_event_id,
        dry_run
    ) WHERE script_pin_epoch > 0;

CREATE UNIQUE INDEX uq_script_event_audit_handler_identity_unpinned ON script_event_audit (
        tenant_id,
        game_instance_id,
        region_id,
        region_epoch,
        entity_id,
        playable_state_scope,
        world_slug,
        realm_slug,
        pointer_version,
        script_id,
        event_type,
        event_schema_version,
        script_patch_version,
        script_event_id,
        dry_run
    ) WHERE script_pin_epoch IS NULL;

ALTER TABLE script_event_audit
    ADD CONSTRAINT ck_script_event_audit_pin_tuple CHECK (
        (script_pin_epoch IS NULL
            AND NULLIF(BTRIM(script_pin_control_plane_request_id), '') IS NULL)
        OR (script_pin_epoch > 0
            AND NULLIF(BTRIM(script_pin_control_plane_request_id), '') IS NOT NULL)
    );
