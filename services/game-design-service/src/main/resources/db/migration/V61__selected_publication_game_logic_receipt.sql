-- Derived receipt storage only. Historical selections and publication operations are untouched.
CREATE TABLE game_design_selected_game_logic_receipt (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    publish_request_id VARCHAR(1024) NOT NULL,
    selection_digest VARCHAR(71) NOT NULL,
    workflow_identity TEXT NOT NULL,
    selection_bytes BYTEA NOT NULL,
    authorization_bytes BYTEA NOT NULL,
    authorization_digest VARCHAR(71) NOT NULL,
    receipt_bytes BYTEA NOT NULL,
    receipt_digest VARCHAR(71) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (canonical_tenant_id, publish_request_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, publish_request_id)
        REFERENCES game_design_authored_draft_publish_selection
        (canonical_tenant_id, canonical_version_id, publish_request_id) ON DELETE RESTRICT,
    CHECK (selection_digest ~ '^sha256:[0-9a-f]{64}$'
        AND authorization_digest ~ '^sha256:[0-9a-f]{64}$'
        AND receipt_digest ~ '^sha256:[0-9a-f]{64}$'),
    CHECK (workflow_identity = 'publish:' || canonical_tenant_id::text || ':publish-request:' || publish_request_id),
    CHECK (octet_length(selection_bytes) BETWEEN 1 AND 8388608),
    CHECK (octet_length(authorization_bytes) BETWEEN 1 AND 4194304),
    CHECK (octet_length(receipt_bytes) BETWEEN 1 AND 16778240)
);

-- [jooq ignore start]
CREATE FUNCTION enforce_gd_selected_game_logic_receipt() RETURNS trigger AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Selected Game Logic receipts are immutable and retained'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM game_design_authored_draft_publish_selection s
        WHERE s.canonical_tenant_id = NEW.canonical_tenant_id
          AND s.canonical_version_id = NEW.canonical_version_id
          AND s.publish_request_id = NEW.publish_request_id
          AND s.selection_digest = NEW.selection_digest
          AND convert_to(s.selection_json, 'UTF8') = NEW.selection_bytes
    ) THEN
        RAISE EXCEPTION 'Receipt requires exact existing immutable selection'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_selected_game_logic_receipt
    BEFORE INSERT OR UPDATE OR DELETE ON game_design_selected_game_logic_receipt
    FOR EACH ROW EXECUTE FUNCTION enforce_gd_selected_game_logic_receipt();
-- [jooq ignore stop]
