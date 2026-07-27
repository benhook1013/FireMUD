-- [jooq ignore start]
ALTER TABLE accounts
    VALIDATE CONSTRAINT accounts_lifecycle_state_check;
-- [jooq ignore stop]
