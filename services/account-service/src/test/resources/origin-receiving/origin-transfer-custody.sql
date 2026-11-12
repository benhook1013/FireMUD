-- PRIVATE receiving definition only. Not an activated Flyway migration or runtime catalog seal.
-- Apply after the retained positive-origin definition and existing V1065 transfer tables.
ALTER TABLE game_session_region_owner_transfer
    ADD COLUMN successor_owner_id VARCHAR(120),
    ADD COLUMN successor_owner_fence BIGINT,
    ADD COLUMN successor_lease_correlation CHAR(64),
    ADD COLUMN origin_verification_round UUID,
    ADD CONSTRAINT gs_transfer_successor_exact CHECK (
        (successor_owner_id IS NULL AND successor_owner_fence IS NULL AND successor_lease_correlation IS NULL)
        OR (successor_owner_id IS NOT NULL AND successor_owner_id<>'' AND btrim(successor_owner_id)=successor_owner_id
            AND successor_owner_fence IS NOT NULL AND successor_owner_fence>old_owner_fence
            AND successor_lease_correlation IS NOT NULL AND successor_lease_correlation ~ '^[0-9a-f]{64}$'));

CREATE TABLE game_session_capacity_origin_transfer_item (
    transfer_id UUID NOT NULL REFERENCES game_session_region_owner_transfer(transfer_id),
    target_ordinal BIGINT NOT NULL CHECK (target_ordinal>=0),
    origin_digest TEXT NOT NULL REFERENCES game_session_original_new_capacity_origin(origin_digest),
    canonical_origin BYTEA NOT NULL CHECK (octet_length(canonical_origin) BETWEEN 1 AND 1048576),
    captured_state TEXT NOT NULL CHECK (captured_state IN ('OPEN','DISPATCH_CLAIMED','CLOSURE_CLAIMED','DENIED')),
    old_owner_id VARCHAR(120) NOT NULL,
    old_owner_fence BIGINT NOT NULL CHECK (old_owner_fence>0),
    account_resolution_bytes BYTEA,
    account_resolution_digest TEXT,
    tenant_gameplay_tag VARCHAR(44) NOT NULL,
    workload_namespace VARCHAR(253) NOT NULL,
    allocation_version INTEGER NOT NULL CHECK (allocation_version>0),
    fence_readback BYTEA CHECK (octet_length(fence_readback) BETWEEN 1 AND 1048576),
    incarnation_digest VARCHAR(71) CHECK (incarnation_digest ~ '^sha256:[0-9a-f]{64}$'),
    before_payload_digest VARCHAR(71) CHECK (before_payload_digest ~ '^sha256:[0-9a-f]{64}$'),
    after_payload_digest VARCHAR(71) CHECK (after_payload_digest ~ '^sha256:[0-9a-f]{64}$'),
    verified_round UUID,
    PRIMARY KEY (transfer_id,target_ordinal),
    UNIQUE (transfer_id,origin_digest),
    CHECK ((account_resolution_bytes IS NULL)=(account_resolution_digest IS NULL)),
    CHECK ((captured_state='OPEN')=(account_resolution_bytes IS NULL)),
    CHECK (account_resolution_bytes IS NULL OR account_resolution_digest='sha256:'||encode(sha256(account_resolution_bytes),'hex')),
    CHECK ((fence_readback IS NULL AND incarnation_digest IS NULL AND before_payload_digest IS NULL AND after_payload_digest IS NULL)
        OR (fence_readback IS NOT NULL AND incarnation_digest IS NOT NULL AND before_payload_digest IS NOT NULL AND after_payload_digest IS NOT NULL)),
    CHECK (verified_round IS NULL OR fence_readback IS NOT NULL)
);
CREATE INDEX gs_origin_transfer_pending_scope
    ON game_session_original_new_capacity_origin(tenant_id,game_instance_id,region_id,origin_digest);

-- Retain the first successor choice across indeterminate physical replies. A later retry may not
-- create another owner, lease, fence, captured state or canonical origin under that transfer.
CREATE FUNCTION guard_gs_origin_transfer_successor() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.successor_owner_id IS NOT NULL AND
        ROW(NEW.successor_owner_id,NEW.successor_owner_fence,NEW.successor_lease_correlation)
        IS DISTINCT FROM ROW(OLD.successor_owner_id,OLD.successor_owner_fence,OLD.successor_lease_correlation) THEN
        RAISE EXCEPTION 'Origin transfer successor is immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.state='COMPLETED' AND NEW.successor_owner_id IS NOT NULL AND
        ROW(NEW.new_owner_id,NEW.new_owner_fence,NEW.new_lease_correlation)
        IS DISTINCT FROM ROW(NEW.successor_owner_id,NEW.successor_owner_fence,NEW.successor_lease_correlation) THEN
        RAISE EXCEPTION 'Runtime installation must consume the reserved successor' USING ERRCODE='23514';
    END IF;
    IF NEW.state='COMPLETED' AND EXISTS (
        SELECT 1 FROM game_session_original_new_capacity_origin o
        WHERE o.tenant_id=NEW.tenant_id AND o.game_instance_id=NEW.game_instance_id AND o.region_id=NEW.region_id
            AND NOT EXISTS (
                SELECT 1 FROM game_session_capacity_origin_transfer_item i
                WHERE i.transfer_id=NEW.transfer_id AND i.origin_digest=o.origin_digest
                    AND i.canonical_origin=o.canonical_origin AND i.captured_state=o.state
                    AND i.old_owner_id=NEW.old_owner_id AND i.old_owner_fence=NEW.old_owner_fence
                    AND i.account_resolution_bytes IS NOT DISTINCT FROM o.account_resolution_bytes
                    AND i.account_resolution_digest IS NOT DISTINCT FROM o.account_resolution_digest
                    AND i.fence_readback IS NOT NULL AND i.incarnation_digest=NEW.incarnation_digest
                    AND i.verified_round=NEW.origin_verification_round
                    AND o.custody_owner_id=NEW.new_owner_id AND o.custody_owner_fence=NEW.new_owner_fence
                    AND o.custody_transfer_id=NEW.transfer_id)) THEN
        RAISE EXCEPTION 'Unresolved origin lacks exact promoted physical custody' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_origin_transfer_successor_guard BEFORE UPDATE ON game_session_region_owner_transfer
    FOR EACH ROW EXECUTE FUNCTION guard_gs_origin_transfer_successor();

CREATE FUNCTION guard_gs_origin_transfer_item() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE parent game_session_region_owner_transfer%ROWTYPE;
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Origin transfer custody is retained' USING ERRCODE='23514'; END IF;
    SELECT * INTO STRICT parent FROM game_session_region_owner_transfer WHERE transfer_id=NEW.transfer_id;
    IF parent.state<>'FROZEN' THEN RAISE EXCEPTION 'Origin transfer is not frozen' USING ERRCODE='23514'; END IF;
    IF TG_OP='INSERT' AND NOT EXISTS (
        SELECT 1 FROM game_session_original_new_capacity_origin o
        WHERE o.origin_digest=NEW.origin_digest AND o.canonical_origin=NEW.canonical_origin
            AND o.state=NEW.captured_state AND o.custody_owner_id=NEW.old_owner_id AND o.custody_owner_fence=NEW.old_owner_fence
            AND o.custody_owner_id=parent.old_owner_id AND o.custody_owner_fence=parent.old_owner_fence
            AND o.tenant_id=parent.tenant_id AND o.game_instance_id=parent.game_instance_id AND o.region_id=parent.region_id
            AND o.tenant_gameplay_tag=NEW.tenant_gameplay_tag
            AND o.account_resolution_bytes IS NOT DISTINCT FROM NEW.account_resolution_bytes
            AND o.account_resolution_digest IS NOT DISTINCT FROM NEW.account_resolution_digest) THEN
        RAISE EXCEPTION 'Origin capture differs from retained pre-Account source' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND (
        ROW(NEW.transfer_id,NEW.target_ordinal,NEW.origin_digest,NEW.canonical_origin,NEW.captured_state,NEW.old_owner_id,NEW.old_owner_fence,
            NEW.account_resolution_bytes,NEW.account_resolution_digest,NEW.tenant_gameplay_tag,NEW.workload_namespace,NEW.allocation_version)
        IS DISTINCT FROM ROW(OLD.transfer_id,OLD.target_ordinal,OLD.origin_digest,OLD.canonical_origin,OLD.captured_state,OLD.old_owner_id,OLD.old_owner_fence,
            OLD.account_resolution_bytes,OLD.account_resolution_digest,OLD.tenant_gameplay_tag,OLD.workload_namespace,OLD.allocation_version)
        OR (OLD.fence_readback IS NOT NULL AND ROW(NEW.fence_readback,NEW.incarnation_digest,NEW.before_payload_digest,NEW.after_payload_digest)
            IS DISTINCT FROM ROW(OLD.fence_readback,OLD.incarnation_digest,OLD.before_payload_digest,OLD.after_payload_digest))) THEN
        RAISE EXCEPTION 'Origin capture and physical fence receipt are immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.fence_readback IS NOT NULL AND parent.successor_owner_id IS NULL THEN
        RAISE EXCEPTION 'Origin physical custody requires a retained successor' USING ERRCODE='23514';
    END IF;
    IF NEW.verified_round IS NOT NULL AND NEW.verified_round IS DISTINCT FROM parent.origin_verification_round THEN
        RAISE EXCEPTION 'Origin current verification belongs to another round' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_origin_transfer_item_guard BEFORE INSERT OR UPDATE OR DELETE ON game_session_capacity_origin_transfer_item
    FOR EACH ROW EXECUTE FUNCTION guard_gs_origin_transfer_item();
CREATE TRIGGER gs_origin_transfer_item_no_truncate BEFORE TRUNCATE ON game_session_capacity_origin_transfer_item
    FOR EACH STATEMENT EXECUTE FUNCTION reject_gs_region_owner_transfer_truncate();

-- Every owner mutation, including origin-only regions, must have completed the exact retained
-- chain in the same runtime installation transaction. Neither a pending intent nor an absent
-- execution row supplies an empty-inventory exemption.
CREATE FUNCTION require_gs_origin_runtime_handoff() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.executor_fence,NEW.owner_instance_id,NEW.lease_token_correlation)
        IS NOT DISTINCT FROM ROW(OLD.executor_fence,OLD.owner_instance_id,OLD.lease_token_correlation) THEN RETURN NEW; END IF;
    IF EXISTS (SELECT 1 FROM game_session_original_new_capacity_origin o
        WHERE o.tenant_id=OLD.tenant_id AND o.game_instance_id=OLD.game_instance_id AND o.region_id=OLD.region_id)
        AND NOT EXISTS (SELECT 1 FROM game_session_region_owner_transfer t
            WHERE t.tenant_id=OLD.tenant_id AND t.game_instance_id=OLD.game_instance_id AND t.region_id=OLD.region_id
                AND t.region_epoch=OLD.region_epoch AND t.old_owner_id=OLD.owner_instance_id AND t.old_owner_fence=OLD.executor_fence
                AND t.old_lease_correlation=OLD.lease_token_correlation AND t.state='COMPLETED'
                AND t.new_owner_id=NEW.owner_instance_id AND t.new_owner_fence=NEW.executor_fence
                AND t.new_lease_correlation=NEW.lease_token_correlation AND t.origin_verification_round IS NOT NULL
                AND NOT EXISTS (SELECT 1 FROM game_session_original_new_capacity_origin o
                    WHERE o.tenant_id=OLD.tenant_id AND o.game_instance_id=OLD.game_instance_id AND o.region_id=OLD.region_id
                        AND (o.custody_owner_id<>NEW.owner_instance_id OR o.custody_owner_fence<>NEW.executor_fence
                            OR o.custody_transfer_id IS DISTINCT FROM t.transfer_id))) THEN
        RAISE EXCEPTION 'Runtime successor lacks all-origin transfer custody' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_origin_runtime_handoff_guard BEFORE UPDATE ON game_session_region_runtime_status
    FOR EACH ROW EXECUTE FUNCTION require_gs_origin_runtime_handoff();

-- Custody may be promoted before COMPLETED within the transaction, but cannot commit without
-- the actual runtime successor. This is a deferred integrity check, never an execution outcome.
CREATE FUNCTION require_gs_origin_custody_successor() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.custody_owner_id,NEW.custody_owner_fence,NEW.custody_transfer_id)
        IS NOT DISTINCT FROM ROW(OLD.custody_owner_id,OLD.custody_owner_fence,OLD.custody_transfer_id) THEN RETURN NULL; END IF;
    IF NOT EXISTS (SELECT 1 FROM game_session_original_new_capacity_origin o
        JOIN game_session_region_owner_transfer t ON t.transfer_id=o.custody_transfer_id
        JOIN game_session_region_runtime_status r ON r.tenant_id=t.tenant_id AND r.game_instance_id=t.game_instance_id AND r.region_id=t.region_id
        JOIN game_session_capacity_origin_transfer_item i ON i.transfer_id=t.transfer_id AND i.origin_digest=o.origin_digest
        WHERE o.admission_attempt_id=NEW.admission_attempt_id AND t.state='COMPLETED'
            AND t.old_owner_id=OLD.custody_owner_id AND t.old_owner_fence=OLD.custody_owner_fence
            AND t.new_owner_id=o.custody_owner_id AND t.new_owner_fence=o.custody_owner_fence
            AND r.owner_instance_id=t.new_owner_id AND r.executor_fence=t.new_owner_fence AND r.region_epoch=t.region_epoch
            AND r.lease_token_correlation=t.new_lease_correlation AND i.canonical_origin=o.canonical_origin
            AND i.captured_state=o.state AND i.fence_readback IS NOT NULL
            AND i.incarnation_digest=t.incarnation_digest AND i.verified_round=t.origin_verification_round) THEN
        RAISE EXCEPTION 'Origin custody promotion requires actual installed successor' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER gs_origin_custody_successor_guard AFTER UPDATE ON game_session_original_new_capacity_origin
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_gs_origin_custody_successor();

-- Forward integration must classify both retained origin families and seal a new runtime-family
-- catalog epoch at the real migration frontier. No catalog installation or cleanup policy here.
-- SQL DENIED alone is not retained physical denial: it may precede a lost physical DENY reply.
-- All origin states remain captured/fenced until a future independently retained terminal or
-- cleanup-proof predicate is implemented. Candidate/index evidence remains held in the meantime.
