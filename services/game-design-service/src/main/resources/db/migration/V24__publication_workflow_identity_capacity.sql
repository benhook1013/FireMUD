ALTER TABLE publish_attempt
    ALTER COLUMN publish_workflow_id TYPE VARCHAR(1024);

ALTER TABLE published_release_bundle
    ALTER COLUMN publish_workflow_id TYPE VARCHAR(1024);

ALTER TABLE version_asset_artifact
    ALTER COLUMN last_workflow_id TYPE VARCHAR(1024);

ALTER TABLE publish_recorded_participant_digest
    ALTER COLUMN recorded_from_publish_workflow_id TYPE VARCHAR(1024);

ALTER TABLE publish_recorded_participant_digest
    ALTER COLUMN last_verified_publish_workflow_id TYPE VARCHAR(1024);
