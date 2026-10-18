-- Fresh-only selected-source retention. Existing Entity rows are never adopted as a selected
-- source; they are reserved as occupied numeric keys and deny a fresh empty-source census.
CREATE TABLE entity_empty_source_numeric_key_reservation (
    key_kind VARCHAR(16) NOT NULL,
    numeric_key BIGINT NOT NULL,
    claim_kind VARCHAR(32) NOT NULL,
    CONSTRAINT pk_entity_empty_source_numeric_key_reservation
        PRIMARY KEY (key_kind, numeric_key),
    CONSTRAINT uq_entity_empty_source_numeric_key_claim
        UNIQUE (numeric_key, key_kind, claim_kind),
    CONSTRAINT ck_entity_empty_source_numeric_key_kind
        CHECK (key_kind IN ('TENANT', 'VERSION')),
    CONSTRAINT ck_entity_empty_source_numeric_key_positive
        CHECK (numeric_key > 0),
    CONSTRAINT ck_entity_empty_source_numeric_key_claim
        CHECK (claim_kind IN ('LEGACY_NUMERIC', 'CANONICAL_EMPTY_SOURCE'))
);

CREATE TABLE entity_empty_selected_source_association (
    target_namespace VARCHAR(63) NOT NULL,
    genesis_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    fence_id UUID NOT NULL,
    intake_request_id UUID NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    selected_commit_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    source_revision_order VARCHAR(32) NOT NULL,
    local_tenant_key BIGINT NOT NULL,
    local_tenant_key_kind VARCHAR(16) NOT NULL DEFAULT 'TENANT',
    local_tenant_key_claim_kind VARCHAR(32) NOT NULL DEFAULT 'CANONICAL_EMPTY_SOURCE',
    local_version_key BIGINT NOT NULL,
    local_version_key_kind VARCHAR(16) NOT NULL DEFAULT 'VERSION',
    local_version_key_claim_kind VARCHAR(32) NOT NULL DEFAULT 'CANONICAL_EMPTY_SOURCE',
    request_digest VARCHAR(71) NOT NULL,
    schema_digest VARCHAR(71) NOT NULL,
    authorization_binding_digest VARCHAR(71) NOT NULL,
    selected_source_digest VARCHAR(71) NOT NULL,
    world_read_request_id UUID NOT NULL,
    world_read_request_digest VARCHAR(71) NOT NULL,
    world_closure_digest VARCHAR(71) NOT NULL,
    receipt_digest VARCHAR(71) NOT NULL,
    associated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_entity_empty_selected_source_association
        PRIMARY KEY (target_namespace, canonical_tenant_id, canonical_version_id),
    CONSTRAINT uq_entity_empty_selected_source_request
        UNIQUE (target_namespace, intake_request_id),
    CONSTRAINT uq_entity_empty_selected_source_operation
        UNIQUE (target_namespace, operation_id),
    CONSTRAINT uq_entity_empty_selected_source_genesis
        UNIQUE (genesis_id),
    CONSTRAINT uq_entity_empty_selected_source_local_tenant
        UNIQUE (local_tenant_key),
    CONSTRAINT uq_entity_empty_selected_source_local_version
        UNIQUE (local_version_key),
    CONSTRAINT ck_entity_empty_selected_source_namespace
        CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT ck_entity_empty_selected_source_ids_nonzero
        CHECK (
            genesis_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND fence_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND intake_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND selected_commit_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND source_revision_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND world_read_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        ),
    CONSTRAINT ck_entity_empty_selected_source_local_keys
        CHECK (
            local_tenant_key > 0
            AND local_version_key > 0
            AND local_tenant_key <> local_version_key
            AND local_tenant_key_kind = 'TENANT'
            AND local_tenant_key_claim_kind = 'CANONICAL_EMPTY_SOURCE'
            AND local_version_key_kind = 'VERSION'
            AND local_version_key_claim_kind = 'CANONICAL_EMPTY_SOURCE'
        ),
    CONSTRAINT ck_entity_empty_selected_source_revision_order
        CHECK (source_revision_order ~ '^(0|[1-9][0-9]*)$'),
    CONSTRAINT ck_entity_empty_selected_source_digests
        CHECK (
            request_digest ~ '^sha256:[0-9a-f]{64}$'
            AND schema_digest ~ '^sha256:[0-9a-f]{64}$'
            AND authorization_binding_digest ~ '^sha256:[0-9a-f]{64}$'
            AND selected_source_digest ~ '^sha256:[0-9a-f]{64}$'
            AND world_read_request_digest ~ '^sha256:[0-9a-f]{64}$'
            AND world_closure_digest ~ '^sha256:[0-9a-f]{64}$'
            AND receipt_digest ~ '^sha256:[0-9a-f]{64}$'
        ),
    CONSTRAINT fk_entity_empty_selected_source_tenant_key
        FOREIGN KEY (local_tenant_key, local_tenant_key_kind, local_tenant_key_claim_kind)
        REFERENCES entity_empty_source_numeric_key_reservation
            (numeric_key, key_kind, claim_kind),
    CONSTRAINT fk_entity_empty_selected_source_version_key
        FOREIGN KEY (local_version_key, local_version_key_kind, local_version_key_claim_kind)
        REFERENCES entity_empty_source_numeric_key_reservation
            (numeric_key, key_kind, claim_kind)
);

-- These fourteen owner-local providers deliberately support only an explicit EMPTY state.
-- Each has its own persisted typed table; the receipt family-state ledger is not a provider.
CREATE TABLE entity_empty_source_other_actor_template_roots (
    target_namespace VARCHAR(63) NOT NULL,
    intake_request_id UUID NOT NULL,
    provider_schema_version SMALLINT NOT NULL,
    owner_state VARCHAR(16) NOT NULL,
    row_count BIGINT NOT NULL,
    reference_count BIGINT NOT NULL,
    authorization_binding_digest VARCHAR(71) NOT NULL,
    selected_source_digest VARCHAR(71) NOT NULL,
    source_revision_binding_digest VARCHAR(71) NOT NULL,
    world_closure_digest VARCHAR(71) NOT NULL,
    provider_state_digest VARCHAR(71) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_entity_empty_source_other_actor_template_roots PRIMARY KEY (target_namespace, intake_request_id),
    CONSTRAINT fk_entity_empty_source_other_actor_template_roots_association FOREIGN KEY (target_namespace, intake_request_id)
        REFERENCES entity_empty_selected_source_association (target_namespace, intake_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_entity_empty_source_other_actor_template_roots_schema CHECK (provider_schema_version = 1),
    CONSTRAINT ck_entity_empty_source_other_actor_template_roots_state CHECK (owner_state = 'EMPTY'),
    CONSTRAINT ck_entity_empty_source_other_actor_template_roots_counts CHECK (row_count = 0 AND reference_count = 0),
    CONSTRAINT ck_entity_empty_source_other_actor_template_roots_digests CHECK (
        authorization_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND selected_source_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_revision_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND world_closure_digest ~ '^sha256:[0-9a-f]{64}$'
        AND provider_state_digest ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE TABLE entity_empty_source_equipment_capabilities (
    target_namespace VARCHAR(63) NOT NULL,
    intake_request_id UUID NOT NULL,
    provider_schema_version SMALLINT NOT NULL,
    owner_state VARCHAR(16) NOT NULL,
    row_count BIGINT NOT NULL,
    reference_count BIGINT NOT NULL,
    authorization_binding_digest VARCHAR(71) NOT NULL,
    selected_source_digest VARCHAR(71) NOT NULL,
    source_revision_binding_digest VARCHAR(71) NOT NULL,
    world_closure_digest VARCHAR(71) NOT NULL,
    provider_state_digest VARCHAR(71) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_entity_empty_source_equipment_capabilities PRIMARY KEY (target_namespace, intake_request_id),
    CONSTRAINT fk_entity_empty_source_equipment_capabilities_association FOREIGN KEY (target_namespace, intake_request_id)
        REFERENCES entity_empty_selected_source_association (target_namespace, intake_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_entity_empty_source_equipment_capabilities_schema CHECK (provider_schema_version = 1),
    CONSTRAINT ck_entity_empty_source_equipment_capabilities_state CHECK (owner_state = 'EMPTY'),
    CONSTRAINT ck_entity_empty_source_equipment_capabilities_counts CHECK (row_count = 0 AND reference_count = 0),
    CONSTRAINT ck_entity_empty_source_equipment_capabilities_digests CHECK (
        authorization_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND selected_source_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_revision_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND world_closure_digest ~ '^sha256:[0-9a-f]{64}$'
        AND provider_state_digest ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE TABLE entity_empty_source_equipment_attachment_rules (
    target_namespace VARCHAR(63) NOT NULL,
    intake_request_id UUID NOT NULL,
    provider_schema_version SMALLINT NOT NULL,
    owner_state VARCHAR(16) NOT NULL,
    row_count BIGINT NOT NULL,
    reference_count BIGINT NOT NULL,
    authorization_binding_digest VARCHAR(71) NOT NULL,
    selected_source_digest VARCHAR(71) NOT NULL,
    source_revision_binding_digest VARCHAR(71) NOT NULL,
    world_closure_digest VARCHAR(71) NOT NULL,
    provider_state_digest VARCHAR(71) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_entity_empty_source_equipment_attachment_rules PRIMARY KEY (target_namespace, intake_request_id),
    CONSTRAINT fk_entity_empty_source_equipment_attachment_rules_association FOREIGN KEY (target_namespace, intake_request_id)
        REFERENCES entity_empty_selected_source_association (target_namespace, intake_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_entity_empty_source_equipment_attachment_rules_schema CHECK (provider_schema_version = 1),
    CONSTRAINT ck_entity_empty_source_equipment_attachment_rules_state CHECK (owner_state = 'EMPTY'),
    CONSTRAINT ck_entity_empty_source_equipment_attachment_rules_counts CHECK (row_count = 0 AND reference_count = 0),
    CONSTRAINT ck_entity_empty_source_equipment_attachment_rules_digests CHECK (
        authorization_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND selected_source_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_revision_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND world_closure_digest ~ '^sha256:[0-9a-f]{64}$'
        AND provider_state_digest ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE TABLE entity_empty_source_equipment_compatibility_rules (
    target_namespace VARCHAR(63) NOT NULL,
    intake_request_id UUID NOT NULL,
    provider_schema_version SMALLINT NOT NULL,
    owner_state VARCHAR(16) NOT NULL,
    row_count BIGINT NOT NULL,
    reference_count BIGINT NOT NULL,
    authorization_binding_digest VARCHAR(71) NOT NULL,
    selected_source_digest VARCHAR(71) NOT NULL,
    source_revision_binding_digest VARCHAR(71) NOT NULL,
    world_closure_digest VARCHAR(71) NOT NULL,
    provider_state_digest VARCHAR(71) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_entity_empty_source_equipment_compatibility_rules PRIMARY KEY (target_namespace, intake_request_id),
    CONSTRAINT fk_entity_empty_source_equipment_compatibility_rules_association FOREIGN KEY (target_namespace, intake_request_id)
        REFERENCES entity_empty_selected_source_association (target_namespace, intake_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_entity_empty_source_equipment_compatibility_rules_schema CHECK (provider_schema_version = 1),
    CONSTRAINT ck_entity_empty_source_equipment_compatibility_rules_state CHECK (owner_state = 'EMPTY'),
    CONSTRAINT ck_entity_empty_source_equipment_compatibility_rules_counts CHECK (row_count = 0 AND reference_count = 0),
    CONSTRAINT ck_entity_empty_source_equipment_compatibility_rules_digests CHECK (
        authorization_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND selected_source_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_revision_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND world_closure_digest ~ '^sha256:[0-9a-f]{64}$'
        AND provider_state_digest ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE TABLE entity_empty_source_equipment_occupancy_rules (
    target_namespace VARCHAR(63) NOT NULL,
    intake_request_id UUID NOT NULL,
    provider_schema_version SMALLINT NOT NULL,
    owner_state VARCHAR(16) NOT NULL,
    row_count BIGINT NOT NULL,
    reference_count BIGINT NOT NULL,
    authorization_binding_digest VARCHAR(71) NOT NULL,
    selected_source_digest VARCHAR(71) NOT NULL,
    source_revision_binding_digest VARCHAR(71) NOT NULL,
    world_closure_digest VARCHAR(71) NOT NULL,
    provider_state_digest VARCHAR(71) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_entity_empty_source_equipment_occupancy_rules PRIMARY KEY (target_namespace, intake_request_id),
    CONSTRAINT fk_entity_empty_source_equipment_occupancy_rules_association FOREIGN KEY (target_namespace, intake_request_id)
        REFERENCES entity_empty_selected_source_association (target_namespace, intake_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_entity_empty_source_equipment_occupancy_rules_schema CHECK (provider_schema_version = 1),
    CONSTRAINT ck_entity_empty_source_equipment_occupancy_rules_state CHECK (owner_state = 'EMPTY'),
    CONSTRAINT ck_entity_empty_source_equipment_occupancy_rules_counts CHECK (row_count = 0 AND reference_count = 0),
    CONSTRAINT ck_entity_empty_source_equipment_occupancy_rules_digests CHECK (
        authorization_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND selected_source_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_revision_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND world_closure_digest ~ '^sha256:[0-9a-f]{64}$'
        AND provider_state_digest ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE TABLE entity_empty_source_actor_body_layout_assignments (
    target_namespace VARCHAR(63) NOT NULL,
    intake_request_id UUID NOT NULL,
    provider_schema_version SMALLINT NOT NULL,
    owner_state VARCHAR(16) NOT NULL,
    row_count BIGINT NOT NULL,
    reference_count BIGINT NOT NULL,
    authorization_binding_digest VARCHAR(71) NOT NULL,
    selected_source_digest VARCHAR(71) NOT NULL,
    source_revision_binding_digest VARCHAR(71) NOT NULL,
    world_closure_digest VARCHAR(71) NOT NULL,
    provider_state_digest VARCHAR(71) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_entity_empty_source_actor_body_layout_assignments PRIMARY KEY (target_namespace, intake_request_id),
    CONSTRAINT fk_entity_empty_source_actor_body_layout_assignments_association FOREIGN KEY (target_namespace, intake_request_id)
        REFERENCES entity_empty_selected_source_association (target_namespace, intake_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_entity_empty_source_actor_body_layout_assignments_schema CHECK (provider_schema_version = 1),
    CONSTRAINT ck_entity_empty_source_actor_body_layout_assignments_state CHECK (owner_state = 'EMPTY'),
    CONSTRAINT ck_entity_empty_source_actor_body_layout_assignments_counts CHECK (row_count = 0 AND reference_count = 0),
    CONSTRAINT ck_entity_empty_source_actor_body_layout_assignments_digests CHECK (
        authorization_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND selected_source_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_revision_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND world_closure_digest ~ '^sha256:[0-9a-f]{64}$'
        AND provider_state_digest ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE TABLE entity_empty_source_loot_table_roots (
    target_namespace VARCHAR(63) NOT NULL,
    intake_request_id UUID NOT NULL,
    provider_schema_version SMALLINT NOT NULL,
    owner_state VARCHAR(16) NOT NULL,
    row_count BIGINT NOT NULL,
    reference_count BIGINT NOT NULL,
    authorization_binding_digest VARCHAR(71) NOT NULL,
    selected_source_digest VARCHAR(71) NOT NULL,
    source_revision_binding_digest VARCHAR(71) NOT NULL,
    world_closure_digest VARCHAR(71) NOT NULL,
    provider_state_digest VARCHAR(71) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_entity_empty_source_loot_table_roots PRIMARY KEY (target_namespace, intake_request_id),
    CONSTRAINT fk_entity_empty_source_loot_table_roots_association FOREIGN KEY (target_namespace, intake_request_id)
        REFERENCES entity_empty_selected_source_association (target_namespace, intake_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_entity_empty_source_loot_table_roots_schema CHECK (provider_schema_version = 1),
    CONSTRAINT ck_entity_empty_source_loot_table_roots_state CHECK (owner_state = 'EMPTY'),
    CONSTRAINT ck_entity_empty_source_loot_table_roots_counts CHECK (row_count = 0 AND reference_count = 0),
    CONSTRAINT ck_entity_empty_source_loot_table_roots_digests CHECK (
        authorization_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND selected_source_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_revision_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND world_closure_digest ~ '^sha256:[0-9a-f]{64}$'
        AND provider_state_digest ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE TABLE entity_empty_source_loot_item_mappings (
    target_namespace VARCHAR(63) NOT NULL,
    intake_request_id UUID NOT NULL,
    provider_schema_version SMALLINT NOT NULL,
    owner_state VARCHAR(16) NOT NULL,
    row_count BIGINT NOT NULL,
    reference_count BIGINT NOT NULL,
    authorization_binding_digest VARCHAR(71) NOT NULL,
    selected_source_digest VARCHAR(71) NOT NULL,
    source_revision_binding_digest VARCHAR(71) NOT NULL,
    world_closure_digest VARCHAR(71) NOT NULL,
    provider_state_digest VARCHAR(71) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_entity_empty_source_loot_item_mappings PRIMARY KEY (target_namespace, intake_request_id),
    CONSTRAINT fk_entity_empty_source_loot_item_mappings_association FOREIGN KEY (target_namespace, intake_request_id)
        REFERENCES entity_empty_selected_source_association (target_namespace, intake_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_entity_empty_source_loot_item_mappings_schema CHECK (provider_schema_version = 1),
    CONSTRAINT ck_entity_empty_source_loot_item_mappings_state CHECK (owner_state = 'EMPTY'),
    CONSTRAINT ck_entity_empty_source_loot_item_mappings_counts CHECK (row_count = 0 AND reference_count = 0),
    CONSTRAINT ck_entity_empty_source_loot_item_mappings_digests CHECK (
        authorization_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND selected_source_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_revision_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND world_closure_digest ~ '^sha256:[0-9a-f]{64}$'
        AND provider_state_digest ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE TABLE entity_empty_source_inbound_loot_bindings (
    target_namespace VARCHAR(63) NOT NULL,
    intake_request_id UUID NOT NULL,
    provider_schema_version SMALLINT NOT NULL,
    owner_state VARCHAR(16) NOT NULL,
    row_count BIGINT NOT NULL,
    reference_count BIGINT NOT NULL,
    authorization_binding_digest VARCHAR(71) NOT NULL,
    selected_source_digest VARCHAR(71) NOT NULL,
    source_revision_binding_digest VARCHAR(71) NOT NULL,
    world_closure_digest VARCHAR(71) NOT NULL,
    provider_state_digest VARCHAR(71) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_entity_empty_source_inbound_loot_bindings PRIMARY KEY (target_namespace, intake_request_id),
    CONSTRAINT fk_entity_empty_source_inbound_loot_bindings_association FOREIGN KEY (target_namespace, intake_request_id)
        REFERENCES entity_empty_selected_source_association (target_namespace, intake_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_entity_empty_source_inbound_loot_bindings_schema CHECK (provider_schema_version = 1),
    CONSTRAINT ck_entity_empty_source_inbound_loot_bindings_state CHECK (owner_state = 'EMPTY'),
    CONSTRAINT ck_entity_empty_source_inbound_loot_bindings_counts CHECK (row_count = 0 AND reference_count = 0),
    CONSTRAINT ck_entity_empty_source_inbound_loot_bindings_digests CHECK (
        authorization_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND selected_source_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_revision_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND world_closure_digest ~ '^sha256:[0-9a-f]{64}$'
        AND provider_state_digest ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE TABLE entity_empty_source_balance_curve_roots (
    target_namespace VARCHAR(63) NOT NULL,
    intake_request_id UUID NOT NULL,
    provider_schema_version SMALLINT NOT NULL,
    owner_state VARCHAR(16) NOT NULL,
    row_count BIGINT NOT NULL,
    reference_count BIGINT NOT NULL,
    authorization_binding_digest VARCHAR(71) NOT NULL,
    selected_source_digest VARCHAR(71) NOT NULL,
    source_revision_binding_digest VARCHAR(71) NOT NULL,
    world_closure_digest VARCHAR(71) NOT NULL,
    provider_state_digest VARCHAR(71) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_entity_empty_source_balance_curve_roots PRIMARY KEY (target_namespace, intake_request_id),
    CONSTRAINT fk_entity_empty_source_balance_curve_roots_association FOREIGN KEY (target_namespace, intake_request_id)
        REFERENCES entity_empty_selected_source_association (target_namespace, intake_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_entity_empty_source_balance_curve_roots_schema CHECK (provider_schema_version = 1),
    CONSTRAINT ck_entity_empty_source_balance_curve_roots_state CHECK (owner_state = 'EMPTY'),
    CONSTRAINT ck_entity_empty_source_balance_curve_roots_counts CHECK (row_count = 0 AND reference_count = 0),
    CONSTRAINT ck_entity_empty_source_balance_curve_roots_digests CHECK (
        authorization_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND selected_source_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_revision_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND world_closure_digest ~ '^sha256:[0-9a-f]{64}$'
        AND provider_state_digest ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE TABLE entity_empty_source_balance_curve_attachments (
    target_namespace VARCHAR(63) NOT NULL,
    intake_request_id UUID NOT NULL,
    provider_schema_version SMALLINT NOT NULL,
    owner_state VARCHAR(16) NOT NULL,
    row_count BIGINT NOT NULL,
    reference_count BIGINT NOT NULL,
    authorization_binding_digest VARCHAR(71) NOT NULL,
    selected_source_digest VARCHAR(71) NOT NULL,
    source_revision_binding_digest VARCHAR(71) NOT NULL,
    world_closure_digest VARCHAR(71) NOT NULL,
    provider_state_digest VARCHAR(71) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_entity_empty_source_balance_curve_attachments PRIMARY KEY (target_namespace, intake_request_id),
    CONSTRAINT fk_entity_empty_source_balance_curve_attachments_association FOREIGN KEY (target_namespace, intake_request_id)
        REFERENCES entity_empty_selected_source_association (target_namespace, intake_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_entity_empty_source_balance_curve_attachments_schema CHECK (provider_schema_version = 1),
    CONSTRAINT ck_entity_empty_source_balance_curve_attachments_state CHECK (owner_state = 'EMPTY'),
    CONSTRAINT ck_entity_empty_source_balance_curve_attachments_counts CHECK (row_count = 0 AND reference_count = 0),
    CONSTRAINT ck_entity_empty_source_balance_curve_attachments_digests CHECK (
        authorization_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND selected_source_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_revision_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND world_closure_digest ~ '^sha256:[0-9a-f]{64}$'
        AND provider_state_digest ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE TABLE entity_empty_source_archetype_roots (
    target_namespace VARCHAR(63) NOT NULL,
    intake_request_id UUID NOT NULL,
    provider_schema_version SMALLINT NOT NULL,
    owner_state VARCHAR(16) NOT NULL,
    row_count BIGINT NOT NULL,
    reference_count BIGINT NOT NULL,
    authorization_binding_digest VARCHAR(71) NOT NULL,
    selected_source_digest VARCHAR(71) NOT NULL,
    source_revision_binding_digest VARCHAR(71) NOT NULL,
    world_closure_digest VARCHAR(71) NOT NULL,
    provider_state_digest VARCHAR(71) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_entity_empty_source_archetype_roots PRIMARY KEY (target_namespace, intake_request_id),
    CONSTRAINT fk_entity_empty_source_archetype_roots_association FOREIGN KEY (target_namespace, intake_request_id)
        REFERENCES entity_empty_selected_source_association (target_namespace, intake_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_entity_empty_source_archetype_roots_schema CHECK (provider_schema_version = 1),
    CONSTRAINT ck_entity_empty_source_archetype_roots_state CHECK (owner_state = 'EMPTY'),
    CONSTRAINT ck_entity_empty_source_archetype_roots_counts CHECK (row_count = 0 AND reference_count = 0),
    CONSTRAINT ck_entity_empty_source_archetype_roots_digests CHECK (
        authorization_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND selected_source_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_revision_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND world_closure_digest ~ '^sha256:[0-9a-f]{64}$'
        AND provider_state_digest ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE TABLE entity_empty_source_archetype_constraints (
    target_namespace VARCHAR(63) NOT NULL,
    intake_request_id UUID NOT NULL,
    provider_schema_version SMALLINT NOT NULL,
    owner_state VARCHAR(16) NOT NULL,
    row_count BIGINT NOT NULL,
    reference_count BIGINT NOT NULL,
    authorization_binding_digest VARCHAR(71) NOT NULL,
    selected_source_digest VARCHAR(71) NOT NULL,
    source_revision_binding_digest VARCHAR(71) NOT NULL,
    world_closure_digest VARCHAR(71) NOT NULL,
    provider_state_digest VARCHAR(71) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_entity_empty_source_archetype_constraints PRIMARY KEY (target_namespace, intake_request_id),
    CONSTRAINT fk_entity_empty_source_archetype_constraints_association FOREIGN KEY (target_namespace, intake_request_id)
        REFERENCES entity_empty_selected_source_association (target_namespace, intake_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_entity_empty_source_archetype_constraints_schema CHECK (provider_schema_version = 1),
    CONSTRAINT ck_entity_empty_source_archetype_constraints_state CHECK (owner_state = 'EMPTY'),
    CONSTRAINT ck_entity_empty_source_archetype_constraints_counts CHECK (row_count = 0 AND reference_count = 0),
    CONSTRAINT ck_entity_empty_source_archetype_constraints_digests CHECK (
        authorization_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND selected_source_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_revision_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND world_closure_digest ~ '^sha256:[0-9a-f]{64}$'
        AND provider_state_digest ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE TABLE entity_empty_source_archetype_assignments (
    target_namespace VARCHAR(63) NOT NULL,
    intake_request_id UUID NOT NULL,
    provider_schema_version SMALLINT NOT NULL,
    owner_state VARCHAR(16) NOT NULL,
    row_count BIGINT NOT NULL,
    reference_count BIGINT NOT NULL,
    authorization_binding_digest VARCHAR(71) NOT NULL,
    selected_source_digest VARCHAR(71) NOT NULL,
    source_revision_binding_digest VARCHAR(71) NOT NULL,
    world_closure_digest VARCHAR(71) NOT NULL,
    provider_state_digest VARCHAR(71) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_entity_empty_source_archetype_assignments PRIMARY KEY (target_namespace, intake_request_id),
    CONSTRAINT fk_entity_empty_source_archetype_assignments_association FOREIGN KEY (target_namespace, intake_request_id)
        REFERENCES entity_empty_selected_source_association (target_namespace, intake_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_entity_empty_source_archetype_assignments_schema CHECK (provider_schema_version = 1),
    CONSTRAINT ck_entity_empty_source_archetype_assignments_state CHECK (owner_state = 'EMPTY'),
    CONSTRAINT ck_entity_empty_source_archetype_assignments_counts CHECK (row_count = 0 AND reference_count = 0),
    CONSTRAINT ck_entity_empty_source_archetype_assignments_digests CHECK (
        authorization_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND selected_source_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_revision_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND world_closure_digest ~ '^sha256:[0-9a-f]{64}$'
        AND provider_state_digest ~ '^sha256:[0-9a-f]{64}$'
    )
);
CREATE TABLE entity_empty_selected_source_family_state (
    target_namespace VARCHAR(63) NOT NULL,
    intake_request_id UUID NOT NULL,
    family_name VARCHAR(64) NOT NULL,
    owner_state VARCHAR(16) NOT NULL,
    evidence_kind VARCHAR(32) NOT NULL,
    row_count BIGINT NOT NULL,
    unqualified_row_count BIGINT NOT NULL,
    retained_row_count BIGINT NOT NULL,
    selected_scope_row_count BIGINT NOT NULL,
    reference_count BIGINT NOT NULL,
    CONSTRAINT pk_entity_empty_selected_source_family_state
        PRIMARY KEY (target_namespace, intake_request_id, family_name),
    CONSTRAINT fk_entity_empty_selected_source_family_association
        FOREIGN KEY (target_namespace, intake_request_id)
        REFERENCES entity_empty_selected_source_association (target_namespace, intake_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_entity_empty_selected_source_family_name
        CHECK (family_name IN (
            'ACTOR_BODY_LAYOUT_ASSIGNMENTS',
            'ARCHETYPE_ASSIGNMENTS',
            'ARCHETYPE_CONSTRAINTS',
            'ARCHETYPE_ROOTS',
            'BALANCE_CURVE_ATTACHMENTS',
            'BALANCE_CURVE_ROOTS',
            'BODY_LAYOUT_MEMBERSHIPS',
            'BODY_LAYOUT_ROOTS',
            'CRAFTING_INGREDIENT_BINDINGS',
            'CRAFTING_RECIPE_RESULT_BINDINGS',
            'CRAFTING_RECIPE_ROOTS',
            'EQUIPMENT_ATTACHMENT_RULES',
            'EQUIPMENT_CAPABILITIES',
            'EQUIPMENT_COMPATIBILITY_RULES',
            'EQUIPMENT_OCCUPANCY_RULES',
            'EQUIPMENT_SLOT_GROUPS',
            'EQUIPMENT_SLOT_ROOTS',
            'INBOUND_LOOT_BINDINGS',
            'ITEM_TEMPLATE_ROOTS',
            'LOOT_ITEM_MAPPINGS',
            'LOOT_TABLE_ROOTS',
            'NPC_TEMPLATE_ROOTS',
            'OTHER_ACTOR_TEMPLATE_ROOTS'
        )),
    CONSTRAINT ck_entity_empty_selected_source_family_state
        CHECK (owner_state = 'EMPTY'),
    CONSTRAINT ck_entity_empty_selected_source_family_evidence_kind
        CHECK (
            (family_name IN (
                'ITEM_TEMPLATE_ROOTS', 'NPC_TEMPLATE_ROOTS', 'CRAFTING_RECIPE_ROOTS',
                'CRAFTING_RECIPE_RESULT_BINDINGS', 'CRAFTING_INGREDIENT_BINDINGS',
                'EQUIPMENT_SLOT_ROOTS', 'EQUIPMENT_SLOT_GROUPS', 'BODY_LAYOUT_ROOTS',
                'BODY_LAYOUT_MEMBERSHIPS'
            ) AND evidence_kind = 'V1_SOURCE_CENSUS')
            OR (family_name IN (
                'ACTOR_BODY_LAYOUT_ASSIGNMENTS', 'ARCHETYPE_ASSIGNMENTS',
                'ARCHETYPE_CONSTRAINTS', 'ARCHETYPE_ROOTS', 'BALANCE_CURVE_ATTACHMENTS',
                'BALANCE_CURVE_ROOTS', 'EQUIPMENT_ATTACHMENT_RULES',
                'EQUIPMENT_CAPABILITIES', 'EQUIPMENT_COMPATIBILITY_RULES',
                'EQUIPMENT_OCCUPANCY_RULES', 'INBOUND_LOOT_BINDINGS', 'LOOT_ITEM_MAPPINGS',
                'LOOT_TABLE_ROOTS', 'OTHER_ACTOR_TEMPLATE_ROOTS'
            ) AND evidence_kind = 'EMPTY_ONLY_OWNER_PROVIDER')
        ),
    CONSTRAINT ck_entity_empty_selected_source_family_counts
        CHECK (
            row_count = 0
            AND unqualified_row_count = 0
            AND retained_row_count = 0
            AND selected_scope_row_count = 0
            AND reference_count = 0
        )
);

CREATE TABLE entity_empty_selected_source_receipt (
    target_namespace VARCHAR(63) NOT NULL,
    intake_request_id UUID NOT NULL,
    request_digest VARCHAR(71) NOT NULL,
    schema_digest VARCHAR(71) NOT NULL,
    receipt_digest VARCHAR(71) NOT NULL,
    receipt_bytes BYTEA NOT NULL,
    retained_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_entity_empty_selected_source_receipt
        PRIMARY KEY (target_namespace, intake_request_id),
    CONSTRAINT fk_entity_empty_selected_source_receipt_association
        FOREIGN KEY (target_namespace, intake_request_id)
        REFERENCES entity_empty_selected_source_association (target_namespace, intake_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_entity_empty_selected_source_receipt_digests
        CHECK (
            request_digest ~ '^sha256:[0-9a-f]{64}$'
            AND schema_digest ~ '^sha256:[0-9a-f]{64}$'
            AND receipt_digest ~ '^sha256:[0-9a-f]{64}$'
            AND octet_length(receipt_bytes) BETWEEN 1 AND 83886080
        )
);

CREATE INDEX idx_entity_empty_source_key_claim_kind
    ON entity_empty_source_numeric_key_reservation (claim_kind, key_kind, numeric_key);

/* [jooq ignore start] */
CREATE FUNCTION entity_source_positive_numeric_key(value_text TEXT)
RETURNS BIGINT
LANGUAGE plpgsql
IMMUTABLE
STRICT
SET search_path = pg_catalog
AS $$
DECLARE
    parsed_value NUMERIC;
BEGIN
    IF value_text !~ '^[0-9]+$' THEN
        RETURN NULL;
    END IF;
    parsed_value := value_text::NUMERIC;
    IF parsed_value <= 0 OR parsed_value > 9223372036854775807 THEN
        RETURN NULL;
    END IF;
    RETURN parsed_value::BIGINT;
END;
$$;
/* [jooq ignore stop] */

-- Lock the receipt/key tables first and then every V1 tenant/version holder and source/reference
-- relation. This both seeds real occupied keys and closes insert/update phantoms for the same
-- fixed order used by the owner-local founding transaction.
/* [jooq ignore start] */
LOCK TABLE entity_empty_source_numeric_key_reservation,
    entity_empty_selected_source_association,
    entity_empty_selected_source_family_state,
    entity_empty_selected_source_receipt,
    entity_empty_source_other_actor_template_roots,
    entity_empty_source_equipment_capabilities,
    entity_empty_source_equipment_attachment_rules,
    entity_empty_source_equipment_compatibility_rules,
    entity_empty_source_equipment_occupancy_rules,
    entity_empty_source_actor_body_layout_assignments,
    entity_empty_source_loot_table_roots,
    entity_empty_source_loot_item_mappings,
    entity_empty_source_inbound_loot_bindings,
    entity_empty_source_balance_curve_roots,
    entity_empty_source_balance_curve_attachments,
    entity_empty_source_archetype_roots,
    entity_empty_source_archetype_constraints,
    entity_empty_source_archetype_assignments
    IN SHARE ROW EXCLUSIVE MODE;
/* [jooq ignore stop] */
/* [jooq ignore start] */
LOCK TABLE actor_active_conditions, actor_resource_states, body_layout_slot_definitions,
    character_equipment, character_friend, characters, container_instances,
    crafting_ingredients, crafting_recipes, entity_mutation_effects,
    equipment_slot_definitions, inventory, item_instances, item_stacks,
    item_transfer_audits, item_visible_ref_counters, items, npcs, room_ground_inventory
    IN SHARE MODE;
/* [jooq ignore stop] */

/* [jooq ignore start] */
INSERT INTO entity_empty_source_numeric_key_reservation (key_kind, numeric_key, claim_kind)
SELECT DISTINCT key_kind, numeric_key, 'LEGACY_NUMERIC'
FROM (
    SELECT 'TENANT'::VARCHAR(16) AS key_kind, tenant_id AS numeric_key FROM characters
    UNION ALL SELECT 'TENANT', tenant_id FROM npcs
    UNION ALL SELECT 'TENANT', tenant_id FROM items
    UNION ALL SELECT 'TENANT', tenant_id FROM crafting_recipes
    UNION ALL SELECT 'TENANT', tenant_id FROM character_friend
    UNION ALL SELECT 'TENANT', tenant_id FROM room_ground_inventory
    UNION ALL SELECT 'TENANT', tenant_id FROM container_instances
    UNION ALL SELECT 'TENANT', tenant_id FROM item_instances
    UNION ALL SELECT 'TENANT', tenant_id FROM item_visible_ref_counters
    UNION ALL SELECT 'TENANT', tenant_id FROM item_stacks
    UNION ALL SELECT 'TENANT', tenant_id FROM entity_mutation_effects
    UNION ALL SELECT 'TENANT', tenant_id FROM item_transfer_audits
    UNION ALL SELECT 'TENANT', tenant_id FROM equipment_slot_definitions
    UNION ALL SELECT 'TENANT', tenant_id FROM body_layout_slot_definitions
    UNION ALL SELECT 'TENANT', tenant_id FROM actor_resource_states
    UNION ALL SELECT 'TENANT', tenant_id FROM actor_active_conditions
    UNION ALL SELECT 'VERSION', version_id FROM items
    UNION ALL SELECT 'VERSION', version_id FROM npcs
    UNION ALL SELECT 'VERSION', version_id FROM crafting_recipes
    UNION ALL SELECT 'VERSION', version_id FROM equipment_slot_definitions
    UNION ALL SELECT 'VERSION', version_id FROM body_layout_slot_definitions
) AS existing_entity_keys
WHERE numeric_key > 0
ON CONFLICT (key_kind, numeric_key) DO NOTHING;
/* [jooq ignore stop] */

/* [jooq ignore start] */
DO $entity_empty_source_functions$
DECLARE
    owner_schema TEXT := current_schema();
BEGIN
    IF owner_schema IS NULL THEN
        RAISE EXCEPTION 'Entity empty-source functions require an explicit installation schema';
    END IF;

    EXECUTE format($function$
        CREATE FUNCTION %I.entity_require_source_numeric_key_unreserved(
            requested_kind TEXT, requested_key BIGINT
        ) RETURNS VOID
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, %I
        AS $body$
        DECLARE
            retained_claim TEXT;
        BEGIN
            IF requested_key IS NULL OR requested_key <= 0 THEN
                RETURN;
            END IF;
            SELECT claim_kind INTO retained_claim
            FROM %I.entity_empty_source_numeric_key_reservation
            WHERE key_kind = requested_kind AND numeric_key = requested_key;
            IF retained_claim = 'CANONICAL_EMPTY_SOURCE' THEN
                RAISE EXCEPTION 'Entity numeric source key is reserved by an immutable empty-source receipt';
            END IF;
        END;
        $body$
    $function$, owner_schema, owner_schema, owner_schema);

    EXECUTE format($function$
        CREATE FUNCTION %I.entity_guard_source_owner_row()
        RETURNS TRIGGER
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, %I
        AS $body$
        DECLARE
            old_value JSONB;
            new_value JSONB;
            old_tenant_key BIGINT;
            new_tenant_key BIGINT;
            old_version_key BIGINT;
            new_version_key BIGINT;
        BEGIN
            IF TG_OP IN ('UPDATE', 'DELETE') THEN
                old_value := to_jsonb(OLD);
                old_tenant_key := %I.entity_source_positive_numeric_key(old_value->>'tenant_id');
                old_version_key := %I.entity_source_positive_numeric_key(old_value->>'version_id');
                PERFORM %I.entity_require_source_numeric_key_unreserved('TENANT', old_tenant_key);
                PERFORM %I.entity_require_source_numeric_key_unreserved('VERSION', old_version_key);
            END IF;
            IF TG_OP IN ('INSERT', 'UPDATE') THEN
                new_value := to_jsonb(NEW);
                new_tenant_key := %I.entity_source_positive_numeric_key(new_value->>'tenant_id');
                new_version_key := %I.entity_source_positive_numeric_key(new_value->>'version_id');
                PERFORM %I.entity_require_source_numeric_key_unreserved('TENANT', new_tenant_key);
                PERFORM %I.entity_require_source_numeric_key_unreserved('VERSION', new_version_key);
            END IF;
            IF TG_OP = 'DELETE' THEN
                RETURN OLD;
            END IF;
            RETURN NEW;
        END;
        $body$
    $function$, owner_schema, owner_schema, owner_schema, owner_schema,
        owner_schema, owner_schema, owner_schema, owner_schema, owner_schema, owner_schema);

    EXECUTE format($function$
        CREATE FUNCTION %I.entity_guard_character_template_reference()
        RETURNS TRIGGER
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, %I
        AS $body$
        DECLARE
            old_character_id BIGINT;
            new_character_id BIGINT;
            referenced_tenant BIGINT;
        BEGIN
            IF TG_OP IN ('UPDATE', 'DELETE') THEN
                old_character_id := (to_jsonb(OLD)->>'character_id')::BIGINT;
                SELECT tenant_id INTO referenced_tenant FROM %I.characters WHERE id = old_character_id;
                IF NOT FOUND THEN
                    RAISE EXCEPTION 'Entity character reference owner is unavailable';
                END IF;
                PERFORM %I.entity_require_source_numeric_key_unreserved('TENANT', referenced_tenant);
            END IF;
            IF TG_OP IN ('INSERT', 'UPDATE') THEN
                new_character_id := (to_jsonb(NEW)->>'character_id')::BIGINT;
                SELECT tenant_id INTO referenced_tenant FROM %I.characters WHERE id = new_character_id;
                IF NOT FOUND THEN
                    RAISE EXCEPTION 'Entity character reference owner is unavailable';
                END IF;
                PERFORM %I.entity_require_source_numeric_key_unreserved('TENANT', referenced_tenant);
            END IF;
            IF TG_OP = 'DELETE' THEN
                RETURN OLD;
            END IF;
            RETURN NEW;
        END;
        $body$
    $function$, owner_schema, owner_schema, owner_schema, owner_schema, owner_schema, owner_schema);

    EXECUTE format($function$
        CREATE FUNCTION %I.entity_guard_recipe_ingredient_reference()
        RETURNS TRIGGER
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, %I
        AS $body$
        DECLARE
            old_recipe_id BIGINT;
            new_recipe_id BIGINT;
            referenced_tenant BIGINT;
            referenced_version BIGINT;
        BEGIN
            IF TG_OP IN ('UPDATE', 'DELETE') THEN
                old_recipe_id := (to_jsonb(OLD)->>'recipe_id')::BIGINT;
                SELECT tenant_id, version_id INTO referenced_tenant, referenced_version
                FROM %I.crafting_recipes WHERE id = old_recipe_id;
                IF NOT FOUND THEN
                    RAISE EXCEPTION 'Entity crafting recipe reference owner is unavailable';
                END IF;
                PERFORM %I.entity_require_source_numeric_key_unreserved('TENANT', referenced_tenant);
                PERFORM %I.entity_require_source_numeric_key_unreserved('VERSION', referenced_version);
            END IF;
            IF TG_OP IN ('INSERT', 'UPDATE') THEN
                new_recipe_id := (to_jsonb(NEW)->>'recipe_id')::BIGINT;
                SELECT tenant_id, version_id INTO referenced_tenant, referenced_version
                FROM %I.crafting_recipes WHERE id = new_recipe_id;
                IF NOT FOUND THEN
                    RAISE EXCEPTION 'Entity crafting recipe reference owner is unavailable';
                END IF;
                PERFORM %I.entity_require_source_numeric_key_unreserved('TENANT', referenced_tenant);
                PERFORM %I.entity_require_source_numeric_key_unreserved('VERSION', referenced_version);
            END IF;
            IF TG_OP = 'DELETE' THEN
                RETURN OLD;
            END IF;
            RETURN NEW;
        END;
        $body$
    $function$, owner_schema, owner_schema, owner_schema, owner_schema, owner_schema,
        owner_schema, owner_schema, owner_schema);

    EXECUTE format($function$
        CREATE FUNCTION %I.entity_reject_source_truncate()
        RETURNS TRIGGER
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, %I
        AS $body$
        BEGIN
            IF EXISTS (
                SELECT 1 FROM %I.entity_empty_source_numeric_key_reservation
                WHERE claim_kind = 'CANONICAL_EMPTY_SOURCE'
            ) THEN
                RAISE EXCEPTION 'Entity authored-source TRUNCATE is forbidden after canonical intake';
            END IF;
            RETURN NULL;
        END;
        $body$
    $function$, owner_schema, owner_schema, owner_schema);

    EXECUTE format($function$
        CREATE FUNCTION %I.entity_reject_empty_source_record_mutation()
        RETURNS TRIGGER
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, %I
        AS $body$
        BEGIN
            RAISE EXCEPTION 'Entity empty-source intake records are immutable';
        END;
        $body$
    $function$, owner_schema, owner_schema);
END;
$entity_empty_source_functions$;
/* [jooq ignore stop] */

/* [jooq ignore start] */
REVOKE ALL ON FUNCTION entity_source_positive_numeric_key(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION entity_require_source_numeric_key_unreserved(TEXT, BIGINT) FROM PUBLIC;
REVOKE ALL ON FUNCTION entity_guard_source_owner_row() FROM PUBLIC;
REVOKE ALL ON FUNCTION entity_guard_character_template_reference() FROM PUBLIC;
REVOKE ALL ON FUNCTION entity_guard_recipe_ingredient_reference() FROM PUBLIC;
REVOKE ALL ON FUNCTION entity_reject_source_truncate() FROM PUBLIC;
REVOKE ALL ON FUNCTION entity_reject_empty_source_record_mutation() FROM PUBLIC;

CREATE TRIGGER trg_entity_items_source_reservation
    BEFORE INSERT OR UPDATE OR DELETE ON items
    FOR EACH ROW EXECUTE FUNCTION entity_guard_source_owner_row();
CREATE TRIGGER trg_entity_npcs_source_reservation
    BEFORE INSERT OR UPDATE OR DELETE ON npcs
    FOR EACH ROW EXECUTE FUNCTION entity_guard_source_owner_row();
CREATE TRIGGER trg_entity_recipes_source_reservation
    BEFORE INSERT OR UPDATE OR DELETE ON crafting_recipes
    FOR EACH ROW EXECUTE FUNCTION entity_guard_source_owner_row();
CREATE TRIGGER trg_entity_equipment_slots_source_reservation
    BEFORE INSERT OR UPDATE OR DELETE ON equipment_slot_definitions
    FOR EACH ROW EXECUTE FUNCTION entity_guard_source_owner_row();
CREATE TRIGGER trg_entity_body_layouts_source_reservation
    BEFORE INSERT OR UPDATE OR DELETE ON body_layout_slot_definitions
    FOR EACH ROW EXECUTE FUNCTION entity_guard_source_owner_row();

CREATE TRIGGER trg_entity_character_source_reservation
    BEFORE INSERT OR UPDATE OR DELETE ON characters
    FOR EACH ROW EXECUTE FUNCTION entity_guard_source_owner_row();
CREATE TRIGGER trg_entity_character_friend_source_reservation
    BEFORE INSERT OR UPDATE OR DELETE ON character_friend
    FOR EACH ROW EXECUTE FUNCTION entity_guard_source_owner_row();
CREATE TRIGGER trg_entity_room_ground_source_reservation
    BEFORE INSERT OR UPDATE OR DELETE ON room_ground_inventory
    FOR EACH ROW EXECUTE FUNCTION entity_guard_source_owner_row();
CREATE TRIGGER trg_entity_container_source_reservation
    BEFORE INSERT OR UPDATE OR DELETE ON container_instances
    FOR EACH ROW EXECUTE FUNCTION entity_guard_source_owner_row();
CREATE TRIGGER trg_entity_item_instance_source_reservation
    BEFORE INSERT OR UPDATE OR DELETE ON item_instances
    FOR EACH ROW EXECUTE FUNCTION entity_guard_source_owner_row();
CREATE TRIGGER trg_entity_item_stack_source_reservation
    BEFORE INSERT OR UPDATE OR DELETE ON item_stacks
    FOR EACH ROW EXECUTE FUNCTION entity_guard_source_owner_row();
CREATE TRIGGER trg_entity_item_audit_source_reservation
    BEFORE INSERT OR UPDATE OR DELETE ON item_transfer_audits
    FOR EACH ROW EXECUTE FUNCTION entity_guard_source_owner_row();
CREATE TRIGGER trg_entity_item_reference_counter_source_reservation
    BEFORE INSERT OR UPDATE OR DELETE ON item_visible_ref_counters
    FOR EACH ROW EXECUTE FUNCTION entity_guard_source_owner_row();
CREATE TRIGGER trg_entity_mutation_effect_source_reservation
    BEFORE INSERT OR UPDATE OR DELETE ON entity_mutation_effects
    FOR EACH ROW EXECUTE FUNCTION entity_guard_source_owner_row();
CREATE TRIGGER trg_entity_actor_resource_source_reservation
    BEFORE INSERT OR UPDATE OR DELETE ON actor_resource_states
    FOR EACH ROW EXECUTE FUNCTION entity_guard_source_owner_row();
CREATE TRIGGER trg_entity_actor_condition_source_reservation
    BEFORE INSERT OR UPDATE OR DELETE ON actor_active_conditions
    FOR EACH ROW EXECUTE FUNCTION entity_guard_source_owner_row();

CREATE TRIGGER trg_entity_inventory_character_source_reservation
    BEFORE INSERT OR UPDATE OR DELETE ON inventory
    FOR EACH ROW EXECUTE FUNCTION entity_guard_character_template_reference();
CREATE TRIGGER trg_entity_character_equipment_source_reservation
    BEFORE INSERT OR UPDATE OR DELETE ON character_equipment
    FOR EACH ROW EXECUTE FUNCTION entity_guard_character_template_reference();
CREATE TRIGGER trg_entity_crafting_ingredient_source_reservation
    BEFORE INSERT OR UPDATE OR DELETE ON crafting_ingredients
    FOR EACH ROW EXECUTE FUNCTION entity_guard_recipe_ingredient_reference();

CREATE TRIGGER trg_entity_items_source_no_truncate
    BEFORE TRUNCATE ON items
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_source_truncate();
CREATE TRIGGER trg_entity_npcs_source_no_truncate
    BEFORE TRUNCATE ON npcs
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_source_truncate();
CREATE TRIGGER trg_entity_recipes_source_no_truncate
    BEFORE TRUNCATE ON crafting_recipes
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_source_truncate();
CREATE TRIGGER trg_entity_ingredients_source_no_truncate
    BEFORE TRUNCATE ON crafting_ingredients
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_source_truncate();
CREATE TRIGGER trg_entity_equipment_slots_source_no_truncate
    BEFORE TRUNCATE ON equipment_slot_definitions
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_source_truncate();
CREATE TRIGGER trg_entity_body_layouts_source_no_truncate
    BEFORE TRUNCATE ON body_layout_slot_definitions
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_source_truncate();

CREATE TRIGGER trg_entity_empty_source_key_reservation_immutable
    BEFORE UPDATE OR DELETE ON entity_empty_source_numeric_key_reservation
    FOR EACH ROW EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_key_reservation_no_truncate
    BEFORE TRUNCATE ON entity_empty_source_numeric_key_reservation
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_association_immutable
    BEFORE UPDATE OR DELETE ON entity_empty_selected_source_association
    FOR EACH ROW EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_association_no_truncate
    BEFORE TRUNCATE ON entity_empty_selected_source_association
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_family_state_immutable
    BEFORE UPDATE OR DELETE ON entity_empty_selected_source_family_state
    FOR EACH ROW EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_family_state_no_truncate
    BEFORE TRUNCATE ON entity_empty_selected_source_family_state
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_receipt_immutable
    BEFORE UPDATE OR DELETE ON entity_empty_selected_source_receipt
    FOR EACH ROW EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_receipt_no_truncate
    BEFORE TRUNCATE ON entity_empty_selected_source_receipt
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_other_actor_template_roots_immutable
    BEFORE UPDATE OR DELETE ON entity_empty_source_other_actor_template_roots
    FOR EACH ROW EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_other_actor_template_roots_no_truncate
    BEFORE TRUNCATE ON entity_empty_source_other_actor_template_roots
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_equipment_capabilities_immutable
    BEFORE UPDATE OR DELETE ON entity_empty_source_equipment_capabilities
    FOR EACH ROW EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_equipment_capabilities_no_truncate
    BEFORE TRUNCATE ON entity_empty_source_equipment_capabilities
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_equipment_attachment_rules_immutable
    BEFORE UPDATE OR DELETE ON entity_empty_source_equipment_attachment_rules
    FOR EACH ROW EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_equipment_attachment_rules_no_truncate
    BEFORE TRUNCATE ON entity_empty_source_equipment_attachment_rules
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_equipment_compatibility_rules_immutable
    BEFORE UPDATE OR DELETE ON entity_empty_source_equipment_compatibility_rules
    FOR EACH ROW EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_equipment_compatibility_rules_no_truncate
    BEFORE TRUNCATE ON entity_empty_source_equipment_compatibility_rules
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_equipment_occupancy_rules_immutable
    BEFORE UPDATE OR DELETE ON entity_empty_source_equipment_occupancy_rules
    FOR EACH ROW EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_equipment_occupancy_rules_no_truncate
    BEFORE TRUNCATE ON entity_empty_source_equipment_occupancy_rules
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_actor_body_layout_assignments_immutable
    BEFORE UPDATE OR DELETE ON entity_empty_source_actor_body_layout_assignments
    FOR EACH ROW EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_actor_body_layout_assignments_no_truncate
    BEFORE TRUNCATE ON entity_empty_source_actor_body_layout_assignments
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_loot_table_roots_immutable
    BEFORE UPDATE OR DELETE ON entity_empty_source_loot_table_roots
    FOR EACH ROW EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_loot_table_roots_no_truncate
    BEFORE TRUNCATE ON entity_empty_source_loot_table_roots
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_loot_item_mappings_immutable
    BEFORE UPDATE OR DELETE ON entity_empty_source_loot_item_mappings
    FOR EACH ROW EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_loot_item_mappings_no_truncate
    BEFORE TRUNCATE ON entity_empty_source_loot_item_mappings
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_inbound_loot_bindings_immutable
    BEFORE UPDATE OR DELETE ON entity_empty_source_inbound_loot_bindings
    FOR EACH ROW EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_inbound_loot_bindings_no_truncate
    BEFORE TRUNCATE ON entity_empty_source_inbound_loot_bindings
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_balance_curve_roots_immutable
    BEFORE UPDATE OR DELETE ON entity_empty_source_balance_curve_roots
    FOR EACH ROW EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_balance_curve_roots_no_truncate
    BEFORE TRUNCATE ON entity_empty_source_balance_curve_roots
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_balance_curve_attachments_immutable
    BEFORE UPDATE OR DELETE ON entity_empty_source_balance_curve_attachments
    FOR EACH ROW EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_balance_curve_attachments_no_truncate
    BEFORE TRUNCATE ON entity_empty_source_balance_curve_attachments
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_archetype_roots_immutable
    BEFORE UPDATE OR DELETE ON entity_empty_source_archetype_roots
    FOR EACH ROW EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_archetype_roots_no_truncate
    BEFORE TRUNCATE ON entity_empty_source_archetype_roots
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_archetype_constraints_immutable
    BEFORE UPDATE OR DELETE ON entity_empty_source_archetype_constraints
    FOR EACH ROW EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_archetype_constraints_no_truncate
    BEFORE TRUNCATE ON entity_empty_source_archetype_constraints
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_archetype_assignments_immutable
    BEFORE UPDATE OR DELETE ON entity_empty_source_archetype_assignments
    FOR EACH ROW EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
CREATE TRIGGER trg_entity_empty_source_archetype_assignments_no_truncate
    BEFORE TRUNCATE ON entity_empty_source_archetype_assignments
    FOR EACH STATEMENT EXECUTE FUNCTION entity_reject_empty_source_record_mutation();
/* [jooq ignore stop] */
