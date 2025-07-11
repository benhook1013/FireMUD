#!/bin/bash
# Seeds minimal data for local testing of the Game Logic Service.
# Requires PostgreSQL to be running from docker-compose.

set -e

psql -h localhost -U postgres -d firemud <<'SQL'
-- Sample region and room for quick manual testing
INSERT INTO region (id, name, tenant_id) VALUES (1, 'Demo Region', 1) ON CONFLICT DO NOTHING;
INSERT INTO zone (id, region_id, name) VALUES (1, 1, 'Demo Zone') ON CONFLICT DO NOTHING;
INSERT INTO room (id, zone_id, name, description) VALUES (1, 1, 'Start Room', 'A place to begin.') ON CONFLICT DO NOTHING;
SQL

echo "Seed data inserted."
