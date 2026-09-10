#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/dev-tools/hosted/preview/preview-eligibility.py"
PREVIEW_WORKFLOW="$ROOT_DIR/.github/workflows/preview.yml"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

extract_workflow_step_run() {
  local step_name="$1"
  local output="$2"
  python3 - "$PREVIEW_WORKFLOW" "$step_name" "$output" <<'PY'
import sys
from pathlib import Path

import yaml

workflow = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
matches = [
    step
    for job in workflow["jobs"].values()
    for step in job.get("steps", [])
    if step.get("name") == sys.argv[2]
]
if len(matches) != 1:
    raise SystemExit(
        f"expected exactly one workflow step named {sys.argv[2]!r}, found {len(matches)}"
    )
Path(sys.argv[3]).write_text(matches[0]["run"], encoding="utf-8")
PY
}

revalidate_deploy() {
  local pull_request_json="$1"
  printf '%s' "$pull_request_json" | python3 "$SCRIPT" \
    --revalidate-deploy \
    --expected-repository example/FireMUD \
    --expected-head-sha aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
}

revalidate_cleanup() {
  local pull_request_json="$1"
  printf '%s' "$pull_request_json" | python3 "$SCRIPT" \
    --revalidate-cleanup \
    --expected-repository example/FireMUD \
    --expected-head-sha aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
}

assert_revalidation_refused() {
  local pull_request_json="$1"
  local expected_reason="$2"
  local output

  if output="$(revalidate_deploy "$pull_request_json")"; then
    echo "Revalidation unexpectedly accepted: $pull_request_json" >&2
    exit 1
  fi
  if [[ "$output" != "$expected_reason" ]]; then
    echo "Expected refusal '$expected_reason', got '$output'" >&2
    exit 1
  fi
}

valid_pull_request='{"state":"open","head":{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop"},"user":{"login":"human"},"labels":[]}'
revalidate_deploy "$valid_pull_request"
valid_mixed_case_pull_request='{"state":"open","head":{"sha":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop"},"user":{"login":"human"},"labels":[]}'
revalidate_deploy "$valid_mixed_case_pull_request"
valid_automation_pull_request='{"state":"open","head":{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop"},"user":{"login":"github-actions[bot]"},"labels":[]}'
revalidate_deploy "$valid_automation_pull_request"
valid_cleanup_pull_request='{"state":"closed","head":{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","repo":{"full_name":"example/FireMUD"}}}'
revalidate_cleanup "$valid_cleanup_pull_request"
if cleanup_refusal="$(revalidate_cleanup "$valid_pull_request")"; then
  echo "Cleanup revalidation unexpectedly accepted an open pull request" >&2
  exit 1
fi
test "$cleanup_refusal" = 'pull request is not closed (state=open)'
if cleanup_refusal="$(revalidate_cleanup '{"state":"closed","head":{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","repo":{"full_name":"fork/FireMUD"}}}')"; then
  echo "Cleanup revalidation unexpectedly accepted an untrusted head repository" >&2
  exit 1
fi
test "$cleanup_refusal" = \
  'head repository is not trusted (expected=example/FireMUD, current=fork/FireMUD)'
if invalid_expected_output="$(
  printf '%s' "$valid_pull_request" | python3 "$SCRIPT" \
    --revalidate-deploy \
    --expected-repository example/FireMUD \
    --expected-head-sha head-123
)"; then
  echo "Revalidation unexpectedly accepted a noncanonical expected head SHA" >&2
  exit 1
fi
test "$invalid_expected_output" = 'expected head SHA must be exactly 40 hexadecimal characters'

assert_revalidation_refused \
  '{not-json' \
  'current pull request metadata is malformed'
assert_revalidation_refused \
  '[]' \
  'current pull request metadata is malformed'
assert_revalidation_refused \
  '{"state":"closed","head":{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop"},"user":{"login":"human"},"labels":[]}' \
  'pull request is not open (state=closed)'
assert_revalidation_refused \
  '{"state":"open","head":{"sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop"},"user":{"login":"human"},"labels":[]}' \
  'head is stale (expected=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, current=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb)'
assert_revalidation_refused \
  '{"state":"open","head":{"sha":"not-a-sha","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop"},"user":{"login":"human"},"labels":[]}' \
  'head is stale (expected=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, current=not-a-sha)'
assert_revalidation_refused \
  '{"state":"open","head":{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","repo":{"full_name":"fork/FireMUD"}},"base":{"ref":"develop"},"user":{"login":"human"},"labels":[]}' \
  'head repository is not trusted (expected=example/FireMUD, current=fork/FireMUD)'
assert_revalidation_refused \
  '{"state":"open","head":{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop"},"user":{"login":"human"},"labels":null}' \
  'label metadata is malformed'
assert_revalidation_refused \
  '{"state":"open","head":{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"feature/stack"},"user":{"login":"human"},"labels":[]}' \
  'target is not preview-eligible (reason=unsupported-base-branch)'
assert_revalidation_refused \
  '{"state":"open","head":{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop"},"user":{"login":"dependabot[bot]"},"labels":[]}' \
  'target is not preview-eligible (reason=dependency-bot)'
for malformed_author_pull_request in \
  '{"state":"open","head":{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop"},"labels":[]}' \
  '{"state":"open","head":{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop"},"user":null,"labels":[]}' \
  '{"state":"open","head":{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop"},"user":{"login":42},"labels":[]}' \
  '{"state":"open","head":{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop"},"user":{"login":""},"labels":[]}'
do
  assert_revalidation_refused \
    "$malformed_author_pull_request" \
    'current pull request metadata is malformed (user.login must be a non-empty string)'
done

inspect_priority="$(python3 "$SCRIPT" --inspect-labels --labels-json '[{"name":"preview:priority"},{"name":"quote\"slash\\label"}]')"
grep -q '^labels_valid=true$' <<<"$inspect_priority"
grep -q '^priority=true$' <<<"$inspect_priority"

assert_conflicting_modes_refused() {
  local conflicting_output

  if conflicting_output="$(python3 "$SCRIPT" "$@" \
    --labels-json '[]' \
    --expected-repository example/FireMUD \
    --expected-head-sha aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
    </dev/null 2>&1)"; then
    echo "Conflicting preview eligibility modes were unexpectedly accepted: $*" >&2
    exit 1
  fi
  grep -q 'not allowed with argument' <<<"$conflicting_output"
}

assert_conflicting_modes_refused --inspect-labels --revalidate-deploy
assert_conflicting_modes_refused --revalidate-deploy --inspect-labels
assert_conflicting_modes_refused --revalidate-deploy --revalidate-cleanup

for malformed_labels in 'null' '{}' '[{"name":1}]' '{not-json'; do
  inspect_malformed="$(python3 "$SCRIPT" --inspect-labels --labels-json "$malformed_labels")"
  grep -q '^labels_valid=false$' <<<"$inspect_malformed"
  grep -q '^priority=false$' <<<"$inspect_malformed"
done

target_metadata_run="$TEMP_DIR/target-metadata.sh"
eligibility_run="$TEMP_DIR/eligibility.sh"
extract_workflow_step_run "Resolve preview target metadata" "$target_metadata_run"
extract_workflow_step_run "Resolve preview eligibility" "$eligibility_run"

run_workflow_eligibility() {
  local labels_json="$1"
  local output="$2"
  (
    cd "$ROOT_DIR"
    DERIVED_ACTION=deploy \
      TARGET_STATE=open \
      TARGET_BASE_REF=develop \
      TARGET_AUTHOR=human \
      TARGET_LABELS_JSON="$labels_json" \
      GITHUB_OUTPUT="$output" \
      bash "$eligibility_run"
  )
}

event_metadata_output="$TEMP_DIR/event-metadata.out"
(
  cd "$ROOT_DIR"
  EVENT_NAME=pull_request \
    EVENT_ACTION=synchronize \
    PR_USER_LOGIN=human \
    PR_BASE_REF=develop \
    PR_LABELS_JSON='[{"name":"preview:priority","color":"ffffff"},{"name":"quote\"slash\\label"}]' \
    DERIVED_PR_NUMBER=900 \
    GITHUB_OUTPUT="$event_metadata_output" \
    bash "$target_metadata_run"
)
event_labels_json="$(sed -n 's/^labels_json=//p' "$event_metadata_output")"
test "$event_labels_json" = '[{"name":"preview:priority"},{"name":"quote\"slash\\label"}]'

event_eligibility_output="$TEMP_DIR/event-eligibility.out"
run_workflow_eligibility "$event_labels_json" "$event_eligibility_output"
grep -qx 'eligible=true' "$event_eligibility_output"
grep -qx 'reason=eligible' "$event_eligibility_output"
grep -qx 'priority=true' "$event_eligibility_output"

malformed_event_metadata_output="$TEMP_DIR/malformed-event-metadata.out"
(
  cd "$ROOT_DIR"
  EVENT_NAME=pull_request \
    EVENT_ACTION=synchronize \
    PR_USER_LOGIN=human \
    PR_BASE_REF=develop \
    PR_LABELS_JSON='{}' \
    DERIVED_PR_NUMBER=900 \
    GITHUB_OUTPUT="$malformed_event_metadata_output" \
    bash "$target_metadata_run"
)
malformed_event_labels_json="$(sed -n 's/^labels_json=//p' "$malformed_event_metadata_output")"
test "$malformed_event_labels_json" = '{}'
malformed_event_eligibility_output="$TEMP_DIR/malformed-event-eligibility.out"
run_workflow_eligibility \
  "$malformed_event_labels_json" \
  "$malformed_event_eligibility_output"
grep -qx 'eligible=false' "$malformed_event_eligibility_output"
grep -qx 'reason=malformed-label-metadata' "$malformed_event_eligibility_output"
grep -qx 'priority=false' "$malformed_event_eligibility_output"

mkdir -p "$TEMP_DIR/bin"
cat > "$TEMP_DIR/bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_GH_LOG"
printf '%s' "$FAKE_PULL_REQUEST_JSON"
EOF
chmod +x "$TEMP_DIR/bin/gh"

revalidation_helper="$ROOT_DIR/dev-tools/hosted/preview/revalidate-preview-deploy.sh"
# shellcheck disable=SC2016 # Match literal helper source.
grep -Fq -- 'echo "::error::Refusing preview deploy for PR #${pr_number}: $1"' \
  "$revalidation_helper"
# shellcheck disable=SC2016 # Match literal helper source.
grep -Fq -- 'echo "Refusing preview deploy for PR #${pr_number}: $1" >&2' \
  "$revalidation_helper"
if GITHUB_REPOSITORY=example/FireMUD \
  GH_TOKEN=test-token \
  FAKE_GH_LOG="$TEMP_DIR/revalidate-gh.log" \
  FAKE_PULL_REQUEST_JSON="$valid_pull_request" \
  PATH="$TEMP_DIR/bin:$PATH" \
  bash "$revalidation_helper" 42 head-123 \
  >"$TEMP_DIR/invalid-shell-revalidation.out" \
  2>"$TEMP_DIR/invalid-shell-revalidation.err"; then
  echo "revalidate-preview-deploy accepted a noncanonical expected head SHA" >&2
  exit 1
fi
grep -Fxq 'expected head SHA must be exactly 40 hexadecimal characters' \
  "$TEMP_DIR/invalid-shell-revalidation.err"
test ! -e "$TEMP_DIR/revalidate-gh.log"

if GITHUB_REPOSITORY=example/FireMUD \
  GH_TOKEN=test-token \
  FAKE_GH_LOG="$TEMP_DIR/refused-gh.log" \
  FAKE_PULL_REQUEST_JSON='not-json' \
  PATH="$TEMP_DIR/bin:$PATH" \
  bash "$revalidation_helper" 42 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  >"$TEMP_DIR/refused.stdout" \
  2>"$TEMP_DIR/refused.stderr"; then
  echo "revalidate-preview-deploy accepted malformed pull request metadata" >&2
  exit 1
fi
grep -Fxq \
  '::error::Refusing preview deploy for PR #42: current pull request metadata is malformed' \
  "$TEMP_DIR/refused.stdout"
grep -Fxq \
  'Refusing preview deploy for PR #42: current pull request metadata is malformed' \
  "$TEMP_DIR/refused.stderr"

GITHUB_REPOSITORY=example/FireMUD \
  GH_TOKEN=test-token \
  FAKE_GH_LOG="$TEMP_DIR/cleanup-gh.log" \
  FAKE_PULL_REQUEST_JSON="$valid_cleanup_pull_request" \
  PATH="$TEMP_DIR/bin:$PATH" \
  bash "$revalidation_helper" --cleanup 42 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa

if GITHUB_REPOSITORY=example/FireMUD \
  GH_TOKEN=test-token \
  FAKE_GH_LOG="$TEMP_DIR/reopened-gh.log" \
  FAKE_PULL_REQUEST_JSON="$valid_pull_request" \
  PATH="$TEMP_DIR/bin:$PATH" \
  bash "$revalidation_helper" --cleanup 42 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  >"$TEMP_DIR/reopened.stdout" \
  2>"$TEMP_DIR/reopened.stderr"; then
  echo "revalidate-preview-deploy cleanup mode accepted an open pull request" >&2
  exit 1
fi
grep -Fxq \
  '::error::Refusing preview cleanup for PR #42: pull request is not closed (state=open)' \
  "$TEMP_DIR/reopened.stdout"
grep -Fxq \
  'Refusing preview cleanup for PR #42: pull request is not closed (state=open)' \
  "$TEMP_DIR/reopened.stderr"

dispatch_metadata_output="$TEMP_DIR/dispatch-metadata.out"
(
  cd "$ROOT_DIR"
  FAKE_GH_LOG="$TEMP_DIR/gh.log" \
    FAKE_PULL_REQUEST_JSON='{"state":"open","base":{"ref":"develop"},"user":{"login":"human"},"labels":[{"name":"preview:priority","color":"ffffff"},{"name":"custom:label"}]}' \
    EVENT_NAME=workflow_dispatch \
    EVENT_ACTION='' \
    PR_USER_LOGIN='' \
    PR_BASE_REF='' \
    PR_LABELS_JSON='' \
    DERIVED_PR_NUMBER=900 \
    GITHUB_REPOSITORY=example/FireMUD \
    GITHUB_OUTPUT="$dispatch_metadata_output" \
    PATH="$TEMP_DIR/bin:$PATH" \
    bash "$target_metadata_run"
)
grep -qx 'api repos/example/FireMUD/pulls/900' "$TEMP_DIR/gh.log"
test "$(wc -l < "$TEMP_DIR/gh.log")" -eq 1
dispatch_labels_json="$(sed -n 's/^labels_json=//p' "$dispatch_metadata_output")"
test "$dispatch_labels_json" = '[{"name":"preview:priority"},{"name":"custom:label"}]'
dispatch_eligibility_output="$TEMP_DIR/dispatch-eligibility.out"
run_workflow_eligibility "$dispatch_labels_json" "$dispatch_eligibility_output"
grep -qx 'eligible=true' "$dispatch_eligibility_output"
grep -qx 'reason=eligible' "$dispatch_eligibility_output"
grep -qx 'priority=true' "$dispatch_eligibility_output"

deploy_open="$(python3 "$SCRIPT" --operation deploy --state open --base-ref develop --author benhook1013 --labels-json '[]')"
grep -q '^eligible=true$' <<<"$deploy_open"
grep -q '^reason=eligible$' <<<"$deploy_open"
grep -q '^priority=false$' <<<"$deploy_open"

deploy_stacked="$(python3 "$SCRIPT" --operation deploy --state open --base-ref feature/design-and-mvp --author benhook1013 --labels-json '[]')"
grep -q '^eligible=false$' <<<"$deploy_stacked"
grep -q '^reason=unsupported-base-branch$' <<<"$deploy_stacked"
grep -q '^priority=false$' <<<"$deploy_stacked"

deploy_dependency_bot="$(python3 "$SCRIPT" --operation deploy --state open --base-ref develop --author 'renovate[bot]' --labels-json '[]')"
grep -q '^eligible=false$' <<<"$deploy_dependency_bot"
grep -q '^reason=dependency-bot$' <<<"$deploy_dependency_bot"
grep -q '^priority=false$' <<<"$deploy_dependency_bot"

retain_closed="$(python3 "$SCRIPT" --operation retain --state closed --base-ref develop --author benhook1013 --labels-json '[]')"
grep -q '^eligible=false$' <<<"$retain_closed"
grep -q '^reason=pr-not-open$' <<<"$retain_closed"

destroy_closed="$(python3 "$SCRIPT" --operation destroy --state closed --base-ref develop --author benhook1013 --labels-json '[]')"
grep -q '^eligible=true$' <<<"$destroy_closed"
grep -q '^reason=eligible$' <<<"$destroy_closed"

destroy_unsupported_base="$(python3 "$SCRIPT" --operation destroy --state closed --base-ref feature/design-and-mvp --author benhook1013 --labels-json '[]')"
grep -q '^eligible=false$' <<<"$destroy_unsupported_base"
grep -q '^reason=unsupported-base-branch$' <<<"$destroy_unsupported_base"

destroy_dependency_bot="$(python3 "$SCRIPT" --operation destroy --state closed --base-ref develop --author 'renovate[bot]' --labels-json '[]')"
grep -q '^eligible=false$' <<<"$destroy_dependency_bot"
grep -q '^reason=dependency-bot$' <<<"$destroy_dependency_bot"

unknown_label_deploy="$(python3 "$SCRIPT" --operation deploy --state open --base-ref develop --author benhook1013 --labels-json '[{"name":"custom:label"}]')"
grep -q '^eligible=true$' <<<"$unknown_label_deploy"
grep -q '^reason=eligible$' <<<"$unknown_label_deploy"
grep -q '^priority=false$' <<<"$unknown_label_deploy"

malformed_deploy="$(python3 "$SCRIPT" --operation deploy --state open --base-ref develop --author benhook1013 --labels-json '{"name":"custom:label"}')"
grep -q '^eligible=false$' <<<"$malformed_deploy"
grep -q '^reason=malformed-label-metadata$' <<<"$malformed_deploy"
grep -q '^priority=false$' <<<"$malformed_deploy"

malformed_retain="$(python3 "$SCRIPT" --operation retain --state open --base-ref develop --author benhook1013 --labels-json '{"name":"custom:label"}')"
grep -q '^eligible=false$' <<<"$malformed_retain"
grep -q '^reason=malformed-label-metadata$' <<<"$malformed_retain"

invalid_json_retain="$(python3 "$SCRIPT" --operation retain --state open --base-ref develop --author benhook1013 --labels-json '{not-json')"
grep -q '^eligible=false$' <<<"$invalid_json_retain"
grep -q '^reason=malformed-label-metadata$' <<<"$invalid_json_retain"

malformed_destroy="$(python3 "$SCRIPT" --operation destroy --state closed --base-ref develop --author benhook1013 --labels-json '{"name":"custom:label"}')"
grep -q '^eligible=true$' <<<"$malformed_destroy"
grep -q '^reason=eligible$' <<<"$malformed_destroy"

echo "preview eligibility contract checks passed"
