ALTER TABLE game_session_canonical_binding_region_bridge_obligation
    ADD COLUMN bridge_operation_id uuid,
    ADD COLUMN bridge_preimage_state character varying(8),
    ADD COLUMN runtime_entity_id bigint,
    ADD COLUMN entity_projection_generation bigint,
    ADD COLUMN entity_projection_fence bigint,
    ADD COLUMN entity_workload_namespace text,
    ADD COLUMN assignment_uuid uuid,
    ADD COLUMN assignment_intent_digest character varying(64),
    ADD COLUMN entity_reference_digest character varying(64),
    ADD COLUMN world_source_digest character varying(64),
    ADD COLUMN canonical_region_id uuid,
    ADD COLUMN world_lifecycle_epoch bigint,
    ADD COLUMN world_lifecycle_row_version bigint,
    ADD COLUMN world_capture_id uuid,
    ADD COLUMN world_graph_sha256 character varying(64),
    ADD COLUMN world_preparation_input_digest character varying(71),
    ADD COLUMN executor_fence numeric,
    ADD COLUMN lease_token_correlation character varying(64),
    ADD COLUMN redis_readback_digest character varying(64),
    ADD COLUMN acknowledged_at timestamp with time zone;

ALTER TABLE game_session_canonical_binding_region_bridge_obligation
    DROP CONSTRAINT chk_gs_canonical_binding_region_bridge_obligation;

ALTER TABLE game_session_canonical_binding_region_bridge_obligation
    ADD CONSTRAINT chk_gs_canonical_binding_region_bridge_obligation CHECK (
        binding_generation > 0
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND game_instance_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND runtime_game_instance_id > 0
        -- [jooq ignore start]
        AND session_id <> ''
        -- [jooq ignore stop]
        AND session_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        AND session_id <> '00000000-0000-0000-0000-000000000000'
        AND session_id !~ '^[[:space:]]*$'
        AND session_id !~ '[[:cntrl:]]'
        AND region_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND region_epoch > 0
        AND status IN ('REQUIRED', 'ACKNOWLEDGED')
        AND inventory_revision > 0
        AND (
            (bridge_operation_id IS NULL
                AND bridge_preimage_state IS NULL
                AND runtime_entity_id IS NULL
                AND entity_projection_generation IS NULL
                AND entity_projection_fence IS NULL
                AND entity_workload_namespace IS NULL
                AND assignment_uuid IS NULL
                AND assignment_intent_digest IS NULL
                AND entity_reference_digest IS NULL
                AND world_source_digest IS NULL
                AND canonical_region_id IS NULL
                AND world_lifecycle_epoch IS NULL
                AND world_lifecycle_row_version IS NULL
                AND world_capture_id IS NULL
                AND world_graph_sha256 IS NULL
                AND world_preparation_input_digest IS NULL)
            OR
            (bridge_operation_id IS NOT NULL
                AND bridge_preimage_state IS NOT NULL
                AND bridge_preimage_state = 'ABSENT'
                AND runtime_entity_id IS NOT NULL
                AND runtime_entity_id > 0
                AND entity_projection_generation IS NOT NULL
                AND entity_projection_generation > 0
                AND entity_projection_fence IS NOT NULL
                AND entity_projection_fence > 0
                AND entity_workload_namespace IS NOT NULL
                AND entity_workload_namespace <> ''
                AND assignment_uuid IS NOT NULL
                AND assignment_uuid <> '00000000-0000-0000-0000-000000000000'::uuid
                AND assignment_intent_digest IS NOT NULL
                AND assignment_intent_digest ~ '^[0-9a-f]{64}$'
                AND entity_reference_digest IS NOT NULL
                AND entity_reference_digest ~ '^[0-9a-f]{64}$'
                AND world_source_digest IS NOT NULL
                AND world_source_digest ~ '^[0-9a-f]{64}$'
                AND canonical_region_id IS NOT NULL
                AND canonical_region_id <> '00000000-0000-0000-0000-000000000000'::uuid
                AND world_lifecycle_epoch IS NOT NULL
                AND world_lifecycle_epoch > 0
                AND world_lifecycle_row_version IS NOT NULL
                AND world_lifecycle_row_version >= 0
                AND world_capture_id IS NOT NULL
                AND world_capture_id <> '00000000-0000-0000-0000-000000000000'::uuid
                AND world_graph_sha256 IS NOT NULL
                AND world_graph_sha256 ~ '^[0-9a-f]{64}$'
                AND world_preparation_input_digest IS NOT NULL
                AND world_preparation_input_digest ~ '^sha256:[0-9a-f]{64}$')
        )
        AND (
            (status = 'REQUIRED'
                AND redis_readback_digest IS NULL
                AND acknowledged_at IS NULL)
            OR
            (status = 'ACKNOWLEDGED'
                AND bridge_operation_id IS NOT NULL
                AND executor_fence IS NOT NULL
                AND executor_fence > 0
                AND lease_token_correlation IS NOT NULL
                AND lease_token_correlation ~ '^[0-9a-f]{64}$'
                AND redis_readback_digest IS NOT NULL
                AND redis_readback_digest ~ '^[0-9a-f]{64}$'
                AND acknowledged_at IS NOT NULL)
        )
        AND (
            (bridge_operation_id IS NULL
                AND executor_fence IS NULL
                AND lease_token_correlation IS NULL)
            OR
            (bridge_operation_id IS NOT NULL
                AND executor_fence IS NOT NULL
                AND executor_fence > 0
                AND lease_token_correlation IS NOT NULL
                AND lease_token_correlation ~ '^[0-9a-f]{64}$')
        )
    );

CREATE UNIQUE INDEX ux_gs_canonical_region_bridge_operation
    ON game_session_canonical_binding_region_bridge_obligation (bridge_operation_id)
    WHERE bridge_operation_id IS NOT NULL;

CREATE TABLE game_session_region_timeline_preparation (
    initialization_operation_id uuid NOT NULL,
    transition_id uuid NOT NULL,
    account_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    playable_state_namespace_id uuid NOT NULL,
    character_id uuid NOT NULL,
    playable_state_scope character varying(16) NOT NULL,
    game_instance_id uuid NOT NULL,
    runtime_game_instance_id bigint NOT NULL,
    binding_ref bytea NOT NULL,
    session_id character varying(36) NOT NULL,
    binding_generation numeric NOT NULL,
    canonical_region_id uuid NOT NULL,
    region_id uuid NOT NULL,
    tenant_region_tag character varying(101) NOT NULL,
    lifecycle_epoch bigint NOT NULL,
    lifecycle_row_version bigint NOT NULL,
    capture_id uuid NOT NULL,
    lifecycle_request_bytes bytea NOT NULL,
    graph_sha256 character varying(64) NOT NULL,
    preparation_input_digest character varying(71) NOT NULL,
    assignment_uuid uuid NOT NULL,
    assignment_intent_digest character varying(64) NOT NULL,
    world_source_digest character varying(64) NOT NULL,
    preparation_digest character varying(64) NOT NULL,
    stable_incarnation_scope_bytes bytea NOT NULL,
    stable_incarnation_source_bytes bytea NOT NULL,
    stable_incarnation_qualification_bytes bytea NOT NULL,
    stable_incarnation_digest character varying(64) NOT NULL,
    timeline_preimage_state character varying(8) NOT NULL,
    timeline_absence_proof_digest character varying(64) NOT NULL,
    region_epoch bigint NOT NULL,
    last_committed_tick_id bigint NOT NULL,
    phase character varying(24) NOT NULL,
    lease_token_correlation character varying(64),
    executor_fence bigint,
    redis_meta_digest character varying(64),
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT pk_game_session_region_timeline_preparation
        PRIMARY KEY (initialization_operation_id),
    CONSTRAINT ux_game_session_region_timeline_preparation_transition
        UNIQUE (transition_id),
    CONSTRAINT ux_game_session_region_timeline_preparation_scope
        UNIQUE (tenant_id, game_instance_id, region_id),
    CONSTRAINT ck_game_session_region_timeline_preparation_ids CHECK (
        initialization_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND transition_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND character_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND game_instance_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND canonical_region_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND region_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND capture_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND assignment_uuid <> '00000000-0000-0000-0000-000000000000'::uuid),
    CONSTRAINT ck_game_session_region_timeline_preparation_binding CHECK (
        playable_state_scope = 'SHARED'
        AND runtime_game_instance_id > 0
        AND octet_length(binding_ref) BETWEEN 1 AND 128
        AND session_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        AND session_id <> '00000000-0000-0000-0000-000000000000'
        AND binding_generation > 0),
    CONSTRAINT ck_game_session_region_timeline_preparation_source CHECK (
        tenant_region_tag ~ '^r1-[0-9a-f]{32}-[0-9a-f]{32}-[0-9a-f]{32}$'
        AND lifecycle_epoch > 0
        AND lifecycle_row_version >= 0
        AND octet_length(lifecycle_request_bytes) BETWEEN 1 AND 4096
        AND graph_sha256 ~ '^[0-9a-f]{64}$'
        AND preparation_input_digest ~ '^sha256:[0-9a-f]{64}$'
        AND assignment_intent_digest ~ '^[0-9a-f]{64}$'
        AND world_source_digest ~ '^[0-9a-f]{64}$'
        AND preparation_digest ~ '^[0-9a-f]{64}$'
        AND octet_length(stable_incarnation_scope_bytes) BETWEEN 1 AND 4096
        AND octet_length(stable_incarnation_source_bytes) BETWEEN 1 AND 65536
        AND octet_length(stable_incarnation_qualification_bytes) BETWEEN 1 AND 8192
        AND stable_incarnation_digest ~ '^[0-9a-f]{64}$'
        AND timeline_preimage_state = 'ABSENT'
        AND timeline_absence_proof_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_game_session_region_timeline_preparation_baseline CHECK (
        region_epoch = 1
        AND last_committed_tick_id = -1),
    CONSTRAINT ck_game_session_region_timeline_preparation_phase CHECK (
        (phase IN ('PREPARED', 'INITIALIZING')
            AND lease_token_correlation IS NULL
            AND executor_fence IS NULL
            AND redis_meta_digest IS NULL)
        OR
        (phase = 'REDIS_INITIALIZED'
            AND lease_token_correlation IS NOT NULL
            AND lease_token_correlation ~ '^[0-9a-f]{64}$'
            AND executor_fence IS NULL
            AND redis_meta_digest IS NOT NULL
            AND redis_meta_digest ~ '^[0-9a-f]{64}$')
        OR
        (phase = 'INSTALLED'
            AND lease_token_correlation IS NOT NULL
            AND lease_token_correlation ~ '^[0-9a-f]{64}$'
            AND executor_fence IS NOT NULL
            AND executor_fence > 0
            AND redis_meta_digest IS NOT NULL
            AND redis_meta_digest ~ '^[0-9a-f]{64}$'))
);
