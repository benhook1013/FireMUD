-- Forward correction for the published V137 restriction operation receipt guard.
-- Qualify the outbox payload column so it cannot resolve to the PL/pgSQL payload variable.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_platform_restriction_operation_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    payload JSONB;
    event_row RECORD;
    expected_fields TEXT[] := ARRAY[
        'schemaVersion', 'eventType', 'eventId', 'requestId', 'accountId', 'sourceScope',
        'outboxStreamKey', 'outboxSequence', 'accountAuthorityGeneration', 'sourceVersion',
        'accountSecurityCutoff', 'restrictionCategory', 'restrictionRevision',
        'restrictionEnforcementEpoch', 'restrictionState', 'restrictionResultId',
        'restrictionRequestDigest', 'restrictionSourceKind', 'restrictionSourceRequestId',
        'restrictionSourceDigest', 'eventDigest'];
BEGIN
    payload := convert_from(NEW.event_payload, 'UTF8')::JSONB;
    SELECT outbox.request_id, outbox.event_id, outbox.event_digest, outbox.payload
        INTO event_row
        FROM account_authority_outbox_events AS outbox
        WHERE outbox.outbox_stream_key = NEW.outbox_stream_key
          AND outbox.outbox_sequence = NEW.event_sequence;
    IF event_row.request_id IS DISTINCT FROM NEW.request_id::TEXT
        OR event_row.event_id IS DISTINCT FROM NEW.event_id
        OR event_row.event_digest IS DISTINCT FROM NEW.event_digest
        OR event_row.payload IS DISTINCT FROM NEW.event_payload
        OR (payload - expected_fields) <> '{}'::JSONB
        OR NOT (payload ?& expected_fields)
        OR payload->>'schemaVersion' IS DISTINCT FROM 'account-auth-account-security-state-event/v1'
        OR payload->>'eventType' IS DISTINCT FROM 'ACCOUNT_RESTRICTION_CHANGED'
        OR payload->>'eventId' IS DISTINCT FROM NEW.event_id
        OR payload->>'requestId' IS DISTINCT FROM NEW.request_id::TEXT
        OR payload->>'accountId' IS DISTINCT FROM NEW.account_uuid::TEXT
        OR payload->>'sourceScope' IS DISTINCT FROM 'account/' || NEW.account_uuid::TEXT
        OR payload->>'outboxStreamKey' IS DISTINCT FROM NEW.outbox_stream_key
        OR payload->>'outboxSequence' IS DISTINCT FROM NEW.event_sequence::TEXT
        OR payload->>'accountAuthorityGeneration' IS DISTINCT FROM NEW.result_generation::TEXT
        OR payload->>'sourceVersion' IS DISTINCT FROM NEW.result_source_version::TEXT
        OR payload#>>'{accountSecurityCutoff,accountAuthorityGeneration}' IS DISTINCT FROM NEW.result_generation::TEXT
        OR payload#>>'{accountSecurityCutoff,outboxStreamKey}' IS DISTINCT FROM NEW.outbox_stream_key
        OR payload#>>'{accountSecurityCutoff,outboxSequence}' IS DISTINCT FROM NEW.event_sequence::TEXT
        OR payload->>'restrictionCategory' IS DISTINCT FROM NEW.category
        OR payload->>'restrictionRevision' IS DISTINCT FROM NEW.result_category_revision::TEXT
        OR payload->>'restrictionEnforcementEpoch' IS DISTINCT FROM NEW.result_enforcement_epoch::TEXT
        OR payload->>'restrictionState' IS DISTINCT FROM NEW.result_state
        OR payload->>'restrictionResultId' IS DISTINCT FROM NEW.result_id::TEXT
        OR payload->>'restrictionRequestDigest' IS DISTINCT FROM NEW.request_digest
        OR payload->>'restrictionSourceKind' IS DISTINCT FROM NEW.source_kind
        OR payload->>'restrictionSourceRequestId' IS DISTINCT FROM NEW.source_request_id::TEXT
        OR payload->>'restrictionSourceDigest' IS DISTINCT FROM NEW.source_digest THEN
        RAISE EXCEPTION 'Account restriction operation requires its exact closed source event and outbox receipt'
            USING ERRCODE = '23514', CONSTRAINT = 'account_platform_restriction_operation_event_binding';
    END IF;
    RETURN NEW;
END;
$$;
-- [jooq ignore end]
