#!/usr/bin/env bash
# Seeds minimal data for local testing of the Automation Scripting Service.
# Requires PostgreSQL to be running via the docker/docker-compose.yml stack.

set -e

psql -v ON_ERROR_STOP=1 -h localhost -U postgres -d firemud <<'SQL'
INSERT INTO factions (id, tenant_id, name, description) VALUES
  (1, '11111111-1111-1111-1111-111111111111', 'Guardians', 'Default faction for testing')
  ON CONFLICT DO NOTHING;

INSERT INTO scripts (id, tenant_id, name, version, definition) VALUES
  (1, '11111111-1111-1111-1111-111111111111', 'hello_world', 'v1', '{"steps":[]}')
  ON CONFLICT DO NOTHING;
SQL

echo "Automation Scripting Service seed data inserted."
