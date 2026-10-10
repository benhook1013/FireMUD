-- Keep the global issuer rows read-shared during issuance authority checks so distinct Account
-- snapshots and pending issuance transactions do not serialize on issuer state. Account-scoped
-- generations, fences, and source rows remain exclusively locked at their mutation boundary.
-- Preserve all V84 predicates and the immutable V1-V84.3 migration history.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_gameplay_delegation_authority_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    current_issuer_generation BIGINT;
    current_issuer_source_version BIGINT;
    source_issuer_generation BIGINT;
    source_issuer_source_version BIGINT;
    current_issuer_sequence BIGINT;
    current_issuer_stream VARCHAR(2048);
    current_account_generation BIGINT;
    current_account_source_version BIGINT;
    source_account_generation BIGINT;
    source_account_source_version BIGINT;
    current_account_sequence BIGINT;
    current_account_stream VARCHAR(2048);
    current_account_cutoff_generation BIGINT;
    current_account_cutoff_stream VARCHAR(2048);
    current_account_cutoff_sequence BIGINT;
    current_issuance_fence BIGINT;
    current_fence_source_version BIGINT;
    expected_authority_tuple TEXT;
BEGIN
    -- Keep the owner lock order: Account row, issuer/account generations and fence, then the
    -- source rows whose exact optional cutoff is carried by the immutable pending intent.
    PERFORM account_uuid FROM accounts WHERE account_uuid = NEW.account_uuid FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account gameplay delegation Account source is missing'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_authority_current';
    END IF;
    SELECT generation, source_version
      INTO current_issuer_generation, current_issuer_source_version
      FROM account_authority_generations
      WHERE scope_kind = 'ISSUER' AND issuer_id = 'firemud-account-service'
        AND account_uuid IS NULL AND tenant_uuid IS NULL
      FOR SHARE;
    SELECT generation, source_version
      INTO current_account_generation, current_account_source_version
      FROM account_authority_generations
      WHERE scope_kind = 'ACCOUNT' AND account_uuid = NEW.account_uuid
        AND issuer_id IS NULL AND tenant_uuid IS NULL
      FOR UPDATE;
    SELECT issuance_fence, source_version
      INTO current_issuance_fence, current_fence_source_version
      FROM account_authority_issuance_fences
      WHERE account_uuid = NEW.account_uuid
      FOR UPDATE;
    SELECT outbox_stream_key, current_generation, current_source_version, last_outbox_sequence
      INTO current_issuer_stream, source_issuer_generation, source_issuer_source_version,
           current_issuer_sequence
      FROM account_authority_source_records
      WHERE scope_kind = 'ISSUER' AND issuer_id = 'firemud-account-service'
        AND account_uuid IS NULL
        AND outbox_stream_key = 'account:auth-authority:v1:issuer/firemud-account-service'
      FOR SHARE;
    SELECT outbox_stream_key, current_generation, current_source_version, last_outbox_sequence,
           cutoff_generation, cutoff_stream_key, cutoff_sequence
      INTO current_account_stream, source_account_generation, source_account_source_version,
           current_account_sequence, current_account_cutoff_generation,
           current_account_cutoff_stream, current_account_cutoff_sequence
      FROM account_authority_source_records
      WHERE scope_kind = 'ACCOUNT' AND account_uuid = NEW.account_uuid
        AND issuer_id IS NULL
        AND outbox_stream_key = 'account:auth-authority:v1:account/' || NEW.account_uuid::TEXT
      FOR UPDATE;

    IF current_account_sequence = 0
        AND current_account_cutoff_generation IS NULL
        AND current_account_cutoff_stream IS NULL
        AND current_account_cutoff_sequence IS NULL THEN
        expected_authority_tuple :=
            '{"accountAuthorityGeneration":"' || source_account_generation::TEXT
            || '","issuerAuthGeneration":"' || source_issuer_generation::TEXT || '"'
            || ',"membershipAuthorityGeneration":{},"privateRealmGrantVersions":[]'
            || ',"tenantAuthorityGeneration":{}}';
    ELSIF current_account_sequence > 0
        AND current_account_cutoff_generation = source_account_generation
        AND current_account_cutoff_stream = current_account_stream
        AND current_account_cutoff_sequence = current_account_sequence THEN
        expected_authority_tuple :=
            '{"accountAuthorityGeneration":"' || source_account_generation::TEXT || '"'
            || ',"accountSecurityCutoff":{"accountAuthorityGeneration":"'
            || current_account_cutoff_generation::TEXT || '","outboxSequence":"'
            || current_account_cutoff_sequence::TEXT || '","outboxStreamKey":"'
            || current_account_cutoff_stream || '"},"issuerAuthGeneration":"'
            || source_issuer_generation::TEXT || '"'
            || ',"membershipAuthorityGeneration":{},"privateRealmGrantVersions":[]'
            || ',"tenantAuthorityGeneration":{}}';
    END IF;

    IF current_issuer_generation IS NULL OR current_account_generation IS NULL
        OR current_issuance_fence IS NULL OR current_issuer_stream IS NULL
        OR current_account_stream IS NULL OR current_issuer_sequence IS NULL
        OR current_account_sequence IS NULL OR source_issuer_generation IS NULL
        OR source_issuer_source_version IS NULL OR source_account_generation IS NULL
        OR source_account_source_version IS NULL OR current_fence_source_version IS NULL
        OR expected_authority_tuple IS NULL
        OR current_issuer_stream <> 'account:auth-authority:v1:issuer/firemud-account-service'
        OR current_account_stream <> 'account:auth-authority:v1:account/' || NEW.account_uuid::TEXT
        OR source_issuer_generation <> current_issuer_generation
        OR source_issuer_source_version <> current_issuer_source_version
        OR source_account_generation <> current_account_generation
        OR source_account_source_version <> current_account_source_version
        OR source_issuer_generation <> source_issuer_source_version
        OR source_issuer_generation <> current_issuer_sequence + 1
        OR source_account_generation <> source_account_source_version
        OR source_account_generation <> current_account_sequence + 1
        OR current_issuance_fence <> current_account_sequence + 1
        OR current_fence_source_version <> current_account_sequence + 1
        OR NEW.authority_issuer_generation <> current_issuer_generation
        OR NEW.authority_issuer_source_version <> current_issuer_source_version
        OR NEW.authority_account_generation <> current_account_generation
        OR NEW.authority_account_source_version <> current_account_source_version
        OR NEW.issuance_fence <> current_issuance_fence
        OR NEW.issuance_fence_source_version <> current_fence_source_version
        OR convert_from(NEW.authority_tuple_canonical_bytes, 'UTF8') <>
            expected_authority_tuple
        OR convert_from(NEW.membership_version_canonical_bytes, 'UTF8') <> '{}'
        OR convert_from(NEW.authority_source_versions_canonical_bytes, 'UTF8') <>
            '{"accountSourceVersion":"' || current_account_source_version::text
            || '","issuanceFenceSourceVersion":"' || current_fence_source_version::text
            || '","issuerSourceVersion":"' || current_issuer_source_version::text || '"}' THEN
        RAISE EXCEPTION 'Account gameplay delegation authority snapshot is stale or missing'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_authority_current';
    END IF;
    RETURN NEW;
END;
$$;
-- [jooq ignore stop]
