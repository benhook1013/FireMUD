-- Historical UUID-only dispatches remain unknown-effect read-only recovery.
-- Each issued nonce permits one PREPARE submission; only its observed winning receipt may be sealed.
-- [jooq ignore start]
ALTER TABLE game_session_region_owner_transfer_item
    ADD COLUMN fence_prepare_ids UUID[],
    ADD COLUMN fence_prepare_seal UUID,
    ADD COLUMN fence_apply_issued BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT gs_region_dispatch_prepare_tuple CHECK (
        (fence_prepare_ids IS NULL AND fence_prepare_seal IS NULL AND NOT fence_apply_issued)
        OR (fence_prepare_ids IS NOT NULL AND fence_dispatch_id IS NOT NULL AND cardinality(fence_prepare_ids) BETWEEN 0 AND 16
            AND (fence_prepare_seal IS NULL OR fence_prepare_seal=ANY(fence_prepare_ids))
            AND (NOT fence_apply_issued OR fence_prepare_seal IS NOT NULL)));

CREATE FUNCTION preserve_gs_region_dispatch_preparation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE parent game_session_region_owner_transfer%ROWTYPE; nonce UUID;
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.fence_prepare_ids IS NOT NULL OR NEW.fence_prepare_seal IS NOT NULL THEN
            RAISE EXCEPTION 'Preparation requires retained dispatch custody' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.fence_prepare_ids,NEW.fence_prepare_seal)
       IS NOT DISTINCT FROM ROW(OLD.fence_prepare_ids,OLD.fence_prepare_seal) THEN
        IF NEW.fence_readback IS NOT NULL AND NEW.fence_prepare_ids IS NOT NULL
           AND NEW.fence_prepare_seal IS NULL THEN
            RAISE EXCEPTION 'Preparation must be sealed before retained effect' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF current_setting('transaction_isolation') <> 'read committed'
       OR OLD.fence_readback IS NOT NULL OR OLD.fence_prepare_seal IS NOT NULL
       OR (OLD.fence_dispatch_id IS NOT NULL AND OLD.fence_prepare_ids IS NULL)
       OR NEW.fence_prepare_ids IS NULL OR NEW.fence_dispatch_id IS NULL
       OR array_position(NEW.fence_prepare_ids,NULL) IS NOT NULL
       OR cardinality(NEW.fence_prepare_ids)>16
       OR (SELECT count(DISTINCT x) FROM unnest(NEW.fence_prepare_ids) x)<>cardinality(NEW.fence_prepare_ids) THEN
        RAISE EXCEPTION 'Historical or sealed dispatch cannot be rearmed' USING ERRCODE='23514';
    END IF;
    FOREACH nonce IN ARRAY NEW.fence_prepare_ids LOOP
        IF nonce='00000000-0000-0000-0000-000000000000'::uuid THEN
            RAISE EXCEPTION 'Preparation nonce must be nonzero' USING ERRCODE='23514';
        END IF;
    END LOOP;
    IF OLD.fence_prepare_ids IS NULL THEN
        IF cardinality(NEW.fence_prepare_ids)<>0 OR NEW.fence_prepare_seal IS NOT NULL THEN
            RAISE EXCEPTION 'Dispatch starts with no preparation submission' USING ERRCODE='23514';
        END IF;
    ELSIF NEW.fence_prepare_seal IS NOT NULL THEN
        IF NEW.fence_prepare_ids IS DISTINCT FROM OLD.fence_prepare_ids
           OR NOT (NEW.fence_prepare_seal=ANY(OLD.fence_prepare_ids)) THEN
            RAISE EXCEPTION 'Only an issued observed nonce can be sealed' USING ERRCODE='23514';
        END IF;
    ELSIF cardinality(NEW.fence_prepare_ids)<>cardinality(OLD.fence_prepare_ids)+1
       OR (cardinality(OLD.fence_prepare_ids)>0 AND
           NEW.fence_prepare_ids[1:cardinality(OLD.fence_prepare_ids)] IS DISTINCT FROM OLD.fence_prepare_ids) THEN
        RAISE EXCEPTION 'Preparation history is append-only and bounded' USING ERRCODE='23514';
    END IF;
    SELECT * INTO STRICT parent FROM game_session_region_owner_transfer
        WHERE transfer_id=NEW.transfer_id FOR UPDATE;
    IF parent.state <> 'FROZEN' OR NEW.fence_readback IS NOT NULL THEN
        RAISE EXCEPTION 'Dispatch preparation requires frozen unsettled transfer' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_region_dispatch_prepare_guard BEFORE INSERT OR UPDATE
    ON game_session_region_owner_transfer_item
    FOR EACH ROW EXECUTE FUNCTION preserve_gs_region_dispatch_preparation();
CREATE FUNCTION preserve_gs_region_dispatch_apply() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE parent game_session_region_owner_transfer%ROWTYPE;
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.fence_apply_issued THEN
            RAISE EXCEPTION 'Apply requires acknowledged issuance' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.fence_apply_issued AND NOT NEW.fence_apply_issued THEN
        RAISE EXCEPTION 'Apply issuance cannot be reset' USING ERRCODE='23514';
    END IF;
    IF NOT OLD.fence_apply_issued AND NEW.fence_apply_issued THEN
        IF current_setting('transaction_isolation') <> 'read committed'
           OR OLD.fence_prepare_seal IS NULL OR OLD.fence_readback IS NOT NULL
           OR ROW(NEW.fence_prepare_ids,NEW.fence_prepare_seal,NEW.fence_dispatch_id)
              IS DISTINCT FROM ROW(OLD.fence_prepare_ids,OLD.fence_prepare_seal,OLD.fence_dispatch_id) THEN
            RAISE EXCEPTION 'Apply requires exact sealed preparation' USING ERRCODE='23514';
        END IF;
        SELECT * INTO STRICT parent FROM game_session_region_owner_transfer
            WHERE transfer_id=NEW.transfer_id FOR UPDATE;
        IF parent.state <> 'FROZEN' THEN
            RAISE EXCEPTION 'Apply requires frozen transfer' USING ERRCODE='23514';
        END IF;
    END IF;
    IF NEW.fence_readback IS NOT NULL AND NEW.fence_prepare_ids IS NOT NULL AND NOT NEW.fence_apply_issued THEN
        RAISE EXCEPTION 'Effect retention requires issued apply' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_region_dispatch_apply_guard BEFORE INSERT OR UPDATE
    ON game_session_region_owner_transfer_item
    FOR EACH ROW EXECUTE FUNCTION preserve_gs_region_dispatch_apply();
-- [jooq ignore stop]

-- Runtime-family catalog registration
-- [jooq ignore start]
DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1065.0.13'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1065.0.13'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.14__seal_transfer_dispatch_preparation.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.14'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1065.0.13'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.14''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1065.0.13','V1065.0.13__repair_frozen_required_member_projection.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.14'',''V1065.0.14__seal_transfer_dispatch_preparation.sql''';
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
