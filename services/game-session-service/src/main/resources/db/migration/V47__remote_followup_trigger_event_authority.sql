ALTER TABLE remote_followup
    ADD COLUMN event_type VARCHAR(128),
    ADD COLUMN event_schema_version VARCHAR(32),
    ADD COLUMN script_event_id VARCHAR(128),
    ADD COLUMN trigger_mode VARCHAR(40),
    ADD COLUMN read_snapshot_token VARCHAR(255),
    ADD COLUMN event_payload_json TEXT;

UPDATE remote_followup
SET event_type = payload_json::jsonb ->> 'eventType',
    event_schema_version = COALESCE(payload_json::jsonb ->> 'eventSchemaVersion', 'v1'),
    script_event_id = payload_json::jsonb ->> 'scriptEventId',
    trigger_mode = payload_json::jsonb ->> 'triggerMode',
    read_snapshot_token = payload_json::jsonb ->> 'readSnapshotToken',
    event_payload_json = CASE
        WHEN payload_json::jsonb ? 'eventPayload' THEN (payload_json::jsonb -> 'eventPayload')::text
        ELSE NULL
        END
WHERE payload_kind = 'trigger_script_event'
  AND payload_json IS NOT NULL
  AND payload_json <> '';

CREATE INDEX IF NOT EXISTS idx_remote_followup_event_due
    ON remote_followup (
        tenant_id,
        event_type,
        script_event_id,
        due_tick_id
    );
