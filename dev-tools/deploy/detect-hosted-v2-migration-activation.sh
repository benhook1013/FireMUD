#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "usage: $0 <event_name> <event_before_sha> <head_sha> <namespace>" >&2
  exit 2
fi

event_name="$1"
event_before_sha="${2,,}"
head_sha="${3,,}"
namespace="$4"

emit_mode() {
  printf 'activation=%s\nreason=%s\n' "$1" "$2"
}

if [[ "$namespace" != dev ]]; then
  echo "refusing V2 deployment-mode classification outside the canonical dev namespace" >&2
  exit 1
fi
if [[ ! "$head_sha" =~ ^[0-9a-f]{40}$ ]]; then
  echo "refusing V2 deployment-mode classification with an invalid target SHA" >&2
  exit 1
fi

namespace_json="$(kubectl get namespace "$namespace" --ignore-not-found -o json)"
if [[ -z "$namespace_json" ]]; then
  echo "refusing hosted deployment: namespace $namespace is absent, so its deployed-head ancestry cannot be proven" >&2
  exit 1
fi
if ! namespace_uid="$(jq -er --arg namespace "$namespace" '
  select(.metadata.name == $namespace
    and .metadata.labels["firemud.dev/dev-demo"] == "true"
    and .metadata.labels["firemud.dev/environment-class"] == "dev-demo-cluster")
  | .metadata.uid | select(type == "string" and test("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"))
' <<<"$namespace_json")"; then
  echo "refusing hosted deployment: namespace $namespace is not the owned dev-demo namespace" >&2
  exit 1
fi
if ! jq -e '
  (.metadata.annotations // {}) | has("firemud.dev/pending-dev-demo-deployment") | not
' <<<"$namespace_json" >/dev/null; then
  echo "refusing hosted deployment: an unresolved retained deployment requires owner verification of the exact Helm release and Flyway state before namespace mutation" >&2
  exit 1
fi
if ! base_sha="$(jq -e -r \
  --arg namespace "$namespace" '
    select(.metadata.name == $namespace)
    | select((.metadata.labels // {})["firemud.dev/dev-demo"] == "true")
    | select((.metadata.labels // {})["firemud.dev/environment-class"] == "dev-demo-cluster")
    | (.metadata.annotations // {})["firemud.dev/last-dev-demo-head-sha"] // empty
  ' <<<"$namespace_json")"; then
  echo "refusing hosted deployment: namespace $namespace lacks a trusted deployed-head annotation" >&2
  exit 1
fi
reason_source="trusted deployed-head annotation"
base_sha="${base_sha,,}"
if [[ ! "$base_sha" =~ ^[0-9a-f]{40}$ ]]; then
  echo "refusing hosted deployment: namespace $namespace has a malformed deployed-head annotation" >&2
  exit 1
fi

if ! git cat-file -e "${head_sha}^{commit}" 2>/dev/null; then
  echo "refusing hosted deployment: target SHA is not available for ancestry proof" >&2
  exit 1
fi
if ! git cat-file -e "${base_sha}^{commit}" 2>/dev/null; then
  echo "refusing hosted deployment: $reason_source is not available for ancestry proof" >&2
  exit 1
fi
if ! git merge-base --is-ancestor "$base_sha" "$head_sha"; then
  echo "refusing hosted deployment: $reason_source is not an ancestor of the target SHA" >&2
  exit 1
fi
if [[ "$base_sha" == "$head_sha" ]]; then
  echo "refusing hosted deployment: the namespace already records the target SHA; inspect the existing rollout and database before retrying" >&2
  exit 1
fi
if [[ "$event_name" == push && -n "$event_before_sha" ]]; then
  if [[ ! "$event_before_sha" =~ ^[0-9a-f]{40}$ ]] || [[ "$event_before_sha" =~ ^0{40}$ ]]; then
    echo "refusing hosted deployment: supplied push before-SHA is malformed" >&2
    exit 1
  fi
  if ! git cat-file -e "${event_before_sha}^{commit}" 2>/dev/null; then
    echo "refusing hosted deployment: supplied push before-SHA is not available for ancestry proof" >&2
    exit 1
  fi
  if ! git merge-base --is-ancestor "$event_before_sha" "$head_sha"; then
    echo "refusing hosted deployment: supplied push before-SHA is not an ancestor of the target SHA" >&2
    exit 1
  fi
fi

if ! changed_paths="$(git diff --no-renames --name-only "$base_sha" "$head_sha")"; then
  echo "refusing hosted deployment: unable to inspect the exact deployed-to-target Git range" >&2
  exit 1
fi
game_session_v2="services/game-session-service/src/main/resources/db/migration/V2__scope_gameplay_command_identity.sql"
automation_v2="services/automation-scripting-service/src/main/resources/db/migration/V2__script_patch_readiness_single_active.sql"
account_v25="services/account-service/src/main/resources/db/migration/V25__scope_profile_identity.sql"
account_migrations="services/account-service/src/main/resources/db/migration"
account_uniqueness_migration="$account_migrations/V1040.4__unique_capacity_origin_attempt.sql"
migration_changed=false
hold_account_uniqueness=false
while IFS= read -r changed_path; do
  [[ -z "$changed_path" ]] && continue
  if [[ "$changed_path" =~ ^services/[^/]+/src/main/(java/db/migration/.+\.java|kotlin/db/migration/.+\.kt)$ ]]; then
    echo "refusing hosted deployment: Flyway migration classes are unsupported; migrations must use the supported SQL allowlist: $changed_path" >&2
    exit 1
  fi
  if [[ "$changed_path" =~ ^services/[^/]+/src/main/resources/db/migration/.+[.]sql$ ]]; then
    case "$changed_path" in
      "$game_session_v2"|"$automation_v2"|"$account_v25"|\
      "$account_migrations/V1040.1__account_capacity_origin_decisions.sql"|\
      "$account_migrations/V1040.2__bound_capacity_origin_refusals.sql"|\
      "$account_migrations/V1040.3__validate_only_inserted_refusal_budgets.sql"|\
      "$account_uniqueness_migration"|\
      "$account_migrations/V1040.5__arbitrate_origin_identity_before_refusal_budget.sql"|\
      "$account_migrations/V1040.6__index_retained_capacity_refusal_slots.sql")
        # A retained-database activation is safe only for the first addition of a supported
        # migration. Re-editing or deleting a retained migration cannot prove
        # Flyway checksum/data compatibility, so stop before namespace mutation.
        if git cat-file -e "${base_sha}:${changed_path}" 2>/dev/null ||
          ! git cat-file -e "${head_sha}:${changed_path}" 2>/dev/null; then
          echo "refusing hosted deployment: supported V2 migration is an edit or deletion, so retained-data and Flyway checksum safety is unproven: $changed_path" >&2
          exit 1
        fi
        migration_changed=true
        if [[ "$changed_path" == "$account_uniqueness_migration" ]]; then
          hold_account_uniqueness=true
        fi
        ;;
      "$account_migrations/V1040.7__retain_expired_original_capacity_denials.sql")
        echo "refusing hosted deployment: Account V1040.7 retained activation is held pending an approved writer and consumer boundary: $changed_path" >&2
        exit 1
        ;;
      *)
        echo "refusing hosted deployment: unsupported Flyway migration path in exact range: $changed_path" >&2
        exit 1
        ;;
    esac
  fi
done <<<"$changed_paths"

# A range containing both holds must retain the V1040.7 writer/consumer refusal.
if [[ "$hold_account_uniqueness" == true ]]; then
  echo "refusing hosted deployment: Account V1040.4 retained activation is held pending an enforced writer fence through the diagnostic and Helm handoff: $account_uniqueness_migration" >&2
  exit 1
fi

printf 'deployed_base_sha=%s\n' "$base_sha"
printf 'namespace_uid=%s\n' "$namespace_uid"
if [[ "$migration_changed" == true ]]; then
  emit_mode true "$reason_source first adds a supported Account, Game Session, or Automation migration"
else
  emit_mode false "$reason_source does not first add a supported migration"
fi
