#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/dev-tools/hosted/preview/preview-eligibility.py"

revalidate_deploy() {
  local pull_request_json="$1"
  printf '%s' "$pull_request_json" | python3 "$SCRIPT" \
    --revalidate-deploy \
    --expected-repository example/FireMUD \
    --expected-head-sha head-123
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

valid_pull_request='{"state":"open","head":{"sha":"head-123","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop"},"user":{"login":"human"},"labels":[]}'
revalidate_deploy "$valid_pull_request"
valid_automation_pull_request='{"state":"open","head":{"sha":"head-123","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop"},"user":{"login":"github-actions[bot]"},"labels":[]}'
revalidate_deploy "$valid_automation_pull_request"

assert_revalidation_refused \
  '{not-json' \
  'current pull request metadata is malformed'
assert_revalidation_refused \
  '{"state":"closed","head":{"sha":"head-123","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop"},"user":{"login":"human"},"labels":[]}' \
  'pull request is not open (state=closed)'
assert_revalidation_refused \
  '{"state":"open","head":{"sha":"head-stale","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop"},"user":{"login":"human"},"labels":[]}' \
  'head is stale (expected=head-123, current=head-stale)'
assert_revalidation_refused \
  '{"state":"open","head":{"sha":"head-123","repo":{"full_name":"fork/FireMUD"}},"base":{"ref":"develop"},"user":{"login":"human"},"labels":[]}' \
  'head repository is not trusted (expected=example/FireMUD, current=fork/FireMUD)'
assert_revalidation_refused \
  '{"state":"open","head":{"sha":"head-123","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop"},"user":{"login":"human"},"labels":null}' \
  'label metadata is malformed'
assert_revalidation_refused \
  '{"state":"open","head":{"sha":"head-123","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"feature/stack"},"user":{"login":"human"},"labels":[]}' \
  'target is not preview-eligible (reason=unsupported-base-branch)'
assert_revalidation_refused \
  '{"state":"open","head":{"sha":"head-123","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop"},"user":{"login":"dependabot[bot]"},"labels":[]}' \
  'target is not preview-eligible (reason=dependency-bot)'
for malformed_author_pull_request in \
  '{"state":"open","head":{"sha":"head-123","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop"},"labels":[]}' \
  '{"state":"open","head":{"sha":"head-123","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop"},"user":null,"labels":[]}' \
  '{"state":"open","head":{"sha":"head-123","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop"},"user":{"login":42},"labels":[]}' \
  '{"state":"open","head":{"sha":"head-123","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop"},"user":{"login":""},"labels":[]}'
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
    --expected-head-sha head-123 \
    </dev/null 2>&1)"; then
    echo "Conflicting preview eligibility modes were unexpectedly accepted: $*" >&2
    exit 1
  fi
  grep -q 'not allowed with argument' <<<"$conflicting_output"
}

assert_conflicting_modes_refused --inspect-labels --revalidate-deploy
assert_conflicting_modes_refused --revalidate-deploy --inspect-labels

for malformed_labels in 'null' '{}' '[{"name":1}]' '{not-json'; do
  inspect_malformed="$(python3 "$SCRIPT" --inspect-labels --labels-json "$malformed_labels")"
  grep -q '^labels_valid=false$' <<<"$inspect_malformed"
  grep -q '^priority=false$' <<<"$inspect_malformed"
done

deploy_open="$(python3 "$SCRIPT" --operation deploy --state open --base-ref develop --author benhook1013 --labels-json '[]')"
grep -q '^eligible=true$' <<<"$deploy_open"
grep -q '^reason=eligible$' <<<"$deploy_open"

deploy_stacked="$(python3 "$SCRIPT" --operation deploy --state open --base-ref feature/design-and-mvp --author benhook1013 --labels-json '[]')"
grep -q '^eligible=false$' <<<"$deploy_stacked"
grep -q '^reason=unsupported-base-branch$' <<<"$deploy_stacked"

deploy_dependency_bot="$(python3 "$SCRIPT" --operation deploy --state open --base-ref develop --author 'renovate[bot]' --labels-json '[]')"
grep -q '^eligible=false$' <<<"$deploy_dependency_bot"
grep -q '^reason=dependency-bot$' <<<"$deploy_dependency_bot"

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

malformed_deploy="$(python3 "$SCRIPT" --operation deploy --state open --base-ref develop --author benhook1013 --labels-json '{"name":"custom:label"}')"
grep -q '^eligible=false$' <<<"$malformed_deploy"
grep -q '^reason=malformed-label-metadata$' <<<"$malformed_deploy"

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
