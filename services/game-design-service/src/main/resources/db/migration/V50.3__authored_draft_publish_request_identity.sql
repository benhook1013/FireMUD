-- A publication operation selects one exact Version in its canonical tenant.
-- Conflicting retained reservations deliberately fail migration; no history is discarded.
ALTER TABLE game_design_authored_draft_publish_selection
    ADD CONSTRAINT uq_gd_authored_draft_publish_operation
    UNIQUE (canonical_tenant_id, publish_request_id);
