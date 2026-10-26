CREATE SEQUENCE game_session_region_executor_fence_seq
    AS bigint
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    NO CYCLE;

CREATE TABLE game_session_region_runtime_status (
    tenant_id uuid NOT NULL,
    game_instance_id uuid NOT NULL,
    canonical_region_id uuid NOT NULL,
    region_id uuid NOT NULL,
    tenant_region_tag character varying(104) NOT NULL,
    lifecycle_epoch bigint NOT NULL,
    lifecycle_row_version bigint NOT NULL,
    lifecycle_request_bytes bytea NOT NULL,
    capture_id uuid NOT NULL,
    graph_sha256 character varying(64) NOT NULL,
    preparation_input_digest character varying(71) NOT NULL,
    region_epoch bigint NOT NULL,
    executor_fence bigint NOT NULL,
    lease_token_correlation character varying(64) NOT NULL,
    owner_service character varying(80) NOT NULL,
    owner_instance_id character varying(120) NOT NULL,
    paused boolean NOT NULL,
    last_committed_tick_batch_id character varying(64),
    last_committed_tick_id bigint NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    CONSTRAINT pk_game_session_region_runtime_status
        PRIMARY KEY (tenant_id, game_instance_id, region_id),
    CONSTRAINT ux_game_session_region_runtime_tenant_tag
        UNIQUE (tenant_region_tag),
    CONSTRAINT ux_game_session_region_runtime_executor_fence
        UNIQUE (executor_fence),
    CONSTRAINT ck_game_session_region_runtime_ids CHECK (
        tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND game_instance_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND canonical_region_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND region_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND capture_id <> '00000000-0000-0000-0000-000000000000'::uuid),
    CONSTRAINT ck_game_session_region_runtime_tag CHECK (
        tenant_region_tag ~ '^r1-[0-9a-f]{32}-[0-9a-f]{32}-[0-9a-f]{32}$'),
    CONSTRAINT ck_game_session_region_runtime_world_source CHECK (
        lifecycle_epoch > 0
        AND lifecycle_row_version >= 0
        AND octet_length(lifecycle_request_bytes) BETWEEN 1 AND 4096
        AND graph_sha256 ~ '^[0-9a-f]{64}$'
        AND preparation_input_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_game_session_region_runtime_fence CHECK (
        region_epoch > 0
        AND executor_fence > 0
        AND lease_token_correlation ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_game_session_region_runtime_owner CHECK (
        owner_service <> ''
        AND owner_instance_id <> ''
        AND last_committed_tick_id >= -1)
);
