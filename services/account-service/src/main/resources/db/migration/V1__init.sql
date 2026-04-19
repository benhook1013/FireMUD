CREATE TABLE accounts (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL
);

CREATE TABLE profiles (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    display_name VARCHAR(100),
    bio VARCHAR(255),
    presence_visibility_policy VARCHAR(32) NOT NULL DEFAULT 'FRIENDS_ONLY'
);
