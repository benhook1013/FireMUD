-- Durable original-attempt source for the first shard-local Account admission OPEN value.
-- The initial path is separate from account-wide CLOSED acquisition and never reopens a key.

ALTER TABLE game_session_canonical_account_admission_fence_intent_cohort_row
    DROP CONSTRAINT chk_gs_canonical_account_admission_fence_intent_cohort_row;

ALTER TABLE game_session_canonical_account_admission_fence_intent_cohort_row
    ADD CONSTRAINT chk_gs_canonical_account_admission_fence_intent_cohort_row CHECK (
        row_ordinal >= 0
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND source_kind IN (
            'BINDING', 'TRANSITION', 'RESERVATION', 'ACCOUNT_OBLIGATION',
            'ISSUER_OBLIGATION', 'REGION_OBLIGATION', 'MEMBER_REPAIR_CONTROL',
            'MEMBER_REPAIR_OPERATION', 'INITIALIZER', 'INITIAL_TARGET')
        AND length(source_key) > 0
        AND ((tenant_id IS NULL AND playable_state_namespace_id IS NULL)
            OR (tenant_id IS NOT NULL
                AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
                AND playable_state_namespace_id IS NOT NULL
                AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid))
        AND jsonb_typeof(source_row) = 'object');

CREATE TABLE game_session_canonical_initial_account_barrier_initializer (
    initializer_id uuid NOT NULL,
    original_request_id uuid NOT NULL,
    original_caller_context_id uuid NOT NULL,
    original_attempt_fence bigint NOT NULL,
    account_id uuid NOT NULL,
    account_login_evidence_digest character varying(71) NOT NULL,
    source_control_state character varying(32) NOT NULL,
    source_control_epoch numeric NOT NULL,
    inventory_snapshot_revision numeric NOT NULL,
    source_control_digest character varying(71) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_initial_account_barrier_initializer PRIMARY KEY (initializer_id),
    CONSTRAINT uq_gs_initial_account_barrier_initializer_request UNIQUE (original_request_id),
    CONSTRAINT uq_gs_initial_account_barrier_initializer_scope
        UNIQUE (initializer_id, original_request_id, account_id),
    CONSTRAINT fk_gs_initial_account_barrier_initializer_control
        FOREIGN KEY (account_id)
        REFERENCES game_session_canonical_account_coverage_control (account_id),
    CONSTRAINT fk_gs_initial_account_barrier_initializer_original
        FOREIGN KEY (original_request_id)
        REFERENCES game_session_initial_gameplay_login_journal (request_id),
    CONSTRAINT chk_gs_initial_account_barrier_initializer CHECK (
        initializer_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND original_request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND original_caller_context_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND original_attempt_fence > 0
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND account_login_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_control_state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW'
        AND source_control_epoch >= 0
        AND length(source_control_epoch::text) <= 256
        AND inventory_snapshot_revision > 0
        AND source_control_digest ~ '^sha256:[0-9a-f]{64}$')
);

CREATE TABLE game_session_canonical_initial_account_barrier_target (
    initializer_id uuid NOT NULL,
    original_request_id uuid NOT NULL,
    account_id uuid NOT NULL,
    target_ordinal bigint NOT NULL,
    tenant_id uuid NOT NULL,
    playable_state_namespace_id uuid NOT NULL,
    target_state character varying(32) NOT NULL,
    before_accepted_attestation_digest character varying(71),
    before_incarnation_digest character varying(71),
    before_source_payload_digest character varying(71),
    before_observation_issued_at_epoch_millis bigint,
    before_observation_observed_at_epoch_millis bigint,
    before_observation_expires_at_epoch_millis bigint,
    installed_fields_digest character varying(71),
    completed_at timestamp with time zone,
    CONSTRAINT pk_gs_initial_account_barrier_target
        PRIMARY KEY (initializer_id, target_ordinal),
    CONSTRAINT uq_gs_initial_account_barrier_target_scope
        UNIQUE (initializer_id, target_ordinal, account_id, tenant_id, playable_state_namespace_id),
    CONSTRAINT uq_gs_initial_account_barrier_never_retarget
        UNIQUE (account_id, tenant_id, playable_state_namespace_id),
    CONSTRAINT fk_gs_initial_account_barrier_target_initializer
        FOREIGN KEY (initializer_id, original_request_id, account_id)
        REFERENCES game_session_canonical_initial_account_barrier_initializer
            (initializer_id, original_request_id, account_id),
    CONSTRAINT chk_gs_initial_account_barrier_target CHECK (
        target_ordinal = 0
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND ((target_state = 'NEVER_DISPATCHED'
                AND before_accepted_attestation_digest IS NULL
                AND before_incarnation_digest IS NULL
                AND before_source_payload_digest IS NULL
                AND before_observation_issued_at_epoch_millis IS NULL
                AND before_observation_observed_at_epoch_millis IS NULL
                AND before_observation_expires_at_epoch_millis IS NULL
                AND installed_fields_digest IS NULL AND completed_at IS NULL)
            OR (target_state = 'DISPATCH_POSSIBLE'
                AND before_accepted_attestation_digest IS NOT NULL
                AND before_accepted_attestation_digest ~ '^sha256:[0-9a-f]{64}$'
                AND before_incarnation_digest IS NOT NULL
                AND before_incarnation_digest ~ '^sha256:[0-9a-f]{64}$'
                AND before_source_payload_digest IS NOT NULL
                AND before_source_payload_digest ~ '^sha256:[0-9a-f]{64}$'
                AND before_observation_issued_at_epoch_millis IS NOT NULL
                AND before_observation_issued_at_epoch_millis > 0
                AND before_observation_observed_at_epoch_millis IS NOT NULL
                AND before_observation_observed_at_epoch_millis
                    >= before_observation_issued_at_epoch_millis
                AND before_observation_expires_at_epoch_millis IS NOT NULL
                AND before_observation_expires_at_epoch_millis
                    > before_observation_observed_at_epoch_millis
                AND installed_fields_digest IS NULL AND completed_at IS NULL)
            OR (target_state = 'OPEN_INSTALLED'
                AND before_accepted_attestation_digest IS NOT NULL
                AND before_accepted_attestation_digest ~ '^sha256:[0-9a-f]{64}$'
                AND before_incarnation_digest IS NOT NULL
                AND before_incarnation_digest ~ '^sha256:[0-9a-f]{64}$'
                AND before_source_payload_digest IS NOT NULL
                AND before_source_payload_digest ~ '^sha256:[0-9a-f]{64}$'
                AND before_observation_issued_at_epoch_millis IS NOT NULL
                AND before_observation_issued_at_epoch_millis > 0
                AND before_observation_observed_at_epoch_millis IS NOT NULL
                AND before_observation_observed_at_epoch_millis
                    >= before_observation_issued_at_epoch_millis
                AND before_observation_expires_at_epoch_millis IS NOT NULL
                AND before_observation_expires_at_epoch_millis
                    > before_observation_observed_at_epoch_millis
                AND installed_fields_digest IS NOT NULL
                AND installed_fields_digest ~ '^sha256:[0-9a-f]{64}$'
                AND completed_at IS NOT NULL)))
);

-- [jooq ignore start]
CREATE FUNCTION guard_gs_initial_account_barrier_initializer() RETURNS trigger AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'Initial account barrier initializer retention is held' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.created_at IS NULL THEN
            RAISE EXCEPTION 'Initial account barrier initializer creation time is required'
                USING ERRCODE = '23514';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM game_session_initial_gameplay_login_journal original
            WHERE original.request_id = NEW.original_request_id
              AND original.caller_context_id = NEW.original_caller_context_id
              AND original.expected_attempt_fence = NEW.original_attempt_fence
              AND original.abandoned = FALSE
              AND original.verified_token_jti IS NOT NULL
              AND original.verified_token_sha256 IS NOT NULL
              AND original.verified_token_generation IS NOT NULL
              AND original.verified_issuance_fence IS NOT NULL) THEN
            RAISE EXCEPTION 'Initial account barrier initializer differs from retained original LOGIN'
                USING ERRCODE = '23514';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM game_session_canonical_account_coverage_control control
            WHERE control.account_id = NEW.account_id
              AND control.state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW'
              AND control.last_operation_fence = NEW.source_control_epoch
              AND control.current_operation_id IS NULL
              AND control.current_fence_intent_id IS NULL
              AND control.current_operation_fence IS NULL
              AND control.account_admission_fence IS NULL
              AND control.historical_account_admission_fence IS NULL
              AND control.coverage_fence IS NULL
              AND control.inventory_snapshot_revision IS NULL
              AND control.coverage_generation IS NULL)
           OR NOT EXISTS (
            SELECT 1 FROM game_session_canonical_binding_inventory_clock inventory_clock
            WHERE inventory_clock.singleton_id = 1
              AND inventory_clock.inventory_revision = NEW.inventory_snapshot_revision
              AND inventory_clock.inventory_revision > 0) THEN
            RAISE EXCEPTION 'Initial account barrier requires positive current SQL and inventory sources'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.initializer_id, NEW.original_request_id, NEW.original_caller_context_id,
           NEW.original_attempt_fence, NEW.account_id, NEW.source_control_state,
           NEW.account_login_evidence_digest, NEW.source_control_epoch, NEW.inventory_snapshot_revision,
           NEW.source_control_digest, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.initializer_id, OLD.original_request_id, OLD.original_caller_context_id,
           OLD.original_attempt_fence, OLD.account_id, OLD.source_control_state,
           OLD.account_login_evidence_digest, OLD.source_control_epoch, OLD.inventory_snapshot_revision,
           OLD.source_control_digest, OLD.created_at) THEN
        RAISE EXCEPTION 'Initial account barrier initializer source is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER gs_initial_account_barrier_initializer_guard
    BEFORE INSERT OR UPDATE OR DELETE ON game_session_canonical_initial_account_barrier_initializer
    FOR EACH ROW EXECUTE FUNCTION guard_gs_initial_account_barrier_initializer();
CREATE TRIGGER gs_initial_account_barrier_initializer_retention
    BEFORE TRUNCATE ON game_session_canonical_initial_account_barrier_initializer
    FOR EACH STATEMENT EXECUTE FUNCTION guard_gs_initial_account_barrier_initializer();

CREATE FUNCTION guard_gs_initial_account_barrier_target() RETURNS trigger AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'Initial account barrier target retention is held' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.target_state <> 'NEVER_DISPATCHED'
           OR NEW.before_incarnation_digest IS NOT NULL
           OR NEW.installed_fields_digest IS NOT NULL THEN
            RAISE EXCEPTION 'Initial account barrier target must begin never-installed'
                USING ERRCODE = '23514';
        END IF;
        IF NOT EXISTS (
            SELECT 1
              FROM game_session_canonical_account_coverage_control control
              JOIN game_session_canonical_initial_account_barrier_initializer initializer
                ON initializer.initializer_id = NEW.initializer_id
               AND initializer.original_request_id = NEW.original_request_id
               AND initializer.account_id = NEW.account_id
            WHERE control.account_id = NEW.account_id
              AND control.state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW'
              AND control.last_operation_fence = initializer.source_control_epoch
              AND control.current_operation_id IS NULL
              AND control.current_fence_intent_id IS NULL
              AND control.current_operation_fence IS NULL
              AND control.account_admission_fence IS NULL
              AND control.historical_account_admission_fence IS NULL
              AND control.coverage_fence IS NULL
              AND control.inventory_snapshot_revision IS NULL
              AND control.coverage_generation IS NULL) THEN
            RAISE EXCEPTION 'Initial account target requires its positive captured NO_ACTIVE SQL source'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.initializer_id, NEW.original_request_id, NEW.account_id, NEW.target_ordinal,
           NEW.tenant_id, NEW.playable_state_namespace_id)
       IS DISTINCT FROM
       ROW(OLD.initializer_id, OLD.original_request_id, OLD.account_id, OLD.target_ordinal,
           OLD.tenant_id, OLD.playable_state_namespace_id) THEN
        RAISE EXCEPTION 'Initial account barrier target identity is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.target_state = 'NEVER_DISPATCHED' AND NEW.target_state = 'DISPATCH_POSSIBLE'
       AND NEW.before_incarnation_digest IS NOT NULL
       AND NEW.installed_fields_digest IS NULL AND NEW.completed_at IS NULL
       AND EXISTS (
           SELECT 1
             FROM game_session_canonical_account_coverage_control control
             JOIN game_session_canonical_initial_account_barrier_initializer initializer
               ON initializer.initializer_id = NEW.initializer_id
              AND initializer.original_request_id = NEW.original_request_id
              AND initializer.account_id = NEW.account_id
           WHERE control.account_id = NEW.account_id
             AND control.state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW'
             AND control.last_operation_fence = initializer.source_control_epoch) THEN
        RETURN NEW;
    END IF;
    IF OLD.target_state = 'DISPATCH_POSSIBLE' AND NEW.target_state = 'OPEN_INSTALLED'
       AND ROW(NEW.before_accepted_attestation_digest, NEW.before_incarnation_digest,
               NEW.before_source_payload_digest, NEW.before_observation_issued_at_epoch_millis,
               NEW.before_observation_observed_at_epoch_millis,
               NEW.before_observation_expires_at_epoch_millis)
           IS NOT DISTINCT FROM
           ROW(OLD.before_accepted_attestation_digest, OLD.before_incarnation_digest,
               OLD.before_source_payload_digest, OLD.before_observation_issued_at_epoch_millis,
               OLD.before_observation_observed_at_epoch_millis,
               OLD.before_observation_expires_at_epoch_millis)
       AND NEW.installed_fields_digest IS NOT NULL AND NEW.completed_at IS NOT NULL
       AND EXISTS (
           SELECT 1
             FROM game_session_canonical_account_coverage_control control
             JOIN game_session_canonical_initial_account_barrier_initializer initializer
               ON initializer.initializer_id = NEW.initializer_id
              AND initializer.original_request_id = NEW.original_request_id
              AND initializer.account_id = NEW.account_id
           WHERE control.account_id = NEW.account_id
             AND control.state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW'
             AND control.last_operation_fence = initializer.source_control_epoch) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Initial account barrier target cannot reset or change its original attempt'
        USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER gs_initial_account_barrier_target_guard
    BEFORE INSERT OR UPDATE OR DELETE ON game_session_canonical_initial_account_barrier_target
    FOR EACH ROW EXECUTE FUNCTION guard_gs_initial_account_barrier_target();
CREATE TRIGGER gs_initial_account_barrier_target_retention
    BEFORE TRUNCATE ON game_session_canonical_initial_account_barrier_target
    FOR EACH STATEMENT EXECUTE FUNCTION guard_gs_initial_account_barrier_target();
-- [jooq ignore stop]
