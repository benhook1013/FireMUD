#!/usr/bin/env bash

# Validate the explicit per-run binding before smoke mutation or destructive
# Compose access. This file is intentionally sourceable by smoke entrypoints
# and executable for focused contract checks.
require_run_owned_compose_project() {
  local project_name="${COMPOSE_PROJECT_NAME:-}"
  local run_id
  local run_attempt
  local expected_project

  if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then
    run_id="${GITHUB_RUN_ID:-}"
    run_attempt="${GITHUB_RUN_ATTEMPT:-}"
    if [[ -z "$run_id" || -z "$run_attempt" ]]; then
      echo "Refusing run-owned smoke access: GitHub Actions mode requires nonempty GITHUB_RUN_ID and GITHUB_RUN_ATTEMPT." >&2
      return 1
    fi
    expected_project="smoke-full-${run_id}-${run_attempt}"
    if [[ "$project_name" != "$expected_project" ]]; then
      echo "Refusing run-owned smoke access: COMPOSE_PROJECT_NAME must exactly match ${expected_project} for GitHub run ${run_id} attempt ${run_attempt} (got '${project_name:-<unset>}')." >&2
      return 1
    fi
    return 0
  fi

  run_id="${FIREMUD_SMOKE_RUN_ID:-}"
  if [[ ! "$run_id" =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
    echo "Refusing run-owned smoke access: local FIREMUD_SMOKE_RUN_ID must match ^[a-z0-9][a-z0-9-]*$ and be bound to COMPOSE_PROJECT_NAME." >&2
    return 1
  fi
  expected_project="firemud-smoke-${run_id}"
  if [[ "$project_name" != "$expected_project" ]]; then
    echo "Refusing run-owned smoke access: COMPOSE_PROJECT_NAME must exactly match ${expected_project} for FIREMUD_SMOKE_RUN_ID=${run_id} (got '${project_name:-<unset>}')." >&2
    return 1
  fi
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  require_run_owned_compose_project
fi
