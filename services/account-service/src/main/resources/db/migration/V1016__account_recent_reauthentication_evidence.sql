-- Tenantless self-service creation reauthentication evidence only. This does not authorize a draft,
-- register a caller, add a route, or establish creator-party/hosted-terms authority.
CREATE TABLE account_recent_reauthentication_policy_values (
    policy_scope VARCHAR(16) NOT NULL CHECK (policy_scope IN ('PLATFORM', 'DEPLOYMENT')),
    policy_version BIGINT NOT NULL CHECK (policy_version > 0),
    recent_age_seconds BIGINT NOT NULL CHECK (recent_age_seconds BETWEEN 1 AND 300),
    max_clock_skew_seconds BIGINT NOT NULL CHECK (max_clock_skew_seconds BETWEEN 0 AND 30),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (max_clock_skew_seconds < recent_age_seconds),
    PRIMARY KEY (policy_scope, policy_version)
);

CREATE TABLE account_recent_reauthentication_policy_current (
    policy_scope VARCHAR(16) PRIMARY KEY CHECK (policy_scope IN ('PLATFORM', 'DEPLOYMENT')),
    policy_version BIGINT NOT NULL CHECK (policy_version > 0),
    FOREIGN KEY (policy_scope, policy_version)
        REFERENCES account_recent_reauthentication_policy_values(policy_scope, policy_version)
        ON DELETE RESTRICT
);

CREATE TABLE account_recent_reauthentication_evidence (
    evidence_id UUID PRIMARY KEY CHECK (evidence_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    request_id UUID NOT NULL UNIQUE CHECK (request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    account_uuid UUID NOT NULL REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    purpose VARCHAR(48) NOT NULL CHECK (purpose = 'SELF_SERVICE_GAME_CREATION'),
    creation_operation_id UUID NOT NULL UNIQUE
        CHECK (creation_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    -- The request preimage contains only request/account/purpose/operation IDs, never email or secret.
    request_payload BYTEA NOT NULL CHECK (octet_length(request_payload) BETWEEN 1 AND 4096),
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$')
        CHECK (request_digest = 'sha256:' || encode(sha256(request_payload), 'hex')),
    identity_provenance VARCHAR(40) NOT NULL
        CHECK (identity_provenance IN ('ACCOUNT_V29_MIGRATION', 'ACCOUNT_REPOSITORY_INSERT', 'ACCOUNT_DATABASE_INSERT')),
    account_uuid_source_numeric_id BIGINT NOT NULL CHECK (account_uuid_source_numeric_id > 0),
    account_lifecycle_state VARCHAR(32) NOT NULL CHECK (account_lifecycle_state = 'ACTIVE'),
    account_authority_generation BIGINT NOT NULL CHECK (account_authority_generation > 0),
    account_authority_source_version BIGINT NOT NULL CHECK (account_authority_source_version > 0),
    issuance_fence BIGINT NOT NULL CHECK (issuance_fence > 0),
    issuance_fence_source_version BIGINT NOT NULL CHECK (issuance_fence_source_version > 0),
    platform_policy_version BIGINT NOT NULL CHECK (platform_policy_version > 0),
    deployment_policy_version BIGINT NOT NULL CHECK (deployment_policy_version > 0),
    effective_policy_version VARCHAR(71) NOT NULL CHECK (effective_policy_version ~ '^sha256:[0-9a-f]{64}$'),
    recent_age_seconds BIGINT NOT NULL CHECK (recent_age_seconds BETWEEN 1 AND 300),
    max_clock_skew_seconds BIGINT NOT NULL CHECK (max_clock_skew_seconds BETWEEN 0 AND 30),
    issued_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (max_clock_skew_seconds < recent_age_seconds),
    CHECK (request_id <> creation_operation_id),
    UNIQUE (evidence_id, request_id, account_uuid, purpose, creation_operation_id),
    UNIQUE (account_uuid, purpose, creation_operation_id)
);

CREATE TABLE account_recent_reauthentication_consumptions (
    evidence_id UUID PRIMARY KEY,
    request_id UUID NOT NULL,
    account_uuid UUID NOT NULL,
    purpose VARCHAR(48) NOT NULL CHECK (purpose = 'SELF_SERVICE_GAME_CREATION'),
    creation_operation_id UUID NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (evidence_id, request_id, account_uuid, purpose, creation_operation_id)
        REFERENCES account_recent_reauthentication_evidence
            (evidence_id, request_id, account_uuid, purpose, creation_operation_id)
        ON DELETE RESTRICT,
    UNIQUE (request_id, account_uuid, purpose, creation_operation_id)
);

-- [jooq ignore start]
CREATE FUNCTION account_recent_reauthentication_no_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Account recent-reauthentication evidence and policy values are immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION account_recent_reauthentication_policy_current_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.policy_scope IS DISTINCT FROM OLD.policy_scope
        OR NEW.policy_version <= OLD.policy_version THEN
        RAISE EXCEPTION 'Account reauthentication policy version must advance monotonically'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_recent_reauthentication_evidence_source_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    account_row RECORD;
    generation_row RECORD;
    fence_row RECORD;
    platform_row RECORD;
    deployment_row RECORD;
    effective_age BIGINT;
    effective_skew BIGINT;
    policy_preimage TEXT;
    expected_policy_version TEXT;
BEGIN
    SELECT id, account_uuid, account_uuid_provenance, account_uuid_source_numeric_id, lifecycle_state
      INTO account_row FROM accounts WHERE account_uuid = NEW.account_uuid FOR SHARE;
    IF NOT FOUND OR account_row.account_uuid IS DISTINCT FROM NEW.account_uuid
        OR account_row.id IS DISTINCT FROM NEW.account_uuid_source_numeric_id
        OR account_row.account_uuid_provenance IS DISTINCT FROM NEW.identity_provenance
        OR account_row.lifecycle_state IS DISTINCT FROM 'active' THEN
        RAISE EXCEPTION 'Recent reauthentication requires the exact current active Account identity'
            USING ERRCODE = '23514';
    END IF;

    SELECT generation, source_version INTO generation_row
      FROM account_authority_generations
      WHERE scope_kind = 'ACCOUNT' AND account_uuid = NEW.account_uuid FOR SHARE;
    SELECT issuance_fence, source_version INTO fence_row
      FROM account_authority_issuance_fences
      WHERE account_uuid = NEW.account_uuid FOR SHARE;
    IF NOT FOUND OR generation_row.generation IS DISTINCT FROM NEW.account_authority_generation
        OR generation_row.source_version IS DISTINCT FROM NEW.account_authority_source_version
        OR fence_row.issuance_fence IS DISTINCT FROM NEW.issuance_fence
        OR fence_row.source_version IS DISTINCT FROM NEW.issuance_fence_source_version THEN
        RAISE EXCEPTION 'Recent reauthentication Account authority source is stale'
            USING ERRCODE = '23514';
    END IF;

    SELECT value.policy_version, value.recent_age_seconds, value.max_clock_skew_seconds
      INTO platform_row
      FROM account_recent_reauthentication_policy_current current
      JOIN account_recent_reauthentication_policy_values value
        ON value.policy_scope = current.policy_scope AND value.policy_version = current.policy_version
      WHERE current.policy_scope = 'PLATFORM';
    SELECT value.policy_version, value.recent_age_seconds, value.max_clock_skew_seconds
      INTO deployment_row
      FROM account_recent_reauthentication_policy_current current
      JOIN account_recent_reauthentication_policy_values value
        ON value.policy_scope = current.policy_scope AND value.policy_version = current.policy_version
      WHERE current.policy_scope = 'DEPLOYMENT';
    IF NOT FOUND OR platform_row.policy_version IS DISTINCT FROM NEW.platform_policy_version
        OR deployment_row.policy_version IS DISTINCT FROM NEW.deployment_policy_version
        OR deployment_row.recent_age_seconds > platform_row.recent_age_seconds
        OR deployment_row.max_clock_skew_seconds > platform_row.max_clock_skew_seconds THEN
        RAISE EXCEPTION 'Recent reauthentication policy source is missing, stale, or non-tightening'
            USING ERRCODE = '23514';
    END IF;
    effective_age := LEAST(platform_row.recent_age_seconds, deployment_row.recent_age_seconds);
    effective_skew := LEAST(platform_row.max_clock_skew_seconds, deployment_row.max_clock_skew_seconds);
    IF effective_skew >= effective_age
        OR NEW.recent_age_seconds IS DISTINCT FROM effective_age
        OR NEW.max_clock_skew_seconds IS DISTINCT FROM effective_skew THEN
        RAISE EXCEPTION 'Recent reauthentication effective policy values are invalid'
            USING ERRCODE = '23514';
    END IF;

    policy_preimage := 'account-recent-reauthentication-policy/v1' || E'\n'
        || 'purpose=SELF_SERVICE_GAME_CREATION' || E'\n'
        || 'platformVersion=' || platform_row.policy_version::TEXT || E'\n'
        || 'platformRecentAgeSeconds=' || platform_row.recent_age_seconds::TEXT || E'\n'
        || 'platformMaxClockSkewSeconds=' || platform_row.max_clock_skew_seconds::TEXT || E'\n'
        || 'deploymentVersion=' || deployment_row.policy_version::TEXT || E'\n'
        || 'deploymentRecentAgeSeconds=' || deployment_row.recent_age_seconds::TEXT || E'\n'
        || 'deploymentMaxClockSkewSeconds=' || deployment_row.max_clock_skew_seconds::TEXT || E'\n'
        || 'effectiveRecentAgeSeconds=' || effective_age::TEXT || E'\n'
        || 'effectiveMaxClockSkewSeconds=' || effective_skew::TEXT || E'\n';
    expected_policy_version := 'sha256:' || encode(sha256(convert_to(policy_preimage, 'UTF8')), 'hex');
    IF NEW.effective_policy_version IS DISTINCT FROM expected_policy_version THEN
        RAISE EXCEPTION 'Recent reauthentication effective policy version is invalid'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_recent_reauthentication_consumption_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    evidence_row account_recent_reauthentication_evidence%ROWTYPE;
BEGIN
    SELECT * INTO evidence_row FROM account_recent_reauthentication_evidence
      WHERE evidence_id = NEW.evidence_id FOR UPDATE;
    IF NOT FOUND OR (NEW.request_id, NEW.account_uuid, NEW.purpose, NEW.creation_operation_id)
        IS DISTINCT FROM (evidence_row.request_id, evidence_row.account_uuid,
            evidence_row.purpose, evidence_row.creation_operation_id) THEN
        RAISE EXCEPTION 'Recent reauthentication consume tuple differs from retained evidence'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_recent_reauthentication_policy_values_immutable
    BEFORE UPDATE OR DELETE ON account_recent_reauthentication_policy_values
    FOR EACH ROW EXECUTE FUNCTION account_recent_reauthentication_no_mutation();
CREATE TRIGGER account_recent_reauthentication_policy_values_no_truncate
    BEFORE TRUNCATE ON account_recent_reauthentication_policy_values
    FOR EACH STATEMENT EXECUTE FUNCTION account_recent_reauthentication_no_mutation();
CREATE TRIGGER account_recent_reauthentication_policy_current_monotonic
    BEFORE UPDATE ON account_recent_reauthentication_policy_current
    FOR EACH ROW EXECUTE FUNCTION account_recent_reauthentication_policy_current_guard();
CREATE TRIGGER account_recent_reauthentication_policy_current_no_delete
    BEFORE DELETE ON account_recent_reauthentication_policy_current
    FOR EACH ROW EXECUTE FUNCTION account_recent_reauthentication_no_mutation();
CREATE TRIGGER account_recent_reauthentication_policy_current_no_truncate
    BEFORE TRUNCATE ON account_recent_reauthentication_policy_current
    FOR EACH STATEMENT EXECUTE FUNCTION account_recent_reauthentication_no_mutation();
CREATE TRIGGER account_recent_reauthentication_evidence_source_guard
    BEFORE INSERT ON account_recent_reauthentication_evidence
    FOR EACH ROW EXECUTE FUNCTION account_recent_reauthentication_evidence_source_guard();
CREATE TRIGGER account_recent_reauthentication_evidence_immutable
    BEFORE UPDATE OR DELETE ON account_recent_reauthentication_evidence
    FOR EACH ROW EXECUTE FUNCTION account_recent_reauthentication_no_mutation();
CREATE TRIGGER account_recent_reauthentication_evidence_no_truncate
    BEFORE TRUNCATE ON account_recent_reauthentication_evidence
    FOR EACH STATEMENT EXECUTE FUNCTION account_recent_reauthentication_no_mutation();
CREATE TRIGGER account_recent_reauthentication_consumption_guard
    BEFORE INSERT ON account_recent_reauthentication_consumptions
    FOR EACH ROW EXECUTE FUNCTION account_recent_reauthentication_consumption_guard();
CREATE TRIGGER account_recent_reauthentication_consumptions_immutable
    BEFORE UPDATE OR DELETE ON account_recent_reauthentication_consumptions
    FOR EACH ROW EXECUTE FUNCTION account_recent_reauthentication_no_mutation();
CREATE TRIGGER account_recent_reauthentication_consumptions_no_truncate
    BEFORE TRUNCATE ON account_recent_reauthentication_consumptions
    FOR EACH STATEMENT EXECUTE FUNCTION account_recent_reauthentication_no_mutation();
-- [jooq ignore stop]
