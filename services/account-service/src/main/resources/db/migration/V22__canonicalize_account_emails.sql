UPDATE accounts
SET email = LOWER(TRIM(email))
WHERE email <> LOWER(TRIM(email));

ALTER TABLE accounts
    ADD CONSTRAINT accounts_email_canonical_check
    CHECK (email = LOWER(TRIM(email)));
