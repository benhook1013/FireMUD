-- Preserve immutable legacy V30 evidence while aligning new source rows with Game Design V29.
-- [jooq ignore start]
ALTER TABLE game_session_authored_world_tenant_source_binding
    ADD CONSTRAINT chk_gs_authored_world_tenant_source_provenance_v29
        CHECK (provenance_kind IN ('NEW_GAME_ROW', 'RETAINED_GAME_V29')) NOT VALID;

ALTER TABLE game_session_authored_world_tenant_source_binding
    DROP CONSTRAINT chk_gs_authored_world_tenant_source_provenance;

ALTER TABLE game_session_authored_world_source_intake
    ADD CONSTRAINT chk_gs_authored_world_source_intake_provenance_v29
        CHECK (source_provenance_kind IN ('NEW_GAME_ROW', 'RETAINED_GAME_V29')) NOT VALID;

ALTER TABLE game_session_authored_world_source_intake
    DROP CONSTRAINT chk_gs_authored_world_source_intake_provenance;
-- [jooq ignore stop]
