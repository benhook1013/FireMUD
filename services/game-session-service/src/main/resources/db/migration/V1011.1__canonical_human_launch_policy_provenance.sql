-- Tighten new Human launch provenance after V1011 without changing retained owner rows.
-- V1010/V1011 remain immutable. Historical all-NULL catalogs are not adopted as Human
-- publication evidence; existing launch/catalog bytes and xmin are preserved unchanged.
-- PostgreSQL-only lock and trigger preflight are outside the jOOQ DDL simulator.
-- [jooq ignore start]
LOCK TABLE game_session_canonical_realm_catalog,
           game_session_canonical_instance_launch IN ACCESS EXCLUSIVE MODE;

DO $$
DECLARE
    launch_relation OID := 'game_session_canonical_instance_launch'::REGCLASS;
    launch_function OID := 'require_complete_canonical_launch_catalog_identity()'::REGPROCEDURE;
BEGIN
    -- Require V1011's renamed trigger and the unchanged V1010 function body.
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgrelid = launch_relation
          AND tgname = 'gs_canonical_instance_launch_complete_catalog_identity'
          AND tgfoid = launch_function
          AND tgtype = 7 -- BEFORE INSERT, FOR EACH ROW
          AND tgenabled = 'O'
          AND NOT tgisinternal
          AND tgnargs = 0
          AND tgattr = ''::INT2VECTOR
          AND tgqual IS NULL
          AND tgconstraint = 0
          AND NOT tgdeferrable
          AND NOT tginitdeferred)
       OR EXISTS (
           SELECT 1 FROM pg_trigger
           WHERE tgrelid = launch_relation
             AND tgname = 'game_session_canonical_instance_launch_complete_catalog_identit')
       OR (SELECT count(*) FROM pg_trigger
           WHERE tgrelid = launch_relation AND tgfoid = launch_function) <> 1
       OR NOT EXISTS (
           SELECT 1 FROM pg_proc function_row
           JOIN pg_class relation_row ON relation_row.oid = launch_relation
           JOIN pg_language language_row ON language_row.oid = function_row.prolang
           WHERE function_row.oid = launch_function
             AND function_row.pronamespace = relation_row.relnamespace
             AND function_row.prorettype = 'trigger'::REGTYPE
             AND function_row.prokind = 'f'
             AND function_row.pronargs = 0
             AND NOT function_row.prosecdef
             AND language_row.lanname = 'plpgsql'
             -- Exact prosrc installed by V1010__canonical_run_owned_fixture_launch_provenance.sql.
             AND md5(function_row.prosrc) = 'd6c8f6e518b45a073c00b5b045b02d5c') THEN
        RAISE EXCEPTION 'Canonical launch guard differs from the exact V1011 trigger and V1010 function'
            USING ERRCODE = '23514';
    END IF;

END;
$$;

CREATE OR REPLACE FUNCTION require_complete_canonical_launch_catalog_identity()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE catalog_row game_session_canonical_realm_catalog%ROWTYPE;
BEGIN
    IF NEW.catalog_creation_request_id IS NULL OR NEW.catalog_revision IS NULL OR NEW.catalog_revision <= 0
       OR NEW.catalog_source_intake_operation_id IS NULL OR NEW.catalog_source_intake_request_id IS NULL
       OR NEW.catalog_source_intake_operation_id = '00000000-0000-0000-0000-000000000000'::UUID
       OR NEW.catalog_source_intake_request_id = '00000000-0000-0000-0000-000000000000'::UUID
       OR NOT ((NEW.original_start_session_tuple IS NOT NULL AND NEW.fixture_policy_request_bytes IS NULL
                AND octet_length(NEW.original_start_session_tuple) BETWEEN 1 AND 262144)
               OR (NEW.original_start_session_tuple IS NULL AND NEW.fixture_policy_request_bytes IS NOT NULL
                   AND octet_length(NEW.fixture_policy_request_bytes) > 0)) THEN
        RAISE EXCEPTION 'New canonical launch requires exactly one complete Human or verified fixture provenance and exact catalog identity'
            USING ERRCODE = '23514', CONSTRAINT = 'chk_gs_canonical_instance_launch_catalog_identity';
    END IF;
    SELECT * INTO catalog_row FROM game_session_canonical_realm_catalog
      WHERE target_namespace = NEW.target_namespace AND realm_id = NEW.canonical_realm_id;
    IF NOT FOUND OR (NEW.fixture_policy_request_bytes IS NULL AND (
            catalog_row.complete_policy_set_evidence IS NULL
            OR octet_length(catalog_row.complete_policy_set_evidence) = 0
            OR catalog_row.fixture_policy_request_bytes IS NOT NULL))
       OR (NEW.fixture_policy_request_bytes IS NOT NULL AND (
            catalog_row.complete_policy_set_evidence IS NOT NULL
            OR catalog_row.fixture_policy_request_bytes IS DISTINCT FROM NEW.fixture_policy_request_bytes
            OR catalog_row.fixture_policy_seal_operation_id IS DISTINCT FROM NEW.fixture_policy_seal_operation_id
            OR catalog_row.fixture_policy_seal_request_digest IS DISTINCT FROM NEW.fixture_policy_seal_request_digest
            OR catalog_row.fixture_policy_seal_evidence_digest IS DISTINCT FROM NEW.fixture_policy_seal_evidence_digest
            OR NEW.complete_launch_binding_evidence -> 'fixturePolicySeal' ->> 'schema'
                IS DISTINCT FROM 'firemud.run-owned-fixture-policy-seal.v1'
            OR NEW.complete_launch_binding_evidence -> 'fixturePolicySeal' ->> 'ownerService'
                IS DISTINCT FROM 'game-design-service'
            OR NEW.complete_launch_binding_evidence -> 'fixturePolicySeal' ->> 'operationId'
                IS DISTINCT FROM NEW.fixture_policy_seal_operation_id::text
            OR NEW.complete_launch_binding_evidence -> 'fixturePolicySeal' ->> 'requestDigest'
                IS DISTINCT FROM NEW.fixture_policy_seal_request_digest
            OR NEW.complete_launch_binding_evidence -> 'fixturePolicySeal' ->> 'evidenceDigest'
                IS DISTINCT FROM NEW.fixture_policy_seal_evidence_digest)) THEN
        RAISE EXCEPTION 'Launch provenance differs from the exact retained canonical catalog owner'
            USING ERRCODE = '23514', CONSTRAINT = 'chk_gs_launch_fixture_provenance';
    END IF;
    RETURN NEW;
END;
$$;
-- [jooq ignore stop]
