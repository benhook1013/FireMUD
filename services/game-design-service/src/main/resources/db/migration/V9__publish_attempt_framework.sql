CREATE TABLE publish_attempt (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    publish_workflow_id VARCHAR(64) NOT NULL,
    publish_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version_id BIGINT,
    version_number INT NOT NULL,
    script_patch_version VARCHAR(100),
    failure_code VARCHAR(64),
    failure_message VARCHAR(512),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    CONSTRAINT uq_publish_attempt_workflow UNIQUE (publish_workflow_id)
);

CREATE INDEX idx_publish_attempt_tenant_id
    ON publish_attempt(tenant_id);

CREATE TABLE publish_attempt_participant_digest (
    id BIGSERIAL PRIMARY KEY,
    publish_attempt_id BIGINT NOT NULL REFERENCES publish_attempt(id) ON DELETE CASCADE,
    participant_key VARCHAR(64) NOT NULL,
    scope_value VARCHAR(128) NOT NULL,
    applied_commit_id VARCHAR(128),
    content_digest VARCHAR(128),
    digest_schema_version INT,
    error_code VARCHAR(64),
    error_message VARCHAR(512),
    observed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_publish_attempt_participant_attempt_id
    ON publish_attempt_participant_digest(publish_attempt_id);

ALTER TABLE published_release_bundle
    ADD COLUMN participant_digests_json TEXT NOT NULL DEFAULT '[]';
