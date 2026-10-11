ALTER TABLE published_release_bundle
    ALTER COLUMN generation_config_revision TYPE TEXT;

ALTER TABLE launch_descriptor
    ALTER COLUMN generation_config_revision TYPE TEXT;

ALTER TABLE version_asset_artifact
    ALTER COLUMN last_error_message TYPE TEXT;

ALTER TABLE publish_attempt
    ALTER COLUMN failure_message TYPE TEXT;
