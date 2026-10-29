-- Positive references to original local operations whose physical capacity has not retired.
-- This is a bounded current projection over the existing inventory, not a new inventory authority.
-- Immutable operations, releases, mutations, retirement receipts and original bytes stay intact.
CREATE TABLE game_session_canonical_issuer_current_local_operation (
    operation_id uuid NOT NULL,
    issuer_id character varying(64) NOT NULL,
    anchor_bootstrap_operation_id uuid NOT NULL,
    partition_id numeric NOT NULL,
    CONSTRAINT pk_gs_issuer_current_local_operation PRIMARY KEY (operation_id),
    CONSTRAINT fk_gs_issuer_current_local_operation_original
        FOREIGN KEY (operation_id)
        REFERENCES game_session_canonical_issuer_binding_local_operation (operation_id),
    CONSTRAINT chk_gs_issuer_current_local_operation CHECK (
        issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C"
        AND anchor_bootstrap_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND partition_id >= 0 AND partition_id = floor(partition_id))
);
CREATE INDEX ix_gs_issuer_current_local_operation_partition
    ON game_session_canonical_issuer_current_local_operation
        (issuer_id, anchor_bootstrap_operation_id, partition_id);

-- All point checks below start at an existing primary/unique key. In particular, retirement
-- lookup starts at the unique release.original_local_operation_id and retirement.release_operation_id;
-- it never searches the accumulated retirement history by status.
-- [jooq ignore start]
CREATE FUNCTION gs_issuer_local_operation_has_exact_retirement(original_id uuid)
RETURNS boolean
LANGUAGE sql
AS $$
    SELECT EXISTS (
        SELECT 1
          FROM game_session_canonical_issuer_binding_local_operation original
          JOIN game_session_canonical_issuer_binding_local_release_operation release
            ON release.original_local_operation_id = original.operation_id
          JOIN game_session_canonical_issuer_binding_local_retirement retirement
            ON retirement.release_operation_id = release.operation_id
          JOIN game_session_canonical_issuer_partition_mutation mutation
            ON mutation.mutation_id = retirement.physical_mutation_id
          JOIN game_session_canonical_issuer_partition_reservation reservation
            ON reservation.reservation_id = original.reservation_id
         WHERE original.operation_id = original_id
           AND release.status = 'RELEASED'
           AND retirement.status = 'RETIRED'
           AND mutation.status = 'COMMITTED'
           AND reservation.lifecycle = 'RELEASED'
           AND ROW(retirement.issuer_id, retirement.partition_id,
                   retirement.original_local_operation_id, retirement.transition_id,
                   retirement.reservation_id, retirement.binding_ref,
                   retirement.original_reservation_fence,
                   retirement.original_issuer_coverage_operation_id,
                   retirement.original_issuer_coverage_operation_fence,
                   retirement.original_coverage_fence,
                   retirement.original_inventory_snapshot_revision,
                   retirement.original_reservation_inventory_revision,
                   retirement.anchor_issuer_coverage_operation_id,
                   retirement.anchor_operation_fence, retirement.anchor_coverage_fence,
                   retirement.anchor_inventory_snapshot_revision,
                   retirement.original_member_value_bytes, retirement.original_member_value_digest)
               IS NOT DISTINCT FROM
               ROW(original.issuer_id, original.partition_id, original.operation_id,
                   original.transition_id, original.reservation_id, original.binding_ref,
                   original.reservation_fence, original.original_issuer_coverage_operation_id,
                   original.original_issuer_coverage_operation_fence,
                   original.original_coverage_fence, original.original_inventory_snapshot_revision,
                   original.local_inventory_snapshot_revision, original.anchor_issuer_coverage_operation_id,
                   original.anchor_operation_fence, original.anchor_coverage_fence,
                   original.anchor_issuer_inventory_snapshot_revision,
                   original.member_value_bytes, original.member_value_digest)
           AND ROW(release.issuer_id, release.partition_id, release.original_local_operation_id,
                   release.transition_id, release.reservation_id, release.binding_ref,
                   release.original_reservation_fence, release.original_issuer_coverage_operation_id,
                   release.original_issuer_coverage_operation_fence, release.original_coverage_fence,
                   release.original_inventory_snapshot_revision,
                   release.original_reservation_inventory_revision,
                   release.anchor_issuer_coverage_operation_id, release.anchor_operation_fence,
                   release.anchor_coverage_fence, release.anchor_inventory_snapshot_revision,
                   release.source_inventory_revision, release.completion_inventory_revision,
                   release.original_member_value_bytes, release.original_member_value_digest,
                   release.released_member_value_bytes, release.released_member_value_digest)
               IS NOT DISTINCT FROM
               ROW(retirement.issuer_id, retirement.partition_id, retirement.original_local_operation_id,
                   retirement.transition_id, retirement.reservation_id, retirement.binding_ref,
                   retirement.original_reservation_fence, retirement.original_issuer_coverage_operation_id,
                   retirement.original_issuer_coverage_operation_fence, retirement.original_coverage_fence,
                   retirement.original_inventory_snapshot_revision,
                   retirement.original_reservation_inventory_revision,
                   retirement.anchor_issuer_coverage_operation_id, retirement.anchor_operation_fence,
                   retirement.anchor_coverage_fence, retirement.anchor_inventory_snapshot_revision,
                   retirement.release_source_inventory_revision,
                   retirement.release_completion_inventory_revision,
                   retirement.original_member_value_bytes, retirement.original_member_value_digest,
                   retirement.released_member_value_bytes, retirement.released_member_value_digest)
           AND reservation.inventory_revision = release.completion_inventory_revision
           AND mutation.issuer_id = original.issuer_id
           AND mutation.bootstrap_operation_id = original.anchor_bootstrap_operation_id
           AND mutation.partition_id = original.partition_id
           AND mutation.mutation_kind = 'RETIRE'
           AND mutation.owner_operation_id = retirement.retirement_operation_id
           AND mutation.expected_revision = retirement.expected_revision
           AND mutation.successor_revision = retirement.successor_revision
           AND mutation.exact_intended_change_bytes = retirement.exact_intended_change_bytes
           AND mutation.exact_intended_change_digest = retirement.exact_intended_change_digest
           AND mutation.exact_readback_field_set_bytes = retirement.exact_absence_readback_field_set_bytes
           AND mutation.exact_readback_field_set_digest = retirement.exact_absence_readback_field_set_digest
           AND retirement.completed_at IS NOT NULL
           AND retirement.exact_absence_readback_field_set_bytes IS NOT NULL
           AND retirement.exact_absence_readback_field_set_digest IS NOT NULL);
$$;

CREATE FUNCTION gs_issuer_local_operation_has_exact_source(original_id uuid)
RETURNS boolean
LANGUAGE sql
AS $$
    SELECT EXISTS (
        SELECT 1
          FROM game_session_canonical_issuer_binding_local_operation original
          JOIN game_session_canonical_issuer_installed_anchor anchor
            ON anchor.issuer_id = original.issuer_id
           AND anchor.bootstrap_operation_id = original.anchor_bootstrap_operation_id
          JOIN game_session_canonical_issuer_partition_reservation reservation
            ON reservation.reservation_id = original.reservation_id
          JOIN game_session_canonical_gameplay_binding_inventory candidate
            ON candidate.binding_ref = original.binding_ref
         WHERE original.operation_id = original_id
           AND ROW(original.anchor_bootstrap_request_id, original.anchor_build_operation_id,
                   original.anchor_build_plan_digest, original.anchor_control_epoch,
                   original.anchor_issuer_coverage_operation_id, original.anchor_operation_fence,
                   original.anchor_coverage_fence, original.anchor_issuer_inventory_snapshot_revision,
                   original.anchor_layout_version, original.anchor_partition_count,
                   original.anchor_partition_capacity, original.anchor_mapping_identity,
                   original.anchor_layout_descriptor_bytes, original.anchor_layout_descriptor_digest,
                   original.anchor_issuer_auth_generation, original.anchor_source_checkpoint_bytes,
                   original.anchor_source_checkpoint_digest, original.anchor_source_event_bytes,
                   original.anchor_source_event_digest, original.anchor_incarnation_bytes,
                   original.anchor_incarnation_digest, original.exact_anchor_bytes, original.exact_anchor_digest)
               IS NOT DISTINCT FROM
               ROW(anchor.bootstrap_request_id, anchor.build_operation_id, anchor.build_plan_digest,
                   anchor.control_epoch, anchor.issuer_coverage_operation_id, anchor.operation_fence,
                   anchor.coverage_fence, anchor.issuer_inventory_snapshot_revision,
                   anchor.layout_version, anchor.partition_count, anchor.partition_capacity,
                   anchor.mapping_identity, anchor.layout_descriptor_bytes, anchor.layout_descriptor_digest,
                   anchor.issuer_auth_generation, anchor.source_checkpoint_bytes,
                   anchor.source_checkpoint_digest, anchor.source_event_bytes,
                   anchor.source_event_digest, anchor.incarnation_bytes,
                   anchor.incarnation_digest, anchor.exact_anchor_bytes, anchor.exact_anchor_digest)
           AND ROW(reservation.issuer_id, reservation.transition_id, reservation.binding_ref,
                   reservation.binding_generation, reservation.reservation_fence,
                   reservation.partition_id, reservation.issuer_index_layout_version,
                   reservation.issuer_index_partition_count, reservation.issuer_index_partition_capacity,
                   reservation.issuer_auth_generation, reservation.issuer_coverage_operation_id,
                   reservation.issuer_coverage_operation_fence, reservation.coverage_fence,
                   reservation.inventory_snapshot_revision)
               IS NOT DISTINCT FROM
               ROW(original.issuer_id, original.transition_id, original.binding_ref,
                   original.binding_generation, original.reservation_fence, original.partition_id,
                   original.anchor_layout_version, original.anchor_partition_count,
                   original.anchor_partition_capacity, original.anchor_issuer_auth_generation,
                   original.original_issuer_coverage_operation_id,
                   original.original_issuer_coverage_operation_fence, original.original_coverage_fence,
                   original.original_inventory_snapshot_revision)
           AND ROW(reservation.account_id, reservation.tenant_id, reservation.playable_state_namespace_id,
                   reservation.character_id, reservation.playable_state_scope, reservation.game_instance_id,
                   reservation.runtime_game_instance_id, reservation.session_id,
                   reservation.region_id, reservation.region_epoch)
               IS NOT DISTINCT FROM
               ROW(candidate.account_id, candidate.tenant_id, candidate.playable_state_namespace_id,
                   candidate.character_id, candidate.playable_state_scope, candidate.game_instance_id,
                   candidate.runtime_game_instance_id, candidate.session_id,
                   candidate.region_id, candidate.region_epoch)
           AND candidate.issuer_reservation_id = original.reservation_id
           AND candidate.transition_id = original.transition_id
           AND candidate.binding_generation = original.binding_generation
           AND candidate.account_index_fence = original.candidate_account_index_fence
           -- The original revision is frozen. Abort/release advance the mutable reservation revision;
           -- equating it to the original here would reject legitimate cleanup and retained upgrades.
           AND reservation.inventory_revision >= original.local_inventory_snapshot_revision);
$$;

-- Use the owner's existing inventory -> issuer -> account serialization. Statement triggers take
-- the clock before a new row lock; they neither advance the clock nor allocate another fence.
CREATE FUNCTION gs_lock_issuer_current_membership_clock()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM singleton_id FROM game_session_canonical_binding_inventory_clock
     WHERE singleton_id = 1 FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Issuer current membership requires the existing inventory clock'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION gs_require_issuer_reservation_current_membership(reservation_identity uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    reservation game_session_canonical_issuer_partition_reservation%ROWTYPE;
    anchor game_session_canonical_issuer_installed_anchor%ROWTYPE;
    original game_session_canonical_issuer_binding_local_operation%ROWTYPE;
BEGIN
    SELECT * INTO STRICT reservation
      FROM game_session_canonical_issuer_partition_reservation
     WHERE reservation_id = reservation_identity;
    SELECT * INTO anchor FROM game_session_canonical_issuer_installed_anchor
     WHERE issuer_id = reservation.issuer_id;
    IF NOT FOUND THEN
        -- Genuine initial inventory preparation precedes the first positive installed anchor.
        RETURN;
    END IF;
    SELECT * INTO original FROM game_session_canonical_issuer_binding_local_operation
     WHERE reservation_id = reservation_identity;
    IF FOUND THEN
        IF original.anchor_bootstrap_operation_id <> anchor.bootstrap_operation_id
           OR NOT gs_issuer_local_operation_has_exact_source(original.operation_id) THEN
            RAISE EXCEPTION 'Issuer reservation differs from its exact installed local-operation source'
                USING ERRCODE = '23514';
        END IF;
        IF gs_issuer_local_operation_has_exact_retirement(original.operation_id) THEN
            IF EXISTS (SELECT 1 FROM game_session_canonical_issuer_current_local_operation current
                        WHERE current.operation_id = original.operation_id) THEN
                RAISE EXCEPTION 'Retired issuer operation cannot remain in current membership'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF NOT EXISTS (
            SELECT 1 FROM game_session_canonical_issuer_current_local_operation current
             WHERE current.operation_id = original.operation_id
               AND current.issuer_id = original.issuer_id
               AND current.anchor_bootstrap_operation_id = anchor.bootstrap_operation_id
               AND current.partition_id = original.partition_id) THEN
            RAISE EXCEPTION 'Unretired issuer reservation requires its exact current local operation'
                USING ERRCODE = '23514';
        END IF;
        RETURN;
    END IF;
    -- Bootstrap source keys are reservation UUID text, unique within the exact installed build.
    -- Preserve the complete original identity, owner, fences, layout and creation timestamp.
    -- Lifecycle and inventory revision are mutable under their existing owner guards; a qualified
    -- release must not lose baseline membership merely because it advances those two columns.
    IF EXISTS (
        SELECT 1 FROM game_session_canonical_issuer_partition_build_cohort_row cohort
         WHERE cohort.build_operation_id = anchor.build_operation_id
           AND cohort.source_operation_id = anchor.bootstrap_operation_id
           AND cohort.issuer_id = anchor.issuer_id
           AND cohort.source_kind = 'RESERVATION'
           AND cohort.source_key = reservation_identity::text
           AND cohort.source_inventory_revision <= reservation.inventory_revision
           -- Session rendering is not source identity: compare timestamp and binary values natively.
           AND (cohort.source_row_json::jsonb ->> 'binding_ref')::bytea = reservation.binding_ref
           AND (cohort.source_row_json::jsonb ->> 'created_at')::timestamptz = reservation.created_at
           AND (cohort.source_row_json::jsonb - 'lifecycle' - 'inventory_revision'
                - 'binding_ref' - 'created_at')
               = (to_jsonb(reservation) - 'lifecycle' - 'inventory_revision'
                  - 'binding_ref' - 'created_at')) THEN
        RETURN;
    END IF;
    RAISE EXCEPTION 'Installed issuer reservation requires exact bootstrap or current local-operation membership'
        USING ERRCODE = '23514';
END;
$$;

-- One-time populated upgrade: inspect retained provenance before enabling ongoing guards, without
-- modifying any original row or temporarily disabling an existing retention trigger.
DO $$
DECLARE
    original record;
    retained record;
BEGIN
    PERFORM singleton_id FROM game_session_canonical_binding_inventory_clock
     WHERE singleton_id = 1 FOR UPDATE;
    FOR original IN SELECT operation_id FROM game_session_canonical_issuer_binding_local_operation LOOP
        IF NOT gs_issuer_local_operation_has_exact_source(original.operation_id) THEN
            RAISE EXCEPTION 'Retained issuer local operation has ambiguous original source: %', original.operation_id
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    FOR retained IN
        SELECT retirement.original_local_operation_id
          FROM game_session_canonical_issuer_binding_local_retirement retirement
         WHERE retirement.status = 'RETIRED'
    LOOP
        IF NOT gs_issuer_local_operation_has_exact_retirement(retained.original_local_operation_id) THEN
            RAISE EXCEPTION 'Retained issuer retirement lacks exact committed original proof: %', retained.original_local_operation_id
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
END;
$$;

INSERT INTO game_session_canonical_issuer_current_local_operation
    (operation_id, issuer_id, anchor_bootstrap_operation_id, partition_id)
SELECT original.operation_id, original.issuer_id, original.anchor_bootstrap_operation_id, original.partition_id
  FROM game_session_canonical_issuer_binding_local_operation original
 WHERE NOT gs_issuer_local_operation_has_exact_retirement(original.operation_id);

DO $$
DECLARE
    retained record;
BEGIN
    FOR retained IN SELECT reservation_id FROM game_session_canonical_issuer_partition_reservation LOOP
        PERFORM gs_require_issuer_reservation_current_membership(retained.reservation_id);
    END LOOP;
END;
$$;

CREATE FUNCTION gs_guard_issuer_current_local_operation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'Issuer current membership identity cannot be updated or truncated'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (
            SELECT 1 FROM game_session_canonical_issuer_binding_local_operation original
             WHERE original.operation_id = NEW.operation_id
               AND original.issuer_id = NEW.issuer_id
               AND original.anchor_bootstrap_operation_id = NEW.anchor_bootstrap_operation_id
               AND original.partition_id = NEW.partition_id)
           OR NOT gs_issuer_local_operation_has_exact_source(NEW.operation_id)
           OR gs_issuer_local_operation_has_exact_retirement(NEW.operation_id) THEN
            RAISE EXCEPTION 'Issuer current membership requires its exact unretired immutable source'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NOT gs_issuer_local_operation_has_exact_retirement(OLD.operation_id) THEN
        RAISE EXCEPTION 'Issuer current membership deletion requires exact committed original retirement'
            USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
END;
$$;

CREATE FUNCTION gs_register_issuer_current_local_operation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO game_session_canonical_issuer_current_local_operation
        (operation_id, issuer_id, anchor_bootstrap_operation_id, partition_id)
    VALUES (NEW.operation_id, NEW.issuer_id, NEW.anchor_bootstrap_operation_id, NEW.partition_id);
    RETURN NEW;
END;
$$;

CREATE FUNCTION gs_retire_issuer_current_local_operation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- The existing BEFORE guard has already required this exact original mutation/absence proof.
    IF OLD.status = 'PENDING_PHYSICAL_PROOF' AND NEW.status = 'RETIRED' THEN
        DELETE FROM game_session_canonical_issuer_current_local_operation current
         USING game_session_canonical_issuer_binding_local_operation original
         WHERE original.operation_id = NEW.original_local_operation_id
           AND original.anchor_issuer_coverage_operation_id = NEW.anchor_issuer_coverage_operation_id
           AND current.operation_id = original.operation_id
           AND current.issuer_id = NEW.issuer_id
           AND current.anchor_bootstrap_operation_id = original.anchor_bootstrap_operation_id
           AND current.partition_id = NEW.partition_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Completed issuer retirement lost its exact current original membership'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION gs_check_issuer_reservation_current_membership()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF EXISTS (SELECT 1 FROM game_session_canonical_issuer_installed_anchor
                    WHERE issuer_id = OLD.issuer_id) THEN
            RAISE EXCEPTION 'Installed issuer reservation history must be retained'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        PERFORM gs_require_issuer_reservation_current_membership(NEW.reservation_id);
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION gs_check_installed_issuer_reservation_membership()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    reservation record;
BEGIN
    -- First installation alone qualifies the complete baseline. Ordinary writes use point checks.
    FOR reservation IN
        SELECT reservation_id FROM game_session_canonical_issuer_partition_reservation
         WHERE issuer_id = NEW.issuer_id
    LOOP
        PERFORM gs_require_issuer_reservation_current_membership(reservation.reservation_id);
    END LOOP;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_gs_issuer_current_local_operation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON game_session_canonical_issuer_current_local_operation
    FOR EACH ROW EXECUTE FUNCTION gs_guard_issuer_current_local_operation();
CREATE TRIGGER trg_gs_issuer_current_local_operation_no_truncate
    BEFORE TRUNCATE ON game_session_canonical_issuer_current_local_operation
    FOR EACH STATEMENT EXECUTE FUNCTION gs_guard_issuer_current_local_operation();
CREATE TRIGGER trg_gs_issuer_current_local_operation_register
    AFTER INSERT ON game_session_canonical_issuer_binding_local_operation
    FOR EACH ROW EXECUTE FUNCTION gs_register_issuer_current_local_operation();
CREATE TRIGGER trg_gs_issuer_current_local_operation_retire
    AFTER UPDATE ON game_session_canonical_issuer_binding_local_retirement
    FOR EACH ROW EXECUTE FUNCTION gs_retire_issuer_current_local_operation();
CREATE CONSTRAINT TRIGGER trg_gs_issuer_reservation_current_membership
    AFTER INSERT OR UPDATE OR DELETE ON game_session_canonical_issuer_partition_reservation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION gs_check_issuer_reservation_current_membership();
CREATE CONSTRAINT TRIGGER trg_gs_installed_issuer_reservation_membership
    AFTER INSERT ON game_session_canonical_issuer_installed_anchor
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION gs_check_installed_issuer_reservation_membership();

CREATE TRIGGER trg_gs_issuer_reservation_membership_clock
    BEFORE INSERT OR UPDATE OR DELETE ON game_session_canonical_issuer_partition_reservation
    FOR EACH STATEMENT EXECUTE FUNCTION gs_lock_issuer_current_membership_clock();
CREATE TRIGGER trg_gs_issuer_local_operation_membership_clock
    BEFORE INSERT ON game_session_canonical_issuer_binding_local_operation
    FOR EACH STATEMENT EXECUTE FUNCTION gs_lock_issuer_current_membership_clock();
CREATE TRIGGER trg_gs_issuer_retirement_membership_clock
    BEFORE UPDATE ON game_session_canonical_issuer_binding_local_retirement
    FOR EACH STATEMENT EXECUTE FUNCTION gs_lock_issuer_current_membership_clock();
CREATE TRIGGER trg_gs_installed_issuer_membership_clock
    BEFORE INSERT ON game_session_canonical_issuer_installed_anchor
    FOR EACH STATEMENT EXECUTE FUNCTION gs_lock_issuer_current_membership_clock();
CREATE TRIGGER trg_gs_issuer_current_membership_clock
    BEFORE INSERT OR UPDATE OR DELETE ON game_session_canonical_issuer_current_local_operation
    FOR EACH STATEMENT EXECUTE FUNCTION gs_lock_issuer_current_membership_clock();
-- [jooq ignore stop]

COMMENT ON TABLE game_session_canonical_issuer_current_local_operation IS
    'Positive bounded current references maintained at immutable local-operation insertion and exact original physical retirement; immutable history and existing inventory remain authoritative.';
