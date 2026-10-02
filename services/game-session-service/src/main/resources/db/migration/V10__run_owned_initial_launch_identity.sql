ALTER TABLE game_instances
    ADD COLUMN run_owned_start_request_id character varying(128),
    ADD COLUMN run_owned_start_request_digest character varying(64),
    ADD COLUMN run_owned_start_published_release_bundle_ref character varying(256),
    ADD COLUMN run_owned_start_preparing_epoch bigint,
    ADD COLUMN run_owned_start_active_epoch bigint;

ALTER TABLE game_instances
    ADD CONSTRAINT game_instances_run_owned_start_identity_pair
        CHECK ((run_owned_start_request_id IS NULL) = (run_owned_start_request_digest IS NULL)),
    ADD CONSTRAINT game_instances_run_owned_start_digest_sha256
        CHECK (run_owned_start_request_digest IS NULL
            OR run_owned_start_request_digest ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT game_instances_run_owned_start_descriptor_binding
        CHECK ((run_owned_start_request_id IS NULL
                AND run_owned_start_published_release_bundle_ref IS NULL)
            OR (run_owned_start_request_id IS NOT NULL
                AND run_owned_start_published_release_bundle_ref IS NOT NULL
                AND game_template_id IS NOT NULL
                AND launch_descriptor_id IS NOT NULL
                AND version_id IS NOT NULL
                AND release_bundle_id IS NOT NULL
                AND version_state_epoch IS NOT NULL
                AND generation_config_revision IS NOT NULL)),
    ADD CONSTRAINT game_instances_run_owned_start_preparing_epoch_positive
        CHECK (run_owned_start_preparing_epoch IS NULL OR run_owned_start_preparing_epoch > 0),
    ADD CONSTRAINT game_instances_run_owned_start_active_epoch_exact
        CHECK (run_owned_start_active_epoch IS NULL
            OR (run_owned_start_preparing_epoch IS NOT NULL
                AND run_owned_start_active_epoch = run_owned_start_preparing_epoch + 1));

ALTER TABLE game_instances
    ADD CONSTRAINT game_instances_run_owned_start_request_unique
        UNIQUE (tenant_id, run_owned_start_request_id);
