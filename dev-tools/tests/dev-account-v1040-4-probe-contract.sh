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
export PROBE_DELETE_ATTEMPT="$temp_dir/probe-delete-attempt"
export PROBE_DRY_RUN="$temp_dir/probe-dry-run"
export PROBE_JOB_READ="$temp_dir/probe-job-read"
export PROBE_DELETING_READ="$temp_dir/probe-deleting-read"
export PROBE_DELETE_REQUESTED="$temp_dir/probe-delete-requested"
PROBE_FIXTURE_HEAD="$(git -C "$root_dir" rev-parse HEAD)"
PROBE_FIXTURE_IMAGE="$(
  git -C "$root_dir" show "$PROBE_FIXTURE_HEAD:k8s/helm/firemud/values-hosted-shared.example.yaml" |
    python3 -c 'import sys, yaml; print(yaml.safe_load(sys.stdin)["previewStack"]["postgres"]["image"])'
)"
export PROBE_FIXTURE_HEAD PROBE_FIXTURE_IMAGE
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
    '--kubeconfig fixture-namespace-manager config view --raw --flatten --minify -o json' | \
    '--kubeconfig fixture-runtime config view --raw --flatten --minify -o json')
      local server=https://dev.cluster.invalid ca=Zml4dHVyZS1jYQ== insecure=false
      if [[ "$2" == fixture-runtime ]]; then
        case "${PROBE_SCENARIO:-}" in
          mismatched-server) server=https://alternate.cluster.invalid ;;
          mismatched-ca) ca=YWx0ZXJuYXRlLWNh ;;
          insecure-runtime) insecure=true ;;
        esac
      fi
      jq -n --arg server "$server" --arg ca "$ca" --argjson insecure "$insecure" '
        {clusters:[{cluster:{server:$server,"certificate-authority-data":$ca,
          "insecure-skip-tls-verify":$insecure}}]}'
      ;;
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
    '--request-timeout=10s -n dev get hpa -o json')
      if [[ "${PROBE_SCENARIO:-}" == renewed-writer && -f "$PROBE_JOB_READ" ]]; then
        printf '{"items":[{"spec":{"scaleTargetRef":{"kind":"Deployment","name":"account-service"}}}]}\n'
      else
        printf '{"items":[]}\n'
      fi
      ;;
    '--request-timeout=10s -n dev get pods -o json')
      printf '{"kind":"PodList","items":[]}\n'
      ;;
    '--request-timeout=10s -n dev get replicasets -o json')
      printf '{"kind":"ReplicaSetList","items":[]}\n'
      ;;
    '--request-timeout=10s -n dev get deployment postgres -o json')
      local kind=Deployment image="$PROBE_FIXTURE_IMAGE"
      case "${PROBE_SCENARIO:-}" in
        wrong-postgres-kind) kind=StatefulSet ;;
        unapproved-postgres-repository) image="registry.example.invalid/fixture/$image" ;;
        unapproved-postgres-digest) image="${image%@*}@sha256:$(printf '%064d' 0)" ;;
      esac
      jq -n --arg kind "$kind" --arg image "$image" '{kind:$kind,metadata:{name:"postgres",namespace:"dev"},
        spec:{template:{spec:{containers:[{name:"postgres",image:$image}]}}}}'
      ;;
    '--request-timeout=10s -n dev get deployment '*" --ignore-not-found -o json")
      [[ "$KUBECONFIG" == fixture-runtime ]] || return 2
      printf '{"metadata":{"generation":2},"status":{"observedGeneration":2},"spec":{"replicas":0}}\n'
      ;;
    '--request-timeout=10s -n dev auth can-i '*)
      case "${PROBE_SCENARIO:-}" in
        missing-delete-permission) [[ "$*" != *'delete jobs.batch' ]] ;;
        missing-get-pod-permission) [[ "$*" != '--request-timeout=10s -n dev auth can-i get pods' ]] ;;
        missing-list-replicasets-permission) [[ "$*" != *'list replicasets.apps' ]] ;;
      esac
      ;;
    '--request-timeout=10s -n dev create --dry-run=server -f - -o name')
      cat >"$PROBE_DRY_RUN"
      [[ "${PROBE_SCENARIO:-}" != admission-denied ]]
      ;;
    '--request-timeout=10s -n dev create -f - -o json')
      cat >"$PROBE_JOB_MANIFEST"
      [[ "${PROBE_SCENARIO:-}" != ambiguous-create ]] || return 1
      jq '.metadata.uid = "fixture-owned-job-uid"' "$PROBE_JOB_MANIFEST"
      ;;
    "--request-timeout=10s -n dev get job $PROBE_FIXTURE_JOB_NAME -o json" | \
    "--request-timeout=10s -n dev get job $PROBE_FIXTURE_JOB_NAME --ignore-not-found -o json")
      [[ ! -f "$PROBE_JOB_DELETED" ]] || return 0
      local observed_uid=fixture-owned-job-uid
      if [[ "${PROBE_SCENARIO:-}" == job-replacement ]] ||
        [[ "${PROBE_SCENARIO:-}" == job-identity-lost && -e "$PROBE_JOB_READ" ]]; then
        observed_uid=different-job-uid
      fi
      if [[ "${PROBE_SCENARIO:-}" == delete-response-lost-while-deleting && -e "$PROBE_DELETE_REQUESTED" ]]; then
        if [[ ! -e "$PROBE_DELETING_READ" ]]; then
          touch "$PROBE_DELETING_READ"
          jq --arg uid "$observed_uid" '.metadata.uid = $uid | .metadata.deletionTimestamp = "2026-11-28T00:00:00Z"' "$PROBE_JOB_MANIFEST"
        else
          touch "$PROBE_JOB_DELETED"
        fi
      else
        jq --arg uid "$observed_uid" '
          .metadata.uid = $uid | .status.conditions = [{type:"Complete",status:"True"}]
        ' "$PROBE_JOB_MANIFEST"
      fi
      ;;
    "--request-timeout=10s -n dev wait --for=condition=complete --timeout=75s job/$PROBE_FIXTURE_JOB_NAME")
      [[ "${PROBE_SCENARIO:-}" != duplicate ]]
      ;;
    '--request-timeout=10s -n dev get pods --selector batch.kubernetes.io/controller-uid=fixture-owned-job-uid -o json')
      local pod_uid=fixture-probe-pod-uid
      if [[ "${PROBE_SCENARIO:-}" == pod-identity-lost && -e "$PROBE_JOB_READ" ]]; then
        pod_uid=different-pod-uid
      fi
      jq -n --arg job "$PROBE_FIXTURE_JOB_NAME" --arg uid "$pod_uid" '
        {kind:"PodList",items:[{kind:"Pod",metadata:{
          name:"fixture-probe-pod",namespace:"dev",uid:$uid,ownerReferences:[
            {apiVersion:"batch/v1",kind:"Job",name:$job,uid:"fixture-owned-job-uid",controller:true}
          ]
        }}]}'
      ;;
    '--request-timeout=10s -n dev logs pod/fixture-probe-pod -c probe')
      touch "$PROBE_JOB_READ"
      local captured_namespace captured_uid captured_base captured_head captured_prefix
      IFS=$'\t' read -r captured_namespace captured_uid captured_base captured_head captured_prefix < <(
        jq -er '.spec.template.spec.containers[0].args[2:] | @tsv' "$PROBE_JOB_MANIFEST"
      )
      printf 'account-v1040-4-probe=retained-no-duplicates namespace=%s namespace_uid=%s deployed_sha=%s target_sha=%s schema=account_service applied_origin_migrations=%s checksum_comparison=verified-v1040-prefix\n' \
        "$captured_namespace" "$captured_uid" "$captured_base" "$captured_head" "$captured_prefix"
      ;;
    '--request-timeout=10s -n dev logs pod/fixture-probe-pod -c probe --tail=1')
      printf '%s\n' 'Account V1040.4 probe found duplicate retained admission attempts. Account-owned disposition is required.'
      ;;
    "--request-timeout=10s delete --raw /apis/batch/v1/namespaces/dev/jobs/$PROBE_FIXTURE_JOB_NAME -f -")
      jq -e '.preconditions.uid == "fixture-owned-job-uid" and .propagationPolicy == "Foreground"' >/dev/null || return 1
      if [[ "${PROBE_SCENARIO:-}" == job-replacement ||
        ( "${PROBE_SCENARIO:-}" == job-identity-lost && -e "$PROBE_JOB_READ" ) ]]; then
        return 1
      fi
      if [[ "${PROBE_SCENARIO:-}" == delete-first-fails && ! -f "$PROBE_DELETE_ATTEMPT" ]]; then
        touch "$PROBE_DELETE_ATTEMPT"
        return 1
      fi
      if [[ "${PROBE_SCENARIO:-}" == delete-response-lost-while-deleting ]]; then
        touch "$PROBE_DELETE_REQUESTED"
        return 1
      fi
      touch "$PROBE_JOB_DELETED"
      [[ "${PROBE_SCENARIO:-}" != delete-response-lost ]]
      ;;
    *)
      echo "unexpected kubectl command in the protected probe fixture: $*" >&2
      return 2
      ;;
  esac
}
export -f kubectl

if printf '%s\n' '{"preconditions":{"uid":"unexpected-job-uid"},"propagationPolicy":"Foreground"}' |
  kubectl --request-timeout=10s delete --raw "/apis/batch/v1/namespaces/dev/jobs/$PROBE_FIXTURE_JOB_NAME" -f -; then
  echo "Mock probe Job deletion accepted an incorrect UID precondition." >&2
  exit 1
fi
[[ ! -e "$PROBE_JOB_DELETED" ]]

run_protected_probe() {
  bash "${PROBE_SCRIPT:-$root_dir/dev-tools/deploy/run-dev-account-v1040-4-probe.sh}" \
    "$@" dev "$namespace_uid" "$deployed_sha" "$PROBE_FIXTURE_HEAD" 3
}

for scenario in mismatched-server mismatched-ca insecure-runtime; do
  if PROBE_SCENARIO="$scenario" run_protected_probe --check-permissions >"$temp_dir/receipt" 2>"$temp_dir/error"; then
    echo "Protected probe accepted $scenario." >&2
    exit 1
  fi
  grep -Eq 'must address the same cluster|must identify a TLS-verified cluster' "$temp_dir/error"
  [[ ! -s "$temp_dir/receipt" && ! -e "$PROBE_JOB_MANIFEST" && ! -e "$PROBE_DRY_RUN" ]]
done

for scenario in unapproved-postgres-repository unapproved-postgres-digest; do
  for mode in --check-permissions run; do
    probe_args=()
    [[ "$mode" != --check-permissions ]] || probe_args+=("$mode")
    if PROBE_SCENARIO="$scenario" run_protected_probe "${probe_args[@]}" >"$temp_dir/receipt" 2>"$temp_dir/error"; then
      echo "Protected probe $mode accepted $scenario." >&2
      exit 1
    fi
    grep -Fq 'retained PostgreSQL image differs from the approved client image' "$temp_dir/error"
    [[ ! -s "$temp_dir/receipt" && ! -e "$PROBE_JOB_MANIFEST" && ! -e "$PROBE_DRY_RUN" ]]
  done
done

run_protected_probe --check-permissions
[[ -s "$PROBE_DRY_RUN" && ! -e "$PROBE_JOB_MANIFEST" ]]
for scenario in missing-delete-permission missing-get-pod-permission missing-list-replicasets-permission admission-denied; do
  if PROBE_SCENARIO="$scenario" run_protected_probe --check-permissions >"$temp_dir/receipt" 2>"$temp_dir/error"; then
    echo "Protected probe access check accepted $scenario." >&2
    exit 1
  fi
  grep -q 'before writer quiescence' "$temp_dir/error"
  [[ ! -e "$PROBE_JOB_MANIFEST" ]]
done

run_protected_probe >"$temp_dir/receipt"
grep -q 'checksum_comparison=verified-v1040-prefix' "$temp_dir/receipt"
[[ -f "$PROBE_JOB_DELETED" ]]
cmp -- "$PROBE_DRY_RUN" "$PROBE_JOB_MANIFEST"

# A disposable checkout view shares the reviewed Git objects but has divergent helpers.
# The job payload and both quiescence reads must still use those reviewed objects.
copied_helpers="$temp_dir/copied-helpers"
mkdir "$copied_helpers"
cp "$root_dir/dev-tools/deploy/run-dev-account-v1040-4-probe.sh" "$copied_helpers/"
printf '%s\n' 'exit 91' >"$copied_helpers/quiesce-v2-schema-migrations.sh"
printf '%s\n' 'exit 92' >"$copied_helpers/probe-dev-account-v1040-4.sh"
rm -f -- "$PROBE_JOB_DELETED" "$PROBE_JOB_READ" "$PROBE_JOB_MANIFEST"
GIT_DIR="$(git -C "$root_dir" rev-parse --absolute-git-dir)" GIT_WORK_TREE="$copied_helpers" \
  PROBE_SCRIPT="$copied_helpers/run-dev-account-v1040-4-probe.sh" run_protected_probe >"$temp_dir/receipt"
grep -q 'checksum_comparison=verified-v1040-prefix' "$temp_dir/receipt"
[[ -f "$PROBE_JOB_DELETED" ]]
cmp -- "$PROBE_DRY_RUN" "$PROBE_JOB_MANIFEST"
jq -e \
  --arg probe "$(git -C "$root_dir" show "$PROBE_FIXTURE_HEAD:dev-tools/deploy/probe-dev-account-v1040-4.sh")" \
  --arg namespace dev --arg uid "$namespace_uid" --arg base "$deployed_sha" \
  --arg head "$PROBE_FIXTURE_HEAD" --arg prefix 3 --arg image "$PROBE_FIXTURE_IMAGE" '
  .kind == "Job" and .metadata.namespace == "dev" and
  .spec.backoffLimit == 0 and .spec.activeDeadlineSeconds == 70 and .spec.ttlSecondsAfterFinished == 300 and
  .spec.template.spec.automountServiceAccountToken == false and
  (.spec.template.spec.containers | if type == "array" then
    length == 1 and .[0].image == $image and .[0].command == ["bash", "-c"] and
    .[0].args == [$probe, "probe-dev-account-v1040-4", $namespace, $uid, $base, $head, $prefix] and
    .[0].env == [
      {name:"FIREMUD_POSTGRES_USER",valueFrom:{secretKeyRef:{name:"firemud-secret",key:"FIREMUD_POSTGRES_USER"}}},
      {name:"PGPASSWORD",valueFrom:{secretKeyRef:{name:"firemud-secret",key:"FIREMUD_POSTGRES_PASSWORD"}}}
    ]
  else false end)
' "$PROBE_JOB_MANIFEST" >/dev/null

for scenario in duplicate replaced renewed-writer ambiguous-create job-replacement job-identity-lost pod-identity-lost delete-first-fails; do
  rm -f -- "$PROBE_JOB_DELETED" "$PROBE_JOB_READ"
  if PROBE_SCENARIO="$scenario" run_protected_probe >"$temp_dir/receipt" 2>"$temp_dir/error"; then
    echo "Protected probe accepted $scenario." >&2
    exit 1
  fi
  [[ ! -s "$temp_dir/receipt" ]]
  if [[ "$scenario" == job-replacement || "$scenario" == job-identity-lost ]]; then
    [[ ! -f "$PROBE_JOB_DELETED" ]]
  else
    [[ -f "$PROBE_JOB_DELETED" ]]
  fi
  if [[ "$scenario" == job-identity-lost || "$scenario" == pod-identity-lost ]]; then
    [[ -f "$PROBE_JOB_READ" ]]
    grep -q 'their bound receipt could not be verified' "$temp_dir/error"
  fi
done

[[ -f "$PROBE_DELETE_ATTEMPT" ]]
rm -f -- "$PROBE_JOB_DELETED"
PROBE_SCENARIO=delete-response-lost run_protected_probe >"$temp_dir/receipt"
[[ -f "$PROBE_JOB_DELETED" && -s "$temp_dir/receipt" ]]

rm -f -- "$PROBE_JOB_DELETED" "$PROBE_DELETING_READ" "$PROBE_DELETE_REQUESTED"
PROBE_SCENARIO=delete-response-lost-while-deleting run_protected_probe >"$temp_dir/receipt"
[[ -f "$PROBE_DELETE_REQUESTED" && -f "$PROBE_JOB_DELETED" && -f "$PROBE_DELETING_READ" && -s "$temp_dir/receipt" ]]

rm -f -- "$PROBE_JOB_MANIFEST" "$PROBE_JOB_DELETED"
if PROBE_SCENARIO=wrong-postgres-kind run_protected_probe >"$temp_dir/receipt" 2>"$temp_dir/error"; then
  echo "Protected probe accepted the wrong PostgreSQL workload kind." >&2
  exit 1
fi
grep -q 'retained PostgreSQL image is unavailable' "$temp_dir/error"
[[ ! -e "$PROBE_JOB_MANIFEST" && ! -s "$temp_dir/receipt" ]]

echo "Protected dev Account V1040.4 probe contract passed (mock Kubernetes transport)."
