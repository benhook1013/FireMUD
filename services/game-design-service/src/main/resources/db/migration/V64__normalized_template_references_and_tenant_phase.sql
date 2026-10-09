ALTER TABLE game_design_template_config_source_revision
    ADD CONSTRAINT uq_template_config_revision_source_binding
        UNIQUE (canonical_tenant_id, canonical_version_id, commit_id, revision_id, template_id);

CREATE TABLE game_template_version_ref (
    canonical_tenant_id UUID NOT NULL,
    template_id BIGINT NOT NULL,
    canonical_version_id UUID NOT NULL,
    source_commit_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    PRIMARY KEY (canonical_tenant_id, template_id),
    FOREIGN KEY (template_id)
        REFERENCES game_design_template_config_source_qualification (template_id) ON DELETE RESTRICT,
    FOREIGN KEY (canonical_tenant_id, canonical_version_id)
        REFERENCES game_design_template_config_source_genesis (canonical_tenant_id, canonical_version_id)
        ON DELETE RESTRICT,
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, source_commit_id)
        REFERENCES game_design_template_config_source_application
            (canonical_tenant_id, canonical_version_id, commit_id) ON DELETE RESTRICT,
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, source_commit_id, source_revision_id, template_id)
        REFERENCES game_design_template_config_source_revision
            (canonical_tenant_id, canonical_version_id, commit_id, revision_id, template_id)
        ON DELETE RESTRICT,
    CHECK (canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID)
);
CREATE INDEX ix_game_template_version_ref_version
    ON game_template_version_ref (canonical_tenant_id, canonical_version_id, template_id);

CREATE TABLE game_template_reference_phase (
    canonical_tenant_id UUID PRIMARY KEY REFERENCES game (canonical_tenant_id) ON DELETE RESTRICT,
    source_game_row_id BIGINT NOT NULL REFERENCES game (id) ON DELETE RESTRICT,
    source_game_tenant_key VARCHAR(36) NOT NULL,
    creation_operation_id UUID NOT NULL UNIQUE
        REFERENCES game_tenant_creation_operations (operation_id) ON DELETE RESTRICT,
    creation_request_id UUID NOT NULL,
    creation_evidence_digest VARCHAR(71) NOT NULL,
    phase VARCHAR(16) NOT NULL,
    phase_epoch BIGINT NOT NULL,
    inventory_digest VARCHAR(71),
    inventory_template_count BIGINT,
    CHECK (canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (source_game_row_id > 0),
    CHECK (char_length(source_game_tenant_key) BETWEEN 1 AND 36),
    CHECK (creation_request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (creation_evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    CHECK (phase IN ('BACKFILLING', 'VALIDATED', 'ENFORCED')),
    CHECK (phase_epoch > 0),
    CHECK (
        (phase = 'BACKFILLING' AND inventory_digest IS NULL AND inventory_template_count IS NULL)
        OR (phase IN ('VALIDATED', 'ENFORCED')
            AND inventory_digest IS NOT NULL
            AND inventory_digest ~ '^sha256:[0-9a-f]{64}$'
            AND inventory_template_count IS NOT NULL
            AND inventory_template_count > 0)
    )
);

-- [jooq ignore start]
CREATE FUNCTION guard_game_template_reference_phase() RETURNS TRIGGER AS $$
DECLARE creation game_tenant_creation_operations%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'template reference phase authority is retained' USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'INSERT' THEN
        SELECT * INTO STRICT creation
        FROM game_tenant_creation_operations
        WHERE operation_id = NEW.creation_operation_id;
        IF NEW.phase <> 'BACKFILLING' OR NEW.phase_epoch <> 1
            OR NEW.inventory_digest IS NOT NULL OR NEW.inventory_template_count IS NOT NULL
            OR creation.status <> 'COMPLETED'
            OR creation.operation_id <> NEW.creation_operation_id
            OR creation.creation_request_id <> NEW.creation_request_id
            OR creation.canonical_tenant_id <> NEW.canonical_tenant_id
            OR creation.source_game_row_id <> NEW.source_game_row_id
            OR creation.source_game_tenant_key <> NEW.source_game_tenant_key
            OR creation.provenance_kind <> 'NEW_GAME_ROW'
            OR creation.evidence_digest <> NEW.creation_evidence_digest
            OR NOT EXISTS (
                SELECT 1 FROM game g
                WHERE g.id = NEW.source_game_row_id
                  AND g.tenant_id = NEW.source_game_tenant_key
                  AND g.canonical_tenant_id = NEW.canonical_tenant_id
                  AND g.tenant_identity_provenance_kind = 'NEW_GAME_ROW'
                  AND g.tenant_identity_source_game_id = g.id
                  AND g.tenant_identity_source_legacy_tenant_id = g.tenant_id
            ) THEN
            RAISE EXCEPTION 'template reference phase requires exact fresh Game creation evidence'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.canonical_tenant_id IS DISTINCT FROM OLD.canonical_tenant_id
        OR NEW.source_game_row_id IS DISTINCT FROM OLD.source_game_row_id
        OR NEW.source_game_tenant_key IS DISTINCT FROM OLD.source_game_tenant_key
        OR NEW.creation_operation_id IS DISTINCT FROM OLD.creation_operation_id
        OR NEW.creation_request_id IS DISTINCT FROM OLD.creation_request_id
        OR NEW.creation_evidence_digest IS DISTINCT FROM OLD.creation_evidence_digest
        OR NEW.phase_epoch <> OLD.phase_epoch + 1
        OR NOT (
            (OLD.phase = 'BACKFILLING' AND NEW.phase = 'VALIDATED'
                AND NEW.inventory_digest ~ '^sha256:[0-9a-f]{64}$'
                AND NEW.inventory_template_count > 0)
            OR (OLD.phase = 'VALIDATED' AND NEW.phase = 'ENFORCED'
                AND NEW.inventory_digest = OLD.inventory_digest
                AND NEW.inventory_template_count = OLD.inventory_template_count)
        ) THEN
        RAISE EXCEPTION 'template reference phase requires a monotonic evidence-backed CAS transition'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER game_template_reference_phase_guard
    BEFORE INSERT OR UPDATE OR DELETE ON game_template_reference_phase
    FOR EACH ROW EXECUTE FUNCTION guard_game_template_reference_phase();

CREATE FUNCTION reject_game_template_reference_phase_truncate() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'template reference phase authority cannot be truncated' USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER game_template_reference_phase_no_truncate
    BEFORE TRUNCATE ON game_template_reference_phase
    FOR EACH STATEMENT EXECUTE FUNCTION reject_game_template_reference_phase_truncate();

CREATE FUNCTION lock_and_guard_game_template_reference_write() RETURNS TRIGGER AS $$
DECLARE owner game%ROWTYPE; old_phase game_template_reference_phase%ROWTYPE;
    new_phase game_template_reference_phase%ROWTYPE;
    active_create BOOLEAN; active_upsert BOOLEAN;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
        PERFORM id FROM game WHERE tenant_id IN (OLD.tenant_id, NEW.tenant_id) ORDER BY id FOR UPDATE;
        SELECT p.* INTO old_phase FROM game_template_reference_phase p
        JOIN game g ON g.canonical_tenant_id = p.canonical_tenant_id
        WHERE g.tenant_id = OLD.tenant_id;
        SELECT p.* INTO new_phase FROM game_template_reference_phase p
        JOIN game g ON g.canonical_tenant_id = p.canonical_tenant_id
        WHERE g.tenant_id = NEW.tenant_id;
        IF old_phase.canonical_tenant_id IS NOT NULL OR new_phase.canonical_tenant_id IS NOT NULL THEN
            RAISE EXCEPTION 'template tenant cannot change after reference phase authority exists'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        SELECT * INTO owner FROM game WHERE tenant_id = OLD.tenant_id FOR UPDATE;
    ELSE
        SELECT * INTO owner FROM game WHERE tenant_id = NEW.tenant_id FOR UPDATE;
    END IF;
    IF NOT FOUND OR owner.canonical_tenant_id IS NULL THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
    END IF;

    SELECT * INTO new_phase FROM game_template_reference_phase
    WHERE canonical_tenant_id = owner.canonical_tenant_id;
    IF NOT FOUND OR new_phase.phase = 'BACKFILLING' THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'validated template rows require an owner source tombstone'
            USING ERRCODE = '23514';
    END IF;

    active_create := FALSE;
    active_upsert := FALSE;
    IF TG_OP = 'INSERT' THEN
        SELECT EXISTS (
            SELECT 1
            FROM version v
            JOIN game_design_draft_commit c
              ON c.canonical_tenant_id = v.canonical_tenant_id
             AND c.canonical_version_id = v.canonical_version_id
            JOIN game_design_draft_commit_application_slot slot
              ON slot.canonical_tenant_id = c.canonical_tenant_id
             AND slot.canonical_version_id = c.canonical_version_id
             AND slot.request_id = c.request_id AND slot.commit_id = c.commit_id
            JOIN game_design_draft_commit_owner_result result
              ON result.canonical_tenant_id = c.canonical_tenant_id
             AND result.canonical_version_id = c.canonical_version_id
             AND result.request_id = c.request_id AND result.owner = 'GAME_DESIGN_CONTROL_PLANE'
             AND result.status IN ('IN_PROGRESS', 'UNKNOWN')
            CROSS JOIN LATERAL jsonb_array_elements(c.binding_json::JSONB->'revisions') revision
            WHERE v.id = NEW.default_version_id AND v.tenant_id = NEW.tenant_id
              AND v.canonical_tenant_id = owner.canonical_tenant_id
              AND c.canonical_tenant_id = owner.canonical_tenant_id
              AND revision->>'owner' = 'GAME_DESIGN_CONTROL_PLANE'
              AND (revision->>'payload')::JSONB->>'revisionKind' = 'TEMPLATE_CONFIG'
              AND (revision->>'payload')::JSONB->>'operation' = 'CREATE'
              AND (revision->>'payload')::JSONB->>'templateName' = NEW.name
              AND ((revision->>'payload')::JSONB->>'configJson')::JSONB = NEW.config
        ) INTO active_create;
        IF NOT active_create THEN
            RAISE EXCEPTION 'validated template create requires exact active owner source binding'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        SELECT EXISTS (
            SELECT 1
            FROM game_design_template_config_source_qualification q
            JOIN game_design_draft_commit c
              ON c.canonical_tenant_id = q.canonical_tenant_id
             AND c.canonical_version_id = q.canonical_version_id
            JOIN game_design_draft_commit_application_slot slot
              ON slot.canonical_tenant_id = c.canonical_tenant_id
             AND slot.canonical_version_id = c.canonical_version_id
             AND slot.request_id = c.request_id AND slot.commit_id = c.commit_id
            JOIN game_design_draft_commit_owner_result result
              ON result.canonical_tenant_id = c.canonical_tenant_id
             AND result.canonical_version_id = c.canonical_version_id
             AND result.request_id = c.request_id AND result.owner = 'GAME_DESIGN_CONTROL_PLANE'
             AND result.status IN ('IN_PROGRESS', 'UNKNOWN')
            CROSS JOIN LATERAL jsonb_array_elements(c.binding_json::JSONB->'revisions') revision
            WHERE q.template_id = OLD.id AND q.canonical_tenant_id = owner.canonical_tenant_id
              AND c.canonical_tenant_id = owner.canonical_tenant_id
              AND revision->>'owner' = 'GAME_DESIGN_CONTROL_PLANE'
              AND (revision->>'payload')::JSONB->>'revisionKind' = 'TEMPLATE_CONFIG'
              AND (revision->>'payload')::JSONB->>'operation' = 'UPSERT'
              AND (revision->>'payload')::JSONB->>'templateId' = OLD.id::TEXT
              AND ((revision->>'payload')::JSONB->>'configJson')::JSONB = NEW.config
        ) INTO active_upsert;
        IF NEW.id IS DISTINCT FROM OLD.id OR NEW.name IS DISTINCT FROM OLD.name
            OR NEW.default_version_id IS DISTINCT FROM OLD.default_version_id
            OR NEW.default_script_patch_version IS DISTINCT FROM OLD.default_script_patch_version
            OR NEW.default_runtime_flags_json IS DISTINCT FROM OLD.default_runtime_flags_json
            OR NEW.template_reference_phase IS DISTINCT FROM OLD.template_reference_phase
            OR NOT active_upsert THEN
            RAISE EXCEPTION 'validated template update requires exact active owner source binding'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER game_template_reference_tenant_mutex_and_guard
    BEFORE INSERT OR UPDATE OR DELETE ON game_templates
    FOR EACH ROW EXECUTE FUNCTION lock_and_guard_game_template_reference_write();

CREATE FUNCTION guard_game_template_version_ref() RETURNS TRIGGER AS $$
DECLARE owner game%ROWTYPE; template game_templates%ROWTYPE; target version%ROWTYPE;
    revision game_design_template_config_source_revision%ROWTYPE;
BEGIN
    SELECT * INTO owner FROM game WHERE canonical_tenant_id =
        CASE WHEN TG_OP = 'DELETE' THEN OLD.canonical_tenant_id ELSE NEW.canonical_tenant_id END FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'normalized template reference lacks exact Game owner' USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'INSERT' THEN
        SELECT * INTO STRICT template FROM game_templates WHERE id = NEW.template_id;
        SELECT * INTO STRICT target FROM version
        WHERE canonical_tenant_id = NEW.canonical_tenant_id
          AND canonical_version_id = NEW.canonical_version_id;
        SELECT * INTO STRICT revision FROM game_design_template_config_source_revision
        WHERE canonical_tenant_id = NEW.canonical_tenant_id
          AND canonical_version_id = NEW.canonical_version_id
          AND commit_id = NEW.source_commit_id
          AND revision_id = NEW.source_revision_id
          AND template_id = NEW.template_id;
        IF template.tenant_id <> owner.tenant_id
            OR template.default_version_id <> target.id
            OR target.tenant_id <> owner.tenant_id
            OR target.identity_source_game_row_id <> owner.id
            OR target.identity_source_game_tenant_key <> owner.tenant_id
            OR target.identity_source_provenance_kind <> owner.tenant_identity_provenance_kind
            OR revision.operation_kind NOT IN ('CREATE', 'UPSERT')
            OR NOT EXISTS (
                SELECT 1 FROM game_design_template_config_source_ref ref
                WHERE ref.canonical_tenant_id = NEW.canonical_tenant_id
                  AND ref.canonical_version_id = NEW.canonical_version_id
                  AND ref.revision_id = NEW.source_revision_id
                  AND ref.ref_kind = 'BASE_VERSION'
                  AND ref.ref_key = NEW.canonical_version_id::TEXT
                  AND ref.referenced_revision_id IS NULL
            )
            OR NOT EXISTS (
                SELECT 1
                FROM game_design_draft_commit c
                JOIN game_design_draft_commit_application_slot slot
                  ON slot.canonical_tenant_id = c.canonical_tenant_id
                 AND slot.canonical_version_id = c.canonical_version_id
                 AND slot.request_id = c.request_id AND slot.commit_id = c.commit_id
                JOIN game_design_draft_commit_owner_result result
                  ON result.canonical_tenant_id = c.canonical_tenant_id
                 AND result.canonical_version_id = c.canonical_version_id
                 AND result.request_id = c.request_id
                 AND result.owner = 'GAME_DESIGN_CONTROL_PLANE'
                 AND result.status IN ('IN_PROGRESS', 'UNKNOWN')
                WHERE c.canonical_tenant_id = NEW.canonical_tenant_id
                  AND c.canonical_version_id = NEW.canonical_version_id
                  AND c.request_id = revision.request_id
                  AND c.commit_id = NEW.source_commit_id
            )
            OR ((revision.payload_json::JSONB)->>'configJson')::JSONB IS DISTINCT FROM template.config THEN
            RAISE EXCEPTION 'normalized template base reference differs from exact authorized source'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF OLD.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
            OR OLD.template_id IS DISTINCT FROM NEW.template_id
            OR OLD.canonical_version_id IS DISTINCT FROM NEW.canonical_version_id THEN
            RAISE EXCEPTION 'normalized template base identity is immutable'
                USING ERRCODE = '23514';
        END IF;
        SELECT * INTO STRICT template FROM game_templates WHERE id = NEW.template_id;
        SELECT * INTO STRICT target FROM version
        WHERE canonical_tenant_id = NEW.canonical_tenant_id
          AND canonical_version_id = NEW.canonical_version_id;
        SELECT * INTO STRICT revision FROM game_design_template_config_source_revision
        WHERE canonical_tenant_id = NEW.canonical_tenant_id
          AND canonical_version_id = NEW.canonical_version_id
          AND commit_id = NEW.source_commit_id
          AND revision_id = NEW.source_revision_id
          AND template_id = NEW.template_id;
        IF template.tenant_id <> owner.tenant_id
            OR template.default_version_id <> target.id
            OR target.tenant_id <> owner.tenant_id
            OR target.identity_source_game_row_id <> owner.id
            OR target.identity_source_game_tenant_key <> owner.tenant_id
            OR target.identity_source_provenance_kind <> owner.tenant_identity_provenance_kind
            OR revision.operation_kind <> 'UPSERT'
            OR NOT EXISTS (
                SELECT 1 FROM game_design_template_config_source_ref ref
                WHERE ref.canonical_tenant_id = NEW.canonical_tenant_id
                  AND ref.canonical_version_id = NEW.canonical_version_id
                  AND ref.revision_id = NEW.source_revision_id
                  AND ref.ref_kind = 'BASE_VERSION'
                  AND ref.ref_key = NEW.canonical_version_id::TEXT
                  AND ref.referenced_revision_id IS NULL
            )
            OR NOT EXISTS (
                SELECT 1
                FROM game_design_draft_commit c
                JOIN game_design_draft_commit_application_slot slot
                  ON slot.canonical_tenant_id = c.canonical_tenant_id
                 AND slot.canonical_version_id = c.canonical_version_id
                 AND slot.request_id = c.request_id AND slot.commit_id = c.commit_id
                JOIN game_design_draft_commit_owner_result result
                  ON result.canonical_tenant_id = c.canonical_tenant_id
                 AND result.canonical_version_id = c.canonical_version_id
                 AND result.request_id = c.request_id
                 AND result.owner = 'GAME_DESIGN_CONTROL_PLANE'
                 AND result.status IN ('IN_PROGRESS', 'UNKNOWN')
                WHERE c.canonical_tenant_id = NEW.canonical_tenant_id
                  AND c.canonical_version_id = NEW.canonical_version_id
                  AND c.request_id = revision.request_id
                  AND c.commit_id = NEW.source_commit_id
            )
            OR ((revision.payload_json::JSONB)->>'configJson')::JSONB IS DISTINCT FROM template.config THEN
            RAISE EXCEPTION 'normalized template base update differs from exact owner upsert'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    SELECT r.* INTO STRICT revision
    FROM game_design_draft_commit_application_slot slot
    JOIN game_design_template_config_source_revision r
      ON r.canonical_tenant_id = slot.canonical_tenant_id
     AND r.canonical_version_id = slot.canonical_version_id
     AND r.request_id = slot.request_id AND r.commit_id = slot.commit_id
    WHERE slot.canonical_tenant_id = OLD.canonical_tenant_id
      AND slot.canonical_version_id = OLD.canonical_version_id
      AND r.template_id = OLD.template_id AND r.operation_kind = 'DELETE';
    IF NOT EXISTS (
        SELECT 1
        FROM game_design_draft_commit c
        JOIN game_design_draft_commit_application_slot slot
          ON slot.canonical_tenant_id = c.canonical_tenant_id
         AND slot.canonical_version_id = c.canonical_version_id
         AND slot.request_id = c.request_id AND slot.commit_id = c.commit_id
        JOIN game_design_draft_commit_owner_result result
          ON result.canonical_tenant_id = c.canonical_tenant_id
         AND result.canonical_version_id = c.canonical_version_id
         AND result.request_id = c.request_id AND result.owner = 'GAME_DESIGN_CONTROL_PLANE'
         AND result.status IN ('IN_PROGRESS', 'UNKNOWN')
        WHERE c.canonical_tenant_id = OLD.canonical_tenant_id
          AND c.canonical_version_id = OLD.canonical_version_id
          AND c.commit_id = revision.commit_id
          AND c.request_id = revision.request_id
    ) THEN
        RAISE EXCEPTION 'normalized template base removal requires exact active source tombstone'
            USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER game_template_version_ref_guard
    BEFORE INSERT OR UPDATE OR DELETE ON game_template_version_ref
    FOR EACH ROW EXECUTE FUNCTION guard_game_template_version_ref();

CREATE FUNCTION reject_game_template_version_ref_truncate() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'normalized template references cannot be truncated' USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER game_template_version_ref_no_truncate
    BEFORE TRUNCATE ON game_template_version_ref
    FOR EACH STATEMENT EXECUTE FUNCTION reject_game_template_version_ref_truncate();
-- [jooq ignore stop]
