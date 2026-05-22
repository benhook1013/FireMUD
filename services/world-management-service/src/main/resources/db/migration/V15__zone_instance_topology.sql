CREATE TABLE zone_instance (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    game_instance_id BIGINT NOT NULL,
    zone_instance_id BIGINT NOT NULL,
    template_zone_id BIGINT NOT NULL REFERENCES zone(id),
    region_instance_id BIGINT NOT NULL REFERENCES region_instance(id),
    name VARCHAR(100) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_zone_instance_tenant_game_zone UNIQUE (tenant_id, game_instance_id, zone_instance_id)
);

INSERT INTO zone_instance (tenant_id, game_instance_id, zone_instance_id, template_zone_id, region_instance_id, name)
SELECT DISTINCT
    ri.tenant_id,
    ri.game_instance_id,
    z.id,
    z.id,
    ri.id,
    z.name
FROM region_instance ri
JOIN room_instance rmi
  ON rmi.region_instance_id = ri.id
JOIN room r
  ON r.id = rmi.template_room_id
JOIN zone z
  ON z.id = r.zone_id
WHERE NOT EXISTS (
    SELECT 1
    FROM zone_instance zi
    WHERE zi.tenant_id = ri.tenant_id
      AND zi.game_instance_id = ri.game_instance_id
      AND zi.zone_instance_id = z.id
);

ALTER TABLE room_instance ADD COLUMN zone_instance_id BIGINT;

UPDATE room_instance rmi
SET zone_instance_id = (
    SELECT zi.id
    FROM room r
    JOIN zone_instance zi
      ON zi.template_zone_id = r.zone_id
    WHERE r.id = rmi.template_room_id
      AND zi.tenant_id = rmi.tenant_id
      AND zi.game_instance_id = rmi.game_instance_id
);

ALTER TABLE room_instance
    ADD CONSTRAINT fk_room_instance_zone_instance
    FOREIGN KEY (zone_instance_id) REFERENCES zone_instance(id);

ALTER TABLE room_instance ALTER COLUMN zone_instance_id SET NOT NULL;

CREATE INDEX idx_zone_instance_tenant_game ON zone_instance(tenant_id, game_instance_id);
CREATE INDEX idx_room_instance_zone_instance ON room_instance(zone_instance_id);
