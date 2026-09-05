-- Bind each event-scope claim to the immutable semantic request that first owned its
-- producer-namespaced scriptEventId. Existing pre-v1 rows remain explicitly unbound and are
-- replay-rejected until reconciled; they must never silently accept changed request inputs.
ALTER TABLE script_event_ingress_audit
    ADD COLUMN request_digest VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE script_event_ingress_audit
    ADD CONSTRAINT ck_script_event_ingress_audit_request_digest CHECK (
           request_digest = '' OR request_digest ~ '^[0-9a-f]{64}$'
    );

-- The control-plane request ID proves the owner observation but is not a claim identity
-- dimension. Rebuild the pinned identity index without it so a changed ID reaches the persisted
-- digest comparison and is rejected as an idempotency conflict instead of creating a new claim.
DROP INDEX uq_script_event_ingress_audit_runtime_identity;
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
    script_event_id,
    dry_run,
    source_service
) WHERE game_instance_id IS NOT NULL AND script_pin_epoch IS NOT NULL;
