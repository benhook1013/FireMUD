CREATE TABLE entity_digest_baseline_migration_audit (
    id BIGSERIAL PRIMARY KEY,
    operation_id VARCHAR(128) NOT NULL,
    recorded_participant_digest_id BIGINT NOT NULL,
    source_tenant_id VARCHAR(36) NOT NULL,
    source_publish_type VARCHAR(32) NOT NULL,
    source_participant_key VARCHAR(64) NOT NULL,
    source_base_version_id BIGINT,
    source_scope_value VARCHAR(128) NOT NULL,
    source_applied_commit_id VARCHAR(128) NOT NULL,
    source_content_digest VARCHAR(128) NOT NULL,
    source_digest_schema_version INT NOT NULL,
    source_recorded_from_publish_workflow_id VARCHAR(1024) NOT NULL,
    source_recorded_at TIMESTAMP NOT NULL,
    source_last_verified_publish_workflow_id VARCHAR(1024) NOT NULL,
    source_last_verified_at TIMESTAMP NOT NULL,
    observed_tenant_id VARCHAR(36) NOT NULL,
    observed_publish_type VARCHAR(32) NOT NULL,
    observed_participant_key VARCHAR(64) NOT NULL,
    observed_base_version_id BIGINT,
    observed_scope_value VARCHAR(128) NOT NULL,
    observed_applied_commit_id VARCHAR(128) NOT NULL,
    observed_content_digest VARCHAR(128) NOT NULL,
    observed_digest_schema_version INT NOT NULL,
    actor_identity VARCHAR(256) NOT NULL,
    workload_identity VARCHAR(256) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    committed_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_entity_digest_baseline_migration_operation UNIQUE (operation_id),
    CONSTRAINT uq_entity_digest_baseline_migration_baseline UNIQUE (recorded_participant_digest_id),
    CONSTRAINT fk_entity_digest_baseline_migration_baseline
        FOREIGN KEY (recorded_participant_digest_id)
        REFERENCES publish_recorded_participant_digest(id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_entity_digest_baseline_migration_source
        CHECK (
            source_publish_type = 'FULL_VERSION'
            AND source_participant_key = 'ENTITY_MANAGEMENT'
            AND source_base_version_id IS NULL
            AND source_digest_schema_version = 1
        ),
    CONSTRAINT chk_entity_digest_baseline_migration_observed
        CHECK (
            observed_publish_type = 'FULL_VERSION'
            AND observed_participant_key = 'ENTITY_MANAGEMENT'
            AND observed_base_version_id IS NULL
            AND observed_digest_schema_version = 2
            AND observed_tenant_id = source_tenant_id
            AND observed_scope_value = source_scope_value
        ),
    CONSTRAINT chk_entity_digest_baseline_migration_outcome
        CHECK (outcome = 'COMMITTED')
);
