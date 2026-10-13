-- [jooq ignore start]
CREATE OR REPLACE FUNCTION world_claim_legacy_numeric_tenant_key()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    claimed_kind VARCHAR(32);
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF EXISTS (
            SELECT 1
            FROM world_authored_source_tenant_key_reservation
            WHERE tenant_key = OLD.tenant_id
                AND claim_kind = 'CANONICAL_AUTHORED_SOURCE'
        ) THEN
            RAISE EXCEPTION
                'World tenant key % is reserved for a canonical authored source', OLD.tenant_id
                USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE' AND EXISTS (
        SELECT 1
        FROM world_authored_source_tenant_key_reservation
        WHERE tenant_key = OLD.tenant_id
            AND claim_kind = 'CANONICAL_AUTHORED_SOURCE'
    ) THEN
        RAISE EXCEPTION
            'World tenant key % is reserved for a canonical authored source', OLD.tenant_id
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM world_authored_source_tenant_key_reservation
        WHERE tenant_key = NEW.tenant_id
            AND claim_kind = 'CANONICAL_AUTHORED_SOURCE'
    ) THEN
        RAISE EXCEPTION
            'World tenant key % is reserved for a canonical authored source', NEW.tenant_id
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'UPDATE' AND OLD.tenant_id = NEW.tenant_id THEN
        RETURN NEW;
    END IF;

    INSERT INTO world_authored_source_tenant_key_reservation (tenant_key, claim_kind)
    VALUES (NEW.tenant_id, 'LEGACY_NUMERIC')
    ON CONFLICT (tenant_key) DO NOTHING;

    SELECT claim_kind
    INTO claimed_kind
    FROM world_authored_source_tenant_key_reservation
    WHERE tenant_key = NEW.tenant_id;

    IF claimed_kind IS DISTINCT FROM 'LEGACY_NUMERIC' THEN
        RAISE EXCEPTION 'World tenant key % is reserved for a canonical authored source', NEW.tenant_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
-- [jooq ignore stop]
