-- A paid tenant authority checkpoint advances the membership stream and its pair pointer
-- without issuing new membership, role, or generation evidence.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_membership_pair_authority_update_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    stream_key TEXT;
    checkpoint_payload BYTEA;
    checkpoint_type TEXT;
BEGIN
    IF NEW.account_uuid IS DISTINCT FROM OLD.account_uuid
        OR NEW.tenant_uuid IS DISTINCT FROM OLD.tenant_uuid
        OR NEW.legacy_tenant_id IS DISTINCT FROM OLD.legacy_tenant_id
        OR NEW.tenant_provenance_kind IS DISTINCT FROM OLD.tenant_provenance_kind
        OR NEW.tenant_source_operation_id IS DISTINCT FROM OLD.tenant_source_operation_id
        OR NEW.tenant_provenance_digest IS DISTINCT FROM OLD.tenant_provenance_digest
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Account membership pair identity and provenance are immutable'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_membership_pair_authority_identity_immutable';
    END IF;

    stream_key := 'account:auth-authority:v1:membership/' || NEW.account_uuid::TEXT || '/' || NEW.tenant_uuid::TEXT;
    SELECT payload INTO checkpoint_payload FROM account_authority_outbox_events
    WHERE outbox_stream_key = stream_key AND outbox_sequence = NEW.last_event_sequence;
    BEGIN
        checkpoint_type := convert_from(checkpoint_payload, 'UTF8')::jsonb->>'eventType';
    EXCEPTION WHEN invalid_text_representation OR character_not_in_repertoire OR untranslatable_character
        OR numeric_value_out_of_range THEN
        checkpoint_type := NULL;
    END;

    IF checkpoint_type = 'MEMBERSHIP_AUTHORITY_CHECKPOINT' THEN
        IF NOT OLD.membership_exists OR NOT NEW.membership_exists OR OLD.last_event_sequence <= 0
            OR NEW.membership_version <> OLD.membership_version
            OR OLD.last_transition_invalidated
            OR NEW.last_event_sequence <> OLD.last_event_sequence + 1
            OR NEW.membership_authority_generation <> OLD.membership_authority_generation
            OR NEW.last_transition_invalidated
            OR NOT EXISTS (
                SELECT 1
                FROM account_authority_outbox_events prior
                JOIN account_authority_outbox_events checkpoint USING (outbox_stream_key)
                JOIN account_authority_outbox_streams stream USING (outbox_stream_key)
                WHERE prior.outbox_stream_key = stream_key
                    AND (prior.outbox_sequence, prior.event_id, prior.event_digest)
                        = (OLD.last_event_sequence, OLD.last_event_id, OLD.last_event_digest)
                    AND (checkpoint.outbox_sequence, checkpoint.event_id, checkpoint.event_digest)
                        = (NEW.last_event_sequence, NEW.last_event_id, NEW.last_event_digest)
                    AND stream.last_sequence = checkpoint.outbox_sequence) THEN
            RAISE EXCEPTION 'Membership pair checkpoint requires its exact prior and next authority events'
                USING ERRCODE = '23514', CONSTRAINT = 'account_membership_checkpoint_pair';
        END IF;
    ELSIF NEW.membership_version <> OLD.membership_version + 1
        OR NEW.last_event_sequence <> OLD.last_event_sequence + 1
        OR (NEW.last_transition_invalidated AND NEW.membership_authority_generation <>
            OLD.membership_authority_generation + 1)
        OR (NOT NEW.last_transition_invalidated AND NEW.membership_authority_generation <>
            OLD.membership_authority_generation) THEN
        RAISE EXCEPTION 'Account membership pair transition must advance exact authority'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_membership_pair_authority_transition_monotonic';
    END IF;
    NEW.updated_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_paid_membership_checkpoint_consistency()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    checkpoint JSONB;
    prior JSONB;
    prior_event account_authority_outbox_events%ROWTYPE;
    pair account_membership_pair_authority%ROWTYPE;
    member account_tenant_membership%ROWTYPE;
    tenant account_tenant_authority_source_records%ROWTYPE;
    account_source account_authority_source_records%ROWTYPE;
    issuer_source account_authority_source_records%ROWTYPE;
    private_account_id BIGINT;
    role_bytes TEXT;
    scope_key TEXT;
    account_cutoff TEXT := '';
    tuple_bytes TEXT;
    preimage TEXT;
    expected_request_id TEXT;
    event_identity_hash TEXT;
    expected_event_id UUID;
    expected_digest TEXT;
BEGIN
    -- Older generic outbox writers can retain opaque bytes. Only this typed event has the
    -- membership checkpoint contract.
    BEGIN
        checkpoint := convert_from(NEW.payload, 'UTF8')::jsonb;
    EXCEPTION WHEN invalid_text_representation OR character_not_in_repertoire OR untranslatable_character
        OR numeric_value_out_of_range THEN
        RETURN NULL;
    END;
    IF checkpoint->>'eventType' IS DISTINCT FROM 'MEMBERSHIP_AUTHORITY_CHECKPOINT' THEN
        RETURN NULL;
    END IF;

    SELECT * INTO pair FROM account_membership_pair_authority
    WHERE account_uuid::TEXT = checkpoint->>'accountId' AND tenant_uuid::TEXT = checkpoint->>'tenantId'
    FOR UPDATE;
    scope_key := 'membership/' || pair.account_uuid::TEXT || '/' || pair.tenant_uuid::TEXT;
    IF NOT FOUND OR NOT pair.membership_exists OR pair.last_transition_invalidated
        OR pair.tenant_provenance_kind <> 'FRESH_GAME_DESIGN' OR pair.legacy_tenant_id IS NOT NULL
        OR NEW.outbox_sequence <= 1 OR NEW.outbox_stream_key IS DISTINCT FROM 'account:auth-authority:v1:' || scope_key
        OR (pair.last_event_sequence, pair.last_event_id, pair.last_event_digest)
            IS DISTINCT FROM (NEW.outbox_sequence, NEW.event_id, NEW.event_digest)
        OR NOT EXISTS (SELECT 1 FROM account_authority_outbox_streams
            WHERE outbox_stream_key = NEW.outbox_stream_key AND last_sequence = NEW.outbox_sequence) THEN
        RAISE EXCEPTION 'Paid membership checkpoint lacks its exact committed pair head'
            USING ERRCODE = '23514', CONSTRAINT = 'account_membership_checkpoint_pair';
    END IF;

    SELECT * INTO prior_event FROM account_authority_outbox_events
    WHERE outbox_stream_key = NEW.outbox_stream_key AND outbox_sequence = NEW.outbox_sequence - 1;
    prior := convert_from(prior_event.payload, 'UTF8')::jsonb;

    SELECT id INTO private_account_id FROM accounts
    WHERE account_uuid = pair.account_uuid AND account_uuid_provenance = 'ACCOUNT_REPOSITORY_INSERT'
        AND account_uuid_source_numeric_id = id AND upper(lifecycle_state) = 'ACTIVE'
    FOR SHARE;
    SELECT * INTO member FROM account_tenant_membership
    WHERE account_id = private_account_id
        AND tenant_uuid = pair.tenant_uuid AND tenant_id IS NULL
        AND tenant_provenance_kind = pair.tenant_provenance_kind
        AND tenant_source_operation_id = pair.tenant_source_operation_id
        AND tenant_provenance_digest = pair.tenant_provenance_digest
        AND membership_version = pair.membership_version
        AND membership_authority_generation = pair.membership_authority_generation
        AND lifecycle_state = 'ACTIVE' AND gameplay_admission_allowed
    FOR SHARE;
    PERFORM 1 FROM account_tenant_membership_role_snapshots
    WHERE membership_id = member.id AND snapshot_version = member.membership_version FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Paid membership checkpoint lacks its exact member and role snapshot'
            USING ERRCODE = '23514', CONSTRAINT = 'account_membership_checkpoint_membership';
    END IF;
    SELECT '[' || COALESCE(string_agg(to_json(role_identifier)::TEXT, ',' ORDER BY convert_to(role_identifier, 'UTF8')), '') || ']'
        INTO role_bytes
    FROM account_tenant_membership_role_snapshot_roles
    WHERE membership_id = member.id AND snapshot_version = member.membership_version;
    IF EXISTS (
        SELECT 1 FROM account_tenant_membership_role_snapshot_roles
        WHERE membership_id = member.id AND snapshot_version = member.membership_version
            AND role_identifier NOT IN ('player', 'designer', 'tenantAdmin', 'moderator')) THEN
        RAISE EXCEPTION 'Paid membership checkpoint requires canonical tenant roles'
            USING ERRCODE = '23514', CONSTRAINT = 'account_membership_checkpoint_membership';
    END IF;

    IF prior->>'schemaVersion' IS DISTINCT FROM 'account-auth-authority-event/v1'
        OR COALESCE(prior->>'eventType', '') NOT IN ('MEMBERSHIP_CHANGED', 'MEMBERSHIP_AUTHORITY_CHECKPOINT')
        OR prior->>'eventId' IS DISTINCT FROM prior_event.event_id
        OR prior->>'eventDigest' IS DISTINCT FROM prior_event.event_digest
        OR prior->>'requestId' IS DISTINCT FROM prior_event.request_id
        OR prior->>'accountId' IS DISTINCT FROM pair.account_uuid::TEXT
        OR prior->>'tenantId' IS DISTINCT FROM pair.tenant_uuid::TEXT
        OR prior->>'outboxStreamKey' IS DISTINCT FROM NEW.outbox_stream_key
        OR prior->>'outboxSequence' IS DISTINCT FROM (NEW.outbox_sequence - 1)::TEXT
        OR prior->>'sourceScope' IS DISTINCT FROM scope_key
        OR prior->'membershipExists' IS DISTINCT FROM 'true'::jsonb
        OR prior->>'membershipLifecycleState' IS DISTINCT FROM member.lifecycle_state
        OR prior->'gameplayAdmissionAllowed' IS DISTINCT FROM to_jsonb(member.gameplay_admission_allowed)
        OR prior->'membershipVersion' IS DISTINCT FROM jsonb_build_object(pair.tenant_uuid::TEXT, pair.membership_version::TEXT)
        OR prior->>'membershipAuthorityGeneration' IS DISTINCT FROM pair.membership_authority_generation::TEXT
        OR prior->'roles' IS DISTINCT FROM role_bytes::jsonb
        OR prior->'callerBoundAuthorityInvalidated' IS DISTINCT FROM 'false'::jsonb
        OR prior#>'{authorityTuple,privateRealmGrantVersions}' IS DISTINCT FROM '[]'::jsonb THEN
        RAISE EXCEPTION 'Paid membership checkpoint must preserve the prior membership facts'
            USING ERRCODE = '23514', CONSTRAINT = 'account_membership_checkpoint_membership';
    END IF;

    SELECT * INTO tenant FROM account_tenant_authority_source_records
    WHERE tenant_uuid = pair.tenant_uuid FOR SHARE;
    IF NOT FOUND
        OR NOT EXISTS (
            SELECT 1 FROM account_fresh_tenant_identity_associations fresh
            WHERE fresh.canonical_tenant_id = pair.tenant_uuid
                AND fresh.operation_id = pair.tenant_source_operation_id
                AND fresh.evidence_digest = pair.tenant_provenance_digest)
        OR NOT EXISTS (
            SELECT 1
            FROM account_authority_generations generation
            JOIN subscription_lifecycle_receipts receipt ON receipt.request_id = tenant.last_request_id
            JOIN subscription_lifecycle_operations operation USING (request_id, subscription_id)
            JOIN subscription current_subscription USING (subscription_id)
            JOIN subscription_first_invoice_completions completion USING (subscription_id)
            JOIN account_authority_outbox_streams authority_stream
                ON authority_stream.outbox_stream_key = tenant.outbox_stream_key
            JOIN account_authority_outbox_events authority_event
                ON (authority_event.outbox_stream_key, authority_event.outbox_sequence)
                    = (authority_stream.outbox_stream_key, authority_stream.last_sequence)
            JOIN account_tenant_entitlement_outbox_streams billing_stream ON billing_stream.tenant_uuid = tenant.tenant_uuid
            JOIN account_tenant_entitlement_outbox_events billing_event
                ON (billing_event.tenant_uuid, billing_event.tenant_billing_sequence)
                    = (billing_stream.tenant_uuid, billing_stream.last_sequence)
            WHERE generation.scope_kind = 'TENANT' AND generation.tenant_uuid = tenant.tenant_uuid
                AND (generation.generation, generation.source_version) = (tenant.current_generation, tenant.current_source_version)
                AND operation.state = 'COMMITTED' AND operation.request_digest = receipt.request_digest
                AND (receipt.tenant_id, receipt.request_digest) = (tenant.tenant_uuid, tenant.last_request_digest)
                AND current_subscription.current_receipt_request_id = receipt.request_id
                AND current_subscription.lifecycle_inflight_request_id IS NULL
                AND current_subscription.superseded_at IS NULL
                AND (current_subscription.tenant_id, current_subscription.row_version, current_subscription.status,
                    current_subscription.provider_subscription_id)
                    = (receipt.tenant_id, receipt.subscription_row_version, receipt.status, receipt.provider_subscription_id)
                AND receipt.status IN ('trialing', 'active', 'past_due')
                AND receipt.valid_until > clock_timestamp()
                AND convert_from(receipt.receipt_payload, 'UTF8')::jsonb->'hardCutoff' = 'false'::jsonb
                AND (receipt.authority_generation, receipt.authority_source_version, receipt.authority_sequence,
                    receipt.authority_event_id, receipt.authority_event_digest)
                    = (tenant.current_generation, tenant.current_source_version, tenant.last_outbox_sequence,
                        tenant.last_event_id, tenant.last_event_digest)
                AND (authority_event.outbox_sequence, authority_event.request_id, authority_event.event_id, authority_event.event_digest)
                    = (receipt.authority_sequence, receipt.request_id::TEXT, receipt.authority_event_id, receipt.authority_event_digest)
                AND (receipt.billing_sequence, receipt.billing_event_id, receipt.billing_event_digest)
                    = (tenant.tenant_billing_sequence, tenant.tenant_billing_event_id, tenant.tenant_billing_event_digest)
                AND (billing_event.tenant_billing_sequence, billing_event.request_id, billing_event.event_id, billing_event.event_digest)
                    = (receipt.billing_sequence, receipt.request_id, receipt.billing_event_id, receipt.billing_event_digest)
                AND convert_from(billing_event.payload, 'UTF8')::jsonb->>'schemaVersion' = 'account-hosting-subscription-event/v1'
                AND convert_from(billing_event.payload, 'UTF8')::jsonb->'receipt' = convert_from(receipt.receipt_payload, 'UTF8')::jsonb
            FOR SHARE OF current_subscription)
        OR NOT COALESCE(
            (prior->'authorityTuple'->'tenantAuthorityGeneration'->>pair.tenant_uuid::TEXT)::BIGINT
                < tenant.current_generation, FALSE) THEN
        RAISE EXCEPTION 'Membership checkpoint requires newer exact current paid tenant authority'
            USING ERRCODE = '23514', CONSTRAINT = 'account_membership_checkpoint_paid_source';
    END IF;

    SELECT * INTO issuer_source FROM account_authority_source_records
    WHERE outbox_stream_key = 'account:auth-authority:v1:issuer/firemud-account-service' FOR SHARE;
    SELECT * INTO account_source FROM account_authority_source_records
    WHERE outbox_stream_key = 'account:auth-authority:v1:account/' || pair.account_uuid::TEXT FOR SHARE;
    IF issuer_source.scope_kind IS DISTINCT FROM 'ISSUER'
        OR account_source.scope_kind IS DISTINCT FROM 'ACCOUNT'
        OR account_source.account_source_numeric_id IS DISTINCT FROM private_account_id
        OR NOT EXISTS (
            SELECT 1
            FROM account_authority_generations issuer
            JOIN account_authority_generations account ON account.scope_kind = 'ACCOUNT'
                AND account.account_uuid = pair.account_uuid
            JOIN account_authority_generations membership ON membership.scope_kind = 'MEMBERSHIP'
                AND membership.account_uuid = pair.account_uuid AND membership.tenant_uuid = pair.tenant_uuid
            JOIN account_authority_issuance_fences fence ON fence.account_uuid = pair.account_uuid
            WHERE issuer.scope_kind = 'ISSUER' AND issuer.issuer_id = issuer_source.issuer_id
                AND (issuer.generation, issuer.source_version) = (issuer_source.current_generation, issuer_source.current_source_version)
                AND (account.generation, account.source_version) = (account_source.current_generation, account_source.current_source_version)
                AND membership.generation = member.membership_authority_generation
                AND (fence.issuance_fence, fence.source_version)
                    = (account_source.current_issuance_fence, account_source.current_issuance_fence_source_version)) THEN
        RAISE EXCEPTION 'Membership checkpoint lacks exact current issuer, Account and membership fences'
            USING ERRCODE = '23514', CONSTRAINT = 'account_membership_checkpoint_fences';
    END IF;

    -- The V1 carrier uses a fixed property order, ASCII keys, UUIDs and decimal strings.
    -- Build the preimage from owner rows and compare the complete wire bytes and digest.
    IF account_source.cutoff_generation IS NOT NULL THEN
        account_cutoff := format(',"accountSecurityCutoff":{"accountAuthorityGeneration":"%s","outboxSequence":"%s","outboxStreamKey":"%s"}',
            account_source.cutoff_generation, account_source.cutoff_sequence, account_source.cutoff_stream_key);
    END IF;
    tuple_bytes := format(
        '{"accountAuthorityGeneration":"%s"%s,"issuerAuthGeneration":"%s","membershipAuthorityGeneration":{"%s":"%s"},"privateRealmGrantVersions":[],"tenantAuthorityGeneration":{"%s":"%s"},"tenantBillingCutoff":{"%s":{"outboxSequence":"%s","outboxStreamKey":"%s","tenantAuthorityGeneration":"%s","tenantBillingSequence":"%s"}}}',
        account_source.current_generation, account_cutoff, issuer_source.current_generation,
        pair.tenant_uuid, pair.membership_authority_generation, pair.tenant_uuid, tenant.current_generation,
        pair.tenant_uuid, tenant.last_outbox_sequence, tenant.outbox_stream_key, tenant.current_generation, tenant.tenant_billing_sequence);
    preimage := format(
        '{"accountId":"%s","authorityTuple":%s,"callerBoundAuthorityInvalidated":false,"eventId":%s,"eventType":"MEMBERSHIP_AUTHORITY_CHECKPOINT","gameplayAdmissionAllowed":%s,"issuanceFence":"%s","membershipAuthorityGeneration":"%s","membershipExists":true,"membershipLifecycleState":"%s","membershipVersion":{"%s":"%s"},"outboxSequence":"%s","outboxStreamKey":"%s","requestId":%s,"roles":%s,"schemaVersion":"account-auth-authority-event/v1","sourceScope":"%s","tenantId":"%s"}',
        pair.account_uuid, tuple_bytes, to_json(NEW.event_id), to_json(member.gameplay_admission_allowed),
        account_source.current_issuance_fence, pair.membership_authority_generation, member.lifecycle_state,
        pair.tenant_uuid, pair.membership_version, NEW.outbox_sequence, NEW.outbox_stream_key,
        to_json(NEW.request_id), role_bytes, scope_key, pair.tenant_uuid);
    expected_request_id := 'paid-membership-checkpoint:' || tenant.last_event_id || ':' || prior_event.event_id;
    -- Match the producer's version-3 UUID derived from the complete request identity.
    event_identity_hash := overlay(md5(convert_to(expected_request_id, 'UTF8')) PLACING '3' FROM 13 FOR 1);
    expected_event_id := overlay(event_identity_hash PLACING substr(
        '89ab', mod(strpos('0123456789abcdef', substr(event_identity_hash, 17, 1)) - 1, 4) + 1, 1)
        FROM 17 FOR 1)::UUID;
    expected_digest := 'sha256:' || encode(sha256(convert_to(preimage, 'UTF8')), 'hex');
    IF NEW.request_id IS DISTINCT FROM expected_request_id
        OR NEW.event_id IS DISTINCT FROM expected_event_id::TEXT
        OR NEW.event_digest IS DISTINCT FROM expected_digest
        OR NEW.payload IS DISTINCT FROM convert_to(
            replace(preimage, ',"eventId":', ',"eventDigest":"' || expected_digest || '","eventId":'), 'UTF8') THEN
        RAISE EXCEPTION 'Membership checkpoint differs from exact canonical current authority evidence'
            USING ERRCODE = '23514', CONSTRAINT = 'account_membership_checkpoint_evidence';
    END IF;
    RETURN NULL;
END;
$$;

-- Observe the predecessor before the append so a raw writer cannot fabricate a new positive
-- pair pointing directly at the checkpoint. The deferred guard checks the atomic advance.
CREATE FUNCTION account_paid_membership_checkpoint_predecessor()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE checkpoint JSONB;
BEGIN
    BEGIN
        checkpoint := convert_from(NEW.payload, 'UTF8')::jsonb;
    EXCEPTION WHEN invalid_text_representation OR character_not_in_repertoire OR untranslatable_character
        OR numeric_value_out_of_range THEN
        RETURN NEW;
    END;
    IF checkpoint->>'eventType' = 'MEMBERSHIP_AUTHORITY_CHECKPOINT' THEN
        PERFORM 1 FROM account_membership_pair_authority pair
        JOIN account_authority_outbox_events prior
            ON (prior.outbox_sequence, prior.event_id, prior.event_digest)
                = (pair.last_event_sequence, pair.last_event_id, pair.last_event_digest)
        WHERE pair.account_uuid::TEXT = checkpoint->>'accountId'
            AND pair.tenant_uuid::TEXT = checkpoint->>'tenantId'
            AND pair.membership_exists AND pair.last_event_sequence > 0
            AND NEW.outbox_sequence = pair.last_event_sequence + 1
            AND prior.outbox_stream_key = NEW.outbox_stream_key
            AND NEW.outbox_stream_key =
                'account:auth-authority:v1:membership/' || pair.account_uuid::TEXT || '/' || pair.tenant_uuid::TEXT
        FOR UPDATE OF pair;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Paid membership checkpoint requires an existing exact predecessor'
                USING ERRCODE = '23514', CONSTRAINT = 'account_membership_checkpoint_pair';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_paid_membership_checkpoint_predecessor
    BEFORE INSERT ON account_authority_outbox_events
    FOR EACH ROW EXECUTE FUNCTION account_paid_membership_checkpoint_predecessor();

CREATE CONSTRAINT TRIGGER account_paid_membership_checkpoint_consistency
    AFTER INSERT ON account_authority_outbox_events
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_paid_membership_checkpoint_consistency();
-- [jooq ignore stop]
