#!/usr/bin/env bash
# Seeds minimal data for local testing of the Game Logic Service.
# Requires PostgreSQL to be running via the docker/docker-compose.yml stack.

set -e

psql -v ON_ERROR_STOP=1 -h localhost -U postgres -d firemud <<'SQL'
-- Sample region, rooms, and exits
INSERT INTO region (id, name, tenant_id) VALUES (1, 'Demo Region', '11111111-1111-1111-1111-111111111111') ON CONFLICT DO NOTHING;
INSERT INTO zone (id, region_id, name) VALUES (1, 1, 'Demo Zone') ON CONFLICT DO NOTHING;
INSERT INTO room (id, zone_id, name, description) VALUES (1, 1, 'Start Room', 'A place to begin.') ON CONFLICT DO NOTHING;
INSERT INTO room (id, zone_id, name, description) VALUES (2, 1, 'North Room', 'Just north of start.') ON CONFLICT DO NOTHING;
INSERT INTO room_exit (id, tenant_id, from_room_id, to_room_id, cost) VALUES
  (1, '11111111-1111-1111-1111-111111111111', 1, 2, 1) ON CONFLICT DO NOTHING;
INSERT INTO room_exit (id, tenant_id, from_room_id, to_room_id, cost) VALUES
  (2, '11111111-1111-1111-1111-111111111111', 2, 1, 1) ON CONFLICT DO NOTHING;

-- Sample item and character
INSERT INTO item (id, name, tenant_id) VALUES (1, 'Sword', '11111111-1111-1111-1111-111111111111') ON CONFLICT DO NOTHING;
INSERT INTO character (id, account_id, name, tenant_id) VALUES (1, 1, 'Hero', '11111111-1111-1111-1111-111111111111') ON CONFLICT DO NOTHING;
INSERT INTO inventory (character_id, item_id, quantity) VALUES (1, 1, 1) ON CONFLICT DO NOTHING;
SQL

echo "Seed data inserted."
