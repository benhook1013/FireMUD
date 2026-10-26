-- This is immutable per-target evidence of a physical CLOSED readback as of two protected
-- observations. It does not advance ACQUISITION_INTENT or prove the barrier is currently installed.
ALTER TABLE game_session_canonical_account_admission_fence_intent_barrier_target
    ADD CONSTRAINT uq_gs_admission_barrier_target_receipt_ref
        UNIQUE (operation_id, target_ordinal, account_id, tenant_id, playable_state_namespace_id);

CREATE TABLE game_session_canonical_account_closed_barrier_receipt (
    operation_id uuid NOT NULL,
    request_id uuid NOT NULL,
    account_id uuid NOT NULL,
    operation_fence numeric NOT NULL,
    planned_account_admission_fence uuid NOT NULL,
    inventory_snapshot_revision numeric NOT NULL,
    cohort_digest character varying(71) NOT NULL,
    barrier_target_digest character varying(71) NOT NULL,
    target_ordinal bigint NOT NULL,
    tenant_id uuid NOT NULL,
    playable_state_namespace_id uuid NOT NULL,
    tenant_gameplay_tag character varying(44) NOT NULL,
    workload_namespace character varying(63) NOT NULL,
    allocation_version integer NOT NULL,
    barrier_key text NOT NULL,
    barrier_schema character varying(64) NOT NULL,
    incarnation_digest character varying(71) NOT NULL,
    barrier_state character varying(16) NOT NULL,
    accepted_attestation_digest character varying(71) NOT NULL,
    barrier_fields_digest character varying(71) NOT NULL,
    before_source_payload_digest character varying(71) NOT NULL,
    before_observation_issued_at_epoch_millis bigint NOT NULL,
    before_observation_observed_at_epoch_millis bigint NOT NULL,
    before_observation_expires_at_epoch_millis bigint NOT NULL,
    after_source_payload_digest character varying(71) NOT NULL,
    after_observation_issued_at_epoch_millis bigint NOT NULL,
    after_observation_observed_at_epoch_millis bigint NOT NULL,
    after_observation_expires_at_epoch_millis bigint NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_admission_closed_barrier_receipt
        PRIMARY KEY (operation_id, target_ordinal),
    CONSTRAINT fk_gs_admission_closed_receipt_intent
        FOREIGN KEY (operation_id, account_id)
        REFERENCES game_session_canonical_account_admission_fence_intent (operation_id, account_id),
    CONSTRAINT fk_gs_admission_closed_receipt_target
        FOREIGN KEY (
            operation_id,
            target_ordinal,
            account_id,
            tenant_id,
            playable_state_namespace_id)
        REFERENCES game_session_canonical_account_admission_fence_intent_barrier_target (
            operation_id,
            target_ordinal,
            account_id,
            tenant_id,
            playable_state_namespace_id),
    CONSTRAINT chk_gs_admission_closed_barrier_receipt CHECK (
        operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND operation_id <> account_id
        AND operation_id <> request_id
        AND planned_account_admission_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND planned_account_admission_fence <> operation_id
        AND planned_account_admission_fence <> request_id
        AND planned_account_admission_fence <> account_id
        AND operation_fence > 0
        AND inventory_snapshot_revision > 0
        AND cohort_digest ~ '^sha256:[0-9a-f]{64}$'
        AND barrier_target_digest ~ '^sha256:[0-9a-f]{64}$'
        AND target_ordinal >= 0
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_gameplay_tag ~ '^g[1-9][0-9]{0,9}-[0-9a-f]{32}$'
        AND length(tenant_gameplay_tag) BETWEEN 35 AND 44
        AND length(workload_namespace) BETWEEN 1 AND 63
        AND allocation_version > 0
        AND barrier_key = 'session:game:account-admission:{' || tenant_gameplay_tag || '}:'
            || account_id::text || ':' || playable_state_namespace_id::text
        AND barrier_schema = 'gs_account_admission_barrier/v1'
        AND incarnation_digest ~ '^sha256:[0-9a-f]{64}$'
        AND barrier_state = 'CLOSED'
        AND accepted_attestation_digest ~ '^sha256:[0-9a-f]{64}$'
        AND barrier_fields_digest ~ '^sha256:[0-9a-f]{64}$'
        AND before_source_payload_digest ~ '^sha256:[0-9a-f]{64}$'
        AND before_observation_issued_at_epoch_millis > 0
        AND before_observation_observed_at_epoch_millis
            >= before_observation_issued_at_epoch_millis
        AND before_observation_expires_at_epoch_millis
            > before_observation_observed_at_epoch_millis
        AND after_source_payload_digest ~ '^sha256:[0-9a-f]{64}$'
        AND after_observation_issued_at_epoch_millis
            >= before_observation_issued_at_epoch_millis
        AND after_observation_observed_at_epoch_millis
            >= after_observation_issued_at_epoch_millis
        AND after_observation_observed_at_epoch_millis
            >= before_observation_observed_at_epoch_millis
        AND after_observation_expires_at_epoch_millis
            > after_observation_observed_at_epoch_millis)
);

-- The service owner only inserts receipts. Retain the accepted physical postimage as immutable
-- historical evidence; a later installation retry still requires fresh protected readback.
-- [jooq ignore start]
CREATE FUNCTION reject_gs_canonical_account_closed_barrier_receipt_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Canonical CLOSED barrier receipts are immutable'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER gs_canonical_account_closed_barrier_receipt_immutable
BEFORE UPDATE OR DELETE ON game_session_canonical_account_closed_barrier_receipt
FOR EACH ROW EXECUTE FUNCTION reject_gs_canonical_account_closed_barrier_receipt_mutation();

CREATE TRIGGER gs_canonical_account_closed_barrier_receipt_no_truncate
BEFORE TRUNCATE ON game_session_canonical_account_closed_barrier_receipt
FOR EACH STATEMENT EXECUTE FUNCTION reject_gs_canonical_account_closed_barrier_receipt_mutation();
-- [jooq ignore stop]
