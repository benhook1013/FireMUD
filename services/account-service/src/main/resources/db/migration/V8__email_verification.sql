ALTER TABLE accounts ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE email_verification_token (
    id SERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    token VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL
);
