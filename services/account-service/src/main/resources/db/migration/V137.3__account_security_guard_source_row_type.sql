-- Preserve the V137 lifecycle dispatch while giving its source-row argument the helper's exact type.
-- [jooq ignore start]
DO $migration$
DECLARE
    account_definition TEXT;
    corrected_definition TEXT;
    source_row_declaration_count INTEGER;
BEGIN
    SELECT pg_get_functiondef(
        'account_authority_account_security_update_guard()'::regprocedure)
        INTO STRICT account_definition;

    IF position('ACCOUNT_RESTRICTION_CHANGED' IN account_definition) = 0
        OR position(
            'account_platform_restriction_require_lifecycle_transition('
            IN account_definition) = 0 THEN
        RAISE EXCEPTION 'Expected V137 Account restriction lifecycle dispatch was not found';
    END IF;

    SELECT count(*) INTO source_row_declaration_count
    FROM regexp_matches(
        account_definition,
        '(^|[\r\n])[[:space:]]*source_row[[:space:]]+record[[:space:]]*;',
        'gi');
    IF source_row_declaration_count <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one Account source_row RECORD declaration';
    END IF;

    corrected_definition := regexp_replace(
        account_definition,
        '(^|[\r\n])([[:space:]]*source_row[[:space:]]+)record([[:space:]]*;)',
        '\1\2account_authority_source_records%ROWTYPE\3',
        'gi');
    IF corrected_definition = account_definition THEN
        RAISE EXCEPTION 'Account source_row RECORD declaration was not corrected';
    END IF;

    EXECUTE corrected_definition;
END;
$migration$;
-- [jooq ignore end]
