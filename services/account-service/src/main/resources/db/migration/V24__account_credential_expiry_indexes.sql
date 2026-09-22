-- flyway:executeInTransaction=false
CREATE INDEX /* [jooq ignore start] */ CONCURRENTLY /* [jooq ignore stop] */ idx_password_reset_token_expires_at_id
    ON password_reset_token(expires_at, id);

CREATE INDEX /* [jooq ignore start] */ CONCURRENTLY /* [jooq ignore stop] */ idx_email_verification_token_expires_at_id
    ON email_verification_token(expires_at, id);

DROP INDEX /* [jooq ignore start] */ CONCURRENTLY /* [jooq ignore stop] */ IF EXISTS idx_account_email_login_challenge_expires_at;

CREATE INDEX /* [jooq ignore start] */ CONCURRENTLY /* [jooq ignore stop] */ idx_account_email_login_challenge_expires_at
    ON account_email_login_challenge(expires_at, id);
