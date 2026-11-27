#!/usr/bin/env bash
# shellcheck disable=SC2016 # This contract intentionally matches literal workflow and source expressions.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

assert_job_condition() {
  local workflow="$1"
  local job="$2"
  local condition="$3"
  local path="$ROOT_DIR/.github/workflows/$workflow"

  if ! awk -v job="$job" -v condition="$condition" '
    $0 == "  " job ":" { in_job = 1; found = 1; next }
    in_job && /^  [A-Za-z0-9_-]+:/ { exit }
    in_job && index($0, condition) { matched = 1 }
    END { exit !(found && matched) }
  ' "$path"; then
    echo "$workflow job $job must be gated by PR base-ref changes" >&2
    exit 1
  fi
}

assert_job_contains() {
  local workflow="$1"
  local job="$2"
  local expected="$3"
  local path="$ROOT_DIR/.github/workflows/$workflow"

  if ! awk -v job="$job" -v expected="$expected" '
    $0 == "  " job ":" { in_job = 1; found = 1; next }
    in_job && /^  [A-Za-z0-9_-]+:/ { exit }
    in_job && index($0, expected) { matched = 1 }
    END { exit !(found && matched) }
  ' "$path"; then
    echo "$workflow job $job must contain: $expected" >&2
    exit 1
  fi
}

assert_job_excludes() {
  local workflow="$1"
  local job="$2"
  local forbidden="$3"
  local path="$ROOT_DIR/.github/workflows/$workflow"

  if ! awk -v job="$job" -v forbidden="$forbidden" '
    $0 == "  " job ":" { in_job = 1; found = 1; next }
    in_job && /^  [A-Za-z0-9_-]+:/ { exit }
    in_job && index($0, forbidden) { forbidden_found = 1 }
    END { exit !(found && !forbidden_found) }
  ' "$path"; then
    echo "$workflow job $job must exist and must not contain: $forbidden" >&2
    exit 1
  fi
}

require_contains() {
  local path="$1"
  local expected="$2"

  if ! grep -Fq -- "$expected" "$path"; then
    echo "$path must contain: $expected" >&2
    exit 1
  fi
}

require_contains_block() {
  local path="$1"
  local expected="$2"

  if ! python3 - "$path" "$expected" <<'PY'
import sys
from pathlib import Path

path = Path(sys.argv[1])
expected = sys.argv[2]
if expected not in path.read_text(encoding="utf-8"):
    raise SystemExit(1)
PY
  then
    echo "$path must contain the complete block:" >&2
    printf '%s\n' "$expected" >&2
    exit 1
  fi
}

require_exact_line() {
  local path="$1"
  local expected="$2"

  if ! grep -Fxq -- "$expected" "$path"; then
    echo "$path must contain the exact line: $expected" >&2
    exit 1
  fi
}

assert_publish_checkout_configuration() {
  local path="$1"

  if ! python3 - "$path" <<'PY'
import sys
from pathlib import Path

import yaml


path = Path(sys.argv[1])
workflow = yaml.safe_load(path.read_text(encoding="utf-8"))
jobs = workflow.get("jobs") if isinstance(workflow, dict) else None
checkouts = []
for job_name, job in (jobs.items() if isinstance(jobs, dict) else []):
    steps = job.get("steps") if isinstance(job, dict) else None
    for step in steps or []:
        if (
            isinstance(step, dict)
            and isinstance(step.get("uses"), str)
            and step["uses"].startswith("actions/checkout@")
        ):
            checkouts.append((job_name, step))
if len(checkouts) != 1:
    raise SystemExit("workflow must contain exactly one actions/checkout step")

checkout_job, checkout = checkouts[0]
if checkout_job != "publish":
    raise SystemExit("the workflow checkout must belong to the publish job")

checkout_with = checkout.get("with")
if not isinstance(checkout_with, dict):
    raise SystemExit("publish checkout must define a with mapping")
if set(checkout_with) != {"ref", "persist-credentials"}:
    raise SystemExit("publish checkout must define exactly ref and persist-credentials")
if checkout_with.get("ref") != "${{ github.event.repository.default_branch }}":
    raise SystemExit("publish checkout must use the repository default branch")
if type(checkout_with.get("persist-credentials")) is not bool or checkout_with["persist-credentials"] is not False:
    raise SystemExit("publish checkout must disable persisted credentials with boolean false")
PY
  then
    echo "$path publish job must have one default-branch, non-persisting checkout" >&2
    exit 1
  fi
}

require_ordered_sequence() {
  local path="$1"
  shift

  if ! awk -v sequence="$(printf '%s\034' "$@")" '
    BEGIN {
      count = split(sequence, expected, "\034") - 1
      current = 1
    }
    {
      remainder = $0
      while (current <= count) {
        matched_at = index(remainder, expected[current])
        if (!matched_at) {
          break
        }
        remainder = substr(remainder, matched_at + length(expected[current]))
        current++
      }
    }
    END { exit !(current > count) }
  ' "$path"; then
    echo "$path must contain the required sequence in order: $*" >&2
    exit 1
  fi
}

require_branch_return() {
  local path="$1"
  local predicate="$2"

  if ! python3 - "$path" "$predicate" <<'PY'
import re
import sys
from pathlib import Path


RETURN_STATEMENT_RE = re.compile(
    r"(?<![A-Za-z0-9_$])return(?![A-Za-z0-9_$])\s*;"
)


def mask_non_code(source: str) -> str:
    # This lightweight scanner does not parse JavaScript regex literals. Braces
    # inside regex literals can therefore affect branch-depth tracking.
    masked = list(source)
    state = "code"
    escaped = False
    block_comment = False
    syntax_depth = 0
    template_resume_depths: list[int] = []
    index = 0

    while index < len(source):
        char = source[index]
        next_char = source[index + 1] if index + 1 < len(source) else ""

        if block_comment:
            if char == "*" and next_char == "/":
                masked[index] = masked[index + 1] = " "
                block_comment = False
                index += 2
                continue
            if char != "\n":
                masked[index] = " "
            index += 1
            continue

        if state in {"single", "double"}:
            if char != "\n":
                masked[index] = " "
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif (state == "single" and char == "'") or (
                state == "double" and char == '"'
            ):
                state = "code"
            index += 1
            continue

        if state == "template":
            if char == "\\":
                masked[index] = " "
                if index + 1 < len(source):
                    if source[index + 1] != "\n":
                        masked[index + 1] = " "
                    index += 2
                    continue
            if char == "`":
                masked[index] = " "
                state = "code"
                index += 1
                continue
            if char == "$" and next_char == "{":
                masked[index] = " "
                template_resume_depths.append(syntax_depth)
                syntax_depth += 1
                index += 2
                state = "code"
                continue
            if char != "\n":
                masked[index] = " "
            index += 1
            continue

        if char == "/" and next_char == "/":
            while index < len(source) and source[index] != "\n":
                masked[index] = " "
                index += 1
            continue
        if char == "/" and next_char == "*":
            masked[index] = masked[index + 1] = " "
            block_comment = True
            index += 2
            continue
        if char == "'":
            masked[index] = " "
            state = "single"
        elif char == '"':
            masked[index] = " "
            state = "double"
        elif char == "`":
            masked[index] = " "
            state = "template"
        elif char == "{":
            syntax_depth += 1
        elif char == "}":
            syntax_depth -= 1
            if template_resume_depths and syntax_depth == template_resume_depths[-1]:
                template_resume_depths.pop()
                state = "template"
        index += 1

    return "".join(masked)


path = Path(sys.argv[1])
predicate = sys.argv[2]
# This helper intentionally anchors to the first predicate occurrence and accepts only a return
# directly inside that branch, not one nested in a child block.
source = path.read_text(encoding="utf-8")
predicate_index = source.find(predicate)
if predicate_index < 0:
    raise SystemExit(1)

masked = mask_non_code(source[predicate_index:])
branch_open = masked.find("{")
if branch_open < 0:
    raise SystemExit(1)

depth = 0
found_return = False
for index in range(branch_open, len(masked)):
    char = masked[index]
    if char == "{":
        depth += 1
    elif char == "}":
        depth -= 1
        if depth == 0:
            raise SystemExit(0 if found_return else 1)
    elif depth == 1 and RETURN_STATEMENT_RE.match(masked, index):
        found_return = True

raise SystemExit(1)
PY
  then
    echo "$path must return within the branch containing: $predicate" >&2
    exit 1
  fi
}

required_condition="github.event.action != 'edited' || github.event.changes.base.ref != null"
ci_path="$ROOT_DIR/.github/workflows/ci.yml"
runtime_images_path="$ROOT_DIR/.github/workflows/runtime-images.yml"
pr_image_publisher_path="$ROOT_DIR/.github/workflows/publish-pr-runtime-images.yml"
smoke_path="$ROOT_DIR/.github/workflows/smoke.yml"
image_wait_path="$ROOT_DIR/dev-tools/hosted/shared/wait-for-runtime-images.sh"
preview_path="$ROOT_DIR/.github/workflows/preview.yml"
trusted_preview_path="$ROOT_DIR/.github/workflows/hosted-identity-request.yml"
preview_reconciler_path="$ROOT_DIR/.github/workflows/preview-reconciler.yml"
preview_comment_publisher_path="$ROOT_DIR/dev-tools/hosted/preview/publish-preview-comment.js"
preview_comment_test_path="$ROOT_DIR/dev-tools/tests/publish-preview-comment.test.cjs"

node --test "$preview_comment_test_path"

security_path="$ROOT_DIR/.github/workflows/security.yml"

for path in "$ci_path" "$security_path" "$smoke_path"; do
  require_contains "$path" 'types: [opened, synchronize, reopened, edited]'
done

# Ordinary required gates must cover stacked feature-base PRs without widening
# trusted push publication or changing substantive/metadata event handling.
python3 - "$ROOT_DIR" <<'PYTHON'
import sys
from pathlib import Path

import yaml

root = Path(sys.argv[1])
for filename in ("ci.yml", "security.yml", "license-scan.yml", "codeql.yml"):
    workflow = yaml.load(
        (root / ".github/workflows" / filename).read_text(encoding="utf-8"),
        Loader=yaml.BaseLoader,
    )
    events = workflow["on"]
    pr = events["pull_request"]
    if set(pr) != {"types"}:
        raise SystemExit(f"{filename} PR triggers must not filter base branches or paths")
    if set(pr["types"]) != {"opened", "synchronize", "reopened", "edited"}:
        raise SystemExit(f"{filename} must cover opens, new commits, reopenings, and base/metadata edits")
    expected_push = ["main"] if filename == "codeql.yml" else ["main", "develop"]
    if events["push"] != {"branches": expected_push}:
        raise SystemExit(f"{filename} must retain its trusted push scope")
    expected_events = {"push", "pull_request", "workflow_dispatch"}
    if filename == "codeql.yml":
        expected_events.add("schedule")
        if events["schedule"] != [{"cron": "0 0 * * 0"}]:
            raise SystemExit("CodeQL must retain its weekly full-analysis schedule")
        if workflow["jobs"]["codeql-gate"]["if"] != "${{ always() && github.event_name == 'pull_request' }}":
            raise SystemExit("CodeQL Gate must cover every PR base and remain PR-only")
    if set(events) != expected_events:
        raise SystemExit(f"{filename} must retain exactly its supported events")
    if filename == "ci.yml":
        dispatch = events["workflow_dispatch"]
        if not isinstance(dispatch, dict) or set(dispatch) != {"inputs"}:
            raise SystemExit("ci.yml manual dispatch must define only its Flyway parent input")
        inputs = dispatch["inputs"]
        if not isinstance(inputs, dict) or set(inputs) != {"flyway_base_sha"}:
            raise SystemExit("ci.yml manual dispatch must require exactly flyway_base_sha")
        base_input = inputs["flyway_base_sha"]
        if (
            not isinstance(base_input, dict)
            or set(base_input) != {"description", "required", "type"}
            or base_input["required"] != "true"
            or base_input["type"] != "string"
            or "full immutable commit sha" not in base_input["description"].lower()
        ):
            raise SystemExit("ci.yml flyway_base_sha must be a required string describing the immutable parent SHA")
    elif events["workflow_dispatch"] not in ("", {}):
        raise SystemExit(f"{filename} must retain an empty manual dispatch")
PYTHON

# Every CI event must fetch its exact immutable Flyway parent with shallow history.
python3 - "$ci_path" <<'PYTHON'
import os
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

workflow = yaml.load(Path(sys.argv[1]).read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
job = workflow["jobs"]["flyway-migration-sanity-checks"]
steps = job["steps"]
fetch_steps = [step for step in steps if step.get("name") == "Fetch exact Flyway parent"]
if len(fetch_steps) != 1:
    raise SystemExit("CI must have exactly one exact Flyway parent fetch step")
fetch = fetch_steps[0]
if "if" in fetch:
    raise SystemExit("Flyway parent fetching must cover every event")
expected_base = (
    "${{ github.event_name == 'workflow_dispatch' && inputs.flyway_base_sha "
    "|| github.event_name == 'pull_request' && github.event.pull_request.base.sha "
    "|| github.event.before }}"
)
if fetch.get("env") != {"FLYWAY_BASE_SHA": expected_base, "FLYWAY_EVENT_NAME": "${{ github.event_name }}"}:
    raise SystemExit("Flyway fetch and checker must use the same immutable event parent")
checkout_steps = [step for step in steps if str(step.get("uses", "")).startswith("actions/checkout@")]
if len(checkout_steps) != 1 or "fetch-depth" in checkout_steps[0].get("with", {}):
    raise SystemExit("Flyway parent fetch must retain the default shallow checkout")
fetch_script = fetch.get("run", "")
for required in (
    '"$FLYWAY_EVENT_NAME" != "workflow_dispatch"',
    '"$FLYWAY_BASE_SHA" == "0000000000000000000000000000000000000000"',
    "^[[:xdigit:]]{40}$",
    'git cat-file -e "${FLYWAY_BASE_SHA}^{commit}"',
    'git fetch --no-tags --depth=1 origin "$FLYWAY_BASE_SHA"',
    'git rev-parse --verify "${FLYWAY_BASE_SHA}^{commit}"',
):
    if required not in fetch_script:
        raise SystemExit(f"Flyway parent fetch must validate and resolve the exact SHA: missing {required}")

check_steps = [step for step in steps if step.get("name") == "🧪 Check Flyway Migration Versions"]
if len(check_steps) != 1:
    raise SystemExit("CI must have exactly one Flyway version check step")
expected_base = (
    "${{ github.event_name == 'workflow_dispatch' && inputs.flyway_base_sha "
    "|| github.event_name == 'pull_request' && github.event.pull_request.base.sha "
    "|| github.event.before }}"
)
if check_steps[0].get("env", {}).get("FIREMUD_FLYWAY_BASE_REF") != expected_base:
    raise SystemExit("Flyway version check must preserve dispatch, PR-base, and push-before parent selection")
# Exercise the actual YAML fetch script against a shallow checkout, rather than
# relying only on static command spelling or the checkout action's defaults.
checker = Path(sys.argv[1]).resolve().parents[2] / "dev-tools/validation/check-flyway-versions.py"
with tempfile.TemporaryDirectory(prefix="firemud-flyway-shallow-") as temporary:
    fixture = Path(temporary) / "source"
    fixture.mkdir()
    def git_at(directory, *arguments):
        return subprocess.run(["git", *arguments], cwd=directory, check=True,
                              capture_output=True, text=True).stdout.strip()
    git_at(fixture, "init", "-q")
    migration = fixture / "services/example-service/src/main/resources/db/migration"
    migration.mkdir(parents=True)
    (fixture / "services/example-service/build.gradle.kts").write_text("dependencies {}\n")
    (migration / "V1__base.sql").write_text("-- base fixture\n")
    git_at(fixture, "add", ".")
    git_at(fixture, "-c", "user.name=Flyway fixture", "-c", "user.email=fixture@example.invalid",
           "commit", "-qm", "base")
    parent = git_at(fixture, "rev-parse", "HEAD")
    (migration / "V2__forward.sql").write_text("-- forward fixture\n")
    git_at(fixture, "add", ".")
    git_at(fixture, "-c", "user.name=Flyway fixture", "-c", "user.email=fixture@example.invalid",
           "commit", "-qm", "forward")
    child = git_at(fixture, "rev-parse", "HEAD")
    checkout = Path(temporary) / "checkout"
    subprocess.run(["git", "clone", "--depth=1", fixture.as_uri(), str(checkout)],
                   check=True, capture_output=True, text=True)
    if git_at(checkout, "rev-parse", "HEAD") != child:
        raise SystemExit("shallow fixture selected the wrong immutable child")
    environment = dict(os.environ)
    for name in ("GITHUB_EVENT_PATH", "GITHUB_EVENT_NAME", "FIREMUD_FLYWAY_BASE_REF"):
        environment.pop(name, None)
    command = [sys.executable, str(checker), "--root", str(checkout), "--base-ref", parent]
    missing = subprocess.run(command, env=environment, capture_output=True, text=True)
    if missing.returncode == 0 or "immutable receiving parent commit is unavailable" not in missing.stderr:
        raise SystemExit(f"shallow checkout did not demonstrate missing parent: {missing.stdout} {missing.stderr}")
    for event_name in ("pull_request", "push", "workflow_dispatch"):
        checkout = Path(temporary) / f"checkout-{event_name}"
        subprocess.run(["git", "clone", "--depth=1", fixture.as_uri(), str(checkout)],
                       check=True, capture_output=True, text=True)
        if git_at(checkout, "rev-parse", "HEAD") != child:
            raise SystemExit(f"shallow fixture selected the wrong immutable child for {event_name}")
        unavailable = subprocess.run(["git", "cat-file", "-e", f"{parent}^{{commit}}"],
                                     cwd=checkout, capture_output=True, text=True)
        if unavailable.returncode == 0:
            raise SystemExit(f"immutable parent was already available before the {event_name} fetch")
        event_command = [sys.executable, str(checker), "--root", str(checkout), "--base-ref", parent]
        environment.update(FLYWAY_BASE_SHA=parent, FLYWAY_EVENT_NAME=event_name)
        fetched = subprocess.run(["bash", "-euo", "pipefail", "-c", fetch_script], cwd=checkout,
                                 env=environment, capture_output=True, text=True)
        if fetched.returncode or git_at(checkout, "rev-parse", parent + "^{commit}") != parent:
            raise SystemExit(f"exact parent fetch failed for {event_name}: {fetched.stderr}")
        available = subprocess.run(event_command, env=environment, capture_output=True, text=True)
        if available.returncode:
            raise SystemExit(f"checker rejected exact fetched parent for {event_name}: {available.stdout} {available.stderr}")
        fetched_command = event_command
    available = subprocess.run(fetched_command, env=environment, capture_output=True, text=True)
    if available.returncode:
        raise SystemExit(f"checker rejected exact fetched parent: {available.stdout} {available.stderr}")
    environment.update(FLYWAY_BASE_SHA="0" * 40, FLYWAY_EVENT_NAME="push")
    sentinel = subprocess.run(["bash", "-euo", "pipefail", "-c", fetch_script], cwd=checkout,
                              env=environment, capture_output=True, text=True)
    if sentinel.returncode:
        raise SystemExit("new-branch sentinel must skip exact fetching")
    environment["FLYWAY_EVENT_NAME"] = "workflow_dispatch"
    sentinel = subprocess.run(["bash", "-euo", "pipefail", "-c", fetch_script], cwd=checkout,
                              env=environment, capture_output=True, text=True)
    if sentinel.returncode == 0:
        raise SystemExit("manual dispatch must reject the zero parent sentinel")
    print("Shallow Flyway parent refusal, exact immutable fetch and event sentinel proof passed")

PYTHON

# Optional native-PR comments cannot delay cancellation or require fork/bot write tokens.
python3 - "$ROOT_DIR" <<'PYTHON'
from pathlib import Path
import sys
import yaml

root = Path(sys.argv[1])
expected = "${{ !cancelled() && github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name == github.repository && github.actor != 'dependabot[bot]' && (github.event.action != 'edited' || github.event.changes.base.ref != null) }}"
for filename, jobs in {
    "security.yml": ("security-summary",),
    "smoke.yml": ("smoke-summary-pending", "smoke-summary"),
}.items():
    workflow = yaml.load((root / ".github/workflows" / filename).read_text(), Loader=yaml.BaseLoader)
    for job in jobs:
        condition = workflow["jobs"][job]["if"]
        if condition != expected:
            raise SystemExit(f"{filename}/{job} must retain the cancellation, trust and substantive-event condition")
        def eligible(cancelled=False, event="pull_request", head_repo="example/repo", actor="contributor", action="synchronize", base_ref=None):
            expression = condition[3:-3].replace("!cancelled()", str(not cancelled))
            values = {
                "github.event.pull_request.head.repo.full_name": head_repo,
                "github.event.changes.base.ref": base_ref,
                "github.event_name": event,
                "github.repository": "example/repo",
                "github.actor": actor,
                "github.event.action": action,
            }
            for key, value in values.items():
                expression = expression.replace(key, repr(value))
            return eval(expression.replace("&&", "and").replace("||", "or").replace("null", "None"), {"__builtins__": {}})
        # Upstream failure is deliberately absent from this status predicate.
        if not eligible() or not eligible(action="edited", base_ref="develop"):
            raise SystemExit(f"{job} must report ordinary results and base retargets")
        for arguments in ({"cancelled": True}, {"head_repo": "fork/repo"}, {"actor": "dependabot[bot]"}, {"action": "edited"}, {"event": "push"}):
            if eligible(**arguments):
                raise SystemExit(f"{job} permits an ineligible comment mutation: {arguments}")
PYTHON

# Metadata-only edits get distinct optional summary contexts; substantive events
# retain each summary's canonical name.
# shellcheck disable=SC2016 # Assert literal GitHub expression syntax.
assert_job_contains security.yml security-summary "name: \${{ github.event.action == 'edited' && github.event.changes.base.ref == null && 'PR Metadata Edit (Security Summary)' || 'Security Summary' }}"
assert_job_contains smoke.yml smoke-summary "name: \${{ github.event.action == 'edited' && github.event.changes.base.ref == null && 'PR Metadata Edit (Smoke Summary)' || 'Smoke Summary' }}"
# A metadata-only edit must retain the required gate name. Its preservation
# action verifies prior proof before reporting success for the unchanged tuple.
assert_job_contains smoke.yml smoke-gate 'name: Smoke Gate'
assert_job_contains smoke.yml smoke-summary-pending 'name: Smoke Summary (Pending)'
assert_job_contains smoke.yml smoke-summary-pending 'tracked-by-smoke-gate'
assert_job_contains smoke.yml smoke-summary 'needs: [changes, smoke-gate, smoke-summary-pending]'
# shellcheck disable=SC2016 # This assertion intentionally matches a literal GitHub expression.
assert_job_contains smoke.yml smoke-summary 'SMOKE_GATE_RESULT: ${{ needs.smoke-gate.result }}'
if grep -Eq '^  smoke-lite:' "$smoke_path"; then
  echo "smoke.yml must not restore the redundant smoke-lite job" >&2
  exit 1
fi

for job in \
  changes \
  buf-check \
  docker-lint \
  shellcheck \
  python-script-validation \
  dev-tool-contract-checks \
  yaml-lint \
  metrics-cardinality-check \
  flyway-migration-sanity-checks \
  grpc-transport-config-sanity-checks \
  workflow-lint \
  helm-lint \
  helm-render-validation \
  docs-check \
  frontend-checks \
  build-and-test \
  build-and-test-account \
  build-and-test-game-session \
  build-and-test-world; do
  assert_job_condition ci.yml "$job" "$required_condition"
done

for job in changes smoke-summary-pending smoke-summary; do
  assert_job_condition smoke.yml "$job" "$required_condition"
done
assert_job_contains smoke.yml smoke-summary-pending 'const smokeGateStatus = fullEnabled ? "pending" : "not-required";'
assert_job_contains smoke.yml smoke-summary-pending 'Full-stack smoke runs in Build Runtime Images and is tracked by Smoke Gate'
assert_job_contains smoke.yml smoke-summary-pending 'firemud-smoke-summary'
assert_job_contains smoke.yml smoke-summary-pending 'comment.user?.login === "github-actions[bot]"'
assert_job_contains smoke.yml smoke-summary-pending 'github.paginate('
assert_job_contains smoke.yml smoke-summary-pending 'comment.created_at || ""'
assert_job_contains smoke.yml smoke-summary-pending 'candidateTimestamp < oldestTimestamp'
assert_job_contains smoke.yml smoke-summary-pending 'const seenCommentIds = new Set();'
assert_job_contains smoke.yml smoke-summary-pending 'String(comment.id) === String(existing?.id)'
assert_job_excludes smoke.yml smoke-summary-pending 'comment.updated_at'
assert_job_contains smoke.yml smoke-summary 'const smokeGateStatus ='
assert_job_contains smoke.yml smoke-summary 'firemud-smoke-summary'
assert_job_contains smoke.yml smoke-summary 'comment.user?.login === "github-actions[bot]"'
assert_job_contains smoke.yml smoke-summary 'github.paginate('
assert_job_contains smoke.yml smoke-summary 'comment.created_at || ""'
assert_job_contains smoke.yml smoke-summary 'candidateTimestamp < oldestTimestamp'
assert_job_contains smoke.yml smoke-summary 'const seenCommentIds = new Set();'
assert_job_contains smoke.yml smoke-summary 'String(comment.id) === String(existing?.id)'
assert_job_excludes smoke.yml smoke-summary 'comment.updated_at'
assert_job_contains smoke.yml smoke-gate 'pull-requests: read'
assert_job_contains smoke.yml smoke-gate 'github.rest.pulls.get'
assert_job_contains smoke.yml smoke-gate 'pullRequest.state !== "open"'
assert_job_contains smoke.yml smoke-gate 'pullRequest.head.sha !== headSha'
assert_job_contains smoke.yml smoke-gate 'pullRequest.base.ref !== baseRef'
assert_job_contains smoke.yml smoke-gate 'github.rest.repos.getCommit'
assert_job_contains smoke.yml smoke-gate 'ref: `heads/${baseRef}`'
assert_job_contains smoke.yml smoke-gate 'const currentMergeSha = currentPullRequest.merge_commit_sha;'
assert_job_contains smoke.yml smoke-gate 'parents.length === 2'
assert_job_contains smoke.yml smoke-gate 'parents[0]?.sha === eventBaseSha'
assert_job_contains smoke.yml smoke-gate 'parents[1]?.sha === headSha'
assert_job_contains smoke.yml smoke-gate 'currentParents.length !== 2'
assert_job_contains smoke.yml smoke-gate 'currentParents[0]?.sha !== currentBaseSha'
assert_job_contains smoke.yml smoke-gate 'currentParents[1]?.sha !== headSha'
assert_job_contains smoke.yml smoke-gate 'sourceEvent: "pull_request"'
assert_job_contains smoke.yml smoke-gate 'run.event !== sourceEvent'
assert_job_contains smoke.yml smoke-gate 'github.rest.git.getRef'
assert_job_contains smoke.yml smoke-gate 'currentIdentity.sourceEvent === "pull_request"'
assert_job_contains smoke.yml smoke-gate 'job.name === "PR Full-Stack Smoke"'
assert_job_contains smoke.yml smoke-gate 'step.name === "Run credential-free full-stack smoke"'
assert_job_contains smoke.yml smoke-gate 'Stopping stale smoke gate before accepting full-stack proof'
assert_job_contains smoke.yml smoke-gate 'continue smokeGatePolling'
assert_job_excludes smoke.yml smoke-gate 'github.rest.repos.createDispatchEvent'

# Every PR base receives the stable Smoke Gate; runtime path filtering is
# retained by the producer rather than filtering the required gate itself.
python3 - "$smoke_path" "$runtime_images_path" <<'PYTHON'
import sys
from pathlib import Path
import yaml

smoke, runtime = [yaml.load(Path(path).read_text(), Loader=yaml.BaseLoader) for path in sys.argv[1:]]
assert set(smoke["on"]["pull_request"]) == {"types"}
assert set(runtime["on"]["pull_request"]) == {"types", "paths"}
assert "branches" not in runtime["on"]["workflow_run"]
PYTHON

# Reproduce the metadata-edit sequence structurally: the edit has its own
# concurrency namespace, all runtime construction jobs remain skipped, and no
# dispatch-capable step exists in Smoke Gate. The original event tuple remains
# valid for merge gating while trusted image publication and preview continue
# to require current-base provenance.
python3 - "$smoke_path" "$runtime_images_path" <<'PY'
from pathlib import Path
import re
import sys

import yaml

smoke_path, runtime_path = map(Path, sys.argv[1:])
smoke = yaml.load(smoke_path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
runtime = yaml.load(runtime_path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)

if smoke["jobs"]["smoke-gate"].get("name") != "Smoke Gate":
    raise SystemExit("metadata-only smoke must retain its required gate context")

runtime_jobs = runtime["jobs"]
metadata_guard = "github.event.action != 'edited' || github.event.changes.base.ref != null"
for job_name in ("image-meta", "pr-local-smoke", "pr-controller-smoke"):
    condition = runtime_jobs[job_name].get("if", "")
    if metadata_guard not in condition:
        raise SystemExit(f"{job_name} can run for a metadata-only edited event")

concurrency_group = runtime["concurrency"]["group"]
if "&& 'metadata' || 'required'" not in concurrency_group:
    raise SystemExit("metadata-only runtime events can cancel substantive runtime builds")

dispatch_queue = runtime_jobs["dispatch-pr-base-refreshes"]["concurrency"]
assert dispatch_queue == {"group": "runtime-pr-refresh-dispatch", "queue": "max"}, "API dispatch callbacks must share the non-evicting queue"
for name, job in runtime_jobs.items():
    if name != "dispatch-pr-base-refreshes":
        assert job.get("concurrency", {}).get("group") != dispatch_queue["group"], "runtime build jobs must not occupy the API dispatcher queue"

dispatch_condition = runtime_jobs["dispatch-pr-base-refreshes"].get("if", "")
if "github.event_name == 'workflow_run'" not in dispatch_condition:
    raise SystemExit("runtime refresh dispatch is not isolated from pull-request metadata events")

# Distinct verified PR tuples must not evict one another; pull_request and
# typed refresh runs for the same tuple must share a group. A dispatch missing
# any tuple field gets a unique run namespace.
pull_request_selector = "format('pr-{0}-base-{1}-head-{2}-merge-{3}', github.event.pull_request.number, github.event.pull_request.base.sha, github.event.pull_request.head.sha, github.sha)"
if pull_request_selector not in concurrency_group:
    raise SystemExit("pull_request runtime concurrency must use its exact PR, base, head, and test-merge identity")
dispatch_fields = ("pr_number", "base_sha", "head_sha", "merge_sha")
for field in dispatch_fields:
    if f"github.event.client_payload.{field} != ''" not in concurrency_group:
        raise SystemExit(f"typed refresh runtime concurrency must require its {field} identity")
dispatch_selector = "format('pr-{0}-base-{1}-head-{2}-merge-{3}', github.event.client_payload.pr_number, github.event.client_payload.base_sha, github.event.client_payload.head_sha, github.event.client_payload.merge_sha)"
if dispatch_selector not in concurrency_group:
    raise SystemExit("typed refresh runtime concurrency must use its full payload tuple")
if "format('repository-dispatch-run-{0}', github.run_id)" not in concurrency_group:
    raise SystemExit("incomplete typed refresh identity must receive a unique run namespace")
if "github.sha" in concurrency_group.split("github.event_name == 'repository_dispatch'", 1)[-1].split("github.event_name == 'workflow_run'", 1)[0]:
    raise SystemExit("repository_dispatch concurrency must not use the default-branch github.sha")
# Execute the workflow-level selector before its dispatcher can classify a CI
# callback. A title/body CI completion must not evict substantive reconciliation.
assert runtime["concurrency"]["cancel-in-progress"] == "true"

def callback_group(branch, source_id, source_attempt, source_event="pull_request", head="a" * 40):
    values = {
        "github.workflow": "Build Runtime Images",
        "github.event_name": "workflow_run",
        "github.event.workflow_run.event": source_event,
        "github.event.workflow_run.id": source_id,
        "github.event.workflow_run.run_attempt": source_attempt,
        "github.event.workflow_run.head_branch": branch,
        "github.event.workflow_run.head_sha": head,
        "github.ref": "refs/heads/develop",
        "github.run_id": 9000 + source_id,
    }

    def evaluate(match):
        expression = re.sub(r"github(?:\.[A-Za-z_][A-Za-z_0-9]*)+",
                            lambda variable: repr(values.get(variable.group(), "")), match.group(1))
        expression = expression.replace("&&", " and ").replace("||", " or ").replace("null", "None")
        return str(eval(expression, {"__builtins__": {}, "format": lambda pattern, *args: pattern.format(*args)}))

    return re.sub(r"\$\{\{(.*?)\}\}", evaluate, concurrency_group)

for branch in ("main", "develop", "feature/parent"):
    substantive = callback_group(branch, 7001, 1)
    metadata = callback_group(branch, 7002, 1)
    subsequent_attempt = callback_group(branch, 7001, 2)
    active = {}
    # With cancel-in-progress enabled, assigning the same group evicts its
    # earlier run. Interleave both callback orders to retain substantive work.
    for callbacks in (((substantive, "substantive"), (metadata, "metadata")),
                      ((metadata, "metadata"), (substantive, "substantive"))):
        active.clear()
        for group, kind in callbacks:
            active[group] = kind
        assert sorted(active.values()) == ["metadata", "substantive"], "metadata CI callback cancelled substantive runtime reconciliation"
    assert subsequent_attempt not in (substantive, metadata), "CI callback concurrency must bind the source attempt"
    assert callback_group(branch, 7001, 1) == substantive, "duplicate completion callbacks retain their exact source-attempt group"
    assert callback_group(branch, 7003, 1, "push") not in (substantive, metadata), "PR callback must not cancel branch publication"

for branch in ("main", "develop"):
    assert callback_group(branch, 7101, 1, "push") == callback_group(branch, 7102, 2, "push", "b" * 40), "protected push publication must retain branch serialization"
assert callback_group("main", 7101, 1, "push") != callback_group("develop", 7102, 1, "push"), "protected publication branches must remain separate"

PY

run_image_meta_exact_parent_fixture() {
  python3 - "$runtime_images_path" <<'PY'
import json
import subprocess
import sys
from pathlib import Path


workflow_path = Path(sys.argv[1])
workflow_source = workflow_path.read_text(encoding="utf-8")
predicate = (
    ".sha == $merge_sha and (.parents | type) == \"array\" and "
    "(.parents | length) == 2 and .parents[0].sha == $base_sha and "
    ".parents[1].sha == $head_sha"
)
if predicate not in workflow_source:
    raise SystemExit("runtime image metadata must retain the exact ordered-parent predicate")


def sha(prefix, fill):
    return prefix + fill * (40 - len(prefix))


stale_event_base = sha("d253", "a")
current_base = sha("7d995", "b")
head = sha("731", "c")
merge = sha("156", "d")
merge_commit = {
    "sha": merge,
    "parents": [{"sha": current_base}, {"sha": head}],
}


def image_meta_output(event_name, base_sha):
    completed = subprocess.run(
        [
            "jq",
            "-e",
            "--arg",
            "base_sha",
            base_sha,
            "--arg",
            "head_sha",
            head,
            "--arg",
            "merge_sha",
            merge,
            predicate,
        ],
        input=json.dumps(merge_commit),
        text=True,
        capture_output=True,
        check=False,
    )
    if completed.returncode != 0:
        return None
    return {
        "event": event_name,
        "base_sha": base_sha,
        "head_sha": head,
        "merge_sha": merge,
    }


if image_meta_output("pull_request", stale_event_base) is not None:
    raise SystemExit("stale ordinary PR tuple must not produce runtime metadata")
refresh_output = image_meta_output("repository_dispatch", current_base)
if refresh_output is None or refresh_output["event"] != "repository_dispatch":
    raise SystemExit("valid typed refresh tuple must produce runtime metadata")
if refresh_output["base_sha"] != current_base or refresh_output["head_sha"] != head or refresh_output["merge_sha"] != merge:
    raise SystemExit("typed refresh metadata did not preserve the exact current tuple")

PY
}

run_image_meta_exact_parent_fixture

# Execute the dispatcher's production GitHub Script body with mocked GitHub APIs.
# This keeps deduplication behavior tests bound to the code deployed by the
# workflow instead of a second implementation of its matching rules.
node - "$runtime_images_path" <<'NODE'
const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const workflowPath = process.argv[2];
const extraction = spawnSync("python3", ["-c", String.raw`
import sys
import yaml
from pathlib import Path

workflow = yaml.load(Path(sys.argv[1]).read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
steps = workflow["jobs"]["dispatch-pr-base-refreshes"]["steps"]
step = next(step for step in steps if step.get("name") == "Dispatch exact PR runtime refreshes and preview reconciles")
sys.stdout.write(step["with"]["script"])
`, workflowPath], { encoding: "utf8" });
assert.equal(extraction.status, 0, extraction.stderr);
const dispatcherScript = extraction.stdout;
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;

const sha = (letter) => letter.repeat(40);
const repository = "owner/repo";
const baseSha = sha("b");
const createdAt = "2026-01-01T00:00:00Z";
const tupleFor = (number, headLetter, mergeLetter, base = baseSha) => ({
  prNumber: String(number),
  baseRef: "develop",
  baseSha: base,
  headSha: sha(headLetter),
  mergeSha: sha(mergeLetter),
});
const titleFor = (tuple) =>
  `Build Runtime Images secure-pr-artifact pr-${tuple.prNumber} base-${tuple.baseSha} head-${tuple.headSha} merge-${tuple.mergeSha} mode-required`;
const producer = (tuple, event, status, conclusion = null, overrides = {}) => ({
  id: Number(tuple.prNumber) * 100 + (event === "pull_request" ? 1 : 2),
  workflow_id: 701,
  run_attempt: 1,
  name: "Build Runtime Images",
  path: ".github/workflows/runtime-images.yml",
  event,
  display_title: titleFor(tuple),
  repository: { full_name: repository },
  head_repository: { full_name: repository },
  head_sha: event === "pull_request" ? tuple.headSha : baseSha,
  head_branch: event === "pull_request" ? "feature/ci" : "develop",
  pull_requests: event === "pull_request"
    ? [{ number: Number(tuple.prNumber), head: { sha: tuple.headSha } }]
    : [],
  status,
  conclusion,
  created_at: "2026-01-01T00:00:00Z",
  ...overrides,
});
const runsDescending = (event, firstCreatedAt) => Array.from({ length: 100 }, (_, index) => ({
  id: 30000 + index,
  event,
  display_title: `historical unrelated run ${index}`,
  path: ".github/workflows/runtime-images.yml",
  name: "Build Runtime Images",
  repository: { full_name: repository },
  head_repository: { full_name: repository },
  created_at: new Date(Date.parse(firstCreatedAt) - index * 1000).toISOString(),
}));
async function runDispatcher({
  tuples, inventories = {}, apiFailure = null, sourceTuples = [], callback = {},
  branchHeads = {}, branchErrors = {}, pullOverrides = {}, mergeParents = {}, mergeReadFailures = {}, ciEvidence = {},
  hideDispatchVisibility = false, filesByPr = {}, postFailurePr = null, previewFailurePr = null,
  visibilityResponse = null, filesFailurePr = null, postFailureVisible = false, postFailureStatus = null,
  fanoutCutoffAfterInventory = false, fanoutCutoffAfterPreviewPr = null, expireAfterFirstApiRequest = false,
  pullGetFailures = {}, lateProducer = null, runtimeWorkflowLookupFailures = [],
  pullListResponse = null, apiRequestMillis = 0, runtimeRunOverrides = {}, runtimeAttemptJobs = {}, runtimeNativeAttempts = {},
  initialTime = "2026-01-08T00:00:00Z", afterPreviewDispatch = null,
}) {
  const dispatches = [];
  const postedRuns = [];
  const pullReads = new Map();
  const mergeReads = new Map();
  const errors = [];
  const infos = [];
  const warnings = [];
  const inventoryCalls = [];
  const observationPostCounts = [];
  const timerPostCounts = [];
  const timerDelays = [];
  const deadlineSignals = [];
  const apiRequestRecords = [];
  const lateProducers = [];
  const runtimeRunReads = [];
  const mockFailures = [];
  let lateProducerAdded = false;
  let runtimeWorkflowLookupCount = 0;
  let waitedMillis = 0;
  let fakeNow = Date.parse(initialTime);
  let expireOnFirstRequest = expireAfterFirstApiRequest;
  const currentBaseSha = tuples[0]?.baseSha ?? baseSha;
  const allTuples = [...tuples, ...sourceTuples];
  const tupleByNumber = new Map(allTuples.map((tuple) => [tuple.prNumber, tuple]));
  const pullRequestFor = (tuple) => ({
    number: Number(tuple.prNumber),
    state: "open",
    base: { ref: tuple.baseRef, repo: { full_name: repository } },
    head: { sha: tuple.headSha, ref: tuple.headRef ?? "feature/ci", repo: { full_name: repository } },
    user: { login: tuple.author ?? "maintainer" },
    labels: Object.hasOwn(tuple, "labels") ? tuple.labels : [],
    mergeable: true,
    mergeable_state: "clean",
    merge_commit_sha: tuple.mergeSha,
    changed_files: 1,
    created_at: createdAt,
  });
  const ciRun = {
    id: 7001, run_attempt: 1, workflow_id: 700,
    name: "CI — Validation", path: ".github/workflows/ci.yml",
    repository: { full_name: repository }, head_repository: { full_name: repository },
    head_branch: "develop", head_sha: currentBaseSha, status: "completed", conclusion: "success", event: "push",
    display_title: `CI — Validation pr-${sourceTuples[0]?.prNumber ?? "101"} base-${sourceTuples[0]?.baseSha ?? baseSha} head-${callback.head_sha ?? currentBaseSha}`,
    ...callback,
  };
  const ciGate = {
    id: 8001, run_id: ciRun.id, run_attempt: ciRun.run_attempt, head_sha: ciRun.head_sha,
    workflow_name: "CI — Validation", name: "Validation Gate", status: "completed", conclusion: "success",
    check_run_url: `https://api.github.com/repos/${repository}/check-runs/8001`,
    steps: [
      { name: "Preserve successful required gate on metadata-only edit", status: "completed", conclusion: "skipped" },
      { name: "Enforce validation success", status: "completed", conclusion: "success" },
    ],
    ...(ciEvidence.gate ?? {}),
  };
  let ciRunReads = 0;
  const ciJobRequests = [];
  const pullListRequests = [];
  const refRequests = [];
  const core = {
    info: (message) => infos.push(message),
    warning: (message) => warnings.push(message),
    error: (message) => errors.push(message),
    setFailed: (message) => errors.push(message),
  };
  const recordApiRequest = (name, options, oneShot = false) => {
    assert(options && typeof options === "object", `${name} must receive request options`);
    assert(options.request && typeof options.request === "object", `${name} must receive a deadline request signal`);
    assert.deepEqual(Object.keys(options.request).sort(), oneShot ? ["retries", "signal"] : ["signal"],
      `${name} must preserve the exact normal/request option shape`);
    if (oneShot) assert.equal(options.request.retries, 0, `${name} must disable transport retries`);
    assert(deadlineSignals.includes(options.request.signal), `${name} must use the actual extracted deadline helper`);
    assert(Number.isSafeInteger(options.request.signal.timeoutMs) && options.request.signal.timeoutMs > 0,
      `${name} must receive a positive remaining wall-clock deadline`);
    apiRequestRecords.push({name, options, issuedAt: fakeNow, abortedAtIssue: options.request.signal.aborted});
    if (expireOnFirstRequest) {
      expireOnFirstRequest = false;
      fakeNow += options.request.signal.timeoutMs;
      assert.equal(options.request.signal.aborted, true, `${name} mock must observe its wall-clock AbortSignal deadline`);
      const error = new Error("mock request aborted at its wall-clock deadline");
      error.name = "TimeoutError";
      throw error;
    }
    fakeNow += apiRequestMillis;
    return options.request.signal;
  };
  const inventoriedRuntimeRuns = () => [
    ...Object.values(inventories).flatMap(inventory => Array.isArray(inventory)
      ? inventory : [...(inventory.initial ?? inventory.runs ?? []), ...(inventory.refined ?? [])]),
    ...lateProducers, ...postedRuns,
  ];
  const github = {
    rest: {
      git: {
        getRef: async (request) => {
          recordApiRequest("git.getRef", request);
          const {ref} = request;
          refRequests.push(ref);
          const branchError = typeof branchErrors[ref] === "function" ? branchErrors[ref](refRequests.length) : branchErrors[ref];
          if (branchError) throw Object.assign(new Error("fixture branch lookup failed"), { status: branchError });
          return { data: { object: {
            sha: branchHeads[ref] ?? allTuples.find((tuple) => `heads/${tuple.baseRef}` === ref)?.baseSha ??
              callback.head_sha ?? currentBaseSha,
          } } };
        },
      },
      pulls: {
        list: async (request) => {
          recordApiRequest("pulls.list", request);
          pullListRequests.push(request);
          const pulls = (request.head ? sourceTuples : tuples)
            .filter((tuple) => request.head || tuple.baseRef === request.base)
            .map(pullRequestFor);
          return { data: pullListResponse ? pullListResponse(request, pulls)
            : pulls.slice((request.page - 1) * 100, request.page * 100) };
        },
        get: async (request) => {
          recordApiRequest("pulls.get", request);
          const {pull_number} = request;
          const number = String(pull_number);
          const reads = (pullReads.get(number) ?? 0) + 1;
          pullReads.set(number, reads);
          const pullGetFailure = typeof pullGetFailures[number] === "function"
            ? pullGetFailures[number](reads)
            : pullGetFailures[number];
          if (pullGetFailure) {
            throw Object.assign(new Error("fixture initial pull request lookup failed"), {status: pullGetFailure});
          }
          const override = pullOverrides[number];
          return { data: {
            ...pullRequestFor(tupleByNumber.get(number)),
            ...(typeof override === "function" ? override(reads) : override ?? {}),
          } };
        },
        listFiles: async (request) => {
          recordApiRequest("pulls.listFiles", request);
          const {pull_number} = request;
          if (String(pull_number) === filesFailurePr) throw new Error("fixture changed-files lookup failed");
          const files = filesByPr[String(pull_number)] ?? [{ filename: "services/example/src/Main.java" }];
          return { data: files.slice((request.page - 1) * 100, request.page * 100) };
        },
      },
      repos: {
        getCommit: async (request) => {
          recordApiRequest("repos.getCommit", request);
          const {ref} = request;
          const reads = (mergeReads.get(ref) ?? 0) + 1;
          mergeReads.set(ref, reads);
          if (mergeReadFailures[ref]?.(reads)) {
            throw Object.assign(new Error("fixture exact merge commit lookup failed"), {status: 502});
          }
          const tuple = allTuples.find((candidate) => candidate.mergeSha === ref);
          return { data: { sha: ref, parents: (mergeParents[ref] ?? [tuple.baseSha, tuple.headSha])
            .map((parent) => ({ sha: parent })) } };
        },
        createDispatchEvent: async (request) => {
          recordApiRequest("repos.createDispatchEvent", request, true);
          dispatches.push(request);
          if (request.event_type === "preview-deploy" && request.client_payload.pr_number === previewFailurePr) {
            throw new Error("ambiguous preview POST timeout");
          }
          const ambiguousRuntimePost = request.event_type === "pr-runtime-base-refresh" &&
            request.client_payload.pr_number === postFailurePr;
          if (request.event_type === "pr-runtime-base-refresh" && !hideDispatchVisibility &&
              (!ambiguousRuntimePost || postFailureVisible)) {
            const payload = request.client_payload;
            postedRuns.push(producer({
              prNumber: payload.pr_number, baseSha: payload.base_sha,
              headSha: payload.head_sha, mergeSha: payload.merge_sha,
            }, "repository_dispatch", "queued", null, {
              id: 900000 + postedRuns.length,
              created_at: new FakeDate(fakeNow + 1000).toISOString(),
            }));
          }
          if (ambiguousRuntimePost) {
            const error = new Error(postFailureStatus ? "fixture HTTP rejection" : "ambiguous POST timeout");
            if (postFailureStatus) Object.assign(error, {status: postFailureStatus, response: {status: postFailureStatus}});
            throw error;
          }
          if (request.event_type === "preview-deploy" && request.client_payload.pr_number === fanoutCutoffAfterPreviewPr) {
            fakeNow += 12 * 60 * 1000;
          }
          if (request.event_type === "preview-deploy" && afterPreviewDispatch) afterPreviewDispatch(request);
        },
      },
      actions: {
        getWorkflow: async (request) => {
          recordApiRequest("actions.getWorkflow", request);
          const {workflow_id} = request;
          if (workflow_id === "runtime-images.yml") {
            runtimeWorkflowLookupCount += 1;
            const failure = runtimeWorkflowLookupFailures[runtimeWorkflowLookupCount - 1];
            if (failure) throw Object.assign(new Error("fixture producer workflow lookup failed"),
              typeof failure === "number" ? {status: failure} : failure);
            return {data: {id: 701, path: ".github/workflows/runtime-images.yml", name: "Build Runtime Images"}};
          }
          return {data: {id: ciRun.workflow_id, path: ".github/workflows/ci.yml", name: "CI — Validation", ...(ciEvidence.workflow ?? {})}};
        },
        getWorkflowRun: async (request) => {
          recordApiRequest("actions.getWorkflowRun", request);
          if (request.run_id !== ciRun.id) {
            runtimeRunReads.push(request.run_id);
            const reads = runtimeRunReads.filter(id => id === request.run_id).length;
            const run = inventoriedRuntimeRuns().find(run => run?.id === request.run_id);
            assert(run, "native runtime lookup must target an inventoried producer");
            const override = runtimeRunOverrides[request.run_id];
            return {data: {...run, ...(typeof override === "function" ? override(reads, run) : override ?? {})}};
          }
          ciRunReads += 1;
          return { data: { ...ciRun, ...(ciEvidence.run ?? {}), ...(ciRunReads > 1 ? ciEvidence.latest ?? {} : {}) } };
        },
        getWorkflowRunAttempt: async (request) => {
          recordApiRequest("actions.getWorkflowRunAttempt", request);
          const {request: requestOptions, ...normal} = request;
          assert.deepEqual(normal, {owner: "owner", repo: "repo", run_id: request.run_id, attempt_number: request.attempt_number});
          const run = inventoriedRuntimeRuns().find(run => run?.id === request.run_id);
          assert(run, "native exact attempt must address an inventoried runtime producer");
          assert.equal(request.attempt_number, run.run_attempt);
          const attempt = Object.hasOwn(runtimeNativeAttempts, run.id) ? runtimeNativeAttempts[run.id] : run;
          if (attempt instanceof Error) throw attempt;
          return {data: attempt};
        },
        listJobsForWorkflowRunAttempt: async (request) => {
          recordApiRequest("actions.listJobsForWorkflowRunAttempt", request);
          if (request.run_id !== ciRun.id) {
            const producer = inventoriedRuntimeRuns().find(run => run?.id === request.run_id);
            assert(producer, "native job listing must address an inventoried producer");
            assert.equal(request.attempt_number, producer.run_attempt, "native jobs must be read from the selected attempt");
            return {data: runtimeAttemptJobs[request.run_id] ?? {total_count: 0, jobs: []}};
          }
          ciJobRequests.push(request);
          const jobs = ciEvidence.jobs ?? [ciGate];
          return {data: ciEvidence.jobPage ? ciEvidence.jobPage(request, jobs) : {
            total_count: jobs.length,
            jobs: jobs.slice((request.page - 1) * 100, request.page * 100),
          }};
        },
        listWorkflowRuns: async (request) => {
          recordApiRequest("actions.listWorkflowRuns", request);
          inventoryCalls.push(request);
          observationPostCounts.push(dispatches.filter(item => item.event_type === "pr-runtime-base-refresh").length);
          if (visibilityResponse && request.created === "2026-01-08T00:00:00.000Z..*") {
            assert.equal(request.event, "repository_dispatch");
            const response = visibilityResponse(request, postedRuns);
            if (response) return {data: response};
          }
          if (apiFailure === request.event || (typeof apiFailure === "function" && apiFailure(request, inventoryCalls.length))) {
            throw new Error("fixture API unavailable");
          }
          const inventory = inventories[request.event] ?? [];
          if (!Array.isArray(inventory) && inventory.malformed) {
            return { data: { malformed: true } };
          }
          const refinement = !Array.isArray(inventory) && Array.isArray(inventory.refined) &&
            request.created === inventory.refinedCreated;
          const suppliedRuns = Array.isArray(inventory)
            ? inventory
            : refinement ? inventory.refined : inventory.initial ?? inventory.runs ?? [];
          const runs = [...suppliedRuns,
            ...lateProducers.filter(run => run.event === request.event),
            ...(request.event === "repository_dispatch" ? postedRuns : [])];
          const createdMatch = /^(.+)\.\.\*$/.exec(request.created ?? "");
          const createdFloor = createdMatch ? Date.parse(createdMatch[1]) : Number.NaN;
          const filteredRuns = Number.isFinite(createdFloor)
            ? runs.filter((run) => {
              const runCreatedAt = run?.created_at;
              const validTimestamp = typeof runCreatedAt === "string"
                && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(runCreatedAt)
                && Number.isFinite(Date.parse(runCreatedAt));
              return !validTimestamp || Date.parse(runCreatedAt) >= createdFloor;
            })
            : runs;
          const filteredTotalCount = Array.isArray(inventory)
            ? filteredRuns.length
            : refinement
              ? inventory.refinedTotalCount ?? filteredRuns.length
              : inventory.initialPageTotals?.[request.page] ?? inventory.initialTotalCount ?? inventory.total_count ?? filteredRuns.length;
          const response = { data: { total_count: filteredTotalCount, workflow_runs: filteredRuns.slice((request.page - 1) * 100, request.page * 100) } };
          const broadRuntimeInventoryCalls = inventoryCalls.filter(call =>
            call.created !== "2026-01-08T00:00:00.000Z..*"
          ).length;
          if (lateProducer && !lateProducerAdded && broadRuntimeInventoryCalls === 2) {
            lateProducerAdded = true;
            lateProducers.push(lateProducer);
          }
          if (fanoutCutoffAfterInventory && request.event === "repository_dispatch") {
            fakeNow += 12 * 60 * 1000;
          }
          return response;
        },
      },
    },
  };
  // Expected fail-closed results must come from production classification, not
  // from request assertions swallowed by its conservative evidence catches.
  for (const namespace of Object.values(github.rest)) {
    for (const [name, method] of Object.entries(namespace)) namespace[name] = async (...args) => {
      try { return await method(...args); }
      catch (error) {
        if (error.code === "ERR_ASSERTION") mockFailures.push(error.message);
        throw error;
      }
    };
  }
  const context = {
    repo: { owner: "owner", repo: "repo" },
    payload: {
      repository: { full_name: repository, default_branch: "develop" },
      workflow_run: ciRun,
    },
  };
  const originalDateNow = Date.now;
  const originalSetTimeout = global.setTimeout;
  const FakeDate = class extends Date {
    constructor(...args) { super(...(args.length ? args : [fakeNow])); }
    static now() { return fakeNow; }
  };
  const DeadlineAbortSignal = {
    timeout(milliseconds) {
      assert(Number.isSafeInteger(milliseconds) && milliseconds > 0, "AbortSignal.timeout must receive positive remaining wall time");
      const signal = {
        timeoutMs: milliseconds,
        deadline: fakeNow + milliseconds,
        get aborted() { return fakeNow >= this.deadline; },
      };
      deadlineSignals.push(signal);
      return signal;
    },
  };
  global.setTimeout = (resolve, millis) => {
    timerPostCounts.push(dispatches.filter(item => item.event_type === "pr-runtime-base-refresh").length);
    timerDelays.push(millis);
    waitedMillis += millis;
    fakeNow += millis;
    resolve();
  };
  Date.now = () => fakeNow;
  let fatalError = null;
  try {
    await new AsyncFunction("github", "context", "core", "Date", "AbortSignal", "setTimeout", dispatcherScript)(
      github, context, core, FakeDate, DeadlineAbortSignal, global.setTimeout,
    );
  } catch (error) {
    if (!expireAfterFirstApiRequest) throw error;
    fatalError = error;
  } finally {
    Date.now = originalDateNow;
    global.setTimeout = originalSetTimeout;
  }
  assert.deepEqual(mockFailures, [], "mock contract assertion failures never count as production refusals");
  return { dispatches, errors, infos, warnings, inventoryCalls, observationPostCounts, timerPostCounts, waitedMillis,
    ciJobRequests, pullListRequests, pullReads, refRequests, deadlineSignals, apiRequestRecords,
    runtimeWorkflowLookupCount, runtimeRunReads, timerDelays, fatalError };
}

(async () => {
  const tuple = tupleFor(101, "a", "c");
  const runtimeDispatches = (result) => result.dispatches.filter((item) => item.event_type === "pr-runtime-base-refresh");
  const previewDispatches = (result) => result.dispatches.filter((item) => item.event_type === "preview-deploy");
  const assertRequestContracts = (result) => {
    assert.equal(result.fatalError, null, "the mocked dispatcher must complete unless a deadline-abort fixture is selected");
    assert.equal(new Set(result.apiRequestRecords.map(({options}) => options.request.signal)).size,
      result.apiRequestRecords.length, "every direct API request must receive a fresh deadline signal");
    for (const {name, options, issuedAt} of result.apiRequestRecords) {
      const request = options.request;
      const oneShot = name === "repos.createDispatchEvent";
      assert.deepEqual(Object.keys(request).sort(), oneShot ? ["retries", "signal"] : ["signal"],
        `${name} must retain its exact request-options shape`);
      assert(oneShot ? request.retries === 0 : !Object.hasOwn(request, "retries"),
        `${name} must be one-shot only for dispatch POSTs`);
      assert(result.deadlineSignals.includes(request.signal), `${name} must use a source-derived AbortSignal timeout`);
      assert(Number.isSafeInteger(request.signal.timeoutMs) && request.signal.timeoutMs > 0,
        `${name} must receive a positive remaining wall-clock deadline`);
      assert.equal(request.signal.timeoutMs, request.signal.deadline - issuedAt,
        `${name} must refresh its timeout at the actual page request boundary`);
      assert.equal(result.apiRequestRecords.find((call) => call.name === name && call.options === options).abortedAtIssue,
        false, `${name} must not issue a request with an already-expired signal`);
    }
  };
  const source = { ...tuple, headRef: "feature/parent" };
  const child = { ...tupleFor(102, "d", "e", source.headSha), baseRef: source.headRef };
  const prCallback = { event: "pull_request", head_branch: source.headRef, head_sha: source.headSha };
  const reconcile = await runDispatcher({ tuples: [child], sourceTuples: [source], callback: prCallback });
  assert.deepEqual(runtimeDispatches(reconcile).map((item) => item.client_payload.pr_number).sort(),
    [source.prNumber, child.prNumber].sort(), "parent PR CI completion must refresh its own exact tuple and dependent feature-base child");
  assert.equal(previewDispatches(reconcile).length, 1, "ordinary feature child has runtime proof without preview eligibility");
  for (const candidate of [source, child]) {
    const payload = runtimeDispatches(reconcile).find((item) => item.client_payload.pr_number === candidate.prNumber).client_payload;
    assert.deepEqual(payload, {
      pr_number: candidate.prNumber, base_ref: candidate.baseRef, base_sha: candidate.baseSha,
      head_sha: candidate.headSha, merge_sha: candidate.mergeSha,
    }, "CI callback may dispatch only the independently verified exact source or dependent tuple");
  }
  assert.equal(reconcile.ciJobRequests.length, 1, "PR callbacks require one exact current native CI attempt jobs request");
  const {request: ciJobRequestOptions, ...ciJobRequest} = reconcile.ciJobRequests[0];
  assert.deepEqual(ciJobRequest, { owner: "owner", repo: "repo", run_id: 7001, attempt_number: 1, per_page: 100, page: 1 },
    "PR callbacks require the exact current native CI attempt jobs request shape");
  assert.deepEqual(Object.keys(ciJobRequestOptions).sort(), ["signal"], "CI job listing carries only its AbortSignal request option");
  assert.equal(reconcile.deadlineSignals[0].timeoutMs, 12 * 60 * 1000, "the first fan-out API call receives the full wall-clock budget");
  assertRequestContracts(reconcile);
  const ciPath = ".github/workflows/ci.yml";
  for (const [callbackPath, apiPath] of [[ciPath, `${ciPath}@refs/pull/101/merge`],
    [`${ciPath}@refs/pull/101/merge`, ciPath]]) {
    const equivalent = await runDispatcher({tuples: [child], sourceTuples: [source],
      callback: {...prCallback, path: callbackPath}, ciEvidence: {run: {path: apiPath}}});
    assert.deepEqual(runtimeDispatches(equivalent).map(item => item.client_payload.pr_number).sort(),
      [source.prNumber, child.prNumber].sort(), "native CI callback/API paths may differ only by this exact PR's merge ref");
    assert.deepEqual(equivalent.errors, []);
  }
  for (const suffix of ["refs/pull/102/merge", "refs/pull/0101/merge", "refs/pull/101/head", "refs/heads/develop"]) {
    const drift = await runDispatcher({tuples: [child], sourceTuples: [source], callback: prCallback,
      ciEvidence: {run: {path: `${ciPath}@${suffix}`}}});
    assert.deepEqual(drift.dispatches, [], "another PR/ref cannot use native path equivalence");
    assert(drift.warnings.some(message => message.includes("identity differs from callback")));
  }
  const pagedFiles = [...Array.from({length: 100}, (_, index) => ({filename: `docs/page-${index}.md`})),
    {filename: "services/example/src/Main.java"}];
  const pagedCiJobs = (request, jobs) => {
    const completeJobs = [...Array.from({length: 100}, (_, index) => ({id: 8100 + index, name: "Other job"})), ...jobs];
    return {total_count: completeJobs.length, jobs: completeJobs.slice((request.page - 1) * 100, request.page * 100)};
  };
  const pagedFanout = await runDispatcher({
    tuples: [child], sourceTuples: [source], callback: prCallback, apiRequestMillis: 25,
    pullListResponse: (request, pulls) => [
      ...Array.from({length: 100}, (_, index) => ({number: 2000 + index, state: "closed"})), ...pulls,
    ].slice((request.page - 1) * 100, request.page * 100),
    filesByPr: {[source.prNumber]: pagedFiles},
    pullOverrides: {[source.prNumber]: {changed_files: pagedFiles.length}},
    ciEvidence: {jobPage: pagedCiJobs},
  });
  assert.deepEqual(runtimeDispatches(pagedFanout).map(item => item.client_payload.pr_number).sort(),
    [source.prNumber, child.prNumber].sort(), "page-two source, dependent, CI gate and runtime file evidence must all be retained");
  assert.deepEqual(pagedFanout.errors, []);
  const assertFanoutPages = (requests, expectedScope) => {
    assert.deepEqual(requests.map(({request, ...scope}) => scope), [1, 2].map(page => ({
      owner: "owner", repo: "repo", ...expectedScope, per_page: 100, page,
    })), "direct page requests must preserve their exact API scope");
    assert(requests[1].request.signal.timeoutMs < requests[0].request.signal.timeoutMs,
      "later pages must receive only the remaining fan-out budget, not a reused timeout");
    assert.equal(requests[0].request.signal.deadline, requests[1].request.signal.deadline,
      "pagination cannot extend the shared fan-out deadline");
  };
  assertFanoutPages(pagedFanout.ciJobRequests, {run_id: 7001, attempt_number: 1});
  assertFanoutPages(pagedFanout.pullListRequests.filter(request => request.base), {state: "open", base: source.headRef});
  assertFanoutPages(pagedFanout.pullListRequests.filter(request => request.head), {state: "open", head: `owner:${source.headRef}`});
  assertFanoutPages(pagedFanout.apiRequestRecords.filter(({name, options}) =>
    name === "pulls.listFiles" && String(options.pull_number) === source.prNumber).map(({options}) => options),
  {pull_number: Number(source.prNumber)});
  assertRequestContracts(pagedFanout);
  // Model both windows independently of AbortSignal.timeout and of one another.
  const fanoutStart = Date.parse("2026-01-08T00:00:00Z");
  const lastPost = pagedFanout.apiRequestRecords.findLastIndex(({name}) => name === "repos.createDispatchEvent");
  assert(lastPost >= 0 && lastPost < pagedFanout.apiRequestRecords.length - 1,
    "exercise both fan-out and post-dispatch visibility requests");
  const visibilityStart = pagedFanout.apiRequestRecords[lastPost].issuedAt + 25;
  for (const [index, {name, options, issuedAt}] of pagedFanout.apiRequestRecords.entries()) {
    const phaseDeadline = index <= lastPost ? fanoutStart + 12 * 60 * 1000 : visibilityStart + 120000;
    assert.equal(options.request.signal.timeoutMs, phaseDeadline - issuedAt,
      `${name} must use the remaining budget of its independently modeled phase`);
  }
  const samePageBoundary = await runDispatcher({
    tuples: [tuple, tupleFor(102, "d", "e")],
    pullListResponse: (request, pulls) => request.page === 1
      ? [...Array.from({length: 99}, () => ({state: "closed"})), pulls[0]]
      : pulls,
  });
  assert.deepEqual(runtimeDispatches(samePageBoundary).map(item => item.client_payload.pr_number).sort(),
    ["101", "102"], "a duplicated page boundary must not dispatch one exact tuple twice");
  assert.deepEqual(samePageBoundary.errors, []);
  for (const jobPage of [
    () => ({total_count: 1, jobs: []}),
    () => ({total_count: 1, jobs: {}}),
    (request, jobs) => request.page === 1 ? pagedCiJobs(request, jobs)
      : {total_count: 102, jobs: [jobs[0], {id: 9999, name: "Other job"}]},
  ]) {
    await assert.rejects(runDispatcher({tuples: [child], sourceTuples: [source], callback: prCallback,
      ciEvidence: {jobPage}}), /Exact source CI attempt job inventory is incomplete or inconsistent/,
    "short, malformed or shifted exact-attempt job pages cannot authorize dispatch");
  }
  const cutoffJobPages = [];
  await assert.rejects(runDispatcher({
    tuples: [child], sourceTuples: [source], callback: prCallback, apiRequestMillis: 4 * 60 * 1000,
    ciEvidence: {jobPage: (request, jobs) => {
      cutoffJobPages.push(request.page);
      return pagedCiJobs(request, jobs);
    }},
  }), /Runtime fan-out deadline reached; request not issued/);
  assert.deepEqual(cutoffJobPages, [1], "a completed first page at the cutoff must not issue page two or reach a handoff");
  for (const branch of ["main", "develop", "feature/parent"]) {
    const branchSource = { ...source, headRef: branch, baseRef: branch === "develop" ? "main" : "develop" };
    const dependent = { ...child, baseRef: branch };
    const branchPr = await runDispatcher({
      tuples: [dependent], sourceTuples: [branchSource],
      callback: { ...prCallback, head_branch: branch },
    });
    const protectedHead = ["main", "develop"].includes(branch);
    assert.deepEqual(runtimeDispatches(branchPr).map((item) => item.client_payload.pr_number).sort(),
      (protectedHead ? [branchSource.prNumber] : [branchSource.prNumber, dependent.prNumber]).sort(),
      `${branch} PR callback must preserve source reconciliation and delegate protected dependent fan-out to push`);
    assert.equal(branchPr.pullListRequests.filter((request) => request.base === branch).length, protectedHead ? 0 : 1,
      `${branch} PR callback must skip dependent enumeration only for protected heads`);
    assert.equal(branchPr.pullListRequests.filter((request) => request.head === `owner:${branch}`).length, 1,
      `${branch} PR callback must retain exact source discovery`);
    if (protectedHead) {
      const branchPush = await runDispatcher({
        tuples: [dependent], sourceTuples: [branchSource],
        callback: { event: "push", head_branch: branch, head_sha: branchSource.headSha },
      });
      assert.deepEqual(runtimeDispatches(branchPush).map((item) => item.client_payload.pr_number), [dependent.prNumber],
        `${branch} push callback exclusively owns dependent fan-out`);
      assert.equal(branchPush.pullListRequests.filter((request) => request.base === branch).length, 1);
      assert.equal(branchPush.pullListRequests.filter((request) => request.head).length, 0,
        "protected push callback must not duplicate PR source reconciliation");
    }
  }
  for (const baseRef of ["feature/topic+fix", "feature/@/parent", "feature/世界", "feature/#fragment", "feature/%encoded", "feature/$(literal)"]) {
    const refSource = { ...source, baseRef, headRef: `${baseRef}/child` };
    const refChild = { ...child, baseRef: refSource.headRef };
    const refCallback = { ...prCallback, head_branch: refSource.headRef };
    const args = { tuples: [refChild], sourceTuples: [refSource], callback: refCallback };
    const accepted = await runDispatcher(args);
    assert.deepEqual(runtimeDispatches(accepted).map((item) => item.client_payload.pr_number).sort(),
      [refSource.prNumber, refChild.prNumber].sort(), `${baseRef} must support source and child refresh`);
    assert(accepted.refRequests.includes(`heads/${refSource.headRef}`));
    assert(accepted.refRequests.includes(`heads/${baseRef}`), "source base must use authenticated exact ref proof");
    assert.equal(runtimeDispatches(accepted).find((item) => item.client_payload.pr_number === refSource.prNumber)
      .client_payload.base_ref, baseRef, "valid source base ref must reach typed dispatch unchanged");
    const stale = await runDispatcher({ ...args, branchHeads: { [`heads/${refSource.headRef}`]: sha("f") } });
    assert.equal(stale.dispatches.length, 0, "valid ref syntax cannot bypass current callback branch SHA");
    const missing = await runDispatcher({ ...args, branchErrors: { [`heads/${refSource.headRef}`]: 404 } });
    assert.equal(missing.dispatches.length, 0, "missing callback branch cannot supply refresh proof");
    const staleBase = await runDispatcher({ ...args, branchHeads: { [`heads/${baseRef}`]: sha("f") } });
    assert.deepEqual(runtimeDispatches(staleBase).map((item) => item.client_payload.pr_number), [refChild.prNumber],
      "valid source base ref still requires immutable merge parents matching its current SHA");
    await assert.rejects(runDispatcher({ ...args, branchErrors: { [`heads/${baseRef}`]: 404 } }),
      /fixture branch lookup failed/, "missing source base must fail closed before dispatch");
  }
  const emptyBranch = await runDispatcher({ tuples: [child], callback: { ...prCallback, head_branch: "" } });
  assert.equal(emptyBranch.dispatches.length, 0);
  assert.equal(emptyBranch.refRequests.length, 0, "empty callback branch must be rejected before ref lookup");
  const preservationStep = { name: "Preserve successful required gate on metadata-only edit", status: "completed", conclusion: "skipped" };
  const enforcementStep = { name: "Enforce validation success", status: "completed", conclusion: "success" };
  for (const [label, ciEvidence] of [
    ["metadata preservation executed", { gate: { steps: [{ ...preservationStep, conclusion: "success" }, { ...enforcementStep, conclusion: "skipped" }] } }],
    ["failed metadata preservation", { gate: { steps: [{ ...preservationStep, conclusion: "failure" }, { ...enforcementStep, conclusion: "skipped" }] } }],
    ["missing preservation", { gate: { steps: [enforcementStep] } }],
    ["ambiguous preservation", { gate: { steps: [preservationStep, preservationStep, enforcementStep] } }],
    ["skipped enforcement", { gate: { steps: [preservationStep, { ...enforcementStep, conclusion: "skipped" }] } }],
    ["old native attempt job", { gate: { run_attempt: 2 } }],
    ["wrong native job run", { gate: { run_id: 7002 } }],
    ["wrong native job head", { gate: { head_sha: sha("f") } }],
    ["wrong native job workflow", { gate: { workflow_name: "Other Workflow" } }],
    ["wrong native check repository", { gate: { check_run_url: "https://api.github.com/repos/outsider/repo/check-runs/8001" } }],
    ["missing native gate", { jobs: [] }],
    ["superseded initial attempt", { run: { run_attempt: 2 } }],
    ["attempt superseded after jobs", { latest: { run_attempt: 2 } }],
    ["wrong source repository", { run: { repository: { full_name: "outsider/repo" } } }],
    ["wrong source workflow path", { run: { path: ".github/workflows/other.yml" } }],
    ["wrong source workflow ID", { workflow: { id: 999 } }],
    ["wrong source head", { run: { head_sha: sha("f") } }],
    ["wrong source title", { run: { display_title: "unattributed" } }],
  ]) {
    const excluded = await runDispatcher({ tuples: [child], sourceTuples: [source], callback: prCallback, ciEvidence });
    assert.equal(excluded.dispatches.length, 0, `${label} must exclude source and child runtime/preview reconciliation`);
  }
  const failedSubstantive = await runDispatcher({
    tuples: [child], sourceTuples: [source], callback: { ...prCallback, conclusion: "failure" },
    ciEvidence: { gate: { conclusion: "failure", steps: [preservationStep, { ...enforcementStep, conclusion: "failure" }] } },
  });
  assert.equal(runtimeDispatches(failedSubstantive).length, 2,
    "failed substantive CI remains a reconcile signal requiring each source/child's independent runtime smoke");
  const staleCallback = await runDispatcher({
    tuples: [child], sourceTuples: [source], callback: prCallback,
    branchHeads: { [`heads/${source.headRef}`]: sha("f") },
  });
  assert.equal(staleCallback.dispatches.length, 0, "superseded parent CI callback must not reconcile any tuple");
  const deletedCallbackBranch = await runDispatcher({
    tuples: [child], sourceTuples: [source], callback: prCallback,
    branchErrors: { [`heads/${source.headRef}`]: 404 },
  });
  assert.equal(deletedCallbackBranch.dispatches.length, 0, "deleted callback branch is an obsolete signal without refresh dispatch");
  await assert.rejects(runDispatcher({
    tuples: [child], sourceTuples: [source], callback: prCallback,
    branchErrors: { [`heads/${source.headRef}`]: 503 },
  }), /fixture branch lookup failed/, "other branch API failures must remain visible errors");
  const retargeted = await runDispatcher({
    tuples: [child], sourceTuples: [source], callback: prCallback,
    pullOverrides: { [source.prNumber]: { base: { ref: "feature/retargeted", repo: { full_name: repository } } } },
  });
  assert.deepEqual(runtimeDispatches(retargeted).map((item) => item.client_payload.pr_number), [child.prNumber],
    "source retarget during resolution must not dispatch an old-base source tuple");
  const advancedSource = await runDispatcher({
    tuples: [child], sourceTuples: [source], callback: prCallback,
    pullOverrides: { [source.prNumber]: { head: { sha: sha("f"), ref: source.headRef, repo: { full_name: repository } } } },
  });
  assert.deepEqual(runtimeDispatches(advancedSource).map((item) => item.client_payload.pr_number), [child.prNumber],
    "source completion must never assume its head remains current");
  const wrongSourceParents = await runDispatcher({
    tuples: [child], sourceTuples: [source], callback: prCallback,
    mergeParents: { [source.mergeSha]: [sha("f"), source.headSha] },
  });
  assert.deepEqual(runtimeDispatches(wrongSourceParents).map((item) => item.client_payload.pr_number), [child.prNumber],
    "a current-looking source merge SHA needs immutable exact ordered parents");
  for (const [labels, author, expectedPreviews] of [
    [[], "maintainer", 0],
    [[{ name: "preview:priority" }], "maintainer", 1],
    [[{ name: "preview:priority" }], "renovate[bot]", 0],
    [null, "maintainer", 0],
  ]) {
    const feature = { ...child, labels, author };
    // Use the dependent path alone to isolate preview admission from source reconciliation.
    const featureResult = await runDispatcher({ tuples: [feature], callback: prCallback });
    assert.equal(runtimeDispatches(featureResult).length, 1, "feature and dependency-bot artifact refresh is independent of preview eligibility");
    assert.equal(previewDispatches(featureResult).length, expectedPreviews, "preview reconciliation retains canonical priority/bot/label policy");
  }
  for (const [event, status] of [
    ["pull_request", "queued"],
    ["pull_request", "in_progress"],
    ["repository_dispatch", "queued"],
    ["repository_dispatch", "in_progress"],
  ]) {
    const result = await runDispatcher({
      tuples: [tuple],
      inventories: { [event]: [producer(tuple, event, status)] },
    });
    assert.equal(result.dispatches.filter((item) => item.event_type === "pr-runtime-base-refresh").length, 0,
      `${event} ${status} exact producer must suppress duplicate runtime dispatch`);
    assert.equal(result.dispatches.filter((item) => item.event_type === "preview-deploy").length, 1,
      "runtime deduplication must retain preview reconciliation");
    assert.equal(result.inventoryCalls.length, 2, "complete initial inventories require one query per event kind");
    assert(result.inventoryCalls.every((request) =>
      request.workflow_id === "runtime-images.yml" &&
      request.per_page === 100 &&
      request.page === 1 &&
      typeof request.created === "string" &&
      !Number.isNaN(Date.parse(request.created.replace(/\.\.\*$/, "")) )
    ), "each target inventory must query only one bounded recent page per event when complete");
  }

  for (const event of ["pull_request", "repository_dispatch"]) {
    const completedSuccess = await runDispatcher({
      tuples: [tuple],
      callback: {updated_at: "2026-01-02T00:00:00Z"},
      inventories: { [event]: [producer(tuple, event, "completed", "success")] },
    });
    assert.equal(completedSuccess.dispatches.filter((item) => item.event_type === "pr-runtime-base-refresh").length, 1,
      `${event} completed source success must preserve recovery dispatch for missing or expired publication`);
  }

  for (const validRef of [
    "main",
    "refs/heads/feature]/test",
    "refs/heads/feature/@/test",
    "refs/heads/feature./test",
  ]) {
    const refSuffixedPath = await runDispatcher({
      tuples: [tuple],
      inventories: { pull_request: [producer(tuple, "pull_request", "in_progress", null, {
        path: `.github/workflows/runtime-images.yml@${validRef}`,
      })] },
    });
    assert.equal(refSuffixedPath.dispatches.filter((item) => item.event_type === "pr-runtime-base-refresh").length, 0,
      `the exact owning workflow path with valid Git ref ${validRef} must remain eligible`);
  }
  for (const invalidRef of ["@", "refs/heads/trailing.", "refs/heads/feature/.hidden", "refs/heads/feature[/test"]) {
    const invalidRefPath = await runDispatcher({
      tuples: [tuple],
      inventories: { pull_request: [producer(tuple, "pull_request", "in_progress", null, {
        path: `.github/workflows/runtime-images.yml@${invalidRef}`,
      })] },
    });
    assert.equal(invalidRefPath.dispatches.filter((item) => item.event_type === "pr-runtime-base-refresh").length, 0,
      `an invalid Git ref ${invalidRef} must not authorize another dispatch`);
    assert(invalidRefPath.errors.some(message => message.includes("no dispatch issued")),
      "invalid ref must be explicit rejection, not successful reuse");
  }
  const wrongWorkflowPath = await runDispatcher({
    tuples: [tuple],
    inventories: { pull_request: [producer(tuple, "pull_request", "in_progress", null, {
      path: ".github/workflows/other.yml@refs/heads/develop",
    })] },
  });
  assert.equal(wrongWorkflowPath.dispatches.filter((item) => item.event_type === "pr-runtime-base-refresh").length, 0,
    "an unrelated workflow path must fail closed");
  assert(wrongWorkflowPath.errors.some((message) => message.includes("no dispatch issued")),
    "an exact-tuple producer with an unrelated workflow path must emit an error");

  for (const [status, conclusion] of [
    ["completed", "failure"],
    ["completed", "cancelled"],
  ]) {
    const result = await runDispatcher({
      tuples: [tuple],
      inventories: { pull_request: [producer(tuple, "pull_request", status, conclusion)] },
    });
    assert.equal(result.dispatches.filter((item) => item.event_type === "pr-runtime-base-refresh").length, 1,
      `${conclusion} producer must allow an exact-tuple retry`);
  }

  const orderedProducerInventory = async (older, newer, newerConclusion = null) => runDispatcher({
    tuples: [tuple],
    inventories: { pull_request: [
      producer(tuple, "pull_request", older[0], older[1], {
        id: 10101,
        created_at: "2026-01-01T00:00:00Z",
      }),
      producer(tuple, "pull_request", newer[0], newerConclusion, {
        id: 10102,
        created_at: "2026-01-02T00:00:00Z",
      }),
    ] },
  });
  for (const conclusion of ["failure", "cancelled"]) {
    const newestFailure = await orderedProducerInventory(
      ["completed", "success"], ["completed", conclusion], conclusion
    );
    assert.equal(newestFailure.dispatches.filter((item) => item.event_type === "pr-runtime-base-refresh").length, 1,
      `newer ${conclusion} producer must allow refresh despite an older success`);
  }
  for (const [olderStatus, olderConclusion, newestStatus, newestConclusion, suppress] of [
    ["completed", "failure", "in_progress", null, true],
    ["completed", "success", "in_progress", null, true],
    ["completed", "failure", "completed", "success", false],
  ]) {
    const newestUsable = await orderedProducerInventory(
      [olderStatus, olderConclusion], [newestStatus, newestConclusion], newestConclusion
    );
    assert.equal(newestUsable.dispatches.filter((item) => item.event_type === "pr-runtime-base-refresh").length,
      suppress ? 0 : 1,
      `newer ${newestStatus}/${newestConclusion} producer must determine whether runtime recovery dispatches`);
  }
  const ambiguousNewest = await runDispatcher({
    tuples: [tuple],
    inventories: { pull_request: [
      producer(tuple, "pull_request", "completed", "success", { id: 10103 }),
      producer(tuple, "pull_request", "completed", "failure", { id: 10104 }),
    ] },
  });
  assert.equal(ambiguousNewest.dispatches.filter((item) => item.event_type === "pr-runtime-base-refresh").length, 0,
    "ambiguous equal-time exact producers must fail closed");
  assert(ambiguousNewest.errors.some((message) => message.includes("ambiguous created_at timestamp")),
    "ambiguous producer ordering must emit an explicit error");

  const staleTuple = tupleFor(101, "a", "c", sha("d"));
  const stale = await runDispatcher({
    tuples: [tuple],
    inventories: { repository_dispatch: [producer(staleTuple, "repository_dispatch", "completed", "success")] },
  });
  assert.equal(stale.dispatches.filter((item) => item.event_type === "pr-runtime-base-refresh").length, 1,
    "stale base tuple must not suppress a current runtime refresh");

  const changedBaseTuple = tupleFor(101, "a", "c", sha("e"));
  const changedBase = await runDispatcher({
    tuples: [changedBaseTuple],
    inventories: { pull_request: [producer(tuple, "pull_request", "completed", "success")] },
  });
  assert.equal(changedBase.dispatches.filter((item) => item.event_type === "pr-runtime-base-refresh").length, 1,
    "a changed base requires a fresh exact tuple producer");

  const sibling = tupleFor(102, "f", "d");
  const numbers = (result) => runtimeDispatches(result).map(item => item.client_payload.pr_number);
  const existingFirst = await runDispatcher({
    tuples: [tuple, sibling],
    inventories: { pull_request: [producer(tuple, "pull_request", "in_progress")] },
  });
  assert.deepEqual(numbers(existingFirst), ["102"], "existing first PR must not starve second");
  assert.equal(existingFirst.errors.length, 0, existingFirst.errors.join("\n"));
  for (const event of ["pull_request", "repository_dispatch"]) {
    for (const [status, conclusion, earlierActive] of [
      ["completed", "cancelled", false],
      ["completed", "timed_out", false],
      ["in_progress", null, false],
      ["completed", "cancelled", true],
    ]) {
      const laterProducer = producer(sibling, event, "queued", null, {created_at: "2026-01-03T00:00:00Z"});
      const inventories = {
        pull_request: [producer(tuple, "pull_request", "in_progress")],
        repository_dispatch: [],
      };
      inventories[event].push(laterProducer);
      if (earlierActive) inventories.pull_request.push(producer(sibling, "pull_request", "in_progress", null, {
        id: 90204, created_at: "2026-01-02T00:00:00Z",
      }));
      let transitions = 0;
      const changedBetweenTargets = await runDispatcher({
        tuples: [tuple, sibling], inventories,
        afterPreviewDispatch: request => {
          if (request.client_payload.pr_number !== tuple.prNumber) return;
          transitions += 1;
          // Replace the REST row; do not mutate the earlier snapshot in place.
          inventories[event] = inventories[event].map(run => run.id === laterProducer.id
            ? {...run, status, conclusion} : run);
        },
      });
      const needsRefresh = status === "completed" && !earlierActive;
      assert.equal(transitions, 1, "the later producer changes only after the first target's preview handoff");
      assert.deepEqual(numbers(changedBetweenTargets), needsRefresh ? [sibling.prNumber] : [],
        `${event} ${status}/${conclusion} must use target-fresh evidence without duplicating an earlier active exact producer`);
      assert.equal(changedBetweenTargets.inventoryCalls.filter(request =>
        request.created !== "2026-01-08T00:00:00.000Z..*"
      ).length, needsRefresh ? 6 : 4,
      "each target gets both event inventories, with another full snapshot only before a required runtime POST");
      assert.equal(previewDispatches(changedBetweenTargets).length, 2,
        "fresh producer classification must retain independent preview reconciliation for both targets");
      assert.deepEqual(changedBetweenTargets.errors, [], "fresh terminal or active producer evidence is classifiable");
      assertRequestContracts(changedBetweenTargets);
    }
  }
  const unavailableLaterInventories = {pull_request: [
    producer(tuple, "pull_request", "in_progress"), producer(sibling, "pull_request", "queued"),
  ]};
  const unavailableLaterInventory = await runDispatcher({
    tuples: [tuple, sibling], inventories: unavailableLaterInventories,
    afterPreviewDispatch: request => {
      if (request.client_payload.pr_number === tuple.prNumber) unavailableLaterInventories.pull_request = {malformed: true};
    },
  });
  assert.deepEqual(numbers(unavailableLaterInventory), [], "unavailable target-fresh evidence cannot authorize a runtime POST");
  assert(unavailableLaterInventory.errors.some(message =>
    message.includes("Cannot verify exact runtime producer inventory for PR #102") && message.includes("inventory is malformed")),
  "an earlier queued snapshot cannot hide a later target's unknown producer evidence");
  assert.equal(previewDispatches(unavailableLaterInventory).length, 2,
    "unavailable runtime inventory must not suppress independent preview reconciliation");
  const initialTargetReadFailure = await runDispatcher({
    tuples: [tuple, sibling], pullGetFailures: {"101": 502},
  });
  assert.deepEqual(numbers(initialTargetReadFailure), ["102"],
    "a failed first pulls.get while resolving listed PR #101 must not prevent PR #102 from progressing");
  assert.equal(initialTargetReadFailure.pullReads.get("101"), 1,
    "the isolated failure is the initial native PR lookup for listed PR #101");
  assert(initialTargetReadFailure.pullReads.get("102") > 0,
    "later exact target discovery continues after the initial PR #101 read fails");
  assert(initialTargetReadFailure.errors.some(message =>
    message.includes("Cannot verify runtime target tuple for PR #101") && message.includes("no dispatch issued")),
  "the unavailable PR #101 tuple remains an explicit failed target rather than silent success");
  const lateSiblingProducer = producer(sibling, "repository_dispatch", "in_progress", null, {
    id: 90202, created_at: "2026-01-03T00:00:00Z",
  });
  const refreshedAfterEarlierTarget = await runDispatcher({
    tuples: [tuple, sibling],
    inventories: {pull_request: [producer(tuple, "pull_request", "in_progress")]},
    lateProducer: lateSiblingProducer,
  });
  assert.deepEqual(numbers(refreshedAfterEarlierTarget), [],
    "a full fresh inventory for the second target must find a producer absent during the earlier target's decision");
  assert.equal(refreshedAfterEarlierTarget.inventoryCalls.filter(request =>
    request.created !== "2026-01-08T00:00:00.000Z..*"
  ).length, 4, "both event kinds must be refreshed even when the earlier target reused an existing producer");
  assert.equal(previewDispatches(refreshedAfterEarlierTarget).length, 2,
    "the later target's producer discovery must not suppress independent preview reconciliation");
  assert.equal(refreshedAfterEarlierTarget.errors.length, 0, refreshedAfterEarlierTarget.errors.join("\n"));
  const lateNative = await runDispatcher({
    tuples: [tuple],
    lateProducer: producer(tuple, "pull_request", "in_progress", null, {
      id: 90203, created_at: "2026-01-03T00:00:00Z",
    }),
  });
  assert.deepEqual(numbers(lateNative), [],
    "a native producer that appears after the first inventory suppresses the exact runtime POST");
  assert.equal(previewDispatches(lateNative).length, 1,
    "a newly visible runtime producer leaves independent preview reconciliation available");
  assert.equal(lateNative.errors.length, 0, lateNative.errors.join("\n"));
  const skippedFirst = await runDispatcher({
    tuples: [tuple, sibling], filesByPr: { "101": [{ filename: "README.md" }] },
  });
  assert.deepEqual(numbers(skippedFirst), ["102"], "non-runtime first PR must not starve second");
  const requestDeadlineAbort = await runDispatcher({tuples: [tuple], expireAfterFirstApiRequest: true});
  assert.equal(requestDeadlineAbort.fatalError?.name, "TimeoutError",
    "a request crossing its actual AbortSignal deadline must surface the mocked timeout");
  assert.equal(requestDeadlineAbort.apiRequestRecords.length, 1,
    "wall-clock expiry must stop further GitHub API requests");
  assert.equal(requestDeadlineAbort.apiRequestRecords[0].name, "git.getRef");
  assert.equal(requestDeadlineAbort.apiRequestRecords[0].options.request.signal.timeoutMs, 12 * 60 * 1000);
  assert.equal(requestDeadlineAbort.apiRequestRecords[0].options.request.signal.aborted, true,
    "the API mock observes the signal expiring as its wall clock advances to the deadline");
  assert.equal(requestDeadlineAbort.dispatches.length, 0, "deadline abort must fail closed before any handoff");
  const failedPost = await runDispatcher({ tuples: [tuple, sibling], postFailurePr: "101" });
  assert.deepEqual(numbers(failedPost), ["101", "102"], "ambiguous first POST must not retry or starve second");
  assert(failedPost.warnings.some(message => message.includes("ambiguous POST timeout")),
    "transport uncertainty is a warning while strict visibility observation continues");
  assert(!failedPost.errors.some(message => message.includes("ambiguous POST timeout")),
    "an uncertain POST response alone must not fail a handoff that can still become visible");
  assert.equal(failedPost.waitedMillis, 120000, "observe unknown handoff through the same bounded shared window");
  assert(failedPost.errors.some(message => message.includes("attempted but visibility is unknown for PRs 101")));
  const deniedPost = await runDispatcher({tuples: [tuple, sibling], postFailurePr: "101", postFailureStatus: 422});
  assert.deepEqual(numbers(deniedPost), ["101", "102"]);
  assert.equal(deniedPost.waitedMillis, 0, "a documented rejection must not extend the shared observation");
  assert(deniedPost.errors.some(error => error.includes("POST rejected for PR #101")));
  const observedAmbiguousPost = await runDispatcher({
    tuples: [{...tuple, labels: [{name: "preview"}]}, sibling], postFailurePr: "101", postFailureVisible: true,
  });
  assert.deepEqual(numbers(observedAmbiguousPost), ["101", "102"], "visible ambiguous handoff must not retransmit");
  assert(previewDispatches(observedAmbiguousPost).some(item => item.client_payload.pr_number === "101"));
  assert(observedAmbiguousPost.infos.some(message => message.includes("became visible after an unknown POST response")));
  assert(observedAmbiguousPost.warnings.some(message => message.includes("ambiguous POST timeout")),
    "the unknown transport result remains visible as a warning");
  assert.deepEqual(observedAmbiguousPost.errors, [],
    "strict native visibility resolves an uncertain handoff without failing the job");
  assert(!observedAmbiguousPost.errors.some(message => message.includes("visibility is unknown")));
  const cutoffBeforePost = await runDispatcher({tuples: [tuple], fanoutCutoffAfterInventory: true});
  assert.deepEqual(numbers(cutoffBeforePost), [], "the twelve-minute reservation cutoff prevents a new runtime POST");
  assert(cutoffBeforePost.errors.some(message => message.includes("fan-out cutoff") &&
    message.includes("during runtime tuple proof") && message.includes("#101 remain incomplete")),
  `a necessary target stopped before its POST is explicitly reported incomplete: ${cutoffBeforePost.errors.join("; ")}`);
  assertRequestContracts(cutoffBeforePost);
  assert(cutoffBeforePost.apiRequestRecords.some(({name}) => name === "actions.listWorkflowRuns"),
    "deadline fixture must issue and observe its bounded inventory request before cutoff");
  assert(!cutoffBeforePost.apiRequestRecords.some(({name}) => name === "repos.createDispatchEvent"),
    "an expired fan-out deadline must prevent the runtime POST from reaching the API mock");
  const cutoffAtBoundary = await runDispatcher({
    tuples: [{...tuple, labels: [{name: "preview:priority"}]}, sibling],
    fanoutCutoffAfterPreviewPr: "101",
  });
  assert.deepEqual(numbers(cutoffAtBoundary), ["101"], "the cutoff stops subsequent runtime fan-out at the next target boundary");
  assert.equal(previewDispatches(cutoffAtBoundary).filter(item => item.client_payload.pr_number === "101").length, 1,
    "preview dispatch remains independent for the target processed before cutoff");
  assert(cutoffAtBoundary.errors.some(message => message.includes("fan-out cutoff") &&
    message.includes("at a target boundary") && message.includes("#102 remain incomplete")));
  assert(cutoffAtBoundary.inventoryCalls.some((request, index) =>
    request.created === "2026-01-08T00:00:00.000Z..*" && cutoffAtBoundary.observationPostCounts[index] === 1),
  "runtime handoffs sent before cutoff are still observed");
  assert(!cutoffAtBoundary.errors.some(message => message.includes("visibility is unknown")));
  const transientFirst = await runDispatcher({
    tuples: [tuple, sibling], apiFailure: (_request, count) => count === 1,
  });
  assert.deepEqual(numbers(transientFirst), ["102"], "transient inventory error for first PR must not poison later independent targets");
  assert(transientFirst.errors.some(message => message.includes("PR #101") && message.includes("no dispatch issued")));
  const changedFirst = await runDispatcher({
    tuples: [tuple, sibling],
    pullOverrides: { "101": reads => reads > 1 ? { state: "closed" } : {} },
  });
  assert.deepEqual(numbers(changedFirst), ["102"], "changed first PR must not starve second");
  assert(changedFirst.infos.some(message => message.includes("Skipping unverified runtime tuple for PR #101")));
  assert(!previewDispatches(changedFirst).some(item => item.client_payload.pr_number === "101"),
    "independent preview proof must still exclude a now-closed PR");
  const vanishedBase = await runDispatcher({
    tuples: [tuple, sibling], branchErrors: {"heads/develop": count => count === 2 ? 404 : null},
  });
  assert.deepEqual(numbers(vanishedBase), ["102"], "base disappearing at first target must not abort later targets");
  assert(vanishedBase.errors.some(message => message.includes("Cannot read current base for PR #101")));
  const unknownFirst = await runDispatcher({
    tuples: [tuple, sibling],
    inventories: { pull_request: [producer(tuple, "pull_request", "in_progress", null, {
      repository: { full_name: "other/repo" },
    })] },
  });
  assert.deepEqual(numbers(unknownFirst), ["102"], "invalid first PR evidence must not starve second");
  assert(unknownFirst.errors.some(message => message.includes("PR #101") && message.includes("no dispatch issued")));

  const secondPageRuns = runsDescending("pull_request", "2026-01-02T00:00:00Z");
  secondPageRuns.push(producer(tuple, "pull_request", "in_progress"));
  const secondPage = await runDispatcher({ tuples: [tuple], inventories: { pull_request: {
      initial: secondPageRuns, refined: [], refinedCreated: "2026-01-02T00:00:00Z..*",
    } } });
  assert.deepEqual(numbers(secondPage), [], "exact page-two producer must suppress duplicate");
  assert.equal(secondPage.errors.length, 0, "page two at the original cutoff must not be treated as malformed refinement");
  assert(secondPage.inventoryCalls.some(request => request.event === "pull_request" && request.page === 2));
  assert(secondPage.inventoryCalls.every(request => request.page <= 10));

  const overBoundPage = runsDescending("pull_request", "2026-01-02T00:00:00Z");
  overBoundPage[0] = producer(tuple, "pull_request", "in_progress", null, {
    created_at: "2026-01-02T00:00:00Z",
  });
  const refinedBound = await runDispatcher({
    tuples: [tuple], callback: {updated_at: "2026-01-03T00:00:00Z"}, inventories: { pull_request: {
      total_count: 1001, runs: overBoundPage, refinedCreated: "2026-01-02T00:00:00Z..*", refined: [overBoundPage[0]], refinedTotalCount: 1,
    } },
  });
  assert.deepEqual(numbers(refinedBound), [], "complete bounded refinement retains exact in-flight producer");
  assert.equal(refinedBound.errors.length, 0);
  assert(refinedBound.inventoryCalls.some(request => request.created === "2026-01-02T00:00:00Z..*"));

  const unrelatedInflight = producer(sibling, "pull_request", "in_progress", null, {created_at: "2026-01-02T00:00:00Z"});
  const completedRefresh = producer(tuple, "repository_dispatch", "completed", "success", {
    created_at: "2026-01-01T12:00:00Z",
    run_started_at: "2026-01-01T12:01:00Z", updated_at: "2026-01-01T12:10:00Z",
  });
  const successfulJob = {id: 73, name: "PR Full-Stack Smoke (runtime-required=true)",
    workflow_name: completedRefresh.display_title, run_id: completedRefresh.id, run_attempt: completedRefresh.run_attempt,
    head_sha: completedRefresh.head_sha, status: "completed", conclusion: "success",
    started_at: "2026-01-01T12:02:00Z", completed_at: "2026-01-01T12:09:00Z", steps: [
      ...["Run credential-free full-stack smoke", "Stop smoke stack", "Require successful runtime smoke and authenticated cleanup"]
        .map(name => ({name, status: "completed", conclusion: "success"})),
      {name: "Known canonical transition PLAY failure with successful authenticated cleanup", status: "completed", conclusion: "skipped"},
    ]};
  const successfulJobs = {[completedRefresh.id]: {total_count: 1, jobs: [successfulJob]}};
  const refinedCompleted = await runDispatcher({
    tuples: [tuple], callback: {updated_at: "2026-01-01T06:00:00Z"},
    inventories: {
      pull_request: {total_count: 1001, runs: [unrelatedInflight], refinedCreated: "2026-01-01T06:00:00Z..*",
        refined: [unrelatedInflight], refinedTotalCount: 1},
      repository_dispatch: [completedRefresh],
    },
    runtimeAttemptJobs: successfulJobs,
  });
  assert.deepEqual(numbers(refinedCompleted), [], "refinement must retain completed refresh between CI completion and sampled inflight floor");
  assert(refinedCompleted.inventoryCalls.some(request => request.created === "2026-01-01T06:00:00Z..*"));
  assert.deepEqual(refinedCompleted.errors, [], "refinement must reuse proof rather than fail closed");
  assert(refinedCompleted.infos.some(message => message.includes(
    `Skipping duplicate PR runtime refresh for #101: exact tuple producer ${completedRefresh.id} remains completed.`
  )), "refinement must report reuse of the completed producer");

  const narrowedAbsence = await runDispatcher({
    tuples: [tuple], callback: {updated_at: "2026-01-03T00:00:00Z"},
    inventories: {pull_request: {total_count: 1001, runs: [unrelatedInflight], refinedCreated: "2026-01-02T00:00:00Z..*",
      refined: [unrelatedInflight], refinedTotalCount: 1}},
  });
  assert.deepEqual(numbers(narrowedAbsence), [], "narrowed empty exact-producer slice cannot exclude older omitted producers");
  assert(narrowedAbsence.errors.some(message => message.includes("truncated initial inventory")));

  for (const args of [
    { inventories: { pull_request: {
      total_count: 1001, runs: overBoundPage, refinedCreated: "2026-01-02T00:00:00Z..*", refined: overBoundPage, refinedTotalCount: 1001,
    } } },
    { inventories: { pull_request: {
      total_count: 1001, runs: overBoundPage, refinedCreated: "2026-01-02T00:00:00Z..*", refined: [], refinedTotalCount: 0,
    } } },
    { inventories: { pull_request: {
      initial: secondPageRuns, initialPageTotals: {1: 101, 2: 102},
    } } },
    { inventories: { pull_request: { total_count: 1000, runs: [] } } },
    { inventories: { pull_request: { total_count: 101, runs: [] } } },
    { inventories: { pull_request: { total_count: 1, runs: [] } } },
    { apiFailure: "pull_request" },
    { inventories: { pull_request: { malformed: true } } },
    { inventories: { pull_request: [null] } },
    { inventories: { pull_request: [producer(tuple, "pull_request", "completed", "success", {
      created_at: "not-a-workflow-run-timestamp",
    })] } },
    { inventories: { pull_request: [producer(tuple, "pull_request", "queued"), producer(tuple, "pull_request", "queued")] } },
    { inventories: { pull_request: { total_count: 1001, runs: runsDescending("pull_request", "2026-01-02T00:00:00Z") } } },
  ]) {
    const uncertain = await runDispatcher({ tuples: [tuple], ...args });
    assert.deepEqual(numbers(uncertain), [], "unknown inventory must never dispatch runtime work");
    assert(uncertain.errors.some(message => message.includes("no dispatch issued")),
      "unknown inventory must be an explicit error, not apparent success");
  }

  for (const signalAt of ["2026-01-01T06:00:00Z", "2026-01-01T12:00:00.200Z", "2026-01-04T00:00:00Z"]) {
    const result = await runDispatcher({
      tuples: [tuple], callback: { updated_at: signalAt },
      inventories: {repository_dispatch: [completedRefresh]}, runtimeAttemptJobs: successfulJobs,
    });
    assert.deepEqual(numbers(result), [], "a usable exact completed refresh suppresses a duplicate even if it completed before Validation");
    assert.deepEqual(result.errors, []);
    assert.deepEqual(result.runtimeRunReads, [completedRefresh.id, completedRefresh.id], "reuse requires a final current-attempt read");
  }
  for (const job of [
    {...successfulJob, name: "PR Full-Stack Smoke (runtime-required=false)"},
    {...successfulJob, steps: successfulJob.steps.filter(step => step.name !== "Stop smoke stack")},
    {...successfulJob, steps: [...successfulJob.steps, successfulJob.steps[0]]},
    {...successfulJob, started_at: "2026-01-01T12:00:59Z"},
  ]) {
    const result = await runDispatcher({tuples: [tuple], callback: {updated_at: "2026-01-04T00:00:00Z"},
      inventories: {repository_dispatch: [completedRefresh]}, runtimeAttemptJobs: {[completedRefresh.id]: {total_count: 1, jobs: [job]}}});
    assert.deepEqual(numbers(result), ["101"], "a green but unusable exact attempt must not suppress the refresh");
    assert.deepEqual(result.errors, []);
  }
  const recentTransition = await runDispatcher({
    tuples: [tuple], callback: {updated_at: "2026-01-04T00:00:00Z"},
    inventories: {repository_dispatch: [completedRefresh]},
    runtimeAttemptJobs: {[completedRefresh.id]: {total_count: 1, jobs: [{...successfulJob,
      steps: successfulJob.steps.map(step => ({...step, conclusion:
        step.name.startsWith("Known canonical transition") ? "success" : "skipped"})),
    }]}},
  });
  assert.deepEqual(numbers(recentTransition), [], "an exact successful PLAY diagnostic retains its hold after later Validation");
  assert.deepEqual(recentTransition.errors, []);
  const staleSuccess = await runDispatcher({tuples: [tuple], inventories: {repository_dispatch: [completedRefresh]},
    runtimeAttemptJobs: successfulJobs, runtimeRunOverrides: {[completedRefresh.id]: reads => reads > 1 ? {run_attempt: 2} : {}}});
  assert.deepEqual(numbers(staleSuccess), [], "a concurrent rerun leaves inventory uncertain, not duplicate suppression or dispatch authority");
  assert(staleSuccess.errors.some(message => message.includes("changed during exact attempt proof")));
  const terminalTyped = conclusion => producer(tuple, "repository_dispatch", "completed", conclusion, {
    created_at: "2026-01-03T00:00:00Z",
  });
  for (const conclusion of ["cancelled", "timed_out"]) {
    const existing = terminalTyped(conclusion);
    for (const signalAt of ["2026-01-02T00:00:00Z", "2026-01-03T00:00:00.200Z", "2026-01-04T00:00:00Z"]) {
      const replaced = await runDispatcher({tuples: [tuple], callback: {updated_at: signalAt},
        inventories: {repository_dispatch: [existing]},
      });
      assert.deepEqual(numbers(replaced), ["101"], `${conclusion} exact typed producer permits only one replacement`);
      assert.deepEqual(replaced.runtimeRunReads, [existing.id, existing.id],
        "both initial selection and the final pre-POST fence require fresh native terminal-attempt proof");
      assert.equal(previewDispatches(replaced).length, 1, "terminal runtime recovery retains independent preview handoff");
      assert.deepEqual(replaced.errors, []);
      assertRequestContracts(replaced);
    }
    const unresolved = await runDispatcher({tuples: [tuple], callback: {updated_at: "2026-01-02T00:00:00Z"},
      inventories: {repository_dispatch: [existing]}, postFailurePr: "101",
    });
    assert.deepEqual(numbers(unresolved), ["101"], "an unresolved replacement POST is never issued a second time");
    assert.equal(unresolved.waitedMillis, 120000);
    assert(unresolved.errors.some(message => message.includes("attempted but visibility is unknown")),
      "the old terminal run cannot confirm receipt of its replacement");
  }
  for (const conclusion of ["failure", null]) {
    const held = await runDispatcher({tuples: [tuple], callback: {updated_at: "2026-01-02T00:00:00Z"},
      inventories: {repository_dispatch: [terminalTyped(conclusion)]},
    });
    assert.deepEqual(numbers(held), [],
      "legacy failure/PLAY and unknown dispositions keep the existing typed refresh hold");
    if (conclusion !== "failure") assert.deepEqual(held.runtimeRunReads, [], "unrelated conclusions need no native classification");
    assert.deepEqual(held.errors, []);
  }
  const failedTyped = {...terminalTyped("failure"), run_started_at: "2026-01-03T00:01:00Z", updated_at: "2026-01-03T00:04:00Z"};
  const diagnosticMarker = {name: "Known canonical transition PLAY failure with successful authenticated cleanup", status: "completed",
    conclusion: "skipped", started_at: "2026-01-03T00:02:00Z", completed_at: "2026-01-03T00:02:00Z"};
  const failedJob = {id: 72, name: "PR Full-Stack Smoke (runtime-required=true)", workflow_name: failedTyped.display_title,
    run_id: failedTyped.id, run_attempt: failedTyped.run_attempt, head_sha: failedTyped.head_sha, status: "completed", conclusion: "failure",
    started_at: "2026-01-03T00:01:01Z", completed_at: "2026-01-03T00:03:00Z", steps: [diagnosticMarker]};
  for (const [jobs, total, expected] of [
    [[failedJob], 1, ["101"]],
    [[{...failedJob, steps: [{...diagnosticMarker, started_at: null, completed_at: null}]}], 1, ["101"]],
    [[{...failedJob, steps: [{name: diagnosticMarker.name, status: "completed", conclusion: "skipped"}]}], 1, ["101"]],
    [[{...failedJob, steps: [{...diagnosticMarker, conclusion: "success"}]}], 1, []],
    [[{...failedJob, steps: [{...diagnosticMarker, conclusion: null}]}], 1, []],
    [[{...failedJob, steps: [diagnosticMarker, diagnosticMarker]}], 1, []],
    [[{...failedJob, steps: [{...diagnosticMarker, started_at: null}]}], 1, []],
    [[{...failedJob, steps: []}], 1, []],
    [[{...failedJob, completed_at: undefined}], 1, []],
    [[{...failedJob, started_at: "2026-02-30T00:00:00Z"}], 1, []],
    [[{...failedJob, started_at: "2026-01-03T00:01:01+00:00", completed_at: "2026-01-02T20:03:00-04:00"}], 1, ["101"]],
    [[{...failedJob, started_at: "2026-01-03T00:00:59Z"}], 1, []],
    [[failedJob], 2, []],
  ]) {
    const result = await runDispatcher({tuples:[tuple], callback:{updated_at: "2026-01-02T00:00:00Z"},
      inventories: {repository_dispatch:[failedTyped]}, runtimeAttemptJobs: {[failedTyped.id]: {total_count: total, jobs}}});
    assert.deepEqual(numbers(result), expected);
    assert.deepEqual(result.errors, []);
  }
  for (const attempt of [{...failedTyped, run_started_at: "2026-01-03T01:00:00Z", updated_at: "2026-01-03T01:04:00Z"},
    {...failedTyped, run_attempt: 2}, {...failedTyped, repository: {full_name: "other/repo"}},
    {...failedTyped, head_repository: {full_name: "fork/repo"}},
    {...failedTyped, path: `${failedTyped.path}@refs/heads/develop`}, null]) {
    const held = await runDispatcher({tuples: [tuple], inventories: {repository_dispatch: [failedTyped]},
      runtimeNativeAttempts: {[failedTyped.id]: attempt}, runtimeAttemptJobs: {[failedTyped.id]: {total_count: 1, jobs: [failedJob]}}});
    assert.deepEqual(numbers(held), [], "wrong native attempt identity or copied execution cannot authorize replacement");
    assert.deepEqual(held.errors, []);
  }
  const supersededFailure = await runDispatcher({tuples: [tuple], inventories: {repository_dispatch: [failedTyped]},
    runtimeRunOverrides: {[failedTyped.id]: reads => reads > 1 ? {run_attempt: 2} : {}},
    runtimeAttemptJobs: {[failedTyped.id]: {total_count: 1, jobs: [failedJob]}}});
  assert.deepEqual(numbers(supersededFailure), [], "a rerun beginning during native attempt/job proof must retain the failed refresh hold");
  assert.deepEqual(supersededFailure.runtimeRunReads, [failedTyped.id, failedTyped.id]);
  assert.deepEqual(supersededFailure.errors, []);
  const validRerun = {...failedTyped, run_attempt: 2};
  const validRerunJob = {...failedJob, run_attempt: 2, started_at: "2026-01-03T01:00:01Z", completed_at: "2026-01-03T01:03:00Z",
    steps: [{name: diagnosticMarker.name, status: "completed", conclusion: "skipped", started_at: null, completed_at: null}]};
  const rerunRecovery = await runDispatcher({tuples: [tuple], inventories: {repository_dispatch: [validRerun]},
    runtimeNativeAttempts: {[validRerun.id]: {...validRerun, run_started_at: "2026-01-03T01:00:00Z", updated_at: "2026-01-03T01:04:00Z"}},
    runtimeAttemptJobs: {[validRerun.id]: {total_count: 1, jobs: [validRerunJob]}}});
  assert.deepEqual(numbers(rerunRecovery), ["101"], "a genuinely executed exact rerun is not constrained by stale run-wide timestamps");
  assert.deepEqual(rerunRecovery.errors, []);
  const earlierRunning = producer(tuple, "pull_request", "in_progress", null, {
    id: 10101, created_at: "2026-01-02T00:00:00Z",
  });
  for (const conclusion of ["cancelled", "timed_out", "failure"]) {
    const unusable = conclusion === "failure" ? failedTyped : terminalTyped(conclusion);
    const retained = await runDispatcher({tuples: [tuple],
      inventories: {pull_request: [earlierRunning], repository_dispatch: [unusable]},
      runtimeAttemptJobs: {[unusable.id]: {total_count: 1, jobs: [failedJob]}}});
    assert.deepEqual(numbers(retained), [], "discarding a terminal typed replacement must reveal an earlier viable exact producer, not dispatch again");
    assert(retained.infos.some(message => message.includes(`exact tuple producer ${earlierRunning.id} remains in_progress`)));
    assert.equal(previewDispatches(retained).length, 1, "earlier producer reuse does not suppress preview reconciliation");
    assert.deepEqual(retained.errors, []);
  }
  const cancelledTyped = terminalTyped("cancelled");
  for (const changed of [
    {run_attempt: 2}, {run_attempt: 0}, {run_attempt: null}, {id: cancelledTyped.id + 1},
    {repository: {full_name: "other/repo"}}, {head_repository: {full_name: "fork/repo"}},
    {workflow_id: 999}, {path: ".github/workflows/other.yml"}, {event: "pull_request"},
    {name: "Other workflow"}, {display_title: "different tuple"}, {head_branch: "feature/ci"},
    {head_sha: sha("f")}, {created_at: "2026-01-03T00:00:01Z"},
    {status: "in_progress", conclusion: null}, {conclusion: "timed_out"}, {conclusion: "failure"},
  ]) {
    for (const failedRead of [1, 2]) {
      const changedNative = await runDispatcher({tuples: [tuple], callback: {updated_at: "2026-01-02T00:00:00Z"},
        inventories: {repository_dispatch: [cancelledTyped]},
        runtimeRunOverrides: {[cancelledTyped.id]: reads => reads === failedRead ? changed : {}},
      });
      assert.deepEqual(numbers(changedNative), [], "changed native evidence at either admission read prevents replacement");
      assert.equal(changedNative.runtimeRunReads.length, failedRead);
      assert(changedNative.errors.some(message => message.includes("same trusted terminal attempt")));
      assertRequestContracts(changedNative);
    }
  }
  for (const failedRead of [1, 2]) {
    const unavailableNative = await runDispatcher({tuples: [tuple],
      inventories: {repository_dispatch: [cancelledTyped]},
      runtimeRunOverrides: {[cancelledTyped.id]: reads => {
        if (reads === failedRead) throw Object.assign(new Error("terminal native lookup unavailable"), {status: 503});
        return {};
      }},
    });
    assert.deepEqual(numbers(unavailableNative), [], "unavailable native evidence is not replacement authority");
    assert(unavailableNative.errors.some(message => message.includes("terminal native lookup unavailable")));
    const unavailableWorkflow = await runDispatcher({tuples: [tuple],
      inventories: {repository_dispatch: [cancelledTyped]},
      runtimeWorkflowLookupFailures: failedRead === 1 ? [503] : [null, 503],
    });
    assert.deepEqual(numbers(unavailableWorkflow), [], "canonical workflow lookup must succeed at both admission fences");
    assert(unavailableWorkflow.errors.some(message => message.includes("fixture producer workflow lookup failed")));
  }
  const shiftingTerminalInventory = [cancelledTyped];
  const shiftedTerminalAttempt = await runDispatcher({tuples: [tuple],
    inventories: {repository_dispatch: shiftingTerminalInventory},
    runtimeRunOverrides: {[cancelledTyped.id]: reads => {
      if (reads === 1) shiftingTerminalInventory[0] = {...cancelledTyped, run_attempt: 2};
      return {};
    }},
  });
  assert.deepEqual(numbers(shiftedTerminalAttempt), [],
    "a newly listed terminal attempt cannot replace the attempt bound by the first admission read in this callback");
  assert(shiftedTerminalAttempt.errors.some(message => message.includes("incomplete or changed attempt identity")));
  const missingTerminalAttempt = await runDispatcher({tuples: [tuple],
    inventories: {repository_dispatch: [{...cancelledTyped, run_attempt: null}]},
  });
  assert.deepEqual(numbers(missingTerminalAttempt), [], "the inventory must identify a positive attempt before native proof lookup");
  assert.deepEqual(missingTerminalAttempt.runtimeRunReads, []);
  const invisible = await runDispatcher({ tuples: [tuple], hideDispatchVisibility: true });
  assert.deepEqual(numbers(invisible), ["101"], "accepted invisible POST must never be repeated");
  assert(invisible.errors.some(message => message.includes("visibility is unknown")));
  const many = Array.from({length: 12}, (_, index) => tupleFor(201 + index, "a", "c"));
  const manyInvisible = await runDispatcher({tuples: many, hideDispatchVisibility: true});
  assert.deepEqual(numbers(manyInvisible), many.map(item => item.prNumber),
    "all eligible targets receive one handoff before the single shared visibility wait");
  assert.deepEqual(manyInvisible.errors.filter(message => message.includes("visibility is unknown")), [
    `Refresh dispatch accepted but visibility is unknown for PRs ${many.map(item => item.prNumber).join(", ")}; no retry issued`,
  ], "one timeout must identify every invisible handoff");
  assert.equal(manyInvisible.timerPostCounts[0], 12, "all runtime POSTs precede the first visibility wait");
  assert.equal(manyInvisible.waitedMillis, 120000, "fan-out shares exactly one visibility window");
  const visibilityFloor = "2026-01-08T00:00:00.000Z..*";
  const observations = manyInvisible.inventoryCalls.filter(request => request.created === visibilityFloor);
  assert.equal(observations.length, 24, "each five-second interval before the deadline queries one narrow dispatch page");
  assert(observations.every(request => request.event === "repository_dispatch" && request.page === 1 && request.per_page === 100));
  assert(manyInvisible.inventoryCalls.some(request => request.event === "pull_request" && request.created !== visibilityFloor),
    "broad preflight still checks native producers");
  assert(manyInvisible.inventoryCalls.some(request => request.event === "repository_dispatch" && request.created !== visibilityFloor),
    "broad preflight still checks existing dispatch producers");
  const sameSecond = await runDispatcher({tuples: [tuple], initialTime: "2026-01-08T00:00:00.200Z",
    visibilityResponse: (_request, [posted]) => ({total_count: 1,
      workflow_runs: [{...posted, created_at: "2026-01-08T00:00:00Z"}]}),
  });
  assert.deepEqual(numbers(sameSecond), ["101"]);
  assert.deepEqual(sameSecond.errors, [], "a unique new exact run in the POST's timestamp second is visible");
  assert.equal(sameSecond.waitedMillis, 0);
  const ambiguousSameSecond = await runDispatcher({tuples: [tuple], initialTime: "2026-01-08T00:00:00.200Z",
    visibilityResponse: (_request, [posted]) => ({total_count: 2, workflow_runs: [
      {...posted, created_at: "2026-01-08T00:00:00Z"},
      {...posted, id: posted.id + 1, created_at: "2026-01-08T00:00:00Z"},
    ]}),
  });
  assert.deepEqual(numbers(ambiguousSameSecond), ["101"], "ambiguous handoff never repeats the POST");
  assert(ambiguousSameSecond.errors.some(error => error.includes("multiple new exact runtime handoffs")));
  const transientTuples = [tuple, tupleFor(102, "a", "c")];
  let transientReads = 0;
  const transient = await runDispatcher({tuples: transientTuples, visibilityResponse: (_request, posted) => {
    if (++transientReads === 1) throw Object.assign(new Error("temporarily unavailable"), {status: 502});
    return {total_count: posted.length, workflow_runs: posted};
  }});
  assert.deepEqual(numbers(transient), ["101", "102"], "retry visibility only, never either accepted POST");
  assert.equal(transientReads, 2);
  assert.equal(transient.waitedMillis, 5000);
  assert.deepEqual(transient.errors, []);
  const transientProducerIdentity = await runDispatcher({
    tuples: [tuple], runtimeWorkflowLookupFailures: [503],
  });
  assert.deepEqual(numbers(transientProducerIdentity), ["101"],
    "a transient post-dispatch producer identity lookup retries observation, never the handoff");
  assert.equal(transientProducerIdentity.runtimeWorkflowLookupCount, 2);
  assert.equal(transientProducerIdentity.waitedMillis, 5000);
  assert.equal(transientProducerIdentity.errors.length, 0, transientProducerIdentity.errors.join("\n"));
  assertRequestContracts(transientProducerIdentity);
  const deniedProducerIdentity = await runDispatcher({
    tuples: [tuple], runtimeWorkflowLookupFailures: [403],
  });
  assert.deepEqual(numbers(deniedProducerIdentity), ["101"],
    "a non-transient producer identity lookup failure cannot retransmit the one-shot handoff");
  assert.equal(deniedProducerIdentity.runtimeWorkflowLookupCount, 1,
    "only transient producer identity lookup failures are retried");
  assert.equal(deniedProducerIdentity.waitedMillis, 0);
  assert(deniedProducerIdentity.errors.some(message => message.includes("producer workflow identity could not be trusted")));
  const expiredProducerIdentity = await runDispatcher({
    tuples: [tuple], runtimeWorkflowLookupFailures: Array(30).fill(503),
  });
  assert.deepEqual(numbers(expiredProducerIdentity), ["101"]);
  assert.equal(expiredProducerIdentity.runtimeWorkflowLookupCount, 24,
    "transient producer identity lookup retries stop at the existing two-minute observation deadline");
  assert.equal(expiredProducerIdentity.waitedMillis, 120000);
  assert(expiredProducerIdentity.errors.some(message => message.includes("visibility deadline")));
  assertRequestContracts(expiredProducerIdentity);
  const visibilityEpoch = Date.parse("2026-01-08T00:00:00Z");
  const rateLimitError = (status, headers = {}, message = "fixture read refusal") => ({
    status, message, response: {status, headers, data: {message}},
  });
  for (const [label, failure, expectedDelay] of [
    ["Retry-After seconds overrides primary reset", rateLimitError(403, {"retry-after": "7",
      "x-ratelimit-remaining": "0", "x-ratelimit-reset": String(visibilityEpoch / 1000 + 90)}), 7000],
    ["Retry-After HTTP date", rateLimitError(429, {"retry-after": new Date(visibilityEpoch + 12000).toUTCString()}), 12000],
    ["exhausted primary reset", rateLimitError(403, {"x-ratelimit-remaining": "0",
      "x-ratelimit-reset": String(visibilityEpoch / 1000 + 90)}), 90000],
    ["malformed Retry-After falls back to primary reset", rateLimitError(403, {"retry-after": "tomorrow",
      "x-ratelimit-remaining": "0", "x-ratelimit-reset": String(visibilityEpoch / 1000 + 10)}), 10000],
    ["429 without guidance", rateLimitError(429), 60000],
    ["secondary limit without guidance", rateLimitError(403, {}, "You have exceeded a secondary rate limit."), 60000],
    ["abuse limit without guidance", rateLimitError(403, {}, "abuse detection mechanism"), 60000],
    ["primary limit with unusable reset", rateLimitError(403, {"x-ratelimit-remaining": "0",
      "x-ratelimit-reset": "invalid"}), 60000],
  ]) {
    for (const phase of ["identity", "inventory"]) {
      let reads = 0;
      const recovered = await runDispatcher({tuples: [tuple],
        ...(phase === "identity" ? {runtimeWorkflowLookupFailures: [failure]} : {
          visibilityResponse: (_request, posted) => {
            if (++reads === 1) throw failure;
            return {total_count: posted.length, workflow_runs: posted};
          },
        }),
      });
      assert.deepEqual(numbers(recovered), ["101"], `${phase}: ${label} must retry only GET observation`);
      assert.deepEqual(recovered.timerDelays, [expectedDelay], `${phase}: ${label} must honor GitHub REST delay guidance`);
      assert.equal(phase === "identity" ? recovered.runtimeWorkflowLookupCount : reads, 2);
      assert.deepEqual(recovered.errors, [], `${phase}: ${label} can confirm the one handoff after waiting`);
      assertRequestContracts(recovered);
    }
  }
  for (const phase of ["identity", "inventory"]) {
    let reads = 0;
    const beyondDeadline = rateLimitError(429, {"retry-after": "300"});
    const exhausted = await runDispatcher({tuples: [tuple],
      ...(phase === "identity" ? {runtimeWorkflowLookupFailures: [beyondDeadline]} : {
        visibilityResponse: () => { reads += 1; throw beyondDeadline; },
      }),
    });
    assert.deepEqual(numbers(exhausted), ["101"], "a rate limit cannot repeat the accepted POST");
    assert.deepEqual(exhausted.timerDelays, [120000], "rate-limit waiting cannot extend the shared visibility deadline");
    assert.equal(phase === "identity" ? exhausted.runtimeWorkflowLookupCount : reads, 1,
      "a Retry-After beyond the remaining budget must not issue another GET");
    assert(exhausted.errors.some(message => message.includes(phase === "identity" ? "visibility deadline" : "visibility is unknown")));
  }
  let sharedQuotaReads = 0;
  const sharedQuotaDeadline = await runDispatcher({tuples: [tuple],
    runtimeWorkflowLookupFailures: [rateLimitError(403, {}, "secondary rate limit")],
    visibilityResponse: () => { sharedQuotaReads += 1; throw rateLimitError(429); },
  });
  assert.deepEqual(numbers(sharedQuotaDeadline), ["101"]);
  assert.deepEqual(sharedQuotaDeadline.timerDelays, [60000, 60000],
    "rate-limit waits are capped by the same deadline across identity and inventory GETs");
  assert.equal(sharedQuotaReads, 1);
  assert(sharedQuotaDeadline.errors.some(message => message.includes("visibility is unknown")));
  for (const [failure, reason] of [
    [() => { throw Object.assign(new Error("visibility forbidden"), {status: 403}); }, "visibility forbidden"],
    [() => { throw rateLimitError(403, {"retry-after": "invalid", "x-ratelimit-remaining": "1",
      "x-ratelimit-reset": String(visibilityEpoch / 1000 + 10)}, "permission denied"); }, "permission denied"],
    [() => ({total_count: 1, workflow_runs: []}), "recent run inventory is incomplete"],
  ]) {
    const terminal = await runDispatcher({tuples: transientTuples, visibilityResponse: failure});
    assert.deepEqual(numbers(terminal), ["101", "102"]);
    assert.equal(terminal.waitedMillis, 0, "untrusted or unauthorized snapshot is terminal");
    assert(terminal.errors.some(message => message.includes(reason)));
  }
  const multiPageObservation = await runDispatcher({tuples: [tuple], visibilityResponse: ({page}, [posted]) => ({
    total_count: 101,
    workflow_runs: page === 1 ? Array.from({length: 100}, (_, index) => ({
      ...posted, id: 700000 + index, display_title: "unrelated"
    })) : [posted],
  })});
  assert.deepEqual(multiPageObservation.inventoryCalls.filter(request => request.created === visibilityFloor).map(request => request.page), [1, 2]);
  assert.equal(multiPageObservation.errors.length, 0, "an exact newly visible dispatch on the second page is accepted");
  for (const invalidIdentity of [
    {head_branch: "feature/ci"}, {workflow_id: 703}, {path: ".github/workflows/ci.yml"},
    {repository: {full_name: "other/repo"}}, {head_repository: {full_name: "fork/repo"}},
  ]) {
    const conflicting = await runDispatcher({tuples: [tuple], visibilityResponse: (_request, [posted]) => ({
      total_count: 1, workflow_runs: [{...posted, ...invalidIdentity}],
    })});
    assert.deepEqual(numbers(conflicting), ["101"], "a conflicting native identity never retries the POST");
    assert(conflicting.errors.some(error => error.includes("untrusted native workflow identity")));
  }
  let shiftedObservationReads = 0;
  const shiftedObservation = await runDispatcher({tuples: [tuple], visibilityResponse: ({page}, [posted]) => ({
    total_count: ++shiftedObservationReads === 1 ? 101 : 102,
    workflow_runs: page === 1 ? Array.from({length: 100}, (_, index) => ({
      ...posted, id: 700000 + index, display_title: "unrelated"
    })) : [posted, {...posted, id: 800000, display_title: "new unrelated run"}],
  })});
  assert.deepEqual(numbers(shiftedObservation), ["101"], "a shifting observation inventory never retries the accepted POST");
  assert.deepEqual(shiftedObservation.inventoryCalls.filter(request => request.created === visibilityFloor).map(request => request.page),
    [1, 2, 1, 2], "discard shifted pages, reread from page one, then confirm the complete stable two-page inventory");
  assert.equal(shiftedObservation.waitedMillis, 5000);
  assert.deepEqual(shiftedObservation.errors, [], "a consistent complete reread confirms visibility without another POST");
  const shortPageObservation = await runDispatcher({tuples: [tuple], visibilityResponse: ({page}, [posted]) => ({
    total_count: 102,
    workflow_runs: page === 1 ? Array.from({length: 100}, (_, index) => ({
      ...posted, id: 700000 + index, display_title: "unrelated"
    })) : [posted],
  })});
  assert.deepEqual(numbers(shortPageObservation), ["101"], "a short second page never retries the accepted POST");
  assert.deepEqual(shortPageObservation.inventoryCalls.filter(request => request.created === visibilityFloor).map(request => request.page), [1, 2]);
  assert.equal(shortPageObservation.waitedMillis, 0, "a short page for its own total is terminal, not pagination drift");
  assert(shortPageObservation.errors.some(error => error.includes("recent run inventory is incomplete")));
  const staleObservation = await runDispatcher({tuples: [tuple], visibilityResponse: (_request, [posted]) => ({
    total_count: 1, workflow_runs: [{...posted, created_at: "2026-01-07T23:59:58Z"}],
  })});
  assert.deepEqual(numbers(staleObservation), ["101"], "an out-of-window row cannot confirm or retry an accepted dispatch");
  assert(staleObservation.errors.some(error => error.includes("visibility is unknown")));
  const previewTuple = {...tuple, labels: [{name: "preview"}]};
  const changedFilesFailure = await runDispatcher({
    tuples: [tuple, {...sibling, labels: [{name: "preview"}]}, tupleFor(103, "d", "e")],
    filesFailurePr: "102",
  });
  assert.deepEqual(numbers(changedFilesFailure), ["101", "103"],
    "a changed-file lookup failure denies only the unproven runtime target");
  assert(previewDispatches(changedFilesFailure).some(item => item.client_payload.pr_number === "102"),
    "the failed runtime target still permits independent eligible preview reconciliation");
  assert(changedFilesFailure.inventoryCalls.some((request, index) =>
    request.created === visibilityFloor && changedFilesFailure.observationPostCounts[index] === 2),
    "accepted handoffs before and after the failed target are still observed");
  assert(changedFilesFailure.errors.some(error => error.includes("Cannot verify runtime changed files for PR #102")));
  assert(!changedFilesFailure.errors.some(error => error.includes("visibility is unknown")));
  const previewUnknown = await runDispatcher({tuples: [previewTuple], apiFailure: "pull_request"});
  assert.equal(runtimeDispatches(previewUnknown).length, 0);
  assert.equal(previewDispatches(previewUnknown).length, 1, "uncertain runtime inventory must not suppress independent preview reconciliation");
  const previewFailed = await runDispatcher({tuples: [previewTuple, sibling], previewFailurePr: "101"});
  assert.deepEqual(numbers(previewFailed), ["101", "102"], "preview POST uncertainty must preserve later runtime targets");
  assert(previewFailed.errors.some(message => message.includes("ambiguous preview POST timeout")));
  assert.equal(previewDispatches(previewFailed).filter(item => item.client_payload.pr_number === "101").length, 1,
    "uncertain preview POST is attempted exactly once, never retried");
  assert(previewFailed.observationPostCounts.includes(2), "visibility observation runs after the final runtime POST");
  assert(!previewFailed.errors.some(message => message.includes("visibility is unknown")), "accepted runtime handoffs still observed after preview failure");
  const runtimeBaseFailure = await runDispatcher({
    tuples: [previewTuple, sibling], branchErrors: {"heads/develop": count => count === 2 ? 502 : null},
  });
  assert.deepEqual(numbers(runtimeBaseFailure), ["102"], "runtime base read failure suppresses only its runtime handoff");
  assert.deepEqual(previewDispatches(runtimeBaseFailure).filter(item => item.client_payload.pr_number === "101").map(item => item.client_payload.pr_number), ["101"],
    "preview independently revalidates after runtime-only base failure");
  assert(runtimeBaseFailure.errors.some(message => message.includes("Cannot read current base")));
  for (const failedRead of [2, 3]) {
    const lookupFailure = await runDispatcher({
      tuples: [previewTuple, sibling], pullOverrides: {"101": reads => {
        if (reads === failedRead) throw new Error("fixture tuple lookup 502");
        return {};
      }},
    });
    assert.deepEqual(numbers(lookupFailure), failedRead === 2 ? ["102"] : ["101", "102"],
      "each runtime/preview lookup failure preserves later targets");
    assert.equal(previewDispatches(lookupFailure).filter(item => item.client_payload.pr_number === "101").length, failedRead === 2 ? 1 : 0);
    assert(lookupFailure.errors.some(message => message.includes("fixture tuple lookup 502")));
    assert(lookupFailure.observationPostCounts.includes(failedRead === 2 ? 1 : 2),
      "accepted handoffs are observed after target lookup failure");
    assert(!lookupFailure.errors.some(message => message.includes("visibility is unknown")));
  }
  for (const previewHeadMoved of [false, true]) {
    const runtimeMergeUnavailable = await runDispatcher({
      tuples: [previewTuple],
      mergeReadFailures: {[previewTuple.mergeSha]: reads => reads >= 2 && reads <= 6},
      pullOverrides: {"101": reads => previewHeadMoved && reads > 6
        ? {head: {sha: sha("f"), repo: {full_name: repository}}}
        : {}},
    });
    assert.equal(runtimeDispatches(runtimeMergeUnavailable).length, 0);
    assert(runtimeMergeUnavailable.warnings.some(message => message.includes("exact merge commit could not be read")));
    assert.deepEqual(previewDispatches(runtimeMergeUnavailable).map(item => item.client_payload.merge_sha),
      previewHeadMoved ? [] : [previewTuple.mergeSha],
      "the independent preview lookup must verify the exact tuple after all runtime merge reads fail");
  }
  const previewInvisible = await runDispatcher({tuples: [previewTuple], hideDispatchVisibility: true});
  assert.equal(previewDispatches(previewInvisible).length, 1, "invisible accepted runtime handoff must preserve preview reconciliation");
  process.stdout.write("runtime refresh deduplication fixtures passed\n");
})().catch((error) => {
  process.stderr.write(`${error.stack || error}\n`);
  process.exitCode = 1;
});
NODE

require_contains "$runtime_images_path" 'types: [opened, synchronize, reopened, edited]'
require_contains "$runtime_images_path" 'types: [pr-runtime-base-refresh]'
require_contains "$runtime_images_path" 'repository_dispatch:'
require_contains "$runtime_images_path" "&& 'metadata' || 'required' }}"
require_contains "$runtime_images_path" '  cancel-in-progress: true'
require_contains "$runtime_images_path" 'run-name: Build Runtime Images '
require_contains "$runtime_images_path" "format('secure-pr-artifact pr-{0}"
require_contains "$runtime_images_path" 'github.event.pull_request.base.sha'
require_contains "$runtime_images_path" 'github.event.pull_request.head.sha'
require_contains "$runtime_images_path" 'github.event.client_payload.base_sha'
require_contains "$runtime_images_path" 'github.event.client_payload.head_sha'
require_contains "$runtime_images_path" 'github.event.client_payload.merge_sha'
require_contains "$runtime_images_path" 'github.sha'
require_contains "$runtime_images_path" 'mode-{4}'
require_contains "$runtime_images_path" 'Runtime PR refresh must run from the repository default branch.'
require_contains "$runtime_images_path" 'Runtime PR refresh base SHA is not the current ${BASE_REF} branch ref.'
require_contains "$runtime_images_path" 'Runtime PR refresh payload is stale, conflicted, fork-owned, closed, or has an invalid merge tuple.'
require_contains "$runtime_images_path" 'Runtime PR refresh merge commit does not have the exact current base/head parents.'
require_contains "$runtime_images_path" "PR event merge commit does not have the event's exact base/head parents; refusing a stale PR artifact."
require_contains "$runtime_images_path" 'GH_TOKEN: ${{ github.token }}'
require_contains "$runtime_images_path" "format('trusted-branch branch-{0} sha-{1}'"
require_contains "$runtime_images_path" "format('base-refresh-only branch-{0} sha-{1} ci-{2}'"
require_contains "$runtime_images_path" '### PR runtime images and applicable full-stack smoke'
require_contains "$runtime_images_path" 'built the PR merge commit and runs full-stack smoke when required by change scope'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'github.event.workflow_run.event == '\''push'\'''
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'github.event.workflow_run.status == '\''completed'\'''
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'github.event.workflow_run.head_repository.full_name == github.repository'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'contents: write'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'github.rest.git.getRef'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'github.rest.pulls.list'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'github.rest.repos.getCommit'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'github.rest.actions.listWorkflowRuns'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'actions: read'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'runtimeRunPageSize = 100'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'runtimeRunLookbackMilliseconds'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'no dispatch issued:'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'queue: max'
assert_job_excludes runtime-images.yml dispatch-pr-base-refreshes 'cancel-in-progress: true'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'Skipping duplicate PR runtime refresh'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'currentBaseRef !== baseBranch'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'parents.length !== 2'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'parents[0] !== baseSha'
assert_job_excludes runtime-images.yml dispatch-pr-base-refreshes '.base.sha'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'event_type: "pr-runtime-base-refresh"'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'pr_number: tuple.prNumber'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'base_sha: tuple.baseSha'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'head_sha: tuple.headSha'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'merge_sha: tuple.mergeSha'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'event_type: "preview-deploy"'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'action: "deploy"'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'preview_domain: "preview.firedevops.net"'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes '".github/actions/load-workflow-tool-versions/"'
assert_job_excludes runtime-images.yml dispatch-pr-base-refreshes 'packages: write'
assert_job_excludes runtime-images.yml dispatch-pr-base-refreshes 'environment:'
assert_job_excludes runtime-images.yml dispatch-pr-base-refreshes 'secrets.'
assert_job_contains runtime-images.yml pr-local-smoke 'github.event_name == '\''repository_dispatch'\'''
assert_job_contains runtime-images.yml pr-local-smoke 'BASE_SHA="${{ needs.image-meta.outputs.base_sha }}"'
assert_job_contains runtime-images.yml pr-local-smoke 'MERGE_SHA="${{ needs.image-meta.outputs.merge_sha }}"'
assert_job_contains runtime-images.yml pr-controller-smoke 'github.event_name == '\''repository_dispatch'\'''
require_contains "$image_wait_path" 'display_title = run.get("display_title", "")'
require_contains "$image_wait_path" 'display_title.startswith("Build Runtime Images trusted-branch ")'
require_contains "$image_wait_path" 'and f"sha-{head_sha}" in tokens'
require_contains "$image_wait_path" 'and "branch-develop" in tokens'
require_contains "$image_wait_path" 'rf"Build Runtime Images secure-pr-artifact pr-[1-9][0-9]* base-{base_sha}"'
require_contains "$image_wait_path" 'rf" head-{head_sha} merge-{merge_sha} mode-required", display_title'
require_contains "$image_wait_path" 'run.get("event") == "workflow_run"'
require_contains "$image_wait_path" 'gh api --paginate --slurp'
require_contains "$image_wait_path" 'GitHub API poll failed while %s; retrying within the existing wait deadline.'
# shellcheck disable=SC2016 # These assertions intentionally match literal shell source.
require_contains "$image_wait_path" 'if ! workflow_payload="$('
# shellcheck disable=SC2016 # These assertions intentionally match literal shell source.
require_contains "$image_wait_path" 'if ! event_payload="$('
if grep -Eq '^concurrency:' "$preview_path"; then
  echo "Preview workflow must not cancel an active lifecycle from workflow-level concurrency" >&2
  exit 1
fi
assert_job_contains preview.yml preview-plan "group: preview-plan-\${{ github.event_name == 'pull_request_target' && github.event.pull_request.number || github.event.client_payload.pr_number || github.ref }}"
assert_job_contains preview.yml preview-plan 'cancel-in-progress: true'
assert_job_excludes preview.yml preview-plan 'Publish preview lifecycle state'
assert_job_excludes preview.yml preview-plan 'firemud-preview-summary'
assert_job_excludes preview.yml preview-plan 'publish-preview-comment.js'
assert_job_excludes preview.yml preview-plan 'write-preview-summary.sh'
if grep -Eq '^  preview-(deploy|destroy):' "$preview_path"; then
  echo "Preview source workflow must not contain privileged deploy or destroy jobs" >&2
  exit 1
fi
for forbidden in \
  'self-hosted' \
  'environment: pr-preview' \
  'secrets.' \
  'issues: write' \
  'pull-requests: write' \
  'packages: write' \
  'kubectl ' \
  'helm upgrade'; do
  if grep -Fq -- "$forbidden" "$preview_path"; then
    echo "Preview source workflow must not contain privileged source-side content: $forbidden" >&2
    exit 1
  fi
done
require_contains "$preview_path" 'run-name: ${{ github.event_name == '
require_contains "$preview_path" "format('Preview dispatch pr-{0}-base-{1}-head-{2}-merge-{3}', github.event.client_payload.pr_number, github.event.client_payload.base_sha || 'unknown', github.event.client_payload.head_sha, github.event.client_payload.merge_sha || 'unknown')"
require_contains "$preview_path" 'contents: read'
require_contains "$preview_path" 'pull-requests: read'
require_contains "$preview_path" 'pull_request_target:'
require_contains "$preview_path" 'types: [preview-deploy, preview-destroy]'
require_contains "$preview_path" '      - opened'
require_contains "$preview_path" '      - synchronize'
require_contains "$preview_path" '      - reopened'
require_contains "$preview_path" '      - labeled'
require_contains "$preview_path" '      - unlabeled'
require_contains "$preview_path" '      - edited'
require_contains "$preview_path" 'CLIENT_HEAD_SHA: ${{ github.event.client_payload.head_sha }}'
require_contains "$preview_path" 'CLIENT_BASE_REF: ${{ github.event.client_payload.base_ref }}'
require_contains "$preview_path" 'CLIENT_BASE_SHA: ${{ github.event.client_payload.base_sha }}'
require_contains "$preview_path" 'CLIENT_MERGE_SHA: ${{ github.event.client_payload.merge_sha }}'
require_contains "$preview_path" 'CLIENT_IMAGE_TAG: ${{ github.event.client_payload.image_tag }}'
require_contains "$preview_path" 'CLIENT_PREVIEW_DOMAIN: ${{ github.event.client_payload.preview_domain }}'
if grep -Fq 'workflow_dispatch:' "$preview_path"; then
  echo "Preview source workflow must not expose a branch-selectable workflow_dispatch trigger" >&2
  exit 1
fi
if grep -Eq '^      - closed$' "$preview_path"; then
  echo "Preview source workflow must leave close cleanup to the trusted workflow" >&2
  exit 1
fi
require_contains "$preview_path" 'Check out trusted default branch for preview plan'
require_contains "$preview_path" 'ref: ${{ github.event.repository.default_branch }}'
require_contains "$preview_path" "github.event_name == 'pull_request_target'"
require_contains "$preview_path" "github.event_name == 'repository_dispatch'"
require_contains "$preview_path" 'github.event.pull_request.head.repo.full_name == github.repository'
require_contains "$preview_path" 'github.event.pull_request.base.repo.full_name == github.repository'
if grep -Fq 'github.event.pull_request.merge_commit_sha' "$preview_path"; then
  echo "Preview source must resolve a fresh REST test merge, not trust the event payload" >&2
  exit 1
fi
require_contains "$preview_path" 'MERGE_RETRY_LIMIT=5'
require_contains "$preview_path" 'Preview merge computation unavailable'
require_contains "$preview_path" 'Stale preview head SHA'
require_contains "$preview_path" '"$CURRENT_BASE_REF" != main && "$CURRENT_BASE_REF" != develop'
require_contains "$preview_path" 'Stale preview dispatch tuple'
require_contains "$preview_path" 'Stale preview image identity'
require_contains "$preview_path" 'Incomplete preview dispatch tuple'
require_contains "$preview_path" '-n "$PLANNED_IMAGE_TAG" ]]; then'

# Execute the preview plan's refresh predicate with the documented minimal
# repository_dispatch payload. A live stacked base must refresh its exact
# merge image even when the dispatch carries no planned tuple.
python3 - "$preview_path" <<'PY'
import shlex
import subprocess
import sys
from pathlib import Path


workflow_path = Path(sys.argv[1])
workflow = workflow_path.read_text(encoding="utf-8")
predicate_start = workflow.index(
    '            if [[ "$EVENT_NAME" == pull_request_target && "$CURRENT_BASE_REF" != main && "$CURRENT_BASE_REF" != develop ]] ||'
)
predicate_end = workflow.index(
    '\n            if [[ "$ACTION" != deploy ]]; then',
    predicate_start,
)
predicate = workflow[predicate_start:predicate_end]


def refresh_required(event_name, current_base_ref, planned_base_sha="", planned_image_tag=""):
    script = "\n".join(
        [
            "set -euo pipefail",
            f"EVENT_NAME={shlex.quote(event_name)}",
            f"CURRENT_BASE_REF={shlex.quote(current_base_ref)}",
            f"PLANNED_BASE_SHA={shlex.quote(planned_base_sha)}",
            f"PLANNED_IMAGE_TAG={shlex.quote(planned_image_tag)}",
            "BASE_REFRESH_REQUIRED=false",
            predicate,
            'printf \'%s\\n\' "$BASE_REFRESH_REQUIRED"',
        ]
    )
    completed = subprocess.run(
        ["bash", "-c", script],
        text=True,
        capture_output=True,
        check=False,
    )
    if completed.returncode != 0:
        raise SystemExit(
            f"preview refresh predicate failed: {completed.stderr.strip()}"
        )
    return completed.stdout.strip()


if refresh_required("repository_dispatch", "feature/parent") != "true":
    raise SystemExit(
        "minimal repository_dispatch payload must refresh a live stacked base image"
    )
if refresh_required("repository_dispatch", "develop") != "false":
    raise SystemExit("ordinary develop previews must continue reusing base images")
if refresh_required(
    "repository_dispatch", "develop", "a" * 40, "base-image"
) != "true":
    raise SystemExit("planned image refresh must remain enabled for ordinary bases")
PY

assert_job_contains preview.yml preview-plan 'resolve-preview-image-tag.sh'
assert_job_contains preview.yml preview-plan 'Expected the tested PR merge tag or immutable base SHA.'
assert_job_contains preview.yml preview-plan '"$MERGE_SHA" "$PR_NUMBER" "$BASE_SHA"'
assert_job_contains preview.yml preview-plan 'pr-merge-${MERGE_SHA}'
require_contains "$ROOT_DIR/dev-tools/hosted/preview/resolve-preview-image-tag.sh" 'expected_file_count'
require_contains "$ROOT_DIR/dev-tools/hosted/preview/resolve-preview-image-tag.sh" 'current_merge_sha'
require_contains "$ROOT_DIR/dev-tools/hosted/preview/resolve-preview-image-tag.sh" 'refusing to select base images'
assert_job_contains preview.yml preview-plan 'preview.firedevops.net'
assert_job_contains preview.yml preview-render 'runs-on: ubuntu-latest'
assert_job_contains preview.yml preview-render "needs.preview-plan.outputs.action == 'deploy'"
assert_job_contains preview.yml preview-render 'ref: refs/pull/${{ needs.preview-plan.outputs.pr_number }}/merge'
assert_job_contains preview.yml preview-render 'fetch-depth: 2'
assert_job_contains preview.yml preview-render 'expected_merge_parents="$MERGE_SHA $BASE_SHA $HEAD_SHA"'
assert_job_contains preview.yml preview-render 'The checked-out merge ref is not the planned two-parent merge'
assert_job_contains preview.yml preview-render 'forbidden = {'
assert_job_contains preview.yml preview-render 'render contains forbidden object not in explicit exclusion allowlist'
assert_job_contains preview.yml preview-render 'validate-preview-artifact.py'
assert_job_contains preview.yml preview-render 'sanitize "$filtered" "$sanitized"'
for binding in manifestSha256 repository sourceRunId prNumber baseSha headSha mergeSha imageTag hostname; do
  assert_job_contains preview.yml preview-render "$binding"
done
assert_job_contains preview.yml preview-render 'if $event == "repository_dispatch" then {action:$action}'
assert_job_contains preview.yml preview-render 'name: preview-render-pr-${{ needs.preview-plan.outputs.pr_number }}-${{ needs.preview-plan.outputs.head_sha }}'
assert_job_contains preview.yml preview-destroy-intent 'runs-on: ubuntu-latest'
assert_job_contains preview.yml preview-destroy-intent "github.event_name == 'repository_dispatch'"
assert_job_contains preview.yml preview-destroy-intent "needs.preview-plan.outputs.action == 'destroy'"
assert_job_contains preview.yml preview-destroy-intent '--arg event repository_dispatch'
assert_job_contains preview.yml preview-destroy-intent 'schemaVersion:1,event:$event,action:$action'
assert_job_contains preview.yml preview-destroy-intent 'name: preview-intent-pr-${{ needs.preview-plan.outputs.pr_number }}-${{ needs.preview-plan.outputs.head_sha }}'
assert_job_excludes preview.yml preview-destroy-intent "github.event_name == 'pull_request'"

# The trusted default-branch workflow owns all runtime and identity mutations.
require_contains "$trusted_preview_path" "github.event.workflow_run.head_repository.full_name == github.repository"
require_contains "$trusted_preview_path" "github.event.workflow_run.event == 'pull_request_target' || github.event.workflow_run.event == 'repository_dispatch'"
require_contains "$trusted_preview_path" "github.event_name == 'pull_request_target' && github.event.action == 'closed'"
require_contains "$trusted_preview_path" 'require_source_field head-branch "$DEFAULT_BRANCH"'
require_contains "$trusted_preview_path" 'preview-(render|intent)-pr-'
require_contains "$trusted_preview_path" 'preview-${ARTIFACT_KIND}-pr-${PR_NUMBER}-${EXPECTED_HEAD_SHA}'
require_contains "$trusted_preview_path" 'metadata_event="$(jq -r'
require_contains "$trusted_preview_path" 'metadata_action="$(jq -r'
require_contains "$trusted_preview_path" 'validate-preview-intent.py'
require_contains "$trusted_preview_path" 'repository="$(jq -r'
require_contains "$trusted_preview_path" '[[ "$repository" == "$GITHUB_REPOSITORY" ]]'
require_contains "$trusted_preview_path" 'base_ref="$(jq -r'
require_contains "$trusted_preview_path" 'Ignoring lifecycle event without the current pull-request test-merge SHA.'
require_contains "$trusted_preview_path" 'expected_merge_image_tag="pr-merge-${merge_sha}"'
require_contains "$trusted_preview_path" 'labels_json="$(jq -c'
require_contains "$trusted_preview_path" 'preview-eligibility.py'
require_contains "$trusted_preview_path" '[[ "$current_head_sha" == "$EXPECTED_HEAD_SHA" ]] || emit_no_action'
require_contains "$trusted_preview_path" 'revalidate-preview-source-binding.sh'
require_contains "$ROOT_DIR/dev-tools/hosted/preview/revalidate-preview-source-binding.sh" 'Preview source binding changed'
require_contains "$trusted_preview_path" 'CLEANUP_STATE=open'
require_contains "$trusted_preview_path" 'RETIRE_IDENTITY=true'
require_contains "$trusted_preview_path" "needs.validate-target.outputs.cleanup_state == 'open'"
require_contains "$trusted_preview_path" '--open-cleanup "$PR_NUMBER" "$EXPECTED_HEAD_SHA"'
require_contains "$trusted_preview_path" '--cleanup "$PR_NUMBER" "$EXPECTED_HEAD_SHA"'
require_contains "$trusted_preview_path" "needs.validate-target.outputs.retire_identity == 'true'"
for job in prepare-runtime deploy-runtime destroy-runtime retire-identity; do
  assert_job_contains hosted-identity-request.yml "$job" 'runs-on:'
  assert_job_contains hosted-identity-request.yml "$job" 'self-hosted'
  assert_job_contains hosted-identity-request.yml "$job" 'environment: trusted-hosted-cluster'
done
require_contains "$preview_reconciler_path" 'actions/runs?branch=${DEFAULT_BRANCH}&status=${active_status}&per_page=100'
require_contains "$preview_reconciler_path" 'gh api --paginate --slurp'
require_contains "$preview_reconciler_path" '.display_title == $run_name'
require_contains "$preview_reconciler_path" '"Preview dispatch pr-${pr_number}-base-${base_sha}-head-${head_sha}-merge-${merge_sha}"'
require_contains "$preview_reconciler_path" '"repos/${GITHUB_REPOSITORY}/dispatches"'
require_contains "$preview_reconciler_path" '-f event_type=preview-deploy'
require_contains "$preview_reconciler_path" 'client_payload[head_sha]=${head_sha}'
require_contains "$preview_reconciler_path" 'client_payload[base_ref]=${pr_base_ref}'
require_contains "$preview_reconciler_path" 'client_payload[base_sha]=${base_sha}'
require_contains "$preview_reconciler_path" 'client_payload[merge_sha]=${merge_sha}'
require_contains "$preview_reconciler_path" 'client_payload[image_tag]=${image_tag}'
require_contains "$preview_reconciler_path" 'client_payload[action]=deploy'
require_contains "$preview_reconciler_path" 'dispatch_candidates() {'
require_contains "$preview_reconciler_path" 'candidate_rows="$('
require_contains "$preview_reconciler_path" 'dispatch_candidates <<<"$candidate_rows"'
if python3 - "$preview_reconciler_path" <<'PY'
import re
import sys
from pathlib import Path

workflow = Path(sys.argv[1]).read_text(encoding="utf-8")
if any(re.search(r"\|\s*(?:IFS=[^;]+\s+)?while\b", line) for line in workflow.splitlines()):
    raise SystemExit("preview reconciler must not pipe candidate rows directly into while")
PY
then
  :
else
  echo "Preview reconciler candidate processing must keep dispatch state in the current shell" >&2
  exit 1
fi

if grep -Fq 'desired_image_tag=' "$preview_reconciler_path"; then
  echo "Preview reconciler must not retain an unused desired image tag" >&2
  exit 1
fi
require_contains "$preview_reconciler_path" 'resolve-preview-image-tag.sh'
for annotation in \
  'firemud.dev/last-preview-base-sha' \
  'firemud.dev/last-preview-head-sha' \
  'firemud.dev/last-preview-merge-sha' \
  'firemud.dev/last-preview-image-tag' \
  'firemud.dev/requested-preview-base-sha' \
  'firemud.dev/requested-preview-head-sha' \
  'firemud.dev/requested-preview-merge-sha' \
  'firemud.dev/requested-preview-image-tag'; do
  require_contains "$preview_reconciler_path" "$annotation"
done
require_contains "$preview_reconciler_path" 'mergeable_state'
require_contains "$ROOT_DIR/dev-tools/hosted/preview/revalidate-preview-source-binding.sh" '.mergeable == true'
if grep -Fq 'actions/workflows/preview.yml/dispatches' "$preview_reconciler_path" ||
  grep -Fq 'inputs[ref]=' "$preview_reconciler_path"; then
  echo "Preview reconciler must use typed repository_dispatch from the default branch" >&2
  exit 1
fi
for duplicate in \
  'const isBotAuthored' \
  'const isWorkflowComment' \
  'const isLegacyWorkflowComment' \
  'const commentTimestamp' \
  'const deleteCommentIfPresent'; do
  if grep -Fq "$duplicate" "$preview_path"; then
    echo "Preview workflow must keep shared comment logic in the canonical publisher: $duplicate" >&2
    exit 1
  fi
done
for helper in \
  'function commentTimestamp' \
  'const isBotAuthored' \
  'const isWorkflowComment' \
  'const isLegacyWorkflowComment' \
  'const existing = previewComments.reduce' \
  'async function deleteCommentIfPresent' \
  'markerPolicy === "preserve-reclaimed"' \
  'statePolicy = "manual-any"' \
  'module.exports = { publishPreviewComment };'; do
  require_contains "$preview_comment_publisher_path" "$helper"
done
if grep -Fq 'Clear previous preview summary comments' "$preview_path"; then
  echo "Preview workflow must update the canonical summary instead of clearing it" >&2
  exit 1
fi
require_contains "$trusted_preview_path" 'mode: "deploying"'
require_contains "$trusted_preview_path" 'mode: "success"'
require_contains "$trusted_preview_path" 'mode: "removed"'
for mode in deploying target unavailable success cleanup removed reclaimed failure; do
  require_contains "$ROOT_DIR/dev-tools/hosted/preview/write-preview-summary.sh" "  $mode)"
done
require_contains "$ROOT_DIR/dev-tools/hosted/preview/write-preview-summary.sh" '## ✅ Preview Removed'
require_contains "$ROOT_DIR/dev-tools/hosted/preview/write-preview-summary.sh" '- Web: pending'
require_contains "$ROOT_DIR/dev-tools/hosted/preview/write-preview-summary.sh" '- TCP: pending'
for job in image-meta pr-local-smoke; do
  assert_job_condition runtime-images.yml "$job" "$required_condition"
done
assert_job_contains runtime-images.yml pr-local-smoke 'timeout-minutes: 45'

for job in build-base-image build-runtime-images smoke-full; do
  assert_job_contains runtime-images.yml "$job" "github.event_name != 'pull_request'"
done

assert_job_contains runtime-images.yml pr-local-smoke 'SMOKE_IMAGE_LOCAL_ONLY:'
assert_job_contains runtime-images.yml pr-local-smoke 'build-local-smoke-images.sh'
assert_job_contains runtime-images.yml pr-local-smoke 'actions/upload-artifact@'
for forbidden in 'packages: write' 'docker/login-action@' 'push: true' 'type=registry,ref='; do
  assert_job_excludes runtime-images.yml pr-local-smoke "$forbidden"
done

require_contains "$pr_image_publisher_path" 'workflow_run:'
require_exact_line "$pr_image_publisher_path" 'permissions: {}'
# shellcheck disable=SC2016 # This assertion intentionally matches a literal GitHub expression.
require_contains "$pr_image_publisher_path" "format('{0} source-run-{1} attempt-{2}', github.event.workflow_run.display_title, github.event.workflow_run.id, github.event.workflow_run.run_attempt)"
require_contains "$pr_image_publisher_path" "format('source-run-{0} attempt-{1}', github.event.client_payload.source_run_id, github.event.client_payload.source_run_attempt)"
require_contains "$pr_image_publisher_path" "github.event.workflow_run.event == 'pull_request'"
require_contains "$pr_image_publisher_path" "github.event.workflow_run.conclusion == 'success'"
require_contains "$pr_image_publisher_path" 'github.event.workflow_run.head_repository.full_name == github.repository'
require_contains "$pr_image_publisher_path" 'TITLE_PATTERN = re.compile('
require_contains "$pr_image_publisher_path" 'mode-required$'
require_contains "$pr_image_publisher_path" 'actions: read'
require_contains "$pr_image_publisher_path" 'packages: write'
require_contains "$pr_image_publisher_path" '### Trusted PR runtime image publication'
require_contains "$pr_image_publisher_path" 'GitHub displays this run in the default-branch context'
require_contains "$pr_image_publisher_path" 'markdown_code(os.environ['
require_contains "$pr_image_publisher_path" 'from html import escape'
require_contains "$pr_image_publisher_path" '<code>{markdown_code(os.environ['

assert_job_contains publish-pr-runtime-images.yml publish 'contents: read'
# shellcheck disable=SC2016 # These are literal GitHub expression and shell source contracts.
require_contains "$pr_image_publisher_path" 'pr-runtime-images-{image_tag}'
# shellcheck disable=SC2016 # This assertion intentionally matches the unevaluated publisher script.
require_contains "$pr_image_publisher_path" 'docker push "$image"'
# shellcheck disable=SC2016 # These assertions intentionally match unevaluated publisher shell.
require_contains "$pr_image_publisher_path" 'docker manifest inspect "$image"'
require_contains "$pr_image_publisher_path" 'Fixed image tag already exists with the validated source image ID; preserving first publication'
require_contains "$pr_image_publisher_path" 'max_push_attempts=3'
# shellcheck disable=SC2016 # These assertions intentionally match unevaluated publisher shell.
require_contains "$pr_image_publisher_path" 'backoff_seconds=$((5 * 2 ** (push_attempt - 1)))'
# shellcheck disable=SC2016 # This assertion intentionally matches unevaluated publisher shell.
require_contains "$pr_image_publisher_path" 'sleep "$backoff_seconds"'
assert_publish_checkout_configuration "$pr_image_publisher_path"

python3 - "$pr_image_publisher_path" <<'PY'
import os
import re
import sys
import tempfile
import textwrap
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("trusted PR image publisher contract requires one workflow path")
workflow_text = Path(sys.argv[1]).read_text(encoding="utf-8")
matches = list(re.finditer(r"python3 - <<'PY'\n(?P<script>.*?)\n          PY", workflow_text, re.DOTALL))
if len(matches) != 2:
    raise SystemExit("trusted PR image publisher summary script was not found")

script = textwrap.dedent(matches[1].group("script"))
with tempfile.NamedTemporaryFile(mode="r+", encoding="utf-8") as summary_file:
    test_environment = {
        "GITHUB_STEP_SUMMARY": summary_file.name,
        "SOURCE_RUN_ID": "123",
        "SOURCE_RUN_URL": "https://github.example/runs/123",
        "SOURCE_RUN_ATTEMPT": "2",
        "SOURCE_ARTIFACT_ID": "456",
        "SOURCE_ARTIFACT_DIGEST": "sha256:" + "a" * 64,
        "SOURCE_RUN_TITLE": "build `title` <script>&\nnext",
        "SOURCE_HEAD_BRANCH": "feature/`branch` <b>&",
        "SOURCE_HEAD_SHA": "abc123",
        "HEAD_SHA": "abc123",
        "MERGE_SHA": "def456",
        "IMAGE_TAG": "pr-merge-def456",
    }
    previous_environment = os.environ.copy()
    try:
        os.environ.update(test_environment)
        exec(compile(script, "publish-pr-runtime-images-summary", "exec"), {})
    finally:
        os.environ.clear()
        os.environ.update(previous_environment)
    summary_file.seek(0)
    summary = summary_file.read()

if "<code>build `title` &lt;script&gt;&amp; next</code>" not in summary:
    raise SystemExit("trusted PR image publisher did not escape the run title")
if "<code>feature/`branch` &lt;b&gt;&amp;</code>" not in summary:
    raise SystemExit("trusted PR image publisher did not escape the head branch")
if "<script>" in summary:
    raise SystemExit("trusted PR image publisher emitted unescaped HTML")
PY

require_contains "$image_wait_path" 'publish-pr-runtime-images.yml/runs?event={event}&created={lower_text}..{upper_text}&per_page=100'
require_contains "$image_wait_path" '"Publish", "PR", "Runtime", "Images", "Build", "Runtime", "Images"'
require_contains "$image_wait_path" 're.fullmatch(r"pr-[1-9][0-9]{0,50}", tokens[8])'
require_contains "$image_wait_path" 'tokens[9] == f"base-{base_sha}"'
require_contains "$image_wait_path" 'tokens[10] == f"head-{head_sha}"'
require_contains "$image_wait_path" 'tokens[11] == f"merge-{merge_sha}"'
require_contains "$image_wait_path" 'tokens[12] == "mode-required"'
require_contains "$image_wait_path" 'wait_for_pr_publisher'
# shellcheck disable=SC2016 # This assertion intentionally matches a literal shell default expression.
require_contains "$image_wait_path" 'publisher_timeout_seconds="${HOSTED_IMAGE_PUBLISHER_WAIT_TIMEOUT_SECONDS:-${timeout_seconds}}"'
# shellcheck disable=SC2016 # This assertion intentionally matches the unevaluated publisher deadline.
require_contains "$image_wait_path" 'local publisher_deadline=$((SECONDS + publisher_timeout_seconds))'

contract_fixture_dir="$(mktemp -d)"
trap 'rm -rf "$contract_fixture_dir"' EXIT
cat >"$contract_fixture_dir/publisher-checkout-other-job.yml" <<'EOF'
jobs:
  publish:
    steps:
      - uses: actions/checkout@fixture
        with:
          ref: ${{ github.event.repository.default_branch }}
          persist-credentials: false
  other:
    steps:
      - uses: actions/checkout@fixture
EOF
if (assert_publish_checkout_configuration \
  "$contract_fixture_dir/publisher-checkout-other-job.yml" \
  ) 2>"$contract_fixture_dir/publisher-checkout-other-job.error"; then
  echo "assert_publish_checkout_configuration must reject checkout steps in another job" >&2
  exit 1
fi
require_contains "$contract_fixture_dir/publisher-checkout-other-job.error" \
  'workflow must contain exactly one actions/checkout step'
cat >"$contract_fixture_dir/publisher-checkout-only-other-job.yml" <<'EOF'
jobs:
  other:
    steps:
      - uses: actions/checkout@fixture
EOF
if (assert_publish_checkout_configuration \
  "$contract_fixture_dir/publisher-checkout-only-other-job.yml" \
  ) 2>"$contract_fixture_dir/publisher-checkout-only-other-job.error"; then
  echo "assert_publish_checkout_configuration must require the checkout in the publish job" >&2
  exit 1
fi
require_contains "$contract_fixture_dir/publisher-checkout-only-other-job.error" \
  'the workflow checkout must belong to the publish job'
cat >"$contract_fixture_dir/publisher-checkout-wrong-ref.yml" <<'EOF'
jobs:
  publish:
    steps:
      - uses: actions/checkout@fixture
        with:
          ref: refs/heads/main
          persist-credentials: false
EOF
if (assert_publish_checkout_configuration \
  "$contract_fixture_dir/publisher-checkout-wrong-ref.yml" \
  ) 2>"$contract_fixture_dir/publisher-checkout-wrong-ref.error"; then
  echo "assert_publish_checkout_configuration must reject a non-default checkout ref" >&2
  exit 1
fi
require_contains "$contract_fixture_dir/publisher-checkout-wrong-ref.error" \
  'publish checkout must use the repository default branch'
cat >"$contract_fixture_dir/publisher-checkout-string-persist-credentials.yml" <<'EOF'
jobs:
  publish:
    steps:
      - uses: actions/checkout@fixture
        with:
          ref: ${{ github.event.repository.default_branch }}
          persist-credentials: 'false'
EOF
if (assert_publish_checkout_configuration \
  "$contract_fixture_dir/publisher-checkout-string-persist-credentials.yml" \
  ) 2>"$contract_fixture_dir/publisher-checkout-string-persist-credentials.error"; then
  echo "assert_publish_checkout_configuration must reject string false persisted credentials" >&2
  exit 1
fi
require_contains "$contract_fixture_dir/publisher-checkout-string-persist-credentials.error" \
  'publish checkout must disable persisted credentials with boolean false'
for unexpected_key in repository submodules fetch-depth; do
  cat >"$contract_fixture_dir/publisher-checkout-$unexpected_key.yml" <<EOF
jobs:
  publish:
    steps:
      - uses: actions/checkout@fixture
        with:
          ref: \${{ github.event.repository.default_branch }}
          persist-credentials: false
          $unexpected_key: fixture
EOF
  if (assert_publish_checkout_configuration \
    "$contract_fixture_dir/publisher-checkout-$unexpected_key.yml" \
    ) 2>"$contract_fixture_dir/publisher-checkout-$unexpected_key.error"; then
    echo "assert_publish_checkout_configuration must reject $unexpected_key" >&2
    exit 1
  fi
  require_contains "$contract_fixture_dir/publisher-checkout-$unexpected_key.error" \
    'publish checkout must define exactly ref and persist-credentials'
done
cat >"$contract_fixture_dir/ordered-sequence.txt" <<'EOF'
prefix first second suffix
third
EOF
require_ordered_sequence "$contract_fixture_dir/ordered-sequence.txt" first second third
if (require_ordered_sequence "$contract_fixture_dir/ordered-sequence.txt" second first) 2>/dev/null; then
  echo "require_ordered_sequence must preserve within-line ordering" >&2
  exit 1
fi
cat >"$contract_fixture_dir/branch-return.js" <<'EOF'
if (
  examplePredicate ||
  otherPredicate
) {
  const quoted = "{";
  const message = `template text } {
    ${JSON.stringify({ nested: "}" })}
  `;
  return;
}
EOF
require_branch_return "$contract_fixture_dir/branch-return.js" 'examplePredicate ||'
cat >"$contract_fixture_dir/branch-non-return.js" <<'EOF'
if (examplePredicate) {
  notreturn;
}
EOF
if (require_branch_return "$contract_fixture_dir/branch-non-return.js" examplePredicate) 2>/dev/null; then
  echo "require_branch_return must match return as a complete token" >&2
  exit 1
fi
cat >"$contract_fixture_dir/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${CONTRACT_GH_STATE_FILE:-}" ]]; then
  invocation=0
  [[ ! -f "$CONTRACT_GH_STATE_FILE" ]] || invocation="$(cat "$CONTRACT_GH_STATE_FILE")"
  printf '%s\n' "$((invocation + 1))" >"$CONTRACT_GH_STATE_FILE"
  if (( invocation == 0 )); then
    printf '{invalid-json'
    exit 0
  fi
fi

if [[ "$*" == *'/actions/runs/101' ]]; then
  printf '{"id":101,"workflow_id":501,"repository":{"full_name":"example/FireMUD"},"head_repository":{"full_name":"example/FireMUD"},"run_attempt":%s,"status":"completed","conclusion":"success","html_url":"https://example.test/runtime/101","event":"pull_request","head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","display_title":"Build Runtime Images secure-pr-artifact pr-1 base-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb head-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa merge-cccccccccccccccccccccccccccccccccccccccc mode-required","created_at":"2026-07-24T00:01:00Z"}' "${CONTRACT_CURRENT_SOURCE_ATTEMPT:-2}"
  exit 0
fi
if [[ "$*" == *'/attempts/1/jobs?per_page=100&page=1' ]]; then
  if [[ -n "${CONTRACT_JOB_PAYLOAD:-}" ]]; then
    cat "$CONTRACT_JOB_PAYLOAD"
    exit 0
  fi
  case "$*" in
    *'/actions/runs/201/'*) run=201 ;;
    *'/actions/runs/202/'*) run=202 ;;
    *) exit 1 ;;
  esac
  printf '{"total_count":1,"jobs":[{"id":1001,"run_id":%s,"run_attempt":1,"head_sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","name":"Publish Fixed PR Merge Image Tags","status":"completed","conclusion":"success","steps":[{"name":"Download successful PR image artifacts","number":5,"status":"completed","conclusion":"success"},{"name":"Validate exact PR source and artifact before registry login","number":6,"status":"completed","conclusion":"success"},{"name":"Publish fixed PR image tags","number":8,"status":"completed","conclusion":"success"},{"name":"PR runtime publication receipt v1 source-101-2 publisher-%s-1 artifact-9001 sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd","number":9,"status":"completed","conclusion":"success"}]}]}\n' "$run" "$run"
  exit 0
fi
if [[ "$*" == *'/actions/runs/201/attempts/1' || "$*" == *'/actions/runs/202/attempts/1' ]]; then
  if [[ "$*" == *'/actions/runs/201/attempts/1' ]]; then
    run=201
    event=workflow_run
    title='Publish PR Runtime Images Build Runtime Images secure-pr-artifact pr-1 base-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb head-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa merge-cccccccccccccccccccccccccccccccccccccccc mode-required source-run-101 attempt-2'
  else
    run=202
    event=repository_dispatch
    title='Publish PR Runtime Images source-run-101 attempt-2'
  fi
  printf '{"id":%s,"workflow_id":502,"path":".github/workflows/publish-pr-runtime-images.yml","head_branch":"main","head_sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","run_attempt":1,"name":"Publish PR Runtime Images","event":"%s","status":"completed","conclusion":"success","display_title":"%s","repository":{"full_name":"example/FireMUD"},"head_repository":{"full_name":"example/FireMUD"}}' \
    "$run" "$event" "$title"
  exit 0
fi
if [[ "$*" == *"publish-pr-runtime-images.yml"* ]]; then
  [[ "$*" != *"--paginate"* ]] || { echo 'publisher inventory must be bounded' >&2; exit 1; }
  if [[ -n "${CONTRACT_PUBLISHER_PAYLOAD:-}" ]]; then
    python3 - "$CONTRACT_PUBLISHER_PAYLOAD" "$*" <<'PY_PAGE'
import json
import re
import sys
payload = json.load(open(sys.argv[1]))
event = re.search(r"event=([^&]+)", sys.argv[2]).group(1)
page_number = int(re.search(r"&page=([0-9]+)", sys.argv[2]).group(1))
pages = payload if isinstance(payload, list) else [payload]
page = pages[min(page_number - 1, len(pages) - 1)]
if page.get("fixture_event", "repository_dispatch") != event:
    page = {"total_count": 0, "workflow_runs": []}
else:
    page = {key: value for key, value in page.items() if key != "fixture_event"}
print(json.dumps(page))
PY_PAGE
    exit 0
  fi
  if [[ "$*" == *event=repository_dispatch* ]]; then
    printf '%s' '{"total_count":0,"workflow_runs":[]}'
    exit 0
  fi
  cat <<'JSON'
{"total_count":1,"workflow_runs":[{"id":201,"workflow_id":502,"run_attempt":1,"event":"workflow_run","status":"completed","conclusion":"success","html_url":"https://example.test/publisher/201","display_title":"Publish PR Runtime Images Build Runtime Images secure-pr-artifact pr-1 base-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb head-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa merge-cccccccccccccccccccccccccccccccccccccccc mode-required source-run-101 attempt-2","created_at":"2026-07-24T00:03:00Z"}]}
JSON
else
  if [[ -n "${CONTRACT_SOURCE_PAYLOAD:-}" ]]; then
    cat "$CONTRACT_SOURCE_PAYLOAD"
    exit 0
  fi
  cat <<'JSON'
[{"workflow_runs":[{"id":102,"status":"completed","conclusion":"skipped","html_url":"https://example.test/runtime/102","event":"pull_request","head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","display_title":"Build Runtime Images secure-pr-artifact pr-1 base-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb head-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa merge-cccccccccccccccccccccccccccccccccccccccc mode-metadata","created_at":"2026-07-24T00:02:00Z"}]},{"workflow_runs":[{"id":101,"workflow_id":501,"run_attempt":2,"status":"completed","conclusion":"success","html_url":"https://example.test/runtime/101","event":"pull_request","head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","display_title":"Build Runtime Images secure-pr-artifact pr-1 base-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb head-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa merge-cccccccccccccccccccccccccccccccccccccccc mode-required","created_at":"2026-07-24T00:01:00Z"}]}]
JSON
fi
EOF
chmod +x "$contract_fixture_dir/gh"
PATH="$contract_fixture_dir:$PATH" \
GH_TOKEN=contract-token \
GITHUB_REPOSITORY=example/FireMUD \
HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=5 \
HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
bash "$image_wait_path" "cccccccccccccccccccccccccccccccccccccccc" \
  "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" \
  "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
  >"$contract_fixture_dir/output"
require_contains "$contract_fixture_dir/output" 'Matching runtime-images workflow 101 succeeded'
require_contains "$contract_fixture_dir/output" 'Trusted PR image publisher 201 succeeded'

CONTRACT_GH_STATE_FILE="$contract_fixture_dir/gh-state" \
PATH="$contract_fixture_dir:$PATH" \
GH_TOKEN=contract-token \
GITHUB_REPOSITORY=example/FireMUD \
HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=5 \
HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
bash "$image_wait_path" "cccccccccccccccccccccccccccccccccccccccc" \
  "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" \
  "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
  >"$contract_fixture_dir/retry-output" \
  2>"$contract_fixture_dir/retry-error"
require_contains "$contract_fixture_dir/retry-error" \
  'GitHub API response was empty or invalid while waiting for the runtime-images workflow; retrying.'
require_contains "$contract_fixture_dir/retry-output" 'Matching runtime-images workflow 101 succeeded'
require_contains "$contract_fixture_dir/retry-output" 'Trusted PR image publisher 201 succeeded'

python3 - "$contract_fixture_dir" "$image_wait_path" <<'PY_FIXTURES'
import json
import subprocess
import sys
from pathlib import Path
root = Path(sys.argv[1])
waiter = Path(sys.argv[2]).read_text()
source_parser = waiter.split("read_run_state() {\n  python3 -c '\n", 1)[1].split("\n' \"${wait_mode}\"", 1)[0]
source = {"id": 101, "workflow_id": 501, "run_attempt": 2, "event": "pull_request", "status": "completed", "conclusion": "success", "head_sha": "a" * 40, "html_url": "https://example.test/runtime/101", "display_title": "Build Runtime Images secure-pr-artifact pr-1 base-" + "b" * 40 + " head-" + "a" * 40 + " merge-" + "c" * 40 + " mode-required", "created_at": "2026-07-24T00:01:00Z"}
for created_at in [source["created_at"], "2026-02-31T00:01:00Z", "2026-07-24T00:01:00+00:00", "not-a-date", None]:
    result = subprocess.run([sys.executable, "-c", source_parser, "pull-request", "pr-merge-" + "c" * 40, "c" * 40, "b" * 40, "a" * 40], input=json.dumps({"workflow_runs": [{**source, "created_at": created_at}]}), capture_output=True, text=True)
    if created_at == source["created_at"]:
        assert result.returncode == 0 and result.stdout.rstrip().endswith("\tpull_request\t" + created_at + "\t2\t501")
    else:
        assert result.returncode != 0, "source creation time must be a real UTC timestamp"
def parse_source(run):
    return subprocess.run([sys.executable, "-c", source_parser, "pull-request", "pr-merge-" + "c" * 40, "c" * 40, "b" * 40, "a" * 40], input=json.dumps({"workflow_runs": [run]}), capture_output=True, text=True)

for field, values in {"id": [None, 0, True, "101"], "run_attempt": [None, 0, True, "2"], "status": [None, "", "completed\t"], "html_url": [None, "", "https://example.test/\t101"], "conclusion": [None, "", "success\n"]}.items():
    for value in values:
        assert parse_source({**source, field: value}).returncode != 0, f"malformed source {field} must be rejected"
for name, run in {"missing-url": {key: value for key, value in source.items() if key != "html_url"}, "empty-url": {**source, "html_url": ""}}.items():
    assert parse_source(run).returncode != 0
    (root / (name + ".json")).write_text(json.dumps({"workflow_runs": [run]}))
for conclusion in [None, ""]:
    result = parse_source({**source, "status": "in_progress", "conclusion": conclusion})
    assert result.returncode == 0
    split = subprocess.run(["bash", "-c", "IFS=$'\\t' read -r state id status conclusion url event created attempt workflow; printf '%s\\n' \"$state\" \"$id\" \"$status\" \"$conclusion\" \"$url\" \"$event\" \"$created\" \"$attempt\" \"$workflow\""], input=result.stdout, capture_output=True, text=True)
    assert split.stdout.splitlines() == ["found", "101", "in_progress", "pending", source["html_url"], source["event"], source["created_at"], "2", "501"], "optional conclusion must preserve Bash field positions"

base = {"id": 202, "workflow_id": 502, "run_attempt": 1, "event": "repository_dispatch", "status": "completed", "conclusion": "success", "html_url": "https://example.test/publisher/202", "display_title": "Publish PR Runtime Images source-run-101 attempt-2", "created_at": "2026-07-24T00:04:00Z"}
fixtures = {
    "dispatch-success": base,
    "dispatch-earlier-attempt": {**base, "display_title": "Publish PR Runtime Images source-run-101 attempt-1"},
    "dispatch-legacy": {**base, "display_title": "Publish PR Runtime Images source-run-101"},
    "callback-earlier-attempt": {**base, "event": "workflow_run",
        "display_title": "Publish PR Runtime Images " + source["display_title"] + " source-run-101 attempt-1"},
    "dispatch-wrong-source": {**base, "display_title": "Publish PR Runtime Images source-run-999 attempt-2"},
    "dispatch-wrong-event": {**base, "event": "push"},
    "dispatch-malformed-title": {**base, "display_title": "Publish PR Runtime Images source-run-101 extra"},
    "dispatch-failure": {**base, "conclusion": "failure"},
    "callback-wrong-tuple": {**base, "event": "workflow_run", "display_title": "Publish PR Runtime Images Build Runtime Images secure-pr-artifact pr-1 base-" + "d" * 40 + " head-" + "a" * 40 + " merge-" + "c" * 40 + " mode-required source-run-101 attempt-2"},
}
for name, run in fixtures.items():
    (root / (name + ".json")).write_text(json.dumps({"total_count": 1, "fixture_event": "workflow_run" if name.startswith("callback-") else "repository_dispatch", "workflow_runs": [run]}))
# Matching publication remains discoverable beyond the first page.
many = [{**base, "id": 300 + index, "display_title": f"Publish PR Runtime Images source-run-{500 + index} attempt-2"} for index in range(100)] + [base]
(root / "dispatch-second-page.json").write_text(json.dumps([{"total_count": 101, "workflow_runs": many[:100]}, {"total_count": 101, "workflow_runs": many[100:]}]))
(root / "dispatch-overflow.json").write_text(json.dumps({"total_count": 1001, "workflow_runs": many[:100]}))
(root / "dispatch-incomplete.json").write_text(json.dumps([{"total_count": 101, "workflow_runs": many[:100]}, {"total_count": 101, "workflow_runs": []}]))
(root / "dispatch-unstable.json").write_text(json.dumps([{"total_count": 101, "workflow_runs": many[:100]}, {"total_count": 102, "workflow_runs": many[100:]}]))
(root / "dispatch-outside-window.json").write_text(json.dumps({"total_count": 1, "workflow_runs": [{**base, "created_at": "2026-07-23T00:00:00Z"}]}))
(root / "dispatch-invalid-date.json").write_text(json.dumps({"total_count": 1, "workflow_runs": [{**base, "created_at": "2026-02-31T00:00:00Z"}]}))
PY_FIXTURES
for fixture in missing-url empty-url; do
  if CONTRACT_SOURCE_PAYLOAD="$contract_fixture_dir/$fixture.json" \
    PATH="$contract_fixture_dir:$PATH" GH_TOKEN=contract-token GITHUB_REPOSITORY=example/FireMUD \
    HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
    bash "$image_wait_path" "cccccccccccccccccccccccccccccccccccccccc" \
      "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
      >"$contract_fixture_dir/$fixture.output" 2>"$contract_fixture_dir/$fixture.error"; then
    echo "$fixture unexpectedly verified publication" >&2
    exit 1
  fi
  require_contains "$contract_fixture_dir/$fixture.error" 'GitHub API response was empty or invalid while waiting for the runtime-images workflow; retrying.'
  if [[ -s "$contract_fixture_dir/$fixture.output" ]]; then
    echo "$fixture must not report source or publisher success" >&2
    exit 1
  fi
done
for fixture in dispatch-success dispatch-earlier-attempt dispatch-legacy callback-earlier-attempt dispatch-wrong-source dispatch-wrong-event dispatch-malformed-title dispatch-failure callback-wrong-tuple dispatch-second-page dispatch-overflow dispatch-incomplete dispatch-unstable dispatch-outside-window dispatch-invalid-date; do
  if CONTRACT_PUBLISHER_PAYLOAD="$contract_fixture_dir/$fixture.json" \
    PATH="$contract_fixture_dir:$PATH" GH_TOKEN=contract-token GITHUB_REPOSITORY=example/FireMUD \
    HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=3 HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
    HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS=0 \
    bash "$image_wait_path" "cccccccccccccccccccccccccccccccccccccccc" \
      "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
      >"$contract_fixture_dir/$fixture.output" 2>"$contract_fixture_dir/$fixture.error"; then
    [[ "$fixture" == "dispatch-success" || "$fixture" == "dispatch-second-page" ]] || { echo "$fixture unexpectedly admitted publisher" >&2; exit 1; }
    require_contains "$contract_fixture_dir/$fixture.output" 'Trusted PR image publisher 202 succeeded'
  else
    [[ "$fixture" != "dispatch-success" && "$fixture" != "dispatch-second-page" ]] || { cat "$contract_fixture_dir/$fixture.error" >&2; exit 1; }
    if [[ "$fixture" == "dispatch-failure" ]]; then
      require_contains "$contract_fixture_dir/$fixture.error" 'completed with failure'
    elif [[ "$fixture" == "dispatch-wrong-event" || "$fixture" == "dispatch-overflow" || "$fixture" == "dispatch-incomplete" || "$fixture" == "dispatch-unstable" || "$fixture" == "dispatch-outside-window" || "$fixture" == "dispatch-invalid-date" ]]; then
      require_contains "$contract_fixture_dir/$fixture.error" 'Publisher coverage unavailable'
      if grep -Fq 'No trusted PR image publisher appeared' "$contract_fixture_dir/$fixture.error"; then
        echo 'unavailable coverage must not claim publisher absence' >&2
        exit 1
      fi
    else
      require_contains "$contract_fixture_dir/$fixture.error" 'No trusted PR image publisher appeared'
    fi
  fi
done
if CONTRACT_PUBLISHER_PAYLOAD="$contract_fixture_dir/dispatch-success.json" \
  CONTRACT_CURRENT_SOURCE_ATTEMPT=3 \
  PATH="$contract_fixture_dir:$PATH" GH_TOKEN=contract-token GITHUB_REPOSITORY=example/FireMUD \
  HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=3 HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
  bash "$image_wait_path" "cccccccccccccccccccccccccccccccccccccccc" \
    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
    >"$contract_fixture_dir/stale-current-attempt.output" 2>"$contract_fixture_dir/stale-current-attempt.error"; then
  echo "A superseded producer must not pass even after exact publisher job success" >&2
  exit 1
fi
require_contains "$contract_fixture_dir/stale-current-attempt.error" 'Current runtime-image producer does not match the successful run 101 attempt 2'

for job_proof in skipped missing-step wrong-attempt incomplete; do
  case "$job_proof" in
    skipped) job_filter='.jobs[0].conclusion = "skipped"' ;;
    missing-step) job_filter='.jobs[0].steps |= .[:2]' ;;
    wrong-attempt) job_filter='.jobs[0].run_attempt = 2' ;;
    incomplete) job_filter='.total_count = 2' ;;
  esac
  PATH="$contract_fixture_dir:$PATH" gh api 'repos/example/FireMUD/actions/runs/202/attempts/1/jobs?per_page=100&page=1' |
    jq "$job_filter" >"$contract_fixture_dir/publisher-jobs-$job_proof.json"
  if CONTRACT_PUBLISHER_PAYLOAD="$contract_fixture_dir/dispatch-success.json" \
    CONTRACT_JOB_PAYLOAD="$contract_fixture_dir/publisher-jobs-$job_proof.json" \
    PATH="$contract_fixture_dir:$PATH" GH_TOKEN=contract-token GITHUB_REPOSITORY=example/FireMUD \
    HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=2 HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
    bash "$image_wait_path" "cccccccccccccccccccccccccccccccccccccccc" \
      "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
      >"$contract_fixture_dir/$job_proof.output" 2>"$contract_fixture_dir/$job_proof.error"; then
    echo "Publisher job proof $job_proof unexpectedly admitted workflow success" >&2
    exit 1
  fi
  require_contains "$contract_fixture_dir/$job_proof.error" 'Exact PR image publication and artifact proof is unavailable'
done

assert_job_excludes runtime-images.yml smoke-full 'pull-requests: write'
if (assert_job_excludes runtime-images.yml missing-job 'pull-requests: write') 2>/dev/null; then
  echo "assert_job_excludes must fail when the requested job is absent" >&2
  exit 1
fi

require_contains "$smoke_path" 'const eventBaseSha = context.payload.pull_request.base.sha;'
require_contains "$smoke_path" 'const baseRef = context.payload.pull_request.base.ref;'
require_contains "$smoke_path" 'const eventMergeSha = context.sha;'
require_contains "$smoke_path" 'github.rest.pulls.get({'
require_contains "$smoke_path" 'pullRequest.state !== "open" ||'
require_contains "$smoke_path" 'pullRequest.head.sha !== headSha ||'
require_contains "$smoke_path" 'pullRequest.base.ref !== baseRef'
require_contains "$smoke_path" 'const matchingRuns = [];'
require_contains "$smoke_path" 'let fullSmokeJob = null;'
require_contains "$smoke_path" 'async function failNonTerminalSnapshot(reason) {'
# shellcheck disable=SC2016 # Assert literal JavaScript template syntax.
require_contains "$smoke_path" '`${reason}: ` +'
require_contains "$smoke_path" 'const maxCompletedJobSnapshotRetries = 8;'
require_contains "$smoke_path" 'const remainingBeforeJobLookupMs = timeoutMs - (Date.now() - started);'
require_contains "$smoke_path" 'if (remainingBeforeJobLookupMs <= 0) {'
require_contains "$smoke_path" 'completedJobSnapshotAttempt <= maxCompletedJobSnapshotRetries'
require_contains "$smoke_path" 'run_id: matching.id'
require_contains "$smoke_path" 'completedJobSnapshotAttempt < maxCompletedJobSnapshotRetries'
require_contains "$smoke_path" 'const remainingBeforeSnapshotSleepMs = timeoutMs - (Date.now() - started);'
require_contains "$smoke_path" 'if (remainingBeforeSnapshotSleepMs <= 0) {'
require_contains "$smoke_path" 'Math.min(snapshotSleepMs, remainingBeforeSnapshotSleepMs)'
require_contains "$smoke_path" 'did not expose a terminal PR Full-Stack Smoke job and smoke step '
require_contains "$smoke_path" "Runtime images run \${matching.id} succeeded, but PR Full-Stack Smoke job did not complete successfully:"
require_contains "$smoke_path" 'Stopping obsolete failed full-smoke gate for'
require_contains "$smoke_path" 'pollIteration % pullRequestCheckInterval === 0'
require_contains "$smoke_path" 'Stopping obsolete smoke gate for'
require_ordered_sequence \
  "$smoke_path" \
  'if (resolvedIdentity?.obsolete) {' \
  'return;' \
  'let matching = await findMatchingRuntimeImagesRun('
require_branch_return \
  "$smoke_path" \
  'if (resolvedIdentity?.obsolete) {'
require_contains "$smoke_path" 'mode-required'
require_contains "$smoke_path" 'Build Runtime Images secure-pr-artifact pr-'
require_contains "$smoke_path" 'sourceEvent: "pull_request",'
require_contains "$smoke_path" 'sourceEvent: "repository_dispatch",'
require_contains "$smoke_path" 'event: sourceEvent,'
require_contains "$smoke_path" 'workflowRunQuery.created = `${pullRequestCreatedAt}..*`;'
require_contains "$smoke_path" 'github.paginate.iterator('
require_contains "$smoke_path" 'for await (const response of github.paginate.iterator('
require_contains "$smoke_path" 'run.display_title !== expectedDisplayTitle'
require_contains "$smoke_path" 'const pullRequests = run.pull_requests ?? [];'
require_contains "$smoke_path" 'pullRequests.length > 0 &&'
require_contains "$smoke_path" '!pullRequests.some'
require_contains "$smoke_path" 'pullRequest.head?.sha === identity.headSha'
require_contains "$smoke_path" 'matchingRuns.push({ run, createdAt });'
require_contains "$smoke_path" 'workflowRunQuery.head_sha = identity.headSha;'
require_contains "$smoke_path" 'matching.conclusion === "cancelled"'
require_contains "$smoke_path" 'waiting for an exact repository_dispatch replacement'
require_contains "$smoke_path" 'const cancelledReplacementTimeoutMs = 5 * 60 * 1000;'
require_contains "$smoke_path" 'Cancelled runtime-images run ${cancelledRunId} has no exact repository_dispatch replacement '
require_contains "$smoke_path" 'github.rest.actions.listJobsForWorkflowRun'
require_contains "$smoke_path" 'job.name === "PR Full-Stack Smoke"'
require_contains "$smoke_path" 'step.name === "Run credential-free full-stack smoke"'
require_contains "$smoke_path" 'fullSmokeJob?.status === "completed"'
require_contains "$smoke_path" 'fullSmokeJob?.conclusion !== "success"'
require_contains "$smoke_path" 'fullSmokeStep?.status === "completed"'
require_contains "$smoke_path" 'fullSmokeStep?.conclusion !== "success"'
require_contains "$smoke_path" 'credential-free full-stack smoke step did not pass'
require_ordered_sequence \
  "$smoke_path" \
  'if (fullSmokeJob?.status === "completed" && !attemptIdentityPending) {' \
  'if (fullSmokeJob?.conclusion !== "success") {' \
  'PR Full-Stack Smoke job did not complete successfully:'
# shellcheck disable=SC2016 # Assert literal GitHub expression syntax.
require_contains "$smoke_path" 'SMOKE_GATE_REQUIRED: ${{ github.event.action != '\''edited'\'' || github.event.changes.base.ref != null }}'
# shellcheck disable=SC2016 # Assert literal GitHub expression syntax.
require_contains "$smoke_path" 'CHANGES_RESULT: ${{ needs.changes.result }}'
# shellcheck disable=SC2016 # Assert literal shell source.
require_contains "$smoke_path" 'echo "required=$SMOKE_GATE_REQUIRED" >> "$GITHUB_OUTPUT"'
# shellcheck disable=SC2016 # Assert literal shell source.
require_contains "$smoke_path" 'if [ "$SMOKE_GATE_REQUIRED" = "true" ] && [ "$CHANGES_RESULT" = "success" ]; then'
# shellcheck disable=SC2016 # Assert literal shell source.
require_contains "$smoke_path" 'echo "execute=true" >> "$GITHUB_OUTPUT"'
# shellcheck disable=SC2016 # Assert literal shell source.
require_contains "$smoke_path" 'echo "execute=false" >> "$GITHUB_OUTPUT"'
# shellcheck disable=SC2016 # Assert literal GitHub expression syntax.
require_contains "$smoke_path" 'if: ${{ steps.smoke_gate_context.outputs.required == '\''true'\'' && steps.smoke_gate_context.outputs.execute != '\''true'\'' }}'
# shellcheck disable=SC2016 # Assert literal GitHub expression syntax.
if grep -Fq 'echo "required=${{' "$smoke_path" || grep -Fq 'echo "execute=${{' "$smoke_path"; then
  echo "smoke_gate_context must pass expressions through step env" >&2
  exit 1
fi
require_contains "$smoke_path" 'Stopping obsolete completed smoke gate for'
require_contains "$smoke_path" 'Stopping obsolete stale-snapshot smoke gate for'
require_ordered_sequence \
  "$smoke_path" \
  'github.rest.actions.listJobsForWorkflowRun,' \
  'if (await isCurrentPullRequestObsolete("Stopping obsolete completed smoke gate for")) {'
require_branch_return "$smoke_path" 'if (await isCurrentPullRequestObsolete("Stopping obsolete completed smoke gate for")) {'
require_ordered_sequence \
  "$smoke_path" \
  'async function failNonTerminalSnapshot(reason) {' \
  'if (await isCurrentPullRequestObsolete("Stopping obsolete stale-snapshot smoke gate for")) {' \
  'core.setFailed('
require_branch_return "$smoke_path" 'if (await isCurrentPullRequestObsolete("Stopping obsolete stale-snapshot smoke gate for")) {'
require_ordered_sequence \
  "$smoke_path" \
  'if (remainingBeforeJobLookupMs <= 0) {' \
  'await failNonTerminalSnapshot("before the workflow deadline");'
require_ordered_sequence \
  "$smoke_path" \
  'if (remainingBeforeSnapshotSleepMs <= 0) {' \
  'await failNonTerminalSnapshot("before the workflow deadline");'
require_ordered_sequence \
  "$smoke_path" \
  'const snapshotRetrySummary = maxCompletedJobSnapshotRetries === 0' \
  'await failNonTerminalSnapshot(snapshotRetrySummary);'
if grep -Fq 'const matching = runs.find((run) => run.head_sha === headSha);' "$smoke_path"; then
  echo "Smoke Gate must not accept a runtime-images run by head SHA alone" >&2
  exit 1
fi

python3 - "$smoke_path" "$runtime_images_path" <<'PY'
import re
import sys
from pathlib import Path

import yaml

if len(sys.argv) != 3:
    raise SystemExit("smoke/runtime-images parity contract requires two workflow paths")
smoke_path, runtime_images_path = map(Path, sys.argv[1:])
smoke = smoke_path.read_text(encoding="utf-8")
runtime_images = runtime_images_path.read_text(encoding="utf-8")


def quoted_entries(source, start_marker, end_marker):
    start = source.index(start_marker) + len(start_marker)
    end = source.index(end_marker, start)
    return re.findall(r'["\']([^"\']+)["\']', source[start:end])


def pull_request_paths(source, label):
    try:
        workflow = yaml.load(source, Loader=yaml.BaseLoader)
    except yaml.YAMLError as exc:
        raise AssertionError(f"{label} is not valid YAML: {exc}") from exc
    if not isinstance(workflow, dict):
        raise AssertionError(f"{label} must contain a workflow mapping")
    triggers = workflow.get("on")
    if not isinstance(triggers, dict) or "pull_request" not in triggers:
        raise AssertionError(f"{label} must define on.pull_request")
    pull_request = triggers["pull_request"]
    if not isinstance(pull_request, dict):
        raise AssertionError(f"{label} on.pull_request must be a mapping")
    paths = pull_request.get("paths")
    if not isinstance(paths, list) or not paths or not all(isinstance(path, str) for path in paths):
        raise AssertionError(f"{label} on.pull_request.paths must be a non-empty string list")
    return set(paths)


full_prefixes = quoted_entries(
    smoke,
    "const fullRelevantPrefixes = [",
    "];",
)
full_files = quoted_entries(
    smoke,
    "const fullRelevantFiles = new Set([",
    "]);",
)

lookup_start_marker = "const producerJobs = jobs.filter("
lookup_end_marker = "fullSmokeJob = producerJobs[0] ?? null;"
try:
    lookup_start = smoke.index(lookup_start_marker)
    lookup_end = smoke.index(lookup_end_marker, lookup_start) + len(lookup_end_marker)
except ValueError as exc:
    raise SystemExit("smoke.yml must define the fullSmokeJob lookup block") from exc

lookup = smoke[lookup_start:lookup_end]
job_name_literals = re.findall(
    r'job[.]name\s*===\s*["\']([^"\']+)["\']',
    lookup,
)
if job_name_literals != ["PR Full-Stack Smoke"]:
    raise SystemExit(
        "smoke.yml fullSmokeJob lookup must use exactly one PR Full-Stack Smoke literal: "
        + repr(job_name_literals)
    )

if "job.name === canonicalSmokeJobName" not in lookup:
    raise SystemExit("smoke.yml producer filter must include the affirmative canonical name")
if not re.search(
    r'if \(producerJobs[.]length > 1\)\s*\{\s*'
    r'await failNonTerminalSnapshot\("with ambiguous producer jobs"\);\s*return;\s*\}',
    lookup,
):
    raise SystemExit("smoke.yml must reject duplicate producers before selecting the sole match")

try:
    runtime_workflow = yaml.load(runtime_images, Loader=yaml.BaseLoader)
    runtime_job_name = runtime_workflow["jobs"]["pr-local-smoke"]["name"]
except (KeyError, TypeError, yaml.YAMLError) as exc:
    raise SystemExit(
        "runtime-images.yml must define the pr-local-smoke job name used by Smoke Gate"
    ) from exc
runtime_name_template = "PR Full-Stack Smoke (runtime-required=${{ needs.image-meta.outputs.runtime_smoke_required }})"
canonical_name = re.search(r'const canonicalSmokeJobName = "([^"\n]+)";', smoke)
if runtime_job_name != runtime_name_template or canonical_name is None or canonical_name[1] != runtime_name_template.replace(
    "${{ needs.image-meta.outputs.runtime_smoke_required }}", "true"
):
    raise SystemExit(
        "smoke.yml full-stack PR job lookup does not match runtime-images.yml: "
        f"canonical={canonical_name[1] if canonical_name else None!r}, runtime={runtime_job_name!r}"
    )

runtime_paths = pull_request_paths(runtime_images, "runtime-images.yml")

# Boot JARs use these helpers; each selector must retain both dependencies.
build_dependencies = {
    "dev-tools/validation/run-gradle-with-dependency-recovery.sh",
    "dev-tools/validation/run-locked-gradle.sh",
    "dev-tools/validation/gradle-run-supervisor.py",
}
selectors = {"runtime trigger": runtime_paths, "smoke full scope": set(full_files)}
marker = "const runtimeFiles = new Set(["
positions = [match.end() for match in re.finditer(re.escape(marker), runtime_images)]
if len(positions) != 2:
    raise SystemExit("runtime-images.yml must retain both runtime file selectors")
for index, position in enumerate(positions):
    end = runtime_images.index("]);", position)
    selectors[f"runtime selector {index + 1}"] = set(
        re.findall(r'["\']([^"\']+)["\']', runtime_images[position:end])
    )
for label, paths in selectors.items():
    missing = build_dependencies - paths
    if missing:
        raise SystemExit(f"{label} misses Gradle source-build dependencies: {sorted(missing)}")

runtime_images_fixture = """on:
  pull_request:
    paths:
      - 'services/example/**'
      - 'literal  push: value'
  push:
    branches: [main]
"""
expected_runtime_fixture_paths = {
    "services/example/**",
    "literal  push: value",
}
if pull_request_paths(runtime_images_fixture, "runtime fixture") != expected_runtime_fixture_paths:
    raise SystemExit("runtime fixture paths were parsed incorrectly")

for invalid_source, expected_message in (
    ("on:\n  push:\n    branches: [main]\n", "must define on.pull_request"),
    ("on:\n  pull_request:\n    paths:\n      invalid: value\n", "must be a non-empty string list"),
):
    try:
        pull_request_paths(invalid_source, "invalid runtime fixture")
    except AssertionError as exc:
        if expected_message not in str(exc):
            raise SystemExit(
                f"invalid runtime fixture raised the wrong message: {exc}"
            ) from exc
    else:
        raise SystemExit(f"invalid runtime fixture was accepted: {expected_message}")


def path_pattern_covers_prefix(pattern, prefix):
    if pattern.startswith("!"):
        return False
    if pattern in {prefix, f"{prefix}**"}:
        return True
    return pattern.endswith("**") and prefix.startswith(pattern.removesuffix("**"))


if not path_pattern_covers_prefix("services/**", "services/common-library/"):
    raise SystemExit("services/** must cover services/common-library/")
if path_pattern_covers_prefix("web-client/**", "services/common-library/"):
    raise SystemExit("web-client/** must not cover services/common-library/")

missing_prefixes = [
    prefix
    for prefix in full_prefixes
    if not any(path_pattern_covers_prefix(pattern, prefix) for pattern in runtime_paths)
]
missing_files = [
    file
    for file in full_files
    if not any(
        pattern == file
        or (
            not pattern.startswith("!")
            and pattern.endswith("/**")
            and file.startswith(pattern.removesuffix("**"))
        )
        for pattern in runtime_paths
    )
]

if missing_prefixes or missing_files:
    details = []
    if missing_prefixes:
        details.append(f"prefixes={missing_prefixes}")
    if missing_files:
        details.append(f"files={missing_files}")
    raise SystemExit(
        "runtime-images.yml must trigger for every smoke.yml full-smoke declaration: "
        + ", ".join(details)
    )

print("smoke/runtime-images full-scope parity checks passed")
PY

python3 - "$smoke_path" <<'PY'
import json
import subprocess
import sys
from pathlib import Path

import yaml

workflow = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
scope_script = next(
    step["with"]["script"]
    for step in workflow["jobs"]["changes"]["steps"]
    if step.get("name") == "Compute smoke scope"
)


def run_scope(changed_paths):
    node_source = """
const scopeScript = %s;
const changedFiles = %s;
const outputs = {};
const github = {
  paginate: async () => changedFiles,
  rest: { pulls: { listFiles: async () => undefined } },
};
const context = {
  repo: { owner: "example", repo: "firemud" },
  payload: { pull_request: { number: 1, changed_files: changedFiles.length } },
};
const core = {
  warning: () => {},
  setOutput: (name, value) => { outputs[name] = value; },
};
const execute = new Function(
  "github",
  "context",
  "core",
  "return (async () => {\\n" + scopeScript + "\\n})()"
);
await execute(github, context, core);
process.stdout.write(JSON.stringify(outputs));
""" % (
        json.dumps(scope_script),
        json.dumps([{"filename": path} for path in changed_paths]),
    )
    result = subprocess.run(
        ["node", "--input-type=module", "-e", node_source],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if result.returncode != 0:
        raise SystemExit(result.stderr)
    return json.loads(result.stdout)


controller_service = "services/hosted-environment-identity-controller/src/Main.java"
controller_smoke_script = "dev-tools/hosted/controller/smoke-paused-controller-image.sh"
runtime_service = "services/account-service/src/Main.java"

assert run_scope([controller_service]) == {"run_smoke_full": "false"}
assert run_scope([controller_smoke_script]) == {"run_smoke_full": "false"}
# Source-built MinIO helpers must require runtime proof even when no Dockerfile changes.
root = Path(sys.argv[1]).resolve().parents[2]
preview_resolver = (root / "dev-tools/hosted/preview/resolve-preview-image-tag.sh").read_text()
preview_predicate = preview_resolver[preview_resolver.index("runtime_relevant() {"):preview_resolver.index('\nif [[ -n "${pr_number}"')]
for helper in ("dev-tools/minio/base_image_refs.py", "dev-tools/minio/build-and-smoke-images.sh"):
    assert run_scope([helper]) == {"run_smoke_full": "true"}, helper
    preview = subprocess.run(["bash", "-c", preview_predicate + '\nruntime_relevant "$1"', "preview-scope", helper], check=False)
    assert preview.returncode == 0, helper
assert subprocess.run(["bash", "-c", preview_predicate + '\nruntime_relevant "$1"', "preview-scope", "design/example.md"], check=False).returncode != 0
assert run_scope([runtime_service]) == {"run_smoke_full": "true"}
assert run_scope([controller_service, runtime_service]) == {"run_smoke_full": "true"}
PY

smoke_gate_script="$contract_fixture_dir/smoke-gate.js"
python3 - "$smoke_path" "$smoke_gate_script" <<'PY'
import sys
import textwrap
from pathlib import Path

workflow_path, script_path = map(Path, sys.argv[1:])
lines = workflow_path.read_text(encoding="utf-8").splitlines()
anchor = next(
    index for index, line in enumerate(lines)
    if line.strip() == "- name: Track full-stack smoke result from Build Runtime Images"
)
start = next(index for index in range(anchor, len(lines)) if lines[index].strip() == "script: |")
base_indent = len(lines[start]) - len(lines[start].lstrip())
body = []
for line in lines[start + 1 :]:
    if line.strip() and len(line) - len(line.lstrip()) <= base_indent:
        break
    body.append(line)
script_path.write_text(textwrap.dedent("\n".join(body)) + "\n", encoding="utf-8")
PY

# Exercise the checked-in retry/cache functions without live GitHub requests.
node - "$smoke_gate_script" <<'NODE'
const assert = require("node:assert/strict");
const fs = require("node:fs");
const source = fs.readFileSync(process.argv[2], "utf8").split("function isObsoletePullRequest(")[0];
const context = { repo: { owner: "owner", repo: "repo" }, sha: "c".repeat(40), payload: {
  pull_request: { number: 42, head: { sha: "a".repeat(40) }, base: { ref: "develop", sha: "b".repeat(40) } },
} };
let now = 100000;
const delays = [];
const realNow = Date.now;
const realTimeout = global.setTimeout;
Date.now = () => now;
global.setTimeout = (callback, delay) => { delays.push(delay); now += delay; queueMicrotask(callback); };
const make = (getCommit = async ({ ref }) => ({ data: { sha: ref, parents: [] } })) =>
  new Function("github", "context", "core", `${source}; return { withTransientGitHubRetry, immutableCommit };`)(
    { rest: { repos: { getCommit } } }, context, { warning: () => {} }
  );
const quota = (reset, remaining = "0") => ({ status: 403, response: { headers: {
  "x-ratelimit-remaining": remaining, "x-ratelimit-reset": reset,
} } });
(async () => {
  let calls = 0;
  const inside = make();
  const error = quota(String(Math.floor(now / 1000) + 60));
  assert.equal(await inside.withTransientGitHubRetry("quota", async () => {
    if (++calls === 1) throw error;
    return "recovered";
  }), "recovered");
  assert.equal(calls, 2);
  assert.deepEqual(delays, [61000], "confirmed exhaustion must wait until after reset");
  for (const error of [quota(String(Math.floor(now / 1000) + 5400)), quota("bad"), quota("999", "1"), { status: 403 }]) {
    const helper = make();
    let attempts = 0;
    const waits = delays.length;
    await assert.rejects(helper.withTransientGitHubRetry("refused", async () => { attempts++; throw error; }), (caught) => caught.cause === error);
    assert.equal(attempts, 1, "invalid/nonquota or beyond-budget 403 must fail immediately");
    assert.equal(delays.length, waits, "refused 403 must not sleep or hammer the API");
  }
  const requested = [];
  const cache = make(async ({ ref }) => { requested.push(ref); return { data: { sha: ref, parents: [] } }; });
  const first = "d".repeat(40), second = "e".repeat(40);
  assert.equal(await cache.immutableCommit(first, "first"), await cache.immutableCommit(first, "cached"));
  await cache.immutableCommit(second, "changed OID");
  assert.deepEqual(requested, [first, second], "only immutable evidence for the exact OID may be reused");
  let badCalls = 0;
  const bad = make(async () => { badCalls++; return { data: { sha: second, parents: [] } }; });
  for (let i = 0; i < 2; i++) await assert.rejects(bad.immutableCommit(first, "wrong OID"), /different commit OID/);
  assert.equal(badCalls, 2, "invalid immutable evidence must not enter the cache");
  const cancellationContext = structuredClone(context);
  cancellationContext.payload.pull_request.head.repo = { full_name: "owner/repo" };
  const cancellationSource = {
    id: 901, run_attempt: 1, workflow_id: 123, path: ".github/workflows/runtime-images.yml",
    repository: { full_name: "owner/repo" }, head_repository: { full_name: "owner/repo" },
    event: "pull_request", status: "completed", conclusion: "cancelled",
    display_title: "exact cancellation comparison", head_sha: "a".repeat(40),
  };
  const cancellationIdentity = { headSha: cancellationSource.head_sha };
  function cancellationFixture() {
    const calls = { attempt: 0, latest: 0, workflow: 0, jobs: 0 };
    const state = { source: structuredClone(cancellationSource), latest: structuredClone(cancellationSource),
      failJobs: false, incompleteJobs: false };
    let jobNames;
    const api = {
      request: async () => { calls.attempt++; return { data: structuredClone(state.source) }; },
      rest: { actions: {
        getWorkflowRun: async () => { calls.latest++; return { data: structuredClone(state.latest) }; },
        getWorkflow: async () => { calls.workflow++; return { data: {
          id: state.source.workflow_id, path: state.source.path, name: "Build Runtime Images",
        } }; },
      } },
      paginate: async () => {
        calls.jobs++;
        if (state.failJobs) throw { status: 404, message: "controlled unavailable job proof" };
        return [...jobNames].map((name, index) => ({
          id: index + 1, run_id: state.source.id, name, status: "completed", conclusion: "cancelled",
          steps: state.incompleteJobs ? [{ name: "executed step" }] : [],
        }));
      },
    };
    const helpers = new Function("github", "context", "core",
      `${source}; return { isPreExecutionCancelledAttempt, preExecutionCancelledJobNames };`)(
      api, cancellationContext, { warning: () => {} });
    jobNames = helpers.preExecutionCancelledJobNames;
    return { calls, state, check: (run = state.source, title = cancellationSource.display_title,
      identity = cancellationIdentity) => helpers.isPreExecutionCancelledAttempt(run, title, identity) };
  }
  const cancellation = cancellationFixture();
  assert.equal(await cancellation.check({ ...cancellationSource, event: "repository_dispatch" }), false);
  assert.deepEqual(cancellation.calls, { attempt: 0, latest: 0, workflow: 0, jobs: 0 },
    "non-PR sources must short-circuit before any API request");
  assert.equal(await cancellation.check(), true);
  assert.deepEqual(cancellation.calls, { attempt: 2, latest: 2, workflow: 1, jobs: 1 },
    "first proof must retain both exact attempt reads, latest guards, workflow and full jobs proof");
  assert.equal(await cancellation.check(), true);
  assert.deepEqual(cancellation.calls, { attempt: 2, latest: 3, workflow: 1, jobs: 1 },
    "positive immutable proof may be reused only with a fresh latest-attempt guard");
  cancellation.state.latest.run_attempt = 2;
  assert.equal(await cancellation.check(), false, "warm cache must refuse a newer latest attempt");
  cancellation.state.latest = { ...cancellationSource, status: "in_progress", conclusion: null };
  assert.equal(await cancellation.check(), false, "warm cache must refuse a nonterminal latest run");
  cancellation.state.latest = { ...cancellationSource, conclusion: "success" };
  assert.equal(await cancellation.check(), false, "warm cache must require current cancellation");
  cancellation.state.source.run_attempt = 2;
  cancellation.state.latest = { ...cancellation.state.source };
  const beforeAttemptChange = cancellation.calls.attempt;
  assert.equal(await cancellation.check(), true);
  assert.equal(cancellation.calls.attempt, beforeAttemptChange + 2,
    "a changed exact attempt requires complete immutable proof again");
  const beforeTitleChange = cancellation.calls.attempt;
  assert.equal(await cancellation.check(undefined, "different expected title"), false);
  assert.equal(cancellation.calls.attempt, beforeTitleChange + 1,
    "changed comparison title must not reuse an earlier proof");
  const beforeHeadChange = cancellation.calls.attempt;
  assert.equal(await cancellation.check(undefined, undefined, { headSha: "b".repeat(40) }), false);
  assert.equal(cancellation.calls.attempt, beforeHeadChange + 1,
    "changed comparison head must not reuse an earlier proof");
  for (const invalid of ["failJobs", "incompleteJobs"]) {
    const rejected = cancellationFixture();
    rejected.state[invalid] = true;
    assert.equal(await rejected.check(), false);
    assert.equal(await rejected.check(), false);
    assert.equal(rejected.calls.jobs, 2, "failed/incomplete job proof must not enter the cache");
    rejected.state[invalid] = false;
    assert.equal(await rejected.check(), true, "recovered proof must be fetched and qualified anew");
  }
  console.log("Smoke quota deadline, immutable OID and cancelled-attempt cache contracts passed");
})().catch((error) => { console.error(error); process.exitCode = 1; }).finally(() => {
  Date.now = realNow;
  global.setTimeout = realTimeout;
});
NODE

node - "$smoke_gate_script" "$runtime_images_path" <<'NODE'
const fs = require("node:fs");
const script = fs.readFileSync(process.argv[2], "utf8");
const runtimeImagesPath = process.argv[3];
const headSha = "a".repeat(40);
const baseSha = "b".repeat(40);
const mergeSha = "c".repeat(40);
let currentHeadSha = headSha;
let currentBaseRef = "develop";
let currentBaseSha = baseSha;
let liveBaseSha = baseSha;
let currentMergeSha = mergeSha;
let currentPullRequestState = "open";
let eventMergeParents = [{ sha: baseSha }, { sha: headSha }];
let currentMergeParents = [{ sha: baseSha }, { sha: headSha }];
let availableRuns = [];
let requestedCommitRefs = [];
let currentBaseLookups = 0;
let fakeNow = 1000;
let onSmokeSleep = null;
Date.now = () => fakeNow;
global.setTimeout = (callback, milliseconds, ...args) => {
  fakeNow += Number(milliseconds);
  onSmokeSleep?.(Number(milliseconds));
  queueMicrotask(() => callback(...args));
  return 0;
};

function runtimeTitle(base, head, merge) {
  return `Build Runtime Images secure-pr-artifact pr-42 base-${base} head-${head} merge-${merge} mode-required`;
}

function runtimeRun(id, title) {
  return {
    id,
    event: "pull_request",
    head_sha: headSha,
    display_title: title,
    status: "completed",
    conclusion: "success",
    created_at: "2026-09-20T00:00:00Z",
    pull_requests: [],
  };
}

function setExactEventRun(id = 202) {
  availableRuns = [runtimeRun(id, runtimeTitle(baseSha, headSha, mergeSha))];
}

function dispatchRun(id, base, head, merge) {
  return {
    ...runtimeRun(id, runtimeTitle(base, head, merge)),
    event: "repository_dispatch",
    run_attempt: 1,
    head_sha: base,
    head_branch: "develop",
    workflow_id: 7,
    path: id === 602 ? ".github/workflows/runtime-images.yml@develop" : ".github/workflows/runtime-images.yml",
    repository: {full_name: "owner/repo"},
    head_repository: {full_name: "owner/repo"},
  };
}

function setOnlyNonExactRuns() {
  availableRuns = [
    runtimeRun(301, runtimeTitle(currentBaseSha, headSha, currentMergeSha)),
    runtimeRun(302, `Build Runtime Images secure-pr-artifact pr-42 head-${headSha} mode-required`),
  ];
}

const context = {
  repo: { owner: "owner", repo: "repo" },
  sha: mergeSha,
  payload: {
    repository: {full_name: "owner/repo", default_branch: "develop"},
    pull_request: {
      number: 42,
      created_at: "2026-09-01T00:00:00Z",
      head: { sha: headSha },
      base: { sha: baseSha, ref: "develop" },
    },
  },
};
const listWorkflowRuns = async () => undefined;
const listJobsForWorkflowRun = async () => undefined;
let smokeStepConclusion = "skipped";
const workflowRunQueries = [];
const jobQueries = [];
let repositoryDispatchQueryCount = 0;
let onRepositoryDispatchQuery = null;
const github = {
  request: async (route, {run_id, attempt_number}) => {
    if (route !== "GET /repos/{owner}/{repo}/actions/runs/{run_id}/attempts/{attempt_number}") throw new Error("unexpected exact run lookup");
    const result = availableRuns.find(row => row.id === run_id && row.run_attempt === attempt_number);
    if (!result) throw new Error("missing exact run attempt");
    return {data: result};
  },
  rest: {
    pulls: {
      get: async () => ({
        data: {
          state: currentPullRequestState,
          head: { sha: currentHeadSha },
          base: { sha: currentBaseSha, ref: currentBaseRef },
          merge_commit_sha: currentMergeSha,
        },
      }),
    },
    git: {
      getRef: async () => {
        currentBaseLookups += 1;
        return { data: { object: { sha: liveBaseSha } } };
      },
    },
    repos: {
      getContent: async ({path, ref}) => {
        if (path !== ".github/workflows/runtime-images.yml" || !availableRuns.some(row => row.event === "repository_dispatch" && row.head_sha === ref)) {
          throw new Error("typed runtime proof must pin its executed workflow definition");
        }
        const bytes = fs.readFileSync(runtimeImagesPath);
        return {data: {type: "file", path, size: bytes.length, encoding: "base64", content: bytes.toString("base64")}};
      },
      getCommit: async ({ ref }) => {
        requestedCommitRefs.push(ref);
        return {
          data: {
            sha: ref,
            parents: ref === currentMergeSha ? currentMergeParents : eventMergeParents,
          },
        };
      },
    },
    actions: { listWorkflowRuns, listJobsForWorkflowRun,
      getWorkflow: async ({workflow_id}) => ({data: {id: workflow_id, path: ".github/workflows/runtime-images.yml", name: "Build Runtime Images"}}),
      getWorkflowRun: async ({run_id}) => ({data: availableRuns.find(run => run.id === run_id)}),
    },
  },
  paginate: async (method, input) => {
    if (method === listJobsForWorkflowRun ||
        method === "GET /repos/{owner}/{repo}/actions/runs/{run_id}/attempts/{attempt_number}/jobs") {
      jobQueries.push(input);
      const run = availableRuns.find(row => row.id === input.run_id);
      const typed = run?.event === "repository_dispatch";
      return [{
        id: 881,
        name: typed ? "PR Full-Stack Smoke (runtime-required=true)" : "PR Full-Stack Smoke",
        check_run_url: "https://api.github.com/repos/owner/repo/check-runs/881",
        run_id: run?.id,
        run_attempt: run?.run_attempt,
        head_sha: run?.head_sha,
        status: "completed",
        conclusion: "success",
        steps: [{
          name: "Run credential-free full-stack smoke",
          status: "completed",
          conclusion: smokeStepConclusion,
        }, ...(typed ? [
          {name: "Known canonical transition PLAY failure with successful authenticated cleanup", status: "completed", conclusion: "skipped"},
          {name: "Stop smoke stack", status: "completed", conclusion: "success"},
          {name: "Require successful runtime smoke and authenticated cleanup", status: "completed", conclusion: "success"},
        ] : [])],
      }];
    }
    throw new Error("unexpected paginated method");
  },
};
github.paginate.iterator = (method, input) => {
  if (method !== listWorkflowRuns) {
    throw new Error("unexpected workflow-run iterator method");
  }
  workflowRunQueries.push(input);
  if (input.event === "repository_dispatch") {
    repositoryDispatchQueryCount += 1;
    onRepositoryDispatchQuery?.(repositoryDispatchQueryCount);
  }
  return (async function* workflowRunPages() {
    yield { data: availableRuns };
  })();
};
const run = new Function("github", "context", "core", `return (async () => {\n${script}\n})()`);
async function check(conclusion, expectedFailure, expectedFailureText) {
  smokeStepConclusion = conclusion;
  const failures = [];
  const core = {
    info: () => {},
    warning: () => {},
    setFailed: (message) => failures.push(message),
  };
  await run(github, context, core);
  if (expectedFailure) {
    if (failures.length !== 1 || !failures[0].includes(expectedFailureText)) {
      throw new Error(`Smoke Gate failure did not include ${expectedFailureText}: ${JSON.stringify(failures)}`);
    }
  } else if (failures.length !== 0) {
    throw new Error(`successful smoke step was rejected: ${JSON.stringify(failures)}`);
  }
}
async function checkPartialPaginationRetry() {
  const originalIterator = github.paginate.iterator;
  const stale = {
    ...runtimeRun(99, runtimeTitle(baseSha, headSha, mergeSha)),
    status: "in_progress",
    conclusion: null,
  };
  const fresh = { ...stale, status: "completed", conclusion: "success" };
  let attempts = 0;
  const jobsBefore = jobQueries.length;
  github.paginate.iterator = (method, input) => {
    if (method !== listWorkflowRuns || input.event !== "pull_request") {
      return originalIterator(method, input);
    }
    workflowRunQueries.push(input);
    return (async function* partialPages() {
      attempts++;
      yield { data: [attempts === 1 ? stale : fresh] };
      if (attempts === 1) throw { status: 503, message: "controlled pagination interruption" };
    })();
  };
  try {
    await check("success", false);
    if (attempts !== 2 || jobQueries.length !== jobsBefore + 1 || jobQueries.at(-1).run_id !== 99) {
      throw new Error("partial pagination retry must discard the stale candidate snapshot without another poll");
    }
  } finally {
    github.paginate.iterator = originalIterator;
  }
}
async function checkIdentityQuotaFailures() {
  const originalGet = github.rest.pulls.get;
  const originalCommit = github.rest.repos.getCommit;
  const originalSleep = onSmokeSleep;
  const core = { info: () => {}, warning: () => {}, setFailed: (message) => { throw new Error(message); } };
  setExactEventRun(100);
  smokeStepConclusion = "success";
  try {
    for (const target of ["pull", "commit"]) {
      for (const headers of [
        { "x-ratelimit-remaining": "0", "x-ratelimit-reset": String(Math.floor(fakeNow / 1000) + 5400) },
        { "x-ratelimit-remaining": "1", "x-ratelimit-reset": String(Math.floor(fakeNow / 1000) + 60) },
      ]) {
        let calls = 0, sleeps = 0;
        const error = { status: 403, response: { headers }, message: "quota or permission refusal" };
        const refused = async () => { calls++; throw error; };
        github.rest.pulls.get = target === "pull" ? refused : originalGet;
        github.rest.repos.getCommit = target === "commit" ? refused : originalCommit;
        onSmokeSleep = () => { sleeps++; };
        const queries = workflowRunQueries.length;
        try {
          await run(github, context, core);
          throw new Error("terminal identity refusal was swallowed");
        } catch (caught) {
          if (caught.cause !== error) throw caught;
        }
        if (calls !== 1 || sleeps !== 0 || workflowRunQueries.length !== queries) throw new Error("terminal identity refusal must stop the complete gate without sleep or producer queries");
      }
    }
    let calls = 0;
    const reset = Math.floor(fakeNow / 1000) + 60;
    const delays = [];
    github.rest.pulls.get = async (...args) => {
      if (++calls === 1) throw { status: 403, response: { headers: { "x-ratelimit-remaining": "0", "x-ratelimit-reset": String(reset) } } };
      return originalGet(...args);
    };
    github.rest.repos.getCommit = originalCommit;
    onSmokeSleep = (delay) => { delays.push(delay); };
    const jobs = jobQueries.length;
    await run(github, context, core);
    if (delays.length !== 1 || delays[0] < 60000 || jobQueries.length !== jobs + 1 || jobQueries.at(-1).run_id !== 100) throw new Error("within-budget identity quota reset must resume the same exact producer proof");
  } finally {
    github.rest.pulls.get = originalGet;
    github.rest.repos.getCommit = originalCommit;
    onSmokeSleep = originalSleep;
  }
}

async function checkSparseReplacementUsesOverallBudget() {
  const priorRuns = availableRuns, priorHook = onRepositoryDispatchQuery;
  const event = {...runtimeRun(610, runtimeTitle(baseSha, headSha, mergeSha)), conclusion: "cancelled"};
  const replacement = {...dispatchRun(611, baseSha, headSha, mergeSha), status: null, conclusion: null};
  const start = repositoryDispatchQueryCount;
  let listedAt = null, runningAt = null, completedAt = null;
  const jobs = jobQueries.length;
  availableRuns = [event];
  onRepositoryDispatchQuery = (count) => {
    if (count === start + 2) {
      listedAt = fakeNow;
      availableRuns = [event, replacement];
    } else if (listedAt !== null && runningAt === null && fakeNow > listedAt) {
      runningAt = fakeNow;
      replacement.status = "in_progress";
    } else if (runningAt !== null && completedAt === null && fakeNow - runningAt >= 150000) {
      completedAt = fakeNow;
      replacement.status = "completed";
      replacement.conclusion = "success";
    }
  };
  try {
    await check("success", false);
    if (listedAt === null || runningAt === null || completedAt === null ||
        runningAt - listedAt >= 120000 || completedAt - runningAt < 150000 ||
        !jobQueries.slice(jobs).some(({run_id, attempt_number}) => run_id === 611 && attempt_number === 1)) {
      throw new Error("sparse replacement must leave the snapshot allowance and finish under the original execution deadline");
    }
  } finally {
    availableRuns = priorRuns;
    onRepositoryDispatchQuery = priorHook;
  }
}
async function checkTypedFailureProofTemporaryRefusal() {
  const priorRuns = availableRuns, priorRequest = github.request, priorEventSha = context.sha;
  const priorWorkflow = github.rest.actions.getWorkflow;
  const event = {...runtimeRun(620, runtimeTitle(baseSha, headSha, mergeSha)), conclusion: "cancelled"};
  for (const cancelledOriginal of [false, true]) {
    for (const status of [503, 403]) {
      const failed = {...dispatchRun(621, baseSha, headSha, mergeSha), name: "Build Runtime Images", conclusion: "failure"};
      const successor = dispatchRun(622, baseSha, headSha, mergeSha);
      context.sha = cancelledOriginal ? priorEventSha : undefined;
      const originals = cancelledOriginal ? [event] : [];
      availableRuns = [...originals, failed];
      let reads = 0, warnings = 0;
      const jobs = jobQueries.length;
      const refusal = Object.assign(new Error("injected exact typed attempt lookup failure"), {status});
      github.rest.actions.getWorkflow = async (input) => {
        if (input.workflow_id === "runtime-images.yml") return {data: {
          id: failed.workflow_id, path: ".github/workflows/runtime-images.yml", name: "Build Runtime Images",
        }};
        return priorWorkflow(input);
      };
      github.request = async (route, input) => {
        if (input.run_id === failed.id) {
          reads++;
          throw refusal;
        }
        return priorRequest(route, input);
      };
      smokeStepConclusion = "success";
      const core = {
        info: () => {},
        warning: (message) => {
          if (message.startsWith("Exact typed failure proof temporarily unavailable:")) {
            warnings++;
            availableRuns = [...originals, successor];
          }
        },
        setFailed: (message) => {throw new Error(message);},
      };
      let caught;
      try { await run(github, context, core); } catch (error) { caught = error; }
      finally {
        availableRuns = priorRuns;
        github.request = priorRequest;
        github.rest.actions.getWorkflow = priorWorkflow;
        context.sha = priorEventSha;
      }
      if (status === 503 && (caught || reads !== 4 || warnings !== 1 ||
          !jobQueries.slice(jobs).some(({run_id, attempt_number}) => run_id === 622 && attempt_number === 1))) {
        throw new Error(`exhausted transient proof lookups must keep polling for exact successor: ${cancelledOriginal}/${reads}/${warnings}`, {cause: caught});
      }
      if (status === 403 && (!caught || reads !== 1 || warnings !== 0)) {
        throw new Error("terminal proof refusal must stop without polling", {cause: caught});
      }
    }
  }
}

checkPartialPaginationRetry().then(checkIdentityQuotaFailures)
  .then(checkSparseReplacementUsesOverallBudget).then(checkTypedFailureProofTemporaryRefusal).then(() => {
  setExactEventRun(101);
  return check("skipped", true, "credential-free full-stack smoke step did not pass");
})
  .then(() => check("success", false))
  .then(() => {
    const eventRun = {
      ...runtimeRun(601, runtimeTitle(baseSha, headSha, mergeSha)),
      status: "completed",
      conclusion: "cancelled",
    };
    const replacement = {
      ...dispatchRun(602, baseSha, headSha, mergeSha),
      status: "in_progress",
      conclusion: null,
    };
    const queryStart = workflowRunQueries.length;
    const jobStart = jobQueries.length;
    const dispatchQueryStart = repositoryDispatchQueryCount;
    const currentBaseLookupStart = currentBaseLookups;
    availableRuns = [eventRun];
    onRepositoryDispatchQuery = (queryCount) => {
      const replacementPoll = queryCount - dispatchQueryStart;
      if (replacementPoll >= 2) {
        availableRuns = [eventRun, replacement];
      }
      if (replacementPoll >= 3) {
        replacement.status = "completed";
        replacement.conclusion = "success";
      }
    };
    return check("success", false).then(() => {
      onRepositoryDispatchQuery = null;
      const queries = workflowRunQueries.slice(queryStart);
      if (!queries.some((query) => query.event === "repository_dispatch")) {
        throw new Error("a cancelled exact event run must poll for a typed repository_dispatch replacement");
      }
      const dispatchQuery = queries.find((query) => query.event === "repository_dispatch");
      if (dispatchQuery.created !== "2026-09-01T00:00:00Z..*" || Object.hasOwn(dispatchQuery, "head_sha")) {
        throw new Error("same-anchor replacement lookup must be bounded to PR-created dispatch runs without head-SHA assumptions");
      }
      if (jobQueries.length !== jobStart + 2 || !jobQueries.slice(jobStart).every(query => query.run_id === 602 && query.attempt_number === 1)) {
        throw new Error("Smoke Gate must wait for and validate the exact dispatch replacement smoke job");
      }
      if (currentBaseLookups <= currentBaseLookupStart) {
        throw new Error("Smoke Gate must verify the live base before accepting a typed replacement");
      }
    });
  })
  .then(() => {
    const eventRun = {
      ...runtimeRun(603, runtimeTitle(baseSha, headSha, mergeSha)),
      status: "completed",
      conclusion: "cancelled",
    };
    const wrongAnchorDispatch = dispatchRun(604, "d".repeat(40), headSha, mergeSha);
    const queryStart = workflowRunQueries.length;
    const jobStart = jobQueries.length;
    const startedAt = fakeNow;
    availableRuns = [eventRun, wrongAnchorDispatch];
    return check("success", true, "Cancelled runtime-images run 603 has no exact repository_dispatch replacement").then(() => {
      if (!workflowRunQueries.slice(queryStart).some((query) => query.event === "repository_dispatch")) {
        throw new Error("a cancelled exact event run must check for a repository_dispatch replacement");
      }
      if (jobQueries.length !== jobStart) {
        throw new Error("Smoke Gate must reject a dispatch replacement with a mismatched base anchor");
      }
      if (fakeNow - startedAt >= 25 * 60 * 1000) {
        throw new Error("a cancelled exact event run without replacement must fail before the generic smoke deadline");
      }
    });
  })
  .then(() => {
    const eventRun = {
      ...runtimeRun(605, runtimeTitle(baseSha, headSha, mergeSha)),
      status: "completed",
      conclusion: "cancelled",
    };
    let recancelledAt = null;
    let cancellationWaits = 0;
    availableRuns = [eventRun];
    onSmokeSleep = () => {
      cancellationWaits += 1;
      if (cancellationWaits === 8) {
        eventRun.status = "in_progress";
        eventRun.conclusion = null;
      } else if (cancellationWaits === 9) {
        recancelledAt = fakeNow;
        eventRun.status = "completed";
        eventRun.conclusion = "cancelled";
      }
    };
    return check(
      "success",
      true,
      "Cancelled runtime-images run 605 has no exact repository_dispatch replacement"
    ).then(() => {
      onSmokeSleep = null;
      if (recancelledAt === null || fakeNow - recancelledAt < 5 * 60 * 1000) {
        throw new Error(
          "a cancelled run that becomes active and is cancelled again must receive a fresh five-minute replacement window"
        );
      }
      if (eventRun.id !== 605 || eventRun.status !== "completed" || eventRun.conclusion !== "cancelled") {
        throw new Error("the cancellation-window regression must reuse the same run ID");
      }
    });
  })
  .then(() => {
    currentBaseSha = "d".repeat(40);
    currentMergeSha = "e".repeat(40);
    eventMergeParents = [{ sha: baseSha }, { sha: headSha }];
    const queryStart = workflowRunQueries.length;
    const jobStart = jobQueries.length;
    const currentBaseLookupStart = currentBaseLookups;
    availableRuns = [
      runtimeRun(303, runtimeTitle(currentBaseSha, headSha, currentMergeSha)),
      runtimeRun(304, `Build Runtime Images secure-pr-artifact pr-42 head-${headSha} mode-required`),
      runtimeRun(202, runtimeTitle(baseSha, headSha, mergeSha)),
    ];
    return check("success", false).then(() => {
      const queries = workflowRunQueries.slice(queryStart);
      if (queries.length !== 1 || queries[0].event !== "pull_request") {
        throw new Error("base advance must continue selecting the original pull_request smoke event");
      }
      if (queries[0].head_sha !== headSha) {
        throw new Error("Smoke Gate lookup must remain pinned to the event head SHA");
      }
      if (requestedCommitRefs.at(-1) !== mergeSha) {
        throw new Error("Smoke Gate must verify the event merge commit, not the regenerated current merge");
      }
      if (jobQueries.length !== jobStart + 1 || jobQueries.at(-1).run_id !== 202) {
        throw new Error("Smoke Gate must select the exact successful event base/head/merge tuple");
      }
      if (currentBaseLookups !== currentBaseLookupStart) {
        throw new Error("Smoke Gate must not require the live base SHA to accept event-tuple smoke");
      }
    });
  })
  .then(() => {
    const jobStart = jobQueries.length;
    eventMergeParents = [{ sha: headSha }, { sha: baseSha }];
    setExactEventRun(305);
    return check("success", true, "Timed out waiting for the exact runtime-images/full-smoke run").then(() => {
      if (jobQueries.length !== jobStart) {
        throw new Error("Smoke Gate must reject an event merge with reversed base/head parents");
      }
    });
  })
  .then(() => {
    const queryStart = workflowRunQueries.length;
    const jobStart = jobQueries.length;
    eventMergeParents = [{ sha: baseSha }, { sha: headSha }];
    setOnlyNonExactRuns();
    return check("success", true, "Timed out waiting for the exact runtime-images/full-smoke run").then(() => {
      if (workflowRunQueries.length <= queryStart || jobQueries.length !== jobStart) {
        throw new Error("a head-only or current-base-only runtime run must not satisfy Smoke Gate");
      }
    });
  })
  .then(() => {
    // GitHub can deliver a PR event whose base SHA predates the branch ref.
    // The trusted refresh may satisfy the gate only for the exact current tuple.
    currentBaseSha = "8".repeat(40);
    liveBaseSha = "d".repeat(40);
    currentMergeSha = "e".repeat(40);
    currentMergeParents = [{ sha: liveBaseSha }, { sha: headSha }];
    eventMergeParents = [{ sha: "7".repeat(40) }, { sha: headSha }];
    const queryStart = workflowRunQueries.length;
    const jobStart = jobQueries.length;
    availableRuns = [dispatchRun(501, liveBaseSha, headSha, currentMergeSha)];
    return check("success", false).then(() => {
      const queries = workflowRunQueries.slice(queryStart);
      if (!queries.some((query) => query.event === "repository_dispatch")) {
        throw new Error("stale event base must permit lookup of a typed repository_dispatch run");
      }
      const dispatchQuery = queries.find((query) => query.event === "repository_dispatch");
      if (dispatchQuery.created !== "2026-09-01T00:00:00Z..*") {
        throw new Error("dispatch lookup must be bounded to runs created after the PR existed");
      }
      if (Object.hasOwn(dispatchQuery, "head_sha")) {
        throw new Error("dispatch lookup must identify the exact tuple by its typed title, not head SHA");
      }
      if (requestedCommitRefs.at(-1) !== currentMergeSha) {
        throw new Error("dispatch fallback must verify the exact current PR merge commit");
      }
      if (jobQueries.length !== jobStart + 2 || !jobQueries.slice(jobStart).every(query => query.run_id === 501 && query.attempt_number === 1)) {
        throw new Error("Smoke Gate must accept only the successful smoke run for the current tuple");
      }
    });
  })
  .then(async () => {
    // Retarget edits can lack an event merge identity. Neither absence nor
    // malformed event data grants head-only success: exact current parents
    // and the typed refresh source are still mandatory.
    const originalEventSha = context.sha;
    for (const missingEventSha of [undefined, "", "not-a-sha"]) {
      context.sha = missingEventSha;
      availableRuns = [dispatchRun(506, liveBaseSha, headSha, currentMergeSha)];
      const jobStart = jobQueries.length;
      await check("success", false);
      if (jobQueries.length !== jobStart + 2 || !jobQueries.slice(jobStart).every(query => query.run_id === 506 && query.attempt_number === 1)) {
        throw new Error("missing event merge must require exact current typed refresh proof");
      }
      currentMergeParents = [{ sha: headSha }, { sha: liveBaseSha }];
      await check("success", true, "Timed out waiting for the exact runtime-images/full-smoke run");
      if (jobQueries.length !== jobStart + 2) {
        throw new Error("missing event merge must reject reversed current parents");
      }
      currentMergeParents = [{ sha: liveBaseSha }, { sha: headSha }];
    }
    context.sha = originalEventSha;
  })
  .then(() => {
    const jobStart = jobQueries.length;
    availableRuns = [
      dispatchRun(502, currentBaseSha, headSha, currentMergeSha),
      {
        ...runtimeRun(505, `Build Runtime Images secure-pr-artifact pr-42 head-${headSha} mode-required`),
        event: "repository_dispatch",
      },
    ];
    return check("success", true, "Timed out waiting for the exact runtime-images/full-smoke run").then(() => {
      if (jobQueries.length !== jobStart) {
        throw new Error("Smoke Gate must reject a dispatch for the stale event base SHA");
      }
    });
  })
  .then(() => {
    const jobStart = jobQueries.length;
    currentMergeParents = [{ sha: headSha }, { sha: liveBaseSha }];
    availableRuns = [dispatchRun(503, liveBaseSha, headSha, currentMergeSha)];
    return check("success", true, "Timed out waiting for the exact runtime-images/full-smoke run").then(() => {
      if (jobQueries.length !== jobStart) {
        throw new Error("Smoke Gate must reject a current merge with an incorrect ordered base parent");
      }
    });
  })
  .then(() => {
    const jobStart = jobQueries.length;
    currentMergeParents = [{ sha: liveBaseSha }, { sha: headSha }];
    availableRuns = [{ ...runtimeRun(504, "arbitrary dispatch"), event: "repository_dispatch" }];
    return check("success", true, "Timed out waiting for the exact runtime-images/full-smoke run").then(() => {
      if (jobQueries.length !== jobStart) {
        throw new Error("Smoke Gate must reject arbitrary repository_dispatch runs");
      }
    });
  })
  .then(() => {
    currentHeadSha = "f".repeat(40);
    const queryStart = workflowRunQueries.length;
    setExactEventRun(404);
    return check("success", false).then(() => {
      if (workflowRunQueries.length !== queryStart) {
        throw new Error("Smoke Gate must stop when the live PR head changed after the event");
      }
    });
  })
  .then(() => {
    currentHeadSha = headSha;
    currentBaseRef = "feature/retargeted";
    const queryStart = workflowRunQueries.length;
    return check("success", false).then(() => {
      if (workflowRunQueries.length !== queryStart) {
        throw new Error("Smoke Gate must stop when the live PR base ref changed after the event");
      }
    });
  })
  .then(() => {
    currentBaseRef = "develop";
    currentPullRequestState = "closed";
    const queryStart = workflowRunQueries.length;
    return check("success", false).then(() => {
      if (workflowRunQueries.length !== queryStart) {
        throw new Error("Smoke Gate must stop when the event PR is no longer open");
      }
    });
  })
  .then(() => {
    console.log("Smoke Gate event-tuple, base-advance, and exact-current-tuple dispatch contract passed");
  }).catch((error) => {
    console.error(error.stack || error);
    process.exit(1);
  });
NODE

smoke_summary_scripts="$({
  python3 - "$smoke_path" <<'PY'
import json
import sys
from pathlib import Path

import yaml


workflow = yaml.load(Path(sys.argv[1]).read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
scripts = {}
for job_name in ("smoke-summary-pending", "smoke-summary"):
    scripts[job_name] = next(
        step["with"]["script"]
        for step in workflow["jobs"][job_name]["steps"]
        if step.get("name", "").startswith("💬 Publish")
    )
print(json.dumps(scripts))
PY
})"

SMOKE_SUMMARY_SCRIPTS="$smoke_summary_scripts" node <<'NODE'
const assert = require("node:assert/strict");

const scripts = JSON.parse(process.env.SMOKE_SUMMARY_SCRIPTS);
const context = {
  repo: { owner: "example", repo: "firemud" },
  issue: { number: 42 },
  payload: { pull_request: { head: { sha: "h" }, base: { ref: "develop", sha: "b" } } },
};

async function checkSummary(jobName, script) {
  const firstPageNoise = Array.from({ length: 35 }, (_, index) => ({
    id: index + 1,
    user: { login: "github-actions[bot]" },
    body: `### Unrelated Summary ${index + 1}`,
    created_at: `2026-08-${String((index % 28) + 1).padStart(2, "0")}T00:00:00Z`,
  }));
  const comments = [
    ...firstPageNoise,
    {
      id: 100,
      user: { login: "github-actions[bot]" },
      body: "### Smoke Summary\nold canonical",
      created_at: "2026-09-01T00:00:00Z",
      updated_at: "2026-09-03T00:00:00Z",
    },
    {
      id: 101,
      user: { login: "github-actions[bot]" },
      body: "<!-- firemud-smoke-summary -->\n### Smoke Summary\nlater duplicate",
      created_at: "2026-09-02T00:00:00Z",
      updated_at: "2026-09-01T00:00:00Z",
    },
    {
      id: 102,
      user: { login: "contributor" },
      body: "<!-- firemud-smoke-summary -->\n### Smoke Summary\nspoof",
      created_at: "2026-08-01T00:00:00Z",
    },
  ];
  const calls = { paginate: [], operations: [], creates: [], bodies: [] };
  let currentPullRequest = { state: "open", ...structuredClone(context.payload.pull_request) };
  let readError = null;
  const listComments = async () => undefined;
  const github = {
    rest: {
      pulls: { get: async () => { if (readError) throw readError; return { data: currentPullRequest }; } },
      issues: {
        listComments,
        updateComment: async ({ comment_id, body }) => { calls.operations.push(`update:${comment_id}`); calls.bodies.push(body); },
        deleteComment: async ({ comment_id }) => calls.operations.push(`delete:${comment_id}`),
        createComment: async (request) => calls.creates.push(request),
      },
    },
    paginate: async (method, params) => {
      calls.paginate.push({ method, params });
      return comments;
    },
  };

  process.env.CHANGES_RESULT = "success";
  process.env.RUN_SMOKE_FULL = "true";
  process.env.SMOKE_GATE_RESULT = "success";
  const run = new Function("github", "context", "core", `return (async () => {\n${script}\n})()`);
  const core = { info: () => {} };
  for (const changed of [
    { state: "closed" }, { head: { sha: "new" } },
    { base: { ref: "main", sha: "b" } }, { base: { ref: "develop", sha: "new" } },
  ]) {
    currentPullRequest = { state: "open", ...structuredClone(context.payload.pull_request), ...changed };
    await run(github, context, core);
    assert.deepEqual(calls.paginate, [], `${jobName} must not list stale comments`);
    assert.deepEqual(calls.operations, [], `${jobName} must not mutate stale comments`);
    assert.deepEqual(calls.creates, [], `${jobName} must not create stale comments`);
  }
  readError = new Error("PR guard unavailable");
  await assert.rejects(run(github, context, core), /PR guard unavailable/);
  assert.deepEqual(calls.paginate, [], `${jobName} guard errors must fail closed`);
  readError = null;
  currentPullRequest = { state: "open", ...structuredClone(context.payload.pull_request) };
  await run(github, context, core);

  assert.equal(calls.paginate.length, 1, `${jobName} must paginate once`);
  assert.equal(calls.paginate[0].method, listComments, `${jobName} must paginate issue comments`);
  assert.equal(calls.paginate[0].params.per_page, 100, `${jobName} must request 100 comments per page`);
  assert.deepEqual(
    calls.operations,
    ["update:100", "delete:101"],
    `${jobName} must update the oldest bot summary before deleting later duplicates`,
  );
  assert.deepEqual(calls.creates, [], `${jobName} must not create another summary`);
  comments.find((comment) => comment.id === 100).body = calls.bodies.at(-1);
  calls.operations.length = 0;
  await run(github, context, core);
  assert.deepEqual(calls.operations, ["delete:101"], `${jobName} must skip identical PATCH while removing duplicates`);
  assert.deepEqual(calls.creates, [], `${jobName} must retain the unchanged canonical comment`);
  process.env.CHANGES_RESULT = "failure";
  process.env.SMOKE_GATE_RESULT = "failure";
  await run(github, context, core);
  assert.ok(calls.bodies.at(-1).includes("failure"), `${jobName} must report failed dependencies`);
}

(async () => {
  for (const [jobName, script] of Object.entries(scripts)) {
    await checkSummary(jobName, script);
  }
  console.log("Smoke summary pagination and deduplication contract passed");
})().catch((error) => {
  console.error(error.stack || error);
  process.exit(1);
});
NODE

echo "PR retarget workflow contract checks passed"
