CREATE TABLE room_exit (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    from_room_id BIGINT NOT NULL REFERENCES room(id),
    to_room_id BIGINT NOT NULL REFERENCES room(id),
    cost INT NOT NULL DEFAULT 1
);
