-- Zero ordinals preserve existing preparations without crediting historical claims.
ALTER TABLE game_session_region_timeline_preparation
    ADD COLUMN initialization_claim_ordinal BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN no_write_released_through BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT gs_timeline_no_write_contiguous CHECK (
        no_write_released_through >= 0
        AND initialization_claim_ordinal >= no_write_released_through
        AND initialization_claim_ordinal - no_write_released_through <= 1);

-- [jooq ignore start]
CREATE FUNCTION preserve_gs_timeline_no_write_release() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='TRUNCATE' THEN
        RAISE EXCEPTION 'Initial timeline claim history is retained' USING ERRCODE='23514';
    ELSIF TG_OP='DELETE' THEN
        IF OLD.initialization_claim_ordinal > 0 THEN
            RAISE EXCEPTION 'Initial timeline claim history is retained' USING ERRCODE='23514';
        END IF;
        RETURN OLD;
    ELSIF TG_OP='INSERT' THEN
        IF NEW.initialization_claim_ordinal <> 0 OR NEW.no_write_released_through <> 0
           OR NEW.phase='INITIALIZING' THEN
            RAISE EXCEPTION 'Initial timeline claim requires an existing preparation' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;

    IF (OLD.initialization_claim_ordinal > 0 OR NEW.initialization_claim_ordinal > 0)
       AND ROW(NEW.initialization_operation_id,NEW.preparation_digest)
           IS DISTINCT FROM ROW(OLD.initialization_operation_id,OLD.preparation_digest) THEN
        RAISE EXCEPTION 'Initial timeline claim source is immutable' USING ERRCODE='23514';
    END IF;
    IF OLD.phase='PREPARED' AND NEW.phase='INITIALIZING' THEN
        IF OLD.initialization_claim_ordinal <> OLD.no_write_released_through
           OR NEW.initialization_claim_ordinal <= 0
           OR NEW.initialization_claim_ordinal - 1 <> OLD.initialization_claim_ordinal
           OR NEW.no_write_released_through <> OLD.no_write_released_through THEN
            RAISE EXCEPTION 'Initial timeline claim must follow the positively released prefix' USING ERRCODE='23514';
        END IF;
    ELSIF OLD.phase='INITIALIZING' AND NEW.phase='PREPARED' THEN
        IF OLD.initialization_claim_ordinal <= OLD.no_write_released_through
           OR NEW.initialization_claim_ordinal <> OLD.initialization_claim_ordinal
           OR NEW.no_write_released_through <> OLD.initialization_claim_ordinal THEN
            RAISE EXCEPTION 'Initial timeline release must close its exact active claim' USING ERRCODE='23514';
        END IF;
    ELSIF ROW(NEW.initialization_claim_ordinal,NEW.no_write_released_through)
       IS DISTINCT FROM ROW(OLD.initialization_claim_ordinal,OLD.no_write_released_through) THEN
        RAISE EXCEPTION 'Initial timeline claim history requires a guarded phase transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER gs_timeline_no_write_release_guard BEFORE INSERT OR UPDATE OR DELETE
    ON game_session_region_timeline_preparation
    FOR EACH ROW EXECUTE FUNCTION preserve_gs_timeline_no_write_release();
CREATE TRIGGER gs_timeline_no_write_release_no_truncate BEFORE TRUNCATE
    ON game_session_region_timeline_preparation
    FOR EACH STATEMENT EXECUTE FUNCTION preserve_gs_timeline_no_write_release();
-- [jooq ignore stop]

-- Runtime-family catalog registration
-- [jooq ignore start]
DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1065.0.15'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1065.0.15'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.16__retain_exact_initial_timeline_no_write_release.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.16'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1065.0.15'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.16''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1065.0.15','V1065.0.15__require_exact_region_transfer_successor.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.16'',''V1065.0.16__retain_exact_initial_timeline_no_write_release.sql''';
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
