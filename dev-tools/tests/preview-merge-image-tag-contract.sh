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
  receipt|receipt_wrong_source|receipt_wrong_publisher|receipt_foreign|receipt_failed|receipt_duplicate|legacy|legacy_expired|legacy_missing)
    source_title="Build Runtime Images secure-pr-artifact pr-7 base-$base_sha head-$head_sha merge-$merge_sha mode-required"
    publisher_title="Publish PR Runtime Images $source_title source-run-101 attempt-2"
    digest=sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd
    case "$*" in
      *'/actions/runs/101/attempts/2/jobs?'*)
        printf '{"total_count":1,"jobs":[{"id":1000,"run_id":101,"run_attempt":2,"head_sha":"%s","name":"PR Full-Stack Smoke (runtime-required=true)","status":"completed","conclusion":"success","started_at":"2026-09-21T00:00:01Z","completed_at":"2026-09-21T00:00:40Z","steps":[{"name":"Upload preview image artifact","number":20,"status":"completed","conclusion":"success","started_at":"2026-09-21T00:00:30Z","completed_at":"2026-09-21T00:00:35Z"}]}]}' "$head_sha"
        ;;
      *'/actions/runs/101/artifacts?'*)
        case "$GH_FIXTURE" in
          legacy) expired=false ;;
          legacy_expired) expired=true ;;
          legacy_missing) printf '%s' '{"total_count":0,"artifacts":[]}'; exit 0 ;;
          *) exit 2 ;;
        esac
        printf '{"total_count":1,"artifacts":[{"id":9001,"name":"pr-runtime-images-pr-merge-%s","digest":"%s","expired":%s,"workflow_run":{"id":101,"head_sha":"%s"},"created_at":"2026-09-21T00:00:32Z"}]}' \
          "$merge_sha" "$digest" "$expired" "$head_sha"
        ;;
      *'/actions/runs/101/attempts/2'|*'/actions/runs/101')
        printf '{"id":101,"workflow_id":501,"path":".github/workflows/runtime-images.yml@refs/pull/7/merge","head_branch":"feature/example","run_attempt":2,"name":"Build Runtime Images","status":"completed","conclusion":"success","event":"pull_request","head_sha":"%s","repository":{"full_name":"example/firemud"},"head_repository":{"full_name":"example/firemud"},"html_url":"https://example.test/runtime/101","display_title":"%s","created_at":"2026-09-21T00:00:00Z","run_started_at":"2026-09-21T00:00:00Z","updated_at":"2026-09-21T00:00:45Z"}' \
          "$head_sha" "$source_title"
        ;;
      *'/actions/runs/102/attempts/1/jobs?'*)
        receipt= source_attempt=2 publisher_attempt=1 conclusion=success
        case "$GH_FIXTURE" in
          receipt_wrong_source) source_attempt=1 ;;
          receipt_wrong_publisher) publisher_attempt=2 ;;
          receipt_failed) conclusion=failure ;;
        esac
        if [[ "$GH_FIXTURE" == receipt* ]]; then
          printf -v receipt ',{"name":"PR runtime publication receipt v1 source-101-%s publisher-102-%s artifact-9001 %s","number":9,"status":"completed","conclusion":"%s"}' \
            "$source_attempt" "$publisher_attempt" "$digest" "$conclusion"
          if [[ "$GH_FIXTURE" == receipt_duplicate ]]; then
            receipt+="$receipt"
          fi
        fi
        printf '{"total_count":1,"jobs":[{"id":1001,"run_id":102,"run_attempt":1,"head_sha":"%s","name":"Publish Fixed PR Merge Image Tags","status":"completed","conclusion":"success","steps":[{"name":"Download successful PR image artifacts","number":5,"status":"completed","conclusion":"success"},{"name":"Validate exact PR source and artifact before registry login","number":6,"status":"completed","conclusion":"success"},{"name":"Publish fixed PR image tags","number":8,"status":"completed","conclusion":"success"}%s]}]}' "$base_sha" "$receipt"
        ;;
      *'/actions/runs/102/attempts/1')
        owner=example/firemud
        if [[ "$GH_FIXTURE" == receipt_foreign ]]; then
          owner=foreign/firemud
        fi
        printf '{"id":102,"workflow_id":502,"path":".github/workflows/publish-pr-runtime-images.yml","head_branch":"main","head_sha":"%s","run_attempt":1,"name":"Publish PR Runtime Images","event":"workflow_run","status":"completed","conclusion":"success","display_title":"%s","repository":{"full_name":"example/firemud"},"head_repository":{"full_name":"%s"}}' \
          "$base_sha" "$publisher_title" "$owner"
        ;;
      *'event=repository_dispatch'*) printf '%s' '{"total_count":0,"workflow_runs":[]}' ;;
      *publish-pr-runtime-images.yml*)
        printf '{"total_count":1,"workflow_runs":[{"id":102,"workflow_id":502,"run_attempt":1,"event":"workflow_run","status":"completed","conclusion":"success","display_title":"%s","created_at":"2026-09-21T00:01:00Z"}]}' "$publisher_title"
        ;;
      *)
        printf '[{"workflow_runs":[{"id":101,"workflow_id":501,"run_attempt":2,"status":"completed","conclusion":"success","event":"pull_request","head_sha":"%s","html_url":"https://example.test/runtime/101","display_title":"%s","created_at":"2026-09-21T00:00:00Z"}]}]' "$head_sha" "$source_title"
        ;;
    esac
    ;;
  wrong_runs)
    if [[ "$*" == *publish-pr-runtime-images.yml* ]]; then
      if [[ "$*" == *event=repository_dispatch* ]]; then
        printf '%s' '{"total_count":0,"workflow_runs":[]}'
        exit 0
      fi
      printf '{"total_count":1,"workflow_runs":[{"id":102,"run_attempt":1,"event":"workflow_run","status":"completed","conclusion":"success","display_title":"Publish PR Runtime Images Build Runtime Images secure-pr-artifact pr-7 base-%s head-%s merge-dddddddddddddddddddddddddddddddddddddddd mode-required source-run-101 attempt-2","created_at":"2026-09-21T00:01:00Z"}]}' "$base_sha" "$head_sha"
    else
      printf '[{"workflow_runs":[{"id":101,"run_attempt":2,"status":"completed","conclusion":"success","event":"pull_request","head_sha":"%s","html_url":"https://example.test/runtime/101","display_title":"Build Runtime Images secure-pr-artifact pr-7 base-%s head-%s merge-%s mode-required","created_at":"2026-09-21T00:00:00Z"}]}]' "$head_sha" "$base_sha" "$head_sha" "$merge_sha"
    fi
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

for fixture in receipt legacy; do
  GH_FIXTURE="$fixture" \
    HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 \
    HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
    HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS=0 \
    bash "$WAITER" "$merge_sha" "$base_sha" "$head_sha" >"$TEMP_DIR/publication-$fixture.out"
  grep -Fq 'using source artifact 9001 (sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd)' \
    "$TEMP_DIR/publication-$fixture.out"
done

for fixture in receipt_wrong_source receipt_wrong_publisher receipt_foreign receipt_failed receipt_duplicate legacy_expired legacy_missing; do
  if GH_FIXTURE="$fixture" \
    HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 \
    HOSTED_IMAGE_PUBLISHER_WAIT_TIMEOUT_SECONDS=2 \
    HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
    bash "$WAITER" "$merge_sha" "$base_sha" "$head_sha" >"$TEMP_DIR/publication-$fixture.out" 2>"$TEMP_DIR/publication-$fixture.err"; then
    echo "$fixture unexpectedly proved the publication artifact" >&2
    exit 1
  fi
  grep -Fq 'Exact PR image publication and artifact proof is unavailable' "$TEMP_DIR/publication-$fixture.err"
done

# A publisher with a mismatched merge token must not satisfy the PR wait.
if GH_FIXTURE=wrong_runs \
  HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 \
  HOSTED_IMAGE_PUBLISHER_WAIT_TIMEOUT_SECONDS=1 \
  HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
  HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS=0 \
  bash "$WAITER" "$merge_sha" "$base_sha" "$head_sha"; then
  echo "waiter accepted a publisher for a different merge identity" >&2
  exit 1
fi

GH_FIXTURE=branch_runs \
  HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 \
  HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
  HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS=0 \
  bash "$WAITER" "$head_sha" >/dev/null

if branch_timeout_output="$(
  GH_FIXTURE=branch_wrong \
    HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 \
    HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
    HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS=0 \
    bash "$WAITER" "$head_sha" 2>&1
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
