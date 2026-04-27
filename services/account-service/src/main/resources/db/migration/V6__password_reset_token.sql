CREATE TABLE password_reset_token (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    token VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
);
