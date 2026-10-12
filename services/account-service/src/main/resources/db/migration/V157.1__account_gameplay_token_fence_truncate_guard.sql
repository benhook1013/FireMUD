-- Retain the published V151 bytes and add the missing statement-level retention guard.
-- [jooq ignore start]
CREATE FUNCTION account_gameplay_token_identity_fence_no_truncate()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM account_gameplay_token_identity_fences) THEN
        RAISE EXCEPTION 'Account token fence retention cannot be bypassed by truncation'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_token_identity_fence_retention';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER account_gameplay_token_identity_fence_no_truncate
    BEFORE TRUNCATE ON account_gameplay_token_identity_fences
    FOR EACH STATEMENT EXECUTE FUNCTION account_gameplay_token_identity_fence_no_truncate();
-- [jooq ignore stop]
