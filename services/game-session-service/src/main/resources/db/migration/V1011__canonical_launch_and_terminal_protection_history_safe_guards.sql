-- Preserve the published V1005/V1006 bytes and tighten their guards in a forward migration.
-- Flyway executes this migration in one PostgreSQL transaction. No retained row is rewritten,
-- backfilled, or adopted; legitimate historical all-NULL terminal protection stays readable.
-- Locks and catalog/data preflight require PostgreSQL and are outside the jOOQ DDL simulator.
-- [jooq ignore start]
LOCK TABLE game_session_canonical_instance_launch,
           game_session_canonical_initial_admission_attempt IN ACCESS EXCLUSIVE MODE;

DO $$
DECLARE
    launch_relation OID := 'game_session_canonical_instance_launch'::REGCLASS;
    attempt_relation OID := 'game_session_canonical_initial_admission_attempt'::REGCLASS;
    launch_function OID := 'require_complete_canonical_launch_catalog_identity()'::REGPROCEDURE;
BEGIN
    -- V1005's 64-byte identifier was truncated by PostgreSQL to this exact 63-byte name.
    -- V1010 replaced the function in place. Require that exact current binding and body,
    -- not the obsolete Human-only V1005 body, before changing any guard object.
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgrelid = launch_relation
          AND tgname = 'game_session_canonical_instance_launch_complete_catalog_identit'
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
             AND tgname = 'gs_canonical_instance_launch_complete_catalog_identity')
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
        RAISE EXCEPTION 'Canonical launch guard differs from the original V1005 trigger and current V1010 function'
            USING ERRCODE = '23514';
    END IF;

    IF (SELECT count(*) FROM pg_constraint
        WHERE conrelid = attempt_relation
          AND conname IN ('chk_gs_initial_admission_account_protection_evidence_complete',
                          'chk_gs_initial_admission_terminal_requires_protection')
          AND contype = 'c' AND convalidated) <> 2 THEN
        RAISE EXCEPTION 'Canonical initial-admission original protection constraints are missing or unexpected'
            USING ERRCODE = '23514';
    END IF;

    -- IS NOT TRUE rejects SQL UNKNOWN as well as FALSE: original V1006 could admit a
    -- partial tuple with a NULL version or fence if its procedural guard was bypassed.
    -- Such retained conflicts require owner adjudication, never implicit repair here.
    IF EXISTS (
        SELECT 1 FROM game_session_canonical_initial_admission_attempt
        WHERE ((account_protection_evidence_version IS NULL
                    AND account_protection_id IS NULL
                    AND account_protection_fence IS NULL
                    AND original_owner_attempt_id IS NULL
                    AND original_owner_mutation_id IS NULL
                    AND original_owner_fence IS NULL
                    AND account_protection_evidence_bytes IS NULL)
                OR (account_protection_evidence_version IS NOT NULL
                    AND account_protection_evidence_version = 1
                    AND account_protection_id IS NOT NULL
                    AND account_protection_id <> '00000000-0000-0000-0000-000000000000'::UUID
                    AND account_protection_fence IS NOT NULL
                    AND account_protection_fence > 0
                    AND original_owner_attempt_id IS NOT NULL
                    AND original_owner_mutation_id IS NOT NULL
                    AND original_owner_mutation_id <> '00000000-0000-0000-0000-000000000000'::UUID
                    AND original_owner_fence IS NOT NULL
                    AND original_owner_fence > 0
                    AND account_protection_evidence_bytes IS NOT NULL
                    AND octet_length(account_protection_evidence_bytes) BETWEEN 1 AND 8388608))
              IS NOT TRUE
           OR (status = 'PENDING' AND account_protection_evidence_version IS NOT NULL)) THEN
        RAISE EXCEPTION 'Retained canonical initial-admission protection evidence is malformed or partial'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chk_gs_initial_admission_account_protection_evidence_complete';
    END IF;
END;
$$;
-- [jooq ignore stop]

-- V28's procedural attempt-table creation is absent from simulated metadata, as in V1006.
-- These declarative guards are executed and proved against PostgreSQL.
-- [jooq ignore start]
ALTER TABLE game_session_canonical_initial_admission_attempt
    DROP CONSTRAINT chk_gs_initial_admission_account_protection_evidence_complete,
    DROP CONSTRAINT chk_gs_initial_admission_terminal_requires_protection,
    ADD CONSTRAINT chk_gs_initial_admission_account_protection_evidence_complete
        CHECK (
            (account_protection_evidence_version IS NULL
                AND account_protection_id IS NULL
                AND account_protection_fence IS NULL
                AND original_owner_attempt_id IS NULL
                AND original_owner_mutation_id IS NULL
                AND original_owner_fence IS NULL
                AND account_protection_evidence_bytes IS NULL)
            OR (account_protection_evidence_version IS NOT NULL
                AND account_protection_evidence_version = 1
                AND account_protection_id IS NOT NULL
                AND account_protection_id <> '00000000-0000-0000-0000-000000000000'::UUID
                AND account_protection_fence IS NOT NULL
                AND account_protection_fence > 0
                AND original_owner_attempt_id IS NOT NULL
                AND original_owner_mutation_id IS NOT NULL
                AND original_owner_mutation_id <> '00000000-0000-0000-0000-000000000000'::UUID
                AND original_owner_fence IS NOT NULL
                AND original_owner_fence > 0
                AND account_protection_evidence_bytes IS NOT NULL
                AND octet_length(account_protection_evidence_bytes) BETWEEN 1 AND 8388608)),
    -- Enforce fresh terminal writes without validating/reclassifying immutable history.
    ADD CONSTRAINT chk_gs_initial_admission_terminal_requires_protection
        CHECK (status = 'PENDING'
            OR (account_protection_evidence_version IS NOT NULL
                AND account_protection_evidence_version = 1)) NOT VALID;
-- [jooq ignore stop]

-- The simulator omits trigger objects. Rename in place to preserve its OID, binding,
-- enabled/event identity and the Human-or-fixture-aware V1010 function unchanged.
-- [jooq ignore start]
ALTER TRIGGER game_session_canonical_instance_launch_complete_catalog_identit
    ON game_session_canonical_instance_launch
    RENAME TO gs_canonical_instance_launch_complete_catalog_identity;
-- [jooq ignore stop]
