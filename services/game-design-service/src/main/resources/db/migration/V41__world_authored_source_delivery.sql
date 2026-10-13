ALTER TABLE game_design_authored_world_source_operations
    ADD CONSTRAINT uq_gd_authored_world_source_delivery_source UNIQUE (
        operation_id,
        schema_version,
        target_namespace,
        registration_request_id,
        request_digest,
        canonical_tenant_id,
        tenant_slug,
        world_slug,
        world_display_name,
        source_game_row_id,
        source_game_tenant_key,
        provenance_kind,
        evidence_digest
    );

CREATE TABLE game_design_authored_world_source_deliveries (
    source_operation_id UUID PRIMARY KEY,
    source_schema_version INTEGER NOT NULL CHECK (source_schema_version = 1),
    target_namespace VARCHAR(63) NOT NULL,
    registration_request_id UUID NOT NULL,
    source_request_digest VARCHAR(71) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    tenant_slug VARCHAR(120) NOT NULL,
    world_slug VARCHAR(120) NOT NULL,
    world_display_name VARCHAR(400) NOT NULL,
    source_game_row_id BIGINT NOT NULL,
    source_game_tenant_key VARCHAR(36) NOT NULL,
    provenance_kind VARCHAR(32) NOT NULL,
    source_evidence_digest VARCHAR(71) NOT NULL,
    intake_schema_version INTEGER NOT NULL CHECK (intake_schema_version = 1),
    intake_request_id UUID NOT NULL,
    intake_request_digest VARCHAR(71) NOT NULL,
    world_operation_id UUID,
    world_request_digest VARCHAR(71),
    world_receipt_digest VARCHAR(71),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    acknowledged_at TIMESTAMPTZ,
    CONSTRAINT uq_gd_authored_world_source_delivery_request
        UNIQUE (target_namespace, intake_request_id),
    CONSTRAINT fk_gd_authored_world_source_delivery_source FOREIGN KEY (
        source_operation_id,
        source_schema_version,
        target_namespace,
        registration_request_id,
        source_request_digest,
        canonical_tenant_id,
        tenant_slug,
        world_slug,
        world_display_name,
        source_game_row_id,
        source_game_tenant_key,
        provenance_kind,
        source_evidence_digest
    ) REFERENCES game_design_authored_world_source_operations (
        operation_id,
        schema_version,
        target_namespace,
        registration_request_id,
        request_digest,
        canonical_tenant_id,
        tenant_slug,
        world_slug,
        world_display_name,
        source_game_row_id,
        source_game_tenant_key,
        provenance_kind,
        evidence_digest
    ),
    CONSTRAINT chk_gd_authored_world_source_delivery_fresh CHECK (
        provenance_kind = 'NEW_GAME_ROW'
    ),
    CONSTRAINT chk_gd_authored_world_source_delivery_ids CHECK (
        source_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND registration_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND intake_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND source_game_row_id > 0
    ),
    CONSTRAINT chk_gd_authored_world_source_delivery_namespace CHECK (
        target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
    ),
    CONSTRAINT chk_gd_authored_world_source_delivery_digests CHECK (
        source_request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
        AND intake_request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND (
            (world_operation_id IS NULL
                AND world_request_digest IS NULL
                AND world_receipt_digest IS NULL
                AND acknowledged_at IS NULL)
            OR
            (world_operation_id IS NOT NULL
                AND world_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
                AND world_request_digest IS NOT NULL
                AND world_request_digest ~ '^sha256:[0-9a-f]{64}$'
                AND world_receipt_digest IS NOT NULL
                AND world_receipt_digest ~ '^sha256:[0-9a-f]{64}$'
                AND acknowledged_at IS NOT NULL)
        )
    ),
    CONSTRAINT chk_gd_authored_world_source_delivery_slugs CHECK (
        octet_length(tenant_slug) BETWEEN 1 AND 120
        AND tenant_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        AND octet_length(world_slug) BETWEEN 1 AND 120
        AND world_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
    ),
    CONSTRAINT chk_gd_authored_world_source_delivery_display_name CHECK (
        char_length(world_display_name) BETWEEN 1 AND 100
        AND octet_length(world_display_name) <= 400
        AND world_display_name !~ '^[[:space:]]*$'
    ),
    CONSTRAINT chk_gd_authored_world_source_delivery_source_key CHECK (
        char_length(source_game_tenant_key) BETWEEN 1 AND 36
        AND source_game_tenant_key !~ '^[[:space:]]*$'
    )
);

-- [jooq ignore start]
CREATE FUNCTION enforce_gd_authored_world_source_delivery() RETURNS trigger AS $$
DECLARE
    source_matches BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Game Design authored-world source delivery is immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.world_operation_id IS NOT NULL
            OR NEW.world_request_digest IS NOT NULL
            OR NEW.world_receipt_digest IS NOT NULL
            OR NEW.acknowledged_at IS NOT NULL THEN
            RAISE EXCEPTION 'New Game Design authored-world delivery cannot start acknowledged'
                USING ERRCODE = 'check_violation';
        END IF;

        SELECT EXISTS (
            SELECT 1
            FROM game_design_authored_world_source_operations o
            JOIN game_design_tenant_slug_binding b
              ON b.target_namespace = o.target_namespace
             AND b.canonical_tenant_id = o.canonical_tenant_id
             AND b.tenant_slug = o.tenant_slug
             AND b.source_game_row_id = o.source_game_row_id
             AND b.source_game_tenant_key = o.source_game_tenant_key
             AND b.provenance_kind = o.provenance_kind
            JOIN game g
              ON g.id = o.source_game_row_id
             AND g.tenant_id = o.source_game_tenant_key
             AND g.canonical_tenant_id = o.canonical_tenant_id
             AND g.tenant_identity_provenance_kind = o.provenance_kind
             AND g.tenant_identity_source_game_id = g.id
             AND g.tenant_identity_source_legacy_tenant_id = g.tenant_id
            WHERE o.operation_id = NEW.source_operation_id
              AND o.schema_version = NEW.source_schema_version
              AND o.target_namespace = NEW.target_namespace
              AND o.registration_request_id = NEW.registration_request_id
              AND o.request_digest = NEW.source_request_digest
              AND o.canonical_tenant_id = NEW.canonical_tenant_id
              AND o.tenant_slug = NEW.tenant_slug
              AND o.world_slug = NEW.world_slug
              AND o.world_display_name = NEW.world_display_name
              AND o.source_game_row_id = NEW.source_game_row_id
              AND o.source_game_tenant_key = NEW.source_game_tenant_key
              AND o.provenance_kind = 'NEW_GAME_ROW'
              AND o.evidence_digest = NEW.source_evidence_digest
        ) INTO source_matches;

        IF NOT source_matches THEN
            RAISE EXCEPTION 'Game Design authored-world delivery has no exact fresh source'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF ROW(
        NEW.source_operation_id,
        NEW.source_schema_version,
        NEW.target_namespace,
        NEW.registration_request_id,
        NEW.source_request_digest,
        NEW.canonical_tenant_id,
        NEW.tenant_slug,
        NEW.world_slug,
        NEW.world_display_name,
        NEW.source_game_row_id,
        NEW.source_game_tenant_key,
        NEW.provenance_kind,
        NEW.source_evidence_digest,
        NEW.intake_schema_version,
        NEW.intake_request_id,
        NEW.intake_request_digest,
        NEW.created_at
    ) IS DISTINCT FROM ROW(
        OLD.source_operation_id,
        OLD.source_schema_version,
        OLD.target_namespace,
        OLD.registration_request_id,
        OLD.source_request_digest,
        OLD.canonical_tenant_id,
        OLD.tenant_slug,
        OLD.world_slug,
        OLD.world_display_name,
        OLD.source_game_row_id,
        OLD.source_game_tenant_key,
        OLD.provenance_kind,
        OLD.source_evidence_digest,
        OLD.intake_schema_version,
        OLD.intake_request_id,
        OLD.intake_request_digest,
        OLD.created_at
    ) THEN
        RAISE EXCEPTION 'Game Design authored-world delivery claim cannot be rebound'
            USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.world_operation_id IS NULL
        AND OLD.world_request_digest IS NULL
        AND OLD.world_receipt_digest IS NULL
        AND OLD.acknowledged_at IS NULL
        AND NEW.world_operation_id IS NOT NULL
        AND NEW.world_request_digest IS NOT NULL
        AND NEW.world_receipt_digest IS NOT NULL
        AND NEW.acknowledged_at IS NOT NULL THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'Game Design authored-world delivery acknowledgement is immutable'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER gd_authored_world_source_delivery_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON game_design_authored_world_source_deliveries
    FOR EACH ROW EXECUTE FUNCTION enforce_gd_authored_world_source_delivery();

CREATE FUNCTION reject_gd_authored_world_source_delivery_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Game Design authored-world source delivery is immutable'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER gd_authored_world_source_delivery_no_truncate
    BEFORE TRUNCATE ON game_design_authored_world_source_deliveries
    FOR EACH STATEMENT EXECUTE FUNCTION reject_gd_authored_world_source_delivery_truncate();
-- [jooq ignore end]
