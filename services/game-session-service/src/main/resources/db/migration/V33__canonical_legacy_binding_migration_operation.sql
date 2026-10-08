-- Durable, replayable owner-operation state for the legacy controller-index migration.
-- This migration deliberately leaves V31 REQUIRED; only the fenced Game Session owner operation
-- may publish VERIFIED after complete source capture, rebuild, and exact readback.
CREATE SEQUENCE game_session_canonical_legacy_migration_source_revision_seq START WITH 1;
CREATE SEQUENCE game_session_canonical_legacy_migration_readback_revision_seq START WITH 1;

CREATE TABLE game_session_canonical_legacy_migration_operation (
    operation_id uuid NOT NULL,
    cohort_id uuid NOT NULL,
    legacy_writer_fence uuid NOT NULL,
    state character varying(24) NOT NULL,
    kubernetes_cluster_uid text NOT NULL,
    kubernetes_namespace_uid text NOT NULL,
    producer_pod_uid text NOT NULL,
    producer_container_id text NOT NULL,
    producer_node_uid text NOT NULL,
    postgres_system_identifier numeric(20, 0) NOT NULL,
    postgres_database_oid bigint NOT NULL,
    postgres_storage_uid text NOT NULL,
    redis_run_id text NOT NULL,
    redis_storage_uid text NOT NULL,
    storage_identity_digest character varying(71) NOT NULL,
    source_snapshot_revision numeric,
    source_snapshot_digest character varying(71),
    source_snapshot_entry_count bigint,
    source_snapshot_families jsonb,
    canonical_snapshot_revision numeric,
    canonical_snapshot_digest character varying(71),
    canonical_snapshot jsonb,
    rebuild_digest character varying(71),
    namespace_index_readback_revision numeric,
    namespace_index_readback_digest character varying(71),
    publication_inventory_revision numeric,
    evidence_digest character varying(71),
    blocked_reason character varying(48),
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_canonical_legacy_migration_operation PRIMARY KEY (operation_id),
    CONSTRAINT uq_gs_canonical_legacy_migration_cohort UNIQUE (cohort_id),
    CONSTRAINT chk_gs_canonical_legacy_migration_identity CHECK (
        operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND cohort_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND legacy_writer_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND postgres_system_identifier > 0
        AND postgres_database_oid > 0
        AND postgres_database_oid <= 4294967295
        AND length(kubernetes_cluster_uid) > 0
        AND length(kubernetes_namespace_uid) > 0
        AND length(producer_pod_uid) > 0
        AND length(producer_container_id) > 0
        AND length(producer_node_uid) > 0
        AND length(postgres_storage_uid) > 0
        AND length(redis_run_id) > 0
        AND length(redis_storage_uid) > 0
        AND storage_identity_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_gs_canonical_legacy_migration_state CHECK (
        (state = 'FENCED'
            AND source_snapshot_revision IS NULL
            AND source_snapshot_digest IS NULL
            AND source_snapshot_entry_count IS NULL
            AND source_snapshot_families IS NULL
            AND canonical_snapshot_revision IS NULL
            AND canonical_snapshot_digest IS NULL
            AND canonical_snapshot IS NULL
            AND rebuild_digest IS NULL
            AND namespace_index_readback_revision IS NULL
            AND namespace_index_readback_digest IS NULL
            AND publication_inventory_revision IS NULL
            AND evidence_digest IS NULL
            AND blocked_reason IS NULL)
        OR (state = 'SNAPSHOTTED'
            AND source_snapshot_revision > 0
            AND source_snapshot_revision IS NOT NULL
            AND source_snapshot_digest ~ '^sha256:[0-9a-f]{64}$'
            AND source_snapshot_digest IS NOT NULL
            AND source_snapshot_entry_count >= 0
            AND source_snapshot_entry_count IS NOT NULL
            AND source_snapshot_families IS NOT NULL
            AND jsonb_typeof(source_snapshot_families) = 'array'
            AND canonical_snapshot_revision >= 0
            AND canonical_snapshot_revision IS NOT NULL
            AND canonical_snapshot_digest ~ '^sha256:[0-9a-f]{64}$'
            AND canonical_snapshot_digest IS NOT NULL
            AND canonical_snapshot IS NOT NULL
            AND rebuild_digest IS NULL
            AND namespace_index_readback_revision IS NULL
            AND namespace_index_readback_digest IS NULL
            AND publication_inventory_revision IS NULL
            AND evidence_digest IS NULL
            AND blocked_reason IS NULL)
        OR (state = 'REBUILT'
            AND source_snapshot_revision > 0
            AND source_snapshot_revision IS NOT NULL
            AND source_snapshot_digest ~ '^sha256:[0-9a-f]{64}$'
            AND source_snapshot_digest IS NOT NULL
            AND source_snapshot_entry_count >= 0
            AND source_snapshot_entry_count IS NOT NULL
            AND source_snapshot_families IS NOT NULL
            AND jsonb_typeof(source_snapshot_families) = 'array'
            AND canonical_snapshot_revision >= 0
            AND canonical_snapshot_revision IS NOT NULL
            AND canonical_snapshot_digest ~ '^sha256:[0-9a-f]{64}$'
            AND canonical_snapshot_digest IS NOT NULL
            AND canonical_snapshot IS NOT NULL
            AND rebuild_digest ~ '^sha256:[0-9a-f]{64}$'
            AND rebuild_digest IS NOT NULL
            AND namespace_index_readback_revision IS NULL
            AND namespace_index_readback_digest IS NULL
            AND publication_inventory_revision IS NULL
            AND evidence_digest IS NULL
            AND blocked_reason IS NULL)
        OR (state = 'READBACK_VERIFIED'
            AND source_snapshot_revision > 0
            AND source_snapshot_revision IS NOT NULL
            AND source_snapshot_digest ~ '^sha256:[0-9a-f]{64}$'
            AND source_snapshot_digest IS NOT NULL
            AND source_snapshot_entry_count >= 0
            AND source_snapshot_entry_count IS NOT NULL
            AND source_snapshot_families IS NOT NULL
            AND jsonb_typeof(source_snapshot_families) = 'array'
            AND canonical_snapshot_revision >= 0
            AND canonical_snapshot_revision IS NOT NULL
            AND canonical_snapshot_digest ~ '^sha256:[0-9a-f]{64}$'
            AND canonical_snapshot_digest IS NOT NULL
            AND canonical_snapshot IS NOT NULL
            AND rebuild_digest ~ '^sha256:[0-9a-f]{64}$'
            AND rebuild_digest IS NOT NULL
            AND namespace_index_readback_revision > 0
            AND namespace_index_readback_revision IS NOT NULL
            AND namespace_index_readback_digest ~ '^sha256:[0-9a-f]{64}$'
            AND namespace_index_readback_digest IS NOT NULL
            AND publication_inventory_revision IS NULL
            AND evidence_digest IS NULL
            AND blocked_reason IS NULL)
        OR (state = 'VERIFIED'
            AND source_snapshot_revision > 0
            AND source_snapshot_revision IS NOT NULL
            AND source_snapshot_digest ~ '^sha256:[0-9a-f]{64}$'
            AND source_snapshot_digest IS NOT NULL
            AND source_snapshot_entry_count >= 0
            AND source_snapshot_entry_count IS NOT NULL
            AND source_snapshot_families IS NOT NULL
            AND jsonb_typeof(source_snapshot_families) = 'array'
            AND canonical_snapshot_revision >= 0
            AND canonical_snapshot_revision IS NOT NULL
            AND canonical_snapshot_digest ~ '^sha256:[0-9a-f]{64}$'
            AND canonical_snapshot_digest IS NOT NULL
            AND canonical_snapshot IS NOT NULL
            AND rebuild_digest ~ '^sha256:[0-9a-f]{64}$'
            AND rebuild_digest IS NOT NULL
            AND namespace_index_readback_revision > 0
            AND namespace_index_readback_revision IS NOT NULL
            AND namespace_index_readback_digest ~ '^sha256:[0-9a-f]{64}$'
            AND namespace_index_readback_digest IS NOT NULL
            AND publication_inventory_revision > 0
            AND publication_inventory_revision IS NOT NULL
            AND evidence_digest ~ '^sha256:[0-9a-f]{64}$'
            AND evidence_digest IS NOT NULL
            AND blocked_reason IS NULL)
        OR (state = 'BLOCKED'
            AND blocked_reason IS NOT NULL
            AND length(blocked_reason) > 0))
);

CREATE TABLE game_session_canonical_legacy_migration_source_snapshot_entry (
    operation_id uuid NOT NULL,
    row_ordinal bigint NOT NULL,
    source_family character varying(40) NOT NULL,
    source_key_digest character varying(71) NOT NULL,
    disposition character varying(32) NOT NULL,
    CONSTRAINT pk_gs_canonical_legacy_migration_source_entry
        PRIMARY KEY (operation_id, row_ordinal),
    CONSTRAINT uq_gs_canonical_legacy_migration_source_key
        UNIQUE (operation_id, source_key_digest),
    CONSTRAINT fk_gs_canonical_legacy_migration_source_operation
        FOREIGN KEY (operation_id)
        REFERENCES game_session_canonical_legacy_migration_operation (operation_id),
    CONSTRAINT chk_gs_canonical_legacy_migration_source_entry CHECK (
        row_ordinal >= 0
        AND source_family IN (
            'TENANT_SESSION_CONTEXT', 'SESSION_ALIAS_CONTEXT',
            'GAMEPLAY_IDENTITY_CONTEXT', 'GAMEPLAY_NAME_CONTEXT',
            'MOVEMENT_EFFECT', 'DURABLE_EFFECT', 'UNCLASSIFIED')
        AND source_key_digest ~ '^sha256:[0-9a-f]{64}$'
        AND disposition IN ('UNMAPPABLE', 'CREDENTIAL_BEARING', 'UNKNOWN'))
);

CREATE INDEX ix_gs_canonical_legacy_migration_state
    ON game_session_canonical_legacy_migration_operation (state, cohort_id);

-- The V31 singleton remains REQUIRED. No migration, fixture, or empty query seeds VERIFIED.
