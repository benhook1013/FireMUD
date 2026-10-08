-- Durable preparation evidence for namespace-qualified gameplay bindings. This is a
-- Game Session inventory and obligation ledger, not an admission or controller authority.
-- Existing numeric/instance-scoped rows are intentionally neither relabelled nor backfilled.
CREATE TABLE game_session_canonical_binding_inventory_clock (
    singleton_id smallint NOT NULL,
    inventory_revision numeric NOT NULL,
    CONSTRAINT pk_gs_canonical_binding_inventory_clock PRIMARY KEY (singleton_id),
    CONSTRAINT chk_gs_canonical_binding_inventory_clock_singleton CHECK (singleton_id = 1),
    CONSTRAINT chk_gs_canonical_binding_inventory_clock_revision CHECK (inventory_revision >= 0)
);

INSERT INTO game_session_canonical_binding_inventory_clock (singleton_id, inventory_revision)
VALUES (1, 0);

CREATE TABLE game_session_canonical_binding_generation (
    tenant_id uuid NOT NULL,
    playable_state_namespace_id uuid NOT NULL,
    character_id uuid NOT NULL,
    last_issued_generation numeric NOT NULL,
    CONSTRAINT pk_gs_canonical_binding_generation
        PRIMARY KEY (tenant_id, playable_state_namespace_id, character_id),
    CONSTRAINT chk_gs_canonical_binding_generation_scope CHECK (
        tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND character_id <> '00000000-0000-0000-0000-000000000000'::uuid),
    CONSTRAINT chk_gs_canonical_binding_generation_positive CHECK (last_issued_generation >= 0)
);

CREATE TABLE game_session_canonical_gameplay_binding_inventory (
    binding_ref bytea NOT NULL,
    account_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    playable_state_namespace_id uuid NOT NULL,
    character_id uuid NOT NULL,
    playable_state_scope character varying(16) NOT NULL,
    game_instance_id uuid NOT NULL,
    runtime_game_instance_id bigint NOT NULL,
    session_id text NOT NULL,
    binding_generation numeric NOT NULL,
    region_id uuid NOT NULL,
    region_epoch numeric NOT NULL,
    issuer_id uuid NOT NULL,
    issuer_auth_generation numeric NOT NULL,
    issuer_index_layout_version numeric NOT NULL,
    issuer_index_partition_count numeric NOT NULL,
    issuer_index_partition_capacity numeric NOT NULL,
    issuer_partition_id numeric NOT NULL,
    account_index_fence uuid NOT NULL,
    issuer_reservation_id uuid NOT NULL,
    transition_id uuid NOT NULL,
    lifecycle character varying(24) NOT NULL,
    account_index_state character varying(16) NOT NULL,
    issuer_index_state character varying(16) NOT NULL,
    inventory_revision numeric NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_gs_canonical_gameplay_binding_inventory_reservation
        UNIQUE (issuer_reservation_id),
    CONSTRAINT chk_gs_canonical_gameplay_binding_inventory_identity CHECK (
        octet_length(binding_ref) > 0
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND character_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND game_instance_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND runtime_game_instance_id > 0
        AND region_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND issuer_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND transition_id <> '00000000-0000-0000-0000-000000000000'::uuid
        -- H2's jOOQ DDL parser turns TEXT <> '' into an invalid CLOB(0) cast. The adjacent
        -- canonical UUID regex already rejects empty text; PostgreSQL retains this explicit check.
        -- [jooq ignore start]
        AND session_id <> ''
        -- [jooq ignore stop]
        AND session_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        AND session_id <> '00000000-0000-0000-0000-000000000000'
        AND session_id !~ '^[[:space:]]*$'
        AND session_id !~ '[[:cntrl:]]'
        AND playable_state_scope IN ('SHARED', 'ISOLATED')
        AND binding_generation > 0
        AND region_epoch > 0
        AND issuer_auth_generation > 0
        AND issuer_index_layout_version > 0
        AND issuer_index_partition_count > 0
        AND issuer_index_partition_capacity > 0
        AND issuer_partition_id >= 0
        AND issuer_partition_id < issuer_index_partition_count
        AND account_index_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND issuer_reservation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND inventory_revision > 0),
    CONSTRAINT chk_gs_canonical_gameplay_binding_inventory_state CHECK (
        lifecycle IN ('CANDIDATE_PREPARED', 'PROVISIONAL', 'ACTIVE', 'TERMINAL_UNRESOLVED', 'TERMINAL')
        AND account_index_state IN ('REPAIR_REQUIRED', 'ACKNOWLEDGED')
        AND issuer_index_state IN ('REPAIR_REQUIRED', 'ACKNOWLEDGED'))
);

-- H2's jOOQ schema parser maps BYTEA to BLOB and cannot index that type. Keep the
-- PostgreSQL runtime primary key exact while exposing the complete table to generation.
-- [jooq ignore start]
ALTER TABLE game_session_canonical_gameplay_binding_inventory
    ADD CONSTRAINT pk_gs_canonical_gameplay_binding_inventory PRIMARY KEY (binding_ref);
-- [jooq ignore stop]

CREATE UNIQUE INDEX uq_gs_canonical_gameplay_binding_active_controller
    ON game_session_canonical_gameplay_binding_inventory
       (tenant_id, playable_state_namespace_id, character_id)
    WHERE lifecycle = 'ACTIVE';

CREATE INDEX ix_gs_canonical_gameplay_binding_inventory_account_revision
    ON game_session_canonical_gameplay_binding_inventory (account_id, inventory_revision);

CREATE INDEX ix_gs_canonical_gameplay_binding_inventory_issuer_revision
    ON game_session_canonical_gameplay_binding_inventory (issuer_id, inventory_revision);

CREATE TABLE game_session_canonical_binding_transition (
    transition_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    playable_state_namespace_id uuid NOT NULL,
    character_id uuid NOT NULL,
    expected_prior_binding_ref bytea,
    expected_prior_binding_generation numeric,
    candidate_binding_ref bytea NOT NULL,
    candidate_binding_generation numeric NOT NULL,
    candidate_account_index_fence uuid NOT NULL,
    issuer_reservation_id uuid NOT NULL,
    status character varying(24) NOT NULL,
    inventory_revision numeric NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_canonical_binding_transition PRIMARY KEY (transition_id),
    CONSTRAINT chk_gs_canonical_binding_transition_identity CHECK (
        transition_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND character_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND candidate_binding_generation > 0
        AND candidate_account_index_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND issuer_reservation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND inventory_revision > 0
        AND ((expected_prior_binding_ref IS NULL) = (expected_prior_binding_generation IS NULL))
        AND (expected_prior_binding_generation IS NULL OR expected_prior_binding_generation > 0)
        AND status IN ('PREPARED', 'PROVISIONAL', 'COMMITTED', 'ABORTED', 'AMBIGUOUS'))
);

-- The candidate carrier is byte-exact BYTEA in PostgreSQL; H2's jOOQ parser cannot index or
-- reference its BLOB representation. These constraints remain enforced by the runtime schema.
-- [jooq ignore start]
ALTER TABLE game_session_canonical_binding_transition
    ADD CONSTRAINT uq_gs_canonical_binding_transition_candidate UNIQUE (candidate_binding_ref);
ALTER TABLE game_session_canonical_binding_transition
    ADD CONSTRAINT fk_gs_canonical_binding_transition_candidate
        FOREIGN KEY (candidate_binding_ref)
        REFERENCES game_session_canonical_gameplay_binding_inventory (binding_ref);
-- [jooq ignore stop]

CREATE UNIQUE INDEX uq_gs_canonical_binding_transition_open_controller
    ON game_session_canonical_binding_transition
       (tenant_id, playable_state_namespace_id, character_id)
    WHERE status IN ('PREPARED', 'PROVISIONAL', 'AMBIGUOUS');

CREATE TABLE game_session_canonical_binding_account_index_obligation (
    transition_id uuid NOT NULL,
    obligation_ordinal integer NOT NULL,
    binding_ref bytea NOT NULL,
    account_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    action character varying(16) NOT NULL,
    binding_generation numeric NOT NULL,
    expected_prior_generation numeric,
    account_index_fence uuid NOT NULL,
    execution_phase character varying(24) NOT NULL,
    status character varying(16) NOT NULL,
    inventory_revision numeric NOT NULL,
    CONSTRAINT pk_gs_canonical_binding_account_index_obligation
        PRIMARY KEY (transition_id, obligation_ordinal),
    CONSTRAINT fk_gs_canonical_binding_account_index_obligation_transition
        FOREIGN KEY (transition_id)
        REFERENCES game_session_canonical_binding_transition (transition_id),
    CONSTRAINT chk_gs_canonical_binding_account_index_obligation CHECK (
        obligation_ordinal >= 0
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND action IN ('ADD_OR_RETAIN', 'REMOVE')
        AND binding_generation > 0
        AND (expected_prior_generation IS NULL OR expected_prior_generation >= 0)
        AND account_index_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND execution_phase IN ('BEFORE_FINAL_CAS', 'AFTER_FINAL_CAS')
        AND status = 'REQUIRED'
        AND inventory_revision > 0)
);

CREATE TABLE game_session_canonical_issuer_partition_reservation (
    reservation_id uuid NOT NULL,
    binding_ref bytea NOT NULL,
    account_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    game_instance_id uuid NOT NULL,
    runtime_game_instance_id bigint NOT NULL,
    session_id text NOT NULL,
    binding_generation numeric NOT NULL,
    transition_id uuid NOT NULL,
    issuer_id uuid NOT NULL,
    issuer_auth_generation numeric NOT NULL,
    playable_state_namespace_id uuid NOT NULL,
    character_id uuid NOT NULL,
    playable_state_scope character varying(16) NOT NULL,
    region_id uuid NOT NULL,
    region_epoch numeric NOT NULL,
    partition_id numeric NOT NULL,
    issuer_index_layout_version numeric NOT NULL,
    issuer_index_partition_count numeric NOT NULL,
    issuer_index_partition_capacity numeric NOT NULL,
    lifecycle character varying(24) NOT NULL,
    reservation_fence uuid NOT NULL,
    issuer_coverage_operation_id uuid NOT NULL,
    issuer_coverage_operation_fence numeric NOT NULL,
    coverage_fence numeric NOT NULL,
    inventory_snapshot_revision numeric NOT NULL,
    inventory_revision numeric NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_canonical_issuer_partition_reservation PRIMARY KEY (reservation_id),
    CONSTRAINT chk_gs_canonical_issuer_partition_reservation CHECK (
        reservation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND game_instance_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND runtime_game_instance_id > 0
        AND issuer_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND character_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND region_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND transition_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND issuer_coverage_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        -- H2's jOOQ DDL parser turns TEXT <> '' into an invalid CLOB(0) cast. The adjacent
        -- canonical UUID regex already rejects empty text; PostgreSQL retains this explicit check.
        -- [jooq ignore start]
        AND session_id <> ''
        -- [jooq ignore stop]
        AND session_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        AND session_id <> '00000000-0000-0000-0000-000000000000'
        AND session_id !~ '^[[:space:]]*$'
        AND session_id !~ '[[:cntrl:]]'
        AND binding_generation > 0
        AND issuer_auth_generation > 0
        AND playable_state_scope IN ('SHARED', 'ISOLATED')
        AND region_epoch > 0
        AND partition_id >= 0
        AND issuer_index_layout_version > 0
        AND issuer_index_partition_count > 0
        AND partition_id < issuer_index_partition_count
        AND issuer_index_partition_capacity > 0
        AND lifecycle IN ('RESERVED', 'BOUND', 'RELEASE_PENDING', 'RELEASED')
        AND reservation_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND issuer_coverage_operation_fence > 0
        AND coverage_fence > 0
        AND inventory_snapshot_revision > 0
        AND inventory_revision > 0)
);

CREATE INDEX ix_gs_canonical_issuer_reservation_partition_revision
    ON game_session_canonical_issuer_partition_reservation
       (issuer_id, issuer_index_layout_version, partition_id, inventory_revision)
    WHERE lifecycle <> 'RELEASED';

CREATE TABLE game_session_canonical_binding_issuer_index_obligation (
    transition_id uuid NOT NULL,
    binding_ref bytea NOT NULL,
    binding_generation numeric NOT NULL,
    reservation_id uuid NOT NULL,
    status character varying(16) NOT NULL,
    inventory_revision numeric NOT NULL,
    CONSTRAINT fk_gs_canonical_binding_issuer_index_obligation_transition
        FOREIGN KEY (transition_id)
        REFERENCES game_session_canonical_binding_transition (transition_id),
    CONSTRAINT fk_gs_canonical_binding_issuer_index_obligation_reservation
        FOREIGN KEY (reservation_id)
        REFERENCES game_session_canonical_issuer_partition_reservation (reservation_id),
    CONSTRAINT chk_gs_canonical_binding_issuer_index_obligation CHECK (
        binding_generation > 0
        AND status = 'REQUIRED'
        AND inventory_revision > 0)
);

-- Preserve the exact composite BYTEA key in PostgreSQL while allowing H2 schema generation.
-- [jooq ignore start]
ALTER TABLE game_session_canonical_binding_issuer_index_obligation
    ADD CONSTRAINT pk_gs_canonical_binding_issuer_index_obligation
        PRIMARY KEY (transition_id, binding_ref);
-- [jooq ignore stop]

CREATE TABLE game_session_canonical_binding_region_bridge_obligation (
    transition_id uuid NOT NULL,
    binding_ref bytea NOT NULL,
    binding_generation numeric NOT NULL,
    account_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    game_instance_id uuid NOT NULL,
    runtime_game_instance_id bigint NOT NULL,
    session_id text NOT NULL,
    region_id uuid NOT NULL,
    region_epoch numeric NOT NULL,
    status character varying(16) NOT NULL,
    inventory_revision numeric NOT NULL,
    CONSTRAINT pk_gs_canonical_binding_region_bridge_obligation PRIMARY KEY (transition_id),
    CONSTRAINT fk_gs_canonical_binding_region_bridge_obligation_transition
        FOREIGN KEY (transition_id)
        REFERENCES game_session_canonical_binding_transition (transition_id),
    CONSTRAINT chk_gs_canonical_binding_region_bridge_obligation CHECK (
        binding_generation > 0
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND game_instance_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND runtime_game_instance_id > 0
        -- H2's jOOQ DDL parser turns TEXT <> '' into an invalid CLOB(0) cast. The adjacent
        -- canonical UUID regex already rejects empty text; PostgreSQL retains this explicit check.
        -- [jooq ignore start]
        AND session_id <> ''
        -- [jooq ignore stop]
        AND session_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        AND session_id <> '00000000-0000-0000-0000-000000000000'
        AND session_id !~ '^[[:space:]]*$'
        AND session_id !~ '[[:cntrl:]]'
        AND region_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND region_epoch > 0
        AND status = 'REQUIRED'
        AND inventory_revision > 0)
);

-- No legacy numeric actor ID is joined to a canonical character UUID here. Unmapped legacy
-- evidence remains in its existing owner and admission remains outside this foundation.
