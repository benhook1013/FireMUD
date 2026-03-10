ALTER TABLE region
    ADD COLUMN generation_seed BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN generator_type VARCHAR(50),
    ADD COLUMN generator_params TEXT;
