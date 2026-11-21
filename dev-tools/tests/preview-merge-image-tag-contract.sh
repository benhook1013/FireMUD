#!/usr/bin/env bash
# shellcheck disable=SC2016 # Literal workflow expressions are contract fixtures.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RESOLVER="$ROOT_DIR/dev-tools/hosted/preview/resolve-preview-image-tag.sh"
WAITER="$ROOT_DIR/dev-tools/hosted/shared/wait-for-runtime-images.sh"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEMP_DIR"' EXIT

merge_sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
base_sha=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
head_sha=cccccccccccccccccccccccccccccccccccccccc
export merge_sha base_sha head_sha

cat > "$TEMP_DIR/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$*" == *"/pulls/7" ]]; then
  base_ref="${GH_BASE_REF:-develop}"
  if [[ "${GH_FIXTURE:-}" == empty ]]; then
    printf '%s' '{"changed_files":0,"base":{"ref":"'"$base_ref"'","sha":"dddddddddddddddddddddddddddddddddddddddd","repo":{"full_name":"example/firemud"}},"head":{"sha":"cccccccccccccccccccccccccccccccccccccccc","repo":{"full_name":"example/firemud"}},"merge_commit_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}'
  elif [[ "${GH_FIXTURE:-}" == incomplete ]]; then
    printf '%s' '{"changed_files":2,"base":{"ref":"'"$base_ref"'","sha":"dddddddddddddddddddddddddddddddddddddddd","repo":{"full_name":"example/firemud"}},"head":{"sha":"cccccccccccccccccccccccccccccccccccccccc","repo":{"full_name":"example/firemud"}},"merge_commit_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}'
  else
    head_sha=cccccccccccccccccccccccccccccccccccccccc
    if [[ "${GH_FIXTURE:-}" == stale_head ]]; then
      head_sha=dddddddddddddddddddddddddddddddddddddddd
    fi
    printf '%s' '{"changed_files":1,"base":{"ref":"'"$base_ref"'","sha":"dddddddddddddddddddddddddddddddddddddddd","repo":{"full_name":"example/firemud"}},"head":{"sha":"'"$head_sha"'","repo":{"full_name":"example/firemud"}},"merge_commit_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}'
  fi
  exit 0
fi
if [[ "$*" == *"/git/ref/heads/"* ]]; then
  printf '%s' '{"ref":"refs/heads/'"${GH_BASE_REF:-develop}"'","object":{"sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}}'
  exit 0
fi
if [[ "$*" == *"/commits/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"* ]]; then
  if [[ "${GH_FIXTURE:-}" == stale_parents ]]; then
    printf '%s' '{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","parents":[{"sha":"dddddddddddddddddddddddddddddddddddddddd"},{"sha":"cccccccccccccccccccccccccccccccccccccccc"}]}'
  else
    printf '%s' '{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","parents":[{"sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},{"sha":"cccccccccccccccccccccccccccccccccccccccc"}]}'
  fi
  exit 0
fi
case "${GH_FIXTURE:-}" in
  runtime)
    printf '%s' '[[{"filename":"services/example/src/Main.java","previous_filename":null}]]'
    ;;
  rename)
    printf '%s' '[[{"filename":"docs/runtime.md","previous_filename":"services/example/src/Main.java"}]]'
    ;;
  non_runtime)
    printf '%s' '[[{"filename":"design/example.md","previous_filename":null}]]'
    ;;
  malformed)
    printf '%s' '[[{"filename":"docs/example.md","previous_filename":42}]]'
    ;;
  empty_path)
    printf '%s' '[[{"filename":"","previous_filename":null}]]'
    ;;
  incomplete)
    printf '%s' '[[{"filename":"design/example.md","previous_filename":null}]]'
    ;;
  empty)
    printf '%s' '[[]]'
    ;;
  branch_runs)
    printf '[{"workflow_runs":[{"id":103,"status":"completed","conclusion":"success","event":"workflow_run","head_sha":"dddddddddddddddddddddddddddddddddddddd","html_url":"https://example.test/runtime/103","display_title":"Build Runtime Images trusted-branch branch-develop sha-%s","created_at":"2026-09-21T00:02:00Z"}]}]' "$head_sha"
    ;;
  branch_wrong)
    printf '[{"workflow_runs":[{"id":104,"status":"completed","conclusion":"success","event":"workflow_run","head_sha":"dddddddddddddddddddddddddddddddddddddd","html_url":"https://example.test/runtime/104","display_title":"Build Runtime Images trusted-branch branch-main sha-%s","created_at":"2026-09-21T00:02:00Z"}]}]' "$head_sha"
    ;;
  success_runs|wrong_runs)
    source_title="Build Runtime Images secure-pr-artifact pr-7 base-${base_sha} head-${head_sha} merge-${merge_sha} mode-required"
    publisher_merge="$merge_sha"
    if [[ "$GH_FIXTURE" == wrong_runs ]]; then
      publisher_merge=dddddddddddddddddddddddddddddddddddddddd
    fi
    publisher_title="Publish PR Runtime Images Build Runtime Images secure-pr-artifact pr-7 base-${base_sha} head-${head_sha} merge-${publisher_merge} mode-required"
    source="$(printf '{"id":101,"run_attempt":1,"status":"completed","conclusion":"success","event":"pull_request","head_sha":"%s","html_url":"https://example.test/runtime/101","display_title":"%s","created_at":"2026-09-21T00:00:00Z","repository":{"full_name":"example/firemud"},"head_repository":{"full_name":"example/firemud"}}' "$head_sha" "$source_title")"
    publisher="$(printf '{"id":102,"run_attempt":2,"status":"completed","conclusion":"success","event":"workflow_run","display_title":"%s","created_at":"2026-09-21T00:01:00Z","path":".github/workflows/publish-pr-runtime-images.yml","head_branch":"develop","repository":{"full_name":"example/firemud"},"head_repository":{"full_name":"example/firemud"}}' "$publisher_title")"
    case "$*" in
      "api repos/example/firemud")
        printf '%s' '{"full_name":"example/firemud","default_branch":"develop"}'
        ;;
      *actions/runs/101)
        printf '%s' "$source"
        ;;
      *actions/runs/102)
        printf '%s' "$publisher"
        ;;
      *actions/runs/102/attempts/2/jobs?per_page=100)
        printf '%s' '{"total_count":1,"jobs":[{"run_id":102,"run_attempt":2,"name":"Publish Fixed PR Merge Image Tags","status":"completed","conclusion":"success","steps":[{"name":"Publish fixed PR image tags","status":"completed","conclusion":"success"}]}]}'
        ;;
      *actions/workflows/publish-pr-runtime-images.yml/runs?*event=workflow_run*)
        printf '{"total_count":1,"workflow_runs":[%s]}' "$publisher"
        ;;
      *actions/workflows/publish-pr-runtime-images.yml/runs?*event=repository_dispatch*)
        printf '%s' '{"total_count":0,"workflow_runs":[]}'
        ;;
      *actions/workflows/runtime-images.yml/runs?*)
        printf '[{"workflow_runs":[%s]}]' "$source"
        ;;
      *)
        echo "Unexpected runtime-image API request in $GH_FIXTURE fixture" >&2
        exit 2
        ;;
    esac
    ;;
  *)
    echo "unknown GH_FIXTURE=${GH_FIXTURE:-}" >&2
    exit 2
    ;;
esac
EOF
chmod +x "$TEMP_DIR/gh"
export PATH="$TEMP_DIR:$PATH"
export GH_TOKEN=test-token GITHUB_REPOSITORY=example/firemud

[[ "$(bash "$RESOLVER" "$merge_sha")" == "pr-merge-${merge_sha}" ]]
[[ "$(GH_FIXTURE=runtime bash "$RESOLVER" "$merge_sha" 7 "$base_sha")" == "pr-merge-${merge_sha}" ]]
[[ "$(GH_FIXTURE=rename bash "$RESOLVER" "$merge_sha" 7 "$base_sha")" == "pr-merge-${merge_sha}" ]]
[[ "$(GH_FIXTURE=non_runtime bash "$RESOLVER" "$merge_sha" 7 "$base_sha")" == "$base_sha" ]]
[[ "$(GH_FIXTURE=non_runtime GH_BASE_REF=feature/stack bash "$RESOLVER" "$merge_sha" 7 "$base_sha")" == "pr-merge-${merge_sha}" ]]

if GH_FIXTURE=malformed bash "$RESOLVER" "$merge_sha" 7 "$base_sha" >/dev/null 2>&1; then
  echo "resolver accepted malformed changed-file metadata" >&2
  exit 1
fi
if GH_FIXTURE=empty bash "$RESOLVER" "$merge_sha" 7 "$base_sha" >/dev/null 2>&1; then
  echo "resolver accepted an empty changed-file response" >&2
  exit 1
fi
if GH_FIXTURE=empty_path bash "$RESOLVER" "$merge_sha" 7 "$base_sha" >/dev/null 2>&1; then
  echo "resolver accepted an empty changed-file path" >&2
  exit 1
fi
if GH_FIXTURE=incomplete bash "$RESOLVER" "$merge_sha" 7 "$base_sha" >/dev/null 2>&1; then
  echo "resolver accepted an incomplete changed-file list" >&2
  exit 1
fi
if GH_FIXTURE=runtime bash "$RESOLVER" "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" 7 "$base_sha" >/dev/null 2>&1; then
  echo "resolver accepted a stale tested merge SHA" >&2
  exit 1
fi
if GH_FIXTURE=stale_parents bash "$RESOLVER" "$merge_sha" 7 "$base_sha" >/dev/null 2>&1; then
  echo "resolver accepted a merge commit with stale parents" >&2
  exit 1
fi
if GH_FIXTURE=stale_head bash "$RESOLVER" "$merge_sha" 7 "$base_sha" >/dev/null 2>&1; then
  echo "resolver accepted a merge commit for a stale PR head" >&2
  exit 1
fi

cat >"$TEMP_DIR/waiter-clock.sh" <<'EOF'
if [[ "${0##*/}" == wait-for-runtime-images.sh ]]; then
  unset SECONDS
  SECONDS=0
  sleep() {
    SECONDS=$((SECONDS + 1))
  }
fi
EOF
export BASH_ENV="$TEMP_DIR/waiter-clock.sh"

GH_FIXTURE=success_runs \
  HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 \
  HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
  HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS=0 \
  timeout 15s bash "$WAITER" "$merge_sha" "$base_sha" "$head_sha" >"$TEMP_DIR/publisher-success.out"
grep -Fq 'Trusted PR image publisher 102 succeeded' "$TEMP_DIR/publisher-success.out"

# A publisher with a mismatched merge token must not satisfy the PR wait.
if wrong_merge_output="$(
  GH_FIXTURE=wrong_runs \
    HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 \
    HOSTED_IMAGE_PUBLISHER_WAIT_TIMEOUT_SECONDS=1 \
    HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
    HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS=0 \
    timeout 15s bash "$WAITER" "$merge_sha" "$base_sha" "$head_sha" 2>&1
)"; then
  echo "waiter accepted a publisher for a different merge identity" >&2
  exit 1
fi
grep -Fq 'No trusted PR image publisher appeared' <<<"$wrong_merge_output"

GH_FIXTURE=branch_runs \
  HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 \
  HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
  HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS=0 \
  timeout 15s bash "$WAITER" "$head_sha" >/dev/null

if branch_timeout_output="$(
  GH_FIXTURE=branch_wrong \
    HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 \
    HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
    HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS=0 \
    timeout 15s bash "$WAITER" "$head_sha" 2>&1
)"; then
  echo "branch waiter accepted a non-develop runtime image run" >&2
  exit 1
fi
grep -Fq "No trusted branch runtime-image publication appeared for branch develop and exact head SHA ${head_sha} after" <<<"$branch_timeout_output"
if grep -Fq 'The PR image source or trusted current-base refresh did not appear for the exact merge SHA.' <<<"$branch_timeout_output"; then
  echo "branch waiter emitted pull-request timeout wording" >&2
  exit 1
fi

grep -Fq '"$MERGE_SHA" "$PR_NUMBER" "$BASE_SHA"' "$ROOT_DIR/.github/workflows/preview.yml"
grep -Fq 'pr-merge-${MERGE_SHA}' "$ROOT_DIR/.github/workflows/preview.yml"
grep -Fq '"$merge_sha" "$PR_NUMBER" "$base_sha"' "$ROOT_DIR/.github/workflows/hosted-identity-request.yml"
grep -Fq 'needs.validate-target.outputs.merge_sha' "$ROOT_DIR/.github/workflows/hosted-identity-request.yml"

echo "preview merge image tag contract passed"
