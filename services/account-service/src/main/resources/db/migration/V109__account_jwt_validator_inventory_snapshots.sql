-- Durable immutable owner observations are bound to a readiness plan, but remain partial
-- non-authorizing evidence. A stored Deployment/ReplicaSet/Pod inventory is not probe acceptance.
CREATE TABLE account_jwt_validator_inventory_snapshots (
    snapshot_digest VARCHAR(64) PRIMARY KEY,
    environment_id VARCHAR(63) NOT NULL,
    cluster_id VARCHAR(128) NOT NULL,
    cluster_incarnation_uid UUID NOT NULL,
    kubernetes_namespace VARCHAR(63) NOT NULL,
    namespace_uid UUID NOT NULL,
    api_binding_revision VARCHAR(128) NOT NULL,
    api_binding_digest VARCHAR(64) NOT NULL,
    inventory_binding_revision VARCHAR(128) NOT NULL,
    inventory_binding_digest VARCHAR(64) NOT NULL,
    observed_at VARCHAR(40) NOT NULL,
    canonical_snapshot BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_jwt_validator_inventory_snapshot_identity_check
        CHECK (snapshot_digest ~ '^[0-9a-f]{64}$'
            AND environment_id ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
            AND cluster_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            AND kubernetes_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
            AND cluster_incarnation_uid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND namespace_uid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND api_binding_revision ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            AND api_binding_digest ~ '^[0-9a-f]{64}$'
            AND inventory_binding_revision ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            AND inventory_binding_digest ~ '^[0-9a-f]{64}$'
            AND length(observed_at) BETWEEN 20 AND 40
            AND observed_at ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T.*Z$'
            AND octet_length(canonical_snapshot) BETWEEN 2 AND 4194304),
    CONSTRAINT account_jwt_validator_inventory_snapshot_plan_identity_unique
        UNIQUE (snapshot_digest, environment_id, cluster_id, kubernetes_namespace,
            cluster_incarnation_uid, namespace_uid)
);

ALTER TABLE account_jwt_readiness_probe_plans
    ADD COLUMN inventory_snapshot_digest VARCHAR(64),
    ADD CONSTRAINT account_jwt_readiness_plan_inventory_snapshot_digest_check
        CHECK (inventory_snapshot_digest IS NULL
            OR inventory_snapshot_digest ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT account_jwt_readiness_plan_inventory_snapshot_fk
        FOREIGN KEY (inventory_snapshot_digest, environment_id, cluster_id,
            kubernetes_namespace, expected_cluster_incarnation_uid, expected_namespace_uid)
        REFERENCES account_jwt_validator_inventory_snapshots(
            snapshot_digest, environment_id, cluster_id, kubernetes_namespace,
            cluster_incarnation_uid, namespace_uid)
        ON DELETE RESTRICT;

-- [jooq ignore start]
CREATE FUNCTION account_jwt_validator_inventory_snapshot_immutable_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account JWT validator inventory snapshots are immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER account_jwt_validator_inventory_snapshot_immutable
    BEFORE UPDATE OR DELETE ON account_jwt_validator_inventory_snapshots
    FOR EACH ROW EXECUTE FUNCTION account_jwt_validator_inventory_snapshot_immutable_guard();

CREATE TRIGGER account_jwt_validator_inventory_snapshot_no_truncate
    BEFORE TRUNCATE ON account_jwt_validator_inventory_snapshots
    FOR EACH STATEMENT EXECUTE FUNCTION account_jwt_validator_inventory_snapshot_immutable_guard();
-- [jooq ignore stop]
