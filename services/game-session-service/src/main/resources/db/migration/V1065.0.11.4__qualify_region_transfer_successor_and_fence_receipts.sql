-- [jooq ignore start]
CREATE OR REPLACE FUNCTION require_gs_region_transfer_successor_installation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM game_session_region_runtime_status s
    WHERE s.tenant_id=NEW.tenant_id AND s.game_instance_id=NEW.game_instance_id
      AND s.region_id=NEW.region_id AND s.region_epoch=NEW.region_epoch
      AND s.owner_service='game-session-service' AND NOT s.paused
      AND s.owner_instance_id=NEW.new_owner_id AND s.executor_fence=NEW.new_owner_fence
      AND s.lease_token_correlation=NEW.new_lease_correlation
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Region transfer completion lacks its exact installed successor' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;

ALTER TABLE game_session_region_owner_transfer_item
    DROP CONSTRAINT gs_region_fence_receipt_dispatch_incarnation,
    ADD CONSTRAINT gs_region_fence_receipt_dispatch_incarnation CHECK (
        fence_dispatch_id IS NULL OR (
            fence_dispatch_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND (fence_readback IS NULL OR (
                incarnation_digest IS NOT NULL AND incarnation_digest = fence_dispatch_incarnation
            ))
        )
    );

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1065.0.11.3'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1065.0.11.3'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.11.4__qualify_region_transfer_successor_and_fence_receipts.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.11.4'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1065.0.11.3'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.11.4''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1065.0.11.3','V1065.0.11.3__bind_region_transfer_installation_and_incarnation.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.11.4'',''V1065.0.11.4__qualify_region_transfer_successor_and_fence_receipts.sql''';
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
