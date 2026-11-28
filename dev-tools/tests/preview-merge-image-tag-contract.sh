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
[[ -z "${WAITER_CALLS:-}" ]] || printf '%s\n' "$*" >>"$WAITER_CALLS"
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
  distinct_replacement|replacement_wrong_attempt)
    source_title="Build Runtime Images secure-pr-artifact pr-7 base-$base_sha head-$head_sha merge-$merge_sha mode-required"
    source_record() {
      local run_id="$1" attempt="$2" event="$3" sha="$4" branch="$5" minute="$6"
      printf '{"id":%s,"workflow_id":501,"run_attempt":%s,"path":".github/workflows/runtime-images.yml","name":"Build Runtime Images","head_branch":"%s","status":"completed","conclusion":"success","event":"%s","head_sha":"%s","html_url":"https://example.test/runtime/%s","display_title":"%s","created_at":"2026-09-21T00:%s:00Z","repository":{"full_name":"example/firemud"},"head_repository":{"full_name":"example/firemud"}}' \
        "$run_id" "$attempt" "$branch" "$event" "$sha" "$run_id" "$source_title" "$minute"
    }
    original_source="$(source_record 101 2 pull_request "$head_sha" feature/example 00)"
    later_source="$(source_record 105 2 repository_dispatch "$base_sha" main 04)"
    case "$*" in
      *'/actions/workflows/runtime-images.yml/runs?event=repository_dispatch'*)
        if [[ -e "$WAITER_STATE" ]]; then
          printf '{"total_count":1,"workflow_runs":[%s]}' "$later_source"
        else
          : >"$WAITER_STATE"
          printf '%s' '{"total_count":0,"workflow_runs":[]}'
        fi
        ;;
      *'/actions/workflows/runtime-images.yml/runs?'*)
        printf '[{"workflow_runs":[%s]}]' "$original_source"
        ;;
      *'/actions/runs/101') printf '%s' "$original_source" ;;
      *'/actions/runs/105/attempts/2')
        if [[ "$GH_FIXTURE" == replacement_wrong_attempt ]]; then
          source_record 105 1 repository_dispatch "$base_sha" main 04
        else
          printf '%s' "$later_source"
        fi
        ;;
      *'/actions/runs/105') printf '%s' "$later_source" ;;
      *'/actions/workflows/publish-pr-runtime-images.yml/runs?event=repository_dispatch&created=2026-09-21T00:04:00Z'*)
        printf '%s' '{"total_count":1,"workflow_runs":[{"id":106,"workflow_id":502,"run_attempt":1,"event":"repository_dispatch","status":"completed","conclusion":"success","display_title":"Publish PR Runtime Images source-run-105 attempt-2","created_at":"2026-09-21T00:05:00Z"}]}'
        ;;
      *'/actions/workflows/publish-pr-runtime-images.yml/runs?event=workflow_run&created=2026-09-21T00:00:00Z'*)
        printf '{"total_count":1,"workflow_runs":[{"id":102,"workflow_id":502,"run_attempt":1,"event":"workflow_run","status":"completed","conclusion":"failure","display_title":"Publish PR Runtime Images %s source-run-101 attempt-2","created_at":"2026-09-21T00:01:00Z"}]}' "$source_title"
        ;;
      *'/actions/workflows/publish-pr-runtime-images.yml/runs?'*)
        printf '%s' '{"total_count":0,"workflow_runs":[]}'
        ;;
      *'/actions/runs/106/attempts/1')
        printf '{"id":106,"workflow_id":502,"path":".github/workflows/publish-pr-runtime-images.yml","head_branch":"main","head_sha":"%s","run_attempt":1,"name":"Publish PR Runtime Images","event":"repository_dispatch","status":"completed","conclusion":"success","display_title":"Publish PR Runtime Images source-run-105 attempt-2","repository":{"full_name":"example/firemud"},"head_repository":{"full_name":"example/firemud"}}' "$base_sha"
        ;;
      *'/actions/runs/106/attempts/1/jobs?'*)
        printf '{"total_count":1,"jobs":[{"id":1006,"run_id":106,"run_attempt":1,"head_sha":"%s","name":"Publish Fixed PR Merge Image Tags","status":"completed","conclusion":"success","steps":[{"name":"Download successful PR image artifacts","number":5,"status":"completed","conclusion":"success"},{"name":"Validate exact PR source and artifact before registry login","number":6,"status":"completed","conclusion":"success"},{"name":"Publish fixed PR image tags","number":8,"status":"completed","conclusion":"success"},{"name":"PR runtime publication receipt v1 source-105-2 publisher-106-1 artifact-9003 sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd","number":9,"status":"completed","conclusion":"success"}]}]}' "$base_sha"
        ;;
      *) exit 2 ;;
    esac
    ;;
  receipt_requalification|requalification_missing_receipt|receipt_after_failed_requalification|receipt_expired_requalification|receipt_wrong_expired_requalification|receipt_no_job_attempt|receipt_typed_callback|receipt_after_tag_conflict|receipt_ignore_ineligible_dispatch|failed_requalification_without_receipt|receipt|receipt_wrong_source|receipt_wrong_publisher|receipt_foreign|receipt_failed|receipt_duplicate|legacy|legacy_expired|legacy_missing|legacy_unsuffixed|legacy_unsuffixed_expired|legacy_unsuffixed_wrong_time|wrong_runs|receipt_transient|receipt_rerun)
    source_title="Build Runtime Images secure-pr-artifact pr-7 base-$base_sha head-$head_sha merge-$merge_sha mode-required"
    fixture_attempt=2
    source_event=pull_request
    source_head="$head_sha"
    source_path='.github/workflows/runtime-images.yml@refs/pull/7/merge'
    source_branch=feature/example
    if [[ "$GH_FIXTURE" == receipt_typed_callback ]]; then
      source_event=repository_dispatch
      source_head="$base_sha"
      source_path='.github/workflows/runtime-images.yml'
      source_branch=main
    fi
    if [[ "$GH_FIXTURE" == receipt_rerun ]]; then
      [[ ! -e "$WAITER_STATE" ]] || fixture_attempt=3
    fi
    publisher_id=102
    publisher_job_id=1001
    if [[ "$GH_FIXTURE" == receipt_rerun && "$fixture_attempt" == 3 ]]; then
      publisher_id=104
      publisher_job_id=1002
    fi
    publisher_title="Publish PR Runtime Images $source_title source-run-101 attempt-$fixture_attempt"
    publisher_event=workflow_run
    if [[ "$GH_FIXTURE" == legacy_unsuffixed* ]]; then
      publisher_title="Publish PR Runtime Images $source_title"
    fi
    if [[ "$GH_FIXTURE" == receipt_requalification || "$GH_FIXTURE" == requalification_missing_receipt ]]; then
      publisher_title="Publish PR Runtime Images requalification source-run-101 attempt-$fixture_attempt"
      publisher_event=repository_dispatch
    fi
    [[ "$GH_FIXTURE" != wrong_runs ]] || publisher_title="${publisher_title/merge-$merge_sha/merge-dddddddddddddddddddddddddddddddddddddddd}"
    digest=sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd
    case "$*" in
      *'/actions/runs/101/attempts/'*/jobs\?*)
        requested_attempt="${2#*/attempts/}"
        requested_attempt="${requested_attempt%%/*}"
        [[ "$requested_attempt" == 2 || "$requested_attempt" == 3 ]] || exit 2
        minute=00
        [[ "$requested_attempt" != 3 ]] || minute=03
        printf '{"total_count":1,"jobs":[{"id":%s,"run_id":101,"run_attempt":%s,"head_sha":"%s","name":"PR Full-Stack Smoke (runtime-required=true)","status":"completed","conclusion":"success","started_at":"2026-09-21T00:%s:01Z","completed_at":"2026-09-21T00:%s:40Z","steps":[{"name":"Upload preview image artifact","number":20,"status":"completed","conclusion":"success","started_at":"2026-09-21T00:%s:30Z","completed_at":"2026-09-21T00:%s:35Z"}]}]}' \
          "$((998 + requested_attempt))" "$requested_attempt" "$source_head" "$minute" "$minute" "$minute" "$minute"
        ;;
      *'/actions/runs/101/artifacts?'*)
        case "$GH_FIXTURE" in
          requalification_missing_receipt|legacy|legacy_unsuffixed|legacy_unsuffixed_wrong_time|receipt_wrong_source|receipt_wrong_publisher|receipt_failed|receipt_duplicate) expired=false ;;
          legacy_expired|legacy_unsuffixed_expired) expired=true ;;
          legacy_missing) printf '%s' '{"total_count":0,"artifacts":[]}'; exit 0 ;;
          *) exit 2 ;;
        esac
        artifact_id=9001
        artifact_digest="$digest"
        if [[ "$fixture_attempt" == 3 ]]; then
          artifact_id=9002
          artifact_digest=sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
        fi
        printf '{"total_count":1,"artifacts":[{"id":%s,"name":"pr-runtime-images-pr-merge-%s","digest":"%s","expired":%s,"workflow_run":{"id":101,"head_sha":"%s"},"created_at":"2026-09-21T00:00:32Z"}]}' \
          "$artifact_id" "$merge_sha" "$artifact_digest" "$expired" "$head_sha"
        ;;
      *'/actions/runs/101/attempts/2'|*'/actions/runs/101/attempts/3'|*'/actions/runs/101')
        if [[ "$GH_FIXTURE" == receipt_transient && ! -e "$WAITER_STATE" ]]; then
          : >"$WAITER_STATE"
          exit 2
        fi
        if [[ "$2" == */attempts/* ]]; then
          fixture_attempt="${2##*/}"
        elif [[ "$GH_FIXTURE" == receipt_rerun ]]; then
          fixture_attempt=3
          : >"$WAITER_STATE"
        fi
        minute=00
        [[ "$fixture_attempt" != 3 ]] || minute=03
        printf '{"id":101,"workflow_id":501,"path":"%s","head_branch":"%s","run_attempt":%s,"name":"Build Runtime Images","status":"completed","conclusion":"success","event":"%s","head_sha":"%s","repository":{"full_name":"example/firemud"},"head_repository":{"full_name":"example/firemud"},"html_url":"https://example.test/runtime/101","display_title":"%s","created_at":"2026-09-21T00:00:00Z","run_started_at":"2026-09-21T00:%s:00Z","updated_at":"2026-09-21T00:%s:45Z"}' \
          "$source_path" "$source_branch" "$fixture_attempt" "$source_event" "$source_head" "$source_title" "$minute" "$minute"
        ;;
      *'/actions/runs/102/attempts/1/jobs?'*|*'/actions/runs/104/attempts/1/jobs?'*)
        receipt= source_attempt="$fixture_attempt" publisher_attempt=1 conclusion=success
        case "$GH_FIXTURE" in
          receipt_wrong_source) source_attempt=1 ;;
          receipt_wrong_publisher) publisher_attempt=2 ;;
          receipt_failed) conclusion=failure ;;
        esac
        if [[ "$GH_FIXTURE" == receipt* ]]; then
          receipt_id=9001
          receipt_digest="$digest"
          if [[ "$source_attempt" == 3 ]]; then
            receipt_id=9002
            receipt_digest=sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
          fi
          printf -v receipt ',{"name":"PR runtime publication receipt v1 source-101-%s publisher-%s-%s artifact-%s %s","number":9,"status":"completed","conclusion":"%s"}' \
            "$source_attempt" "$publisher_id" "$publisher_attempt" "$receipt_id" "$receipt_digest" "$conclusion"
          if [[ "$GH_FIXTURE" == receipt_duplicate ]]; then
            receipt+="$receipt"
          fi
        fi
        publisher_attempt_field=',"run_attempt":1'
        [[ "$GH_FIXTURE" != receipt_no_job_attempt ]] || publisher_attempt_field=''
        download_started="2026-09-21T00:01:03Z"
        [[ "$GH_FIXTURE" != legacy_unsuffixed_wrong_time ]] || download_started="2026-09-21T00:00:29Z"
        printf '{"total_count":1,"jobs":[{"id":%s,"run_id":%s%s,"head_sha":"%s","name":"Publish Fixed PR Merge Image Tags","status":"completed","conclusion":"success","steps":[{"name":"Download successful PR image artifacts","number":5,"status":"completed","conclusion":"success","started_at":"%s"},{"name":"Validate exact PR source and artifact before registry login","number":6,"status":"completed","conclusion":"success"},{"name":"Publish fixed PR image tags","number":8,"status":"completed","conclusion":"success"}%s]}]}' "$publisher_job_id" "$publisher_id" "$publisher_attempt_field" "$base_sha" "$download_started" "$receipt"
        ;;
      *'/actions/runs/103/attempts/1/jobs?'*)
        if [[ "$GH_FIXTURE" == receipt_expired_requalification || "$GH_FIXTURE" == receipt_wrong_expired_requalification ]]; then
          expired_artifact=9001
          [[ "$GH_FIXTURE" != receipt_wrong_expired_requalification ]] || expired_artifact=9888
          printf '{"total_count":2,"jobs":[{"id":1003,"run_id":103,"head_sha":"%s","name":"Publish Fixed PR Merge Image Tags","status":"completed","conclusion":"skipped","steps":[]},{"id":1004,"run_id":103,"head_sha":"%s","name":"Resolve Exact Runtime Publication Source","status":"completed","conclusion":"failure","steps":[{"name":"Resolve successful exact source run","status":"completed","conclusion":"success"},{"name":"Exact runtime artifact expired source-101-%s artifact-%s %s","status":"completed","conclusion":"failure"}]}]}' \
            "$base_sha" "$base_sha" "$fixture_attempt" "$expired_artifact" "$digest"
          exit 0
        fi
        tag_result=skipped
        [[ "$GH_FIXTURE" != receipt_after_tag_conflict ]] || tag_result=failure
        printf '{"total_count":1,"jobs":[{"id":1003,"run_id":103,"run_attempt":1,"head_sha":"%s","name":"Publish Fixed PR Merge Image Tags","status":"completed","conclusion":"failure","steps":[{"name":"Publish fixed PR image tags","number":8,"status":"completed","conclusion":"%s"}]}]}' "$base_sha" "$tag_result"
        ;;
      *'/actions/runs/103/attempts/1')
        printf '{"id":103,"workflow_id":502,"path":".github/workflows/publish-pr-runtime-images.yml","head_branch":"main","head_sha":"%s","run_attempt":1,"name":"Publish PR Runtime Images","event":"repository_dispatch","status":"completed","conclusion":"failure","display_title":"Publish PR Runtime Images requalification source-run-101 attempt-%s","repository":{"full_name":"example/firemud"},"head_repository":{"full_name":"example/firemud"}}' \
          "$base_sha" "$fixture_attempt"
        ;;
      *'/actions/runs/102/attempts/1'|*'/actions/runs/104/attempts/1')
        owner=example/firemud
        if [[ "$GH_FIXTURE" == receipt_foreign ]]; then
          owner=foreign/firemud
        fi
        printf '{"id":%s,"workflow_id":502,"path":".github/workflows/publish-pr-runtime-images.yml","head_branch":"main","head_sha":"%s","run_attempt":1,"name":"Publish PR Runtime Images","event":"%s","status":"completed","conclusion":"success","display_title":"%s","repository":{"full_name":"example/firemud"},"head_repository":{"full_name":"%s"}}' \
          "$publisher_id" "$base_sha" "$publisher_event" "$publisher_title" "$owner"
        ;;
      *publish-pr-runtime-images.yml*)
        if [[ "$GH_FIXTURE" == failed_requalification_without_receipt ]]; then
          case "$*" in
            *event=workflow_run*) printf '%s' '{"total_count":0,"workflow_runs":[]}' ;;
            *event=repository_dispatch*)
              printf '{"total_count":1,"workflow_runs":[{"id":103,"workflow_id":502,"run_attempt":1,"event":"repository_dispatch","status":"completed","conclusion":"failure","display_title":"Publish PR Runtime Images requalification source-run-101 attempt-%s","created_at":"2026-09-21T00:02:00Z"}]}' \
                "$fixture_attempt"
              ;;
          esac
          exit 0
        fi
        if [[ "$GH_FIXTURE" == receipt_after_failed_requalification || "$GH_FIXTURE" == receipt_expired_requalification || "$GH_FIXTURE" == receipt_wrong_expired_requalification || "$GH_FIXTURE" == receipt_after_tag_conflict || "$GH_FIXTURE" == receipt_ignore_ineligible_dispatch ]]; then
          case "$*" in
            *event=workflow_run*)
              printf '{"total_count":1,"workflow_runs":[{"id":102,"workflow_id":502,"run_attempt":1,"event":"workflow_run","status":"completed","conclusion":"success","display_title":"%s","created_at":"2026-09-21T00:01:00Z"}]}' \
                "Publish PR Runtime Images $source_title source-run-101 attempt-$fixture_attempt"
              ;;
            *event=repository_dispatch*)
              dispatch_title="Publish PR Runtime Images requalification source-run-101 attempt-$fixture_attempt"
              if [[ "$GH_FIXTURE" == receipt_ignore_ineligible_dispatch ]]; then
                dispatch_title="Publish PR Runtime Images source-run-101 attempt-$fixture_attempt"
              fi
              printf '{"total_count":1,"workflow_runs":[{"id":103,"workflow_id":502,"run_attempt":1,"event":"repository_dispatch","status":"completed","conclusion":"failure","display_title":"%s","created_at":"2026-09-21T00:02:00Z"}]}' \
                "$dispatch_title"
              ;;
          esac
          exit 0
        fi
        if [[ "$*" != *"event=$publisher_event"* ]]; then
          printf '%s' '{"total_count":0,"workflow_runs":[]}'
          exit 0
        fi
        printf '{"total_count":1,"workflow_runs":[{"id":%s,"workflow_id":502,"run_attempt":1,"event":"%s","status":"completed","conclusion":"success","display_title":"%s","created_at":"2026-09-21T00:01:00Z"}]}' "$publisher_id" "$publisher_event" "$publisher_title"
        ;;
      *)
        printf '[{"workflow_runs":[{"id":101,"workflow_id":501,"run_attempt":%s,"status":"completed","conclusion":"success","event":"%s","head_sha":"%s","html_url":"https://example.test/runtime/101","display_title":"%s","created_at":"2026-09-21T00:00:00Z"}]}]' "$fixture_attempt" "$source_event" "$source_head" "$source_title"
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

for fixture in receipt_requalification receipt_no_job_attempt receipt_typed_callback receipt_expired_requalification receipt_after_failed_requalification receipt_ignore_ineligible_dispatch receipt legacy legacy_unsuffixed receipt_transient receipt_rerun; do
  if ! GH_FIXTURE="$fixture" WAITER_STATE="$TEMP_DIR/waiter-$fixture.state" \
    WAITER_CALLS="$TEMP_DIR/waiter-$fixture.calls" \
    HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=30 \
    HOSTED_IMAGE_PUBLISHER_WAIT_TIMEOUT_SECONDS=30 \
    HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
    HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS=0 \
    bash "$WAITER" "$merge_sha" "$base_sha" "$head_sha" >"$TEMP_DIR/publication-$fixture.out" \
      2>"$TEMP_DIR/publication-$fixture.err"; then
    echo "$fixture failed to prove the expected publication" >&2
    tail -20 "$TEMP_DIR/publication-$fixture.err" >&2
    exit 1
  fi
  if [[ "$fixture" != receipt_rerun ]]; then
    grep -Fq 'using source artifact 9001 (sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd)' \
      "$TEMP_DIR/publication-$fixture.out"
    if [[ "$fixture" == receipt_after_failed_requalification ]]; then
      python3 - "$TEMP_DIR/waiter-$fixture.calls" <<'PY_RETAINED_RECEIPT_ORDER'
import sys
calls = open(sys.argv[1], encoding="utf-8").read().splitlines()
current_reads = [i for i, call in enumerate(calls)
                 if call.rstrip().endswith("/actions/runs/101")]
publisher_jobs = next(i for i, call in enumerate(calls)
                      if "/actions/runs/102/attempts/1/jobs?" in call)
assert len(current_reads) >= 2, "retained receipt requires exact source checks before and after proof"
assert current_reads[0] < publisher_jobs < current_reads[-1], \
    "exact current-source rechecks must bracket retained publisher receipt proof"
PY_RETAINED_RECEIPT_ORDER
    fi
  else
    grep -Fq 'Runtime-image producer 101 advanced from attempt 2 to 3; selecting fresh proof.' \
      "$TEMP_DIR/publication-$fixture.err"
    grep -Fq 'using source artifact 9002 (sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee)' \
      "$TEMP_DIR/publication-$fixture.out"
    if grep -Fq 'using source artifact 9001' "$TEMP_DIR/publication-$fixture.out"; then
      echo 'superseded attempt-2 artifact was selected after the producer advanced' >&2
      exit 1
    fi
    python3 - "$TEMP_DIR/waiter-$fixture.calls" <<'PY_RERUN_PROOF_ORDER'
import sys
calls = open(sys.argv[1], encoding="utf-8").read().splitlines()
def index(fragment):
    matches = [i for i, call in enumerate(calls) if fragment in call]
    assert matches, f"missing proof read: {fragment}"
    return matches[0]
original_publisher_jobs = index("/actions/runs/102/attempts/1/jobs?")
replacement_publisher_jobs = index("/actions/runs/104/attempts/1/jobs?")
current_source = next(i for i, call in enumerate(calls)
                      if call.rstrip().endswith("/actions/runs/101"))
assert original_publisher_jobs < current_source < replacement_publisher_jobs, \
    "attempt-2 publisher proof must precede the current-attempt read and attempt-3 publisher proof must follow it"
PY_RERUN_PROOF_ORDER
    for attempt in 2 3; do
      endpoint="repos/example/firemud/actions/runs/101/attempts/$attempt"
      GH_FIXTURE=receipt_rerun WAITER_STATE="$TEMP_DIR/waiter-$fixture.state" gh api "$endpoint" |
        jq -e --argjson attempt "$attempt" '.run_attempt == $attempt' >/dev/null
      GH_FIXTURE=receipt_rerun WAITER_STATE="$TEMP_DIR/waiter-$fixture.state" gh api "$endpoint/jobs?per_page=100&page=1" |
        jq -e --argjson attempt "$attempt" '.jobs[0].run_attempt == $attempt' >/dev/null
    done
  fi
done

cat >"$TEMP_DIR/proof-rejection-clock.sh" <<'EOF'
# Keep the clock fixed through the proof reads. Stop on the first poll sleep.
unset SECONDS
SECONDS=0
sleep() { SECONDS=$((SECONDS + $1)); }
EOF
for fixture in distinct_replacement replacement_wrong_attempt; do
  result=0
  GH_FIXTURE="$fixture" WAITER_STATE="$TEMP_DIR/waiter-$fixture.state" \
    WAITER_CALLS="$TEMP_DIR/waiter-$fixture.calls" BASH_ENV="$TEMP_DIR/proof-rejection-clock.sh" \
    HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=60 HOSTED_IMAGE_PUBLISHER_WAIT_TIMEOUT_SECONDS=60 \
    HOSTED_IMAGE_WAIT_SLEEP_SECONDS=10 bash "$WAITER" "$merge_sha" "$base_sha" "$head_sha" \
    >"$TEMP_DIR/publication-$fixture.out" 2>"$TEMP_DIR/publication-$fixture.err" || result=$?
  grep -Fq 'Trusted PR image publisher 102 completed with failure' "$TEMP_DIR/publication-$fixture.err"
  grep -Fq '/actions/runs/105/attempts/2' "$TEMP_DIR/waiter-$fixture.calls"
  if [[ "$fixture" == distinct_replacement ]]; then
    if [[ "$result" != 0 ]]; then
      cat "$TEMP_DIR/publication-$fixture.err" >&2
      exit 1
    fi
    grep -Fq 'using source artifact 9003' "$TEMP_DIR/publication-$fixture.out"
    grep -Fq '/actions/runs/106/attempts/1/jobs?' "$TEMP_DIR/waiter-$fixture.calls"
  else
    [[ "$result" == 1 ]]
    ! grep -Fq '/actions/runs/106/attempts/1' "$TEMP_DIR/waiter-$fixture.calls"
    ! grep -Fq 'using source artifact' "$TEMP_DIR/publication-$fixture.out"
  fi
done
for fixture in requalification_missing_receipt receipt_wrong_expired_requalification receipt_after_tag_conflict receipt_wrong_source receipt_wrong_publisher receipt_foreign receipt_failed receipt_duplicate legacy_expired legacy_missing legacy_unsuffixed_expired legacy_unsuffixed_wrong_time; do
  if GH_FIXTURE="$fixture" \
    WAITER_CALLS="$TEMP_DIR/waiter-$fixture.calls" \
    BASH_ENV="$TEMP_DIR/proof-rejection-clock.sh" \
    HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=60 \
    HOSTED_IMAGE_PUBLISHER_WAIT_TIMEOUT_SECONDS=60 \
    HOSTED_IMAGE_WAIT_SLEEP_SECONDS=60 \
    bash "$WAITER" "$merge_sha" "$base_sha" "$head_sha" >"$TEMP_DIR/publication-$fixture.out" 2>"$TEMP_DIR/publication-$fixture.err"; then
    echo "$fixture unexpectedly proved the publication artifact" >&2
    exit 1
  fi
  case "$fixture" in
    legacy_expired|legacy_missing|legacy_unsuffixed_expired) proof_read='/actions/runs/101/artifacts\?' ;;
    legacy_unsuffixed_wrong_time) proof_read='/actions/runs/101/attempts/2/jobs\?' ;;
    receipt_wrong_expired_requalification|receipt_after_tag_conflict) proof_read='/actions/runs/103/attempts/1/jobs\?' ;;
    receipt_foreign) proof_read='/actions/runs/102/attempts/1$' ;;
    *) proof_read='/actions/runs/102/attempts/1/jobs\?' ;;
  esac
  grep -Eq "$proof_read" "$TEMP_DIR/waiter-$fixture.calls"
  grep -Fq 'Exact PR image publication and artifact proof is unavailable' "$TEMP_DIR/publication-$fixture.err"
done

if GH_FIXTURE=failed_requalification_without_receipt \
  HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 \
  HOSTED_IMAGE_PUBLISHER_WAIT_TIMEOUT_SECONDS=2 \
  HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
  bash "$WAITER" "$merge_sha" "$base_sha" "$head_sha" \
    >"$TEMP_DIR/failed-requalification.out" 2>"$TEMP_DIR/failed-requalification.err"; then
  echo 'a failed requalification without a retained successful receipt unexpectedly proved publication' >&2
  exit 1
fi
grep -Fq 'Trusted PR image publisher 103 completed with failure' "$TEMP_DIR/failed-requalification.err"

# A publisher with a mismatched merge token must not satisfy the PR wait.
if GH_FIXTURE=wrong_runs \
  BASH_ENV="$TEMP_DIR/proof-rejection-clock.sh" \
  HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=60 \
  HOSTED_IMAGE_PUBLISHER_WAIT_TIMEOUT_SECONDS=60 \
  HOSTED_IMAGE_WAIT_SLEEP_SECONDS=60 \
  HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS=0 \
  bash "$WAITER" "$merge_sha" "$base_sha" "$head_sha" >"$TEMP_DIR/wrong-runs.out" 2>"$TEMP_DIR/wrong-runs.err"; then
  echo "waiter accepted a publisher for a different merge identity" >&2
  exit 1
fi
grep -Fq "No trusted PR image publisher appeared" "$TEMP_DIR/wrong-runs.err"

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
