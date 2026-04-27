CREATE TABLE plugin_version_status_events (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(80) NOT NULL UNIQUE,
    tenant_id VARCHAR(64) NOT NULL,
    plugin_id VARCHAR(128) NOT NULL,
    plugin_version_id VARCHAR(128) NOT NULL,
    previous_publication_state VARCHAR(64) NOT NULL,
    new_publication_state VARCHAR(64) NOT NULL,
    status_reason VARCHAR(256) NOT NULL,
    observed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_plugin_version_status_events_scope
    ON plugin_version_status_events(tenant_id, plugin_id, plugin_version_id, observed_at);
