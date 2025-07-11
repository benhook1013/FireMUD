#!/bin/bash
# Seeds minimal data for local testing of the Automation Scripting Service.
# Requires PostgreSQL to be running from docker-compose.

set -e

psql -h localhost -U postgres -d firemud <<'SQL'
INSERT INTO factions (id, tenant_id, name, description) VALUES
  (1, 1, 'Guardians', 'Default faction for testing')
  ON CONFLICT DO NOTHING;

INSERT INTO scripts (id, tenant_id, name, version, definition) VALUES
  (1, 1, 'hello_world', 'v1', '{"steps":[]}')
  ON CONFLICT DO NOTHING;
SQL

echo "Automation Scripting Service seed data inserted."
