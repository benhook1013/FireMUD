#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

fail() {
  printf 'pr-review controller contract: %s\n' "$1" >&2
  exit 1
}

[[ -f dev-tools/pr-review ]] || fail 'missing dev-tools/pr-review entrypoint'
[[ -x dev-tools/pr-review ]] || fail 'dev-tools/pr-review must be executable'
[[ -d dev-tools/pr_review ]] || fail 'missing dev-tools/pr_review package'

help_output="$(python3 dev-tools/pr-review --help)"
for command in stack status run evidence decide; do
  grep -Fq "$command" <<<"$help_output" \
    || fail "public help does not expose ${command}"
done

stack_help="$(python3 dev-tools/pr-review stack --help)"
grep -Eq 'set' <<<"$stack_help" || fail 'stack help does not expose set'
grep -Eq 'show' <<<"$stack_help" || fail 'stack help does not expose show'

run_help="$(python3 dev-tools/pr-review run --help)"
grep -Eq 'hosted' <<<"$run_help" || fail 'run help does not expose hosted'
grep -Eq 'cli' <<<"$run_help" || fail 'run help does not expose cli'
grep -Eq -- '--expect-pr' <<<"$(python3 dev-tools/pr-review run hosted --help)" \
  || fail 'Hosted help does not expose --expect-pr'
grep -Eq -- '--expect-pr' <<<"$(python3 dev-tools/pr-review run cli --help)" \
  || fail 'CLI help does not expose --expect-pr'
grep -Fq 'trigger-retire' <<<"$(python3 dev-tools/pr-review decide --help)" \
  || fail 'decide help does not expose guarded Hosted trigger retirement'
grep -Fq 'trigger-recover-prepost' <<<"$(python3 dev-tools/pr-review decide --help)" \
  || fail 'decide help does not expose guarded pre-POST recovery'
grep -Fq 'reconcile' <<<"$(python3 dev-tools/pr-review decide --help)" \
  || fail 'decide help does not expose exact stack reconciliation'

# Keep the deletion/reference assertion hermetic. The contract itself constructs
# the retired basenames so it can check every other tracked file without failing
# merely because it documents the names it is required to remove.
for token in \
  'request-' 'run-' 'check-' 'report-pr-review-' 'report-pr-' ; do
  case "$token" in
    request-*) suffix='coderabbit-review.sh' ;;
    run-*) suffix='coderabbit-review.sh' ;;
    check-*) suffix='coderabbit-review.py' ;;
    report-pr-review-*) suffix='checkpoints.py' ;;
    report-pr-*) suffix='status.py' ;;
  esac
  retired="${token}${suffix}"
  if [[ -e "dev-tools/$retired" || -e "dev-tools/validation/$retired" ]]; then
    fail "retired public tool still exists: $retired"
  fi
  # git grep searches all tracked paths, including dotfiles, without requiring
  # an extra runner binary. Exclude this contract, which constructs the retired
  # basenames to keep the scan hermetic.
  if git grep -n -F -e "$retired" -- . ':!dev-tools/tests/pr-review-controller-contract.sh' >/dev/null; then
    fail "retired public tool is still referenced: $retired"
  else
    grep_status=$?
    (( grep_status == 1 )) || fail "retired reference scan failed for $retired (git grep exit $grep_status)"
  fi
done

grep -Fq 'pr-review' AGENTS.md || fail 'AGENTS.md does not route review work through pr-review'
grep -Fq 'pr-review' design/developer-workflows/pr-lifecycle.md \
  || fail 'lifecycle guidance does not name pr-review'
grep -Fq 'one repository review stack' design/developer-workflows/pr-lifecycle.md \
  || fail 'lifecycle guidance does not define one stack'
grep -Fq 'PARENT_MOVED' design/developer-workflows/pr-lifecycle.md \
  || fail 'lifecycle guidance does not define PARENT_MOVED'
grep -Fq 'JUDGMENT_REQUIRED' design/developer-workflows/pr-lifecycle.md \
  || fail 'lifecycle guidance does not define JUDGMENT_REQUIRED'
grep -Fq 'exact parent' design/developer-workflows/pr-lifecycle.md \
  || fail 'lifecycle guidance does not require exact parent anchoring'
grep -Fq 'merge-base SHA' design/developer-workflows/pr-lifecycle.md \
  || fail 'lifecycle guidance does not require merge-base anchoring'
grep -Fq 'unique patch identity' design/developer-workflows/pr-lifecycle.md \
  || fail 'lifecycle guidance does not require patch anchoring'
grep -Fq 'one corrected-state zero-useful' design/developer-workflows/pr-lifecycle.md \
  || fail 'lifecycle guidance does not define Hosted taper'
grep -Fq 'three consecutive zero-useful' design/developer-workflows/pr-lifecycle.md \
  || fail 'lifecycle guidance does not define CLI taper'
grep -Fq -- '--allow-unreconciled' design/developer-workflows/pr-lifecycle.md \
  || fail 'lifecycle guidance does not define provisional CLI semantics'

grep -Fq 'review_results' dev-tools/pr_review/acceptance.py \
  || fail 'acceptance fixtures do not expose deterministic review result sequences'
grep -Fq 'result_positions' dev-tools/pr_review/acceptance.py \
  || fail 'acceptance fixtures do not persist result sequence positions'
grep -Fq 'simulated = True' dev-tools/pr_review/acceptance.py \
  || fail 'acceptance adapter is not marked simulated for live-trigger guards'
awk '
  /^    allocation = decide_commands\.add_parser\(/ {
    getline
    if ($0 ~ /^        "allocation",/) found = 1
  }
  END { exit !found }
' dev-tools/pr_review/cli.py \
  || fail 'public CLI does not register the allocation decision subcommand'

printf 'pr-review controller contract: passed\n'
