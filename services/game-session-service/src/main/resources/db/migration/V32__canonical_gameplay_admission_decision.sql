-- A lease-bound decision is the existing binding transition plus immutable Account lease and
-- point-in-time current route evidence. This is not a second decision ledger or an admission.
ALTER TABLE game_session_canonical_binding_transition
    ADD COLUMN admission_request_id uuid,
    ADD COLUMN admission_lease_id uuid,
    ADD COLUMN admission_lease_fence numeric,
    ADD COLUMN admission_lease_kind character varying(16),
    ADD COLUMN admission_lease_digest character varying(64),
    ADD COLUMN admission_lease_evidence text,
    ADD COLUMN admission_resume_episode_id uuid,
    ADD COLUMN admission_expected_old_binding_generation numeric,
    ADD COLUMN admission_lease_expires_at numeric,
    ADD COLUMN admission_target_namespace text,
    ADD COLUMN admission_target_tenant_slug text,
    ADD COLUMN admission_target_tenant_id uuid,
    ADD COLUMN admission_target_world_slug text,
    ADD COLUMN admission_target_world_display_name text,
    ADD COLUMN admission_target_realm_id uuid,
    ADD COLUMN admission_target_realm_slug text,
    ADD COLUMN admission_target_realm_display_name text,
    ADD COLUMN admission_target_game_session_tenant_id bigint,
    ADD COLUMN admission_target_game_instance_id bigint,
    ADD COLUMN admission_target_playable_state_namespace_id uuid,
    ADD COLUMN admission_target_playable_state_scope character varying(16),
    ADD COLUMN admission_target_canonical_game_instance_id uuid,
    ADD COLUMN admission_target_canonical_version_id uuid,
    ADD COLUMN admission_target_runtime_version_id bigint,
    ADD COLUMN admission_target_catalog_revision bigint,
    ADD COLUMN admission_target_pointer_version bigint,
    ADD COLUMN admission_target_pointer_snapshot_digest character varying(64),
    ADD COLUMN admission_target_active_world_epoch bigint,
    ADD COLUMN admission_target_initial_admission_request_id text,
    ADD COLUMN admission_target_initial_admission_request_digest character varying(64),
    ADD COLUMN admission_target_origin_kind character varying(24),
    ADD COLUMN admission_target_expected_prior_pointer_version bigint,
    ADD COLUMN admission_target_hold_id uuid,
    ADD COLUMN admission_target_hold_fence uuid,
    ADD COLUMN admission_target_hold_binding_digest character varying(71),
    ADD COLUMN admission_target_audit_event_id bigint,
    ADD COLUMN admission_target_owner_proof_digest character varying(71),
    ADD COLUMN admission_target_owner_proof_outcome character varying(16),
    ADD COLUMN admission_target_positive_durable_abort boolean,
    ADD COLUMN admission_target_terminal_at timestamp with time zone,
    ADD COLUMN admission_target_character_creation_policy text;

ALTER TABLE game_session_canonical_binding_transition
    ADD CONSTRAINT chk_gs_canonical_binding_transition_admission_decision CHECK (
        (admission_request_id IS NULL
            AND admission_lease_id IS NULL
            AND admission_lease_fence IS NULL
            AND admission_lease_kind IS NULL
            AND admission_lease_digest IS NULL
            AND admission_lease_evidence IS NULL
            AND admission_resume_episode_id IS NULL
            AND admission_expected_old_binding_generation IS NULL
            AND admission_lease_expires_at IS NULL
            AND admission_target_namespace IS NULL
            AND admission_target_tenant_slug IS NULL
            AND admission_target_tenant_id IS NULL
            AND admission_target_world_slug IS NULL
            AND admission_target_world_display_name IS NULL
            AND admission_target_realm_id IS NULL
            AND admission_target_realm_slug IS NULL
            AND admission_target_realm_display_name IS NULL
            AND admission_target_game_session_tenant_id IS NULL
            AND admission_target_game_instance_id IS NULL
            AND admission_target_playable_state_namespace_id IS NULL
            AND admission_target_playable_state_scope IS NULL
            AND admission_target_canonical_game_instance_id IS NULL
            AND admission_target_canonical_version_id IS NULL
            AND admission_target_runtime_version_id IS NULL
            AND admission_target_catalog_revision IS NULL
            AND admission_target_pointer_version IS NULL
            AND admission_target_pointer_snapshot_digest IS NULL
            AND admission_target_active_world_epoch IS NULL
            AND admission_target_initial_admission_request_id IS NULL
            AND admission_target_initial_admission_request_digest IS NULL
            AND admission_target_origin_kind IS NULL
            AND admission_target_expected_prior_pointer_version IS NULL
            AND admission_target_hold_id IS NULL
            AND admission_target_hold_fence IS NULL
            AND admission_target_hold_binding_digest IS NULL
            AND admission_target_audit_event_id IS NULL
            AND admission_target_owner_proof_digest IS NULL
            AND admission_target_owner_proof_outcome IS NULL
            AND admission_target_positive_durable_abort IS NULL
            AND admission_target_terminal_at IS NULL
            AND admission_target_character_creation_policy IS NULL)
        OR (admission_request_id IS NOT NULL
            AND admission_request_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND admission_lease_id IS NOT NULL
            AND admission_lease_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND admission_lease_fence IS NOT NULL
            AND admission_lease_fence > 0
            AND admission_lease_kind IS NOT NULL
            AND admission_lease_kind IN ('NEW_BINDING', 'RESUME')
            AND admission_lease_digest IS NOT NULL
            AND admission_lease_digest ~ '^[0-9a-f]{64}$'
            AND admission_lease_evidence IS NOT NULL
            AND octet_length(admission_lease_evidence) <= 65536
            AND admission_lease_expires_at IS NOT NULL
            AND admission_lease_expires_at > 0
            AND ((admission_lease_kind = 'RESUME'
                    AND admission_resume_episode_id IS NOT NULL
                    AND admission_resume_episode_id <> '00000000-0000-0000-0000-000000000000'::uuid
                    AND admission_expected_old_binding_generation IS NOT NULL
                    AND admission_expected_old_binding_generation > 0)
                OR (admission_lease_kind = 'NEW_BINDING'
                    AND admission_resume_episode_id IS NULL
                    AND (admission_expected_old_binding_generation IS NULL
                        OR admission_expected_old_binding_generation > 0)))
            AND admission_target_namespace IS NOT NULL
            AND admission_target_tenant_slug IS NOT NULL
            AND admission_target_tenant_id IS NOT NULL
            AND admission_target_tenant_id = tenant_id
            AND admission_target_world_slug IS NOT NULL
            AND admission_target_world_display_name IS NOT NULL
            AND admission_target_realm_id IS NOT NULL
            AND admission_target_realm_slug IS NOT NULL
            AND admission_target_realm_display_name IS NOT NULL
            AND admission_target_game_session_tenant_id IS NOT NULL
            AND admission_target_game_session_tenant_id > 0
            AND admission_target_game_instance_id IS NOT NULL
            AND admission_target_game_instance_id > 0
            AND admission_target_playable_state_namespace_id IS NOT NULL
            AND admission_target_playable_state_namespace_id = playable_state_namespace_id
            AND admission_target_playable_state_scope IS NOT NULL
            AND admission_target_playable_state_scope IN ('SHARED', 'ISOLATED')
            AND admission_target_canonical_game_instance_id IS NOT NULL
            AND admission_target_canonical_version_id IS NOT NULL
            AND admission_target_runtime_version_id IS NOT NULL
            AND admission_target_runtime_version_id > 0
            AND admission_target_catalog_revision IS NOT NULL
            AND admission_target_catalog_revision > 0
            AND admission_target_pointer_version IS NOT NULL
            AND admission_target_pointer_version > 0
            AND admission_target_pointer_snapshot_digest IS NOT NULL
            AND admission_target_pointer_snapshot_digest ~ '^[0-9a-f]{64}$'
            AND admission_target_active_world_epoch IS NOT NULL
            AND admission_target_active_world_epoch > 0
            AND admission_target_initial_admission_request_id IS NOT NULL
            AND admission_target_initial_admission_request_digest IS NOT NULL
            AND admission_target_initial_admission_request_digest ~ '^[0-9a-f]{64}$'
            AND admission_target_origin_kind IS NOT NULL
            AND admission_target_origin_kind IN ('NO_PRIOR_POINTER', 'EXPECT_CLOSED')
            AND ((admission_target_origin_kind = 'NO_PRIOR_POINTER'
                    AND admission_target_expected_prior_pointer_version IS NULL)
                OR (admission_target_origin_kind = 'EXPECT_CLOSED'
                    AND admission_target_expected_prior_pointer_version IS NOT NULL
                    AND admission_target_expected_prior_pointer_version > 0))
            AND admission_target_hold_id IS NOT NULL
            AND admission_target_hold_fence IS NOT NULL
            AND admission_target_hold_binding_digest IS NOT NULL
            AND admission_target_hold_binding_digest ~ '^sha256:[0-9a-f]{64}$'
            AND admission_target_audit_event_id IS NOT NULL
            AND admission_target_audit_event_id > 0
            AND admission_target_owner_proof_digest IS NOT NULL
            AND admission_target_owner_proof_digest ~ '^sha256:[0-9a-f]{64}$'
            AND admission_target_owner_proof_outcome IS NOT NULL
            AND admission_target_owner_proof_outcome = 'COMMITTED'
            AND admission_target_positive_durable_abort IS NOT NULL
            AND admission_target_positive_durable_abort = FALSE
            AND admission_target_terminal_at IS NOT NULL
            AND admission_target_character_creation_policy IS NOT NULL
            AND length(admission_target_character_creation_policy) > 0
            AND status IN ('PROVISIONAL', 'COMMITTED', 'ABORTED', 'AMBIGUOUS'))
    );

CREATE UNIQUE INDEX uq_gs_canonical_binding_admission_request
    ON game_session_canonical_binding_transition (admission_request_id)
    WHERE admission_request_id IS NOT NULL;

CREATE UNIQUE INDEX uq_gs_canonical_binding_admission_lease
    ON game_session_canonical_binding_transition (admission_lease_id)
    WHERE admission_lease_id IS NOT NULL;
