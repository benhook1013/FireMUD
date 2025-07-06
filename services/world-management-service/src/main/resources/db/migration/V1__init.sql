CREATE TABLE region (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    tenant_id BIGINT NOT NULL
);

CREATE TABLE zone (
    id BIGSERIAL PRIMARY KEY,
    region_id BIGINT NOT NULL REFERENCES region(id),
    name VARCHAR(100) NOT NULL
);

CREATE TABLE room (
    id BIGSERIAL PRIMARY KEY,
    zone_id BIGINT NOT NULL REFERENCES zone(id),
    name VARCHAR(100) NOT NULL,
    description TEXT
);

CREATE TABLE instance (
    id BIGSERIAL PRIMARY KEY,
    zone_id BIGINT NOT NULL REFERENCES zone(id),
    owner_account_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
