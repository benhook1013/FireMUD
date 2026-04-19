CREATE TABLE publish_recorded_participant_digest (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    publish_type VARCHAR(32) NOT NULL,
    participant_key VARCHAR(64) NOT NULL,
    scope_value VARCHAR(128) NOT NULL,
    applied_commit_id VARCHAR(128) NOT NULL,
    content_digest VARCHAR(128) NOT NULL,
    digest_schema_version INT NOT NULL,
    recorded_from_publish_workflow_id VARCHAR(64) NOT NULL,
    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_verified_publish_workflow_id VARCHAR(64) NOT NULL,
    last_verified_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_recorded_participant_digest UNIQUE (
        tenant_id,
        publish_type,
        participant_key,
        applied_commit_id
    )
);

CREATE INDEX idx_recorded_participant_digest_tenant_publish
    ON publish_recorded_participant_digest(tenant_id, publish_type);
