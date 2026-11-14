-- [jooq ignore start]
ALTER TABLE game_session_region_owner_transfer_item
    ADD COLUMN fence_dispatch_id UUID,
    ADD COLUMN fence_dispatch_incarnation VARCHAR(71),
    ADD CONSTRAINT gs_region_fence_dispatch_tuple CHECK (
        (fence_dispatch_id IS NULL AND fence_dispatch_incarnation IS NULL)
        OR (fence_dispatch_id IS NOT NULL AND fence_dispatch_incarnation IS NOT NULL
            AND fence_dispatch_incarnation ~ '^sha256:[0-9a-f]{64}$'));

-- One retained submission authority per execution across successive owner generations.
CREATE UNIQUE INDEX gs_region_fence_dispatch_execution
    ON game_session_region_owner_transfer_item(execution_digest)
    WHERE fence_dispatch_id IS NOT NULL;
CREATE INDEX gs_region_fence_receipt_execution
    ON game_session_region_owner_transfer_item(execution_digest)
    WHERE fence_readback IS NOT NULL;

CREATE FUNCTION preserve_gs_region_fence_dispatch() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE parent game_session_region_owner_transfer%ROWTYPE;
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.fence_dispatch_id IS NOT NULL THEN
            RAISE EXCEPTION 'Region fence dispatch requires a retained item' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.fence_dispatch_id,NEW.fence_dispatch_incarnation)
       IS NOT DISTINCT FROM ROW(OLD.fence_dispatch_id,OLD.fence_dispatch_incarnation) THEN
        RETURN NEW;
    END IF;
    IF OLD.fence_dispatch_id IS NOT NULL OR OLD.fence_readback IS NOT NULL
       OR NEW.fence_dispatch_id IS NULL OR NEW.fence_readback IS NOT NULL
       OR current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Region fence dispatch custody is immutable' USING ERRCODE='23514';
    END IF;
    SELECT * INTO STRICT parent FROM game_session_region_owner_transfer
        WHERE transfer_id=NEW.transfer_id FOR UPDATE;
    IF parent.state <> 'FROZEN'
       OR (parent.incarnation_digest IS NOT NULL
           AND parent.incarnation_digest IS DISTINCT FROM NEW.fence_dispatch_incarnation)
       OR EXISTS (SELECT 1 FROM game_session_region_owner_transfer_item i
           WHERE i.execution_digest=NEW.execution_digest AND i.fence_readback IS NOT NULL) THEN
        RAISE EXCEPTION 'Region fence dispatch requires an unfenced frozen original' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER gs_region_fence_dispatch_guard BEFORE INSERT OR UPDATE
    ON game_session_region_owner_transfer_item
    FOR EACH ROW EXECUTE FUNCTION preserve_gs_region_fence_dispatch();

CREATE FUNCTION require_gs_region_fence_dispatch_completion() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state='FROZEN' AND NEW.state='COMPLETED'
       AND (current_setting('transaction_isolation') <> 'read committed'
            OR EXISTS (SELECT 1 FROM game_session_region_owner_transfer_item i
               WHERE i.transfer_id=NEW.transfer_id AND i.fence_dispatch_id IS NOT NULL
                 AND i.fence_readback IS NULL)) THEN
        RAISE EXCEPTION 'Region transfer requires settled fence dispatch custody' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER gs_region_fence_dispatch_completion BEFORE UPDATE
    ON game_session_region_owner_transfer
    FOR EACH ROW EXECUTE FUNCTION require_gs_region_fence_dispatch_completion();

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1065.0.8'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1065.0.8'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.9__retain_region_fence_dispatch_custody.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.9'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1065.0.8'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.9''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1065.0.8','V1065.0.8__require_current_region_owner_replacement.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.9'',''V1065.0.9__retain_region_fence_dispatch_custody.sql''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog marker'; END IF;
    EXECUTE replace(definition,original,replacement);
END;
$migration$;

INSERT INTO game_session_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT epoch,bytes,'sha256:'||encode(sha256(bytes),'hex') FROM (
    SELECT epoch,gs_runtime_family_catalog_bytes(epoch) AS bytes FROM (
        SELECT coalesce(max(catalog_epoch),0)+1 AS epoch FROM game_session_runtime_family_catalog_epoch
    ) next_epoch
) seal;
-- [jooq ignore stop]
