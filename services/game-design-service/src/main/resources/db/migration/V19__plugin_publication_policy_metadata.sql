ALTER TABLE published_plugin_versions
    ADD COLUMN signer_key_id VARCHAR(128) NOT NULL DEFAULT 'unknown',
    ADD COLUMN signer_revoked BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN component_policy_decision VARCHAR(32) NOT NULL DEFAULT 'ALLOWED';
