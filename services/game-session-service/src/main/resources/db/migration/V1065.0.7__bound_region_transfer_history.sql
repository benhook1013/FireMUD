-- Bound retained transfer parents and their full worst-case execution and fence receipts.
CREATE TABLE game_session_region_transfer_history_budget (
    tenant_id UUID PRIMARY KEY REFERENCES game_session_canonical_tenant_gameplay_tag(tenant_id),
    maximum_transfers INTEGER NOT NULL CHECK (maximum_transfers > 0),
    maximum_items INTEGER NOT NULL CHECK (maximum_items > 0)
);

-- A null reservation belongs to a transfer retained before this migration. Keep its proof intact.
ALTER TABLE game_session_region_owner_transfer
    ADD COLUMN reserved_execution_items INTEGER CHECK (reserved_execution_items >= 0),
    ADD COLUMN reserved_origin_items INTEGER CHECK (reserved_origin_items >= 0),
    ADD CONSTRAINT gs_region_transfer_history_reservation_pair
        CHECK ((reserved_execution_items IS NULL) = (reserved_origin_items IS NULL));
CREATE INDEX gs_region_transfer_history_by_tenant
    ON game_session_region_owner_transfer(tenant_id, transfer_id);

-- [jooq ignore start]
CREATE TRIGGER gs_region_transfer_history_budget_retained
    BEFORE UPDATE OR DELETE ON game_session_region_transfer_history_budget
    FOR EACH ROW EXECUTE FUNCTION reject_gs_capacity_evidence_budget_mutation();
CREATE TRIGGER gs_region_transfer_history_budget_no_truncate
    BEFORE TRUNCATE ON game_session_region_transfer_history_budget
    FOR EACH STATEMENT EXECUTE FUNCTION reject_gs_capacity_evidence_budget_mutation();

-- The origin receiver installs its additional inventory census with its own schema.
CREATE FUNCTION count_gs_region_transfer_pending_origins(
    tenant UUID, game UUID, region UUID, remaining BIGINT
) RETURNS BIGINT LANGUAGE sql AS $$ SELECT 0::BIGINT $$;

CREATE FUNCTION count_gs_region_transfer_legacy_origin_items(
    tenant UUID, remaining BIGINT
) RETURNS BIGINT LANGUAGE sql AS $$ SELECT 0::BIGINT $$;

CREATE FUNCTION reserve_gs_region_transfer_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE limits game_session_region_transfer_history_budget%ROWTYPE;
        retained_transfers BIGINT; reserved_items BIGINT; legacy_items BIGINT; pending_items BIGINT;
        origin_items BIGINT; remaining BIGINT;
BEGIN
    -- The preceding freeze guard already holds the exact current region owner row. Dispatch
    -- takes that lock and rejects a frozen region, so the unresolved inventory cannot grow.
    IF NEW.reserved_execution_items IS NOT NULL OR NEW.reserved_origin_items IS NOT NULL
        OR current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Transfer history reservation must be computed at the owner boundary' USING ERRCODE='23514';
    END IF;
    SELECT * INTO limits FROM game_session_region_transfer_history_budget
        WHERE tenant_id=NEW.tenant_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Transfer history budget is unavailable' USING ERRCODE='23514';
    END IF;
    SELECT count(*), coalesce(sum(reserved_execution_items), 0)
      INTO retained_transfers, reserved_items
      FROM (
          SELECT reserved_execution_items::BIGINT + reserved_origin_items AS reserved_execution_items
          FROM game_session_region_owner_transfer
          WHERE tenant_id=NEW.tenant_id LIMIT limits.maximum_transfers
      ) retained;
    IF retained_transfers >= limits.maximum_transfers OR reserved_items > limits.maximum_items
        OR EXISTS (
            SELECT 1 FROM game_session_region_owner_transfer
            WHERE tenant_id=NEW.tenant_id AND state='FROZEN' AND reserved_execution_items IS NULL
        ) THEN
        RAISE EXCEPTION 'Transfer history is exhausted or has an unreserved predecessor' USING ERRCODE='23514';
    END IF;
    SELECT count(*) INTO legacy_items FROM (
        SELECT 1 FROM game_session_region_owner_transfer t
        JOIN game_session_region_owner_transfer_item i ON i.transfer_id=t.transfer_id
        WHERE t.tenant_id=NEW.tenant_id AND t.reserved_execution_items IS NULL
        LIMIT limits.maximum_items::BIGINT - reserved_items + 1
    ) retained;
    IF legacy_items > limits.maximum_items::BIGINT - reserved_items THEN
        RAISE EXCEPTION 'Legacy transfer items exhaust the retained history budget' USING ERRCODE='23514';
    END IF;
    remaining := limits.maximum_items::BIGINT - reserved_items - legacy_items;
    remaining := remaining - count_gs_region_transfer_legacy_origin_items(NEW.tenant_id, remaining);
    IF remaining < 0 THEN
        RAISE EXCEPTION 'Legacy origin transfer items exhaust the retained history budget' USING ERRCODE='23514';
    END IF;
    -- Region obligations are uniquely keyed by transition. The indexed region inventory is
    -- bounded before the aggregate; a successful reservation therefore covers every item.
    SELECT count(*) INTO pending_items FROM (
        SELECT 1 FROM game_session_original_new_capacity_execution e
        JOIN game_session_canonical_binding_region_bridge_obligation b ON b.transition_id=e.transition_id
        WHERE b.tenant_id=NEW.tenant_id AND b.game_instance_id=NEW.game_instance_id AND b.region_id=NEW.region_id
          AND e.status IN ('INSTALL_DISPATCHED','AUTHORIZED')
        LIMIT remaining + 1
    ) pending;
    IF pending_items > remaining THEN
        RAISE EXCEPTION 'Transfer history cannot reserve its full execution inventory' USING ERRCODE='23514';
    END IF;
    origin_items := count_gs_region_transfer_pending_origins(
        NEW.tenant_id, NEW.game_instance_id, NEW.region_id, remaining - pending_items);
    IF origin_items > remaining - pending_items THEN
        RAISE EXCEPTION 'Transfer history cannot reserve its full origin inventory' USING ERRCODE='23514';
    END IF;
    NEW.reserved_execution_items := pending_items::INTEGER;
    NEW.reserved_origin_items := origin_items::INTEGER;
    RETURN NEW;
END;
$$;
-- PostgreSQL invokes same-kind triggers in name order. Run after the existing freeze guard.
CREATE TRIGGER gs_region_owner_transfer_history_reserve
    BEFORE INSERT ON game_session_region_owner_transfer
    FOR EACH ROW EXECUTE FUNCTION reserve_gs_region_transfer_history();

CREATE FUNCTION preserve_gs_region_transfer_history_reservation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.reserved_execution_items IS DISTINCT FROM OLD.reserved_execution_items
        OR NEW.reserved_origin_items IS DISTINCT FROM OLD.reserved_origin_items THEN
        RAISE EXCEPTION 'Transfer history reservation is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_region_owner_transfer_history_retained
    BEFORE UPDATE ON game_session_region_owner_transfer
    FOR EACH ROW EXECUTE FUNCTION preserve_gs_region_transfer_history_reservation();

CREATE FUNCTION require_gs_region_transfer_item_reservation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE reserved_items INTEGER;
BEGIN
    SELECT reserved_execution_items INTO STRICT reserved_items
        FROM game_session_region_owner_transfer WHERE transfer_id=NEW.transfer_id;
    -- The existing primary key makes the bounded ordinal one retained slot. Predecessor
    -- frozen intents can complete before a tenant may freeze any new transfer.
    IF reserved_items IS NOT NULL AND NEW.target_ordinal >= reserved_items THEN
        RAISE EXCEPTION 'Transfer item is outside its reserved inventory' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_region_owner_transfer_item_reservation
    BEFORE INSERT ON game_session_region_owner_transfer_item
    FOR EACH ROW EXECUTE FUNCTION require_gs_region_transfer_item_reservation();

INSERT INTO game_session_runtime_family_classification
    (family_id,relation_name,family_role,state_class,fresh_disposition,retention_disposition,owner_consequence)
VALUES ('game_session_region_transfer_history_budget','game_session_region_transfer_history_budget',
    'CONTROL','NOT_PAYLOAD','CONTROL_ONLY','OWNER_POLICY_RETAINED',
    'Retain the finite tenant transfer-parent and full execution-item history bounds across recovery.');

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1065.0.6'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1065.0.6'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.7__bound_region_transfer_history.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.7'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1065.0.6'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.7''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1065.0.6','V1065.0.6__settle_denied_capacity_evidence_charges.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.7'',''V1065.0.7__bound_region_transfer_history.sql''';
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
