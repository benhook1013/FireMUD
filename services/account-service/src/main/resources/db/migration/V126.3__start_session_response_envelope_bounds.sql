-- [jooq ignore start]
DO $$
DECLARE
    envelope_table REGCLASS := to_regclass('account_start_session_operator_authorizations');
    envelope_column SMALLINT;
    matching_constraints INTEGER;
    size_constraint_name TEXT;
BEGIN
    IF envelope_table IS NULL THEN
        RAISE EXCEPTION 'StartSession authorization table is missing'
            USING ERRCODE = '55000';
    END IF;

    SELECT attnum
    INTO envelope_column
    FROM pg_attribute
    WHERE attrelid = envelope_table
        AND attname = 'encrypted_response_envelope'
        AND NOT attisdropped;

    IF envelope_column IS NULL THEN
        RAISE EXCEPTION 'StartSession response-envelope column is missing'
            USING ERRCODE = '55000';
    END IF;

    SELECT COUNT(*), MIN(conname::TEXT)
    INTO matching_constraints, size_constraint_name
    FROM pg_constraint
    WHERE conrelid = envelope_table
        AND contype = 'c'
        AND convalidated
        AND conkey = ARRAY[envelope_column]::SMALLINT[]
        AND lower(
            replace(
                regexp_replace(
                    pg_get_expr(conbin, conrelid), '[[:space:]()]', '', 'g'),
                '"',
                '')) IN (
                    'octet_lengthencrypted_response_envelopebetween1and65536',
                    'octet_lengthencrypted_response_envelope>=1andoctet_lengthencrypted_response_envelope<=65536');

    IF matching_constraints <> 1 THEN
        RAISE EXCEPTION
            'Expected exactly one StartSession encrypted-response size guard, found %',
            matching_constraints
            USING ERRCODE = '55000';
    END IF;

    EXECUTE format(
        'ALTER TABLE %s DROP CONSTRAINT %I',
        envelope_table,
        size_constraint_name);
END;
$$;
-- [jooq ignore stop]

-- Only the old size guard changes; retained ciphertext and immutable/erasure guards stay intact.
ALTER TABLE account_start_session_operator_authorizations
    ADD CONSTRAINT account_start_session_operator_authz_envelope_size_bound
    CHECK (octet_length(encrypted_response_envelope) BETWEEN 1 AND 163840);
