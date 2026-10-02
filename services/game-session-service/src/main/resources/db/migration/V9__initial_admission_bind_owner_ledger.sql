ALTER TABLE gameplay_admission_pointer_event
    ALTER COLUMN control_plane_request_id TYPE character varying(128);

CREATE TABLE gameplay_initial_admission_bind_catalog (
    realm_id uuid PRIMARY KEY,
    tenant_id bigint NOT NULL,
    game_template_id bigint NOT NULL,
    world_slug character varying(120) NOT NULL,
    world_display_name character varying(200) NOT NULL,
    realm_slug character varying(120) NOT NULL,
    realm_display_name character varying(200) NOT NULL,
    catalog_revision bigint NOT NULL DEFAULT 1,
    playable_state_namespace_id uuid NOT NULL,
    visible boolean NOT NULL DEFAULT true,
    public_production_realm boolean NOT NULL DEFAULT true,
    requires_character_selection boolean NOT NULL,
    state_scope character varying(32) NOT NULL DEFAULT 'SHARED',
    character_creation_policy character varying(32) NOT NULL DEFAULT 'ALLOW_NEW',
    created_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT gameplay_initial_admission_bind_catalog_tenant_positive
        CHECK (tenant_id > 0),
    CONSTRAINT gameplay_initial_admission_bind_catalog_template_positive
        CHECK (game_template_id > 0),
    CONSTRAINT gameplay_initial_admission_bind_catalog_revision_one
        CHECK (catalog_revision = 1),
    CONSTRAINT gameplay_initial_admission_bind_catalog_public_shared
        CHECK (visible AND public_production_realm AND state_scope = 'SHARED'
            AND character_creation_policy = 'ALLOW_NEW'),
    CONSTRAINT gameplay_initial_admission_bind_catalog_selector_unique
        UNIQUE (tenant_id, world_slug, realm_slug),
    CONSTRAINT gameplay_initial_admission_bind_catalog_template_unique
        UNIQUE (tenant_id, game_template_id),
    CONSTRAINT gameplay_initial_admission_bind_catalog_attempt_identity_unique
        UNIQUE (tenant_id, realm_id, playable_state_namespace_id, catalog_revision)
);

CREATE UNIQUE INDEX uq_gameplay_initial_admission_bind_public_realm_per_tenant
    ON gameplay_initial_admission_bind_catalog (tenant_id)
    WHERE visible AND public_production_realm;

CREATE TABLE gameplay_initial_admission_bind_attempt (
    attempt_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id bigint NOT NULL,
    initial_admission_request_id character varying(128) NOT NULL,
    request_digest character varying(64) NOT NULL,
    realm_id uuid NOT NULL,
    playable_state_namespace_id uuid NOT NULL,
    playable_state_scope character varying(32) NOT NULL,
    expected_no_prior_pointer boolean NOT NULL DEFAULT true,
    catalog_revision bigint NOT NULL,
    game_instance_id bigint NOT NULL,
    version_id bigint NOT NULL,
    active_lifecycle_epoch bigint NOT NULL,
    hold_id uuid,
    hold_fence uuid,
    status character varying(16) NOT NULL DEFAULT 'PENDING',
    pointer_id bigint,
    audit_event_id bigint,
    created_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    terminal_at timestamp without time zone,
    CONSTRAINT gameplay_initial_admission_bind_attempt_request_unique
        UNIQUE (tenant_id, initial_admission_request_id),
    CONSTRAINT gameplay_initial_admission_bind_attempt_catalog_fk
        FOREIGN KEY (tenant_id, realm_id, playable_state_namespace_id, catalog_revision)
        REFERENCES gameplay_initial_admission_bind_catalog
            (tenant_id, realm_id, playable_state_namespace_id, catalog_revision),
    CONSTRAINT gameplay_initial_admission_bind_attempt_pointer_fk
        FOREIGN KEY (pointer_id) REFERENCES gameplay_admission_pointer (id),
    CONSTRAINT gameplay_initial_admission_bind_attempt_audit_fk
        FOREIGN KEY (audit_event_id) REFERENCES gameplay_admission_pointer_event (id),
    CONSTRAINT gameplay_initial_admission_bind_attempt_digest_sha256
        CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT gameplay_initial_admission_bind_attempt_positive_tuple
        CHECK (tenant_id > 0 AND catalog_revision > 0 AND game_instance_id > 0
            AND version_id > 0 AND active_lifecycle_epoch > 0),
    CONSTRAINT gameplay_initial_admission_bind_attempt_shared_scope
        CHECK (playable_state_scope = 'SHARED' AND expected_no_prior_pointer),
    CONSTRAINT gameplay_initial_admission_bind_attempt_status
        CHECK (status IN ('PENDING', 'COMMITTED', 'ABORTED')),
    CONSTRAINT gameplay_initial_admission_bind_attempt_hold_pair
        CHECK ((hold_id IS NULL) = (hold_fence IS NULL)),
    CONSTRAINT gameplay_initial_admission_bind_attempt_result_pair
        CHECK ((pointer_id IS NULL) = (audit_event_id IS NULL)),
    CONSTRAINT gameplay_initial_admission_bind_attempt_terminal_shape
        CHECK ((status = 'PENDING' AND terminal_at IS NULL AND pointer_id IS NULL)
            OR (status = 'COMMITTED' AND terminal_at IS NOT NULL AND pointer_id IS NOT NULL
                AND hold_id IS NOT NULL)
            OR (status = 'ABORTED' AND terminal_at IS NOT NULL AND pointer_id IS NULL))
);

CREATE UNIQUE INDEX uq_gameplay_initial_admission_bind_attempt_hold_id
    ON gameplay_initial_admission_bind_attempt (hold_id)
    WHERE hold_id IS NOT NULL;
CREATE UNIQUE INDEX uq_gameplay_initial_admission_bind_attempt_pointer_id
    ON gameplay_initial_admission_bind_attempt (pointer_id)
    WHERE pointer_id IS NOT NULL;
CREATE UNIQUE INDEX uq_gameplay_initial_admission_bind_attempt_audit_event_id
    ON gameplay_initial_admission_bind_attempt (audit_event_id)
    WHERE audit_event_id IS NOT NULL;
CREATE UNIQUE INDEX uq_gameplay_initial_admission_bind_pending_realm
    ON gameplay_initial_admission_bind_attempt (tenant_id, realm_id)
    WHERE status = 'PENDING';
