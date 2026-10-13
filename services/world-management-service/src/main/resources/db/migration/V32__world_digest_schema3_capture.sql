-- Preserve exact immutable schema-2 history; only new captures use schema 3.
ALTER TABLE world_authored_graph_snapshot
    DROP CONSTRAINT ck_world_authored_graph_snapshot_scope;
ALTER TABLE world_authored_graph_snapshot
    ADD CONSTRAINT ck_world_authored_graph_snapshot_scope CHECK (
        canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND game_design_version_id > 0
        AND local_version_key > 0
        AND local_tenant_key > 0
        AND owner_binding_schema_version = 1
        AND version_state_epoch > 0
        AND digest_schema_version IN (2, 3)
    );

-- [jooq ignore start]
CREATE FUNCTION world_require_new_digest_schema3_capture() RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_TABLE_NAME = 'world_authored_graph_snapshot' THEN
        IF NEW.digest_schema_version IS DISTINCT FROM 3 THEN
            RAISE EXCEPTION 'New World graph capture requires digest schema 3' USING ERRCODE = '23514';
        END IF;
    ELSE
        IF (NEW.freeze_request_json::jsonb)->>'digestSchemaVersion' IS DISTINCT FROM '3' THEN
            RAISE EXCEPTION 'New canonical graph capture requires digest schema 3' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_world_authored_graph_snapshot_schema3 BEFORE INSERT
    ON world_authored_graph_snapshot FOR EACH ROW
    EXECUTE FUNCTION world_require_new_digest_schema3_capture();
CREATE TRIGGER trg_world_canonical_frozen_00_schema3 BEFORE INSERT
    ON world_canonical_frozen_topology FOR EACH ROW
    EXECUTE FUNCTION world_require_new_digest_schema3_capture();
-- [jooq ignore stop]
