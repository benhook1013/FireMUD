#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
namespace="dev"
desired_head_sha="$(
  gh api "repos/${GITHUB_REPOSITORY}/branches/develop" --jq '.commit.sha'
)"
[[ "$desired_head_sha" =~ ^[0-9a-f]{40}$ ]]
current_head_sha="$(kubectl get namespace "${namespace}" --ignore-not-found -o jsonpath='{.metadata.annotations.firemud\.dev/last-dev-demo-head-sha}')"
max_failed_attempts=3
max_history_pages=10
expected_deploy_title="Develop Dev Demo Environment deploy head-${desired_head_sha}"
workflow_runs_api="repos/${GITHUB_REPOSITORY}/actions/workflows/dev-demo.yml/runs"

list_run_page() {
  local page="$1"
  local api_args=(
    --method GET
    "${workflow_runs_api}"
    -F branch=develop
    -F per_page=100
    -F "page=${page}"
  )
  gh api "${api_args[@]}"
}

develop_push_run="$(
  gh api \
    --method GET \
    "${workflow_runs_api}" \
    -f branch=develop \
    -f event=push \
    -f "head_sha=${desired_head_sha}" \
    -F per_page=1
)"
history_not_before="$(jq -r '.workflow_runs[0].created_at // empty' <<<"${develop_push_run}")"
if [[ -z "$history_not_before" ]]; then
  # The current develop head can predate this workflow, so it may
  # legitimately have no push-triggered dev-demo run. Bootstrap only
  # from bounded history that contains the exact immutable deploy
  # target. A successful/nonterminal candidate or a complete retry
  # budget is decisive without walking older unrelated history;
  # otherwise the scan must reach the beginning of retained history.
  bootstrap_oldest=""
  bootstrap_candidate_status=""
  bootstrap_candidate_conclusion=""
  bootstrap_failed_attempts=0
  bootstrap_exact_run_count=0
  bootstrap_complete=false
  for ((bootstrap_page = 1; bootstrap_page <= max_history_pages; bootstrap_page += 1)); do
    bootstrap_run_page="$(list_run_page "$bootstrap_page")"
    bootstrap_page_size="$(jq -r '.workflow_runs | length' <<<"${bootstrap_run_page}")"
    bootstrap_exact_runs="$(
      jq -c \
        --arg head "${desired_head_sha}" \
        --arg title "${expected_deploy_title}" \
        '[.workflow_runs[] | select(.head_sha == $head and .display_title == $title)]' \
        <<<"${bootstrap_run_page}"
    )"
    if [[ -z "$bootstrap_candidate_status" ]]; then
      bootstrap_candidate="$(
        jq -r '(first(.[]) // empty) | [.status, (.conclusion // "")] | @tsv' \
          <<<"${bootstrap_exact_runs}"
      )"
      IFS=$'\t' read -r bootstrap_candidate_status bootstrap_candidate_conclusion \
        <<<"${bootstrap_candidate}"
    fi
    page_bootstrap_failures="$(
      jq -r '[.[] | select(.status == "completed" and .conclusion != "success")] | length' \
        <<<"${bootstrap_exact_runs}"
    )"
    bootstrap_failed_attempts=$((bootstrap_failed_attempts + page_bootstrap_failures))
    page_bootstrap_exact_run_count="$(jq -r 'length' <<<"${bootstrap_exact_runs}")"
    bootstrap_exact_run_count=$((bootstrap_exact_run_count + page_bootstrap_exact_run_count))
    page_bootstrap_oldest="$(jq -r '.[-1].created_at // empty' <<<"${bootstrap_exact_runs}")"
    if [[ -n "$page_bootstrap_oldest" ]]; then
      bootstrap_oldest="$page_bootstrap_oldest"
    fi
    if [[ -n "$bootstrap_candidate_status" \
      && "$bootstrap_candidate_status" != completed ]]; then
      bootstrap_complete=true
      break
    fi
    if [[ "$bootstrap_candidate_status" == completed \
      && "$bootstrap_candidate_conclusion" == success ]]; then
      bootstrap_complete=true
      break
    fi
    if (( bootstrap_failed_attempts >= max_failed_attempts )); then
      bootstrap_complete=true
      break
    fi
    if (( bootstrap_page_size < 100 )); then
      bootstrap_complete=true
      break
    fi
  done
  if [[ "$bootstrap_complete" != true ]]; then
    echo "::error title=Dev-demo history bootstrap exhausted::No decisive exact deploy history for ${desired_head_sha} with title '${expected_deploy_title}' was found within ${max_history_pages} pages; refusing a history-blind dispatch."
    exit 1
  fi
  if (( bootstrap_exact_run_count == 0 )); then
    history_not_before="1970-01-01T00:00:00Z"
    echo "No develop push anchor or exact-target dev-demo attempt exists for ${desired_head_sha}; retained history is complete and dispatch may proceed."
  else
    if [[ -z "$bootstrap_oldest" ]]; then
      echo "::error title=Dev-demo history bootstrap invalid::Exact-target history for ${desired_head_sha} has no usable creation timestamp; refusing a history-blind dispatch."
      exit 1
    fi
    history_not_before="$bootstrap_oldest"
    echo "No develop push anchor exists for ${desired_head_sha}; using bounded exact-target dev-demo history from ${history_not_before}."
  fi
fi

# Re-runs retain their original workflow-run identity and can therefore
# sort behind newer completed attempts. Search all statuses
# before using completed history to decide whether another dispatch is safe.
candidate_run=""
page=1
while (( page <= max_history_pages )); do
  run_page="$(list_run_page "$page")"
  candidate_run="$(
    jq -r --arg head "${desired_head_sha}" --arg title "${expected_deploy_title}" \
      '(first(.workflow_runs[] | select(.head_sha == $head and .display_title == $title and .status != "completed")) // empty) | [.id, .status, (.conclusion // "")] | @tsv' \
      <<<"${run_page}"
  )"
  if [[ -n "$candidate_run" ]]; then
    break
  fi
  page_size="$(jq -r '.workflow_runs | length' <<<"${run_page}")"
  if (( page_size < 100 )); then
    break
  fi
  ((page += 1))
done
if (( page > max_history_pages )); then
  echo "::error title=Dev-demo nonterminal history exhausted::Refusing an unbounded nonterminal history scan."
  exit 1
fi

IFS=$'\t' read -r candidate_run_id candidate_status candidate_conclusion <<<"${candidate_run}"

if [[ -n "${candidate_status}" && "${candidate_status}" != completed ]]; then
  echo "Dev-demo run ${candidate_run_id} is already converging develop head ${desired_head_sha}."
  exit 0
fi

# Walk completed history newest-first until the exact target is found
# and, for an unaligned successful candidate, its bounded repair budget
# is known. Bootstrap evidence is only an anchor; its failures are
# already part of this same retained history and must not be counted twice.
# Irrelevant destroy and other-target runs cannot evict this evidence.
failed_attempts=0
unaligned_completed_attempts=0
page=1
while (( page <= max_history_pages )); do
  run_page="$(list_run_page "$page")"
  exact_page_runs="$(
    jq -c \
      --arg head "${desired_head_sha}" \
      --arg title "${expected_deploy_title}" \
      --arg not_before "${history_not_before}" \
      '[.workflow_runs[] | select(.head_sha == $head and .display_title == $title and .created_at >= $not_before)]' \
      <<<"${run_page}"
  )"
  if [[ -z "$candidate_run" ]]; then
    candidate_run="$(
      jq -r \
        '(first(.[]) // empty) | [.id, .status, (.conclusion // "")] | @tsv' \
        <<<"${exact_page_runs}"
    )"
    IFS=$'\t' read -r candidate_run_id candidate_status candidate_conclusion <<<"${candidate_run}"
  fi
  if [[ -n "${candidate_status}" && "${candidate_status}" != completed ]]; then
    break
  fi
  if [[ "${candidate_status}" == completed && "${candidate_conclusion}" == success \
    && "${current_head_sha}" == "${desired_head_sha}" ]]; then
    break
  fi
  page_failed_attempts="$(
    jq -r \
      '[.[] | select(.status == "completed" and .conclusion != "success")] | length' \
      <<<"${exact_page_runs}"
  )"
  failed_attempts=$((failed_attempts + page_failed_attempts))
  if [[ "${current_head_sha}" != "${desired_head_sha}" ]]; then
    page_completed_attempts="$(
      jq -r '[.[] | select(.status == "completed")] | length' \
        <<<"${exact_page_runs}"
    )"
    unaligned_completed_attempts=$((unaligned_completed_attempts + page_completed_attempts))
    if (( unaligned_completed_attempts >= max_failed_attempts )); then
      break
    fi
  fi
  if (( failed_attempts >= max_failed_attempts )); then
    break
  fi
  page_size="$(jq -r '.workflow_runs | length' <<<"${run_page}")"
  if (( page_size < 100 )); then
    break
  fi
  oldest_page_created_at="$(jq -r '.workflow_runs[-1].created_at // empty' <<<"${run_page}")"
  if [[ -z "$oldest_page_created_at" || "$oldest_page_created_at" < "$history_not_before" ]]; then
    break
  fi
  ((page += 1))
done
if (( page > max_history_pages )); then
  echo "::error title=Dev-demo completed history exhausted::Refusing an unbounded completed-history scan."
  exit 1
fi

if [[ -n "${candidate_status}" && "${candidate_status}" != completed ]]; then
  echo "Dev-demo run ${candidate_run_id} is already converging develop head ${desired_head_sha}."
  exit 0
fi

if [[ "${current_head_sha}" == "${desired_head_sha}" \
  && -z "${candidate_run}" ]]; then
  echo "Dev demo already aligned to develop head ${desired_head_sha}; no exact deploy candidate remains."
  exit 0
fi

if [[ "${current_head_sha}" == "${desired_head_sha}" \
  && "${candidate_status}" == completed \
  && "${candidate_conclusion}" == success ]]; then
  echo "Dev demo already aligned to successful develop head ${desired_head_sha}"
  exit 0
fi

if [[ "${current_head_sha}" != "${desired_head_sha}" \
  && "${candidate_status}" == completed \
  && "${candidate_conclusion}" == success ]]; then
  if (( unaligned_completed_attempts >= max_failed_attempts )); then
    echo "::error title=Dev-demo alignment retry budget exhausted::Develop head ${desired_head_sha} has ${unaligned_completed_attempts} completed attempts without namespace alignment; automatic redispatch is stopped until develop advances or an operator intervenes."
    exit 1
  fi
  echo "Redispatching successful dev-demo candidate ${candidate_run_id} because the runtime namespace remains unaligned to develop head ${desired_head_sha}."
fi

if [[ "${candidate_status}" == completed && "${candidate_conclusion}" != success ]]; then
  if (( failed_attempts >= max_failed_attempts )); then
    echo "::error title=Dev-demo retry budget exhausted::Develop head ${desired_head_sha} reached ${failed_attempts} failed or cancelled deploy attempts; automatic redispatch is stopped until develop advances or an operator intervenes."
    exit 1
  fi
  echo "Redispatching failed dev-demo candidate ${candidate_run_id} for develop head ${desired_head_sha}."
fi

echo "Dispatching dev-demo deploy for develop head ${desired_head_sha}"
gh api \
  --method POST \
  "repos/${GITHUB_REPOSITORY}/actions/workflows/dev-demo.yml/dispatches" \
  -f ref="develop" \
  -f 'inputs[action]=deploy' \
  -f "inputs[image_tag]=${desired_head_sha}" \
  -f "inputs[head_sha]=${desired_head_sha}" \
  -f 'inputs[hostname]=dev.preview.firedevops.net' \
  -f 'inputs[telnet_port]=32016'

