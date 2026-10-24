-- Typed PLAYER_CREATED recognition is additive. Published V1018/V1019 tuples stay untouched.
ALTER TABLE entity_canonical_roster_target_classifications
    ADD COLUMN normalization_version SMALLINT NOT NULL DEFAULT 1 CHECK (normalization_version IN (1, 2));
/* [jooq ignore start] */
ALTER TABLE entity_canonical_roster_target_classifications DROP CONSTRAINT entity_canonical_roster_target_classifications_pkey;
ALTER TABLE entity_canonical_roster_target_classifications ADD PRIMARY KEY (source_kind, operation_uuid, admission_uuid, normalization_version);
/* [jooq ignore stop] */
ALTER TABLE entity_canonical_roster_snapshot_targets
    ADD COLUMN recognition_version SMALLINT NOT NULL DEFAULT 1 CHECK (recognition_version IN (1, 2));
/* [jooq ignore start] */
ALTER TABLE entity_canonical_roster_snapshot_targets DROP CONSTRAINT entity_canonical_roster_snapshot_targets_pkey;
ALTER TABLE entity_canonical_roster_snapshot_targets ADD PRIMARY KEY (snapshot_uuid, source_kind, recognition_version);
/* [jooq ignore stop] */
/* [jooq ignore start] */
ALTER TABLE entity_canonical_roster_snapshot_targets ADD CONSTRAINT ck_entity_roster_created_recognition
    CHECK (recognition_version = 1 OR classification_state = 'UNKNOWN' OR target_projection->>'entry_policy' IS NOT DISTINCT FROM 'PLAYER_CREATED');
/* [jooq ignore stop] */

/* [jooq ignore start] */
DO $$
DECLARE selected RECORD;
BEGIN
    FOR selected IN SELECT conname, conrelid::regclass AS relation FROM pg_constraint
        WHERE contype = 'c' AND (
            (conrelid = 'entity_canonical_roster_target_classifications'::regclass AND pg_get_constraintdef(oid) LIKE '%octet_length(retirement_proof)%')
            OR (conrelid = 'entity_canonical_roster_retired_targets'::regclass AND pg_get_constraintdef(oid) LIKE '%octet_length(owner_retirement_proof)%'))
    LOOP EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', selected.relation, selected.conname); END LOOP;
END;
$$;
/* [jooq ignore stop] */
ALTER TABLE entity_canonical_roster_target_classifications ADD CONSTRAINT ck_entity_roster_classified_proof_budget
    CHECK (retirement_proof IS NULL OR octet_length(retirement_proof) BETWEEN 1 AND CASE WHEN normalization_version = 2 THEN 203423744 ELSE 131072 END);
ALTER TABLE entity_canonical_roster_snapshot_targets ADD CONSTRAINT ck_entity_roster_snapshot_source_proof_budget
    CHECK (source_retirement_proof IS NULL OR octet_length(source_retirement_proof) BETWEEN 1 AND CASE WHEN recognition_version = 2 THEN 203423744 ELSE 131072 END);
ALTER TABLE entity_canonical_roster_retired_targets ADD CONSTRAINT ck_entity_roster_seal_maximum_proof_budget
    CHECK (octet_length(owner_retirement_proof) BETWEEN 1 AND 203423744);

/* [jooq ignore start] */
-- A recognition version alone is never qualification: the live immutable policy/source/proof must match.
CREATE VIEW entity_roster_effective_target_classifications AS
    SELECT m.* FROM entity_canonical_roster_target_classifications m
    WHERE (m.source_kind = 'ADMISSION' AND EXISTS (
        SELECT 1 FROM entity_canonical_roster_admissions a WHERE a.operation_uuid = m.operation_uuid
            AND a.admission_uuid = m.admission_uuid AND a.request_binding = m.source_binding
            AND m.normalization_version = CASE WHEN a.entry_policy = 'PLAYER_CREATED' THEN 2 ELSE 1 END))
        OR (m.source_kind IN ('TRANSFER', 'TRANSFER_RETIREMENT') AND EXISTS (
            SELECT 1 FROM entity_canonical_roster_retirement_transfers t WHERE t.operation_uuid = m.operation_uuid
                AND t.admission_uuid = m.admission_uuid AND t.original_request = m.source_binding
                AND m.normalization_version = CASE WHEN t.entry_policy = 'PLAYER_CREATED' THEN 2 ELSE 1 END
                AND (m.source_kind = 'TRANSFER' OR (t.transfer_state <> 'WAITING_RETIREMENT'
                    AND (m.classification_state = 'UNKNOWN' OR m.retirement_proof = t.observed_retirement)))));
CREATE VIEW entity_roster_effective_snapshot_targets AS
    SELECT m.* FROM entity_canonical_roster_snapshot_targets m
    WHERE m.recognition_version = 2 OR (m.recognition_version = 1
        AND (m.classification_state = 'UNKNOWN' OR COALESCE(m.target_projection->>'entry_policy', m.source_snapshot_projection->>'entry_policy', '') <> 'PLAYER_CREATED')
        AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_snapshot_targets typed
            WHERE typed.snapshot_uuid = m.snapshot_uuid AND typed.snapshot_digest = m.snapshot_digest
                AND typed.source_kind = m.source_kind AND typed.recognition_version = 2
                AND typed.source_snapshot_projection IS NOT DISTINCT FROM m.source_snapshot_projection
                AND typed.source_retirement_proof IS NOT DISTINCT FROM m.source_retirement_proof));

CREATE FUNCTION guard_entity_roster_typed_seal_budget()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE policy TEXT;
BEGIN
    SELECT entry_policy INTO policy FROM entity_canonical_gameplay_roster_snapshots
        WHERE snapshot_uuid = NEW.snapshot_uuid AND snapshot_digest = NEW.snapshot_digest;
    IF octet_length(NEW.owner_retirement_proof) > (CASE WHEN policy = 'PLAYER_CREATED' THEN 203423744 ELSE 131072 END) THEN
        RAISE EXCEPTION 'retained proof exceeds exact snapshot family budget' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_entity_roster_typed_seal_budget
    BEFORE INSERT ON entity_canonical_roster_retired_targets
    FOR EACH ROW EXECUTE FUNCTION guard_entity_roster_typed_seal_budget();
CREATE OR REPLACE FUNCTION guard_entity_roster_target_classification()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' AND NEW.normalization_version = 2 AND NOT (
        (NEW.source_kind = 'ADMISSION' AND EXISTS (SELECT 1 FROM entity_canonical_roster_admissions a
            WHERE a.operation_uuid = NEW.operation_uuid AND a.admission_uuid = NEW.admission_uuid
                AND a.entry_policy = 'PLAYER_CREATED' AND a.request_binding = NEW.source_binding))
        OR (NEW.source_kind IN ('TRANSFER', 'TRANSFER_RETIREMENT') AND EXISTS (
            SELECT 1 FROM entity_canonical_roster_retirement_transfers t
            WHERE t.operation_uuid = NEW.operation_uuid AND t.admission_uuid = NEW.admission_uuid
                AND t.entry_policy = 'PLAYER_CREATED' AND t.original_request = NEW.source_binding
                AND (NEW.source_kind = 'TRANSFER' OR NEW.classification_state = 'UNKNOWN'
                    OR (t.transfer_state <> 'WAITING_RETIREMENT' AND t.observed_retirement = NEW.retirement_proof
                        AND t.acknowledgement_request IS NOT NULL
                        AND t.request_digest = encode(sha256(t.acknowledgement_request), 'hex')))))) THEN
        RAISE EXCEPTION 'typed classification requires exact actual created source' USING ERRCODE = '23514';
    END IF;
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
CREATE OR REPLACE FUNCTION guard_entity_roster_snapshot_target()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' AND NEW.recognition_version = 2 THEN
        IF NEW.classification_state = 'KNOWN' AND NEW.target_projection->>'entry_policy' IS DISTINCT FROM 'PLAYER_CREATED' THEN
            RAISE EXCEPTION 'typed recognition requires a created target' USING ERRCODE = '23514';
        END IF;
        IF NEW.source_kind = 'SNAPSHOT' AND EXISTS (
            SELECT 1 FROM entity_canonical_roster_snapshot_targets prior
            WHERE prior.snapshot_uuid = NEW.snapshot_uuid AND prior.snapshot_digest = NEW.snapshot_digest
                AND prior.source_kind = 'SNAPSHOT' AND prior.recognition_version = 1
                AND prior.source_snapshot_projection = NEW.source_snapshot_projection
                AND prior.source_snapshot_projection->>'entry_policy' = 'PLAYER_CREATED'
                AND (NEW.classification_state = 'UNKNOWN' OR (prior.classification_state = 'KNOWN'
                    AND NEW.target_projection = prior.target_projection))) THEN RETURN NEW; END IF;
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF (NEW.source_kind = 'SNAPSHOT' AND EXISTS (
                SELECT 1 FROM entity_canonical_gameplay_roster_snapshots s
                WHERE s.snapshot_uuid = NEW.snapshot_uuid AND s.snapshot_digest = NEW.snapshot_digest
                    AND s.construction_state = 'SEALED'
                    AND NEW.source_snapshot_projection = entity_roster_snapshot_target_projection(s)
                    AND ((NEW.classification_state = 'KNOWN' AND entity_roster_snapshot_target_known(s)
                            AND NEW.target_projection = NEW.source_snapshot_projection)
                        OR (NEW.classification_state = 'UNKNOWN' AND NOT entity_roster_snapshot_target_known(s)))))
            OR (NEW.source_kind = 'DISPOSED_SEAL' AND EXISTS (
                SELECT 1 FROM entity_canonical_roster_retired_targets s
                WHERE s.snapshot_uuid = NEW.snapshot_uuid AND s.snapshot_digest = NEW.snapshot_digest
                    AND s.owner_retirement_proof = NEW.source_retirement_proof
                    AND s.payload_disposed_at IS NOT NULL
                    AND (NEW.classification_state = 'UNKNOWN' OR NOT EXISTS (
                        SELECT 1 FROM entity_roster_effective_snapshot_targets m
                        WHERE m.snapshot_uuid = NEW.snapshot_uuid AND m.source_kind = 'SNAPSHOT'
                            AND (m.classification_state <> 'KNOWN' OR m.target_projection <> NEW.target_projection))))) THEN RETURN NEW; END IF;
    ELSIF TG_OP = 'DELETE' AND NOT entity_roster_snapshot_target_dependency(OLD.snapshot_uuid) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'snapshot target classification source mismatch or remains protected' USING ERRCODE = '23514';
END;
$$;
CREATE OR REPLACE FUNCTION capture_entity_roster_snapshot_target_disposal()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.payload_disposed_at IS NOT NULL THEN
        INSERT INTO entity_canonical_roster_snapshot_targets
            (snapshot_uuid, snapshot_digest, source_kind, classification_state, target_projection, source_retirement_proof, recognition_version)
            SELECT NEW.snapshot_uuid, NEW.snapshot_digest, 'DISPOSED_SEAL', 'KNOWN', m.target_projection, NEW.owner_retirement_proof, m.recognition_version
            FROM entity_roster_effective_snapshot_targets m
            WHERE m.snapshot_uuid = NEW.snapshot_uuid AND m.snapshot_digest = NEW.snapshot_digest
                AND m.source_kind = 'SNAPSHOT' AND m.classification_state = 'KNOWN'
            ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END;
$$;
CREATE OR REPLACE FUNCTION entity_roster_target_classification_pending()
RETURNS BOOLEAN LANGUAGE SQL AS $$
    SELECT EXISTS (SELECT 1 FROM entity_canonical_roster_admissions a WHERE NOT EXISTS (
        SELECT 1 FROM entity_roster_effective_target_classifications m WHERE m.source_kind = 'ADMISSION'
          AND m.operation_uuid = a.operation_uuid AND m.admission_uuid = a.admission_uuid
          AND m.source_binding = a.request_binding AND m.classification_state = 'KNOWN'))
    OR EXISTS (SELECT 1 FROM entity_canonical_roster_retirement_transfers t WHERE NOT EXISTS (
        SELECT 1 FROM entity_roster_effective_target_classifications m WHERE m.source_kind = 'TRANSFER'
          AND m.operation_uuid = t.operation_uuid AND m.admission_uuid = t.admission_uuid
          AND m.source_binding = t.original_request AND m.classification_state = 'KNOWN')
        OR (t.transfer_state <> 'WAITING_RETIREMENT' AND NOT EXISTS (
            SELECT 1 FROM entity_roster_effective_target_classifications m WHERE m.source_kind = 'TRANSFER_RETIREMENT'
              AND m.operation_uuid = t.operation_uuid AND m.admission_uuid = t.admission_uuid
              AND m.source_binding = t.original_request AND m.retirement_proof = t.observed_retirement AND m.classification_state = 'KNOWN')))
    OR EXISTS (SELECT 1 FROM entity_canonical_roster_retention_lifecycle l WHERE l.lifecycle_state IN ('RETIRED', 'DISPOSING')
        AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_retired_targets s
            WHERE s.snapshot_uuid = l.snapshot_uuid AND s.snapshot_digest = l.snapshot_digest AND s.owner_retirement_proof = l.owner_retirement_proof))
    OR EXISTS (SELECT 1 FROM entity_canonical_gameplay_roster_snapshots s WHERE NOT EXISTS (
        SELECT 1 FROM entity_roster_effective_snapshot_targets m
        WHERE m.snapshot_uuid = s.snapshot_uuid AND m.snapshot_digest = s.snapshot_digest
            AND m.classification_state = 'KNOWN' AND m.target_projection = entity_roster_snapshot_target_projection(s)))
    OR EXISTS (SELECT 1 FROM entity_roster_effective_snapshot_targets WHERE classification_state = 'UNKNOWN')
    OR EXISTS (SELECT 1 FROM entity_canonical_roster_snapshot_targets raw
        WHERE raw.recognition_version = 1 AND raw.source_kind = 'SNAPSHOT'
            AND raw.source_snapshot_projection->>'entry_policy' = 'PLAYER_CREATED'
            AND NOT EXISTS (SELECT 1 FROM entity_roster_effective_snapshot_targets typed
                WHERE typed.snapshot_uuid = raw.snapshot_uuid AND typed.snapshot_digest = raw.snapshot_digest
                    AND typed.source_kind = raw.source_kind AND typed.recognition_version = 2
                    AND typed.source_snapshot_projection = raw.source_snapshot_projection
                    AND typed.classification_state = 'KNOWN'))
    OR EXISTS (SELECT 1 FROM entity_canonical_roster_retired_targets s WHERE NOT EXISTS (
        SELECT 1 FROM entity_roster_effective_snapshot_targets m
        WHERE m.snapshot_uuid = s.snapshot_uuid AND m.snapshot_digest = s.snapshot_digest
            AND m.classification_state = 'KNOWN'
            AND ((s.payload_disposed_at IS NULL AND m.source_kind = 'SNAPSHOT')
                OR (s.payload_disposed_at IS NOT NULL AND m.source_kind = 'DISPOSED_SEAL' AND m.source_retirement_proof = s.owner_retirement_proof))))
    OR EXISTS (SELECT 1 FROM entity_canonical_roster_target_classifications raw
        WHERE raw.classification_state = 'UNKNOWN'
            AND entity_roster_classification_source_exists(raw.source_kind, raw.operation_uuid, raw.admission_uuid)
            AND NOT EXISTS (SELECT 1 FROM entity_roster_effective_target_classifications typed
                WHERE typed.source_kind = raw.source_kind AND typed.operation_uuid = raw.operation_uuid
                    AND typed.admission_uuid = raw.admission_uuid AND typed.source_binding = raw.source_binding
                    AND typed.normalization_version = 2 AND typed.classification_state = 'KNOWN'
                    AND (typed.source_kind <> 'TRANSFER_RETIREMENT' OR typed.retirement_proof IS NOT NULL)))
$$;
CREATE OR REPLACE FUNCTION entity_roster_retired_target_purgeable(target_id TEXT)
RETURNS BOOLEAN LANGUAGE SQL AS $$
    SELECT EXISTS (SELECT 1 FROM entity_canonical_roster_retired_targets WHERE target_digest = target_id)
        AND NOT entity_roster_target_classification_pending()
        AND NOT EXISTS (
            SELECT 1 FROM entity_canonical_roster_retired_targets s WHERE s.target_digest = target_id
                AND (s.payload_disposed_at IS NULL
                    OR s.observed_at + s.minimum_retention_millis * interval '1 millisecond' > clock_timestamp()
                    OR EXISTS (SELECT 1 FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = s.snapshot_uuid)
                    OR EXISTS (SELECT 1 FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = s.snapshot_uuid)
                    OR EXISTS (SELECT 1 FROM entity_canonical_roster_original_selections WHERE snapshot_uuid = s.snapshot_uuid)
                    OR EXISTS (SELECT 1 FROM entity_canonical_roster_disposal_receipts WHERE snapshot_uuid = s.snapshot_uuid)
                    OR EXISTS (
                        SELECT 1 FROM entity_roster_effective_snapshot_targets sealed
                        JOIN entity_roster_effective_snapshot_targets sibling USING (target_projection)
                        WHERE sealed.snapshot_uuid = s.snapshot_uuid AND sealed.snapshot_digest = s.snapshot_digest
                            AND (NOT EXISTS (SELECT 1 FROM entity_roster_effective_snapshot_targets done
                                    WHERE done.snapshot_uuid = sibling.snapshot_uuid AND done.snapshot_digest = sibling.snapshot_digest
                                        AND done.source_kind = 'DISPOSED_SEAL' AND done.classification_state = 'KNOWN'
                                        AND done.target_projection = sibling.target_projection)
                                OR EXISTS (SELECT 1 FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = sibling.snapshot_uuid)
                                OR EXISTS (SELECT 1 FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = sibling.snapshot_uuid)
                                OR EXISTS (SELECT 1 FROM entity_canonical_roster_original_selections WHERE snapshot_uuid = sibling.snapshot_uuid)
                                OR EXISTS (SELECT 1 FROM entity_canonical_roster_admissions WHERE snapshot_uuid = sibling.snapshot_uuid)
                                OR EXISTS (SELECT 1 FROM entity_canonical_roster_retirement_transfers WHERE snapshot_uuid = sibling.snapshot_uuid)
                                OR EXISTS (SELECT 1 FROM entity_canonical_roster_disposal_receipts WHERE snapshot_uuid = sibling.snapshot_uuid)))))
        AND NOT EXISTS (
            SELECT 1 FROM entity_canonical_roster_retired_targets s
            WHERE s.target_digest = target_id AND s.target_binding <> (
                SELECT first.target_binding FROM entity_canonical_roster_retired_targets first
                WHERE first.target_digest = target_id ORDER BY first.snapshot_uuid LIMIT 1))
        AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_admissions a
            JOIN entity_roster_effective_target_classifications m ON m.source_kind = 'ADMISSION'
                AND m.operation_uuid = a.operation_uuid AND m.admission_uuid = a.admission_uuid
                AND m.source_binding = a.request_binding
            WHERE m.target_digest = target_id)
        AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_retirement_transfers t
            JOIN entity_roster_effective_target_classifications m ON m.source_kind = 'TRANSFER'
                AND m.operation_uuid = t.operation_uuid AND m.admission_uuid = t.admission_uuid
                AND m.source_binding = t.original_request
            WHERE m.target_digest = target_id)
$$;
CREATE OR REPLACE FUNCTION entity_roster_retirement_prepared(snapshot_id UUID)
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
                  JOIN entity_roster_effective_target_classifications m ON m.source_kind = 'TRANSFER_RETIREMENT'
                    AND m.operation_uuid = t.operation_uuid AND m.admission_uuid = t.admission_uuid
                    AND m.classification_state = 'KNOWN' AND m.source_binding = t.original_request AND m.retirement_proof = t.observed_retirement
                  JOIN entity_canonical_roster_retention_references r ON r.reference_uuid = o.operation_uuid
                  JOIN entity_canonical_roster_retention_releases x ON x.reference_uuid = r.reference_uuid
                  WHERE a.operation_uuid = o.operation_uuid AND a.snapshot_uuid = o.snapshot_uuid
                    AND a.admission_state IN ('OPEN', 'CLOSED') AND a.entry_policy = o.entry_policy AND t.entry_policy = o.entry_policy AND o.entry_policy = l.entry_policy
                    AND t.original_request = o.owner_request AND t.snapshot_uuid = o.snapshot_uuid
                    AND t.snapshot_digest = o.snapshot_digest AND t.owner_fence = o.owner_fence
                    AND t.target_fence_uuid = o.owner_target_fence_uuid
                    AND t.transfer_state <> 'WAITING_RETIREMENT'
                    AND t.observed_retirement = l.owner_retirement_proof AND t.acknowledgement_request IS NOT NULL
                    AND r.acquire_operation_uuid = o.operation_uuid AND r.reference_kind = 'ORIGINAL_SELECTION'
                    AND r.snapshot_uuid = o.snapshot_uuid AND r.snapshot_digest = o.snapshot_digest
                    AND x.snapshot_uuid = o.snapshot_uuid AND x.snapshot_digest = o.snapshot_digest)))
$$;
/* [jooq ignore stop] */
