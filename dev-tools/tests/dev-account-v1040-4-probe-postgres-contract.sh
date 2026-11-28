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
rig=$(mktemp -d /tmp/firemud-account-probe.XXXXXX)
postgres_launch_owned=false
cleanup() {
  if [[ "$postgres_launch_owned" == true ]]; then
    local postgres_status=0
    "$bin/pg_ctl" -D "$rig/data" status >/dev/null 2>&1 || postgres_status=$?
    if (( postgres_status == 0 )); then
      "$bin/pg_ctl" -D "$rig/data" -m fast -w stop >/dev/null || true
      postgres_status=0
      "$bin/pg_ctl" -D "$rig/data" status >/dev/null 2>&1 || postgres_status=$?
    fi
    if (( postgres_status != 3 )); then
      echo "could not confirm the owned PostgreSQL fixture stopped; retaining its data directory at $rig" >&2
      return 1
    fi
  fi
  rm -rf -- "$rig"
}
trap cleanup EXIT
password="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
printf '%s\n' "$password" >"$rig/password"
"$bin/initdb" -L "$share" -D "$rig/data" --no-locale --encoding=UTF8 \
  -A scram-sha-256 --pwfile="$rig/password" -U local_fixture_user >"$rig/init.log"
socket_dir="$rig/socket"
mkdir "$socket_dir"
postgres_launch_owned=true
if ! "$bin/pg_ctl" -D "$rig/data" -l "$rig/postgres.log" \
  -o "-c listen_addresses='' -c unix_socket_directories=$socket_dir -c port=5432" \
  -w start >"$rig/start.log"; then
  cat "$rig/postgres.log" >&2
  exit 1
fi
test -S "$socket_dir/.s.PGSQL.5432"
export PGHOST="$socket_dir" PGPORT=5432 PGUSER=local_fixture_user PGDATABASE=postgres PGPASSWORD="$password"
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
if ! [[ "$#" == 7 && "$1" == -XqAtw && "$2" == --host=postgres.dev.svc.cluster.local \
    && "$3" == --port=5432 && "$4" == --dbname=firemud && "$5" == --username=local_fixture_user \
    && "$6" == --set=ON_ERROR_STOP=1 && "$7" == --set=expected_prefix=3 ]]; then
  printf 'Unexpected fixture psql arguments:' >"$C9_REAL_PSQL_LOG"
  printf ' %q' "$@" >>"$C9_REAL_PSQL_LOG"
  printf '\n' >>"$C9_REAL_PSQL_LOG"
  exit 1
fi
args=()
for value in "$@"; do
  case "$value" in
    --host=postgres.dev.svc.cluster.local) args+=("--host=$PGHOST");;
    *) args+=("$value");;
  esac
done
exec "$C9_REAL_PSQL" "${args[@]}" 2>"$C9_REAL_PSQL_LOG"
WRAPPER
chmod 700 "$rig/bin/psql"
export C9_REAL_PSQL="$bin/psql" C9_REAL_PSQL_LOG="$rig/probe-client.err" FIREMUD_POSTGRES_USER="$PGUSER"
invoke_probe() {
  PATH="$rig/bin:$PATH" bash "$probe" dev local-fixture-uid \
    aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 3
}
if invoke_probe >"$rig/refused.out" 2>"$rig/refused.err"; then exit 1; fi
test ! -s "$rig/refused.out"
if ! grep -Fq "duplicate retained admission attempts" "$rig/refused.err"; then
  cat "$rig/refused.err" >&2
  if [[ -s "$C9_REAL_PSQL_LOG" ]]; then cat "$C9_REAL_PSQL_LOG" >&2; fi
  exit 1
fi
"$bin/psql" -XqAtw -v ON_ERROR_STOP=1 -c "TRUNCATE account_service.account_capacity_origin_decisions; INSERT INTO account_service.account_capacity_origin_decisions VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'), ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');"
if ! invoke_probe >"$rig/clean.out" 2>"$rig/clean.err"; then
  cat "$rig/clean.err" >&2
  if [[ -s "$C9_REAL_PSQL_LOG" ]]; then cat "$C9_REAL_PSQL_LOG" >&2; fi
  exit 1
fi
test ! -s "$rig/clean.err"
expected_receipt='account-v1040-4-probe=retained-no-duplicates namespace=dev namespace_uid=local-fixture-uid deployed_sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa target_sha=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb schema=account_service applied_origin_migrations=3 checksum_comparison=verified-v1040-prefix'
[[ "$(<"$rig/clean.out")" == "$expected_receipt" ]]
echo "shell receipt gate: clean prefix emits scoped receipt; duplicate prefix exits nonzero with no receipt"
