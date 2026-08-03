-- [jooq ignore start]
ALTER TABLE accounts
    VALIDATE CONSTRAINT accounts_email_canonical_check;
-- [jooq ignore stop]
