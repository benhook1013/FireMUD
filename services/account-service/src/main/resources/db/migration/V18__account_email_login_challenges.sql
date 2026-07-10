CREATE TABLE account_email_login_challenge (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL UNIQUE REFERENCES accounts(id),
    code_hash VARCHAR(256) NOT NULL,
    expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    resend_available_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    invalid_attempt_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

CREATE INDEX idx_account_email_login_challenge_expires_at
    ON account_email_login_challenge(expires_at);
