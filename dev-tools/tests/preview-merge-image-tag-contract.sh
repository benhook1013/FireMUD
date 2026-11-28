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
  case "${GH_FIXTURE:-}" in
    branch_newer_noop|branch_older_jobs_transient)
      printf '{"ref":"refs/heads/develop","object":{"sha":"%s"}}' "$base_sha"
      ;;
    branch_wrong|branch_runs|branch_unpromoted_current|branch_newer_failed|branch_newer_ambiguous|branch_older_mismatch|branch_older_unproved)
      printf '{"ref":"refs/heads/develop","object":{"sha":"%s"}}' "$head_sha"
      ;;
    branch_ref_unavailable)
      exit 1
      ;;
    branch_ref_malformed)
      printf '%s' '{"ref":"refs/heads/develop","object":{"sha":"not-a-commit-sha"}}'
      ;;
    branch_ref_wrong_branch)
      printf '{"ref":"refs/heads/main","object":{"sha":"%s"}}' "$base_sha"
      ;;
    *)
      printf '%s' '{"ref":"refs/heads/'"${GH_BASE_REF:-develop}"'","object":{"sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}}'
      ;;
  esac
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
  branch_runs|branch_obsolete|branch_unpromoted_current|branch_ref_unavailable|branch_ref_malformed|branch_ref_wrong_branch)
    source="$(printf '{"id":103,"run_attempt":2,"status":"completed","conclusion":"success","event":"workflow_run","html_url":"https://example.test/runtime/103","display_title":"Build Runtime Images trusted-branch branch-develop sha-%s","created_at":"2026-09-21T00:02:00Z","repository":{"full_name":"example/firemud"},"head_repository":{"full_name":"example/firemud"}}' "$head_sha")"
    case "$*" in
      *actions/runs/103)
        printf '%s' "$source"
        ;;
      *actions/runs/103/attempts/2/jobs?per_page=100)
        if [[ "$GH_FIXTURE" == branch_runs ]]; then
          printf '%s' '{"total_count":1,"jobs":[{"run_id":103,"run_attempt":2,"name":"Promote Smoke-Tested Runtime Images","status":"completed","conclusion":"success","steps":[{"name":"Promote smoke-tested service digests","status":"completed","conclusion":"success"}]}]}'
        else
          printf '%s' '{"total_count":1,"jobs":[{"run_id":103,"run_attempt":2,"name":"Promote Smoke-Tested Runtime Images","status":"completed","conclusion":"skipped","steps":[]}]}'
        fi
        ;;
      *actions/workflows/runtime-images.yml/runs?*)
        printf '[{"workflow_runs":[%s]}]' "$source"
        ;;
      *)
        echo "Unexpected branch runtime-image API request in $GH_FIXTURE fixture" >&2
        exit 2
        ;;
    esac
    ;;
  branch_wrong)
    first="$(printf '{"id":104,"run_attempt":1,"status":"completed","conclusion":"success","event":"workflow_run","html_url":"https://example.test/runtime/104","display_title":"Build Runtime Images trusted-branch branch-main sha-%s","created_at":"2026-09-21T00:02:00Z","repository":{"full_name":"example/firemud"},"head_repository":{"full_name":"example/firemud"}}' "$head_sha")"
    second="$(printf '{"id":105,"run_attempt":1,"status":"completed","conclusion":"success","event":"workflow_run","html_url":"https://example.test/runtime/105","display_title":"Build Runtime Images trusted-branch sha-%s branch-develop","created_at":"2026-09-21T00:03:00Z","repository":{"full_name":"example/firemud"},"head_repository":{"full_name":"example/firemud"}}' "$head_sha")"
    case "$*" in
      *actions/runs/104) printf '%s' "$first" ;;
      *actions/runs/105) printf '%s' "$second" ;;
      *actions/runs/104/attempts/1/jobs?per_page=100|*actions/runs/105/attempts/1/jobs?per_page=100)
        run_id=104
        [[ "$*" != *actions/runs/105/* ]] || run_id=105
        printf '{"total_count":1,"jobs":[{"run_id":%s,"run_attempt":1,"name":"Promote Smoke-Tested Runtime Images","status":"completed","conclusion":"success","steps":[{"name":"Promote smoke-tested service digests","status":"completed","conclusion":"success"}]}]}' "$run_id"
        ;;
      *actions/workflows/runtime-images.yml/runs?*) printf '[{"workflow_runs":[%s,%s]}]' "$first" "$second" ;;
      *) echo "Unexpected API request in $GH_FIXTURE fixture" >&2; exit 2 ;;
    esac
    ;;
  branch_ninth_promotion|branch_intermediate_rerun)
    promoted_id=201
    [[ "$GH_FIXTURE" != branch_intermediate_rerun ]] || promoted_id=207
    branch_run() {
      local run_id="$1"
      printf '{"id":%s,"run_attempt":%s,"status":"%s","conclusion":%s,"event":"workflow_run","html_url":"https://example.test/runtime/%s","display_title":"Build Runtime Images trusted-branch branch-develop sha-%s","created_at":"2026-09-21T00:00:%02dZ","repository":{"full_name":"example/firemud"},"head_repository":{"full_name":"example/firemud"}}' \
        "$run_id" "$2" "$3" "$4" "$run_id" "$head_sha" "$((run_id - 200))"
    }
    case "$*" in
      *actions/workflows/runtime-images.yml/runs?*)
        printf '[{"workflow_runs":['
        for ((id = promoted_id; id <= 209; id++)); do
          if (( id != promoted_id )); then printf ','; fi
          branch_run "$id" 1 completed '"success"'
        done
        printf ']}]'
        ;;
      *actions/runs/2[0-9][0-9]/attempts/1/jobs?per_page=100)
        endpoint="${*: -1}"
        id="${endpoint#*actions/runs/}"
        id="${id%%/*}"
        if (( id == promoted_id )); then
          touch "$GH_FIXTURE_STATE.promotion-read"
          conclusion=success
          steps='[{"name":"Promote smoke-tested service digests","status":"completed","conclusion":"success"}]'
        else
          conclusion=skipped
          steps=[]
        fi
        printf '{"total_count":1,"jobs":[{"run_id":%s,"run_attempt":1,"name":"Promote Smoke-Tested Runtime Images","status":"completed","conclusion":"%s","steps":%s}]}' "$id" "$conclusion" "$steps"
        ;;
      *actions/runs/2[0-9][0-9])
        id="${*: -1}"
        id="${id##*/}"
        printf '%s\n' "$id" >>"$GH_FIXTURE_STATE"
        if [[ "$GH_FIXTURE" == branch_intermediate_rerun && "$id" == 208 && -e "$GH_FIXTURE_STATE.promotion-read" ]]; then
          touch "$GH_FIXTURE_STATE.rerun-observed"
          branch_run "$id" 2 in_progress null
        else
          branch_run "$id" 1 completed '"success"'
        fi
        ;;
      *)
        echo "Unexpected branch coverage API request in $GH_FIXTURE fixture" >&2
        exit 2
        ;;
    esac
    ;;
  branch_newer_noop|branch_older_jobs_transient|branch_newer_failed|branch_newer_ambiguous|branch_older_mismatch|branch_older_unproved)
    source="$(printf '{"id":103,"run_attempt":2,"status":"completed","conclusion":"success","event":"workflow_run","html_url":"https://example.test/runtime/103","display_title":"Build Runtime Images trusted-branch branch-develop sha-%s","created_at":"2026-09-21T00:02:00Z","repository":{"full_name":"example/firemud"},"head_repository":{"full_name":"example/firemud"}}' "$head_sha")"
    older_sha="$head_sha"
    [[ "$GH_FIXTURE" != branch_older_mismatch ]] || older_sha="$base_sha"
    older="$(printf '{"id":102,"run_attempt":1,"status":"completed","conclusion":"success","event":"workflow_run","html_url":"https://example.test/runtime/102","display_title":"Build Runtime Images trusted-branch branch-develop sha-%s","created_at":"2026-09-21T00:01:00Z","repository":{"full_name":"example/firemud"},"head_repository":{"full_name":"example/firemud"}}' "$older_sha")"
    case "$*" in
      *actions/runs/103)
        printf '%s' "$source"
        ;;
      *actions/runs/102)
        printf '%s' "$older"
        ;;
      *actions/runs/103/attempts/2/jobs?per_page=100)
        case "$GH_FIXTURE" in
          branch_newer_failed)
            printf '%s' '{"total_count":1,"jobs":[{"run_id":103,"run_attempt":2,"name":"Promote Smoke-Tested Runtime Images","status":"completed","conclusion":"failure","steps":[]}]}'
            ;;
          branch_newer_ambiguous)
            # An absent promotion job is not affirmative skipped-work proof.
            printf '%s' '{"total_count":0,"jobs":[]}'
            ;;
          *)
            printf '%s' '{"total_count":1,"jobs":[{"run_id":103,"run_attempt":2,"name":"Promote Smoke-Tested Runtime Images","status":"completed","conclusion":"skipped","steps":[]}]}'
            ;;
        esac
        ;;
      *actions/runs/102/attempts/1/jobs?per_page=100)
        if [[ "$GH_FIXTURE" == branch_older_jobs_transient && ! -e "$GH_FIXTURE_STATE" ]]; then
          touch "$GH_FIXTURE_STATE"
          exit 1
        fi
        conclusion=success
        [[ "$GH_FIXTURE" != branch_older_unproved ]] || conclusion=failure
        printf '{"total_count":1,"jobs":[{"run_id":102,"run_attempt":1,"name":"Promote Smoke-Tested Runtime Images","status":"completed","conclusion":"success","steps":[{"name":"Promote smoke-tested service digests","status":"completed","conclusion":"%s"}]}]}' "$conclusion"
        ;;
      *actions/workflows/runtime-images.yml/runs?*)
        printf '[{"workflow_runs":[%s,%s]}]' "$source" "$older"
        ;;
      *)
        echo "Unexpected branch fallback API request in $GH_FIXTURE fixture" >&2
        exit 2
        ;;
    esac
    ;;
  success_runs|wrong_runs|publisher_steps_absent|publisher_duplicate_steps|typed_publisher_success|typed_publisher_step_absent|publisher_failed_with_active_alternate|source_failure_with_alternate_rerun|publisher_replacement_handoff*|publisher_producer_rerun_at_acceptance|publisher_stale_receipt*|publisher_exact_retry|publisher_expired_producer|publisher_missing_producer)
    source_title="Build Runtime Images secure-pr-artifact pr-7 base-${base_sha} head-${head_sha} merge-${merge_sha} mode-required"
    source_event=pull_request
    publisher_merge="$merge_sha"
    if [[ "$GH_FIXTURE" == wrong_runs ]]; then
      publisher_merge=dddddddddddddddddddddddddddddddddddddddd
    fi
    publisher_title="Publish PR Runtime Images Build Runtime Images secure-pr-artifact pr-7 base-${base_sha} head-${head_sha} merge-${publisher_merge} mode-required"
    if [[ "$GH_FIXTURE" == typed_publisher_* ]]; then
      source_event=repository_dispatch
      publisher_title+=" source-run-101"
    fi
    source_attempt=1 publisher_id=102 recorded_attempt=1
    [[ "$GH_FIXTURE" != publisher_stale_receipt* ]] || source_attempt=2
    if [[ "$GH_FIXTURE" == publisher_exact_retry ]]; then
      source_attempt=2 publisher_id=103 recorded_attempt=2
    fi
    receipt_zip() {
      python3 - "${1:-$publisher_id}" "$recorded_attempt" "${2:-101}" <<'PY_RECEIPT'
import json
import os
import sys
import zipfile
with zipfile.ZipFile(sys.stdout.buffer, "w") as archive:
    archive.writestr(zipfile.ZipInfo("exact-pr-runtime-publication.json"), json.dumps({
        "schema": 1, "repository": "example/firemud", "prNumber": 7,
        "baseSha": os.environ["base_sha"], "headSha": os.environ["head_sha"],
        "mergeSha": os.environ["merge_sha"], "imageTag": "pr-merge-" + os.environ["merge_sha"],
        "producerWorkflowId": 50, "producerRunId": int(sys.argv[3]), "producerAttempt": int(sys.argv[2]),
        "producerArtifactId": 300 + int(sys.argv[2]), "producerArtifactDigest": "sha256:" + "8"*64,
        "publisherWorkflowRef": "example/firemud/.github/workflows/publish-pr-runtime-images.yml@refs/heads/develop",
        "publisherRunId": int(sys.argv[1]), "publisherAttempt": 2,
        "publishJob": "Publish Fixed PR Merge Image Tags", "publishStep": "Publish fixed PR image tags",
    }))
PY_RECEIPT
    }
    source="$(printf '{"id":101,"run_attempt":%s,"workflow_id":50,"path":".github/workflows/runtime-images.yml","status":"completed","conclusion":"success","event":"%s","head_sha":"%s","html_url":"https://example.test/runtime/101","display_title":"%s","created_at":"2026-09-21T00:00:00Z","repository":{"full_name":"example/firemud"},"head_repository":{"full_name":"example/firemud"}}' "$source_attempt" "$source_event" "$head_sha" "$source_title")"
    publisher="$(printf '{"id":%s,"run_attempt":2,"status":"completed","conclusion":"success","event":"workflow_run","display_title":"%s","created_at":"2026-09-21T00:01:00Z","path":".github/workflows/publish-pr-runtime-images.yml","head_branch":"develop","repository":{"full_name":"example/firemud"},"head_repository":{"full_name":"example/firemud"}}' "$publisher_id" "$publisher_title")"
    if [[ "$GH_FIXTURE" == publisher_failed_with_active_alternate || "$GH_FIXTURE" == publisher_replacement_handoff* ]]; then
      publisher="$(jq -c '.conclusion="failure"' <<<"$publisher")"
    fi
    if [[ "$GH_FIXTURE" == publisher_replacement_handoff* ]]; then
      reads=0
      [[ ! -e "$GH_FIXTURE_STATE" ]] || read -r reads <"$GH_FIXTURE_STATE"
      alt_source="$(jq -c '.id=103 | .event="repository_dispatch" | .created_at="2026-09-21T00:02:00Z"' <<<"$source")"
      alt_publisher="$(jq -c '.id=104 | .display_title += " source-run-103" |
        .created_at="2026-09-21T00:03:00Z" | .conclusion="success"' <<<"$publisher")"
      if (( reads <= 3 )); then
        alt_publisher="$(jq -c '.status="queued" | .conclusion=null' <<<"$alt_publisher")"
      fi
    fi
    case "$*" in
      "api repos/example/firemud")
        if [[ "$GH_FIXTURE" == publisher_replacement_handoff_repo_transient && "$reads" == 2 ]]; then
          echo 'gh: HTTP 503 Service Unavailable' >&2
          exit 1
        fi
        if [[ "$GH_FIXTURE" == publisher_replacement_handoff_repo_malformed ]]; then
          printf '%s' '{"full_name":"example/firemud","default_branch":7}'
          exit
        fi
        printf '%s' '{"full_name":"example/firemud","default_branch":"develop"}'
        ;;
      *actions/workflows/runtime-images.yml)
        if [[ "$GH_FIXTURE" == publisher_replacement_handoff_workflow_transient && "$reads" == 2 ]]; then
          echo 'gh: HTTP 503 Service Unavailable' >&2
          exit 1
        fi
        if [[ "$GH_FIXTURE" == publisher_replacement_handoff_workflow_forbidden ]]; then
          echo 'gh: HTTP 403 Resource not accessible by integration' >&2
          exit 1
        fi
        printf '%s' '{"id":50,"name":"Build Runtime Images","path":".github/workflows/runtime-images.yml"}'
        ;;
      *actions/runs/101/artifacts?*)
        if [[ "$GH_FIXTURE" == publisher_missing_producer || "$GH_FIXTURE" == publisher_stale_receipt_missing_producer ]]; then
          printf '%s' '{"total_count":0,"artifacts":[]}'
          exit
        fi
        expired=false
        [[ "$GH_FIXTURE" != publisher_expired_producer ]] || expired=true
        jq -cn --arg name "pr-runtime-images-pr-merge-${merge_sha}-attempt-${source_attempt}" \
          --argjson attempt "$source_attempt" --argjson expired "$expired" \
          '{total_count:1,artifacts:[{name:$name,id:(300+$attempt),expired:$expired,workflow_run:{id:101},digest:("sha256:"+("8"*64))}]}'
        ;;
      *actions/runs/103/artifacts?*)
        if [[ "$GH_FIXTURE" != publisher_replacement_handoff* ]]; then
          receipt_sha="$(receipt_zip | sha256sum | cut -d' ' -f1)"
          jq -cn --arg name "pr-runtime-publication-${publisher_id}-attempt-2" --arg dig "sha256:$receipt_sha" \
            --argjson run "$publisher_id" '{total_count:1,artifacts:[{name:$name,id:499,expired:false,workflow_run:{id:$run},digest:$dig}]}'
          exit
        fi
        if [[ "$GH_FIXTURE" == publisher_replacement_handoff_artifact_transient && "$reads" == 2 ]]; then
          exit 1
        fi
        jq -cn --arg name "pr-runtime-images-pr-merge-${merge_sha}-attempt-1" \
          '{total_count:1,artifacts:[{name:$name,id:301,expired:false,workflow_run:{id:103},digest:("sha256:"+("8"*64))}]}'
        ;;
      *actions/runs/104/artifacts?*)
        receipt_sha="$(receipt_zip 104 103 | sha256sum | cut -d' ' -f1)"
        jq -cn --arg dig "sha256:$receipt_sha" \
          '{total_count:1,artifacts:[{name:"pr-runtime-publication-104-attempt-2",id:498,expired:false,workflow_run:{id:104},digest:$dig}]}'
        ;;
      *actions/artifacts/498/zip)
        receipt_zip 104 103
        ;;
      *actions/runs/103)
        if [[ "$GH_FIXTURE" == publisher_replacement_handoff_transient && "$reads" == 2 ]]; then
          exit 1
        fi
        if [[ "$GH_FIXTURE" == publisher_replacement_handoff* ]]; then
          printf '%s' "$alt_source"
        else
          printf '%s' "$publisher"
        fi
        ;;
      *actions/runs/104)
        printf '%s' "$alt_publisher"
        ;;
      *actions/runs/104/attempts/2/jobs?per_page=100)
        printf '%s' '{"total_count":1,"jobs":[{"run_id":104,"run_attempt":2,"name":"Publish Fixed PR Merge Image Tags","status":"completed","conclusion":"success","steps":[{"name":"Publish fixed PR image tags","status":"completed","conclusion":"success"}]}]}'
        ;;
      *actions/runs/"$publisher_id"/artifacts?*)
        receipt_sha="$(receipt_zip | sha256sum | cut -d' ' -f1)"
        jq -cn --arg name "pr-runtime-publication-${publisher_id}-attempt-2" --arg dig "sha256:$receipt_sha" \
          --argjson run "$publisher_id" '{total_count:1,artifacts:[{name:$name,id:499,expired:false,workflow_run:{id:$run},digest:$dig}]}'
        ;;
      *actions/artifacts/499/zip)
        receipt_zip
        ;;
      *actions/runs/101)
        if [[ "$GH_FIXTURE" == source_failure_with_alternate_rerun ]]; then
          : >"$GH_FIXTURE_STATE"
          jq -c '.run_attempt=2 | .status="in_progress" | .conclusion=null' <<<"$source"
        elif [[ "$GH_FIXTURE" == publisher_producer_rerun_at_acceptance && -e "$GH_FIXTURE_STATE" ]]; then
          printf '%s\n' 'mock observed producer attempt 2' >&2
          jq -c '.run_attempt=2 | .status="in_progress" | .conclusion=null' <<<"$source"
        else
          printf '%s' "$source"
        fi
        ;;
      *actions/runs/"$publisher_id")
        if [[ "$GH_FIXTURE" == publisher_producer_rerun_at_acceptance ]]; then
          if [[ -e "$GH_FIXTURE_STATE.publisher-read" ]]; then
            touch "$GH_FIXTURE_STATE"
          fi
          touch "$GH_FIXTURE_STATE.publisher-read"
        fi
        printf '%s' "$publisher"
        ;;
      *actions/runs/"$publisher_id"/attempts/2/jobs?per_page=100)
        publication_step='{"name":"Publish fixed PR image tags","status":"completed","conclusion":"success"}'
        unrelated_step='{"name":"Load fixed-tag images","status":"completed","conclusion":"success"}'
        case "$GH_FIXTURE" in
          publisher_steps_absent)
            steps=null
            ;;
          typed_publisher_step_absent)
            steps="[$unrelated_step]"
            ;;
          publisher_duplicate_steps)
            steps="[$publication_step,$publication_step]"
            ;;
          *)
            steps="[$publication_step]"
            ;;
        esac
        jq -cn --argjson run "$publisher_id" --argjson steps "$steps" '
          {run_id:$run,run_attempt:2,name:"Publish Fixed PR Merge Image Tags",status:"completed",conclusion:"success"}
          + (if $steps == null then {} else {steps:$steps} end)
          | {total_count:1,jobs:[.]}
        '
        ;;
      *actions/workflows/publish-pr-runtime-images.yml/runs?*event=workflow_run*)
        if [[ "$GH_FIXTURE" == publisher_replacement_handoff* && "$*" == *created=2026-09-21T00:02:00Z* ]]; then
          printf '{"total_count":1,"workflow_runs":[%s]}' "$alt_publisher"
        elif [[ "$GH_FIXTURE" == publisher_replacement_handoff_missing_direct ]]; then
          printf '%s' '{"total_count":0,"workflow_runs":[]}'
        else
          printf '{"total_count":1,"workflow_runs":[%s]}' "$publisher"
        fi
        ;;
      *actions/workflows/publish-pr-runtime-images.yml/runs?*event=repository_dispatch*)
        printf '%s' '{"total_count":0,"workflow_runs":[]}'
        ;;
      *actions/workflows/runtime-images.yml/runs?*)
        if [[ "$GH_FIXTURE" == source_failure_with_alternate_rerun ]]; then
          failed_source="$(jq -c '.id=105 | .created_at="2026-09-21T00:03:00Z" | .conclusion="failure"' <<<"$source")"
          if [[ -e "$GH_FIXTURE_STATE" ]]; then
            source="$(jq -c '.run_attempt=2 | .status="in_progress" | .conclusion=null' <<<"$source")"
          fi
          printf '[{"workflow_runs":[%s,%s]}]' "$failed_source" "$source"
        elif [[ "$GH_FIXTURE" == publisher_replacement_handoff* ]]; then
          printf '%s\n' "$((reads + 1))" >"$GH_FIXTURE_STATE"
          if [[ "$GH_FIXTURE" == publisher_replacement_handoff_inventory_transient && "$reads" == 1 ]]; then
            echo 'gh: HTTP 503 Service Unavailable' >&2
            exit 1
          fi
          if [[ "$GH_FIXTURE" == publisher_replacement_handoff_newest_failed ]]; then
            failed_source="$(jq -c '.id=105 | .created_at="2026-09-21T00:04:00Z" | .conclusion="failure"' <<<"$source")"
            printf '[{"workflow_runs":[%s,%s]}]' "$failed_source" "$alt_source"
          elif (( reads == 0 )); then
            printf '[{"workflow_runs":[%s]}]' "$source"
          else
            if { [[ "$GH_FIXTURE" == publisher_replacement_handoff ||
                    "$GH_FIXTURE" == publisher_replacement_handoff_missing_direct ]] && (( reads == 1 )); } ||
               [[ "$GH_FIXTURE" == publisher_replacement_handoff_inventory_transient && "$reads" == 2 ]]; then
              alt_source="$(jq -c '.status="in_progress" | .conclusion=null' <<<"$alt_source")"
            fi
            printf '[{"workflow_runs":[%s,%s]}]' "$source" "$alt_source"
          fi
        elif [[ "$GH_FIXTURE" == publisher_failed_with_active_alternate && -e "$GH_FIXTURE_STATE" ]]; then
          alternate="$(jq -c '.id=103 | .status="in_progress" | .conclusion=null | .created_at="2026-09-21T00:02:00Z"' <<<"$source")"
          printf '[{"workflow_runs":[%s,%s]}]' "$source" "$alternate"
        else
          if [[ "$GH_FIXTURE" == publisher_failed_with_active_alternate ]]; then
            : >"$GH_FIXTURE_STATE"
          fi
          printf '[{"workflow_runs":[%s]}]' "$source"
        fi
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
    if [[ "${GH_FIXTURE:-}" == branch_obsolete ]]; then
      echo "obsolete branch waiter must refuse before sleeping" >&2
      exit 99
    fi
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

# A failed publisher is not terminal while a freshly observed exact source is
# still building. This fixture never completes that source; bounded failure is
# expected, but an immediate publisher failure is not.
alternate_status=0
alternate_output="$(
  GH_FIXTURE=publisher_failed_with_active_alternate GH_FIXTURE_STATE="$TEMP_DIR/active-alternate-state" \
    HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 HOSTED_IMAGE_PUBLISHER_WAIT_TIMEOUT_SECONDS=3 \
    HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS=0 \
    timeout 15s bash "$WAITER" "$merge_sha" "$base_sha" "$head_sha" 2>&1
)" || alternate_status=$?
test "$alternate_status" = 1
grep -Fq 'Another exact runtime-images producer is still active' <<<"$alternate_output"
grep -Fq 'Timed out waiting for trusted PR image publisher' <<<"$alternate_output"

# A stale success in the first list can turn into a rerun before its detail read.
# That replacement is still active even while the newer exact producer stays red.
alternate_rerun_status=0
alternate_rerun_output="$(
  GH_FIXTURE=source_failure_with_alternate_rerun GH_FIXTURE_STATE="$TEMP_DIR/source-alternate-rerun" \
    HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=3 HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
    timeout 15s bash "$WAITER" "$merge_sha" "$base_sha" "$head_sha" 2>&1
)" || alternate_rerun_status=$?
test "$alternate_rerun_status" = 1
test -e "$TEMP_DIR/source-alternate-rerun"
grep -Fq 'replacement is active' <<<"$alternate_rerun_output"
grep -Fq 'Timed out waiting for runtime-images workflow' <<<"$alternate_rerun_output"

for handoff_fixture in publisher_replacement_handoff publisher_replacement_handoff_unobserved \
  publisher_replacement_handoff_transient publisher_replacement_handoff_artifact_transient \
  publisher_replacement_handoff_missing_direct publisher_replacement_handoff_newest_failed \
  publisher_replacement_handoff_workflow_transient publisher_replacement_handoff_repo_transient \
  publisher_replacement_handoff_inventory_transient; do
  GH_FIXTURE="$handoff_fixture" GH_FIXTURE_STATE="$TEMP_DIR/$handoff_fixture-state" \
    HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=8 HOSTED_IMAGE_PUBLISHER_WAIT_TIMEOUT_SECONDS=8 \
    HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS=0 \
    timeout 20s bash "$WAITER" "$merge_sha" "$base_sha" "$head_sha" >"$TEMP_DIR/$handoff_fixture.out" 2>&1
  if [[ "$handoff_fixture" != publisher_replacement_handoff_inventory_transient ]]; then
    grep -Fq 'Waiting for publication of an exact replacement producer' "$TEMP_DIR/$handoff_fixture.out"
  fi
  grep -Fq 'Trusted PR image publisher 104 succeeded' "$TEMP_DIR/$handoff_fixture.out"
done
grep -Fq 'Another exact runtime-images producer is still active' "$TEMP_DIR/publisher_replacement_handoff_missing_direct.out"
grep -Fq 'Runtime-image producer coverage unavailable' "$TEMP_DIR/publisher_replacement_handoff_inventory_transient.out"
grep -Fq 'Another exact runtime-images producer is still active' "$TEMP_DIR/publisher_replacement_handoff_inventory_transient.out"

# Forbidden or malformed metadata cannot qualify a replacement handoff.
for handoff_fixture in publisher_replacement_handoff_workflow_forbidden publisher_replacement_handoff_repo_malformed; do
  handoff_status=0
  handoff_output="$(
    GH_FIXTURE="$handoff_fixture" GH_FIXTURE_STATE="$TEMP_DIR/$handoff_fixture-state" \
      HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=8 HOSTED_IMAGE_PUBLISHER_WAIT_TIMEOUT_SECONDS=8 \
      HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS=0 \
      timeout 20s bash "$WAITER" "$merge_sha" "$base_sha" "$head_sha" 2>&1
  )" || handoff_status=$?
  test "$handoff_status" = 1
  grep -Fq 'Trusted PR image publisher 102 completed with failure' <<<"$handoff_output"
  if grep -Eq 'Waiting for publication of an exact replacement producer|publisher 104 succeeded' <<<"$handoff_output"; then
    echo "replacement received eligibility from invalid metadata in $handoff_fixture" >&2
    exit 1
  fi
done

# A typed native callback binds the exact source run in its title, and both
# producer events still require one positive publication step for the attempt.
GH_FIXTURE=typed_publisher_success \
  HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 \
  HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
  HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS=0 \
  timeout 15s bash "$WAITER" "$merge_sha" "$base_sha" "$head_sha" >"$TEMP_DIR/typed-publisher-success.out"
grep -Fq 'Trusted PR image publisher 102 succeeded' "$TEMP_DIR/typed-publisher-success.out"
# New producer attempt cannot borrow the old receipt; its own exact publisher can qualify.
GH_FIXTURE=publisher_exact_retry \
  HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
  HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS=0 \
  timeout 15s bash "$WAITER" "$merge_sha" "$base_sha" "$head_sha" >"$TEMP_DIR/publisher-exact-retry.out"
grep -Fq 'Trusted PR image publisher 103 succeeded' "$TEMP_DIR/publisher-exact-retry.out"

for publisher_fixture in publisher_expired_producer publisher_missing_producer; do
  GH_FIXTURE="$publisher_fixture" HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=3 \
    HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS=0 \
    timeout 15s bash "$WAITER" "$merge_sha" "$base_sha" "$head_sha" >"$TEMP_DIR/$publisher_fixture.out"
  grep -Fq 'Trusted PR image publisher 102 succeeded' "$TEMP_DIR/$publisher_fixture.out"
done

for publisher_fixture in publisher_steps_absent publisher_duplicate_steps typed_publisher_step_absent publisher_stale_receipt publisher_stale_receipt_missing_producer publisher_producer_rerun_at_acceptance; do
  publisher_status=0
  publisher_output="$(
    GH_FIXTURE="$publisher_fixture" GH_FIXTURE_STATE="$TEMP_DIR/$publisher_fixture-state" HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 \
      HOSTED_IMAGE_PUBLISHER_WAIT_TIMEOUT_SECONDS=2 HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
      HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS=0 \
      timeout 15s bash "$WAITER" "$merge_sha" "$base_sha" "$head_sha" 2>&1
  )" || publisher_status=$?
  if [[ "$publisher_status" != 1 ]]; then
    printf 'publisher proof must refuse with status 1 for %s, got %s\n' "$publisher_fixture" "$publisher_status" >&2
    exit 1
  fi
  grep -Fq 'publisher 102 completed successfully, but its exact current-attempt fixed-tag publication proof is unavailable' <<<"$publisher_output"
  if [[ "$publisher_fixture" == publisher_producer_rerun_at_acceptance ]]; then
    grep -Fq 'mock observed producer attempt 2' <<<"$publisher_output"
  fi
done

# A publisher with a mismatched merge token must not satisfy the PR wait.
wrong_merge_status=0
wrong_merge_output="$(
  GH_FIXTURE=wrong_runs \
    HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 \
    HOSTED_IMAGE_PUBLISHER_WAIT_TIMEOUT_SECONDS=1 \
    HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
    HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS=0 \
    timeout 15s bash "$WAITER" "$merge_sha" "$base_sha" "$head_sha" 2>&1
)" || wrong_merge_status=$?
if [[ "$wrong_merge_status" != 1 ]]; then
  printf 'wrong-merge publisher must be refused with exit status 1, got %s\n' "$wrong_merge_status" >&2
  exit 1
fi
grep -Fq 'No trusted PR image publisher appeared' <<<"$wrong_merge_output"

GH_FIXTURE=branch_runs \
  HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 \
  HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
  HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS=0 \
  timeout 15s bash "$WAITER" "$head_sha" >"$TEMP_DIR/branch-success.out"
grep -Fq "Matching runtime-images workflow 103 succeeded for ${head_sha} after" "$TEMP_DIR/branch-success.out"

# A later successful callback with a wholly skipped promotion cannot hide exact
# immutable-commit promotion proof from an earlier callback.
for branch_fixture in branch_newer_noop branch_older_jobs_transient; do
  GH_FIXTURE="$branch_fixture" GH_FIXTURE_STATE="$TEMP_DIR/$branch_fixture-state" \
    HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
    timeout 15s bash "$WAITER" "$head_sha" >"$TEMP_DIR/$branch_fixture.out"
  grep -Fq "Earlier trusted branch runtime-image run 102 has verified promotion for ${head_sha} after a newer skipped promotion." "$TEMP_DIR/$branch_fixture.out"
done

# A promotion beneath eight exact skips can still qualify after develop moves.
# The last native read must be for the selected successful attempt.
branch_state="$TEMP_DIR/branch-ninth-state"
GH_FIXTURE=branch_ninth_promotion GH_FIXTURE_STATE="$branch_state" \
  HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=8 HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
  timeout 15s bash "$WAITER" "$head_sha" >"$TEMP_DIR/branch-ninth.out"
grep -Fq "Earlier trusted branch runtime-image run 201 has verified promotion for ${head_sha}" "$TEMP_DIR/branch-ninth.out"
test "$(tail -n 2 "$branch_state")" = $'202\n201'

# An intermediate skip can be bypassed only while its exact attempt remains
# current, including after the older candidate's successful jobs were read.
branch_state="$TEMP_DIR/branch-intermediate-rerun-state"
branch_rerun_status=0
branch_rerun_output="$(
  GH_FIXTURE=branch_intermediate_rerun GH_FIXTURE_STATE="$branch_state" \
    HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=3 HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
    timeout 15s bash "$WAITER" "$head_sha" 2>&1
)" || branch_rerun_status=$?
test "$branch_rerun_status" = 1
test -e "$branch_state.rerun-observed"
grep -Fq "Timed out waiting for runtime-images workflow for ${head_sha}" <<<"$branch_rerun_output"
if grep -Eq 'has verified promotion|is obsolete:' <<<"$branch_rerun_output"; then
  echo "branch waiter settled while an intermediate promotion attempt had changed" >&2
  exit 1
fi

# No fallback for a failed/unknown newer promotion, a different immutable commit,
# or an older callback lacking the existing positive promotion-step evidence.
for branch_fixture in branch_newer_failed branch_newer_ambiguous branch_older_mismatch branch_older_unproved; do
  branch_fallback_status=0
  branch_fallback_output="$(
    GH_FIXTURE="$branch_fixture" HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
      timeout 15s bash "$WAITER" "$head_sha" 2>&1
  )" || branch_fallback_status=$?
  if [[ "$branch_fallback_status" != 1 ]]; then
    printf 'branch fallback must refuse with status 1 for %s, got %s\n' "$branch_fixture" "$branch_fallback_status" >&2
    exit 1
  fi
  grep -Fq 'Trusted branch runtime-image run 103 has no verified promotion' <<<"$branch_fallback_output"
  grep -Fq "Timed out waiting for runtime-images workflow for ${head_sha} after" <<<"$branch_fallback_output"
done

branch_obsolete_status=0
branch_obsolete_output="$(
  GH_FIXTURE=branch_obsolete HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
    timeout 15s bash "$WAITER" "$head_sha" 2>&1
)" || branch_obsolete_status=$?
if [[ "$branch_obsolete_status" != 1 ]]; then
  printf 'obsolete branch waiter must exit with its own status 1, got %s\n' "$branch_obsolete_status" >&2
  exit 1
fi
grep -Fq 'Trusted branch runtime-image run 103 has no verified promotion' <<<"$branch_obsolete_output"
grep -Fq "Branch runtime-image target develop@${head_sha} is obsolete: current head is ${base_sha}; refusing deployment without verified promotion." <<<"$branch_obsolete_output"
if grep -Fq 'Timed out waiting' <<<"$branch_obsolete_output"; then
  echo "obsolete branch waiter exhausted its deadline instead of refusing immediately" >&2
  exit 1
fi

# Current, unavailable, malformed, or wrong-ref metadata cannot replace promotion
# proof or positively establish that the requested develop target is obsolete.
for branch_fixture in branch_unpromoted_current branch_ref_unavailable branch_ref_malformed branch_ref_wrong_branch; do
  branch_ref_status=0
  branch_ref_output="$(
    GH_FIXTURE="$branch_fixture" HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
      timeout 15s bash "$WAITER" "$head_sha" 2>&1
  )" || branch_ref_status=$?
  if [[ "$branch_ref_status" != 1 ]]; then
    printf 'unpromoted branch waiter must refuse with status 1 for %s, got %s\n' "$branch_fixture" "$branch_ref_status" >&2
    exit 1
  fi
  grep -Fq 'Trusted branch runtime-image run 103 has no verified promotion' <<<"$branch_ref_output"
  grep -Fq "Timed out waiting for runtime-images workflow for ${head_sha} after" <<<"$branch_ref_output"
  if grep -Fq 'is obsolete:' <<<"$branch_ref_output"; then
    printf 'branch waiter inferred obsolescence from %s without a verified different develop head\n' "$branch_fixture" >&2
    exit 1
  fi
done

branch_timeout_status=0
branch_timeout_output="$(
  GH_FIXTURE=branch_wrong \
    HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 \
    HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
    HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS=0 \
    timeout 15s bash "$WAITER" "$head_sha" 2>&1
)" || branch_timeout_status=$?
if [[ "$branch_timeout_status" != 1 ]]; then
  printf 'wrong-branch waiter must refuse with status 1, got %s\n' "$branch_timeout_status" >&2
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

bash "$ROOT_DIR/dev-tools/tests/preview-publication-expired-artifact-contract.sh" "$WAITER"

echo "preview merge image tag contract passed"
