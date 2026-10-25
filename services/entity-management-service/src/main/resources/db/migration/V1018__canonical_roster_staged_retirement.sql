-- Bounded retirement preparation after the unchanged published V1015-V1017 schema prefix.
-- Scalar inventory describes retained structured rows; it never creates retirement authority.
-- Existing request/proof/receipt bytes and immutable child-row versions remain untouched.
ALTER TABLE entity_canonical_roster_retention_lifecycle
    ADD COLUMN original_selection_count BIGINT DEFAULT 0 CHECK (original_selection_count >= 0),
    ADD COLUMN retirement_original_count BIGINT CHECK (retirement_original_count > 0),
    ADD COLUMN retirement_prepared_count BIGINT NOT NULL DEFAULT 0 CHECK (retirement_prepared_count >= 0),
    ADD COLUMN retirement_preparation_cursor UUID;
-- DDL transaction retains table locks from ALTER; original insertion cannot race this baseline.
-- No timestamp, proof, retirement identity or stored horizon is rewritten.
/* [jooq ignore start] */
WITH inventory AS (
    SELECT l.snapshot_uuid, (SELECT count(*) FROM entity_canonical_roster_original_selections o WHERE o.snapshot_uuid = l.snapshot_uuid) AS originals,
        NOT EXISTS (SELECT 1 FROM entity_canonical_roster_retention_references r
            WHERE r.snapshot_uuid = l.snapshot_uuid AND r.reference_kind = 'ORIGINAL_SELECTION'
              AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_original_selections o
                  WHERE o.operation_uuid = r.reference_uuid AND o.operation_uuid = r.acquire_operation_uuid
                    AND o.snapshot_uuid = r.snapshot_uuid AND o.snapshot_digest = r.snapshot_digest))
        AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_original_selections o WHERE o.snapshot_uuid = l.snapshot_uuid
            AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_retention_references r
                WHERE r.reference_uuid = o.operation_uuid AND r.acquire_operation_uuid = o.operation_uuid
                  AND r.snapshot_uuid = o.snapshot_uuid AND r.snapshot_digest = o.snapshot_digest AND r.reference_kind = 'ORIGINAL_SELECTION'))
        AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_admissions a WHERE a.snapshot_uuid = l.snapshot_uuid
            AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_original_selections o WHERE o.operation_uuid = a.operation_uuid AND o.snapshot_uuid = a.snapshot_uuid))
        AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_retirement_transfers t WHERE t.snapshot_uuid = l.snapshot_uuid
            AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_original_selections o WHERE o.operation_uuid = t.operation_uuid AND o.snapshot_uuid = t.snapshot_uuid)) AS consistent
    FROM entity_canonical_roster_retention_lifecycle l
)
UPDATE entity_canonical_roster_retention_lifecycle l SET
    original_selection_count = CASE WHEN i.consistent THEN i.originals ELSE NULL END,
    retirement_original_count = CASE WHEN l.lifecycle_state = 'RETIRED' AND i.consistent AND i.originals > 0 THEN i.originals ELSE NULL END,
    retirement_prepared_count = (SELECT count(*) FROM entity_canonical_roster_original_selections o
        JOIN entity_canonical_roster_admissions a USING (operation_uuid, snapshot_uuid)
        JOIN entity_canonical_roster_retirement_transfers t USING (operation_uuid, admission_uuid)
        JOIN entity_canonical_roster_retention_references r ON r.reference_uuid = o.operation_uuid
        JOIN entity_canonical_roster_retention_releases x ON x.reference_uuid = o.operation_uuid
        WHERE l.lifecycle_state = 'RETIRED' AND o.snapshot_uuid = l.snapshot_uuid
          AND a.admission_state IN ('OPEN', 'CLOSED') AND t.original_request = o.owner_request
          AND t.snapshot_uuid = o.snapshot_uuid AND t.snapshot_digest = o.snapshot_digest
          AND t.owner_fence = o.owner_fence AND t.target_fence_uuid = o.owner_target_fence_uuid
          AND t.transfer_state <> 'WAITING_RETIREMENT' AND t.observed_retirement = l.owner_retirement_proof
          AND t.acknowledgement_request IS NOT NULL AND r.acquire_operation_uuid = o.operation_uuid
          AND r.reference_kind = 'ORIGINAL_SELECTION' AND r.snapshot_uuid = o.snapshot_uuid AND r.snapshot_digest = o.snapshot_digest
          AND x.snapshot_uuid = o.snapshot_uuid AND x.snapshot_digest = o.snapshot_digest),
    local_epoch = local_epoch + 1
FROM inventory i WHERE i.snapshot_uuid = l.snapshot_uuid AND l.lifecycle_state IN ('PROTECTED', 'RETIRED');

/* [jooq ignore stop] */

CREATE TABLE entity_canonical_roster_target_classifications (
    source_kind VARCHAR(24) NOT NULL CHECK (source_kind IN ('ADMISSION', 'TRANSFER', 'TRANSFER_RETIREMENT')),
    operation_uuid UUID NOT NULL,
    admission_uuid UUID NOT NULL,
    source_binding BYTEA NOT NULL CHECK (octet_length(source_binding) BETWEEN 1 AND 4096),
    classification_state VARCHAR(8) NOT NULL CHECK (classification_state IN ('KNOWN', 'UNKNOWN')),
    target_binding BYTEA,
    target_digest VARCHAR(64),
    retirement_proof BYTEA CHECK (octet_length(retirement_proof) BETWEEN 1 AND 131072),
    PRIMARY KEY (source_kind, operation_uuid, admission_uuid),
    CHECK ((classification_state = 'UNKNOWN' AND target_binding IS NULL AND target_digest IS NULL AND retirement_proof IS NULL)
        OR (classification_state = 'KNOWN' AND target_binding IS NOT NULL AND target_digest IS NOT NULL AND octet_length(target_binding) BETWEEN 1 AND 4096
            AND target_digest = encode(sha256(target_binding), 'hex')
            AND ((source_kind = 'TRANSFER_RETIREMENT' AND retirement_proof IS NOT NULL)
                OR (source_kind <> 'TRANSFER_RETIREMENT' AND retirement_proof IS NULL))))
);
CREATE INDEX idx_entity_roster_classified_target ON entity_canonical_roster_target_classifications (target_digest, source_kind);

/* [jooq ignore start] */
CREATE FUNCTION entity_roster_classification_source_exists(source_kind TEXT, operation_id UUID, admission_id UUID)
RETURNS BOOLEAN LANGUAGE SQL AS $$
    SELECT CASE WHEN source_kind = 'ADMISSION' THEN EXISTS (
        SELECT 1 FROM entity_canonical_roster_admissions a WHERE a.operation_uuid = operation_id AND a.admission_uuid = admission_id)
        ELSE EXISTS (SELECT 1 FROM entity_canonical_roster_retirement_transfers t
            WHERE t.operation_uuid = operation_id AND t.admission_uuid = admission_id) END
$$;
CREATE FUNCTION guard_entity_roster_target_classification()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND NOT entity_roster_classification_source_exists(OLD.source_kind, OLD.operation_uuid, OLD.admission_uuid) THEN RETURN OLD; END IF;
    IF TG_OP = 'INSERT' AND (
        (NEW.source_kind = 'ADMISSION' AND EXISTS (SELECT 1 FROM entity_canonical_roster_admissions a
            WHERE a.operation_uuid = NEW.operation_uuid AND a.admission_uuid = NEW.admission_uuid AND a.request_binding = NEW.source_binding))
        OR (NEW.source_kind IN ('TRANSFER', 'TRANSFER_RETIREMENT') AND EXISTS (
            SELECT 1 FROM entity_canonical_roster_retirement_transfers t
            WHERE t.operation_uuid = NEW.operation_uuid AND t.admission_uuid = NEW.admission_uuid AND t.original_request = NEW.source_binding
              AND (NEW.source_kind = 'TRANSFER' OR (t.transfer_state <> 'WAITING_RETIREMENT'
                  AND (NEW.classification_state = 'UNKNOWN' OR NEW.retirement_proof = t.observed_retirement)))))) THEN RETURN NEW; END IF;
    RAISE EXCEPTION 'target classification requires exact immutable source lineage' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER trg_entity_roster_target_classification_guard
    BEFORE INSERT OR UPDATE OR DELETE ON entity_canonical_roster_target_classifications
    FOR EACH ROW EXECUTE FUNCTION guard_entity_roster_target_classification();
CREATE TRIGGER trg_entity_roster_target_classification_no_truncate
    BEFORE TRUNCATE ON entity_canonical_roster_target_classifications
    FOR EACH STATEMENT EXECUTE FUNCTION reject_entity_assignment_history_removal();
/* [jooq ignore stop] */

-- Immutable target seal survives payload disposal: a cached owner response cannot
-- authorize another snapshot, including through an admission reserved before the seal.
CREATE TABLE entity_canonical_roster_retired_targets (
    target_digest VARCHAR(64) PRIMARY KEY CHECK (target_digest ~ '^[0-9a-f]{64}$'),
    target_binding BYTEA NOT NULL CHECK (octet_length(target_binding) BETWEEN 1 AND 4096),
    snapshot_uuid UUID NOT NULL UNIQUE,
    snapshot_digest VARCHAR(64) NOT NULL CHECK (snapshot_digest ~ '^[0-9a-f]{64}$'),
    owner_retirement_proof BYTEA NOT NULL CHECK (octet_length(owner_retirement_proof) BETWEEN 1 AND 131072),
    minimum_retention_millis BIGINT NOT NULL CHECK (minimum_retention_millis > 0),
    observed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    payload_disposed_at TIMESTAMPTZ
);

/* [jooq ignore start] */
CREATE FUNCTION entity_roster_target_classification_pending()
RETURNS BOOLEAN LANGUAGE SQL AS $$
    SELECT EXISTS (SELECT 1 FROM entity_canonical_roster_admissions a WHERE NOT EXISTS (
        SELECT 1 FROM entity_canonical_roster_target_classifications m WHERE m.source_kind = 'ADMISSION'
          AND m.operation_uuid = a.operation_uuid AND m.admission_uuid = a.admission_uuid
          AND m.source_binding = a.request_binding AND m.classification_state = 'KNOWN'))
    OR EXISTS (SELECT 1 FROM entity_canonical_roster_retirement_transfers t WHERE NOT EXISTS (
        SELECT 1 FROM entity_canonical_roster_target_classifications m WHERE m.source_kind = 'TRANSFER'
          AND m.operation_uuid = t.operation_uuid AND m.admission_uuid = t.admission_uuid
          AND m.source_binding = t.original_request AND m.classification_state = 'KNOWN')
        OR (t.transfer_state <> 'WAITING_RETIREMENT' AND NOT EXISTS (
            SELECT 1 FROM entity_canonical_roster_target_classifications m WHERE m.source_kind = 'TRANSFER_RETIREMENT'
              AND m.operation_uuid = t.operation_uuid AND m.admission_uuid = t.admission_uuid
              AND m.source_binding = t.original_request AND m.retirement_proof = t.observed_retirement AND m.classification_state = 'KNOWN')))
    OR EXISTS (SELECT 1 FROM entity_canonical_roster_retention_lifecycle l WHERE l.lifecycle_state IN ('RETIRED', 'DISPOSING')
        AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_retired_targets s
            WHERE s.snapshot_uuid = l.snapshot_uuid AND s.snapshot_digest = l.snapshot_digest AND s.owner_retirement_proof = l.owner_retirement_proof))
$$;
CREATE FUNCTION entity_roster_retired_target_purgeable(target_id TEXT)
RETURNS BOOLEAN LANGUAGE SQL AS $$
    SELECT EXISTS (SELECT 1 FROM entity_canonical_roster_retired_targets s WHERE s.target_digest = target_id
        AND s.payload_disposed_at IS NOT NULL AND s.observed_at + s.minimum_retention_millis * interval '1 millisecond' <= clock_timestamp()
        AND NOT entity_roster_target_classification_pending()
        AND NOT EXISTS (SELECT 1 FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = s.snapshot_uuid)
        AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = s.snapshot_uuid)
        AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_original_selections WHERE snapshot_uuid = s.snapshot_uuid)
        AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_admissions a
            JOIN entity_canonical_roster_target_classifications m ON m.source_kind = 'ADMISSION'
                AND m.operation_uuid = a.operation_uuid AND m.admission_uuid = a.admission_uuid AND m.source_binding = a.request_binding
            WHERE m.target_digest = target_id)
        AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_retirement_transfers t
            JOIN entity_canonical_roster_target_classifications m ON m.source_kind = 'TRANSFER'
                AND m.operation_uuid = t.operation_uuid AND m.admission_uuid = t.admission_uuid AND m.source_binding = t.original_request
            WHERE m.target_digest = target_id))
$$;
/* [jooq ignore stop] */

/* [jooq ignore start] */
CREATE FUNCTION protect_entity_roster_retired_target()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.observed_at := clock_timestamp();
        IF NEW.payload_disposed_at IS NOT NULL THEN
            RAISE EXCEPTION 'target seal cannot originate disposed' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.payload_disposed_at IS NULL
        AND ROW(NEW.target_digest, NEW.target_binding, NEW.snapshot_uuid, NEW.snapshot_digest,
            NEW.owner_retirement_proof, NEW.minimum_retention_millis, NEW.observed_at)
        IS NOT DISTINCT FROM ROW(OLD.target_digest, OLD.target_binding, OLD.snapshot_uuid, OLD.snapshot_digest,
            OLD.owner_retirement_proof, OLD.minimum_retention_millis, OLD.observed_at)
        AND entity_roster_disposal_authorized(OLD.snapshot_uuid) THEN
        NEW.payload_disposed_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' AND entity_roster_retired_target_purgeable(OLD.target_digest) THEN RETURN OLD; END IF;
    RAISE EXCEPTION 'authenticated retired target seal remains protected' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER trg_entity_roster_retired_target_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON entity_canonical_roster_retired_targets
    FOR EACH ROW EXECUTE FUNCTION protect_entity_roster_retired_target();
CREATE TRIGGER trg_entity_roster_retired_target_no_truncate
    BEFORE TRUNCATE ON entity_canonical_roster_retired_targets
    FOR EACH STATEMENT EXECUTE FUNCTION reject_entity_assignment_history_removal();

CREATE FUNCTION entity_roster_retirement_prepared(snapshot_id UUID)
RETURNS BOOLEAN LANGUAGE SQL AS $$
    SELECT EXISTS (
        SELECT 1 FROM entity_canonical_roster_retention_lifecycle l
        JOIN entity_canonical_roster_retired_targets s USING (snapshot_uuid, snapshot_digest)
        WHERE l.snapshot_uuid = snapshot_id AND l.retirement_original_count > 0
          AND l.owner_retirement_proof = s.owner_retirement_proof
          AND l.retirement_original_count = (
              SELECT count(*) FROM entity_canonical_roster_original_selections o WHERE o.snapshot_uuid = snapshot_id)
          AND NOT EXISTS (
              SELECT 1 FROM entity_canonical_roster_original_selections o
              WHERE o.snapshot_uuid = snapshot_id AND NOT EXISTS (
                  SELECT 1 FROM entity_canonical_roster_admissions a
                  JOIN entity_canonical_roster_retirement_transfers t USING (operation_uuid, admission_uuid)
                  JOIN entity_canonical_roster_target_classifications m ON m.source_kind = 'TRANSFER_RETIREMENT'
                    AND m.operation_uuid = t.operation_uuid AND m.admission_uuid = t.admission_uuid
                    AND m.classification_state = 'KNOWN' AND m.source_binding = t.original_request AND m.retirement_proof = t.observed_retirement
                  JOIN entity_canonical_roster_retention_references r ON r.reference_uuid = o.operation_uuid
                  JOIN entity_canonical_roster_retention_releases x ON x.reference_uuid = r.reference_uuid
                  WHERE a.operation_uuid = o.operation_uuid AND a.snapshot_uuid = o.snapshot_uuid
                    AND a.admission_state IN ('OPEN', 'CLOSED')
                    AND t.original_request = o.owner_request AND t.snapshot_uuid = o.snapshot_uuid
                    AND t.snapshot_digest = o.snapshot_digest AND t.owner_fence = o.owner_fence
                    AND t.target_fence_uuid = o.owner_target_fence_uuid
                    AND t.transfer_state <> 'WAITING_RETIREMENT'
                    AND t.observed_retirement = l.owner_retirement_proof AND t.acknowledgement_request IS NOT NULL
                    AND r.acquire_operation_uuid = o.operation_uuid AND r.reference_kind = 'ORIGINAL_SELECTION'
                    AND r.snapshot_uuid = o.snapshot_uuid AND r.snapshot_digest = o.snapshot_digest
                    AND x.snapshot_uuid = o.snapshot_uuid AND x.snapshot_digest = o.snapshot_digest)))
$$;
CREATE FUNCTION guard_entity_roster_staged_retirement()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.lifecycle_state <> 'PROTECTED' AND ROW(NEW.retirement_original_count, NEW.original_selection_count)
            IS DISTINCT FROM ROW(OLD.retirement_original_count, OLD.original_selection_count))
        OR (OLD.lifecycle_state = 'PROTECTED' AND NEW.lifecycle_state = 'PROTECTED'
            AND ROW(NEW.retirement_original_count, NEW.retirement_preparation_cursor, NEW.retirement_prepared_count)
                IS DISTINCT FROM ROW(OLD.retirement_original_count, OLD.retirement_preparation_cursor, OLD.retirement_prepared_count))
        OR NEW.retirement_prepared_count < OLD.retirement_prepared_count
        OR (NEW.retirement_original_count IS NOT NULL AND NEW.retirement_prepared_count > NEW.retirement_original_count)
        OR (OLD.lifecycle_state = 'PROTECTED' AND NEW.lifecycle_state = 'RETIRED'
            AND (NEW.retirement_prepared_count <> 0 OR NEW.retirement_original_count IS NULL OR NEW.retirement_preparation_cursor IS NOT NULL
                OR OLD.original_selection_count IS NULL OR NEW.retirement_original_count <> OLD.original_selection_count
                OR NOT EXISTS (SELECT 1 FROM entity_canonical_roster_retired_targets s
                    WHERE s.snapshot_uuid = NEW.snapshot_uuid AND s.snapshot_digest = NEW.snapshot_digest
                        AND s.owner_retirement_proof = NEW.owner_retirement_proof)))
        OR (NEW.lifecycle_state = 'DISPOSING' AND NOT entity_roster_retirement_prepared(OLD.snapshot_uuid)) THEN
        RAISE EXCEPTION 'retirement preparation incomplete or inventory changed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_entity_roster_staged_retirement_guard
    BEFORE UPDATE ON entity_canonical_roster_retention_lifecycle
    FOR EACH ROW EXECUTE FUNCTION guard_entity_roster_staged_retirement();
/* [jooq ignore stop] */

CREATE INDEX idx_entity_roster_original_preparation_page
    ON entity_canonical_roster_original_selections (snapshot_uuid, operation_uuid);

/* [jooq ignore start] */
CREATE FUNCTION count_entity_roster_original_selection()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    UPDATE entity_canonical_roster_retention_lifecycle
        SET original_selection_count = original_selection_count + 1, local_epoch = local_epoch + 1
        WHERE snapshot_uuid = NEW.snapshot_uuid AND snapshot_digest = NEW.snapshot_digest AND lifecycle_state = 'PROTECTED';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'sealed roster target refuses another original' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_entity_roster_original_selection_count
    AFTER INSERT ON entity_canonical_roster_original_selections
    FOR EACH ROW EXECUTE FUNCTION count_entity_roster_original_selection();
/* [jooq ignore stop] */
