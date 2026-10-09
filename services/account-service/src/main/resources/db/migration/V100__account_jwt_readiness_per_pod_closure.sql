-- V2 readiness plans bind every protected live validator Pod and retain one immutable production
-- verification receipt per Pod and logical probe. Existing V1 plans and receipts remain retained
-- and partial; they are never rewritten into V2 closure evidence.

ALTER TABLE account_jwt_readiness_probe_plans
    DROP CONSTRAINT account_jwt_readiness_plan_window_check;

ALTER TABLE account_jwt_readiness_probe_plans
    ADD CONSTRAINT account_jwt_readiness_plan_window_check
        CHECK (maximum_cache_age_seconds BETWEEN 1 AND 300
            AND planned_at_epoch_seconds > 0
            AND not_before_epoch_seconds >= planned_at_epoch_seconds + maximum_cache_age_seconds
            AND expires_at_epoch_seconds > not_before_epoch_seconds
            AND expires_at_epoch_seconds - not_before_epoch_seconds <= 300
            AND plan_version IN (1, 2)
            AND (plan_version <> 2 OR inventory_snapshot_digest IS NOT NULL));

ALTER TABLE account_jwt_readiness_probe_entries
    ADD COLUMN entry_plan_version SMALLINT NOT NULL DEFAULT 1;

ALTER TABLE account_jwt_readiness_probe_entries
    ADD COLUMN pod_receipt_count SMALLINT;

ALTER TABLE account_jwt_readiness_probe_entries
    ADD COLUMN pod_receipt_closure_sha256 VARCHAR(64);

ALTER TABLE account_jwt_readiness_probe_entries
    DROP CONSTRAINT account_jwt_readiness_probe_evidence_check;

ALTER TABLE account_jwt_readiness_probe_entries
    ADD CONSTRAINT account_jwt_readiness_probe_evidence_check
        CHECK (plan_digest ~ '^[0-9a-f]{64}$'
            AND registry_version = 1
            AND entry_plan_version IN (1, 2)
            AND entry_version > 0
            AND planned_issued_at_epoch_seconds > 0
            AND expires_at_epoch_seconds > planned_issued_at_epoch_seconds
            AND (compact_token_sha256 IS NULL OR compact_token_sha256 ~ '^[0-9a-f]{64}$')
            AND (signing_attempted_at_epoch_seconds IS NULL
                OR (signing_attempted_at_epoch_seconds >= planned_issued_at_epoch_seconds
                    AND signing_attempted_at_epoch_seconds < expires_at_epoch_seconds))
            AND ((compact_token_sha256 IS NULL AND signing_attempted_at_epoch_seconds IS NULL)
                OR (compact_token_sha256 IS NOT NULL AND signing_attempted_at_epoch_seconds IS NOT NULL))
            AND (state NOT IN ('ISSUED', 'VERIFIED', 'RETIRED') OR compact_token_sha256 IS NOT NULL)
            AND (verification_receipt_version IS NULL
                OR (entry_plan_version = 1 AND verification_receipt_version = 1)
                OR (entry_plan_version = 2 AND verification_receipt_version = 2))
            AND (verification_source_entry_version IS NULL OR verification_source_entry_version > 0)
            AND (verified_at_epoch_seconds IS NULL
                OR (verified_at_epoch_seconds >= planned_issued_at_epoch_seconds
                    AND verified_at_epoch_seconds < expires_at_epoch_seconds))
            AND (verified_kid IS NULL OR verified_kid = target_kid)
            AND (validator_instance_id IS NULL
                OR validator_instance_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$')
            AND (validator_binding_digest IS NULL OR validator_binding_digest ~ '^[0-9a-f]{64}$')
            AND (validator_config_revision IS NULL
                OR validator_config_revision ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$')
            AND (validator_peer_uri IS NULL
                OR validator_peer_uri ~ '^spiffe://firemud/ns/[a-z0-9-]{1,63}/sa/[a-z0-9][a-z0-9-]{0,62}$')
            AND (validator_peer_spki_sha256 IS NULL
                OR validator_peer_spki_sha256 ~ '^[0-9a-f]{64}$')
            AND (verification_receipt_sha256 IS NULL
                OR verification_receipt_sha256 ~ '^[0-9a-f]{64}$')
            AND (pod_receipt_count IS NULL OR pod_receipt_count BETWEEN 1 AND 256)
            AND (pod_receipt_closure_sha256 IS NULL
                OR pod_receipt_closure_sha256 ~ '^[0-9a-f]{64}$')
            AND ((verification_receipt_sha256 IS NULL
                    AND verification_receipt_version IS NULL
                    AND verification_source_entry_version IS NULL
                    AND verified_at_epoch_seconds IS NULL
                    AND verified_kid IS NULL
                    AND validator_instance_id IS NULL
                    AND validator_binding_digest IS NULL
                    AND validator_config_revision IS NULL
                    AND validator_peer_uri IS NULL
                    AND validator_peer_spki_sha256 IS NULL
                    AND pod_receipt_count IS NULL
                    AND pod_receipt_closure_sha256 IS NULL)
                OR (verification_receipt_sha256 IS NOT NULL
                    AND verification_receipt_version = 1
                    AND entry_plan_version = 1
                    AND verification_source_entry_version IS NOT NULL
                    AND verified_at_epoch_seconds IS NOT NULL
                    AND verified_kid IS NOT NULL
                    AND validator_instance_id IS NOT NULL
                    AND validator_binding_digest IS NOT NULL
                    AND validator_config_revision IS NOT NULL
                    AND validator_peer_uri IS NOT NULL
                    AND validator_peer_spki_sha256 IS NOT NULL
                    AND pod_receipt_count IS NULL
                    AND pod_receipt_closure_sha256 IS NULL)
                OR (verification_receipt_sha256 IS NOT NULL
                    AND verification_receipt_version = 2
                    AND entry_plan_version = 2
                    AND verification_source_entry_version IS NOT NULL
                    AND verified_at_epoch_seconds IS NOT NULL
                    AND verified_kid = target_kid
                    AND validator_instance_id IS NOT NULL
                    AND validator_binding_digest IS NOT NULL
                    AND validator_config_revision IS NOT NULL
                    AND validator_peer_uri IS NOT NULL
                    AND validator_peer_spki_sha256 IS NOT NULL
                    AND pod_receipt_count IS NOT NULL
                    AND pod_receipt_closure_sha256 = verification_receipt_sha256))
            AND (state NOT IN ('VERIFIED', 'RETIRED') OR verification_receipt_sha256 IS NOT NULL)
            AND (state NOT IN ('PLANNED', 'ISSUED') OR verification_receipt_sha256 IS NULL));

CREATE TABLE account_jwt_readiness_validator_pods (
    rotation_operation_id UUID NOT NULL,
    plan_digest VARCHAR(64) NOT NULL,
    validator_id VARCHAR(63) NOT NULL,
    token_profile VARCHAR(128) NOT NULL,
    audience VARCHAR(128) NOT NULL,
    probe_kind VARCHAR(16) NOT NULL,
    jti UUID NOT NULL,
    inventory_snapshot_digest VARCHAR(64) NOT NULL,
    environment_id VARCHAR(63) NOT NULL,
    cluster_id VARCHAR(128) NOT NULL,
    cluster_incarnation_uid UUID NOT NULL,
    kubernetes_namespace VARCHAR(63) NOT NULL,
    namespace_uid UUID NOT NULL,
    api_binding_revision VARCHAR(128) NOT NULL,
    api_binding_digest VARCHAR(64) NOT NULL,
    inventory_binding_revision VARCHAR(128) NOT NULL,
    inventory_binding_digest VARCHAR(64) NOT NULL,
    deployment_uid UUID NOT NULL,
    pod_uid UUID NOT NULL,
    pod_ip VARCHAR(45) NOT NULL,
    image VARCHAR(512) NOT NULL,
    verifier_config_sha256 VARCHAR(64) NOT NULL,
    applicability_matrix_digest VARCHAR(64) NOT NULL,
    expected_outcome VARCHAR(32) NOT NULL,
    exact_pod_endpoint VARCHAR(512) NOT NULL,
    canonical_service_uri VARCHAR(256) NOT NULL,
    expected_pod_leaf_spki_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (rotation_operation_id, validator_id, token_profile, audience, probe_kind, pod_uid),
    CONSTRAINT account_jwt_readiness_validator_pod_entry_fk
        FOREIGN KEY (rotation_operation_id, validator_id, token_profile, audience, probe_kind)
        REFERENCES account_jwt_readiness_probe_entries(
            rotation_operation_id, validator_id, token_profile, audience, probe_kind)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_readiness_validator_pod_snapshot_fk
        FOREIGN KEY (inventory_snapshot_digest, environment_id, cluster_id, kubernetes_namespace,
            cluster_incarnation_uid, namespace_uid)
        REFERENCES account_jwt_validator_inventory_snapshots(
            snapshot_digest, environment_id, cluster_id, kubernetes_namespace,
            cluster_incarnation_uid, namespace_uid)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_readiness_validator_pod_shape_check
        CHECK (rotation_operation_id::text ~
                '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
            AND jti::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
            AND plan_digest ~ '^[0-9a-f]{64}$'
            AND inventory_snapshot_digest ~ '^[0-9a-f]{64}$'
            AND validator_id ~ '^[a-z0-9][a-z0-9-]{0,62}$'
            AND token_profile ~ '^[a-z0-9][a-z0-9-]{0,127}$'
            AND audience ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            AND probe_kind IN ('CANARY', 'REPRESENTATIVE')
            AND environment_id ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
            AND cluster_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            AND cluster_incarnation_uid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND kubernetes_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
            AND namespace_uid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND api_binding_revision ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            AND api_binding_digest ~ '^[0-9a-f]{64}$'
            AND inventory_binding_revision ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            AND inventory_binding_digest ~ '^[0-9a-f]{64}$'
            AND deployment_uid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND pod_uid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND (pod_ip ~ '^(?:[0-9]{1,3}[.]){3}[0-9]{1,3}$' OR pod_ip ~ '^[0-9a-f:]+$')
            AND image ~ '^[^[:space:]@]+@sha256:[0-9a-f]{64}$'
            AND verifier_config_sha256 ~ '^[0-9a-f]{64}$'
            AND applicability_matrix_digest ~ '^[0-9a-f]{64}$'
            AND expected_outcome IN ('ACCEPT', 'INAPPLICABLE_REJECT')
            AND exact_pod_endpoint ~ '^grpcs://[^/?#]+:[0-9]{1,5}$'
            AND canonical_service_uri ~
                '^spiffe://firemud/ns/[a-z0-9-]{1,63}/sa/[a-z0-9][a-z0-9-]{0,62}$'
            AND expected_pod_leaf_spki_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE account_jwt_readiness_pod_receipts (
    rotation_operation_id UUID NOT NULL,
    plan_digest VARCHAR(64) NOT NULL,
    validator_id VARCHAR(63) NOT NULL,
    token_profile VARCHAR(128) NOT NULL,
    audience VARCHAR(128) NOT NULL,
    probe_kind VARCHAR(16) NOT NULL,
    pod_uid UUID NOT NULL,
    receipt_version SMALLINT NOT NULL,
    jti UUID NOT NULL,
    source_entry_version BIGINT NOT NULL,
    compact_token_sha256 VARCHAR(64) NOT NULL,
    target_generation BIGINT NOT NULL,
    target_kid VARCHAR(64) NOT NULL,
    expected_active_generation BIGINT,
    expected_active_kid VARCHAR(64),
    outcome VARCHAR(32) NOT NULL,
    observed_pod_uid UUID NOT NULL,
    observed_image VARCHAR(512) NOT NULL,
    observed_verifier_config_sha256 VARCHAR(64) NOT NULL,
    verified_kid VARCHAR(64),
    receiver_endpoint VARCHAR(512) NOT NULL,
    receiver_service_uri VARCHAR(256) NOT NULL,
    receiver_peer_spki_sha256 VARCHAR(64) NOT NULL,
    observed_at_epoch_seconds BIGINT NOT NULL,
    receipt_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (rotation_operation_id, validator_id, token_profile, audience, probe_kind, pod_uid),
    CONSTRAINT account_jwt_readiness_pod_receipt_target_fk
        FOREIGN KEY (rotation_operation_id, validator_id, token_profile, audience, probe_kind, pod_uid)
        REFERENCES account_jwt_readiness_validator_pods(
            rotation_operation_id, validator_id, token_profile, audience, probe_kind, pod_uid)
        ON DELETE RESTRICT,
    CONSTRAINT account_jwt_readiness_pod_receipt_shape_check
        CHECK (receipt_version = 1
            AND source_entry_version > 0
            AND compact_token_sha256 ~ '^[0-9a-f]{64}$'
            AND target_generation > 0
            AND target_kid ~ '^[A-Za-z0-9_-]{1,64}$'
            AND ((expected_active_generation IS NULL AND expected_active_kid IS NULL)
                OR (expected_active_generation > 0
                    AND expected_active_kid ~ '^[A-Za-z0-9_-]{1,64}$'))
            AND target_kid <> expected_active_kid
            AND outcome IN ('ACCEPT', 'INAPPLICABLE_REJECT')
            AND observed_pod_uid = pod_uid
            AND observed_image ~ '^[^[:space:]@]+@sha256:[0-9a-f]{64}$'
            AND observed_verifier_config_sha256 ~ '^[0-9a-f]{64}$'
            AND (verified_kid IS NULL OR verified_kid = target_kid)
            AND receiver_endpoint ~ '^grpcs://[^/?#]+:[0-9]{1,5}$'
            AND receiver_service_uri ~
                '^spiffe://firemud/ns/[a-z0-9-]{1,63}/sa/[a-z0-9][a-z0-9-]{0,62}$'
            AND receiver_peer_spki_sha256 ~ '^[0-9a-f]{64}$'
            AND observed_at_epoch_seconds > 0
            AND receipt_sha256 ~ '^[0-9a-f]{64}$')
);

-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_jwt_readiness_plan_inventory_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    matrix jsonb;
    account_profiles jsonb;
    game_session_profiles jsonb;
BEGIN
    matrix := NEW.applicability_matrix_json::jsonb;
    IF NEW.plan_version = 1 THEN
        IF NEW.validator_inventory_complete
           OR matrix ->> 'inventoryStatus' <> 'PARTIAL_UNCONFIRMED'
           OR matrix ->> 'schemaVersion' <> '1'
           OR jsonb_array_length(matrix -> 'validators') <> 1
           OR matrix #>> '{validators,0,validatorId}' <> 'account-service'
           OR jsonb_array_length(matrix #> '{validators,0,applicableProfiles}') <> 3
           OR NOT (matrix #> '{validators,0,applicableProfiles}' @>
              '[{"tokenProfile":"control-ui","audience":"control-ui"}]'::jsonb)
           OR NOT (matrix #> '{validators,0,applicableProfiles}' @>
              '[{"tokenProfile":"player-bootstrap","audience":"player-bootstrap"}]'::jsonb)
           OR NOT (matrix #> '{validators,0,applicableProfiles}' @>
              '[{"tokenProfile":"game-session-account-delegation","audience":"account-service"}]'::jsonb) THEN
            RAISE EXCEPTION 'Legacy readiness plans remain explicitly partial and Account-only'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.plan_version <> 2
       OR NEW.inventory_snapshot_digest IS NULL
       OR matrix ->> 'schemaVersion' <> '2'
       OR matrix ->> 'inventorySnapshotDigest' IS DISTINCT FROM NEW.inventory_snapshot_digest
       OR jsonb_array_length(matrix -> 'validators') < 1
       OR jsonb_array_length(matrix -> 'validators') > 32
       OR jsonb_array_length(matrix -> 'productionProfiles') <> 3 THEN
        RAISE EXCEPTION 'Readiness V2 plan must bind its protected live snapshot and closed profile family'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.validator_inventory_complete THEN
        SELECT value -> 'applicableProfiles' INTO account_profiles
          FROM jsonb_array_elements(matrix -> 'validators') AS validator(value)
         WHERE value ->> 'validatorId' = 'account-service';
        SELECT value -> 'applicableProfiles' INTO game_session_profiles
          FROM jsonb_array_elements(matrix -> 'validators') AS validator(value)
         WHERE value ->> 'validatorId' = 'game-session-service';
        IF matrix ->> 'inventoryStatus' <> 'PROTECTED_LIVE_COMPLETE'
           OR jsonb_array_length(matrix -> 'validators') <> 2
           OR account_profiles IS NULL
           OR jsonb_array_length(account_profiles) <> 3
           OR NOT (account_profiles @> '[{"tokenProfile":"control-ui","audience":"control-ui"}]'::jsonb)
           OR NOT (account_profiles @> '[{"tokenProfile":"player-bootstrap","audience":"player-bootstrap"}]'::jsonb)
           OR NOT (account_profiles @> '[{"tokenProfile":"game-session-account-delegation","audience":"account-service"}]'::jsonb)
           OR game_session_profiles IS NULL
           OR jsonb_array_length(game_session_profiles) <> 1
           OR NOT (game_session_profiles @> '[{"tokenProfile":"game-session-account-delegation","audience":"account-service"}]'::jsonb) THEN
            RAISE EXCEPTION 'Readiness V2 completion requires exact Account and Game Session profile inventory'
                USING ERRCODE = '23514';
        END IF;
    ELSIF matrix ->> 'inventoryStatus' <> 'PARTIAL_UNCONFIRMED' THEN
        RAISE EXCEPTION 'Incomplete Readiness V2 inventory must remain explicitly partial'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_jwt_readiness_entry_plan_version_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    plan_row account_jwt_readiness_probe_plans%ROWTYPE;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.entry_plan_version IS DISTINCT FROM OLD.entry_plan_version THEN
        RAISE EXCEPTION 'Readiness entry schema version is immutable' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO plan_row
      FROM account_jwt_readiness_probe_plans
     WHERE rotation_operation_id = NEW.rotation_operation_id
       AND plan_digest = NEW.plan_digest;
    IF NOT FOUND OR NEW.entry_plan_version IS DISTINCT FROM plan_row.plan_version THEN
        RAISE EXCEPTION 'Readiness entry schema differs from its immutable plan' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_jwt_readiness_entry_plan_version_binding
    BEFORE INSERT OR UPDATE ON account_jwt_readiness_probe_entries
    FOR EACH ROW EXECUTE FUNCTION account_jwt_readiness_entry_plan_version_guard();

DROP TRIGGER account_jwt_readiness_entry_lifecycle ON account_jwt_readiness_probe_entries;
CREATE TRIGGER account_jwt_readiness_entry_lifecycle_v1
    BEFORE INSERT OR UPDATE ON account_jwt_readiness_probe_entries
    FOR EACH ROW WHEN (NEW.entry_plan_version = 1)
    EXECUTE FUNCTION account_jwt_readiness_entry_lifecycle_guard();
CREATE TRIGGER account_jwt_readiness_entry_delete_guard
    BEFORE DELETE ON account_jwt_readiness_probe_entries
    FOR EACH ROW EXECUTE FUNCTION account_jwt_readiness_entry_lifecycle_guard();

CREATE FUNCTION account_jwt_readiness_expected_pod_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    plan_row account_jwt_readiness_probe_plans%ROWTYPE;
    entry_row account_jwt_readiness_probe_entries%ROWTYPE;
    snapshot_json jsonb;
    validator_json jsonb;
    pod_json jsonb;
    expected_outcome_value text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Expected readiness Pod bindings are immutable' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO plan_row
      FROM account_jwt_readiness_probe_plans
     WHERE rotation_operation_id = NEW.rotation_operation_id
       AND plan_digest = NEW.plan_digest;
    SELECT * INTO entry_row
      FROM account_jwt_readiness_probe_entries
     WHERE rotation_operation_id = NEW.rotation_operation_id
       AND validator_id = NEW.validator_id
       AND token_profile = NEW.token_profile
       AND audience = NEW.audience
       AND probe_kind = NEW.probe_kind;
    SELECT convert_from(canonical_snapshot, 'UTF8')::jsonb INTO snapshot_json
      FROM account_jwt_validator_inventory_snapshots
     WHERE snapshot_digest = NEW.inventory_snapshot_digest;
    IF NOT FOUND OR plan_row.plan_version <> 2 OR entry_row.entry_plan_version <> 2
       OR entry_row.jti IS DISTINCT FROM NEW.jti
       OR plan_row.inventory_snapshot_digest IS DISTINCT FROM NEW.inventory_snapshot_digest
       OR plan_row.applicability_matrix_digest IS DISTINCT FROM NEW.applicability_matrix_digest
       OR (plan_row.environment_id, plan_row.cluster_id, plan_row.kubernetes_namespace,
           plan_row.expected_cluster_incarnation_uid, plan_row.expected_namespace_uid)
          IS DISTINCT FROM (NEW.environment_id, NEW.cluster_id, NEW.kubernetes_namespace,
                            NEW.cluster_incarnation_uid, NEW.namespace_uid)
       OR NOT EXISTS (
            SELECT 1
              FROM jsonb_array_elements(snapshot_json -> 'validators') AS validator(value)
              CROSS JOIN LATERAL jsonb_array_elements(validator.value -> 'pods') AS pod(value)
             WHERE validator.value ->> 'validatorId' = NEW.validator_id
               AND validator.value ->> 'deploymentUid' = NEW.deployment_uid::text
               AND pod.value ->> 'uid' = NEW.pod_uid::text
               AND pod.value ->> 'podIp' = NEW.pod_ip
               AND pod.value ->> 'exactPodEndpoint' = NEW.exact_pod_endpoint
               AND pod.value ->> 'canonicalServiceUri' = NEW.canonical_service_uri
               AND pod.value ->> 'leafSpkiSha256' = NEW.expected_pod_leaf_spki_sha256
               AND pod.value ->> 'image' = NEW.image
               AND pod.value ->> 'verifierConfigSha256' = NEW.verifier_config_sha256
               AND validator.value ->> 'image' = NEW.image
               AND validator.value ->> 'verifierConfigSha256' = NEW.verifier_config_sha256)
       OR (NEW.probe_kind = 'CANARY' AND NEW.expected_outcome <> 'ACCEPT')
       OR (NEW.probe_kind = 'REPRESENTATIVE' AND (
            (NEW.expected_outcome = 'ACCEPT') IS DISTINCT FROM EXISTS (
                SELECT 1
                  FROM jsonb_array_elements(snapshot_json -> 'validators') AS validator(value)
                  CROSS JOIN LATERAL jsonb_array_elements(validator.value -> 'profiles') AS profile(value)
                 WHERE validator.value ->> 'validatorId' = NEW.validator_id
                   AND profile.value ->> 'tokenProfile' = NEW.token_profile
                   AND profile.value ->> 'audience' = NEW.audience))) THEN
        RAISE EXCEPTION 'Expected readiness Pod differs from its exact protected inventory and entry'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_jwt_readiness_expected_pod_insert_guard
    BEFORE INSERT OR UPDATE OR DELETE ON account_jwt_readiness_validator_pods
    FOR EACH ROW EXECUTE FUNCTION account_jwt_readiness_expected_pod_guard();

CREATE FUNCTION account_jwt_readiness_pod_receipt_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    plan_row account_jwt_readiness_probe_plans%ROWTYPE;
    entry_row account_jwt_readiness_probe_entries%ROWTYPE;
    pod_row account_jwt_readiness_validator_pods%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Readiness Pod verification receipts are immutable' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO plan_row
      FROM account_jwt_readiness_probe_plans
     WHERE rotation_operation_id = NEW.rotation_operation_id
       AND plan_digest = NEW.plan_digest;
    SELECT * INTO entry_row
      FROM account_jwt_readiness_probe_entries
     WHERE rotation_operation_id = NEW.rotation_operation_id
       AND validator_id = NEW.validator_id
       AND token_profile = NEW.token_profile
       AND audience = NEW.audience
       AND probe_kind = NEW.probe_kind
     FOR UPDATE;
    SELECT * INTO pod_row
      FROM account_jwt_readiness_validator_pods
     WHERE rotation_operation_id = NEW.rotation_operation_id
       AND validator_id = NEW.validator_id
       AND token_profile = NEW.token_profile
       AND audience = NEW.audience
       AND probe_kind = NEW.probe_kind
       AND pod_uid = NEW.pod_uid
     FOR KEY SHARE;
    IF plan_row.plan_version <> 2 OR plan_row.validator_inventory_complete IS DISTINCT FROM TRUE
       OR entry_row.entry_plan_version <> 2 OR entry_row.state <> 'ISSUED'
       OR entry_row.jti IS DISTINCT FROM NEW.jti
       OR entry_row.entry_version IS DISTINCT FROM NEW.source_entry_version
       OR entry_row.compact_token_sha256 IS DISTINCT FROM NEW.compact_token_sha256
       OR entry_row.target_generation IS DISTINCT FROM NEW.target_generation
       OR entry_row.target_kid IS DISTINCT FROM NEW.target_kid
       OR entry_row.expected_active_generation IS DISTINCT FROM NEW.expected_active_generation
       OR entry_row.expected_active_kid IS DISTINCT FROM NEW.expected_active_kid
       OR pod_row.expected_outcome IS DISTINCT FROM NEW.outcome
       OR pod_row.exact_pod_endpoint IS NULL
       OR pod_row.exact_pod_endpoint IS DISTINCT FROM NEW.receiver_endpoint
       OR pod_row.canonical_service_uri IS DISTINCT FROM NEW.receiver_service_uri
       OR pod_row.expected_pod_leaf_spki_sha256 IS DISTINCT FROM NEW.receiver_peer_spki_sha256
       OR pod_row.pod_uid IS DISTINCT FROM NEW.observed_pod_uid
       OR pod_row.image IS DISTINCT FROM NEW.observed_image
       OR pod_row.verifier_config_sha256 IS DISTINCT FROM NEW.observed_verifier_config_sha256
       OR NEW.verified_kid IS DISTINCT FROM NEW.target_kid
       OR NEW.observed_at_epoch_seconds < entry_row.planned_issued_at_epoch_seconds
       OR NEW.observed_at_epoch_seconds >= entry_row.expires_at_epoch_seconds
       OR NEW.observed_at_epoch_seconds > floor(extract(epoch FROM CURRENT_TIMESTAMP))::BIGINT
       OR CURRENT_TIMESTAMP < to_timestamp(entry_row.planned_issued_at_epoch_seconds)
       OR CURRENT_TIMESTAMP >= to_timestamp(entry_row.expires_at_epoch_seconds) THEN
        RAISE EXCEPTION 'Readiness Pod receipt differs from exact current inventory, probe, or TLS identity'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_jwt_readiness_pod_receipt_insert_guard
    BEFORE INSERT OR UPDATE OR DELETE ON account_jwt_readiness_pod_receipts
    FOR EACH ROW EXECUTE FUNCTION account_jwt_readiness_pod_receipt_guard();

CREATE FUNCTION account_jwt_readiness_entry_v2_lifecycle_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    plan_row account_jwt_readiness_probe_plans%ROWTYPE;
    expected_pod_count integer;
    receipt_count integer;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO plan_row
          FROM account_jwt_readiness_probe_plans
         WHERE rotation_operation_id = NEW.rotation_operation_id
           AND plan_digest = NEW.plan_digest;
        IF NOT FOUND OR plan_row.plan_version <> 2
           OR NEW.state <> 'PLANNED'
           OR NEW.entry_version <> 1
           OR NEW.registry_version <> 1
           OR NEW.terminal_outcome IS NOT NULL
           OR NEW.compact_token_sha256 IS NOT NULL
           OR NEW.signing_attempted_at_epoch_seconds IS NOT NULL
           OR NEW.verification_receipt_sha256 IS NOT NULL
           OR NEW.target_generation IS DISTINCT FROM plan_row.target_generation
           OR NEW.target_kid IS DISTINCT FROM plan_row.target_kid
           OR NEW.expected_active_generation IS DISTINCT FROM plan_row.expected_active_generation
           OR NEW.expected_active_kid IS DISTINCT FROM plan_row.expected_active_kid
           OR NEW.planned_issued_at_epoch_seconds IS DISTINCT FROM plan_row.not_before_epoch_seconds
           OR NEW.expires_at_epoch_seconds IS DISTINCT FROM plan_row.expires_at_epoch_seconds THEN
            RAISE EXCEPTION 'Readiness V2 entry differs from its immutable plan'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.probe_kind = 'CANARY' THEN
            IF NEW.token_profile <> 'account-jwt-readiness-canary'
               OR NEW.audience <> 'firemud-account-jwt-readiness'
               OR NOT EXISTS (SELECT 1 FROM jsonb_array_elements(plan_row.applicability_matrix_json::jsonb -> 'validators') AS v(value)
                   WHERE v.value ->> 'validatorId' = NEW.validator_id) THEN
                RAISE EXCEPTION 'Readiness V2 canary entry is outside the closed validator set'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF NOT EXISTS (
            SELECT 1
              FROM jsonb_array_elements(plan_row.applicability_matrix_json::jsonb -> 'validators') AS v(value)
              CROSS JOIN LATERAL jsonb_array_elements(plan_row.applicability_matrix_json::jsonb -> 'productionProfiles') AS p(value)
             WHERE v.value ->> 'validatorId' = NEW.validator_id
               AND p.value ->> 'tokenProfile' = NEW.token_profile
               AND p.value ->> 'audience' = NEW.audience) THEN
            RAISE EXCEPTION 'Readiness V2 representative entry is outside its exact profile matrix'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Account JWT readiness evidence is retained' USING ERRCODE = '23514';
    END IF;
    IF OLD.entry_plan_version <> 2 OR NEW.entry_plan_version <> 2
       OR NEW.rotation_operation_id IS DISTINCT FROM OLD.rotation_operation_id
       OR NEW.plan_digest IS DISTINCT FROM OLD.plan_digest
       OR NEW.validator_id IS DISTINCT FROM OLD.validator_id
       OR NEW.token_profile IS DISTINCT FROM OLD.token_profile
       OR NEW.audience IS DISTINCT FROM OLD.audience
       OR NEW.probe_kind IS DISTINCT FROM OLD.probe_kind
       OR NEW.jti IS DISTINCT FROM OLD.jti
       OR NEW.target_generation IS DISTINCT FROM OLD.target_generation
       OR NEW.target_kid IS DISTINCT FROM OLD.target_kid
       OR NEW.expected_active_generation IS DISTINCT FROM OLD.expected_active_generation
       OR NEW.expected_active_kid IS DISTINCT FROM OLD.expected_active_kid
       OR NEW.registry_version IS DISTINCT FROM OLD.registry_version
       OR NEW.planned_issued_at_epoch_seconds IS DISTINCT FROM OLD.planned_issued_at_epoch_seconds
       OR NEW.expires_at_epoch_seconds IS DISTINCT FROM OLD.expires_at_epoch_seconds
       OR NEW.entry_version <> OLD.entry_version + 1 THEN
        RAISE EXCEPTION 'Readiness V2 entry identity/version is immutable except for one exact transition'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'PLANNED' AND NEW.state = 'PLANNED'
       AND OLD.compact_token_sha256 IS NULL
       AND NEW.compact_token_sha256 IS NOT NULL
       AND NEW.signing_attempted_at_epoch_seconds IS NOT NULL
       AND NEW.signing_attempted_at_epoch_seconds >= OLD.planned_issued_at_epoch_seconds
       AND NEW.signing_attempted_at_epoch_seconds < OLD.expires_at_epoch_seconds
       AND NEW.verification_receipt_sha256 IS NULL
       AND CURRENT_TIMESTAMP >= to_timestamp(OLD.planned_issued_at_epoch_seconds)
       AND CURRENT_TIMESTAMP < to_timestamp(OLD.expires_at_epoch_seconds)
       AND NEW.terminal_outcome IS NULL THEN
        RETURN NEW;
    END IF;
    IF OLD.state = 'PLANNED' AND NEW.state = 'ISSUED'
       AND OLD.compact_token_sha256 IS NOT NULL
       AND NEW.compact_token_sha256 = OLD.compact_token_sha256
       AND NEW.signing_attempted_at_epoch_seconds = OLD.signing_attempted_at_epoch_seconds
       AND NEW.verification_receipt_sha256 IS NULL
       AND CURRENT_TIMESTAMP >= to_timestamp(OLD.planned_issued_at_epoch_seconds)
       AND CURRENT_TIMESTAMP < to_timestamp(OLD.expires_at_epoch_seconds)
       AND NEW.terminal_outcome IS NULL THEN
        RETURN NEW;
    END IF;
    IF OLD.state = 'ISSUED' AND NEW.state = 'VERIFIED' THEN
        SELECT count(*) INTO expected_pod_count
          FROM account_jwt_readiness_validator_pods
         WHERE rotation_operation_id = NEW.rotation_operation_id
           AND validator_id = NEW.validator_id
           AND token_profile = NEW.token_profile
           AND audience = NEW.audience
           AND probe_kind = NEW.probe_kind;
        SELECT count(*) INTO receipt_count
          FROM account_jwt_readiness_pod_receipts
         WHERE rotation_operation_id = NEW.rotation_operation_id
           AND validator_id = NEW.validator_id
           AND token_profile = NEW.token_profile
           AND audience = NEW.audience
           AND probe_kind = NEW.probe_kind;
        IF expected_pod_count <= 0 OR expected_pod_count <> receipt_count
           OR NEW.verification_receipt_version IS DISTINCT FROM 2
           OR NEW.verification_source_entry_version IS DISTINCT FROM OLD.entry_version
           OR NEW.verification_receipt_sha256 IS NULL
           OR NEW.pod_receipt_count IS DISTINCT FROM expected_pod_count
           OR NEW.pod_receipt_closure_sha256 IS DISTINCT FROM NEW.verification_receipt_sha256
           OR NEW.entry_version IS DISTINCT FROM OLD.entry_version + 1
           OR NEW.target_kid IS DISTINCT FROM OLD.target_kid
           OR NEW.compact_token_sha256 IS DISTINCT FROM OLD.compact_token_sha256
           OR NEW.verified_at_epoch_seconds IS NULL
           OR NEW.verified_at_epoch_seconds < OLD.planned_issued_at_epoch_seconds
           OR NEW.verified_at_epoch_seconds >= OLD.expires_at_epoch_seconds
           OR to_timestamp(NEW.verified_at_epoch_seconds) > CURRENT_TIMESTAMP
           OR NEW.validator_instance_id IS NULL
           OR NEW.validator_binding_digest IS NULL
           OR NEW.validator_config_revision IS NULL
           OR NEW.validator_peer_uri IS NULL
           OR NEW.validator_peer_spki_sha256 IS NULL
           OR CURRENT_TIMESTAMP < to_timestamp(OLD.planned_issued_at_epoch_seconds)
           OR CURRENT_TIMESTAMP >= to_timestamp(OLD.expires_at_epoch_seconds)
           OR NEW.terminal_outcome IS NOT NULL THEN
            RAISE EXCEPTION 'Readiness V2 VERIFIED transition lacks complete exact per-Pod receipts'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.state IN ('PLANNED', 'ISSUED', 'VERIFIED')
       AND NEW.state = 'ABORTED'
       AND NEW.compact_token_sha256 IS NOT DISTINCT FROM OLD.compact_token_sha256
       AND NEW.signing_attempted_at_epoch_seconds IS NOT DISTINCT FROM OLD.signing_attempted_at_epoch_seconds
       AND NEW.verification_receipt_sha256 IS NOT DISTINCT FROM OLD.verification_receipt_sha256
       AND NEW.verification_receipt_version IS NOT DISTINCT FROM OLD.verification_receipt_version
       AND NEW.verification_source_entry_version IS NOT DISTINCT FROM OLD.verification_source_entry_version
       AND NEW.verified_at_epoch_seconds IS NOT DISTINCT FROM OLD.verified_at_epoch_seconds
       AND NEW.verified_kid IS NOT DISTINCT FROM OLD.verified_kid
       AND NEW.validator_instance_id IS NOT DISTINCT FROM OLD.validator_instance_id
       AND NEW.validator_binding_digest IS NOT DISTINCT FROM OLD.validator_binding_digest
       AND NEW.validator_config_revision IS NOT DISTINCT FROM OLD.validator_config_revision
       AND NEW.validator_peer_uri IS NOT DISTINCT FROM OLD.validator_peer_uri
       AND NEW.validator_peer_spki_sha256 IS NOT DISTINCT FROM OLD.validator_peer_spki_sha256
       AND NEW.pod_receipt_count IS NOT DISTINCT FROM OLD.pod_receipt_count
       AND NEW.pod_receipt_closure_sha256 IS NOT DISTINCT FROM OLD.pod_receipt_closure_sha256
       AND NEW.terminal_outcome = 'ABORTED' THEN
        RETURN NEW;
    END IF;
    IF OLD.state IN ('PLANNED', 'ISSUED', 'VERIFIED')
       AND NEW.state = 'EXPIRED'
       AND CURRENT_TIMESTAMP >= to_timestamp(OLD.expires_at_epoch_seconds)
       AND NEW.compact_token_sha256 IS NOT DISTINCT FROM OLD.compact_token_sha256
       AND NEW.signing_attempted_at_epoch_seconds IS NOT DISTINCT FROM OLD.signing_attempted_at_epoch_seconds
       AND NEW.verification_receipt_sha256 IS NOT DISTINCT FROM OLD.verification_receipt_sha256
       AND NEW.verification_receipt_version IS NOT DISTINCT FROM OLD.verification_receipt_version
       AND NEW.verification_source_entry_version IS NOT DISTINCT FROM OLD.verification_source_entry_version
       AND NEW.verified_at_epoch_seconds IS NOT DISTINCT FROM OLD.verified_at_epoch_seconds
       AND NEW.verified_kid IS NOT DISTINCT FROM OLD.verified_kid
       AND NEW.validator_instance_id IS NOT DISTINCT FROM OLD.validator_instance_id
       AND NEW.validator_binding_digest IS NOT DISTINCT FROM OLD.validator_binding_digest
       AND NEW.validator_config_revision IS NOT DISTINCT FROM OLD.validator_config_revision
       AND NEW.validator_peer_uri IS NOT DISTINCT FROM OLD.validator_peer_uri
       AND NEW.validator_peer_spki_sha256 IS NOT DISTINCT FROM OLD.validator_peer_spki_sha256
       AND NEW.pod_receipt_count IS NOT DISTINCT FROM OLD.pod_receipt_count
       AND NEW.pod_receipt_closure_sha256 IS NOT DISTINCT FROM OLD.pod_receipt_closure_sha256
       AND NEW.terminal_outcome = 'EXPIRED' THEN
        RETURN NEW;
    END IF;
    IF OLD.state IN ('ABORTED', 'EXPIRED') AND NEW.state = 'CLEANED'
       AND NEW.terminal_outcome = OLD.terminal_outcome
       AND NEW.compact_token_sha256 IS NOT DISTINCT FROM OLD.compact_token_sha256
       AND NEW.signing_attempted_at_epoch_seconds IS NOT DISTINCT FROM OLD.signing_attempted_at_epoch_seconds
       AND NEW.verification_receipt_sha256 IS NOT DISTINCT FROM OLD.verification_receipt_sha256
       AND NEW.verification_receipt_version IS NOT DISTINCT FROM OLD.verification_receipt_version
       AND NEW.verification_source_entry_version IS NOT DISTINCT FROM OLD.verification_source_entry_version
       AND NEW.verified_at_epoch_seconds IS NOT DISTINCT FROM OLD.verified_at_epoch_seconds
       AND NEW.verified_kid IS NOT DISTINCT FROM OLD.verified_kid
       AND NEW.validator_instance_id IS NOT DISTINCT FROM OLD.validator_instance_id
       AND NEW.validator_binding_digest IS NOT DISTINCT FROM OLD.validator_binding_digest
       AND NEW.validator_config_revision IS NOT DISTINCT FROM OLD.validator_config_revision
       AND NEW.validator_peer_uri IS NOT DISTINCT FROM OLD.validator_peer_uri
       AND NEW.validator_peer_spki_sha256 IS NOT DISTINCT FROM OLD.validator_peer_spki_sha256
       AND NEW.pod_receipt_count IS NOT DISTINCT FROM OLD.pod_receipt_count
       AND NEW.pod_receipt_closure_sha256 IS NOT DISTINCT FROM OLD.pod_receipt_closure_sha256 THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Account JWT readiness V2 lifecycle transition is unavailable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER account_jwt_readiness_entry_lifecycle_v2
    BEFORE INSERT OR UPDATE ON account_jwt_readiness_probe_entries
    FOR EACH ROW WHEN (NEW.entry_plan_version = 2)
    EXECUTE FUNCTION account_jwt_readiness_entry_v2_lifecycle_guard();

CREATE FUNCTION account_jwt_readiness_inventory_plan_closure_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    operation_id UUID;
    plan_row account_jwt_readiness_probe_plans%ROWTYPE;
    snapshot_json jsonb;
    expected_entry_count integer;
    actual_entry_count integer;
    expected_pod_count integer;
    actual_pod_count integer;
BEGIN
    operation_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.rotation_operation_id ELSE NEW.rotation_operation_id END;
    SELECT * INTO plan_row FROM account_jwt_readiness_probe_plans WHERE rotation_operation_id = operation_id;
    IF NOT FOUND OR plan_row.plan_version <> 2 THEN
        RETURN NULL;
    END IF;
    SELECT convert_from(canonical_snapshot, 'UTF8')::jsonb INTO snapshot_json
      FROM account_jwt_validator_inventory_snapshots
     WHERE snapshot_digest = plan_row.inventory_snapshot_digest;
    IF snapshot_json IS NULL THEN
        RAISE EXCEPTION 'Readiness V2 inventory snapshot is missing' USING ERRCODE = '23514';
    END IF;
    SELECT count(*) * 4 INTO expected_entry_count
      FROM jsonb_array_elements(snapshot_json -> 'validators');
    SELECT count(*) INTO actual_entry_count
      FROM account_jwt_readiness_probe_entries
     WHERE rotation_operation_id = operation_id AND plan_digest = plan_row.plan_digest;
    SELECT COALESCE(sum(jsonb_array_length(validator.value -> 'pods') * 4), 0)
      INTO expected_pod_count
      FROM jsonb_array_elements(snapshot_json -> 'validators') AS validator(value);
    SELECT count(*) INTO actual_pod_count
      FROM account_jwt_readiness_validator_pods
     WHERE rotation_operation_id = operation_id AND plan_digest = plan_row.plan_digest;
    IF actual_entry_count <> expected_entry_count
       OR actual_pod_count <> expected_pod_count
       OR actual_entry_count > 256 OR actual_pod_count > 256
       OR EXISTS (
            SELECT 1
              FROM jsonb_array_elements(snapshot_json -> 'validators') AS validator(value)
              CROSS JOIN LATERAL jsonb_array_elements(plan_row.applicability_matrix_json::jsonb -> 'productionProfiles') AS profile(value)
             WHERE NOT EXISTS (
                SELECT 1 FROM account_jwt_readiness_probe_entries entry
                 WHERE entry.rotation_operation_id = operation_id
                   AND entry.plan_digest = plan_row.plan_digest
                   AND entry.validator_id = validator.value ->> 'validatorId'
                   AND entry.token_profile = profile.value ->> 'tokenProfile'
                   AND entry.audience = profile.value ->> 'audience'
                   AND entry.probe_kind = 'REPRESENTATIVE'))
       OR EXISTS (
            SELECT 1
              FROM jsonb_array_elements(snapshot_json -> 'validators') AS validator(value)
             WHERE NOT EXISTS (
                SELECT 1 FROM account_jwt_readiness_probe_entries entry
                 WHERE entry.rotation_operation_id = operation_id
                   AND entry.plan_digest = plan_row.plan_digest
                   AND entry.validator_id = validator.value ->> 'validatorId'
                   AND entry.token_profile = 'account-jwt-readiness-canary'
                   AND entry.audience = 'firemud-account-jwt-readiness'
                   AND entry.probe_kind = 'CANARY'))
       OR EXISTS (
            SELECT 1
              FROM account_jwt_readiness_probe_entries entry
             WHERE entry.rotation_operation_id = operation_id
               AND entry.plan_digest = plan_row.plan_digest
               AND (SELECT count(*) FROM account_jwt_readiness_validator_pods target
                     WHERE target.rotation_operation_id = entry.rotation_operation_id
                       AND target.validator_id = entry.validator_id
                       AND target.token_profile = entry.token_profile
                       AND target.audience = entry.audience
                       AND target.probe_kind = entry.probe_kind)
                   <> (SELECT jsonb_array_length(validator.value -> 'pods')
                         FROM jsonb_array_elements(snapshot_json -> 'validators') AS validator(value)
                        WHERE validator.value ->> 'validatorId' = entry.validator_id)) THEN
        RAISE EXCEPTION 'Readiness V2 plan is missing a validator entry or exact per-Pod inventory row'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER account_jwt_readiness_plan_v2_closure
    AFTER INSERT ON account_jwt_readiness_probe_plans
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION account_jwt_readiness_inventory_plan_closure_guard();
CREATE CONSTRAINT TRIGGER account_jwt_readiness_entry_v2_closure
    AFTER INSERT ON account_jwt_readiness_probe_entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION account_jwt_readiness_inventory_plan_closure_guard();
CREATE CONSTRAINT TRIGGER account_jwt_readiness_pod_v2_closure
    AFTER INSERT ON account_jwt_readiness_validator_pods
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION account_jwt_readiness_inventory_plan_closure_guard();
-- [jooq ignore stop]
