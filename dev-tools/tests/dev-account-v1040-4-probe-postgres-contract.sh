#!/usr/bin/env bash
set -euo pipefail
# Focused physical companion to the mock-only probe contract. Never targets a deployed database.
root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
probe="$root_dir/dev-tools/deploy/probe-dev-account-v1040-4.sh"
if [[ $# -ne 2 ]]; then
  echo "usage: $0 <PostgreSQL-18-bin-dir> <PostgreSQL-18-share-dir>" >&2
  exit 2
fi
bin="$(cd "$1" && pwd)"
share="$(cd "$2" && pwd)"
if [[ "$("$bin/postgres" --version)" != 'postgres (PostgreSQL) 18.'* ]]; then
  echo 'This fixture requires PostgreSQL 18.' >&2
  exit 2
fi
for var in ${!PG@}; do
  unset "$var"
done
umask 077
rig=$(mktemp -d)
started=false
cleanup() {
  if [[ "$started" == true ]]; then
    "$bin/pg_ctl" -D "$rig/data" -m fast -w stop >/dev/null || return
  fi
  rm -rf -- "$rig"
}
trap cleanup EXIT
password="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
printf '%s\n' "$password" >"$rig/password"
"$bin/initdb" -L "$share" -D "$rig/data" --no-locale --encoding=UTF8 \
  -A scram-sha-256 --pwfile="$rig/password" -U local_fixture_user >"$rig/init.log"
port=$((30000 + RANDOM % 25000))
if ! "$bin/pg_ctl" -D "$rig/data" -l "$rig/postgres.log" \
  -o "-c listen_addresses=127.0.0.1 -c unix_socket_directories='' -c port=$port" \
  -w start >"$rig/start.log"; then
  cat "$rig/postgres.log" >&2
  exit 1
fi
started=true
export PGHOST=127.0.0.1 PGPORT="$port" PGUSER=local_fixture_user PGDATABASE=postgres PGPASSWORD="$password"
"$bin/createdb" -- firemud
export PGDATABASE=firemud
"$bin/psql" -XqAtw -v ON_ERROR_STOP=1 <<'SQL'
CREATE SCHEMA account_service;
CREATE TABLE account_service.flyway_schema_history_account_service(version text,script text,checksum integer,success boolean);
CREATE TABLE account_service.account_tenant_capacity_reservations(id bigint);
CREATE TABLE account_service.account_capacity_origin_decisions(admission_attempt_id uuid not null);
INSERT INTO account_service.flyway_schema_history_account_service VALUES
('1040', 'V1040__account_tenant_capacity_owner.sql', 1080396723, true),
('1040.1', 'V1040.1__account_capacity_origin_decisions.sql', 2014106789, true),
('1040.2', 'V1040.2__bound_capacity_origin_refusals.sql', -1834870498, true),
('1040.3', 'V1040.3__validate_only_inserted_refusal_budgets.sql', 1807395108, true);
INSERT INTO account_service.account_capacity_origin_decisions VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'), ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
SQL
"$bin/psql" -XqAtw -c "SHOW server_version;"
# Add one duplicate after creating a verified synthetic retained migration prefix.
"$bin/psql" -XqAtw -v ON_ERROR_STOP=1 -c "INSERT INTO account_service.account_capacity_origin_decisions VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');"
# Exercise the original shell receipt gate, translating only the fixed destination in
# a test-process-only psql adapter; unmodified production shell and SQL are used.
mkdir "$rig/bin"
cat >"$rig/bin/psql" <<'WRAPPER'
#!/usr/bin/env bash
set -euo pipefail
[[ "$#" == 7 && "$1" == -XqAtw && "$2" == --host=postgres.dev.svc.cluster.local \
    && "$3" == --port=5432 && "$4" == --dbname=firemud && "$5" == --username=local_fixture_user \
    && "$6" == --set=ON_ERROR_STOP=1 && "$7" == --set=expected_prefix=3 ]]
args=()
for value in "$@"; do
  case "$value" in
    --host=postgres.dev.svc.cluster.local) args+=("--host=127.0.0.1");;
    --port=5432) args+=("--port=$PGPORT");;
    *) args+=("$value");;
  esac
done
exec "$C9_REAL_PSQL" "${args[@]}"
WRAPPER
chmod 700 "$rig/bin/psql"
export C9_REAL_PSQL="$bin/psql" FIREMUD_POSTGRES_USER="$PGUSER"
invoke_probe() {
  PATH="$rig/bin:$PATH" bash "$probe" dev local-fixture-uid \
    aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 3
}
if invoke_probe >"$rig/refused.out" 2>"$rig/refused.err"; then exit 1; fi
test ! -s "$rig/refused.out"
grep -Fq "duplicate retained admission attempts" "$rig/refused.err"
"$bin/psql" -XqAtw -v ON_ERROR_STOP=1 -c "TRUNCATE account_service.account_capacity_origin_decisions; INSERT INTO account_service.account_capacity_origin_decisions VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'), ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');"
invoke_probe >"$rig/clean.out" 2>"$rig/clean.err"
test ! -s "$rig/clean.err"
grep -Eq '^account-v1040-4-probe=retained-no-duplicates namespace=dev namespace_uid=local-fixture-uid .*applied_origin_migrations=3 checksum_comparison=verified-v1040-prefix$' "$rig/clean.out"
echo "shell receipt gate: clean prefix emits scoped receipt; duplicate prefix exits nonzero with no receipt"
