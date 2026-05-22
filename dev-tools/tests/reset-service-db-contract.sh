#!/usr/bin/env bash
# Lightweight contract check for dev-tools/restores/reset-service-db.sh.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/dev-tools/restores/reset-service-db.sh"

account_output="$("$SCRIPT" account-service --dry-run)"
gateway_output="$("$SCRIPT" gateway --dry-run)"

grep -q "Flyway history table: account_service.flyway_schema_history_account_service" <<<"$account_output"
grep -q "  account_service.accounts" <<<"$account_output"
grep -q "  account_service.profiles" <<<"$account_output"

grep -q "Flyway history table: gateway.flyway_schema_history_gateway" <<<"$gateway_output"
grep -q "  gateway.route_config" <<<"$gateway_output"

echo "reset-service-db contract checks passed"
