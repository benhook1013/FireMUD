-- Keep canonical activation's preparation schema correlated with the retained association schema.
-- V43 required schema-1 association/schema-2 preparation. V65 admits the schema-2 association and
-- its captured realm/catalog identity, whose selector-bearing preparation input is schema four.
-- [jooq ignore start]
DO $migration$
DECLARE
    definition TEXT;
    original_oid OID;
    original_acl ACLITEM[];
    old_fragment TEXT := $$AND p.input_json::JSONB->>'schemaVersion'='2'$$;
    new_fragment TEXT := $replacement$AND (
                (a.schema_version=1 AND p.input_json::JSONB->>'schemaVersion'='2')
                OR (a.schema_version=2 AND p.input_json::JSONB->>'schemaVersion'='4')
            )$replacement$;
BEGIN
    SELECT p.oid, p.proacl
        INTO STRICT original_oid, original_acl
        FROM pg_proc p
        WHERE p.oid = '"${serviceSchema}".world_validate_canonical_activation_operation()'::REGPROCEDURE;
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_validate_canonical_activation_operation()'::REGPROCEDURE)
        INTO STRICT definition;

    IF (length(definition)-length(replace(definition, old_fragment, '')))/length(old_fragment) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V65 canonical activation preparation schema discriminator';
    END IF;
    definition := replace(definition, old_fragment, new_fragment);

    EXECUTE definition;
    IF '"${serviceSchema}".world_validate_canonical_activation_operation()'::REGPROCEDURE
            IS DISTINCT FROM original_oid
        OR (SELECT p.proacl FROM pg_proc p WHERE p.oid = original_oid)
            IS DISTINCT FROM original_acl THEN
        RAISE EXCEPTION 'Canonical activation guard replacement changed function identity or grants';
    END IF;
END;
$migration$;
-- [jooq ignore stop]
