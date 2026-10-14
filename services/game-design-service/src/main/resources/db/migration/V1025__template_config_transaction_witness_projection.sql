-- PostgreSQL executes this guard; the native upgrade proof verifies it.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION guard_template_config_insert_witness() RETURNS TRIGGER AS $$
BEGIN
    IF pg_trigger_depth() <> 2 OR NEW.creation_transaction_id IS DISTINCT FROM pg_current_xact_id()::TEXT
        OR NOT EXISTS (SELECT 1 FROM game_templates t WHERE t.id = NEW.template_id
            AND t.xmin = pg_current_xact_id()::xid) THEN
        RAISE EXCEPTION 'template insert witness requires actual row insert trigger' USING ERRCODE = '23514'; END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
-- [jooq ignore stop]
