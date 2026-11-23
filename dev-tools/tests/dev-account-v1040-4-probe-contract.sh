#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
probe="$root_dir/dev-tools/deploy/probe-dev-account-v1040-4.sh"
temp_dir="$(mktemp -d)"
trap 'rm -rf -- "$temp_dir"' EXIT

export PATH="$temp_dir:$PATH"
export PROBE_SQL="$temp_dir/probe.sql"
export PROBE_ARGS="$temp_dir/probe.args"
export FIREMUD_POSTGRES_USER=fixture-user

cat >"$temp_dir/psql" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$PGCONNECT_TIMEOUT" == 5 && -z "$PGOPTIONS" && "$PGPASSFILE" == /dev/null ]]
[[ ! -v PGHOSTADDR && ! -v PGSERVICE && ! -v PGSERVICEFILE ]]
printf '%s\n' "$@" >"$PROBE_ARGS"
cat >"$PROBE_SQL"
if [[ "${MOCK_PSQL_EXIT:-0}" != 0 ]]; then
  echo 'fixture detail must not be forwarded' >&2
  exit "$MOCK_PSQL_EXIT"
fi
printf '%s\n' "$MOCK_PSQL_RESULT"
EOF
chmod +x "$temp_dir/psql"

namespace_uid=dev-fixture-uid
deployed_sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
target_sha=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
run_probe() {
  bash "$probe" dev "$namespace_uid" "$deployed_sha" "$target_sha" "$1"
}

receipt="$(
  MOCK_PSQL_RESULT=first-origin-migration \
    PGHOSTADDR=unexpected-address PGSERVICE=unexpected-service PGSERVICEFILE=unexpected-file \
    PGOPTIONS=unexpected-options run_probe 0
)"
[[ "$receipt" == "account-v1040-4-probe=first-origin-migration namespace=dev namespace_uid=$namespace_uid deployed_sha=$deployed_sha target_sha=$target_sha schema=account_service applied_origin_migrations=0 checksum_comparison=verified-v1040-prefix" ]]
diff -u <(printf '%s\n' \
  -XqAtw --host=postgres.dev.svc.cluster.local --port=5432 --dbname=firemud \
  --username=fixture-user --set=ON_ERROR_STOP=1 --set=expected_prefix=0) "$PROBE_ARGS"

for prefix in 1 2 3; do
  receipt="$(MOCK_PSQL_RESULT=retained-no-duplicates run_probe "$prefix")"
  [[ "$receipt" == "account-v1040-4-probe=retained-no-duplicates namespace=dev namespace_uid=$namespace_uid deployed_sha=$deployed_sha target_sha=$target_sha schema=account_service applied_origin_migrations=$prefix checksum_comparison=verified-v1040-prefix" ]]
done

for status in duplicates-present unverified-account-schema unverified-account-history unverified-origin-relation \
  first-origin-migration unexpected ""; do
  if MOCK_PSQL_RESULT="$status" run_probe 3 >"$temp_dir/stdout" 2>"$temp_dir/stderr"; then
    echo "Probe accepted unexpected retained result '$status'." >&2
    exit 1
  fi
  [[ ! -s "$temp_dir/stdout" && -s "$temp_dir/stderr" ]]
  if [[ "$status" == duplicates-present ]]; then
    grep -Fq 'found duplicate retained admission attempts' "$temp_dir/stderr"
  fi
done
for status in retained-no-duplicates $'first-origin-migration\nretained-no-duplicates'; do
  if MOCK_PSQL_RESULT="$status" run_probe 0 >"$temp_dir/stdout" 2>"$temp_dir/stderr"; then
    echo "Probe accepted inconsistent baseline result." >&2
    exit 1
  fi
  [[ ! -s "$temp_dir/stdout" ]]
done
if MOCK_PSQL_EXIT=3 run_probe 3 >"$temp_dir/stdout" 2>"$temp_dir/stderr"; then
  echo "Probe accepted failed PostgreSQL execution." >&2
  exit 1
fi
[[ ! -s "$temp_dir/stdout" ]]
grep -Fq 'could not complete the read-only PostgreSQL diagnostic' "$temp_dir/stderr"
if grep -Fq 'fixture detail' "$temp_dir/stderr"; then
  echo "Probe forwarded internal PostgreSQL details." >&2
  exit 1
fi

for comparison in \
  "staging $namespace_uid $deployed_sha $target_sha 0" \
  "dev invalid/uid $deployed_sha $target_sha 0" \
  "dev $namespace_uid $deployed_sha $deployed_sha 0" \
  "dev $namespace_uid $deployed_sha $target_sha 4"; do
  rm -f -- "$PROBE_ARGS"
  read -r -a args <<<"$comparison"
  if bash "$probe" "${args[@]}" >"$temp_dir/stdout" 2>"$temp_dir/stderr"; then
    echo "Probe accepted invalid comparison." >&2
    exit 1
  fi
  [[ ! -e "$PROBE_ARGS" && ! -s "$temp_dir/stdout" ]]
done

python3 - "$root_dir" "$PROBE_SQL" <<'PY'
import hashlib
import re
import sys
from pathlib import Path

root, sql_path = map(Path, sys.argv[1:])
sql = sql_path.read_text(encoding="utf-8")
assert sql.startswith("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n")
assert sql.strip().endswith("ROLLBACK;")
assert "SET LOCAL statement_timeout = '30s';" in sql
assert "SET LOCAL lock_timeout = '5s';" in sql
assert not re.search(r"\b(INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE|COMMIT|COPY)\b", sql, re.I)
assert "current_database() = 'firemud'" in sql
assert "account_service.flyway_schema_history_account_service" in sql
assert "account_service.account_tenant_capacity_reservations" in sql
assert "count(DISTINCT h.version) = :expected_prefix + 1" in sql
assert "h.success IS TRUE AND h.script IS NOT DISTINCT FROM e.script" in sql
assert "h.checksum IS NOT DISTINCT FROM e.checksum" in sql

migrations = root / "services/account-service/src/main/resources/db/migration"
checksums = {
    "1040": (1080396723, "b4f3d08e6272eb1757331fcfed187ab3035fdbc900274506b1d710ac5daf543e"),
    "1040.1": (2014106789, "a81c98c6f192abace2b98c7ebac89926f68978bd9d911077d0b2aa7a666e6aa0"),
    "1040.2": (-1834870498, "fc049730afe251b4b7c79b282cc647ec88da0ecebb4c0041bbf3045fd2b0c7f3"),
    "1040.3": (1807395108, "4a50e0c7e6ac24d7862daedd8448b0c793a1bd5b2664e8bce79e2461388fe3d6"),
}
for prefix, (version, (checksum, source_hash)) in enumerate(checksums.items()):
    script, = migrations.glob(f"V{version}__*.sql")
    assert hashlib.sha256(script.read_bytes()).hexdigest() == source_hash
    assert f"('{version}', '{script.name}', {prefix}, {checksum})" in sql
assert sql.index("WITH expected") < sql.index(r"\if :expected_history")
assert sql.index(r"\if :first_origin_migration") < sql.index(r"\if :origin_missing")
assert sql.index(r"\elif :expected_origin_relation") < sql.index("GROUP BY admission_attempt_id")
assert "AND NOT c.relrowsecurity" in sql
assert "a.atttypid = 'pg_catalog.uuid'::regtype" in sql
assert re.search(
    r"SELECT EXISTS \(\s*SELECT 1\s+FROM account_service\.account_capacity_origin_decisions"
    r"\s+GROUP BY admission_attempt_id\s+HAVING count\(\*\) > 1",
    sql,
)
PY

echo "Dev Account V1040.4 probe contract passed (mock PostgreSQL transport)."

export PROBE_JOB_MANIFEST="$temp_dir/probe-job.json"
export PROBE_JOB_DELETED="$temp_dir/probe-job-deleted"
export PROBE_JOB_READ="$temp_dir/probe-job-read"
PROBE_FIXTURE_HEAD="$(git -C "$root_dir" rev-parse HEAD)"
export PROBE_FIXTURE_HEAD
export PROBE_FIXTURE_BASE="$deployed_sha"
export PROBE_FIXTURE_UID="$namespace_uid"
export PROBE_FIXTURE_JOB_NAME="firemud-account-v1040-4-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
export KUBECONFIG=fixture-runtime
export DEV_NAMESPACE_MANAGER_KUBECONFIG=fixture-namespace-manager

openssl() {
  [[ "$*" == "rand -hex 16" ]] || return 2
  printf '%s\n' aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
}
export -f openssl

kubectl() {
  case "$*" in
    '--kubeconfig fixture-namespace-manager --request-timeout=10s get namespace dev -o json')
      local uid="$PROBE_FIXTURE_UID"
      if [[ "${PROBE_SCENARIO:-}" == replaced && -f "$PROBE_JOB_READ" ]]; then
        uid=replaced-namespace
      fi
      jq -n --arg uid "$uid" --arg base "$PROBE_FIXTURE_BASE" '
        {metadata: {name: "dev", uid: $uid, labels: {"firemud.dev/dev-demo": "true",
          "firemud.dev/environment-class": "dev-demo-cluster"},
          annotations: {"firemud.dev/last-dev-demo-head-sha": $base}}}'
      ;;
    '-n dev get deployment '*)
      [[ "$KUBECONFIG" == fixture-runtime ]]
      printf '{"spec":{"replicas":0}}\n'
      ;;
    '-n dev get hpa -o json')
      if [[ "${PROBE_SCENARIO:-}" == renewed-writer && -f "$PROBE_JOB_READ" ]]; then
        printf '{"items":[{"spec":{"scaleTargetRef":{"kind":"Deployment","name":"account-service"}}}]}\n'
      else
        printf '{"items":[]}\n'
      fi
      ;;
    '-n dev get pods -o json')
      printf '{"kind":"PodList","items":[]}\n'
      ;;
    '--request-timeout=10s -n dev get statefulset postgres -o json')
      printf '{"spec":{"template":{"spec":{"containers":[{"name":"postgres","image":"postgres:18@sha256:eb63372c1e05c755f55fa30cbd07aecc443676fc6c0fa90b9002d01537841e1a"}]}}}}\n'
      ;;
    '--request-timeout=10s -n dev create -f - -o json')
      cat >"$PROBE_JOB_MANIFEST"
      [[ "${PROBE_SCENARIO:-}" != ambiguous-create ]] || return 1
      jq '.metadata.uid = "fixture-owned-job-uid"' "$PROBE_JOB_MANIFEST"
      ;;
    "--request-timeout=10s -n dev get job $PROBE_FIXTURE_JOB_NAME -o json" | \
    "--request-timeout=10s -n dev get job $PROBE_FIXTURE_JOB_NAME --ignore-not-found -o json")
      local observed_uid=fixture-owned-job-uid
      if [[ "${PROBE_SCENARIO:-}" == job-replacement ]]; then
        observed_uid=different-job-uid
      fi
      jq --arg uid "$observed_uid" '.metadata.uid = $uid' "$PROBE_JOB_MANIFEST"
      ;;
    "-n dev wait --for=condition=complete --timeout=75s job/$PROBE_FIXTURE_JOB_NAME")
      [[ "${PROBE_SCENARIO:-}" != duplicate ]]
      ;;
    "--request-timeout=10s -n dev logs job/$PROBE_FIXTURE_JOB_NAME")
      touch "$PROBE_JOB_READ"
      printf 'account-v1040-4-probe=retained-no-duplicates namespace=dev namespace_uid=%s deployed_sha=%s target_sha=%s schema=account_service applied_origin_migrations=3 checksum_comparison=verified-v1040-prefix\n' \
        "$PROBE_FIXTURE_UID" "$PROBE_FIXTURE_BASE" "$PROBE_FIXTURE_HEAD"
      ;;
    "--request-timeout=10s -n dev logs job/$PROBE_FIXTURE_JOB_NAME --tail=1")
      printf '%s\n' 'Account V1040.4 probe found duplicate retained admission attempts. Account-owned disposition is required.'
      ;;
    "--request-timeout=10s delete --raw /apis/batch/v1/namespaces/dev/jobs/$PROBE_FIXTURE_JOB_NAME -f -")
      jq -e '.preconditions.uid == "fixture-owned-job-uid" and .propagationPolicy == "Foreground"' >/dev/null
      [[ "${PROBE_SCENARIO:-}" != job-replacement ]] || return 1
      touch "$PROBE_JOB_DELETED"
      ;;
    "-n dev wait --for=delete --timeout=30s job/$PROBE_FIXTURE_JOB_NAME")
      [[ -f "$PROBE_JOB_DELETED" ]]
      ;;
    *)
      echo "unexpected kubectl command in the protected probe fixture: $*" >&2
      return 2
      ;;
  esac
}
export -f kubectl

run_protected_probe() {
  bash "$root_dir/dev-tools/deploy/run-dev-account-v1040-4-probe.sh" \
    dev "$namespace_uid" "$deployed_sha" "$PROBE_FIXTURE_HEAD" 3
}

run_protected_probe >"$temp_dir/receipt"
grep -q 'checksum_comparison=verified-v1040-prefix' "$temp_dir/receipt"
[[ -f "$PROBE_JOB_DELETED" ]]
jq -e '
  .kind == "Job" and .metadata.namespace == "dev" and
  .spec.backoffLimit == 0 and .spec.activeDeadlineSeconds == 70 and .spec.ttlSecondsAfterFinished == 300 and
  .spec.template.spec.automountServiceAccountToken == false and
  (.spec.template.spec.containers | if type == "array" then
    length == 1 and .[0].command == ["bash", "-c"] and
    (.[0].args[0] | contains("REPEATABLE READ READ ONLY")) and
    .[0].env == [
      {name:"FIREMUD_POSTGRES_USER",valueFrom:{secretKeyRef:{name:"firemud-secret",key:"FIREMUD_POSTGRES_USER"}}},
      {name:"PGPASSWORD",valueFrom:{secretKeyRef:{name:"firemud-secret",key:"FIREMUD_POSTGRES_PASSWORD"}}}
    ]
  else false end)
' "$PROBE_JOB_MANIFEST" >/dev/null

for scenario in duplicate replaced renewed-writer ambiguous-create job-replacement; do
  rm -f -- "$PROBE_JOB_DELETED" "$PROBE_JOB_READ"
  if PROBE_SCENARIO="$scenario" run_protected_probe >"$temp_dir/receipt" 2>"$temp_dir/error"; then
    echo "Protected probe accepted $scenario." >&2
    exit 1
  fi
  [[ ! -s "$temp_dir/receipt" ]]
  if [[ "$scenario" == job-replacement ]]; then
    [[ ! -f "$PROBE_JOB_DELETED" ]]
  else
    [[ -f "$PROBE_JOB_DELETED" ]]
  fi
done

echo "Protected dev Account V1040.4 probe contract passed (mock Kubernetes transport)."
