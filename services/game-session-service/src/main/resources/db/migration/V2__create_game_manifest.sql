CREATE TABLE game_manifest (
    id BIGSERIAL PRIMARY KEY,
    version_id VARCHAR(100) NOT NULL,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
