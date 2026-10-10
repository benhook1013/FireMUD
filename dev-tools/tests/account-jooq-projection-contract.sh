#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd "$ROOT_DIR"

fail() {
  printf 'account jOOQ projection contract: %s\n' "$1" >&2
  exit 1
}

TEMP_PARENT="$(cd "${TMPDIR:-/tmp}" && pwd -P)"
TEMP_DIR="$(mktemp -d "$TEMP_PARENT/account-jooq-projection.XXXXXXXX")"
case "$TEMP_DIR" in
  "$TEMP_PARENT"/account-jooq-projection.*) ;;
  *) fail 'temporary directory did not use the expected private prefix' ;;
esac
[[ -d "$TEMP_DIR" && ! -L "$TEMP_DIR" ]] || fail 'temporary directory is not a real directory'

cleanup() {
  local resolved
  if [[ -n "${TEMP_DIR:-}" && -d "$TEMP_DIR" && ! -L "$TEMP_DIR" ]]; then
    resolved="$(cd "$TEMP_DIR" && pwd -P)" || return
    case "$resolved" in
      "$TEMP_PARENT"/account-jooq-projection.*) rm -rf -- "$resolved" ;;
      *) printf 'account jOOQ projection contract: refusing unvalidated cleanup path\n' >&2 ;;
    esac
  fi
}
trap cleanup EXIT

BASE_COMMIT=3976704464d193e56aa27111724ffdc192c6cef6
BASE_MIGRATION=services/account-service/src/main/resources/db/migration/V52__account_jwt_validator_inventory_snapshots.sql
BASELINE="$TEMP_DIR/original-migration.sql"
EXPECTED="$TEMP_DIR/expected-projection.sql"
INIT_SCRIPT="$TEMP_DIR/override-projection-directories.gradle"
git show "$BASE_COMMIT:$BASE_MIGRATION" > "$BASELINE" \
  || fail 'could not read the original migration fixture from the delegated base commit'
[[ -s "$BASELINE" ]] || fail 'original migration fixture is empty'

python3 - "$BASELINE" "$EXPECTED" <<'PY'
from pathlib import Path
import sys

source_path, expected_path = map(Path, sys.argv[1:])
source_bytes = source_path.read_bytes()
source = source_bytes.decode("utf-8")
ignore_end = "-- [jooq ignore end]"
ignore_stop = "-- [jooq ignore stop]"
inventory_alter = """ALTER TABLE account_jwt_readiness_probe_plans
    ADD COLUMN inventory_snapshot_digest VARCHAR(64),
    ADD CONSTRAINT account_jwt_readiness_plan_inventory_snapshot_digest_check
        CHECK (inventory_snapshot_digest IS NULL
            OR inventory_snapshot_digest ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT account_jwt_readiness_plan_inventory_snapshot_fk
        FOREIGN KEY (inventory_snapshot_digest, environment_id, cluster_id,
            kubernetes_namespace, expected_cluster_incarnation_uid, expected_namespace_uid)
        REFERENCES account_jwt_validator_inventory_snapshots(
            snapshot_digest, environment_id, cluster_id, kubernetes_namespace,
            cluster_incarnation_uid, namespace_uid)
        ON DELETE RESTRICT;"""
projected_inventory_alters = """ALTER TABLE account_jwt_readiness_probe_plans
    ADD COLUMN inventory_snapshot_digest VARCHAR(64);
ALTER TABLE account_jwt_readiness_probe_plans
    ADD CONSTRAINT account_jwt_readiness_plan_inventory_snapshot_digest_check
        CHECK (inventory_snapshot_digest IS NULL
            OR inventory_snapshot_digest ~ '^[0-9a-f]{64}$');
ALTER TABLE account_jwt_readiness_probe_plans
    ADD CONSTRAINT account_jwt_readiness_plan_inventory_snapshot_fk
        FOREIGN KEY (inventory_snapshot_digest, environment_id, cluster_id,
            kubernetes_namespace, expected_cluster_incarnation_uid, expected_namespace_uid)
        REFERENCES account_jwt_validator_inventory_snapshots(
            snapshot_digest, environment_id, cluster_id, kubernetes_namespace,
            cluster_incarnation_uid, namespace_uid)
        ON DELETE RESTRICT;"""

if source.count(ignore_end) != 1 or source.count(inventory_alter) != 1:
    raise SystemExit("original migration fixture does not match the approved projection shape")
expected = source.replace(ignore_end, ignore_stop).replace(
    inventory_alter, projected_inventory_alters
)
expected_path.write_bytes(expected.encode("utf-8"))
PY

cat > "$INIT_SCRIPT" <<'GROOVY'
gradle.projectsEvaluated {
    def account = gradle.rootProject.findProject(":account-service")
    if (account == null) {
        return
    }
    def properties = gradle.startParameter.projectProperties
    def source = properties.get("firemudProjectionSource")
    def output = properties.get("firemudProjectionOutput")
    if (!source || !output) {
        throw new GradleException("temporary Account projection source/output properties are required")
    }
    account.tasks.named("projectAccountJooqMigrations").configure { task ->
        task.sourceDirectory.set(new File(source))
        task.outputDirectory.set(new File(output))
    }
}
GROOVY

run_projection() {
  local source_dir="$1"
  local output_dir="$2"
  bash dev-tools/validation/run-locked-gradle.sh \
    -I "$INIT_SCRIPT" \
    -PfiremudProjectionSource="$source_dir" \
    -PfiremudProjectionOutput="$output_dir" \
    :account-service:projectAccountJooqMigrations
}

write_noninventory_fixtures() {
  local source_dir="$1"
  mkdir -p "$source_dir"
  printf 'SELECT 7;\r\n-- byte-exact fixture without final newline' \
    > "$source_dir/V300__unrelated_projection_contract.sql"
  printf 'SELECT 8;\n' > "$source_dir/prefix_V301__account_jwt_validator_inventory_snapshots.sql"
}

verify_success_case() {
  local version="$1"
  local source_dir="$TEMP_DIR/source-$version"
  local output_dir="$TEMP_DIR/output-$version"
  local migration_name="${version}__account_jwt_validator_inventory_snapshots.sql"
  mkdir -p "$source_dir"
  cp -- "$BASELINE" "$source_dir/$migration_name"
  write_noninventory_fixtures "$source_dir"

  run_projection "$source_dir" "$output_dir" \
    || fail "registered Gradle task rejected $migration_name"
  cmp -s "$source_dir/$migration_name" "$BASELINE" \
    || fail "registered Gradle task modified $migration_name"
  cmp -s "$output_dir/$migration_name" "$EXPECTED" \
    || fail "registered Gradle task produced an unexpected projection for $migration_name"
  cmp -s "$source_dir/V300__unrelated_projection_contract.sql" \
    "$output_dir/V300__unrelated_projection_contract.sql" \
    || fail 'noninventory migration was not copied byte-for-byte'
  cmp -s "$source_dir/prefix_V301__account_jwt_validator_inventory_snapshots.sql" \
    "$output_dir/prefix_V301__account_jwt_validator_inventory_snapshots.sql" \
    || fail 'nonmigration lookalike was not copied byte-for-byte'
}

verify_rejected_case() {
  local label="$1"
  local source_dir="$2"
  local output_dir="$TEMP_DIR/rejected-output-$label"
  mkdir -p "$output_dir"
  printf 'preserve-before-selection\n' > "$output_dir/sentinel.txt"
  if run_projection "$source_dir" "$output_dir" > "$TEMP_DIR/$label.log" 2>&1; then
    fail "registered Gradle task unexpectedly accepted $label inventory migrations"
  fi
  rg -q 'Expected exactly one correctly versioned Account validator-inventory migration' \
    "$TEMP_DIR/$label.log" \
    || fail "registered Gradle task failed for an unrelated reason in $label case"
  cmp -s "$output_dir/sentinel.txt" <(printf 'preserve-before-selection\n') \
    || fail "registered Gradle task changed output before rejecting $label inventory migrations"
  [[ ! -e "$output_dir/V52__account_jwt_validator_inventory_snapshots.sql" ]] \
    || fail "registered Gradle task wrote projected output before rejecting $label inventory migrations"
}

verify_success_case V52
verify_success_case V100
verify_success_case V108

ZERO_SOURCE="$TEMP_DIR/source-zero"
write_noninventory_fixtures "$ZERO_SOURCE"
verify_rejected_case zero "$ZERO_SOURCE"

AMBIGUOUS_SOURCE="$TEMP_DIR/source-ambiguous"
mkdir -p "$AMBIGUOUS_SOURCE"
cp -- "$BASELINE" "$AMBIGUOUS_SOURCE/V52__account_jwt_validator_inventory_snapshots.sql"
cp -- "$BASELINE" "$AMBIGUOUS_SOURCE/V100__account_jwt_validator_inventory_snapshots.sql"
verify_rejected_case ambiguous "$AMBIGUOUS_SOURCE"

printf 'account jOOQ projection contract: passed\n'
