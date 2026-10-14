-- World owns the assignment label; Game Session registers it and owns its operational epoch/fence.
-- The assignment is separate from the canonical topology UUID.
-- Historical rows remain unassigned until a separately authorized migration can prove identity.
ALTER TABLE region_instance
    ADD COLUMN operational_region_id UUID;

ALTER TABLE region_instance
    ADD CONSTRAINT uq_region_instance_operational_region
        UNIQUE (tenant_id, game_instance_id, operational_region_id),
    ADD CONSTRAINT ck_region_instance_operational_region_non_nil_and_distinct
        CHECK (
            operational_region_id IS NULL
            OR (operational_region_id <> '00000000-0000-0000-0000-000000000000'::UUID
                AND operational_region_id IS DISTINCT FROM canonical_region_instance_id)
        );

-- [jooq ignore start]
CREATE FUNCTION reject_region_instance_operational_assignment_tuple_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.operational_region_id IS DISTINCT FROM OLD.operational_region_id
        OR (
            OLD.operational_region_id IS NOT NULL
            AND (
                NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
                OR NEW.game_instance_id IS DISTINCT FROM OLD.game_instance_id
                OR NEW.world_instance_id IS DISTINCT FROM OLD.world_instance_id
            )
        ) THEN
        RAISE EXCEPTION 'region_instance operational assignment tuple is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_region_instance_operational_assignment_tuple_immutable
    BEFORE UPDATE ON region_instance
    FOR EACH ROW
    EXECUTE FUNCTION reject_region_instance_operational_assignment_tuple_change();

-- V35 writes region_instance directly, so preserve its manifest and row write while adding an
-- independent operational UUID to the exact composite inserted by that canonical producer.
DO $migration$
DECLARE
    original TEXT;
    original_oid OID;
    original_acl ACLITEM[];
    declaration_anchor TEXT := $anchor$    runtime_uuid UUID;$anchor$;
    declaration_replacement TEXT := $replacement$    runtime_uuid UUID;
    operational_runtime_uuid UUID;$replacement$;
    allocation_anchor TEXT := $anchor$        runtime_uuid := gen_random_uuid();
        region_runtime := jsonb_populate_record$anchor$;
    allocation_replacement TEXT := $replacement$        runtime_uuid := gen_random_uuid();
        operational_runtime_uuid := gen_random_uuid();
        WHILE operational_runtime_uuid = runtime_uuid LOOP
            operational_runtime_uuid := gen_random_uuid();
        END LOOP;
        region_runtime := jsonb_populate_record$replacement$;
    region_anchor TEXT := $anchor$                'canonical_region_instance_id', runtime_uuid));$anchor$;
    region_replacement TEXT := $replacement$                'canonical_region_instance_id', runtime_uuid,
                'operational_region_id', operational_runtime_uuid));$replacement$;
BEGIN
    SELECT p.oid, p.proacl
        INTO STRICT original_oid, original_acl
        FROM pg_proc p
        WHERE p.oid = '"${serviceSchema}".world_prepare_canonical_instance(text,text)'::REGPROCEDURE;
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_prepare_canonical_instance(text,text)'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original) - length(replace(original, declaration_anchor, '')))
            / length(declaration_anchor) <> 1
        OR (length(original) - length(replace(original, allocation_anchor, '')))
            / length(allocation_anchor) <> 1
        OR (length(original) - length(replace(original, region_anchor, '')))
            / length(region_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one canonical region materialization insertion site';
    END IF;
    original := replace(original, declaration_anchor, declaration_replacement);
    original := replace(original, allocation_anchor, allocation_replacement);
    original := replace(original, region_anchor, region_replacement);
    EXECUTE original;
    IF '"${serviceSchema}".world_prepare_canonical_instance(text,text)'::REGPROCEDURE
            IS DISTINCT FROM original_oid
        OR (SELECT p.proacl FROM pg_proc p WHERE p.oid = original_oid)
            IS DISTINCT FROM original_acl THEN
        RAISE EXCEPTION 'Canonical preparation replacement changed function identity or grants';
    END IF;
END;
$migration$;
-- [jooq ignore stop]
