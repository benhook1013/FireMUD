-- Typed terminal outcomes retain the complete authenticated Game Session proof separately from
-- the legacy bare digest columns. Retained V23 rows stay untyped and receive no inferred proof.
ALTER TABLE initial_admission_bind_hold
    ADD COLUMN canonical_owner_proof_bytes BYTEA,
    ADD COLUMN canonical_owner_proof_digest VARCHAR(71),
    ADD CONSTRAINT ck_initial_admission_bind_canonical_owner_proof CHECK (
        (
            canonical_request_bytes IS NULL
            AND canonical_owner_proof_bytes IS NULL
            AND canonical_owner_proof_digest IS NULL
        )
        OR
        (
            canonical_request_bytes IS NOT NULL
            AND (
                (
                    status = 'PENDING'
                    AND canonical_owner_proof_bytes IS NULL
                    AND canonical_owner_proof_digest IS NULL
                )
                OR
                (
                    status IN ('COMMITTED', 'ABORTED')
                    AND canonical_owner_proof_bytes IS NOT NULL
                    AND octet_length(canonical_owner_proof_bytes) > 0
                    AND canonical_owner_proof_digest IS NOT NULL
                    AND canonical_owner_proof_digest COLLATE "C" ~ '^sha256:[0-9a-f]{64}$'
                )
            )
        )
    );

-- This transaction-local, exact-row manifest is not owner proof and is never public storage.
CREATE TABLE world_canonical_initial_admission_hold_finalization_manifest (
    transaction_id BIGINT NOT NULL,
    hold_id UUID NOT NULL,
    hold_fence UUID NOT NULL,
    expected_old JSONB NOT NULL,
    expected_new JSONB NOT NULL,
    PRIMARY KEY (transaction_id, hold_id)
);

-- [jooq ignore start]
REVOKE ALL ON world_canonical_initial_admission_hold_finalization_manifest FROM PUBLIC;

CREATE FUNCTION world_consume_canonical_initial_admission_hold_finalization_manifest(
    actual_operation TEXT,
    actual_old JSONB,
    actual_new JSONB
) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    consumed UUID;
BEGIN
    IF actual_operation IS DISTINCT FROM 'UPDATE'
        OR actual_old IS NULL
        OR actual_new IS NULL
        OR actual_old->>'status' IS DISTINCT FROM 'PENDING'
        OR (actual_new->>'status' IS DISTINCT FROM 'COMMITTED'
            AND actual_new->>'status' IS DISTINCT FROM 'ABORTED') THEN
        RETURN FALSE;
    END IF;

    DELETE FROM "${serviceSchema}".world_canonical_initial_admission_hold_finalization_manifest m
    WHERE m.transaction_id = txid_current()
        AND m.hold_id::TEXT = actual_old->>'hold_id'
        AND m.hold_id::TEXT = actual_new->>'hold_id'
        AND m.hold_fence::TEXT = actual_old->>'hold_fence'
        AND m.hold_fence::TEXT = actual_new->>'hold_fence'
        AND m.expected_old = actual_old
        AND m.expected_new = actual_new
        AND actual_old->>'canonical_request_bytes' IS NOT NULL
        AND actual_new->>'canonical_request_bytes' = actual_old->>'canonical_request_bytes'
        AND actual_new->>'hold_binding_digest' = actual_old->>'hold_binding_digest'
        AND actual_new->>'canonical_owner_proof_bytes' IS NOT NULL
        AND actual_new->>'canonical_owner_proof_digest' IS NOT NULL
    RETURNING m.hold_id INTO consumed;
    RETURN consumed IS NOT NULL;
END;
$$;
REVOKE ALL ON FUNCTION
    world_consume_canonical_initial_admission_hold_finalization_manifest(TEXT, JSONB, JSONB)
    FROM PUBLIC;

CREATE FUNCTION world_finalize_canonical_initial_admission_hold(
    p_hold_id UUID,
    p_hold_fence UUID,
    p_status TEXT,
    p_owner_proof_id TEXT,
    p_owner_proof_digest TEXT,
    p_owner_pointer_audit_id TEXT,
    p_owner_pointer_version BIGINT,
    p_terminal_at TIMESTAMPTZ,
    p_positive_durable_abort BOOLEAN,
    p_canonical_owner_proof_bytes BYTEA,
    p_canonical_owner_proof_digest TEXT
) RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    candidate "${serviceSchema}".initial_admission_bind_hold%ROWTYPE;
    held "${serviceSchema}".initial_admission_bind_hold%ROWTYPE;
    next_held "${serviceSchema}".initial_admission_bind_hold%ROWTYPE;
    association_row "${serviceSchema}".world_canonical_instance_association%ROWTYPE;
    instance_row "${serviceSchema}".world_instance%ROWTYPE;
    updated_count INTEGER;
BEGIN
    IF p_status NOT IN ('COMMITTED', 'ABORTED')
        OR p_terminal_at IS NULL
        OR p_canonical_owner_proof_bytes IS NULL
        OR octet_length(p_canonical_owner_proof_bytes) = 0
        OR p_canonical_owner_proof_digest !~ '^sha256:[0-9a-f]{64}$'
        OR p_canonical_owner_proof_digest IS DISTINCT FROM
            ('sha256:' || encode(sha256(p_canonical_owner_proof_bytes), 'hex')) THEN
        RAISE EXCEPTION 'Canonical initial-admission terminal proof is incomplete or has an invalid digest'
            USING ERRCODE = '23514';
    END IF;

    -- Read the immutable selector without locking, then preserve acquisition order by locking
    -- the actual World lifecycle row before the hold itself.
    SELECT h.* INTO candidate
    FROM "${serviceSchema}".initial_admission_bind_hold h
    WHERE h.hold_id = p_hold_id;
    IF NOT FOUND
        OR candidate.hold_fence IS DISTINCT FROM p_hold_fence
        OR candidate.canonical_request_bytes IS NULL THEN
        RAISE EXCEPTION 'Canonical initial-admission hold identity is missing or untyped'
            USING ERRCODE = '55000';
    END IF;

    SELECT a.* INTO association_row
    FROM "${serviceSchema}".world_canonical_instance_association a
    JOIN "${serviceSchema}".world_instance wi ON wi.id = a.world_instance_id
    WHERE wi.tenant_id = candidate.tenant_id
        AND wi.game_instance_id = candidate.game_instance_id
        AND wi.version_id = candidate.version_id
        AND a.local_tenant_key = candidate.tenant_id
        AND a.private_game_instance_key = candidate.game_instance_id
        AND a.local_version_key = candidate.version_id
        AND a.canonical_target_namespace = candidate.canonical_target_namespace
        AND a.canonical_tenant_id = candidate.canonical_tenant_id
        AND a.canonical_world_slug = candidate.canonical_world_slug
        AND a.canonical_game_instance_id = candidate.canonical_game_instance_id
        AND a.canonical_version_id = candidate.canonical_version_id
        AND a.playable_state_namespace_id = candidate.playable_state_namespace_uuid
        AND a.playable_state_scope = candidate.playable_state_scope
    FOR UPDATE OF wi;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Canonical initial-admission terminalization lacks its exact World association'
            USING ERRCODE = '23514';
    END IF;

    -- Keep each composite result in one ROWTYPE target. The preceding query already holds
    -- this actual lifecycle row; the second read retains that lock and its exact binding.
    SELECT wi.* INTO instance_row
    FROM "${serviceSchema}".world_instance wi
    WHERE wi.id = association_row.world_instance_id
    FOR UPDATE;
    IF NOT FOUND
        OR instance_row.canonical_game_instance_id IS DISTINCT FROM association_row.canonical_game_instance_id
        OR instance_row.canonical_target_namespace IS DISTINCT FROM association_row.canonical_target_namespace
        OR instance_row.canonical_tenant_id IS DISTINCT FROM association_row.canonical_tenant_id
        OR instance_row.canonical_world_slug IS DISTINCT FROM association_row.canonical_world_slug
        OR instance_row.playable_state_namespace_id IS DISTINCT FROM association_row.playable_state_namespace_id
        OR instance_row.playable_state_scope IS DISTINCT FROM association_row.playable_state_scope
        OR instance_row.status IS DISTINCT FROM 'ACTIVE'
        OR instance_row.lifecycle_epoch IS DISTINCT FROM candidate.active_lifecycle_epoch THEN
        RAISE EXCEPTION 'Canonical initial-admission terminalization lacks its exact ACTIVE World association epoch'
            USING ERRCODE = '23514';
    END IF;

    SELECT h.* INTO held
    FROM "${serviceSchema}".initial_admission_bind_hold h
    WHERE h.hold_id = p_hold_id
    FOR UPDATE;
    IF NOT FOUND
        OR held.hold_fence IS DISTINCT FROM candidate.hold_fence
        OR held.tenant_id IS DISTINCT FROM candidate.tenant_id
        OR held.realm_uuid IS DISTINCT FROM candidate.realm_uuid
        OR held.playable_state_namespace_uuid IS DISTINCT FROM candidate.playable_state_namespace_uuid
        OR held.playable_state_scope IS DISTINCT FROM candidate.playable_state_scope
        OR held.game_instance_id IS DISTINCT FROM candidate.game_instance_id
        OR held.version_id IS DISTINCT FROM candidate.version_id
        OR held.active_lifecycle_epoch IS DISTINCT FROM candidate.active_lifecycle_epoch
        OR held.initial_admission_request_id IS DISTINCT FROM candidate.initial_admission_request_id
        OR held.request_digest IS DISTINCT FROM candidate.request_digest
        OR held.expected_no_prior_pointer IS DISTINCT FROM candidate.expected_no_prior_pointer
        OR held.expected_catalog_revision IS DISTINCT FROM candidate.expected_catalog_revision
        OR held.canonical_target_namespace IS DISTINCT FROM candidate.canonical_target_namespace
        OR held.canonical_tenant_id IS DISTINCT FROM candidate.canonical_tenant_id
        OR held.canonical_world_slug IS DISTINCT FROM candidate.canonical_world_slug
        OR held.canonical_game_instance_id IS DISTINCT FROM candidate.canonical_game_instance_id
        OR held.canonical_version_id IS DISTINCT FROM candidate.canonical_version_id
        OR held.initial_admission_origin IS DISTINCT FROM candidate.initial_admission_origin
        OR held.expected_prior_pointer_version IS DISTINCT FROM candidate.expected_prior_pointer_version
        OR held.canonical_request_bytes IS DISTINCT FROM candidate.canonical_request_bytes
        OR held.hold_binding_digest IS DISTINCT FROM candidate.hold_binding_digest
        OR held.status IS DISTINCT FROM 'PENDING'
        OR held.canonical_owner_proof_bytes IS NOT NULL
        OR held.canonical_owner_proof_digest IS NOT NULL
        OR held.owner_proof_id IS NOT NULL
        OR held.owner_proof_digest IS NOT NULL
        OR held.owner_pointer_audit_id IS NOT NULL
        OR held.owner_pointer_version IS NOT NULL
        OR held.terminal_at IS NOT NULL
        OR held.row_version = 9223372036854775807 THEN
        RAISE EXCEPTION 'Canonical initial-admission hold is not the exact terminalizable PENDING row'
            USING ERRCODE = '55000';
    END IF;

    IF p_owner_proof_id IS DISTINCT FROM held.initial_admission_request_id
        OR p_owner_proof_digest !~ '^[0-9a-f]{64}$'
        OR p_owner_proof_digest IS NULL THEN
        RAISE EXCEPTION 'Canonical initial-admission owner proof differs from its original request identity'
            USING ERRCODE = '23514';
    END IF;

    IF p_status = 'COMMITTED' THEN
        IF p_positive_durable_abort IS DISTINCT FROM FALSE
            OR p_owner_pointer_version IS NULL
            OR p_owner_pointer_version <= 0
            OR p_owner_pointer_audit_id IS NULL
            OR p_owner_pointer_audit_id !~ '^[1-9][0-9]*$'
            OR p_owner_pointer_audit_id::NUMERIC > 9223372036854775807 THEN
            RAISE EXCEPTION 'Committed canonical initial-admission proof has invalid pointer or audit evidence'
                USING ERRCODE = '23514';
        END IF;
        IF held.initial_admission_origin = 'NO_PRIOR_POINTER' THEN
            IF p_owner_pointer_version IS DISTINCT FROM 1 THEN
                RAISE EXCEPTION 'First canonical initial-admission pointer version must be one'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF held.initial_admission_origin = 'EXPECT_CLOSED' THEN
            IF held.expected_prior_pointer_version = 9223372036854775807
                OR p_owner_pointer_version IS DISTINCT FROM held.expected_prior_pointer_version + 1 THEN
                RAISE EXCEPTION 'Committed canonical initial-admission pointer is not the exact next version'
                    USING ERRCODE = '23514';
            END IF;
        ELSE
            RAISE EXCEPTION 'Canonical initial-admission origin is unsupported'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        IF p_positive_durable_abort IS DISTINCT FROM TRUE
            OR p_owner_pointer_audit_id IS NOT NULL
            OR p_owner_pointer_version IS NOT NULL THEN
            RAISE EXCEPTION 'Aborted canonical initial-admission proof lacks positive durable fencing'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    next_held := held;
    next_held.status := p_status;
    next_held.owner_proof_id := p_owner_proof_id;
    next_held.owner_proof_digest := p_owner_proof_digest;
    next_held.owner_pointer_audit_id := p_owner_pointer_audit_id;
    next_held.owner_pointer_version := p_owner_pointer_version;
    next_held.terminal_at := p_terminal_at AT TIME ZONE 'UTC';
    next_held.canonical_owner_proof_bytes := p_canonical_owner_proof_bytes;
    next_held.canonical_owner_proof_digest := p_canonical_owner_proof_digest;
    next_held.updated_at := CURRENT_TIMESTAMP;
    next_held.row_version := held.row_version + 1;

    INSERT INTO "${serviceSchema}".world_canonical_initial_admission_hold_finalization_manifest
        (transaction_id, hold_id, hold_fence, expected_old, expected_new)
    VALUES (txid_current(), held.hold_id, held.hold_fence, to_jsonb(held), to_jsonb(next_held));

    UPDATE "${serviceSchema}".initial_admission_bind_hold h
    SET status = next_held.status,
        owner_proof_id = next_held.owner_proof_id,
        owner_proof_digest = next_held.owner_proof_digest,
        owner_pointer_audit_id = next_held.owner_pointer_audit_id,
        owner_pointer_version = next_held.owner_pointer_version,
        terminal_at = next_held.terminal_at,
        canonical_owner_proof_bytes = next_held.canonical_owner_proof_bytes,
        canonical_owner_proof_digest = next_held.canonical_owner_proof_digest,
        updated_at = next_held.updated_at,
        row_version = next_held.row_version
    WHERE h.hold_id = held.hold_id
        AND h.hold_fence = held.hold_fence
        AND h.status = 'PENDING'
        AND h.row_version = held.row_version;
    GET DIAGNOSTICS updated_count = ROW_COUNT;
    IF updated_count <> 1 THEN
        RAISE EXCEPTION 'Canonical initial-admission hold terminal compare-and-set did not update one row'
            USING ERRCODE = '40001';
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION world_finalize_canonical_initial_admission_hold(
    UUID, UUID, TEXT, TEXT, TEXT, TEXT, BIGINT, TIMESTAMPTZ, BOOLEAN, BYTEA, TEXT
) FROM PUBLIC;

-- Replace only V47's typed-update denial branch. Immutable acquisition checks and legacy
-- lifecycle behavior remain in the original trigger function definition.
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$        IF OLD.canonical_request_bytes IS NOT NULL
            AND (OLD.status IS DISTINCT FROM NEW.status
                OR OLD.owner_proof_id IS DISTINCT FROM NEW.owner_proof_id
                OR OLD.owner_proof_digest IS DISTINCT FROM NEW.owner_proof_digest
                OR OLD.owner_pointer_audit_id IS DISTINCT FROM NEW.owner_pointer_audit_id
                OR OLD.owner_pointer_version IS DISTINCT FROM NEW.owner_pointer_version
                OR OLD.terminal_at IS DISTINCT FROM NEW.terminal_at) THEN
            RAISE EXCEPTION 'Canonical initial-admission hold requires its deferred typed owner outcome'
                USING ERRCODE = '55000';
        END IF;
        IF OLD.canonical_request_bytes IS NOT NULL THEN
            RAISE EXCEPTION 'Canonical initial-admission hold lifecycle updates are not enabled'
                USING ERRCODE = '55000';
        END IF;$anchor$;
    replacement TEXT := $replacement$        IF OLD.canonical_request_bytes IS NOT NULL THEN
            IF "${serviceSchema}".world_consume_canonical_initial_admission_hold_finalization_manifest(
                TG_OP, to_jsonb(OLD), to_jsonb(NEW)) THEN
                RETURN NEW;
            END IF;
            RAISE EXCEPTION 'Canonical initial-admission hold update lacks its exact one-use owner manifest'
                USING ERRCODE = '55000';
        END IF;$replacement$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_guard_canonical_initial_admission_hold()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one canonical initial-admission typed update denial';
    END IF;
    EXECUTE replace(original, anchor, replacement);
END;
$migration$;
-- [jooq ignore stop]
