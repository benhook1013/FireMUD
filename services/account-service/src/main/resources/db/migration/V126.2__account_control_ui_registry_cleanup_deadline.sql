ALTER TABLE account_control_ui_issuance_operations
    ADD COLUMN registry_cleanup_deadline_epoch_millis BIGINT;

ALTER TABLE account_control_ui_issuance_operations
    ADD CONSTRAINT account_control_ui_registry_cleanup_deadline_bound CHECK (
        registry_cleanup_deadline_epoch_millis IS NULL
        OR (registry_cleanup_deadline_epoch_millis >= expires_at_epoch_second::NUMERIC * 1000
            AND registry_cleanup_deadline_epoch_millis <= 9007199254740991)
    );

-- Retained pre-migration rows stay NULL and byte-for-byte unchanged. New operations carry one
-- immutable physical cleanup deadline; old rows cannot be advanced or reinterpreted.
-- [jooq ignore start]
CREATE FUNCTION account_control_ui_cleanup_deadline_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.registry_cleanup_deadline_epoch_millis IS NULL THEN
            RAISE EXCEPTION 'New control-ui issuance requires cleanup deadline evidence'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.registry_cleanup_deadline_epoch_millis IS NULL
        OR NEW.registry_cleanup_deadline_epoch_millis
            IS DISTINCT FROM OLD.registry_cleanup_deadline_epoch_millis THEN
        RAISE EXCEPTION 'Control-ui cleanup deadline is immutable and legacy rows cannot advance'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_control_ui_cleanup_deadline_immutable
    BEFORE INSERT OR UPDATE ON account_control_ui_issuance_operations
    FOR EACH ROW EXECUTE FUNCTION account_control_ui_cleanup_deadline_guard();
-- [jooq ignore stop]
