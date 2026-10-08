-- Add the first canonical OPEN as a separate representation. The V13 CLOSED representation and
-- its original request/event remain immutable survivor evidence.
-- [jooq ignore start]
ALTER TABLE gameplay_admission_pointer
    ADD COLUMN canonical_game_instance_id uuid,
    ADD COLUMN canonical_version_id uuid,
    ADD COLUMN runtime_version_id bigint,
    ADD COLUMN initial_admission_request_id character varying(128),
    ADD COLUMN initial_admission_request_digest character varying(64),
    ADD COLUMN initial_admission_origin_kind character varying(24),
    ADD COLUMN initial_admission_prior_pointer_version bigint,
    ADD COLUMN initial_admission_active_epoch bigint,
    ADD COLUMN initial_admission_hold_id uuid,
    ADD COLUMN initial_admission_hold_fence uuid,
    ADD COLUMN initial_admission_hold_binding_digest character varying(71);

ALTER TABLE gameplay_admission_pointer_event
    ADD COLUMN canonical_game_instance_id uuid,
    ADD COLUMN canonical_version_id uuid,
    ADD COLUMN runtime_version_id bigint,
    ADD COLUMN initial_admission_request_id character varying(128),
    ADD COLUMN initial_admission_request_digest character varying(64),
    ADD COLUMN initial_admission_origin_kind character varying(24),
    ADD COLUMN initial_admission_prior_pointer_version bigint,
    ADD COLUMN initial_admission_active_epoch bigint,
    ADD COLUMN initial_admission_hold_id uuid,
    ADD COLUMN initial_admission_hold_fence uuid,
    ADD COLUMN initial_admission_hold_binding_digest character varying(71);

CREATE TABLE game_session_canonical_initial_admission_attempt (
    target_namespace character varying(63) NOT NULL,
    initial_admission_request_id character varying(128) NOT NULL,
    request_digest character varying(64) NOT NULL,
    canonical_tenant_id uuid NOT NULL,
    world_slug character varying(120) NOT NULL,
    realm_id uuid NOT NULL,
    playable_state_namespace_id uuid NOT NULL,
    playable_state_scope character varying(16) NOT NULL,
    canonical_game_instance_id uuid NOT NULL,
    canonical_version_id uuid NOT NULL,
    game_session_tenant_id bigint NOT NULL,
    game_instance_id bigint NOT NULL,
    runtime_version_id bigint NOT NULL,
    expected_catalog_revision bigint NOT NULL,
    origin_kind character varying(24) NOT NULL,
    expected_prior_pointer_version bigint,
    active_lifecycle_epoch bigint NOT NULL,
    hold_id uuid NOT NULL,
    hold_fence uuid NOT NULL,
    hold_binding_digest character varying(71) NOT NULL,
    status character varying(16) NOT NULL DEFAULT 'PENDING',
    pointer_id bigint,
    pointer_version bigint,
    audit_event_id bigint,
    prior_pointer_request_id uuid,
    prior_pointer_request_digest character varying(71),
    prior_pointer_audit_event_id bigint,
    commit_proof_digest character varying(71),
    abort_proof_digest character varying(71),
    abort_reason character varying(500),
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    terminal_at timestamp with time zone,
    CONSTRAINT pk_gs_canonical_initial_admission_attempt
        PRIMARY KEY (target_namespace, initial_admission_request_id),
    CONSTRAINT uq_gs_canonical_initial_admission_hold UNIQUE (target_namespace, hold_id),
    CONSTRAINT uq_gs_canonical_initial_admission_audit UNIQUE (audit_event_id),
    CONSTRAINT fk_gs_canonical_initial_admission_catalog
        FOREIGN KEY (target_namespace, realm_id)
        REFERENCES game_session_canonical_realm_catalog (target_namespace, realm_id),
    CONSTRAINT fk_gs_canonical_initial_admission_launch
        FOREIGN KEY (target_namespace, canonical_game_instance_id)
        REFERENCES game_session_canonical_instance_launch (target_namespace, game_instance_uuid),
    CONSTRAINT fk_gs_canonical_initial_admission_pointer
        FOREIGN KEY (pointer_id) REFERENCES gameplay_admission_pointer (id),
    CONSTRAINT fk_gs_canonical_initial_admission_event
        FOREIGN KEY (audit_event_id) REFERENCES gameplay_admission_pointer_event (id),
    CONSTRAINT fk_gs_canonical_initial_admission_prior_event
        FOREIGN KEY (prior_pointer_audit_event_id) REFERENCES gameplay_admission_pointer_event (id),
    CONSTRAINT chk_gs_canonical_initial_admission_identity CHECK (
        octet_length(target_namespace) BETWEEN 1 AND 63
        AND target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
        AND char_length(initial_admission_request_id) BETWEEN 1 AND 128
        AND initial_admission_request_id !~ '^[[:space:]]*$'
        AND initial_admission_request_id !~ '[[:cntrl:]]'
        AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND realm_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND canonical_game_instance_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND hold_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND hold_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND game_session_tenant_id > 0
        AND game_instance_id > 0
        AND runtime_version_id > 0
        AND expected_catalog_revision > 0
        AND active_lifecycle_epoch > 0
        AND playable_state_scope = 'SHARED'),
    CONSTRAINT chk_gs_canonical_initial_admission_digest CHECK (
        request_digest ~ '^[0-9a-f]{64}$'
        AND hold_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND (commit_proof_digest IS NULL OR commit_proof_digest ~ '^sha256:[0-9a-f]{64}$')
        AND (abort_proof_digest IS NULL OR abort_proof_digest ~ '^sha256:[0-9a-f]{64}$')
        AND (prior_pointer_request_digest IS NULL
            OR prior_pointer_request_digest ~ '^sha256:[0-9a-f]{64}$')),
    CONSTRAINT chk_gs_canonical_initial_admission_origin CHECK (
        (origin_kind = 'NO_PRIOR_POINTER'
            AND expected_prior_pointer_version IS NULL
            AND prior_pointer_request_id IS NULL
            AND prior_pointer_request_digest IS NULL
            AND prior_pointer_audit_event_id IS NULL)
        OR (origin_kind = 'EXPECT_CLOSED'
            AND expected_prior_pointer_version > 0
            AND ((prior_pointer_request_id IS NULL
                    AND prior_pointer_request_digest IS NULL
                    AND prior_pointer_audit_event_id IS NULL)
                OR (prior_pointer_request_id IS NOT NULL
                    AND prior_pointer_request_digest IS NOT NULL
                    AND prior_pointer_audit_event_id IS NOT NULL)))),
    CONSTRAINT chk_gs_canonical_initial_admission_result CHECK (
        (status = 'PENDING'
            AND terminal_at IS NULL
            AND pointer_id IS NULL
            AND pointer_version IS NULL
            AND audit_event_id IS NULL
            AND prior_pointer_request_id IS NULL
            AND prior_pointer_request_digest IS NULL
            AND prior_pointer_audit_event_id IS NULL
            AND commit_proof_digest IS NULL
            AND abort_proof_digest IS NULL
            AND abort_reason IS NULL)
        OR (status = 'COMMITTED'
            AND terminal_at IS NOT NULL
            AND pointer_id IS NOT NULL
            AND pointer_version > 0
            AND audit_event_id IS NOT NULL
            AND commit_proof_digest IS NOT NULL
            AND abort_proof_digest IS NULL
            AND abort_reason IS NULL
            AND ((origin_kind = 'NO_PRIOR_POINTER'
                    AND prior_pointer_request_id IS NULL
                    AND prior_pointer_request_digest IS NULL
                    AND prior_pointer_audit_event_id IS NULL)
                OR (origin_kind = 'EXPECT_CLOSED'
                    AND prior_pointer_request_id IS NOT NULL
                    AND prior_pointer_request_digest IS NOT NULL
                    AND prior_pointer_audit_event_id IS NOT NULL)))
        OR (status = 'ABORTED'
            AND terminal_at IS NOT NULL
            AND pointer_id IS NULL
            AND pointer_version IS NULL
            AND audit_event_id IS NULL
            AND commit_proof_digest IS NULL
            AND abort_proof_digest IS NOT NULL
            AND abort_reason IS NOT NULL
            AND char_length(abort_reason) BETWEEN 1 AND 500
            AND abort_reason !~ '^[[:space:]]*$'
            AND ((origin_kind = 'NO_PRIOR_POINTER'
                    AND prior_pointer_request_id IS NULL
                    AND prior_pointer_request_digest IS NULL
                    AND prior_pointer_audit_event_id IS NULL)
                OR (origin_kind = 'EXPECT_CLOSED'
                    AND prior_pointer_request_id IS NOT NULL
                    AND prior_pointer_request_digest IS NOT NULL
                    AND prior_pointer_audit_event_id IS NOT NULL))))
);

CREATE UNIQUE INDEX uq_gs_canonical_initial_admission_pending_realm
    ON game_session_canonical_initial_admission_attempt (target_namespace, canonical_tenant_id, realm_id)
    WHERE status = 'PENDING';

CREATE UNIQUE INDEX uq_gameplay_admission_pointer_event_initial_realm_version
    ON gameplay_admission_pointer_event (target_namespace, canonical_tenant_id, realm_id, pointer_version)
    WHERE representation_version = 3;

ALTER TABLE gameplay_admission_pointer
    DROP CONSTRAINT chk_gameplay_admission_pointer_representation,
    ADD CONSTRAINT chk_gameplay_admission_pointer_representation CHECK (
        (representation_version = 1
            AND target_namespace IS NULL
            AND canonical_tenant_id IS NULL
            AND admission_state IS NULL
            AND tenant_id IS NOT NULL
            AND game_instance_id IS NOT NULL
            AND world_display_name IS NOT NULL
            AND realm_display_name IS NOT NULL
            AND visible IS NOT NULL
            AND public_production_realm IS NOT NULL
            AND requires_character_selection IS NOT NULL
            AND state_scope IS NOT NULL
            AND character_creation_policy IS NOT NULL
            AND last_updated_by IS NOT NULL
            AND last_update_reason IS NOT NULL
            AND canonical_game_instance_id IS NULL
            AND canonical_version_id IS NULL
            AND runtime_version_id IS NULL
            AND initial_admission_hold_binding_digest IS NULL)
        OR (representation_version = 2
            AND target_namespace IS NOT NULL
            AND canonical_tenant_id IS NOT NULL
            AND admission_state IS NOT NULL
            AND admission_state = 'CLOSED'
            AND realm_id IS NOT NULL
            AND playable_state_namespace_id IS NULL
            AND tenant_id IS NULL
            AND game_instance_id IS NULL
            AND pointer_version = 1
            AND catalog_revision = 1
            AND world_display_name IS NULL
            AND realm_display_name IS NULL
            AND visible IS NULL
            AND public_production_realm IS NULL
            AND requires_character_selection IS NULL
            AND state_scope IS NULL
            AND character_creation_policy IS NULL
            AND last_updated_by IS NULL
            AND last_update_reason IS NULL
            AND canonical_game_instance_id IS NULL
            AND canonical_version_id IS NULL
            AND runtime_version_id IS NULL
            AND initial_admission_request_id IS NULL
            AND initial_admission_request_digest IS NULL
            AND initial_admission_origin_kind IS NULL
            AND initial_admission_prior_pointer_version IS NULL
            AND initial_admission_active_epoch IS NULL
            AND initial_admission_hold_id IS NULL
            AND initial_admission_hold_fence IS NULL
            AND initial_admission_hold_binding_digest IS NULL)
        OR (representation_version = 3
            AND target_namespace IS NOT NULL
            AND canonical_tenant_id IS NOT NULL
            AND realm_id IS NOT NULL
            AND playable_state_namespace_id IS NOT NULL
            AND admission_state = 'OPEN'
            AND tenant_id IS NOT NULL
            AND game_instance_id IS NOT NULL
            AND pointer_version > 0
            AND catalog_revision > 0
            AND world_display_name IS NOT NULL
            AND realm_display_name IS NOT NULL
            AND visible IS NOT NULL
            AND public_production_realm IS TRUE
            AND requires_character_selection IS NULL
            AND state_scope = 'SHARED'
            AND character_creation_policy IS NOT NULL
            AND last_updated_by = 'game-session-canonical-initial-admission'
            AND last_update_reason = 'World-held initial admission'
            AND canonical_game_instance_id IS NOT NULL
            AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND runtime_version_id > 0
            AND initial_admission_request_id IS NOT NULL
            AND initial_admission_request_digest IS NOT NULL
            AND initial_admission_origin_kind IS NOT NULL
            AND initial_admission_active_epoch > 0
            AND initial_admission_hold_id IS NOT NULL
            AND initial_admission_hold_fence IS NOT NULL
            AND initial_admission_hold_binding_digest IS NOT NULL
            AND ((initial_admission_origin_kind = 'NO_PRIOR_POINTER'
                    AND initial_admission_prior_pointer_version IS NULL
                    AND pointer_version = 1)
                OR (initial_admission_origin_kind = 'EXPECT_CLOSED'
                    AND initial_admission_prior_pointer_version > 0
                    AND pointer_version = initial_admission_prior_pointer_version + 1))))
;

ALTER TABLE gameplay_admission_pointer
    DROP CONSTRAINT gameplay_admission_pointer_identity_pair_complete,
    ADD CONSTRAINT gameplay_admission_pointer_identity_pair_complete CHECK (
        (representation_version = 1
            AND ((realm_id IS NULL) = (playable_state_namespace_id IS NULL)))
        OR (representation_version = 2 AND realm_id IS NOT NULL AND playable_state_namespace_id IS NULL)
        OR (representation_version = 3 AND realm_id IS NOT NULL AND playable_state_namespace_id IS NOT NULL));

ALTER TABLE gameplay_admission_pointer
    DROP CONSTRAINT chk_gameplay_admission_pointer_canonical_identity,
    ADD CONSTRAINT chk_gameplay_admission_pointer_canonical_identity CHECK (
        representation_version NOT IN (2, 3)
        OR (octet_length(target_namespace) BETWEEN 1 AND 63
            AND target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
            AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND realm_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND (representation_version = 2
                OR (playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid
                    AND canonical_game_instance_id <> '00000000-0000-0000-0000-000000000000'::uuid
                    AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::uuid
                    AND runtime_version_id > 0
                    AND initial_admission_request_digest ~ '^[0-9a-f]{64}$'
                    AND initial_admission_request_id !~ '[[:cntrl:]]'
                    AND initial_admission_hold_id <> '00000000-0000-0000-0000-000000000000'::uuid
                    AND initial_admission_hold_fence <> '00000000-0000-0000-0000-000000000000'::uuid
                    AND initial_admission_hold_binding_digest ~ '^sha256:[0-9a-f]{64}$'))));

ALTER TABLE gameplay_admission_pointer_event
    DROP CONSTRAINT chk_gameplay_admission_pointer_event_representation,
    ADD CONSTRAINT chk_gameplay_admission_pointer_event_representation CHECK (
        (representation_version = 1
            AND target_namespace IS NULL
            AND canonical_tenant_id IS NULL
            AND admission_state IS NULL
            AND tenant_id IS NOT NULL
            AND game_instance_id IS NOT NULL
            AND world_display_name IS NOT NULL
            AND realm_display_name IS NOT NULL
            AND visible IS NOT NULL
            AND public_production_realm IS NOT NULL
            AND requires_character_selection IS NOT NULL
            AND state_scope IS NOT NULL
            AND character_creation_policy IS NOT NULL)
        OR (representation_version = 2
            AND target_namespace IS NOT NULL
            AND canonical_tenant_id IS NOT NULL
            AND admission_state = 'CLOSED'
            AND realm_id IS NOT NULL
            AND playable_state_namespace_id IS NULL
            AND catalog_revision = 1
            AND tenant_id IS NULL
            AND game_instance_id IS NULL
            AND pointer_version = 1
            AND world_display_name IS NULL
            AND realm_display_name IS NULL
            AND visible IS NULL
            AND public_production_realm IS NULL
            AND requires_character_selection IS NULL
            AND state_scope IS NULL
            AND character_creation_policy IS NULL
            AND prepared_version_upgrade_id IS NULL
            AND canonical_game_instance_id IS NULL
            AND canonical_version_id IS NULL
            AND runtime_version_id IS NULL
            AND initial_admission_request_id IS NULL
            AND initial_admission_request_digest IS NULL
            AND initial_admission_origin_kind IS NULL
            AND initial_admission_prior_pointer_version IS NULL
            AND initial_admission_active_epoch IS NULL
            AND initial_admission_hold_id IS NULL
            AND initial_admission_hold_fence IS NULL
            AND initial_admission_hold_binding_digest IS NULL)
        OR (representation_version = 3
            AND target_namespace IS NOT NULL
            AND canonical_tenant_id IS NOT NULL
            AND realm_id IS NOT NULL
            AND playable_state_namespace_id IS NOT NULL
            AND admission_state = 'OPEN'
            AND catalog_revision > 0
            AND tenant_id IS NOT NULL
            AND game_instance_id IS NOT NULL
            AND pointer_version > 0
            AND world_display_name IS NOT NULL
            AND realm_display_name IS NOT NULL
            AND visible IS NOT NULL
            AND public_production_realm IS TRUE
            AND requires_character_selection IS NULL
            AND state_scope = 'SHARED'
            AND character_creation_policy IS NOT NULL
            AND prepared_version_upgrade_id IS NULL
            AND canonical_game_instance_id IS NOT NULL
            AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND runtime_version_id > 0
            AND initial_admission_request_id IS NOT NULL
            AND initial_admission_request_digest IS NOT NULL
            AND initial_admission_origin_kind IS NOT NULL
            AND initial_admission_active_epoch > 0
            AND initial_admission_hold_id IS NOT NULL
            AND initial_admission_hold_fence IS NOT NULL
            AND initial_admission_hold_binding_digest IS NOT NULL
            AND actor_principal = 'game-session-canonical-initial-admission'
            AND reason = 'World-held initial admission'
            AND ((initial_admission_origin_kind = 'NO_PRIOR_POINTER'
                    AND initial_admission_prior_pointer_version IS NULL
                    AND pointer_version = 1)
                OR (initial_admission_origin_kind = 'EXPECT_CLOSED'
                    AND initial_admission_prior_pointer_version > 0
                    AND pointer_version = initial_admission_prior_pointer_version + 1))))
;

ALTER TABLE gameplay_admission_pointer_event
    DROP CONSTRAINT gameplay_admission_pointer_event_identity_pair_complete,
    ADD CONSTRAINT gameplay_admission_pointer_event_identity_pair_complete CHECK (
        (representation_version = 1
            AND ((realm_id IS NULL) = (playable_state_namespace_id IS NULL)))
        OR (representation_version = 2 AND realm_id IS NOT NULL AND playable_state_namespace_id IS NULL)
        OR (representation_version = 3 AND realm_id IS NOT NULL AND playable_state_namespace_id IS NOT NULL));

ALTER TABLE gameplay_admission_pointer_event
    DROP CONSTRAINT chk_gameplay_admission_pointer_event_canonical_identity,
    ADD CONSTRAINT chk_gameplay_admission_pointer_event_canonical_identity CHECK (
        representation_version NOT IN (2, 3)
        OR (octet_length(target_namespace) BETWEEN 1 AND 63
            AND target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
            AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND realm_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND (representation_version = 2
                OR (playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid
                    AND canonical_game_instance_id <> '00000000-0000-0000-0000-000000000000'::uuid
                    AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::uuid
                    AND runtime_version_id > 0
                    AND initial_admission_request_digest ~ '^[0-9a-f]{64}$'
                    AND initial_admission_request_id !~ '[[:cntrl:]]'
                    AND initial_admission_hold_id <> '00000000-0000-0000-0000-000000000000'::uuid
                    AND initial_admission_hold_fence <> '00000000-0000-0000-0000-000000000000'::uuid
                    AND initial_admission_hold_binding_digest ~ '^sha256:[0-9a-f]{64}$'))));

ALTER TABLE gameplay_admission_pointer
    ADD CONSTRAINT fk_gameplay_admission_pointer_initial_request
        FOREIGN KEY (target_namespace, initial_admission_request_id)
        REFERENCES game_session_canonical_initial_admission_attempt
            (target_namespace, initial_admission_request_id)
        DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT fk_gameplay_admission_pointer_initial_launch
        FOREIGN KEY (target_namespace, canonical_game_instance_id)
        REFERENCES game_session_canonical_instance_launch (target_namespace, game_instance_uuid);

ALTER TABLE gameplay_admission_pointer_event
    ADD CONSTRAINT fk_gameplay_admission_event_initial_request
        FOREIGN KEY (target_namespace, initial_admission_request_id)
        REFERENCES game_session_canonical_initial_admission_attempt
            (target_namespace, initial_admission_request_id)
        DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT fk_gameplay_admission_event_initial_launch
        FOREIGN KEY (target_namespace, canonical_game_instance_id)
        REFERENCES game_session_canonical_instance_launch (target_namespace, game_instance_uuid);

DROP TRIGGER gameplay_admission_pointer_canonical_immutable ON gameplay_admission_pointer;
DROP TRIGGER gameplay_admission_pointer_event_canonical_immutable ON gameplay_admission_pointer_event;
DROP TRIGGER gameplay_admission_pointer_canonical_no_truncate ON gameplay_admission_pointer;
DROP TRIGGER gameplay_admission_pointer_event_canonical_no_truncate ON gameplay_admission_pointer_event;

CREATE OR REPLACE FUNCTION reject_game_session_canonical_closed_pointer_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        IF TG_TABLE_NAME = 'game_session_canonical_closed_admission_pointer_request'
            AND EXISTS (SELECT 1 FROM game_session_canonical_closed_admission_pointer_request)
        THEN
            RAISE EXCEPTION 'Canonical CLOSED pointer request outcomes are immutable survivor evidence'
                USING ERRCODE = '23514';
        ELSIF TG_TABLE_NAME = 'gameplay_admission_pointer'
            AND EXISTS (SELECT 1 FROM gameplay_admission_pointer WHERE representation_version IN (2, 3))
        THEN
            RAISE EXCEPTION 'Canonical admission pointers are immutable survivor evidence'
                USING ERRCODE = '23514';
        ELSIF TG_TABLE_NAME = 'gameplay_admission_pointer_event'
            AND EXISTS (SELECT 1 FROM gameplay_admission_pointer_event WHERE representation_version IN (2, 3))
        THEN
            RAISE EXCEPTION 'Canonical admission pointer events are immutable survivor evidence'
                USING ERRCODE = '23514';
        ELSIF TG_TABLE_NAME = 'game_session_canonical_initial_admission_attempt'
            AND EXISTS (SELECT 1 FROM game_session_canonical_initial_admission_attempt)
        THEN
            RAISE EXCEPTION 'Canonical initial-admission outcomes are durable survivor evidence'
                USING ERRCODE = '23514';
        END IF;
        RETURN NULL;
    END IF;

    IF TG_TABLE_NAME = 'game_session_canonical_closed_admission_pointer_request' THEN
        RAISE EXCEPTION 'Canonical CLOSED pointer evidence is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'gameplay_admission_pointer_event' THEN
        IF OLD.representation_version IN (2, 3) THEN
            RAISE EXCEPTION 'Canonical pointer audit events are immutable'
                USING ERRCODE = '23514';
        END IF;
    ELSIF TG_TABLE_NAME = 'gameplay_admission_pointer' THEN
        IF TG_OP = 'DELETE' AND OLD.representation_version IN (2, 3) THEN
            RAISE EXCEPTION 'Canonical admission pointers are immutable survivor evidence'
                USING ERRCODE = '23514';
        END IF;
        IF TG_OP = 'UPDATE' AND OLD.representation_version = 2 THEN
            IF NEW.representation_version = 3
                AND OLD.id = NEW.id
                AND OLD.target_namespace = NEW.target_namespace
                AND OLD.canonical_tenant_id = NEW.canonical_tenant_id
                AND OLD.realm_id = NEW.realm_id
                AND OLD.pointer_version = 1
                AND OLD.admission_state = 'CLOSED'
                AND OLD.playable_state_namespace_id IS NULL
                AND OLD.tenant_id IS NULL
                AND OLD.game_instance_id IS NULL
                AND NEW.created_at = OLD.created_at
            THEN
                RETURN NEW;
            END IF;
            RAISE EXCEPTION 'Canonical CLOSED pointer may only enter the fenced first-OPEN operation'
                USING ERRCODE = '23514';
        ELSIF TG_OP = 'UPDATE' AND OLD.representation_version = 3 THEN
            RAISE EXCEPTION 'Canonical OPEN pointer is immutable outside the initial owner operation'
                USING ERRCODE = '23514';
        ELSIF TG_OP = 'UPDATE' AND NEW.representation_version = 2 THEN
            RAISE EXCEPTION 'Canonical CLOSED pointer evidence cannot be rewritten'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER gameplay_admission_pointer_canonical_immutable
BEFORE UPDATE OR DELETE ON gameplay_admission_pointer
FOR EACH ROW EXECUTE FUNCTION reject_game_session_canonical_closed_pointer_mutation();
CREATE TRIGGER gameplay_admission_pointer_event_canonical_immutable
BEFORE UPDATE OR DELETE ON gameplay_admission_pointer_event
FOR EACH ROW EXECUTE FUNCTION reject_game_session_canonical_closed_pointer_mutation();
CREATE TRIGGER gameplay_admission_pointer_canonical_no_truncate
BEFORE TRUNCATE ON gameplay_admission_pointer
FOR EACH STATEMENT EXECUTE FUNCTION reject_game_session_canonical_closed_pointer_mutation();
CREATE TRIGGER gameplay_admission_pointer_event_canonical_no_truncate
BEFORE TRUNCATE ON gameplay_admission_pointer_event
FOR EACH STATEMENT EXECUTE FUNCTION reject_game_session_canonical_closed_pointer_mutation();

CREATE FUNCTION reject_game_session_canonical_initial_admission_attempt_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Canonical initial-admission attempts cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.status <> 'PENDING'
        OR NEW.status NOT IN ('COMMITTED', 'ABORTED')
        OR NEW.target_namespace IS DISTINCT FROM OLD.target_namespace
        OR NEW.initial_admission_request_id IS DISTINCT FROM OLD.initial_admission_request_id
        OR NEW.request_digest IS DISTINCT FROM OLD.request_digest
        OR NEW.canonical_tenant_id IS DISTINCT FROM OLD.canonical_tenant_id
        OR NEW.world_slug IS DISTINCT FROM OLD.world_slug
        OR NEW.realm_id IS DISTINCT FROM OLD.realm_id
        OR NEW.playable_state_namespace_id IS DISTINCT FROM OLD.playable_state_namespace_id
        OR NEW.playable_state_scope IS DISTINCT FROM OLD.playable_state_scope
        OR NEW.canonical_game_instance_id IS DISTINCT FROM OLD.canonical_game_instance_id
        OR NEW.canonical_version_id IS DISTINCT FROM OLD.canonical_version_id
        OR NEW.game_session_tenant_id IS DISTINCT FROM OLD.game_session_tenant_id
        OR NEW.game_instance_id IS DISTINCT FROM OLD.game_instance_id
        OR NEW.runtime_version_id IS DISTINCT FROM OLD.runtime_version_id
        OR NEW.expected_catalog_revision IS DISTINCT FROM OLD.expected_catalog_revision
        OR NEW.origin_kind IS DISTINCT FROM OLD.origin_kind
        OR NEW.expected_prior_pointer_version IS DISTINCT FROM OLD.expected_prior_pointer_version
        OR NEW.active_lifecycle_epoch IS DISTINCT FROM OLD.active_lifecycle_epoch
        OR NEW.hold_id IS DISTINCT FROM OLD.hold_id
        OR NEW.hold_fence IS DISTINCT FROM OLD.hold_fence
        OR NEW.hold_binding_digest IS DISTINCT FROM OLD.hold_binding_digest
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.updated_at <= OLD.updated_at
        OR NEW.terminal_at IS NULL
    THEN
        RAISE EXCEPTION 'Canonical initial-admission request identity is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER game_session_canonical_initial_admission_attempt_immutable
BEFORE UPDATE OR DELETE ON game_session_canonical_initial_admission_attempt
FOR EACH ROW EXECUTE FUNCTION reject_game_session_canonical_initial_admission_attempt_mutation();

CREATE TRIGGER game_session_canonical_initial_admission_attempt_no_truncate
BEFORE TRUNCATE ON game_session_canonical_initial_admission_attempt
FOR EACH STATEMENT EXECUTE FUNCTION reject_game_session_canonical_closed_pointer_mutation();

CREATE FUNCTION require_game_session_canonical_initial_admission_catalog()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.representation_version <> 3 THEN
        RETURN NEW;
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM game_session_canonical_realm_catalog catalog
        JOIN game_session_canonical_instance_launch launch
          ON launch.target_namespace = catalog.target_namespace
         AND launch.canonical_tenant_id = catalog.canonical_tenant_id
         AND launch.canonical_realm_id = catalog.realm_id
         AND launch.world_slug = catalog.world_slug
         AND launch.playable_state_namespace_id = catalog.playable_state_namespace_id
         AND launch.game_instance_uuid = NEW.canonical_game_instance_id
         AND launch.game_session_tenant_id = NEW.tenant_id
         AND launch.game_instance_id = NEW.game_instance_id
         AND launch.version_id = NEW.runtime_version_id
         AND launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'canonicalVersionId'
             = NEW.canonical_version_id::text
         AND launch.playable_state_scope = catalog.state_scope
         AND launch.public_production = catalog.public_production
        JOIN game_instances current
          ON current.tenant_id = launch.game_session_tenant_id
         AND current.id = launch.game_instance_id
         AND current.game_instance_uuid = launch.game_instance_uuid
         AND current.version_id = NEW.runtime_version_id
         AND current.status = 'RUNNING'
        WHERE catalog.target_namespace = NEW.target_namespace
          AND catalog.canonical_tenant_id = NEW.canonical_tenant_id
          AND catalog.realm_id = NEW.realm_id
          AND catalog.world_slug = NEW.world_slug
          AND catalog.realm_slug = NEW.realm_slug
          AND catalog.catalog_revision = NEW.catalog_revision
          AND catalog.playable_state_namespace_id = NEW.playable_state_namespace_id
          AND catalog.state_scope = NEW.state_scope
          AND catalog.visible = NEW.visible
          AND catalog.public_production = NEW.public_production_realm
          AND catalog.character_creation_policy = NEW.character_creation_policy
    ) THEN
        RAISE EXCEPTION 'Canonical OPEN pointer requires exact catalog and launch owner evidence'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER gameplay_admission_pointer_initial_admission_requires_owners
BEFORE INSERT OR UPDATE ON gameplay_admission_pointer
FOR EACH ROW EXECUTE FUNCTION require_game_session_canonical_initial_admission_catalog();

CREATE FUNCTION require_game_session_canonical_initial_admission_atomic_evidence()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    selected_namespace character varying(63);
    selected_request_id character varying(128);
    selected_status character varying(16);
BEGIN
    IF TG_TABLE_NAME = 'gameplay_admission_pointer' THEN
        IF NEW.representation_version <> 3 THEN
            RETURN NULL;
        END IF;
        selected_namespace := NEW.target_namespace;
        selected_request_id := NEW.initial_admission_request_id;
    ELSIF TG_TABLE_NAME = 'gameplay_admission_pointer_event' THEN
        IF NEW.representation_version <> 3 THEN
            RETURN NULL;
        END IF;
        selected_namespace := NEW.target_namespace;
        selected_request_id := NEW.initial_admission_request_id;
    ELSE
        selected_namespace := NEW.target_namespace;
        selected_request_id := NEW.initial_admission_request_id;
    END IF;

    SELECT status INTO selected_status
    FROM game_session_canonical_initial_admission_attempt
    WHERE target_namespace = selected_namespace
      AND initial_admission_request_id = selected_request_id;

    IF selected_status = 'PENDING' THEN
        IF EXISTS (
            SELECT 1 FROM gameplay_admission_pointer
            WHERE representation_version = 3
              AND target_namespace = selected_namespace
              AND initial_admission_request_id = selected_request_id
        ) OR EXISTS (
            SELECT 1 FROM gameplay_admission_pointer_event
            WHERE representation_version = 3
              AND target_namespace = selected_namespace
              AND initial_admission_request_id = selected_request_id
        ) THEN
            RAISE EXCEPTION 'PENDING canonical initial-admission attempt cannot carry pointer evidence'
                USING ERRCODE = '23514';
        END IF;
        RETURN NULL;
    END IF;

    IF selected_status = 'COMMITTED' AND EXISTS (
        SELECT 1
        FROM game_session_canonical_initial_admission_attempt attempt
        JOIN gameplay_admission_pointer pointer
          ON pointer.id = attempt.pointer_id
         AND pointer.representation_version = 3
         AND pointer.target_namespace = attempt.target_namespace
         AND pointer.canonical_tenant_id = attempt.canonical_tenant_id
         AND pointer.realm_id = attempt.realm_id
         AND pointer.world_slug = attempt.world_slug
         AND pointer.playable_state_namespace_id = attempt.playable_state_namespace_id
         AND pointer.state_scope = attempt.playable_state_scope
         AND pointer.canonical_game_instance_id = attempt.canonical_game_instance_id
         AND pointer.canonical_version_id = attempt.canonical_version_id
         AND pointer.runtime_version_id = attempt.runtime_version_id
         AND pointer.tenant_id = attempt.game_session_tenant_id
         AND pointer.game_instance_id = attempt.game_instance_id
         AND pointer.pointer_version = attempt.pointer_version
         AND pointer.catalog_revision = attempt.expected_catalog_revision
         AND pointer.initial_admission_request_id = attempt.initial_admission_request_id
         AND pointer.initial_admission_request_digest = attempt.request_digest
         AND pointer.initial_admission_origin_kind = attempt.origin_kind
         AND pointer.initial_admission_prior_pointer_version IS NOT DISTINCT FROM attempt.expected_prior_pointer_version
         AND pointer.initial_admission_active_epoch = attempt.active_lifecycle_epoch
         AND pointer.initial_admission_hold_id = attempt.hold_id
         AND pointer.initial_admission_hold_fence = attempt.hold_fence
         AND pointer.initial_admission_hold_binding_digest = attempt.hold_binding_digest
         AND pointer.admission_state = 'OPEN'
        JOIN gameplay_admission_pointer_event event
          ON event.id = attempt.audit_event_id
         AND event.representation_version = 3
         AND event.target_namespace = attempt.target_namespace
         AND event.canonical_tenant_id = attempt.canonical_tenant_id
         AND event.realm_id = attempt.realm_id
         AND event.world_slug = attempt.world_slug
         AND event.playable_state_namespace_id = attempt.playable_state_namespace_id
         AND event.state_scope = attempt.playable_state_scope
         AND event.canonical_game_instance_id = attempt.canonical_game_instance_id
         AND event.canonical_version_id = attempt.canonical_version_id
         AND event.runtime_version_id = attempt.runtime_version_id
         AND event.tenant_id = attempt.game_session_tenant_id
         AND event.game_instance_id = attempt.game_instance_id
         AND event.pointer_version = attempt.pointer_version
         AND event.catalog_revision = attempt.expected_catalog_revision
         AND event.initial_admission_request_id = attempt.initial_admission_request_id
         AND event.control_plane_request_id = attempt.initial_admission_request_id
         AND event.initial_admission_request_digest = attempt.request_digest
         AND event.initial_admission_origin_kind = attempt.origin_kind
         AND event.initial_admission_prior_pointer_version IS NOT DISTINCT FROM attempt.expected_prior_pointer_version
         AND event.initial_admission_active_epoch = attempt.active_lifecycle_epoch
         AND event.initial_admission_hold_id = attempt.hold_id
         AND event.initial_admission_hold_fence = attempt.hold_fence
         AND event.initial_admission_hold_binding_digest = attempt.hold_binding_digest
         AND event.admission_state = 'OPEN'
         AND event.prepared_version_upgrade_id IS NULL
        JOIN game_session_canonical_realm_catalog catalog
          ON catalog.target_namespace = attempt.target_namespace
         AND catalog.canonical_tenant_id = attempt.canonical_tenant_id
         AND catalog.realm_id = attempt.realm_id
         AND catalog.world_slug = attempt.world_slug
         AND catalog.catalog_revision = attempt.expected_catalog_revision
         AND catalog.playable_state_namespace_id = attempt.playable_state_namespace_id
         AND catalog.state_scope = attempt.playable_state_scope
         AND catalog.visible = pointer.visible
         AND catalog.public_production = pointer.public_production_realm
         AND catalog.character_creation_policy = pointer.character_creation_policy
        JOIN game_session_canonical_instance_launch launch
          ON launch.target_namespace = attempt.target_namespace
         AND launch.canonical_tenant_id = attempt.canonical_tenant_id
         AND launch.canonical_realm_id = attempt.realm_id
         AND launch.world_slug = attempt.world_slug
         AND launch.playable_state_namespace_id = attempt.playable_state_namespace_id
         AND launch.playable_state_scope = attempt.playable_state_scope
         AND launch.public_production = TRUE
         AND launch.game_instance_uuid = attempt.canonical_game_instance_id
         AND launch.game_session_tenant_id = attempt.game_session_tenant_id
         AND launch.game_instance_id = attempt.game_instance_id
         AND launch.version_id = attempt.runtime_version_id
         AND launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'canonicalVersionId'
             = attempt.canonical_version_id::text
        JOIN game_instances current
          ON current.tenant_id = launch.game_session_tenant_id
         AND current.id = launch.game_instance_id
         AND current.game_instance_uuid = launch.game_instance_uuid
         AND current.version_id = attempt.runtime_version_id
         AND current.status = 'RUNNING'
        WHERE attempt.target_namespace = selected_namespace
          AND attempt.initial_admission_request_id = selected_request_id
          AND attempt.status = 'COMMITTED'
          AND attempt.pointer_version = CASE
              WHEN attempt.origin_kind = 'NO_PRIOR_POINTER' THEN 1
              ELSE attempt.expected_prior_pointer_version + 1 END
          AND attempt.terminal_at = (pointer.updated_at AT TIME ZONE 'UTC')
          AND attempt.terminal_at = (event.occurred_at AT TIME ZONE 'UTC')
          AND ((attempt.origin_kind = 'NO_PRIOR_POINTER'
                AND attempt.expected_prior_pointer_version IS NULL
                AND attempt.prior_pointer_request_id IS NULL
                AND attempt.prior_pointer_request_digest IS NULL
                AND attempt.prior_pointer_audit_event_id IS NULL
                AND NOT EXISTS (
                    SELECT 1 FROM game_session_canonical_closed_admission_pointer_request origin
                    WHERE origin.target_namespace = attempt.target_namespace
                      AND origin.canonical_tenant_id = attempt.canonical_tenant_id
                      AND origin.realm_id = attempt.realm_id)
                AND NOT EXISTS (
                    SELECT 1 FROM gameplay_admission_pointer other_pointer
                    WHERE other_pointer.id <> pointer.id
                      AND (other_pointer.realm_id = attempt.realm_id
                        OR (other_pointer.target_namespace = attempt.target_namespace
                            AND other_pointer.canonical_tenant_id = attempt.canonical_tenant_id)
                        OR (other_pointer.world_slug = attempt.world_slug
                            AND other_pointer.realm_slug = catalog.realm_slug)
                        OR (other_pointer.tenant_id = attempt.game_session_tenant_id
                            AND other_pointer.game_instance_id = attempt.game_instance_id)))
                AND NOT EXISTS (
                    SELECT 1 FROM gameplay_admission_pointer_event other_event
                    WHERE other_event.id <> event.id
                      AND (other_event.realm_id = attempt.realm_id
                        OR (other_event.target_namespace = attempt.target_namespace
                            AND other_event.canonical_tenant_id = attempt.canonical_tenant_id)
                        OR (other_event.world_slug = attempt.world_slug
                            AND other_event.realm_slug = catalog.realm_slug)
                        OR (other_event.tenant_id = attempt.game_session_tenant_id
                            AND other_event.game_instance_id = attempt.game_instance_id)))
                AND NOT EXISTS (
                    SELECT 1 FROM gameplay_initial_admission_bind_catalog legacy_catalog
                    WHERE legacy_catalog.tenant_id = attempt.game_session_tenant_id
                      AND (legacy_catalog.realm_id = attempt.realm_id
                        OR (legacy_catalog.world_slug = attempt.world_slug
                            AND legacy_catalog.realm_slug = catalog.realm_slug)))
                AND NOT EXISTS (
                    SELECT 1 FROM gameplay_initial_admission_bind_attempt legacy_attempt
                    WHERE legacy_attempt.tenant_id = attempt.game_session_tenant_id
                      AND (legacy_attempt.realm_id = attempt.realm_id
                        OR legacy_attempt.game_instance_id = attempt.game_instance_id)))
            OR (attempt.origin_kind = 'EXPECT_CLOSED'
                AND attempt.expected_prior_pointer_version = 1
                AND attempt.prior_pointer_request_id IS NOT NULL
                AND attempt.prior_pointer_request_digest IS NOT NULL
                AND attempt.prior_pointer_audit_event_id IS NOT NULL
                AND EXISTS (
                    SELECT 1
                    FROM game_session_canonical_closed_admission_pointer_request origin
                    JOIN gameplay_admission_pointer_event origin_event
                      ON origin_event.id = origin.audit_event_id
                     AND origin_event.representation_version = 2
                     AND origin_event.admission_state = 'CLOSED'
                     AND origin_event.pointer_version = 1
                     AND origin_event.target_namespace = origin.target_namespace
                     AND origin_event.canonical_tenant_id = origin.canonical_tenant_id
                     AND origin_event.realm_id = origin.realm_id
                     AND origin_event.catalog_revision = origin.catalog_revision
                     AND origin_event.control_plane_request_id = origin.request_id::text
                    WHERE origin.target_namespace = attempt.target_namespace
                      AND origin.request_id = attempt.prior_pointer_request_id
                      AND origin.canonical_tenant_id = attempt.canonical_tenant_id
                      AND origin.realm_id = attempt.realm_id
                      AND origin.request_digest = attempt.prior_pointer_request_digest
                      AND origin.audit_event_id = attempt.prior_pointer_audit_event_id
                      AND origin.pointer_version = attempt.expected_prior_pointer_version
                      AND origin.catalog_revision = attempt.expected_catalog_revision)))
    ) THEN
        RETURN NULL;
    END IF;

    IF selected_status = 'ABORTED' AND EXISTS (
        SELECT 1
        FROM game_session_canonical_initial_admission_attempt attempt
        JOIN game_session_canonical_realm_catalog catalog
          ON catalog.target_namespace = attempt.target_namespace
         AND catalog.canonical_tenant_id = attempt.canonical_tenant_id
         AND catalog.realm_id = attempt.realm_id
         AND catalog.world_slug = attempt.world_slug
         AND catalog.catalog_revision = attempt.expected_catalog_revision
         AND catalog.playable_state_namespace_id = attempt.playable_state_namespace_id
         AND catalog.state_scope = attempt.playable_state_scope
        WHERE attempt.target_namespace = selected_namespace
          AND attempt.initial_admission_request_id = selected_request_id
          AND attempt.status = 'ABORTED'
          AND attempt.pointer_id IS NULL
          AND attempt.audit_event_id IS NULL
          AND NOT EXISTS (
              SELECT 1 FROM gameplay_admission_pointer pointer
              WHERE pointer.representation_version = 3
                AND pointer.target_namespace = attempt.target_namespace
                AND pointer.initial_admission_request_id = attempt.initial_admission_request_id)
          AND NOT EXISTS (
              SELECT 1 FROM gameplay_admission_pointer_event event
              WHERE event.representation_version = 3
                AND event.target_namespace = attempt.target_namespace
                AND event.initial_admission_request_id = attempt.initial_admission_request_id)
          AND ((attempt.origin_kind = 'NO_PRIOR_POINTER'
                AND NOT EXISTS (
                    SELECT 1 FROM gameplay_admission_pointer pointer
                    WHERE pointer.realm_id = attempt.realm_id
                      OR (pointer.target_namespace = attempt.target_namespace
                          AND pointer.canonical_tenant_id = attempt.canonical_tenant_id)
                      OR (pointer.world_slug = attempt.world_slug
                          AND pointer.realm_slug = catalog.realm_slug)
                      OR (pointer.tenant_id = attempt.game_session_tenant_id
                          AND pointer.game_instance_id = attempt.game_instance_id))
                AND NOT EXISTS (
                    SELECT 1 FROM gameplay_admission_pointer_event event
                    WHERE event.realm_id = attempt.realm_id
                      OR (event.target_namespace = attempt.target_namespace
                          AND event.canonical_tenant_id = attempt.canonical_tenant_id)
                      OR (event.world_slug = attempt.world_slug
                          AND event.realm_slug = catalog.realm_slug)
                      OR (event.tenant_id = attempt.game_session_tenant_id
                          AND event.game_instance_id = attempt.game_instance_id))
                AND NOT EXISTS (
                    SELECT 1 FROM game_session_canonical_closed_admission_pointer_request origin
                    WHERE origin.target_namespace = attempt.target_namespace
                      AND origin.canonical_tenant_id = attempt.canonical_tenant_id
                      AND origin.realm_id = attempt.realm_id)
                AND NOT EXISTS (
                    SELECT 1 FROM gameplay_initial_admission_bind_catalog legacy_catalog
                    WHERE legacy_catalog.tenant_id = attempt.game_session_tenant_id
                      AND (legacy_catalog.realm_id = attempt.realm_id
                        OR (legacy_catalog.world_slug = attempt.world_slug
                            AND legacy_catalog.realm_slug = catalog.realm_slug)))
                AND NOT EXISTS (
                    SELECT 1 FROM gameplay_initial_admission_bind_attempt legacy_attempt
                    WHERE legacy_attempt.tenant_id = attempt.game_session_tenant_id
                      AND (legacy_attempt.realm_id = attempt.realm_id
                        OR legacy_attempt.game_instance_id = attempt.game_instance_id)))
            OR (attempt.origin_kind = 'EXPECT_CLOSED'
                AND attempt.expected_prior_pointer_version = 1
                AND EXISTS (
                    SELECT 1
                    FROM gameplay_admission_pointer pointer
                    JOIN game_session_canonical_closed_admission_pointer_request origin
                      ON origin.target_namespace = attempt.target_namespace
                     AND origin.request_id = attempt.prior_pointer_request_id
                     AND origin.canonical_tenant_id = attempt.canonical_tenant_id
                     AND origin.realm_id = attempt.realm_id
                     AND origin.request_digest = attempt.prior_pointer_request_digest
                     AND origin.audit_event_id = attempt.prior_pointer_audit_event_id
                    JOIN gameplay_admission_pointer_event origin_event
                      ON origin_event.id = origin.audit_event_id
                     AND origin_event.representation_version = 2
                     AND origin_event.admission_state = 'CLOSED'
                     AND origin_event.pointer_version = 1
                     AND origin_event.target_namespace = origin.target_namespace
                     AND origin_event.canonical_tenant_id = origin.canonical_tenant_id
                     AND origin_event.realm_id = origin.realm_id
                     AND origin_event.catalog_revision = origin.catalog_revision
                     AND origin_event.control_plane_request_id = origin.request_id::text
                    WHERE pointer.representation_version = 2
                      AND pointer.target_namespace = attempt.target_namespace
                      AND pointer.canonical_tenant_id = attempt.canonical_tenant_id
                      AND pointer.realm_id = attempt.realm_id
                      AND pointer.pointer_version = attempt.expected_prior_pointer_version
                      AND pointer.catalog_revision = attempt.expected_catalog_revision
                      AND pointer.admission_state = 'CLOSED'
                      AND origin.pointer_version = attempt.expected_prior_pointer_version
                      AND origin.catalog_revision = attempt.expected_catalog_revision)))
    ) THEN
        RETURN NULL;
    END IF;

    RAISE EXCEPTION 'Canonical initial-admission attempt lacks matching atomic pointer/audit or fenced abort evidence'
        USING ERRCODE = '23514';
END;
$$;

CREATE CONSTRAINT TRIGGER gameplay_admission_pointer_initial_admission_atomic
AFTER INSERT OR UPDATE ON gameplay_admission_pointer
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_game_session_canonical_initial_admission_atomic_evidence();
CREATE CONSTRAINT TRIGGER gameplay_admission_pointer_event_initial_admission_atomic
AFTER INSERT ON gameplay_admission_pointer_event
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_game_session_canonical_initial_admission_atomic_evidence();
CREATE CONSTRAINT TRIGGER game_session_initial_admission_attempt_atomic
AFTER INSERT OR UPDATE ON game_session_canonical_initial_admission_attempt
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_game_session_canonical_initial_admission_atomic_evidence();
-- [jooq ignore stop]

-- Persist the exact canonical intent before calling World to acquire the initial-admission hold.
-- The terminal pointer/audit outcome remains exclusively owned by the V17 attempt ledger.
CREATE TABLE game_session_canonical_initial_admission_intent (
    target_namespace character varying(63) NOT NULL,
    initial_admission_request_id character varying(120) NOT NULL,
    request_digest character varying(64) NOT NULL,
    canonical_tenant_id uuid NOT NULL,
    world_slug character varying(120) NOT NULL,
    realm_id uuid NOT NULL,
    playable_state_namespace_id uuid NOT NULL,
    playable_state_scope character varying(16) NOT NULL,
    catalog_visible boolean NOT NULL,
    catalog_public_production boolean NOT NULL,
    catalog_revision bigint NOT NULL,
    catalog_creation_request_id uuid NOT NULL,
    catalog_request_digest character varying(71) NOT NULL,
    catalog_receipt_digest character varying(71) NOT NULL,
    tenant_association_operation_id uuid NOT NULL,
    game_session_tenant_id bigint NOT NULL,
    canonical_game_instance_id uuid NOT NULL,
    game_instance_id bigint NOT NULL,
    canonical_version_id uuid NOT NULL,
    runtime_version_id bigint NOT NULL,
    control_plane_request_id character varying(128) NOT NULL,
    launch_descriptor_id character varying(255) NOT NULL,
    captured_starting_row_version bigint NOT NULL,
    current_row_version bigint NOT NULL,
    descriptor_request_digest character varying(71) NOT NULL,
    descriptor_result_digest character varying(71) NOT NULL,
    release_attestation_digest character varying(71) NOT NULL,
    canonical_hold_request_bytes bytea NOT NULL,
    canonical_lifecycle_request_bytes bytea NOT NULL,
    hold_identity_bytes bytea,
    state character varying(16) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    terminal_at timestamp with time zone,
    CONSTRAINT pk_gs_canonical_initial_admission_intent
        PRIMARY KEY (target_namespace, initial_admission_request_id),
    CONSTRAINT fk_gs_initial_admission_intent_catalog
        FOREIGN KEY (target_namespace, realm_id)
        REFERENCES game_session_canonical_realm_catalog (target_namespace, realm_id),
    CONSTRAINT fk_gs_initial_admission_intent_launch
        FOREIGN KEY (target_namespace, canonical_game_instance_id)
        REFERENCES game_session_canonical_instance_launch (target_namespace, game_instance_uuid),
    CONSTRAINT chk_gs_initial_admission_intent_identity CHECK (
        octet_length(target_namespace) BETWEEN 1 AND 63
        AND target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
        AND char_length(initial_admission_request_id) BETWEEN 1 AND 120
        AND initial_admission_request_id !~ '^[[:space:]]*$'
        AND initial_admission_request_id !~ '[[:cntrl:]]'
        AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND realm_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND catalog_creation_request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_association_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND canonical_game_instance_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND game_session_tenant_id > 0
        AND game_instance_id > 0
        AND runtime_version_id > 0
        AND catalog_revision > 0
        AND captured_starting_row_version >= 0
        AND current_row_version >= captured_starting_row_version
        AND playable_state_scope = 'SHARED'
        AND catalog_visible IS TRUE
        AND catalog_public_production IS TRUE),
    CONSTRAINT chk_gs_initial_admission_intent_digests CHECK (
        request_digest ~ '^[0-9a-f]{64}$'
        AND catalog_request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND catalog_receipt_digest ~ '^sha256:[0-9a-f]{64}$'
        AND descriptor_request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND descriptor_result_digest ~ '^sha256:[0-9a-f]{64}$'
        AND release_attestation_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_gs_initial_admission_intent_request_bytes CHECK (
        octet_length(canonical_hold_request_bytes) BETWEEN 1 AND 16384
        AND octet_length(canonical_lifecycle_request_bytes) BETWEEN 1 AND 16384
        AND (hold_identity_bytes IS NULL
            OR octet_length(hold_identity_bytes) BETWEEN 1 AND 65536)),
    CONSTRAINT chk_gs_initial_admission_intent_state CHECK (
        (state = 'PENDING_HOLD'
            AND hold_identity_bytes IS NULL
            AND terminal_at IS NULL)
        OR (state = 'HOLD_ATTACHED'
            AND hold_identity_bytes IS NOT NULL
            AND terminal_at IS NULL)
        OR (state = 'TERMINAL'
            AND hold_identity_bytes IS NOT NULL
            AND terminal_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_gs_canonical_initial_admission_pending_intent_realm
    ON game_session_canonical_initial_admission_intent
       (target_namespace, canonical_tenant_id, realm_id)
    WHERE state IN ('PENDING_HOLD', 'HOLD_ATTACHED');
