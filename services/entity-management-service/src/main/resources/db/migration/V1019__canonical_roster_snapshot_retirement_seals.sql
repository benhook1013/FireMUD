-- One account/full-target acquisition fence, independently authenticated snapshot seals.
-- Existing snapshot/seal tuple bytes and xmin are preserved; dependency metadata is additive.
-- PostgreSQL names the inherited implicit key; jOOQ's H2 DDL simulation uses a different name.
-- These owner queries use explicit SQL identity predicates; physical Flyway proof checks this key.
/* [jooq ignore start] */
ALTER TABLE entity_canonical_roster_retired_targets
    DROP CONSTRAINT entity_canonical_roster_retired_targets_pkey;
ALTER TABLE entity_canonical_roster_retired_targets
    ADD PRIMARY KEY (target_digest, snapshot_uuid);
/* [jooq ignore stop] */

-- Non-authoritative dependency inventory. Source tuples and seal proof/identity remain immutable.
CREATE TABLE entity_canonical_roster_snapshot_targets (
    snapshot_uuid UUID NOT NULL,
    snapshot_digest VARCHAR(64) NOT NULL CHECK (snapshot_digest ~ '^[0-9a-f]{64}$'),
    source_kind VARCHAR(16) NOT NULL CHECK (source_kind IN ('SNAPSHOT', 'DISPOSED_SEAL')),
    classification_state VARCHAR(8) NOT NULL CHECK (classification_state IN ('KNOWN', 'UNKNOWN')),
    target_projection JSONB,
    source_snapshot_projection JSONB,
    source_retirement_proof BYTEA,
    PRIMARY KEY (snapshot_uuid, source_kind),
    CHECK ((classification_state = 'KNOWN' AND target_projection IS NOT NULL)
        OR (classification_state = 'UNKNOWN' AND target_projection IS NULL)),
    CHECK ((source_kind = 'SNAPSHOT' AND source_snapshot_projection IS NOT NULL AND source_retirement_proof IS NULL)
        OR (source_kind = 'DISPOSED_SEAL' AND source_snapshot_projection IS NULL AND source_retirement_proof IS NOT NULL))
);
CREATE INDEX idx_entity_roster_snapshot_target_projection
    ON entity_canonical_roster_snapshot_targets USING HASH (target_projection);

/* [jooq ignore start] */
CREATE FUNCTION entity_roster_target_projection(p_canonical_account_uuid UUID, p_tenant_uuid UUID, p_realm_uuid UUID, p_world_slug TEXT, p_realm_slug TEXT, p_game_instance_uuid UUID, p_catalog_revision BIGINT, p_canonical_version_uuid UUID, p_pointer_version BIGINT, p_active_world_epoch BIGINT, p_published_policy_digest TEXT, p_published_release_bundle_ref TEXT, p_admission_pointer_snapshot_digest TEXT, p_published_owner_proof_digest TEXT, p_playable_state_namespace_uuid UUID, p_playable_state_scope TEXT, p_entry_policy TEXT, p_canonical_player_created_source_identity BYTEA, p_publication_evidence_format TEXT)
RETURNS JSONB LANGUAGE SQL IMMUTABLE AS $$
    SELECT jsonb_build_object(
        'canonical_account_uuid', p_canonical_account_uuid,
        'tenant_uuid', p_tenant_uuid,
        'realm_uuid', p_realm_uuid,
        'world_slug', p_world_slug,
        'realm_slug', p_realm_slug,
        'game_instance_uuid', p_game_instance_uuid,
        'catalog_revision', p_catalog_revision,
        'canonical_version_uuid', p_canonical_version_uuid,
        'pointer_version', p_pointer_version,
        'active_world_epoch', p_active_world_epoch,
        'published_policy_digest', p_published_policy_digest,
        'published_release_bundle_ref', p_published_release_bundle_ref,
        'admission_pointer_snapshot_digest', p_admission_pointer_snapshot_digest,
        'published_owner_proof_digest', p_published_owner_proof_digest,
        'playable_state_namespace_uuid', p_playable_state_namespace_uuid,
        'playable_state_scope', p_playable_state_scope,
        'entry_policy', p_entry_policy,
        'canonical_player_created_source_identity', p_canonical_player_created_source_identity,
        'publication_evidence_format', p_publication_evidence_format)
$$;
CREATE FUNCTION entity_roster_snapshot_target_projection(s entity_canonical_gameplay_roster_snapshots)
RETURNS JSONB LANGUAGE SQL IMMUTABLE AS $$
    SELECT entity_roster_target_projection(s.canonical_account_uuid, s.tenant_uuid, s.realm_uuid, s.world_slug, s.realm_slug, s.game_instance_uuid, s.catalog_revision, s.canonical_version_uuid, s.pointer_version, s.active_world_epoch, s.published_policy_digest, s.published_release_bundle_ref, s.admission_pointer_snapshot_digest, s.published_owner_proof_digest, s.playable_state_namespace_uuid, s.playable_state_scope, s.entry_policy, s.canonical_player_created_source_identity, s.publication_evidence_format)
$$;
CREATE FUNCTION entity_roster_snapshot_target_known(s entity_canonical_gameplay_roster_snapshots)
RETURNS BOOLEAN LANGUAGE SQL IMMUTABLE AS $$
    SELECT COALESCE(s.publication_evidence_format = 'CANONICAL_UUID_V2'
        AND s.canonical_version_uuid IS NOT NULL AND s.pointer_version > 0 AND s.active_world_epoch > 0, FALSE)
$$;
CREATE FUNCTION entity_roster_snapshot_target_dependency(snapshot_id UUID)
RETURNS BOOLEAN LANGUAGE SQL AS $$
    SELECT EXISTS (SELECT 1 FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = snapshot_id)
        OR EXISTS (SELECT 1 FROM entity_canonical_roster_retired_targets WHERE snapshot_uuid = snapshot_id)
        OR EXISTS (SELECT 1 FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = snapshot_id)
        OR EXISTS (SELECT 1 FROM entity_canonical_roster_original_selections WHERE snapshot_uuid = snapshot_id)
        OR EXISTS (SELECT 1 FROM entity_canonical_roster_admissions WHERE snapshot_uuid = snapshot_id)
        OR EXISTS (SELECT 1 FROM entity_canonical_roster_retirement_transfers WHERE snapshot_uuid = snapshot_id)
        OR EXISTS (SELECT 1 FROM entity_canonical_roster_disposal_receipts WHERE snapshot_uuid = snapshot_id)
        OR EXISTS (
            SELECT 1 FROM entity_canonical_roster_snapshot_targets own
            JOIN entity_canonical_roster_snapshot_targets sibling USING (target_projection)
            JOIN entity_canonical_roster_retired_targets seal ON seal.snapshot_uuid = sibling.snapshot_uuid
            WHERE own.snapshot_uuid = snapshot_id)
$$;
CREATE FUNCTION guard_entity_roster_snapshot_target()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
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
                        SELECT 1 FROM entity_canonical_roster_snapshot_targets m
                        WHERE m.snapshot_uuid = NEW.snapshot_uuid AND m.source_kind = 'SNAPSHOT'
                            AND (m.classification_state <> 'KNOWN' OR m.target_projection <> NEW.target_projection))))) THEN RETURN NEW; END IF;
    ELSIF TG_OP = 'DELETE' AND NOT entity_roster_snapshot_target_dependency(OLD.snapshot_uuid) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'snapshot target classification source mismatch or remains protected' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER trg_entity_roster_snapshot_target_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON entity_canonical_roster_snapshot_targets
    FOR EACH ROW EXECUTE FUNCTION guard_entity_roster_snapshot_target();
CREATE TRIGGER trg_entity_roster_snapshot_target_no_truncate
    BEFORE TRUNCATE ON entity_canonical_roster_snapshot_targets
    FOR EACH STATEMENT EXECUTE FUNCTION reject_entity_assignment_history_removal();
CREATE FUNCTION capture_entity_roster_snapshot_target()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.construction_state = 'SEALED' THEN
        INSERT INTO entity_canonical_roster_snapshot_targets
            (snapshot_uuid, snapshot_digest, source_kind, classification_state, target_projection, source_snapshot_projection)
            VALUES (NEW.snapshot_uuid, NEW.snapshot_digest, 'SNAPSHOT',
                CASE WHEN entity_roster_snapshot_target_known(NEW::entity_canonical_gameplay_roster_snapshots) THEN 'KNOWN' ELSE 'UNKNOWN' END,
                CASE WHEN entity_roster_snapshot_target_known(NEW::entity_canonical_gameplay_roster_snapshots) THEN entity_roster_snapshot_target_projection(NEW::entity_canonical_gameplay_roster_snapshots) ELSE NULL END,
                entity_roster_snapshot_target_projection(NEW::entity_canonical_gameplay_roster_snapshots))
            ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_entity_roster_snapshot_target_capture
    AFTER INSERT OR UPDATE ON entity_canonical_gameplay_roster_snapshots
    FOR EACH ROW EXECUTE FUNCTION capture_entity_roster_snapshot_target();
-- Retain the actual positive guarded disposal proof through bounded sibling seal cleanup.
CREATE FUNCTION capture_entity_roster_snapshot_target_disposal()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.payload_disposed_at IS NOT NULL THEN
        INSERT INTO entity_canonical_roster_snapshot_targets
            (snapshot_uuid, snapshot_digest, source_kind, classification_state, target_projection, source_retirement_proof)
            SELECT NEW.snapshot_uuid, NEW.snapshot_digest, 'DISPOSED_SEAL', 'KNOWN', m.target_projection, NEW.owner_retirement_proof
            FROM entity_canonical_roster_snapshot_targets m
            WHERE m.snapshot_uuid = NEW.snapshot_uuid AND m.snapshot_digest = NEW.snapshot_digest
                AND m.source_kind = 'SNAPSHOT' AND m.classification_state = 'KNOWN'
            ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_entity_roster_snapshot_target_disposal
    AFTER UPDATE ON entity_canonical_roster_retired_targets
    FOR EACH ROW EXECUTE FUNCTION capture_entity_roster_snapshot_target_disposal();
CREATE FUNCTION entity_roster_initialize_snapshot_targets(page_limit INTEGER)
RETURNS INTEGER LANGUAGE plpgsql AS $$
DECLARE initialized INTEGER;
BEGIN
    IF page_limit <= 0 THEN RAISE EXCEPTION 'bounded snapshot target initialization required'; END IF;
    INSERT INTO entity_canonical_roster_snapshot_targets
        (snapshot_uuid, snapshot_digest, source_kind, classification_state, target_projection, source_snapshot_projection)
        SELECT s.snapshot_uuid, s.snapshot_digest, 'SNAPSHOT',
            CASE WHEN entity_roster_snapshot_target_known(s) THEN 'KNOWN' ELSE 'UNKNOWN' END,
            CASE WHEN entity_roster_snapshot_target_known(s) THEN entity_roster_snapshot_target_projection(s) ELSE NULL END,
            entity_roster_snapshot_target_projection(s)
        FROM entity_canonical_gameplay_roster_snapshots s
        WHERE s.construction_state = 'SEALED' AND NOT EXISTS (
            SELECT 1 FROM entity_canonical_roster_snapshot_targets m WHERE m.snapshot_uuid = s.snapshot_uuid AND m.source_kind = 'SNAPSHOT')
        ORDER BY s.snapshot_uuid LIMIT page_limit ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS initialized = ROW_COUNT;
    RETURN initialized;
END;
$$;
/* [jooq ignore stop] */

/* [jooq ignore start] */
CREATE OR REPLACE FUNCTION entity_roster_target_classification_pending()
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
    OR EXISTS (SELECT 1 FROM entity_canonical_gameplay_roster_snapshots s WHERE NOT EXISTS (
        SELECT 1 FROM entity_canonical_roster_snapshot_targets m
        WHERE m.snapshot_uuid = s.snapshot_uuid AND m.snapshot_digest = s.snapshot_digest
            AND m.classification_state = 'KNOWN' AND m.target_projection = entity_roster_snapshot_target_projection(s)))
    OR EXISTS (SELECT 1 FROM entity_canonical_roster_snapshot_targets WHERE classification_state = 'UNKNOWN')
    OR EXISTS (SELECT 1 FROM entity_canonical_roster_retired_targets s WHERE NOT EXISTS (
        SELECT 1 FROM entity_canonical_roster_snapshot_targets m
        WHERE m.snapshot_uuid = s.snapshot_uuid AND m.snapshot_digest = s.snapshot_digest
            AND m.classification_state = 'KNOWN'
            AND ((s.payload_disposed_at IS NULL AND m.source_kind = 'SNAPSHOT')
                OR (s.payload_disposed_at IS NOT NULL AND m.source_kind = 'DISPOSED_SEAL' AND m.source_retirement_proof = s.owner_retirement_proof))))
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
                        SELECT 1 FROM entity_canonical_roster_snapshot_targets sealed
                        JOIN entity_canonical_roster_snapshot_targets sibling USING (target_projection)
                        WHERE sealed.snapshot_uuid = s.snapshot_uuid AND sealed.snapshot_digest = s.snapshot_digest
                            AND (NOT EXISTS (SELECT 1 FROM entity_canonical_roster_snapshot_targets done
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
            JOIN entity_canonical_roster_target_classifications m ON m.source_kind = 'ADMISSION'
                AND m.operation_uuid = a.operation_uuid AND m.admission_uuid = a.admission_uuid
                AND m.source_binding = a.request_binding
            WHERE m.target_digest = target_id)
        AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_retirement_transfers t
            JOIN entity_canonical_roster_target_classifications m ON m.source_kind = 'TRANSFER'
                AND m.operation_uuid = t.operation_uuid AND m.admission_uuid = t.admission_uuid
                AND m.source_binding = t.original_request
            WHERE m.target_digest = target_id)
$$;
/* [jooq ignore stop] */
