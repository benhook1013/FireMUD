-- Explicit, protected-fixture-only non-paid entitlement state. Tenant identity is UUID-native;
-- source row IDs are retained solely as authenticated provenance.
CREATE TABLE account_tenant_entitlement_outbox_streams (
    tenant_uuid UUID PRIMARY KEY
        REFERENCES account_fresh_tenant_identity_associations (canonical_tenant_id) ON DELETE RESTRICT,
    last_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_sequence >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE account_tenant_entitlement_outbox_events (
    tenant_uuid UUID NOT NULL,
    tenant_billing_sequence BIGINT NOT NULL CHECK (tenant_billing_sequence > 0),
    request_id UUID NOT NULL CHECK (request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    event_id UUID NOT NULL UNIQUE CHECK (event_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    event_digest VARCHAR(71) NOT NULL CHECK (event_digest ~ '^sha256:[0-9a-f]{64}$'),
    payload BYTEA NOT NULL CHECK (octet_length(payload) BETWEEN 1 AND 32768),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_uuid, tenant_billing_sequence),
    UNIQUE (tenant_uuid, request_id),
    UNIQUE (tenant_uuid, tenant_billing_sequence, event_id, event_digest),
    FOREIGN KEY (tenant_uuid) REFERENCES account_tenant_entitlement_outbox_streams (tenant_uuid)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE account_demo_tenant_entitlement_operations (
    request_id UUID PRIMARY KEY CHECK (request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    tenant_uuid UUID NOT NULL
        REFERENCES account_fresh_tenant_identity_associations (canonical_tenant_id) ON DELETE RESTRICT,
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    source_creation_request_id UUID NOT NULL,
    source_request_digest VARCHAR(71) NOT NULL CHECK (source_request_digest ~ '^sha256:[0-9a-f]{64}$'),
    source_operation_id UUID NOT NULL,
    source_evidence_digest VARCHAR(71) NOT NULL CHECK (source_evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'COMMITTED')),
    tenant_billing_sequence BIGINT,
    event_id UUID,
    event_digest VARCHAR(71),
    entitlement_version BIGINT,
    tenant_authority_generation BIGINT,
    tenant_authority_source_version BIGINT,
    committed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_demo_entitlement_operation_receipt_shape CHECK (
        (status = 'PENDING' AND tenant_billing_sequence IS NULL AND event_id IS NULL
            AND event_digest IS NULL AND entitlement_version IS NULL
            AND tenant_authority_generation IS NULL AND tenant_authority_source_version IS NULL
            AND committed_at IS NULL)
        OR
        (status = 'COMMITTED' AND tenant_billing_sequence IS NOT NULL AND tenant_billing_sequence > 0
            AND event_id IS NOT NULL
            AND event_digest IS NOT NULL AND event_digest ~ '^sha256:[0-9a-f]{64}$'
            AND entitlement_version IS NOT NULL AND entitlement_version > 0
            AND tenant_authority_generation IS NOT NULL AND tenant_authority_generation > 0
            AND tenant_authority_source_version IS NOT NULL AND tenant_authority_source_version > 0
            AND committed_at IS NOT NULL)
    ),
    UNIQUE (tenant_uuid, request_id),
    FOREIGN KEY (tenant_uuid, tenant_billing_sequence, event_id, event_digest)
        REFERENCES account_tenant_entitlement_outbox_events
            (tenant_uuid, tenant_billing_sequence, event_id, event_digest)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE account_demo_tenant_entitlements (
    tenant_uuid UUID PRIMARY KEY
        REFERENCES account_fresh_tenant_identity_associations (canonical_tenant_id) ON DELETE RESTRICT,
    source_schema_version INTEGER NOT NULL CHECK (source_schema_version = 1),
    source_target_namespace VARCHAR(128) NOT NULL
        CHECK (source_target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    source_creation_request_id UUID NOT NULL,
    source_operation_id UUID NOT NULL,
    source_request_digest VARCHAR(71) NOT NULL CHECK (source_request_digest ~ '^sha256:[0-9a-f]{64}$'),
    source_game_row_id BIGINT NOT NULL CHECK (source_game_row_id > 0),
    source_game_tenant_key VARCHAR(36) NOT NULL CHECK (char_length(source_game_tenant_key) BETWEEN 1 AND 36),
    source_provenance_kind VARCHAR(32) NOT NULL CHECK (source_provenance_kind = 'NEW_GAME_ROW'),
    source_evidence_digest VARCHAR(71) NOT NULL CHECK (source_evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    entitlement_kind VARCHAR(32) NOT NULL DEFAULT 'NON_PAID_DEMO' CHECK (entitlement_kind = 'NON_PAID_DEMO'),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status = 'ACTIVE'),
    subscription_status VARCHAR(32) CHECK (subscription_status IS NULL),
    paid BOOLEAN NOT NULL DEFAULT FALSE CHECK (paid = FALSE),
    gameplay_available BOOLEAN NOT NULL,
    allow_public_join BOOLEAN NOT NULL,
    allow_new_gameplay_bindings BOOLEAN NOT NULL,
    allow_new_instance_starts BOOLEAN NOT NULL,
    max_active_sessions BIGINT NOT NULL CHECK (max_active_sessions >= 0),
    max_concurrent_game_instances BIGINT NOT NULL CHECK (max_concurrent_game_instances >= 0),
    max_storage_bytes BIGINT NOT NULL CHECK (max_storage_bytes >= 0),
    entitlement_version BIGINT NOT NULL CHECK (entitlement_version > 0),
    tenant_authority_generation BIGINT NOT NULL CHECK (tenant_authority_generation > 0),
    tenant_authority_source_version BIGINT NOT NULL CHECK (tenant_authority_source_version > 0),
    tenant_billing_sequence BIGINT NOT NULL CHECK (tenant_billing_sequence > 0),
    event_id UUID NOT NULL,
    event_digest VARCHAR(71) NOT NULL CHECK (event_digest ~ '^sha256:[0-9a-f]{64}$'),
    last_request_id UUID NOT NULL,
    last_request_digest VARCHAR(71) NOT NULL CHECK (last_request_digest ~ '^sha256:[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (tenant_uuid, tenant_billing_sequence, event_id, event_digest)
        REFERENCES account_tenant_entitlement_outbox_events
            (tenant_uuid, tenant_billing_sequence, event_id, event_digest)
        DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (tenant_uuid, last_request_id)
        REFERENCES account_demo_tenant_entitlement_operations (tenant_uuid, request_id)
        DEFERRABLE INITIALLY DEFERRED
);

-- [jooq ignore start]
CREATE FUNCTION account_tenant_entitlement_stream_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.last_sequence <> 0 THEN
            RAISE EXCEPTION 'Tenant entitlement stream must begin at sequence zero'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP <> 'UPDATE'
        OR NEW.tenant_uuid IS DISTINCT FROM OLD.tenant_uuid
        OR NEW.last_sequence <> OLD.last_sequence + 1 THEN
        RAISE EXCEPTION 'Tenant entitlement stream is immutable and advances by exactly one'
            USING ERRCODE = '23514';
    END IF;
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION account_tenant_entitlement_outbox_event_guard() RETURNS trigger AS $$
DECLARE
    current_sequence BIGINT;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Tenant entitlement outbox events are immutable'
            USING ERRCODE = '23514';
    END IF;
    SELECT last_sequence INTO current_sequence FROM account_tenant_entitlement_outbox_streams
        WHERE tenant_uuid = NEW.tenant_uuid;
    IF current_sequence IS DISTINCT FROM NEW.tenant_billing_sequence THEN
        RAISE EXCEPTION 'Tenant entitlement event does not match its stream checkpoint'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION account_tenant_entitlement_stream_consistency() RETURNS trigger AS $$
DECLARE
    stream_tenant UUID;
    stream_sequence BIGINT;
    max_sequence BIGINT;
    event_count BIGINT;
BEGIN
    stream_tenant := COALESCE(NEW.tenant_uuid, OLD.tenant_uuid);
    SELECT last_sequence INTO stream_sequence FROM account_tenant_entitlement_outbox_streams
        WHERE tenant_uuid = stream_tenant;
    SELECT COALESCE(MAX(tenant_billing_sequence), 0), COUNT(*)
        INTO max_sequence, event_count FROM account_tenant_entitlement_outbox_events
        WHERE tenant_uuid = stream_tenant;
    IF stream_sequence IS NULL OR stream_sequence <> max_sequence OR stream_sequence <> event_count THEN
        RAISE EXCEPTION 'Tenant entitlement outbox stream and events are inconsistent'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION account_demo_entitlement_operation_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION 'Demo entitlement operation must start pending'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP <> 'UPDATE' OR OLD.status <> 'PENDING' OR NEW.status <> 'COMMITTED'
        OR NEW.request_id IS DISTINCT FROM OLD.request_id
        OR NEW.tenant_uuid IS DISTINCT FROM OLD.tenant_uuid
        OR NEW.request_digest IS DISTINCT FROM OLD.request_digest
        OR NEW.source_creation_request_id IS DISTINCT FROM OLD.source_creation_request_id
        OR NEW.source_request_digest IS DISTINCT FROM OLD.source_request_digest
        OR NEW.source_operation_id IS DISTINCT FROM OLD.source_operation_id
        OR NEW.source_evidence_digest IS DISTINCT FROM OLD.source_evidence_digest THEN
        RAISE EXCEPTION 'Demo entitlement operation identity is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION account_demo_entitlement_current_guard() RETURNS trigger AS $$
DECLARE
    authority_generation BIGINT;
    authority_source_version BIGINT;
    source_matches BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Demo entitlement state cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.tenant_uuid IS DISTINCT FROM OLD.tenant_uuid
            OR NEW.source_schema_version IS DISTINCT FROM OLD.source_schema_version
            OR NEW.source_target_namespace IS DISTINCT FROM OLD.source_target_namespace
            OR NEW.source_creation_request_id IS DISTINCT FROM OLD.source_creation_request_id
            OR NEW.source_operation_id IS DISTINCT FROM OLD.source_operation_id
            OR NEW.source_request_digest IS DISTINCT FROM OLD.source_request_digest
            OR NEW.source_game_row_id IS DISTINCT FROM OLD.source_game_row_id
            OR NEW.source_game_tenant_key IS DISTINCT FROM OLD.source_game_tenant_key
            OR NEW.source_provenance_kind IS DISTINCT FROM OLD.source_provenance_kind
            OR NEW.source_evidence_digest IS DISTINCT FROM OLD.source_evidence_digest
            OR NEW.entitlement_version <> OLD.entitlement_version + 1 THEN
            RAISE EXCEPTION 'Demo entitlement identity is immutable and version advances by one'
                USING ERRCODE = '23514';
        END IF;
        NEW.updated_at = CURRENT_TIMESTAMP;
    ELSIF NEW.entitlement_version <> 1 THEN
        RAISE EXCEPTION 'Demo entitlement must begin at version one'
            USING ERRCODE = '23514';
    END IF;
    SELECT generation, source_version INTO authority_generation, authority_source_version
        FROM account_authority_generations
        WHERE scope_kind = 'TENANT' AND tenant_uuid = NEW.tenant_uuid;
    IF authority_generation IS DISTINCT FROM NEW.tenant_authority_generation
        OR authority_source_version IS DISTINCT FROM NEW.tenant_authority_source_version THEN
        RAISE EXCEPTION 'Demo entitlement is not fenced by current tenant authority'
            USING ERRCODE = '23514';
    END IF;
    SELECT EXISTS (
        SELECT 1 FROM account_fresh_tenant_identity_associations fresh
        WHERE fresh.canonical_tenant_id = NEW.tenant_uuid
          AND fresh.schema_version = NEW.source_schema_version
          AND fresh.target_namespace = NEW.source_target_namespace
          AND fresh.creation_request_id = NEW.source_creation_request_id
          AND fresh.operation_id = NEW.source_operation_id
          AND fresh.request_digest = NEW.source_request_digest
          AND fresh.source_game_row_id = NEW.source_game_row_id
          AND fresh.source_game_tenant_key = NEW.source_game_tenant_key
          AND fresh.provenance_kind = NEW.source_provenance_kind
          AND fresh.evidence_digest = NEW.source_evidence_digest
    ) INTO source_matches;
    IF NOT source_matches THEN
        RAISE EXCEPTION 'Demo entitlement requires exact fresh Game Design source evidence'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION account_demo_entitlement_operation_terminal_guard() RETURNS trigger AS $$
DECLARE
    operation_status VARCHAR(16);
    operation_tenant UUID;
    operation_sequence BIGINT;
    operation_event_id UUID;
    operation_event_digest VARCHAR(71);
    operation_request_digest VARCHAR(71);
    operation_entitlement_version BIGINT;
    operation_authority_generation BIGINT;
    operation_authority_source_version BIGINT;
    operation_source_creation_request_id UUID;
    operation_source_request_digest VARCHAR(71);
    operation_source_operation_id UUID;
    operation_source_evidence_digest VARCHAR(71);
    event_payload JSONB;
BEGIN
    SELECT status, tenant_uuid, tenant_billing_sequence, event_id, event_digest, request_digest,
           entitlement_version, tenant_authority_generation, tenant_authority_source_version,
           source_creation_request_id, source_request_digest, source_operation_id,
           source_evidence_digest
        INTO operation_status, operation_tenant, operation_sequence, operation_event_id,
             operation_event_digest, operation_request_digest, operation_entitlement_version,
             operation_authority_generation, operation_authority_source_version,
             operation_source_creation_request_id, operation_source_request_digest,
             operation_source_operation_id, operation_source_evidence_digest
        FROM account_demo_tenant_entitlement_operations
        WHERE request_id = NEW.request_id;
    IF operation_status IS DISTINCT FROM 'COMMITTED' THEN
        RAISE EXCEPTION 'Demo entitlement operation cannot commit pending'
            USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(event.payload, 'UTF8')::JSONB INTO event_payload
        FROM account_tenant_entitlement_outbox_events event
        WHERE event.tenant_uuid = operation_tenant
          AND event.tenant_billing_sequence = operation_sequence
          AND event.event_id = operation_event_id
          AND event.event_digest = operation_event_digest;
    IF event_payload IS NULL
        OR event_payload->>'requestId' IS DISTINCT FROM NEW.request_id::TEXT
        OR event_payload->>'requestDigest' IS DISTINCT FROM operation_request_digest
        OR event_payload->>'tenantId' IS DISTINCT FROM operation_tenant::TEXT
        OR event_payload->>'tenantBillingSequence' IS DISTINCT FROM operation_sequence::TEXT
        OR event_payload->>'eventId' IS DISTINCT FROM operation_event_id::TEXT
        OR event_payload->>'eventDigest' IS DISTINCT FROM operation_event_digest
        OR event_payload->>'entitlementVersion' IS DISTINCT FROM operation_entitlement_version::TEXT
        OR event_payload->>'tenantAuthorityGeneration'
            IS DISTINCT FROM operation_authority_generation::TEXT
        OR event_payload->>'tenantAuthoritySourceVersion'
            IS DISTINCT FROM operation_authority_source_version::TEXT
        OR event_payload->'sourceEvidence'->>'creationRequestId'
            IS DISTINCT FROM operation_source_creation_request_id::TEXT
        OR event_payload->'sourceEvidence'->>'requestDigest'
            IS DISTINCT FROM operation_source_request_digest
        OR event_payload->'sourceEvidence'->>'operationId'
            IS DISTINCT FROM operation_source_operation_id::TEXT
        OR event_payload->'sourceEvidence'->>'evidenceDigest'
            IS DISTINCT FROM operation_source_evidence_digest THEN
        RAISE EXCEPTION 'Demo entitlement operation receipt differs from its exact event'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM account_fresh_tenant_identity_associations fresh
        WHERE fresh.canonical_tenant_id = operation_tenant
          AND fresh.creation_request_id = operation_source_creation_request_id
          AND fresh.request_digest = operation_source_request_digest
          AND fresh.operation_id = operation_source_operation_id
          AND fresh.evidence_digest = operation_source_evidence_digest
          AND fresh.provenance_kind = 'NEW_GAME_ROW'
    ) THEN
        RAISE EXCEPTION 'Demo entitlement operation has no exact fresh tenant source'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION account_demo_entitlement_current_event_guard() RETURNS trigger AS $$
DECLARE
    payload JSONB;
BEGIN
    SELECT convert_from(event.payload, 'UTF8')::JSONB INTO payload
        FROM account_tenant_entitlement_outbox_events event
        WHERE event.tenant_uuid = NEW.tenant_uuid
          AND event.tenant_billing_sequence = NEW.tenant_billing_sequence
          AND event.event_id = NEW.event_id
          AND event.event_digest = NEW.event_digest;
    IF payload IS NULL
        OR payload->>'schemaVersion' IS DISTINCT FROM 'account-tenant-entitlement-event/v1'
        OR payload->>'eventType' IS DISTINCT FROM 'TENANT_ENTITLEMENT_CHANGED'
        OR payload->>'outboxStreamKey' IS DISTINCT FROM
            ('account:tenant-entitlement:v1:tenant/' || NEW.tenant_uuid::TEXT)
        OR payload->>'tenantId' IS DISTINCT FROM NEW.tenant_uuid::TEXT
        OR payload->>'tenantBillingSequence' IS DISTINCT FROM NEW.tenant_billing_sequence::TEXT
        OR payload->>'eventId' IS DISTINCT FROM NEW.event_id::TEXT
        OR payload->>'eventDigest' IS DISTINCT FROM NEW.event_digest
        OR payload->>'requestId' IS DISTINCT FROM NEW.last_request_id::TEXT
        OR payload->>'requestDigest' IS DISTINCT FROM NEW.last_request_digest
        OR payload->>'entitlementVersion' IS DISTINCT FROM NEW.entitlement_version::TEXT
        OR payload->>'tenantAuthorityGeneration' IS DISTINCT FROM NEW.tenant_authority_generation::TEXT
        OR payload->>'tenantAuthoritySourceVersion' IS DISTINCT FROM NEW.tenant_authority_source_version::TEXT
        OR payload->>'entitlementKind' IS DISTINCT FROM NEW.entitlement_kind
        OR payload->>'status' IS DISTINCT FROM NEW.status
        OR payload->>'paid' IS DISTINCT FROM 'false'
        OR payload->>'gameplayAvailable' IS DISTINCT FROM NEW.gameplay_available::TEXT
        OR payload->>'allowPublicJoin' IS DISTINCT FROM NEW.allow_public_join::TEXT
        OR payload->>'allowNewGameplayBindings' IS DISTINCT FROM NEW.allow_new_gameplay_bindings::TEXT
        OR payload->>'allowNewInstanceStarts' IS DISTINCT FROM NEW.allow_new_instance_starts::TEXT
        OR payload->'quotas'->>'maxActiveSessions' IS DISTINCT FROM NEW.max_active_sessions::TEXT
        OR payload->'quotas'->>'maxConcurrentGameInstances' IS DISTINCT FROM NEW.max_concurrent_game_instances::TEXT
        OR payload->'quotas'->>'maxStorageBytes' IS DISTINCT FROM NEW.max_storage_bytes::TEXT
        OR payload->'sourceEvidence'->>'schemaVersion' IS DISTINCT FROM NEW.source_schema_version::TEXT
        OR payload->'sourceEvidence'->>'targetNamespace' IS DISTINCT FROM NEW.source_target_namespace
        OR payload->'sourceEvidence'->>'creationRequestId' IS DISTINCT FROM NEW.source_creation_request_id::TEXT
        OR payload->'sourceEvidence'->>'operationId' IS DISTINCT FROM NEW.source_operation_id::TEXT
        OR payload->'sourceEvidence'->>'requestDigest' IS DISTINCT FROM NEW.source_request_digest
        OR payload->'sourceEvidence'->>'canonicalTenantId' IS DISTINCT FROM NEW.tenant_uuid::TEXT
        OR payload->'sourceEvidence'->>'sourceGameRowId' IS DISTINCT FROM NEW.source_game_row_id::TEXT
        OR payload->'sourceEvidence'->>'sourceGameTenantKey' IS DISTINCT FROM NEW.source_game_tenant_key
        OR payload->'sourceEvidence'->>'provenanceKind' IS DISTINCT FROM NEW.source_provenance_kind
        OR payload->'sourceEvidence'->>'evidenceDigest' IS DISTINCT FROM NEW.source_evidence_digest THEN
        RAISE EXCEPTION 'Demo entitlement state differs from its canonical outbox event'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION account_demo_entitlement_reject_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Account demo entitlement history cannot be truncated'
        USING ERRCODE = '23514';
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_tenant_entitlement_stream_guard
    BEFORE INSERT OR UPDATE OR DELETE ON account_tenant_entitlement_outbox_streams
    FOR EACH ROW EXECUTE FUNCTION account_tenant_entitlement_stream_guard();
CREATE TRIGGER account_tenant_entitlement_stream_no_truncate
    BEFORE TRUNCATE ON account_tenant_entitlement_outbox_streams
    FOR EACH STATEMENT EXECUTE FUNCTION account_demo_entitlement_reject_truncate();
CREATE TRIGGER account_tenant_entitlement_event_guard
    BEFORE INSERT OR UPDATE OR DELETE ON account_tenant_entitlement_outbox_events
    FOR EACH ROW EXECUTE FUNCTION account_tenant_entitlement_outbox_event_guard();
CREATE TRIGGER account_tenant_entitlement_event_no_truncate
    BEFORE TRUNCATE ON account_tenant_entitlement_outbox_events
    FOR EACH STATEMENT EXECUTE FUNCTION account_demo_entitlement_reject_truncate();
CREATE CONSTRAINT TRIGGER account_tenant_entitlement_stream_consistency_stream
    AFTER INSERT OR UPDATE ON account_tenant_entitlement_outbox_streams
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION account_tenant_entitlement_stream_consistency();
CREATE CONSTRAINT TRIGGER account_tenant_entitlement_stream_consistency_event
    AFTER INSERT ON account_tenant_entitlement_outbox_events
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION account_tenant_entitlement_stream_consistency();
CREATE TRIGGER account_demo_entitlement_operation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON account_demo_tenant_entitlement_operations
    FOR EACH ROW EXECUTE FUNCTION account_demo_entitlement_operation_guard();
CREATE TRIGGER account_demo_entitlement_operation_no_truncate
    BEFORE TRUNCATE ON account_demo_tenant_entitlement_operations
    FOR EACH STATEMENT EXECUTE FUNCTION account_demo_entitlement_reject_truncate();
CREATE CONSTRAINT TRIGGER account_demo_entitlement_operation_terminal
    AFTER INSERT OR UPDATE ON account_demo_tenant_entitlement_operations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION account_demo_entitlement_operation_terminal_guard();
CREATE TRIGGER account_demo_entitlement_current_guard
    BEFORE INSERT OR UPDATE OR DELETE ON account_demo_tenant_entitlements
    FOR EACH ROW EXECUTE FUNCTION account_demo_entitlement_current_guard();
CREATE TRIGGER account_demo_entitlement_current_no_truncate
    BEFORE TRUNCATE ON account_demo_tenant_entitlements
    FOR EACH STATEMENT EXECUTE FUNCTION account_demo_entitlement_reject_truncate();
CREATE CONSTRAINT TRIGGER account_demo_entitlement_current_event
    AFTER INSERT OR UPDATE ON account_demo_tenant_entitlements
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION account_demo_entitlement_current_event_guard();
-- [jooq ignore stop]
