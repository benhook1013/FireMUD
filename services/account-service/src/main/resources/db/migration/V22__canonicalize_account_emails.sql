-- Preflight the retained account data before rewriting emails. The primary key
-- deliberately rejects multiple existing rows with the same LOWER(TRIM(email)).
-- Resolve which account retains a colliding address, then change or merge the
-- other retained rows before rerunning this migration.
CREATE TABLE account_email_canonicalization_preflight (
    canonical_email VARCHAR(100)
        CONSTRAINT accounts_email_canonicalization_collision PRIMARY KEY
);

INSERT INTO account_email_canonicalization_preflight (canonical_email)
SELECT LOWER(TRIM(email))
FROM accounts;

DROP TABLE account_email_canonicalization_preflight;

UPDATE accounts
SET email = LOWER(TRIM(email))
WHERE email <> LOWER(TRIM(email));

ALTER TABLE accounts
    ADD CONSTRAINT accounts_email_canonical_check
    CHECK (email = LOWER(TRIM(email)));
