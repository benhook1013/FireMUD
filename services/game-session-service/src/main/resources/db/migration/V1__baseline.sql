CREATE TABLE feature_flag (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    name character varying(100) NOT NULL,
    enabled boolean NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);

CREATE SEQUENCE feature_flag_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE feature_flag_id_seq OWNED BY feature_flag.id;

CREATE TABLE game_instances (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    runtime_version character varying(100) NOT NULL,
    script_patch_version character varying(100),
    owner_account_id bigint NOT NULL,
    status character varying(20) NOT NULL,
    row_version bigint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    script_patch_pinned_at timestamp without time zone,
    script_patch_pinned_by character varying(200),
    script_patch_pinned_reason character varying(500),
    game_template_id bigint,
    launch_descriptor_id character varying(64),
    version_id bigint,
    release_bundle_id bigint,
    version_state_epoch bigint,
    generation_config_revision character varying(128),
    remap_set_id character varying(64),
    script_patch_pinned_control_plane_request_id character varying(128),
    script_pin_epoch bigint
);

CREATE SEQUENCE game_instances_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE game_instances_id_seq OWNED BY game_instances.id;

CREATE TABLE game_manifest (
    id bigint NOT NULL,
    version_id character varying(100) NOT NULL,
    description text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);

CREATE SEQUENCE game_manifest_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE game_manifest_id_seq OWNED BY game_manifest.id;

CREATE TABLE gameplay_admission_pointer (
    id bigint NOT NULL,
    world_slug character varying(120) NOT NULL,
    world_display_name character varying(200) NOT NULL,
    realm_slug character varying(120) NOT NULL,
    realm_display_name character varying(200) NOT NULL,
    tenant_id bigint NOT NULL,
    game_instance_id bigint NOT NULL,
    pointer_version bigint NOT NULL,
    visible boolean NOT NULL,
    requires_character_selection boolean NOT NULL,
    state_scope character varying(32) NOT NULL,
    character_creation_policy character varying(32) NOT NULL,
    last_updated_by character varying(200) NOT NULL,
    last_update_reason character varying(500) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    public_production_realm boolean DEFAULT false NOT NULL
);

CREATE TABLE gameplay_admission_pointer_event (
    id bigint NOT NULL,
    world_slug character varying(120) NOT NULL,
    realm_slug character varying(120) NOT NULL,
    world_display_name character varying(200) NOT NULL,
    realm_display_name character varying(200) NOT NULL,
    tenant_id bigint NOT NULL,
    game_instance_id bigint NOT NULL,
    pointer_version bigint NOT NULL,
    visible boolean NOT NULL,
    requires_character_selection boolean NOT NULL,
    state_scope character varying(32) NOT NULL,
    character_creation_policy character varying(32) NOT NULL,
    actor_principal character varying(200) NOT NULL,
    reason character varying(500) NOT NULL,
    control_plane_request_id character varying(120) NOT NULL,
    occurred_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    prepared_version_upgrade_id character varying(64),
    public_production_realm boolean DEFAULT false NOT NULL
);

CREATE SEQUENCE gameplay_admission_pointer_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE gameplay_admission_pointer_event_id_seq OWNED BY gameplay_admission_pointer_event.id;

CREATE SEQUENCE gameplay_admission_pointer_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE gameplay_admission_pointer_id_seq OWNED BY gameplay_admission_pointer.id;

CREATE TABLE gameplay_command (
    id bigint NOT NULL,
    command_id character varying(64) NOT NULL,
    tenant_id bigint NOT NULL,
    game_instance_id bigint NOT NULL,
    session_id bigint NOT NULL,
    account_id bigint,
    character_id bigint,
    command_name character varying(80) NOT NULL,
    sanitized_command_text character varying(1000) NOT NULL,
    requires_solo_tick boolean NOT NULL,
    execution_outcome character varying(40) NOT NULL,
    gameplay_result character varying(40) NOT NULL,
    accepted_at timestamp without time zone NOT NULL,
    staged_at timestamp without time zone,
    completed_at timestamp without time zone,
    last_attempt_at timestamp without time zone,
    attempt_count integer DEFAULT 0 NOT NULL,
    failure_code character varying(80),
    failure_message character varying(500),
    source_type character varying(40) DEFAULT 'PLAYER'::character varying NOT NULL,
    automation_dispatch_id character varying(128),
    automation_work_item_id character varying(128),
    script_id character varying(128),
    script_patch_version character varying(128),
    target_entity_id character varying(64),
    region_id character varying(64),
    region_epoch bigint,
    due_tick_id bigint,
    command_text character varying(1000) DEFAULT ''::character varying NOT NULL,
    plugin_id character varying(128),
    plugin_version_id character varying(128),
    enqueue_seq bigint NOT NULL,
    playable_state_scope character varying(32) DEFAULT ''::character varying NOT NULL,
    world_slug character varying(64) DEFAULT ''::character varying NOT NULL,
    realm_slug character varying(64) DEFAULT ''::character varying NOT NULL,
    pointer_version bigint,
    origin_source_kind character varying(64),
    origin_source_state character varying(64),
    origin_source_ordinal bigint,
    origin_source_due_tick_id bigint,
    origin_source_due_at_ms bigint,
    queue_source_kind character varying(64),
    queue_source_state character varying(64),
    queue_source_ordinal bigint,
    queue_source_due_tick_id bigint,
    queue_source_due_at_ms bigint,
    remote_coordinator_id character varying(128),
    remote_followup_id character varying(128),
    execution_hook character varying(128),
    admitted_release_bundle_id bigint,
    admitted_version_id bigint,
    declared_effects_json text,
    script_pin_epoch bigint,
    script_pin_control_plane_request_id character varying(128)
);

CREATE SEQUENCE gameplay_command_enqueue_seq_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE gameplay_command_enqueue_seq_seq OWNED BY gameplay_command.enqueue_seq;

CREATE SEQUENCE gameplay_command_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE gameplay_command_id_seq OWNED BY gameplay_command.id;

CREATE SEQUENCE resume_transcript_entry_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE resume_transcript_entry (
    id bigint NOT NULL DEFAULT nextval('resume_transcript_entry_id_seq'),
    tenant_id bigint NOT NULL,
    game_instance_id bigint NOT NULL,
    character_id bigint NOT NULL,
    protocol_text text NOT NULL,
    line_count integer NOT NULL,
    byte_size integer NOT NULL,
    appended_at timestamp with time zone NOT NULL,
    output_kind character varying(64),
    replay_policy character varying(64),
    brief_render_policy character varying(64),
    payload_type character varying(128),
    payload_json text,
    expires_at timestamp with time zone,
    CONSTRAINT resume_transcript_entry_pkey PRIMARY KEY (id)
);

ALTER SEQUENCE resume_transcript_entry_id_seq OWNED BY resume_transcript_entry.id;

CREATE SEQUENCE player_command_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE player_command_history (
    id bigint NOT NULL DEFAULT nextval('player_command_history_id_seq'),
    tenant_id bigint NOT NULL,
    game_instance_id bigint NOT NULL,
    character_id bigint NOT NULL,
    command_text text NOT NULL,
    accepted_at timestamp with time zone NOT NULL,
    CONSTRAINT player_command_history_pkey PRIMARY KEY (id)
);

ALTER SEQUENCE player_command_history_id_seq OWNED BY player_command_history.id;

CREATE TABLE player_command_history_retention_sweep_state (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE,
    cursor_tenant_id BIGINT,
    cursor_game_instance_id BIGINT,
    cursor_character_id BIGINT,
    batches_since_wrap INTEGER NOT NULL DEFAULT 0
);

INSERT INTO player_command_history_retention_sweep_state (singleton) VALUES (TRUE);

CREATE TABLE script_pin_operation (
    tenant_id bigint NOT NULL,
    game_instance_id bigint NOT NULL,
    control_plane_request_id character varying(128) NOT NULL,
    operation_kind character varying(32) NOT NULL,
    target_script_patch_version character varying(100) NOT NULL,
    expected_pin_kind character varying(32) NOT NULL,
    expected_script_pin_epoch bigint,
    actor_principal character varying(200) NOT NULL,
    reason character varying(500) NOT NULL,
    mutation_digest character varying(64) NOT NULL,
    outcome character varying(32) NOT NULL,
    error_code character varying(128),
    previous_script_patch_version character varying(100),
    previous_script_pin_epoch bigint,
    resulting_script_patch_version character varying(100),
    resulting_script_pin_epoch bigint,
    committed_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, game_instance_id, control_plane_request_id),
    CHECK (
        (previous_script_patch_version IS NULL AND previous_script_pin_epoch IS NULL)
        OR
        (previous_script_patch_version IS NOT NULL AND previous_script_pin_epoch IS NOT NULL AND previous_script_pin_epoch > 0)
    ),
    CHECK (
        (resulting_script_patch_version IS NULL AND resulting_script_pin_epoch IS NULL)
        OR
        (resulting_script_patch_version IS NOT NULL AND resulting_script_pin_epoch IS NOT NULL AND resulting_script_pin_epoch > 0)
    ),
    CHECK (
        (expected_pin_kind = 'EXPECT_UNPINNED' AND expected_script_pin_epoch IS NULL)
        OR
        (expected_pin_kind = 'EXPECT_EPOCH' AND expected_script_pin_epoch IS NOT NULL AND expected_script_pin_epoch > 0)
        OR
        (expected_pin_kind = 'UNCONDITIONAL' AND expected_script_pin_epoch IS NULL)
    )
);

CREATE INDEX idx_script_pin_operation_instance
    ON script_pin_operation (tenant_id, game_instance_id, committed_at, control_plane_request_id);

CREATE TABLE prepared_version_upgrade (
    id bigint NOT NULL,
    preparation_id character varying(64) NOT NULL,
    control_plane_request_id character varying(128) NOT NULL,
    tenant_id bigint NOT NULL,
    source_game_instance_id bigint NOT NULL,
    source_version_id bigint NOT NULL,
    target_version_id bigint NOT NULL,
    target_launch_descriptor_id character varying(64) NOT NULL,
    remap_set_id character varying(64),
    result character varying(32) NOT NULL,
    reasons_json text NOT NULL,
    checked_participants_json text NOT NULL,
    participant_results_json text NOT NULL,
    checked_at timestamp without time zone NOT NULL,
    executed_target_game_instance_id bigint,
    executed_pointer_version bigint,
    executed_at timestamp without time zone,
    execution_control_plane_request_id character varying(128)
);

CREATE SEQUENCE prepared_version_upgrade_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE prepared_version_upgrade_id_seq OWNED BY prepared_version_upgrade.id;

CREATE TABLE remote_command_coordinator (
    id bigint NOT NULL,
    coordinator_id character varying(64) NOT NULL,
    tenant_id bigint NOT NULL,
    command_id character varying(64) NOT NULL,
    origin_game_instance_id bigint NOT NULL,
    origin_region_id character varying(64) NOT NULL,
    origin_region_epoch bigint NOT NULL,
    target_game_instance_id bigint NOT NULL,
    target_region_id character varying(64) NOT NULL,
    target_region_epoch bigint NOT NULL,
    target_due_tick_id bigint NOT NULL,
    origin_deadline_region_epoch bigint NOT NULL,
    origin_deadline_tick_id bigint NOT NULL,
    state character varying(40) NOT NULL,
    late_result_policy character varying(64) NOT NULL,
    execution_outcome character varying(40),
    gameplay_result character varying(40),
    updated_at timestamp with time zone NOT NULL,
    followup_id character varying(64) NOT NULL,
    playable_state_scope character varying(32),
    world_slug character varying(64),
    realm_slug character varying(64),
    pointer_version bigint,
    script_patch_version character varying(128),
    plugin_id character varying(128),
    plugin_version_id character varying(128),
    automation_dispatch_id character varying(128),
    automation_work_item_id character varying(128),
    script_id character varying(128)
);

CREATE SEQUENCE remote_command_coordinator_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE remote_command_coordinator_id_seq OWNED BY remote_command_coordinator.id;

CREATE TABLE remote_followup (
    id bigint NOT NULL,
    followup_id character varying(64) NOT NULL,
    tenant_id bigint NOT NULL,
    origin_game_instance_id bigint NOT NULL,
    origin_region_id character varying(64) NOT NULL,
    origin_region_epoch bigint NOT NULL,
    target_game_instance_id bigint NOT NULL,
    target_region_id character varying(64) NOT NULL,
    target_region_epoch bigint NOT NULL,
    due_tick_id bigint NOT NULL,
    effect_key character varying(128) NOT NULL,
    target_entity_id character varying(64),
    status character varying(40) NOT NULL,
    claimed_tick_batch_id character varying(64),
    payload_json text,
    failure_code character varying(80),
    failure_message character varying(500),
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    claim_ordinal bigint,
    playable_state_scope character varying(32),
    world_slug character varying(64),
    realm_slug character varying(64),
    pointer_version bigint,
    script_patch_version character varying(128),
    plugin_id character varying(128),
    plugin_version_id character varying(128),
    command_id character varying(128),
    automation_dispatch_id character varying(128),
    automation_work_item_id character varying(128),
    script_id character varying(128),
    payload_kind character varying(80),
    requested_command character varying(500),
    requires_solo_tick boolean DEFAULT false NOT NULL,
    origin_source_kind character varying(64),
    origin_source_state character varying(64),
    origin_source_ordinal bigint,
    origin_source_due_tick_id bigint,
    origin_source_due_at_ms bigint,
    event_type character varying(128),
    event_schema_version character varying(32),
    script_event_id character varying(128),
    trigger_mode character varying(40),
    read_snapshot_token character varying(255),
    event_payload_json text,
    claim_target_aggregate character varying(128) NOT NULL,
    queue_source_kind character varying(64),
    queue_source_state character varying(64),
    queue_source_ordinal bigint,
    queue_source_due_tick_id bigint,
    queue_source_due_at_ms bigint
);

CREATE SEQUENCE remote_followup_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE remote_followup_id_seq OWNED BY remote_followup.id;

CREATE TABLE remote_followup_result (
    id bigint NOT NULL,
    result_id character varying(64) NOT NULL,
    tenant_id bigint NOT NULL,
    coordinator_id character varying(64) NOT NULL,
    followup_id character varying(64) NOT NULL,
    origin_region_id character varying(64) NOT NULL,
    origin_region_epoch bigint NOT NULL,
    target_region_id character varying(64) NOT NULL,
    target_region_epoch bigint NOT NULL,
    outcome character varying(40) NOT NULL,
    result_payload_json text,
    observed_at timestamp with time zone NOT NULL,
    playable_state_scope character varying(32),
    world_slug character varying(64),
    realm_slug character varying(64),
    pointer_version bigint,
    script_patch_version character varying(128),
    plugin_id character varying(128),
    plugin_version_id character varying(128),
    command_id character varying(128),
    automation_dispatch_id character varying(128),
    automation_work_item_id character varying(128),
    script_id character varying(128),
    result_error_code character varying(80),
    result_command_id character varying(128),
    result_message character varying(500),
    origin_game_instance_id bigint NOT NULL,
    target_game_instance_id bigint NOT NULL
);

CREATE SEQUENCE remote_followup_result_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE remote_followup_result_id_seq OWNED BY remote_followup_result.id;

CREATE TABLE runtime_region_status (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    game_instance_id bigint NOT NULL,
    region_epoch bigint NOT NULL,
    executor_fence character varying(64) NOT NULL,
    owner_service character varying(80) NOT NULL,
    owner_instance_id character varying(120) NOT NULL,
    paused boolean NOT NULL,
    last_committed_tick_batch_id character varying(64),
    updated_at timestamp without time zone NOT NULL,
    last_committed_tick_id bigint DEFAULT 0 NOT NULL,
    region_id character varying(64) DEFAULT ''::character varying NOT NULL
);

CREATE SEQUENCE runtime_region_status_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE runtime_region_status_id_seq OWNED BY runtime_region_status.id;

CREATE TABLE tick_batch (
    id bigint NOT NULL,
    tick_batch_id character varying(64) NOT NULL,
    tenant_id bigint NOT NULL,
    game_instance_id bigint NOT NULL,
    region_epoch bigint NOT NULL,
    executor_fence character varying(64) NOT NULL,
    batch_source character varying(40) NOT NULL,
    status character varying(40) NOT NULL,
    requires_solo_tick boolean NOT NULL,
    command_count integer NOT NULL,
    staged_at timestamp without time zone NOT NULL,
    completed_at timestamp without time zone,
    failure_code character varying(80),
    failure_message character varying(500),
    expected_effect_count integer DEFAULT 0 NOT NULL,
    selected_work_manifest_digest character varying(64),
    selected_work_manifest_json text,
    region_id character varying(64) DEFAULT ''::character varying NOT NULL
);

CREATE SEQUENCE tick_batch_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE tick_batch_id_seq OWNED BY tick_batch.id;

CREATE TABLE tick_effect (
    id bigint NOT NULL,
    effect_id character varying(64) NOT NULL,
    tick_batch_id character varying(64) NOT NULL,
    command_id character varying(64),
    effect_type character varying(80) NOT NULL,
    target_aggregate character varying(120) NOT NULL,
    status character varying(40) NOT NULL,
    staged_at timestamp without time zone NOT NULL,
    completed_at timestamp without time zone,
    failure_code character varying(80),
    failure_message character varying(500),
    effect_key character varying(160) NOT NULL
);

CREATE SEQUENCE tick_effect_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE tick_effect_id_seq OWNED BY tick_effect.id;

ALTER TABLE feature_flag ALTER COLUMN id SET DEFAULT nextval('feature_flag_id_seq');

ALTER TABLE game_instances ALTER COLUMN id SET DEFAULT nextval('game_instances_id_seq');

ALTER TABLE game_manifest ALTER COLUMN id SET DEFAULT nextval('game_manifest_id_seq');

ALTER TABLE gameplay_admission_pointer ALTER COLUMN id SET DEFAULT nextval('gameplay_admission_pointer_id_seq');

ALTER TABLE gameplay_admission_pointer_event ALTER COLUMN id SET DEFAULT nextval('gameplay_admission_pointer_event_id_seq');

ALTER TABLE gameplay_command ALTER COLUMN id SET DEFAULT nextval('gameplay_command_id_seq');

ALTER TABLE gameplay_command ALTER COLUMN enqueue_seq SET DEFAULT nextval('gameplay_command_enqueue_seq_seq');

ALTER TABLE prepared_version_upgrade ALTER COLUMN id SET DEFAULT nextval('prepared_version_upgrade_id_seq');

ALTER TABLE remote_command_coordinator ALTER COLUMN id SET DEFAULT nextval('remote_command_coordinator_id_seq');

ALTER TABLE remote_followup ALTER COLUMN id SET DEFAULT nextval('remote_followup_id_seq');

ALTER TABLE remote_followup_result ALTER COLUMN id SET DEFAULT nextval('remote_followup_result_id_seq');

ALTER TABLE runtime_region_status ALTER COLUMN id SET DEFAULT nextval('runtime_region_status_id_seq');

ALTER TABLE tick_batch ALTER COLUMN id SET DEFAULT nextval('tick_batch_id_seq');

ALTER TABLE tick_effect ALTER COLUMN id SET DEFAULT nextval('tick_effect_id_seq');

ALTER TABLE feature_flag
    ADD CONSTRAINT feature_flag_pkey PRIMARY KEY (id);

ALTER TABLE game_instances
    ADD CONSTRAINT game_instances_pkey PRIMARY KEY (id);

ALTER TABLE game_instances
    ADD CONSTRAINT game_instances_script_pin_tuple_coherent
    CHECK (
        (
            NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NULL
            AND script_pin_epoch IS NULL
            AND NULLIF(regexp_replace(script_patch_pinned_control_plane_request_id, '[[:space:]]', '', 'g'), '') IS NULL
        )
        OR
        (
            NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NOT NULL
            AND script_pin_epoch IS NOT NULL
            AND script_pin_epoch > 0
            AND NULLIF(regexp_replace(script_patch_pinned_control_plane_request_id, '[[:space:]]', '', 'g'), '') IS NOT NULL
        )
    );

ALTER TABLE game_manifest
    ADD CONSTRAINT game_manifest_pkey PRIMARY KEY (id);

ALTER TABLE gameplay_admission_pointer_event
    ADD CONSTRAINT gameplay_admission_pointer_event_pkey PRIMARY KEY (id);

ALTER TABLE gameplay_admission_pointer
    ADD CONSTRAINT gameplay_admission_pointer_pkey PRIMARY KEY (id);

ALTER TABLE gameplay_command
    ADD CONSTRAINT gameplay_command_command_id_key UNIQUE (command_id);

ALTER TABLE gameplay_command
    ADD CONSTRAINT gameplay_command_pkey PRIMARY KEY (id);

ALTER TABLE gameplay_command
    ADD CONSTRAINT gameplay_command_script_pin_tuple_coherent
    CHECK (
        (
            upper(btrim(source_type)) = 'PLAYER'
            AND NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NULL
            AND script_pin_epoch IS NULL
            AND NULLIF(regexp_replace(script_pin_control_plane_request_id, '[[:space:]]', '', 'g'), '') IS NULL
        )
        OR
        (
            upper(btrim(source_type)) = 'AUTOMATION'
            AND NULLIF(regexp_replace(remote_followup_id, '[[:space:]]', '', 'g'), '') IS NULL
            AND (
                (
                    NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NULL
                    AND script_pin_epoch IS NULL
                    AND NULLIF(regexp_replace(script_pin_control_plane_request_id, '[[:space:]]', '', 'g'), '') IS NULL
                )
                OR
                (
                    NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NOT NULL
                    AND script_pin_epoch IS NOT NULL
                    AND script_pin_epoch > 0
                    AND NULLIF(regexp_replace(script_pin_control_plane_request_id, '[[:space:]]', '', 'g'), '') IS NOT NULL
                )
            )
        )
        OR
        (
            upper(btrim(source_type)) <> 'PLAYER'
            AND NOT (
                upper(btrim(source_type)) = 'AUTOMATION'
                AND NULLIF(regexp_replace(remote_followup_id, '[[:space:]]', '', 'g'), '') IS NULL
            )
            AND script_pin_epoch IS NULL
            AND NULLIF(regexp_replace(script_pin_control_plane_request_id, '[[:space:]]', '', 'g'), '') IS NULL
        )
    );

ALTER TABLE prepared_version_upgrade
    ADD CONSTRAINT prepared_version_upgrade_pkey PRIMARY KEY (id);

ALTER TABLE prepared_version_upgrade
    ADD CONSTRAINT prepared_version_upgrade_preparation_id_key UNIQUE (preparation_id);

ALTER TABLE remote_command_coordinator
    ADD CONSTRAINT remote_command_coordinator_coordinator_id_key UNIQUE (coordinator_id);

ALTER TABLE remote_command_coordinator
    ADD CONSTRAINT remote_command_coordinator_pkey PRIMARY KEY (id);

ALTER TABLE remote_followup
    ADD CONSTRAINT remote_followup_followup_id_key UNIQUE (followup_id);

ALTER TABLE remote_followup
    ADD CONSTRAINT remote_followup_pkey PRIMARY KEY (id);

ALTER TABLE remote_followup_result
    ADD CONSTRAINT remote_followup_result_pkey PRIMARY KEY (id);

ALTER TABLE remote_followup_result
    ADD CONSTRAINT remote_followup_result_result_id_key UNIQUE (result_id);

ALTER TABLE runtime_region_status
    ADD CONSTRAINT runtime_region_status_pkey PRIMARY KEY (id);

ALTER TABLE tick_batch
    ADD CONSTRAINT tick_batch_pkey PRIMARY KEY (id);

ALTER TABLE tick_batch
    ADD CONSTRAINT tick_batch_tick_batch_id_key UNIQUE (tick_batch_id);

ALTER TABLE tick_effect
    ADD CONSTRAINT tick_effect_effect_id_key UNIQUE (effect_id);

ALTER TABLE tick_effect
    ADD CONSTRAINT tick_effect_pkey PRIMARY KEY (id);

CREATE INDEX idx_feature_flag_tenant_id ON feature_flag USING btree (tenant_id);

CREATE UNIQUE INDEX idx_feature_flag_tenant_name ON feature_flag USING btree (tenant_id, name);

CREATE INDEX idx_game_instances_launch_descriptor_id ON game_instances USING btree (launch_descriptor_id);

CREATE INDEX idx_game_instances_owner_account_id ON game_instances USING btree (owner_account_id);

CREATE INDEX idx_game_instances_tenant_id ON game_instances USING btree (tenant_id);

CREATE INDEX idx_gameplay_admission_pointer_event_world_realm ON gameplay_admission_pointer_event USING btree (world_slug, realm_slug, occurred_at DESC);

CREATE INDEX idx_gameplay_admission_pointer_tenant_instance ON gameplay_admission_pointer USING btree (tenant_id, game_instance_id);

CREATE UNIQUE INDEX idx_gameplay_command_automation_dispatch ON gameplay_command USING btree (tenant_id, game_instance_id, region_id, region_epoch, automation_dispatch_id);

CREATE INDEX idx_gameplay_command_automation_plugin_version ON gameplay_command USING btree (tenant_id, game_instance_id, source_type, region_id, plugin_id, plugin_version_id);

CREATE UNIQUE INDEX idx_gameplay_command_command_id ON gameplay_command USING btree (command_id);

CREATE UNIQUE INDEX idx_gameplay_command_remote_followup ON gameplay_command USING btree (tenant_id, game_instance_id, region_id, region_epoch, remote_followup_id) WHERE (remote_followup_id IS NOT NULL);

CREATE INDEX idx_gameplay_command_tenant_instance_enqueue_seq ON gameplay_command USING btree (tenant_id, game_instance_id, enqueue_seq);

CREATE INDEX idx_gameplay_command_tenant_instance_status ON gameplay_command USING btree (tenant_id, game_instance_id, execution_outcome);

CREATE INDEX idx_resume_transcript_entry_scope_order
    ON resume_transcript_entry USING btree (tenant_id, game_instance_id, character_id, appended_at, id);

CREATE INDEX idx_resume_transcript_entry_expiry
    ON resume_transcript_entry USING btree (expires_at)
    WHERE expires_at IS NOT NULL;

CREATE INDEX idx_player_command_history_scope_order
    ON player_command_history USING btree (tenant_id, game_instance_id, character_id, accepted_at, id);

CREATE INDEX idx_prepared_version_upgrade_source_instance ON prepared_version_upgrade USING btree (tenant_id, source_game_instance_id, target_version_id);

CREATE UNIQUE INDEX idx_remote_command_coordinator_command_id ON remote_command_coordinator USING btree (tenant_id, command_id);

CREATE INDEX idx_remote_command_coordinator_followup_id ON remote_command_coordinator USING btree (tenant_id, followup_id);

CREATE INDEX idx_remote_command_coordinator_origin_region_state ON remote_command_coordinator USING btree (tenant_id, origin_region_id, state);

CREATE INDEX idx_remote_command_coordinator_provenance_updated ON remote_command_coordinator USING btree (tenant_id, script_id, plugin_id, automation_dispatch_id, command_id, updated_at);

CREATE INDEX idx_remote_command_coordinator_routing_updated ON remote_command_coordinator USING btree (tenant_id, script_patch_version, plugin_version_id, playable_state_scope, world_slug, realm_slug, pointer_version, updated_at);

CREATE INDEX idx_remote_command_coordinator_target_scope_state ON remote_command_coordinator USING btree (tenant_id, target_game_instance_id, target_region_id, target_region_epoch, state, updated_at);

CREATE INDEX idx_remote_command_coordinator_work_item_updated ON remote_command_coordinator USING btree (tenant_id, automation_work_item_id, updated_at);

CREATE INDEX idx_remote_followup_claim_target_due ON remote_followup USING btree (tenant_id, target_region_id, status, claim_target_aggregate, due_tick_id);

CREATE INDEX idx_remote_followup_event_due ON remote_followup USING btree (tenant_id, event_type, script_event_id, due_tick_id);

CREATE INDEX idx_remote_followup_identity_due ON remote_followup USING btree (tenant_id, automation_work_item_id, target_entity_id, effect_key, failure_code, due_tick_id);

CREATE INDEX idx_remote_followup_origin_scope_due ON remote_followup USING btree (tenant_id, origin_game_instance_id, origin_region_id, due_tick_id);

CREATE INDEX idx_remote_followup_origin_scope_epoch_due ON remote_followup USING btree (tenant_id, origin_game_instance_id, origin_region_id, origin_region_epoch, due_tick_id);

CREATE INDEX idx_remote_followup_provenance_due ON remote_followup USING btree (tenant_id, script_id, plugin_id, automation_dispatch_id, command_id, due_tick_id);

CREATE INDEX idx_remote_followup_queue_source_due ON remote_followup USING btree (tenant_id, queue_source_kind, queue_source_state, queue_source_due_tick_id, due_tick_id);

CREATE INDEX idx_remote_followup_result_coordinator_observed ON remote_followup_result USING btree (tenant_id, coordinator_id, observed_at);

CREATE INDEX idx_remote_followup_result_followup_id ON remote_followup_result USING btree (tenant_id, followup_id);

CREATE INDEX idx_remote_followup_result_identity_observed ON remote_followup_result USING btree (tenant_id, automation_work_item_id, result_command_id, observed_at);

CREATE INDEX idx_remote_followup_result_provenance_observed ON remote_followup_result USING btree (tenant_id, script_id, plugin_id, automation_dispatch_id, command_id, observed_at);

CREATE INDEX idx_remote_followup_result_routing_observed ON remote_followup_result USING btree (tenant_id, script_patch_version, plugin_version_id, playable_state_scope, world_slug, realm_slug, pointer_version, result_error_code, observed_at);

CREATE INDEX idx_remote_followup_result_scope_observed ON remote_followup_result USING btree (tenant_id, origin_game_instance_id, origin_region_id, origin_region_epoch, target_game_instance_id, target_region_id, target_region_epoch, observed_at);

CREATE INDEX idx_remote_followup_routing_due ON remote_followup USING btree (tenant_id, script_patch_version, plugin_version_id, playable_state_scope, world_slug, realm_slug, pointer_version, payload_kind, origin_source_kind, due_tick_id);

CREATE UNIQUE INDEX idx_remote_followup_target_instance_region_epoch_effect
    ON remote_followup USING btree (tenant_id, target_game_instance_id, target_region_id, target_region_epoch, effect_key);

CREATE INDEX idx_remote_followup_target_region_status_due ON remote_followup USING btree (tenant_id, target_region_id, status, due_tick_id);

CREATE INDEX idx_remote_followup_target_scope_epoch_due ON remote_followup USING btree (tenant_id, target_game_instance_id, target_region_id, target_region_epoch, due_tick_id);

CREATE UNIQUE INDEX idx_runtime_region_status_tenant_instance ON runtime_region_status USING btree (tenant_id, game_instance_id);

CREATE UNIQUE INDEX idx_runtime_region_status_tenant_region ON runtime_region_status USING btree (tenant_id, region_id);

CREATE INDEX idx_tick_batch_tenant_instance_status ON tick_batch USING btree (tenant_id, game_instance_id, status);

CREATE UNIQUE INDEX idx_tick_batch_tick_batch_id ON tick_batch USING btree (tick_batch_id);

CREATE UNIQUE INDEX idx_tick_effect_batch_effect_key ON tick_effect USING btree (tick_batch_id, effect_key);

CREATE INDEX idx_tick_effect_command_id ON tick_effect USING btree (command_id);

CREATE UNIQUE INDEX idx_tick_effect_effect_id ON tick_effect USING btree (effect_id);

CREATE INDEX idx_tick_effect_effect_key ON tick_effect USING btree (effect_key);

CREATE INDEX idx_tick_effect_tick_batch_id ON tick_effect USING btree (tick_batch_id);

CREATE UNIQUE INDEX uq_game_instances_running_tenant_owner ON game_instances USING btree (tenant_id, owner_account_id) WHERE ((status)::text = 'RUNNING'::text);

CREATE UNIQUE INDEX uq_gameplay_admission_pointer_world_realm ON gameplay_admission_pointer USING btree (world_slug, realm_slug);

CREATE UNIQUE INDEX uq_gameplay_admission_pointer_runtime_target
    ON gameplay_admission_pointer USING btree (tenant_id, game_instance_id);

CREATE UNIQUE INDEX uq_prepared_version_upgrade_request ON prepared_version_upgrade USING btree (tenant_id, control_plane_request_id);
