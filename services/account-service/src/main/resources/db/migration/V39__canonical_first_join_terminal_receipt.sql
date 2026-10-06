-- Extend the existing global JOIN journal and Account audit outbox in place. Retained V1
-- operation/audit representations stay numeric and byte-compatible; no V1 row is rewritten.
ALTER TABLE account_audit_outbox
    ADD COLUMN tenant_identity_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN tenant_uuid UUID;

ALTER TABLE account_audit_outbox
    ALTER COLUMN tenant_identity_version DROP DEFAULT;

/* [jooq ignore start] */
ALTER TABLE account_audit_outbox
    DROP CONSTRAINT account_audit_outbox_scope_check;

ALTER TABLE account_audit_outbox
    ADD CONSTRAINT account_audit_outbox_scope_identity_check CHECK (
        (scope = 'platform' AND tenant_identity_version = 1
            AND tenant_id IS NULL AND tenant_uuid IS NULL)
        OR (scope = 'tenant' AND tenant_identity_version = 1
            AND tenant_id IS NOT NULL AND tenant_id > 0 AND tenant_uuid IS NULL)
        OR (scope = 'tenant' AND tenant_identity_version = 2
            AND tenant_id IS NULL AND tenant_uuid IS NOT NULL
            AND tenant_uuid <> '00000000-0000-0000-0000-000000000000'::UUID)
    );
/* [jooq ignore stop] */

ALTER TABLE account_join_operations
    ADD COLUMN membership_authority_outbox_stream_key VARCHAR(2048),
    ADD COLUMN membership_authority_outbox_sequence BIGINT,
    ADD COLUMN membership_authority_event_id VARCHAR(512),
    ADD COLUMN membership_authority_event_digest VARCHAR(71),
    ADD COLUMN join_audit_event_id UUID,
    ADD COLUMN join_audit_payload_digest VARCHAR(71),
    ADD COLUMN join_audit_occurred_at TIMESTAMP;

/* [jooq ignore start] */
ALTER TABLE account_join_operations
    DROP CONSTRAINT account_join_operation_representation_check;

ALTER TABLE account_join_operations
    ADD CONSTRAINT account_join_operation_representation_check CHECK (
        (operation_representation_version = 1 AND scope_digest_version = 1
            AND target_class IS NULL AND account_uuid IS NULL AND tenant_uuid IS NULL
            AND tenant_slug IS NULL AND playable_state_namespace_uuid IS NULL
            AND game_instance_uuid IS NULL)
        OR
        (operation_representation_version = 2 AND scope_digest_version = 2
            AND request_id IS NOT NULL AND account_id IS NOT NULL
            AND verified_caller_binding IS NOT NULL AND scope_token_hash IS NOT NULL
            AND connect_scope_digest IS NOT NULL AND realm_id IS NOT NULL
            AND world_slug IS NOT NULL AND realm_slug IS NOT NULL
            AND playable_state_scope IS NOT NULL AND catalog_revision IS NOT NULL
            AND pointer_version IS NOT NULL AND intent_digest_version = 2
            AND intent_digest ~ '^sha256:[0-9a-f]{64}$'
            AND entitlement_authority_availability IS NOT NULL
            AND last_attempt_authority_availability IS NOT NULL
            AND caller_bound_authority_invalidated IS FALSE
            AND btrim(request_id) <> '' AND verified_caller_binding <> ''
            AND scope_token_hash ~ '^sha256:[0-9a-f]{64}$'
            AND target_class = 'PUBLIC_PRODUCTION'
            AND tenant_id IS NULL AND game_instance_id IS NULL
            AND playable_state_namespace_id IS NULL
            AND account_uuid IS NOT NULL
            AND account_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND tenant_uuid IS NOT NULL
            AND tenant_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND tenant_slug IS NOT NULL AND tenant_slug <> ''
            AND realm_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND world_slug <> '' AND realm_slug <> ''
            AND playable_state_namespace_uuid IS NOT NULL
            AND playable_state_namespace_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND playable_state_scope IN ('SHARED', 'ISOLATED')
            AND game_instance_uuid IS NOT NULL
            AND game_instance_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND connect_scope_digest ~ '^sha256:[0-9a-f]{64}$'
            AND ((status = 'PENDING' AND outcome IS NULL AND membership_id IS NULL
                    AND outcome_membership_version IS NULL
                    AND outcome_membership_authority_generation IS NULL)
                OR (status = 'COMMITTED' AND outcome = 'JOINED'
                    AND membership_id IS NOT NULL AND membership_id > 0
                    AND outcome_membership_version = 2
            AND outcome_membership_authority_generation = 1)))
    );
/* [jooq ignore stop] */

/* [jooq ignore start] */
ALTER TABLE account_join_operations
    ADD CONSTRAINT account_join_canonical_terminal_proof_check CHECK (
        (operation_representation_version = 1
            AND membership_authority_outbox_stream_key IS NULL
            AND membership_authority_outbox_sequence IS NULL
            AND membership_authority_event_id IS NULL
            AND membership_authority_event_digest IS NULL
            AND join_audit_event_id IS NULL
            AND join_audit_payload_digest IS NULL
            AND join_audit_occurred_at IS NULL)
        OR (operation_representation_version = 2 AND status = 'PENDING'
            AND outcome IS NULL AND membership_id IS NULL
            AND outcome_membership_version IS NULL
            AND outcome_membership_authority_generation IS NULL
            AND membership_authority_outbox_stream_key IS NULL
            AND membership_authority_outbox_sequence IS NULL
            AND membership_authority_event_id IS NULL
            AND membership_authority_event_digest IS NULL
            AND join_audit_event_id IS NULL
            AND join_audit_payload_digest IS NULL
            AND join_audit_occurred_at IS NULL)
        OR (operation_representation_version = 2 AND status = 'COMMITTED'
            AND outcome = 'JOINED'
            AND membership_id IS NOT NULL AND membership_id > 0
            AND outcome_membership_version = 2
            AND outcome_membership_authority_generation = 1
            AND membership_authority_outbox_stream_key IS NOT NULL
            AND membership_authority_outbox_stream_key =
                'account:auth-authority:v1:membership/' || account_uuid::TEXT || '/' || tenant_uuid::TEXT
            AND membership_authority_outbox_sequence IS NOT NULL
            AND membership_authority_outbox_sequence = 1
            AND membership_authority_event_id IS NOT NULL
            AND btrim(membership_authority_event_id) <> ''
            AND membership_authority_event_digest IS NOT NULL
            AND membership_authority_event_digest ~ '^sha256:[0-9a-f]{64}$'
            AND join_audit_event_id IS NOT NULL
            AND join_audit_event_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND join_audit_payload_digest IS NOT NULL
            AND join_audit_payload_digest ~ '^sha256:[0-9a-f]{64}$'
            AND join_audit_occurred_at IS NOT NULL)
    );

ALTER TABLE account_join_operations
    ADD CONSTRAINT account_join_canonical_membership_event_fk
        FOREIGN KEY (membership_authority_outbox_stream_key, membership_authority_outbox_sequence)
        REFERENCES account_authority_outbox_events (outbox_stream_key, outbox_sequence)
        ON DELETE RESTRICT,
    ADD CONSTRAINT account_join_canonical_audit_event_fk
        FOREIGN KEY (join_audit_event_id)
        REFERENCES account_audit_outbox (audit_event_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT account_join_canonical_audit_event_uq UNIQUE (join_audit_event_id);
/* [jooq ignore stop] */

-- [jooq ignore start]
CREATE FUNCTION guard_account_audit_canonical_identity() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.tenant_identity_version = 2 THEN
            RAISE EXCEPTION 'Canonical Account audit envelope cannot be deleted'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.tenant_identity_version = 2
            AND (NEW.scope IS DISTINCT FROM 'tenant'
                OR NEW.tenant_id IS NOT NULL
                OR NEW.tenant_uuid IS NULL
                OR NEW.payload IS NULL
                OR NEW.delivery_status IS DISTINCT FROM 'PENDING'
                OR NEW.receiver_receipt_id IS NOT NULL
                OR NEW.receiver_log_event_id IS NOT NULL) THEN
            RAISE EXCEPTION 'Canonical Account audit envelope must start as exact pending tenant evidence'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.tenant_identity_version = 1 THEN
        IF NEW.tenant_identity_version IS DISTINCT FROM 1
            OR NEW.tenant_uuid IS DISTINCT FROM OLD.tenant_uuid THEN
            RAISE EXCEPTION 'Retained Account audit identity cannot change representation'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.tenant_identity_version <> 2
        OR NEW.tenant_identity_version IS DISTINCT FROM OLD.tenant_identity_version
        OR NEW.audit_event_id IS DISTINCT FROM OLD.audit_event_id
        OR NEW.scope IS DISTINCT FROM OLD.scope
        OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.tenant_uuid IS DISTINCT FROM OLD.tenant_uuid
        OR NEW.producer_service IS DISTINCT FROM OLD.producer_service
        OR NEW.event_type IS DISTINCT FROM OLD.event_type
        OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
        OR NEW.schema_version IS DISTINCT FROM OLD.schema_version
        OR NEW.payload_digest_version IS DISTINCT FROM OLD.payload_digest_version
        OR NEW.payload_digest IS DISTINCT FROM OLD.payload_digest
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR (NEW.payload IS DISTINCT FROM OLD.payload
            AND NOT (OLD.payload IS NOT NULL AND NEW.payload IS NULL
                AND NEW.delivery_status = 'MINIMIZED'))
        OR (OLD.delivery_status = 'MINIMIZED'
            AND NEW.delivery_status IS DISTINCT FROM OLD.delivery_status) THEN
        RAISE EXCEPTION 'Canonical Account audit envelope bytes and identity are immutable'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_audit_canonical_identity_guard
    BEFORE INSERT OR UPDATE OR DELETE ON account_audit_outbox
    FOR EACH ROW EXECUTE FUNCTION guard_account_audit_canonical_identity();

CREATE FUNCTION guard_account_audit_canonical_truncate() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM account_audit_outbox WHERE tenant_identity_version = 2) THEN
        RAISE EXCEPTION 'Canonical Account audit history cannot be truncated'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_audit_canonical_truncate_guard
    BEFORE TRUNCATE ON account_audit_outbox
    FOR EACH STATEMENT EXECUTE FUNCTION guard_account_audit_canonical_truncate();

DROP TRIGGER account_join_operation_identity_guard ON account_join_operations;

CREATE OR REPLACE FUNCTION guard_account_join_operation_identity() RETURNS trigger AS $$
DECLARE
    scope_source_matches BOOLEAN := FALSE;
    membership_matches BOOLEAN := FALSE;
    pair_matches BOOLEAN := FALSE;
    authority_event_matches BOOLEAN := FALSE;
    audit_envelope_matches BOOLEAN := FALSE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.operation_representation_version = 2 THEN
            RAISE EXCEPTION 'Canonical JOIN operation evidence cannot be deleted'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.operation_representation_version = 1 THEN
            RETURN NEW;
        END IF;
        IF NEW.operation_representation_version IS DISTINCT FROM 2
            OR NEW.scope_digest_version IS DISTINCT FROM 2
            OR NEW.intent_digest_version IS DISTINCT FROM 2
            OR NEW.status IS DISTINCT FROM 'PENDING'
            OR NEW.outcome IS NOT NULL OR NEW.membership_id IS NOT NULL
            OR NEW.outcome_membership_version IS NOT NULL
            OR NEW.outcome_membership_authority_generation IS NOT NULL
            OR NEW.membership_authority_outbox_stream_key IS NOT NULL
            OR NEW.membership_authority_outbox_sequence IS NOT NULL
            OR NEW.membership_authority_event_id IS NOT NULL
            OR NEW.membership_authority_event_digest IS NOT NULL
            OR NEW.join_audit_event_id IS NOT NULL
            OR NEW.join_audit_payload_digest IS NOT NULL
            OR NEW.join_audit_occurred_at IS NOT NULL
            OR NEW.caller_bound_authority_invalidated IS DISTINCT FROM FALSE
            OR NEW.entitlement_authority_availability IS DISTINCT FROM 'NOT_EVALUATED'
            OR NEW.entitlement_version IS NOT NULL OR NEW.allow_public_join IS NOT NULL
            OR NEW.request_digest_version IS NOT NULL OR NEW.request_digest IS NOT NULL
            OR NEW.last_attempt_authority_availability IS DISTINCT FROM 'NOT_EVALUATED'
            OR NEW.last_attempt_failure_code IS NOT NULL THEN
            RAISE EXCEPTION 'Canonical JOIN operation must start as an exact pending intent'
                USING ERRCODE = 'check_violation';
        END IF;

        SELECT EXISTS (
            SELECT 1 FROM account_connect_scope_records scope_row
            WHERE scope_row.scope_token_hash = NEW.scope_token_hash
              AND scope_row.scope_digest_version = 2
              AND scope_row.snapshot_digest = NEW.connect_scope_digest
              AND scope_row.account_id = NEW.account_id
              AND scope_row.account_uuid = NEW.account_uuid
              AND scope_row.target_class = NEW.target_class
              AND scope_row.tenant_id IS NULL
              AND scope_row.tenant_uuid = NEW.tenant_uuid
              AND scope_row.tenant_slug = NEW.tenant_slug
              AND scope_row.realm_id = NEW.realm_id
              AND scope_row.world_slug = NEW.world_slug
              AND scope_row.realm_slug = NEW.realm_slug
              AND scope_row.playable_state_namespace_id IS NULL
              AND scope_row.playable_state_namespace_uuid = NEW.playable_state_namespace_uuid
              AND scope_row.playable_state_scope = NEW.playable_state_scope
              AND scope_row.game_instance_id IS NULL
              AND scope_row.game_instance_uuid = NEW.game_instance_uuid
              AND scope_row.catalog_revision = NEW.catalog_revision
              AND scope_row.pointer_version = NEW.pointer_version
        ) INTO scope_source_matches;
        IF NOT scope_source_matches THEN
            RAISE EXCEPTION 'Canonical JOIN operation has no exact persisted scope source'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.operation_representation_version = 1 THEN
        IF NEW.operation_representation_version IS DISTINCT FROM 1 THEN
            RAISE EXCEPTION 'Retained JOIN operation evidence cannot change representation'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.operation_representation_version <> 2
        OR NEW.operation_representation_version IS DISTINCT FROM 2
        OR NEW.request_id IS DISTINCT FROM OLD.request_id
        OR NEW.account_id IS DISTINCT FROM OLD.account_id
        OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.verified_caller_binding IS DISTINCT FROM OLD.verified_caller_binding
        OR NEW.scope_token_hash IS DISTINCT FROM OLD.scope_token_hash
        OR NEW.connect_scope_digest IS DISTINCT FROM OLD.connect_scope_digest
        OR NEW.world_slug IS DISTINCT FROM OLD.world_slug
        OR NEW.realm_slug IS DISTINCT FROM OLD.realm_slug
        OR NEW.realm_id IS DISTINCT FROM OLD.realm_id
        OR NEW.playable_state_namespace_id IS DISTINCT FROM OLD.playable_state_namespace_id
        OR NEW.playable_state_scope IS DISTINCT FROM OLD.playable_state_scope
        OR NEW.game_instance_id IS DISTINCT FROM OLD.game_instance_id
        OR NEW.catalog_revision IS DISTINCT FROM OLD.catalog_revision
        OR NEW.pointer_version IS DISTINCT FROM OLD.pointer_version
        OR NEW.intent_digest_version IS DISTINCT FROM OLD.intent_digest_version
        OR NEW.intent_digest IS DISTINCT FROM OLD.intent_digest
        OR NEW.scope_digest_version IS DISTINCT FROM OLD.scope_digest_version
        OR NEW.target_class IS DISTINCT FROM OLD.target_class
        OR NEW.account_uuid IS DISTINCT FROM OLD.account_uuid
        OR NEW.tenant_uuid IS DISTINCT FROM OLD.tenant_uuid
        OR NEW.tenant_slug IS DISTINCT FROM OLD.tenant_slug
        OR NEW.playable_state_namespace_uuid IS DISTINCT FROM OLD.playable_state_namespace_uuid
        OR NEW.game_instance_uuid IS DISTINCT FROM OLD.game_instance_uuid
        OR NEW.caller_bound_authority_invalidated IS DISTINCT FROM FALSE
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Canonical JOIN operation intent evidence is immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.status = 'COMMITTED' THEN
        RAISE EXCEPTION 'Committed canonical JOIN receipt is immutable'
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status IS DISTINCT FROM 'PENDING' THEN
        RAISE EXCEPTION 'Canonical JOIN operation has unsupported prior status'
            USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.request_digest IS NULL THEN
        IF NEW.request_digest IS NOT NULL THEN
            IF NEW.request_digest_version IS DISTINCT FROM 2
                OR NEW.entitlement_authority_availability IS DISTINCT FROM 'AVAILABLE'
                OR NEW.entitlement_version IS NULL OR NEW.entitlement_version <= 0
                OR NEW.allow_public_join IS NULL
                OR NEW.last_attempt_authority_availability IS DISTINCT FROM 'AVAILABLE'
                OR NEW.last_attempt_failure_code IS NOT NULL THEN
                RAISE EXCEPTION 'Canonical JOIN policy binding is incomplete'
                    USING ERRCODE = 'check_violation';
            END IF;
        ELSIF NEW.request_digest_version IS NOT NULL
            OR NEW.entitlement_version IS NOT NULL OR NEW.allow_public_join IS NOT NULL
            OR NEW.entitlement_authority_availability IS DISTINCT FROM 'UNAVAILABLE'
            OR NEW.last_attempt_authority_availability IS DISTINCT FROM 'UNAVAILABLE'
            OR NEW.last_attempt_failure_code IS NULL
            OR btrim(NEW.last_attempt_failure_code) = '' THEN
            RAISE EXCEPTION 'Canonical JOIN unavailable attempt cannot invent policy evidence'
                USING ERRCODE = 'check_violation';
        END IF;
    ELSE
        IF NEW.request_digest IS DISTINCT FROM OLD.request_digest
            OR NEW.request_digest_version IS DISTINCT FROM OLD.request_digest_version
            OR NEW.entitlement_authority_availability IS DISTINCT FROM OLD.entitlement_authority_availability
            OR NEW.entitlement_version IS DISTINCT FROM OLD.entitlement_version
            OR NEW.allow_public_join IS DISTINCT FROM OLD.allow_public_join
            OR NEW.last_attempt_authority_availability IS DISTINCT FROM OLD.last_attempt_authority_availability
            OR NEW.last_attempt_failure_code IS DISTINCT FROM OLD.last_attempt_failure_code THEN
            RAISE EXCEPTION 'Canonical JOIN available policy evidence is immutable'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    IF NEW.status = 'PENDING' THEN
        IF NEW.outcome IS NOT NULL OR NEW.membership_id IS NOT NULL
            OR NEW.outcome_membership_version IS NOT NULL
            OR NEW.outcome_membership_authority_generation IS NOT NULL
            OR NEW.membership_authority_outbox_stream_key IS NOT NULL
            OR NEW.membership_authority_outbox_sequence IS NOT NULL
            OR NEW.membership_authority_event_id IS NOT NULL
            OR NEW.membership_authority_event_digest IS NOT NULL
            OR NEW.join_audit_event_id IS NOT NULL
            OR NEW.join_audit_payload_digest IS NOT NULL
            OR NEW.join_audit_occurred_at IS NOT NULL THEN
            RAISE EXCEPTION 'Pending canonical JOIN cannot carry terminal receipt evidence'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.status IS DISTINCT FROM 'COMMITTED'
        OR NEW.outcome IS DISTINCT FROM 'JOINED'
        OR NEW.entitlement_authority_availability IS DISTINCT FROM 'AVAILABLE'
        OR NEW.allow_public_join IS DISTINCT FROM TRUE
        OR NEW.entitlement_version IS NULL OR NEW.entitlement_version <= 0
        OR NEW.request_digest_version IS DISTINCT FROM 2
        OR NEW.request_digest IS NULL
        OR NEW.request_digest !~ '^sha256:[0-9a-f]{64}$'
        OR NEW.last_attempt_failure_code IS NOT NULL
        OR NEW.membership_id IS NULL
        OR NEW.outcome_membership_version IS DISTINCT FROM 2
        OR NEW.outcome_membership_authority_generation IS DISTINCT FROM 1
        OR NEW.membership_authority_outbox_stream_key IS NULL
        OR NEW.membership_authority_outbox_sequence IS DISTINCT FROM 1
        OR NEW.membership_authority_event_id IS NULL
        OR NEW.membership_authority_event_digest IS NULL
        OR NEW.membership_authority_event_digest !~ '^sha256:[0-9a-f]{64}$'
        OR NEW.join_audit_event_id IS NULL
        OR NEW.join_audit_payload_digest IS NULL
        OR NEW.join_audit_payload_digest !~ '^sha256:[0-9a-f]{64}$'
        OR NEW.join_audit_occurred_at IS NULL THEN
        RAISE EXCEPTION 'Canonical JOIN terminal receipt is incomplete or noncommitting'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT EXISTS (
        SELECT 1 FROM account_tenant_membership membership
        WHERE membership.id = NEW.membership_id
          AND membership.account_id = NEW.account_id
          AND membership.tenant_uuid = NEW.tenant_uuid
          AND membership.tenant_id IS NULL
          AND membership.tenant_provenance_kind = 'FRESH_GAME_DESIGN'
          AND membership.lifecycle_state = 'ACTIVE'
          AND membership.gameplay_admission_allowed IS TRUE
          AND membership.authority_provenance = 'EXPLICIT_JOIN'
          AND membership.membership_version = NEW.outcome_membership_version
          AND membership.membership_authority_generation =
              NEW.outcome_membership_authority_generation
    ) INTO membership_matches;

    SELECT EXISTS (
        SELECT 1 FROM account_membership_pair_authority pair
        JOIN account_tenant_membership membership
          ON membership.id = NEW.membership_id
        WHERE pair.account_uuid = NEW.account_uuid
          AND pair.tenant_uuid = NEW.tenant_uuid
          AND pair.membership_exists IS TRUE
          AND pair.membership_version = NEW.outcome_membership_version
          AND pair.membership_authority_generation =
              NEW.outcome_membership_authority_generation
          AND pair.last_event_sequence = NEW.membership_authority_outbox_sequence
          AND pair.last_event_id = NEW.membership_authority_event_id
          AND pair.last_event_digest = NEW.membership_authority_event_digest
          AND pair.last_transition_invalidated IS FALSE
          AND pair.tenant_provenance_kind = membership.tenant_provenance_kind
          AND pair.tenant_source_operation_id = membership.tenant_source_operation_id
          AND pair.tenant_provenance_digest = membership.tenant_provenance_digest
    ) INTO pair_matches;

    SELECT EXISTS (
        SELECT 1 FROM account_authority_outbox_events event
        JOIN account_authority_outbox_streams stream
          ON stream.outbox_stream_key = event.outbox_stream_key
        WHERE event.outbox_stream_key = NEW.membership_authority_outbox_stream_key
          AND event.outbox_sequence = NEW.membership_authority_outbox_sequence
          AND event.request_id = NEW.request_id
          AND event.event_id = NEW.membership_authority_event_id
          AND event.event_digest = NEW.membership_authority_event_digest
          AND stream.last_sequence = NEW.membership_authority_outbox_sequence
    ) INTO authority_event_matches;

    SELECT EXISTS (
        SELECT 1 FROM account_audit_outbox audit
        WHERE audit.audit_event_id = NEW.join_audit_event_id
          AND audit.scope = 'tenant'
          AND audit.tenant_identity_version = 2
          AND audit.tenant_id IS NULL
          AND audit.tenant_uuid = NEW.tenant_uuid
          AND audit.producer_service = 'account-service'
          AND audit.event_type = 'ACCOUNT_JOINED_PUBLIC_PRODUCTION'
          AND audit.schema_version = 1
          AND audit.payload_digest_version = 1
          AND audit.payload_digest = NEW.join_audit_payload_digest
          AND audit.occurred_at = NEW.join_audit_occurred_at
          AND audit.delivery_status = 'PENDING'
          AND audit.payload IS NOT NULL
          AND audit.payload::JSONB ->> 'accountId' = NEW.account_uuid::TEXT
          AND audit.payload::JSONB ->> 'tenantId' = NEW.tenant_uuid::TEXT
          AND audit.payload::JSONB ->> 'requestId' = NEW.request_id
          AND audit.payload::JSONB ->> 'worldSlug' = NEW.world_slug
          AND audit.payload::JSONB ->> 'realmSlug' = NEW.realm_slug
          AND audit.payload::JSONB ->> 'membershipVersion' = '2'
          AND audit.payload::JSONB ->> 'membershipAuthorityGeneration' = '1'
          AND audit.payload::JSONB ->> 'membershipEventStreamKey' =
              NEW.membership_authority_outbox_stream_key
          AND audit.payload::JSONB ->> 'membershipEventSequence' =
              NEW.membership_authority_outbox_sequence::TEXT
          AND audit.payload::JSONB ->> 'membershipEventId' = NEW.membership_authority_event_id
          AND audit.payload::JSONB ->> 'membershipEventDigest' =
              NEW.membership_authority_event_digest
          AND audit.payload::JSONB ->> 'joinIntentDigest' = NEW.intent_digest
          AND audit.payload::JSONB ->> 'joinRequestDigest' = NEW.request_digest
          AND audit.payload::JSONB ->> 'allowPublicJoin' = 'true'
          AND audit.payload::JSONB ->> 'entitlementVersion' = NEW.entitlement_version::TEXT
    ) INTO audit_envelope_matches;

    IF NOT membership_matches OR NOT pair_matches OR NOT authority_event_matches
        OR NOT audit_envelope_matches THEN
        RAISE EXCEPTION 'Canonical JOIN terminal receipt does not match committed owner evidence'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_join_operation_identity_guard
    BEFORE INSERT OR UPDATE OR DELETE ON account_join_operations
    FOR EACH ROW EXECUTE FUNCTION guard_account_join_operation_identity();
-- [jooq ignore stop]
