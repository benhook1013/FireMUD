#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT_SOURCE="$ROOT_DIR/dev-tools/maintenance/cloc-report.py"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

TEST_REPO="$TMP_DIR/repo"
mkdir -p "$TEST_REPO/dev-tools/maintenance"
cp "$SCRIPT_SOURCE" "$TEST_REPO/dev-tools/maintenance/cloc-report.py"

cd "$TEST_REPO"
git init -q
git config user.name "FireMUD Test"
git config user.email "test@example.com"

mkdir -p \
  buildSrc/src/main/kotlin \
  design/architecture \
  dev-tools/tests \
  dev-tools/validation \
  gradle \
  protos/example/v1 \
  services/foo/src/main/java/example \
  services/foo/src/test/java/example \
  services/foo/design \
  services/foo/docs \
  services/foo/bin \
  services/bar/src/main/java/example \
  services/bar/src/testFixtures/java/example \
  services/bar/design \
  web-client/src

cat >README.md <<'EOF'
# Example Repo
Tracked markdown root file.
EOF

cat >.gitignore <<'EOF'
**/bin/
EOF

cat >build.gradle.kts <<'EOF'
plugins {}
EOF

cat >settings.gradle.kts <<'EOF'
rootProject.name = "example"
EOF

cat >buildSrc/src/main/kotlin/BuildLogic.kt <<'EOF'
package example
class BuildLogic
EOF

cat >design/overview.md <<'EOF'
# Design Overview
Top-level design doc.
EOF

cat >design/architecture/system.md <<'EOF'
# Architecture
Architecture-only doc.
EOF

cat >design/architecture/metadata.yaml <<'EOF'
schemaVersion: v1
kind: ArchitectureMetadata
EOF

cat >dev-tools/tests/contract.sh <<'EOF'
echo contract
EOF

cat >dev-tools/validation/test_helper.py <<'EOF'
def test_helper():
    return "ok"
EOF

cat >gradle/libs.versions.toml <<'EOF'
[versions]
EOF

cat >protos/example/v1/example.proto <<'EOF'
syntax = "proto3";
package example.v1;
EOF

cat >services/foo/README.md <<'EOF'
# Foo Service
Service readme.
EOF

cat >services/foo/design/notes.md <<'EOF'
# Foo Notes
Service design notes.
EOF

cat >services/foo/docs/README.md <<'EOF'
# Nested Foo Docs
Should not count as a service-local README.
EOF

cat >services/foo/src/main/java/example/Foo.java <<'EOF'
package example;
class Foo {}
EOF

cat >services/foo/src/main/java/example/DuplicateFootprint.java <<'EOF'
package example;
class DuplicateFootprint {}
EOF

cat >services/foo/src/test/java/example/FooTest.java <<'EOF'
package example;
class FooTest {}
EOF

cat >services/foo/src/test/java/example/DuplicateFootprint.java <<'EOF'
package example;
class DuplicateFootprint {}
EOF

cat >services/bar/README.md <<'EOF'
# Bar Service
Service readme.
EOF

cat >services/bar/design/notes.md <<'EOF'
# Bar Notes
Service design notes.
EOF

cat >services/bar/src/main/java/example/Bar.java <<'EOF'
package example;
class Bar {}
EOF

cat >services/bar/src/testFixtures/java/example/BarFixture.java <<'EOF'
package example;
class BarFixture {}
EOF

cat >web-client/src/App.tsx <<'EOF'
export const App = () => null;
EOF

cat >services/foo/bin/Generated.java <<'EOF'
package example;
class Generated {}
EOF

git add .
git add -f services/foo/bin/Generated.java
git commit -q -m "Initial fixture repo"

cat >>services/foo/src/main/java/example/Foo.java <<'EOF'
class FooChange {}
EOF

cat >>services/foo/src/test/java/example/FooTest.java <<'EOF'
class FooTestChange {}
EOF

cat >>design/overview.md <<'EOF'
Design diff update.
EOF

cat >>services/foo/design/notes.md <<'EOF'
Service design diff update.
EOF

git add .
git commit -q -m "Change fixture repo"

git rm -q services/foo/src/test/java/example/FooTest.java
git commit -q -m "Delete fixture test file"

MINIMAL_REPO="$TMP_DIR/minimal"
mkdir -p "$MINIMAL_REPO/dev-tools/maintenance"
cp "$SCRIPT_SOURCE" "$MINIMAL_REPO/dev-tools/maintenance/cloc-report.py"

(
  cd "$MINIMAL_REPO"
  git init -q
  git config user.name "FireMUD Test"
  git config user.email "test@example.com"
  cat >README.md <<'EOF'
# Minimal Repo
Only markdown here.
EOF
  # Track the copied cloc tool so prod scope still has one file in the minimal fixture repo.
  git add .
  git commit -q -m "Initial minimal fixture"
)

REAL_PR_SOURCE="$TMP_DIR/real-pr-source"
REAL_PR_REMOTE="$TMP_DIR/real-pr-remote.git"
REAL_PR_CALLER="$TMP_DIR/real-pr-caller"
mkdir -p "$REAL_PR_SOURCE"
(
  cd "$REAL_PR_SOURCE"
  git init -q
  git config user.name "FireMUD Test"
  git config user.email "test@example.com"
  cat >app.py <<'EOF'
print("base")
EOF
  git add app.py
  git commit -q -m "Initial PR fixture"
  git branch -M develop
  cat >base.py <<'EOF'
print("base branch")
EOF
  git add base.py
  git commit -q -m "Base branch change"
  git checkout -q -b feature
  cat >feature.py <<'EOF'
print("feature one")
print("feature two")
EOF
  git add feature.py
  git rm -q app.py
  git commit -q -m "Feature change"
  git checkout -q develop
  cat >develop.py <<'EOF'
print("develop one")
print("develop two")
print("develop three")
EOF
  git add develop.py
  git commit -q -m "Diverged base change"
  git init -q --bare "$REAL_PR_REMOTE"
  git remote add origin "$REAL_PR_REMOTE"
  git push -q origin develop
  git push -q origin feature:refs/pull/2736/head
)
git clone -q --branch develop "$REAL_PR_REMOTE" "$REAL_PR_CALLER"
printf 'print("dirty caller")\n' >"$REAL_PR_CALLER/dirty.py"
export REAL_PR_SOURCE REAL_PR_CALLER

python3 - <<'PY'
import json
import importlib.util
import os
import subprocess
import sys
from contextlib import contextmanager, redirect_stderr, redirect_stdout
from io import StringIO
from pathlib import Path
from types import SimpleNamespace

repo = Path.cwd()
script = ["python3", "dev-tools/maintenance/cloc-report.py"]
minimal_repo = repo.parent / "minimal"

spec = importlib.util.spec_from_file_location(
    "cloc_report", repo / "dev-tools/maintenance/cloc-report.py"
)
assert spec is not None and spec.loader is not None
cloc_report = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = cloc_report
spec.loader.exec_module(cloc_report)

original_subprocess_module = cloc_report.subprocess
subprocess_calls = []


def fake_subprocess_run(args, **kwargs):
    subprocess_calls.append((args, kwargs))
    if args[0] == "gh":
        raise subprocess.TimeoutExpired(args, kwargs["timeout"])
    return subprocess.CompletedProcess(args, 0, stdout=b"", stderr=b"")


cloc_report.subprocess = SimpleNamespace(
    run=fake_subprocess_run,
    CalledProcessError=subprocess.CalledProcessError,
    TimeoutExpired=subprocess.TimeoutExpired,
)
try:
    cloc_report.run_command(("git", "status"), repo)
    assert subprocess_calls[-1][1]["timeout"] is None
    try:
        cloc_report.run_command(
            ("gh", "pr", "view"), repo, timeout=cloc_report.REMOTE_COMMAND_TIMEOUT_SECONDS
        )
    except cloc_report.ReportError as error:
        assert "gh pr view" in str(error)
        assert "120 seconds" in str(error)
    else:
        raise AssertionError("remote command timeout must be normalized")
finally:
    cloc_report.subprocess = original_subprocess_module

original_snapshot_bindings = (
    cloc_report.tempfile,
    cloc_report.run_command,
    cloc_report.shutil,
)
snapshot_temp_root = repo / "snapshot-cleanup-fixture"
snapshot_cleanup_calls = []
snapshot_cleanup_failures = {"add": False, "remove": True, "prune": True}
snapshot_add_error = cloc_report.ReportError("worktree add failed")


def failing_snapshot_command(args, _root):
    if args[3:5] == ("worktree", "add") and snapshot_cleanup_failures["add"]:
        snapshot_cleanup_calls.append("worktree-add")
        raise snapshot_add_error
    if args[3:5] == ("worktree", "remove"):
        snapshot_cleanup_calls.append("worktree-remove")
        if snapshot_cleanup_failures["remove"]:
            raise cloc_report.ReportError("worktree removal failed")
    elif args[3:5] == ("worktree", "prune"):
        snapshot_cleanup_calls.append("worktree-prune")
        if snapshot_cleanup_failures["prune"]:
            raise cloc_report.ReportError("worktree prune failed")
    return subprocess.CompletedProcess(args, 0, stdout=b"", stderr=b"")


def recording_rmtree(path, *, ignore_errors):
    assert path == snapshot_temp_root
    assert ignore_errors is False
    snapshot_cleanup_calls.append("rmtree")


cloc_report.tempfile = SimpleNamespace(mkdtemp=lambda **_kwargs: str(snapshot_temp_root))
cloc_report.run_command = failing_snapshot_command
cloc_report.shutil = SimpleNamespace(rmtree=recording_rmtree)
try:
    body_error = RuntimeError("snapshot body failed")
    try:
        with cloc_report.snapshot_worktree(repo, "a" * 40):
            raise body_error
    except RuntimeError as error:
        assert error is body_error
    else:
        raise AssertionError("snapshot cleanup replaced or suppressed the body error")
    assert snapshot_cleanup_calls == ["worktree-remove", "rmtree", "worktree-prune"]

    snapshot_cleanup_calls.clear()
    try:
        with cloc_report.snapshot_worktree(repo, "b" * 40):
            pass
    except cloc_report.ReportError as error:
        assert str(error) == "worktree removal failed"
    else:
        raise AssertionError("snapshot cleanup suppressed a worktree removal failure")
    assert snapshot_cleanup_calls == ["worktree-remove", "rmtree", "worktree-prune"]

    snapshot_cleanup_calls.clear()
    snapshot_cleanup_failures["remove"] = False
    with cloc_report.snapshot_worktree(repo, "c" * 40):
        pass
    assert snapshot_cleanup_calls == ["worktree-remove", "rmtree"]

    snapshot_cleanup_calls.clear()
    snapshot_cleanup_failures["add"] = True
    try:
        with cloc_report.snapshot_worktree(repo, "d" * 40):
            raise AssertionError("failed worktree add unexpectedly entered the body")
    except cloc_report.ReportError as error:
        assert error is snapshot_add_error
    else:
        raise AssertionError("snapshot cleanup replaced or suppressed the add failure")
    assert snapshot_cleanup_calls == ["worktree-add", "rmtree", "worktree-prune"]
finally:
    (
        cloc_report.tempfile,
        cloc_report.run_command,
        cloc_report.shutil,
    ) = original_snapshot_bindings

assert cloc_report.source_classification("services/foo/README.md") is None
assert cloc_report.source_classification("README.md") is None
assert cloc_report.module_bucket("services/README.md") == "services"
assert cloc_report.module_bucket("services/foo/README.md") == "services/foo"
assert cloc_report.render_scope([], "tests", by_file=True) == (
    "path  language  blank  comments  lines"
)
assert cloc_report.table_lines(
    [{"label": "short", "count": 2}, {"label": "longer", "count": 100}],
    (("label", "label", "left"), ("count", "count", "right")),
) == ["label   count", "short       2", "longer    100"]

original_require_tool = cloc_report.require_tool
original_run_command = cloc_report.run_command
cloc_report.require_tool = lambda _name: None
cloc_report.run_command = lambda args, _cwd: subprocess.CompletedProcess(
    args, 0, stdout=b"", stderr=b""
)
assert cloc_report.scan_cloc(repo, ["README.md"]) == []
cloc_report.run_command = lambda args, _cwd: subprocess.CompletedProcess(
    args, 0, stdout=b"not-json", stderr=b""
)
try:
    cloc_report.scan_cloc(repo, ["README.md"])
except cloc_report.ReportError as error:
    assert "cloc returned invalid JSON" in str(error)
else:
    raise AssertionError("non-empty invalid cloc output must fail")
finally:
    cloc_report.require_tool = original_require_tool
    cloc_report.run_command = original_run_command


def run_json(*args: str, cwd: Path = repo) -> dict:
    output = subprocess.check_output(
        [*script, *args], cwd=cwd, stderr=subprocess.DEVNULL, text=True
    )
    return json.loads(output)


def nodes_by_name(summary: dict) -> dict[str, dict]:
    nodes = {}

    def visit(node: dict) -> None:
        nodes[node["name"]] = node
        for child in node["children"]:
            visit(child)

    visit(summary["root"])
    return nodes


source = run_json("scope", "source", "--json")
prod = run_json("scope", "prod", "--json")
tests = run_json("scope", "tests", "--json")
design = run_json("scope", "design", "--json")
architecture = run_json("scope", "architecture", "--json")
service_docs = run_json("scope", "service-docs", "--json")
summary = run_json("summary", "--json")
modules = run_json("modules", "--json")
diff_summary = run_json("diff", "HEAD~2...HEAD~1", "--json")
diff_modules = run_json("diff", "HEAD~2...HEAD~1", "--modules", "--json")

minimal_tests = run_json("scope", "tests", "--json", cwd=minimal_repo)
minimal_service_docs = run_json("scope", "service-docs", "--json", cwd=minimal_repo)
minimal_prod = run_json("scope", "prod", "--json", cwd=minimal_repo)

summary_table = subprocess.check_output(script, cwd=repo, stderr=subprocess.DEVNULL, text=True)
custom_bar_table = subprocess.check_output(
    [*script, "summary", "--bar-width", "8"],
    cwd=repo,
    stderr=subprocess.DEVNULL,
    text=True,
)

source_total = source["totals"]
prod_total = prod["totals"]
test_total = tests["totals"]
assert source_total["files"] == prod_total["files"] + test_total["files"]
assert source_total["lines"] == prod_total["lines"] + test_total["lines"]

summary_nodes = nodes_by_name(summary)
assert set(summary_nodes) == {
    "repo",
    "source",
    "prod",
    "tests",
    "markdown",
    "design",
    "architecture",
    "project_management",
    "observability",
    "operations",
    "other_design",
}
assert summary_nodes["source"]["files"] == source_total["files"]
assert summary_nodes["source"]["lines"] == source_total["lines"]
assert summary_nodes["prod"]["files"] == prod_total["files"]
assert summary_nodes["prod"]["lines"] == prod_total["lines"]
assert summary_nodes["tests"]["files"] == test_total["files"]
assert summary_nodes["tests"]["lines"] == test_total["lines"]

design_children = summary_nodes["design"]["children"]
assert sum(row["files"] for row in design_children) == summary_nodes["design"]["files"]
assert sum(row["lines"] for row in design_children) == summary_nodes["design"]["lines"]
assert summary_nodes["architecture"]["files"] == architecture["totals"]["files"]
assert summary_nodes["architecture"]["lines"] == architecture["totals"]["lines"]
assert summary_nodes["design"]["files"] == design["totals"]["files"]
assert summary_nodes["design"]["lines"] == design["totals"]["lines"]
assert [child["name"] for child in summary["root"]["children"]] == [
    "source",
    "markdown",
    "design",
]
assert summary_nodes["design"]["parent"] == "repo"
assert summary_nodes["design"]["files"] == 3
assert summary_nodes["markdown"]["children"] == []
assert summary_nodes["markdown"]["overlaps"] == ["design"]

assert "scope / relationship" in summary_table
assert "files  lines  share of parent (lines)" in summary_table.splitlines()[0]
assert "|-- source (= prod + tests)" in summary_table
assert "|   |-- prod" in summary_table
assert "|   `-- tests" in summary_table
assert "|-- markdown (overlaps design)" in summary_table
assert "`-- design (= sections below)" in summary_table
assert "    |-- architecture" in summary_table
assert "    |-- project management" in summary_table
assert "    |-- observability" in summary_table
assert "    |-- operations" in summary_table
assert "    `-- other design" in summary_table
assert "service-docs" not in summary_table
assert "[################] 100.0%" in summary_table
assert "[########] 100.0%" in custom_bar_table
source_share = 100.0 * summary_nodes["source"]["lines"] / summary_nodes["repo"]["lines"]
architecture_share = (
    100.0 * summary_nodes["architecture"]["lines"] / summary_nodes["design"]["lines"]
)
assert f"{source_share:5.1f}%" in next(
    line for line in summary_table.splitlines() if "source (= prod + tests)" in line
)
assert f"{architecture_share:5.1f}%" in next(
    line for line in summary_table.splitlines() if "|-- architecture" in line
)
assert "Additive branches: source = prod + tests; design = its listed sections" in summary_table
assert "Markdown is excluded from source and overlaps design where paths match" in summary_table
assert "Bars compare each row's lines with its immediate parent" in summary_table
assert "Lines exclude blank and comment-only lines" in summary_table

assert service_docs["totals"]["files"] == 4
assert minimal_tests["totals"]["files"] == 0
assert minimal_tests["totals"]["lines"] == 0
assert minimal_service_docs["totals"]["files"] == 0
assert minimal_service_docs["totals"]["lines"] == 0
assert minimal_prod["totals"]["files"] > 0

classification = subprocess.check_output(
    [*script, "classify"], cwd=repo, stderr=subprocess.DEVNULL, text=True
)
assert "services/foo/README.md" not in classification
assert "\tREADME.md" not in classification
assert "tests\tdev_tools_contract_tests\tdev-tools/tests/contract.sh" in classification
assert "tests\tdev_tools_validation_test\tdev-tools/validation/test_helper.py" in classification
assert "tests\tgradle_src_test\tservices/foo/src/test/java/example/DuplicateFootprint.java" in classification
assert "prod\tsource_root:services\tservices/foo/bin/Generated.java" in classification

module_total = modules["total"]
assert module_total["files"] == source_total["files"]
assert module_total["lines"] == source_total["lines"]
assert module_total["prod_files"] == prod_total["files"]
assert module_total["prod_lines"] == prod_total["lines"]
assert module_total["test_files"] == test_total["files"]
assert module_total["test_lines"] == test_total["lines"]

module_rows = {row["module"]: row for row in modules["modules"]}
assert module_rows["services/foo"]["test_files"] > 0
assert module_rows["dev-tools"]["test_files"] > 0
assert module_rows["repo-root"]["prod_files"] > 0

diff_nodes = nodes_by_name(diff_summary)
diff_total = diff_modules["total"]
assert diff_nodes["source"]["files"] == diff_total["files"]
assert diff_nodes["source"]["lines"] == diff_total["lines"]
assert diff_nodes["prod"]["files"] == diff_total["prod_files"]
assert diff_nodes["prod"]["lines"] == diff_total["prod_lines"]
assert diff_nodes["tests"]["files"] == diff_total["test_files"]
assert diff_nodes["tests"]["lines"] == diff_total["test_lines"]
assert diff_nodes["design"]["files"] > 0
assert diff_nodes["design"]["parent"] == "repo"

deletion_diff = subprocess.run(
    [*script, "diff", "HEAD~1...HEAD", "--json"],
    cwd=repo,
    capture_output=True,
    text=True,
    check=True,
)
deletion_nodes = nodes_by_name(json.loads(deletion_diff.stdout))
assert all(row["files"] == 0 and row["lines"] == 0 for row in deletion_nodes.values())
assert "omitted 1 tracked path(s) that are deleted or missing" in deletion_diff.stderr

mock_metadata = cloc_report.PullRequestMetadata(
    number=2736,
    repository="example/example",
    base_ref="stack/base",
    base_oid="a" * 40,
    head_ref="feature/forked",
    head_oid="b" * 40,
)
base_design_children = [
    cloc_report.ReportNode(
        key=key,
        label=label,
        counts=cloc_report.Counts(lines=0),
    )
    for key, label, _prefix in cloc_report.DESIGN_SECTIONS
]
head_design_children = [
    cloc_report.ReportNode(
        key=key,
        label=label,
        counts=cloc_report.Counts(lines=1 if key == "architecture" else 0),
    )
    for key, label, _prefix in cloc_report.DESIGN_SECTIONS
]
base_source = cloc_report.ReportNode(
    key="source",
    label="source (= prod + tests)",
    counts=cloc_report.Counts(files=3, lines=8),
    children=[
        cloc_report.ReportNode("prod", "prod", cloc_report.Counts(files=2, lines=5)),
        cloc_report.ReportNode("tests", "tests", cloc_report.Counts(files=1, lines=3)),
    ],
)
head_source = cloc_report.ReportNode(
    key="source",
    label="source (= prod + tests)",
    counts=cloc_report.Counts(files=3, lines=7),
    children=[
        cloc_report.ReportNode("prod", "prod", cloc_report.Counts(files=2, lines=5)),
        cloc_report.ReportNode("tests", "tests", cloc_report.Counts(files=1, lines=2)),
    ],
)
base_summary_node = cloc_report.ReportNode(
    key="repo",
    label="repo",
    counts=cloc_report.Counts(files=4, lines=10),
    children=[
        base_source,
        cloc_report.ReportNode("markdown", "markdown (overlaps design)", cloc_report.Counts(lines=2), overlaps=("design",)),
        cloc_report.ReportNode("design", "design (= sections below)", cloc_report.Counts(lines=0), children=base_design_children),
    ],
)
head_summary_node = cloc_report.ReportNode(
    key="repo",
    label="repo",
    counts=cloc_report.Counts(files=4, lines=12),
    children=[
        head_source,
        cloc_report.ReportNode("markdown", "markdown (overlaps design)", cloc_report.Counts(lines=2), overlaps=("design",)),
        cloc_report.ReportNode("design", "design (= sections below)", cloc_report.Counts(lines=1), children=head_design_children),
    ],
)

original_pr_functions = (
    cloc_report.resolve_pull_request,
    cloc_report.pull_request_merge_base,
    cloc_report.classifier_digest,
    cloc_report.snapshot_worktree,
    cloc_report.summary_for_root,
)
snapshot_revisions = []
cloc_report.resolve_pull_request = lambda _root, _number, _repository: mock_metadata
cloc_report.pull_request_merge_base = lambda _root, _metadata: "c" * 40
cloc_report.classifier_digest = lambda: "digest"

@contextmanager
def fake_snapshot(_root, revision):
    snapshot_revisions.append(revision)
    yield Path("/isolated") / revision

cloc_report.snapshot_worktree = fake_snapshot
cloc_report.summary_for_root = lambda snapshot: (
    base_summary_node if snapshot.name == "".join(["c"] * 40) else head_summary_node
)
try:
    impact = cloc_report.build_pr_report(repo, 2736, "example/example")
    impact_rows_by_name = {row["name"]: row for row in impact["sections"]}
    assert snapshot_revisions == ["c" * 40, "b" * 40]
    assert impact["base"]["oid"] == "a" * 40
    assert impact["base"]["merge_base"] == "c" * 40
    assert impact_rows_by_name["repo"]["delta"]["lines"] == 2
    assert impact_rows_by_name["tests"]["delta"]["lines"] == -1
    assert impact_rows_by_name["prod"]["change_percent"] == 0.0
    assert impact_rows_by_name["architecture"]["change_percent"] is None
    rendered_impact = cloc_report.render_pr_report(impact)
    assert cloc_report.PR_REPORT_START in rendered_impact
    assert cloc_report.PR_REPORT_END in rendered_impact
    assert "| Overall | 10 | 12 | +2 | +20.0% |" in rendered_impact
    assert "| ↳ Production | 5 | 5 | 0 | 0.0% |" in rendered_impact
    assert "| ↳ Architecture | 0 | 1 | +1 | new |" in rendered_impact
finally:
    (
        cloc_report.resolve_pull_request,
        cloc_report.pull_request_merge_base,
        cloc_report.classifier_digest,
        cloc_report.snapshot_worktree,
        cloc_report.summary_for_root,
    ) = original_pr_functions

fetch_calls = []
availability = {"a" * 40: [False, True], "b" * 40: [False, True]}
original_commit_object_exists = cloc_report.commit_object_exists
original_run_command = cloc_report.run_command
cloc_report.commit_object_exists = lambda _root, object_id: availability[object_id].pop(0)
def fake_pr_command(args, _root, *, timeout=None):
    fetch_calls.append((args, timeout))
    if args[1:] == ("merge-base", "a" * 40, "b" * 40):
        return subprocess.CompletedProcess(args, 0, stdout=("c" * 40).encode(), stderr=b"")
    return subprocess.CompletedProcess(args, 0, stdout=b"", stderr=b"")
cloc_report.run_command = fake_pr_command
try:
    assert cloc_report.pull_request_merge_base(repo, mock_metadata) == "c" * 40
    assert all(
        timeout == cloc_report.REMOTE_COMMAND_TIMEOUT_SECONDS
        for args, timeout in fetch_calls
        if "fetch" in args
    )
    assert any("refs/heads/stack/base" in args for args, _timeout in fetch_calls)
    assert any("refs/pull/2736/head" in args for args, _timeout in fetch_calls)
    assert next(timeout for args, timeout in fetch_calls if "merge-base" in args) is None
finally:
    cloc_report.commit_object_exists = original_commit_object_exists
    cloc_report.run_command = original_run_command

assert cloc_report.format_change(0.04, 1, 1) == "+<0.1%"
assert cloc_report.format_change(-0.04, -1, 1) == "-<0.1%"
assert cloc_report.format_change(0.0, 0, 0) == "0.0%"

impact_snippet = cloc_report.render_pr_report(impact)
appended_body = cloc_report.replace_pr_report_block("prefix", impact_snippet)
assert appended_body.startswith("prefix\n\n" + cloc_report.PR_REPORT_START)
assert appended_body.count(cloc_report.PR_REPORT_START) == 1
assert appended_body.count(cloc_report.PR_REPORT_END) == 1
replaced_body = cloc_report.replace_pr_report_block(
    "prefix\n" + cloc_report.PR_REPORT_START + "\nold\n" + cloc_report.PR_REPORT_END + "\nsuffix",
    impact_snippet,
)
assert replaced_body.startswith("prefix\n")
assert replaced_body.endswith("\nsuffix")
assert replaced_body.count(cloc_report.PR_REPORT_START) == 1
assert cloc_report.replace_pr_report_block(replaced_body, impact_snippet) == replaced_body
for malformed_body in (
    cloc_report.PR_REPORT_START,
    cloc_report.PR_REPORT_END,
    cloc_report.PR_REPORT_START + "\n" + cloc_report.PR_REPORT_END + "\n" + cloc_report.PR_REPORT_START,
    cloc_report.PR_REPORT_END + "\n" + cloc_report.PR_REPORT_START,
):
    try:
        cloc_report.replace_pr_report_block(malformed_body, impact_snippet)
    except cloc_report.ReportError:
        pass
    else:
        raise AssertionError("malformed PR body markers must fail closed")

original_require_tool = cloc_report.require_tool
original_run_command = cloc_report.run_command
update_calls = []
cloc_report.require_tool = lambda _name: None
def fake_changed_revision_command(args, _root, *, timeout=None):
    update_calls.append(args)
    assert timeout == cloc_report.REMOTE_COMMAND_TIMEOUT_SECONDS
    return subprocess.CompletedProcess(
        args,
        0,
        stdout=json.dumps(
            {"baseRefOid": "a" * 40, "headRefOid": "d" * 40, "body": "existing"}
        ).encode(),
        stderr=b"",
    )
cloc_report.run_command = fake_changed_revision_command
try:
    try:
        cloc_report.update_pull_request_body(repo, 2736, impact)
    except cloc_report.ReportError as error:
        assert "base or head changed" in str(error)
    else:
        raise AssertionError("changed PR revisions must block body update")
finally:
    cloc_report.require_tool = original_require_tool
    cloc_report.run_command = original_run_command

stable_update_calls = []
cloc_report.require_tool = lambda _name: None
def fake_stable_update_command(args, _root, *, timeout=None):
    stable_update_calls.append((args, timeout))
    if args[:3] == ("gh", "pr", "view"):
        return subprocess.CompletedProcess(
            args,
            0,
            stdout=json.dumps(
                {"baseRefOid": "a" * 40, "headRefOid": "b" * 40, "body": "existing"}
            ).encode(),
            stderr=b"",
        )
    if args[:3] == ("gh", "pr", "edit"):
        assert Path(args[-1]).read_text(encoding="utf-8") == cloc_report.replace_pr_report_block(
            "existing", impact_snippet
        )
        return subprocess.CompletedProcess(args, 0, stdout=b"", stderr=b"")
    raise AssertionError(f"unexpected stable update command: {args}")
cloc_report.run_command = fake_stable_update_command
try:
    assert cloc_report.update_pull_request_body(repo, 2736, impact) is True
    assert [(args[:3], timeout) for args, timeout in stable_update_calls] == [
        (("gh", "pr", "view"), cloc_report.REMOTE_COMMAND_TIMEOUT_SECONDS),
        (("gh", "pr", "view"), cloc_report.REMOTE_COMMAND_TIMEOUT_SECONDS),
        (("gh", "pr", "edit"), cloc_report.REMOTE_COMMAND_TIMEOUT_SECONDS),
    ]
finally:
    cloc_report.require_tool = original_require_tool
    cloc_report.run_command = original_run_command

for changed_state, change_name in (
    ({"baseRefOid": "a" * 40, "headRefOid": "b" * 40, "body": "concurrent"}, "body"),
    ({"baseRefOid": "a" * 40, "headRefOid": "d" * 40, "body": "existing"}, "revision"),
):
    conflict_calls = []
    update_states = iter(
        (
            {"baseRefOid": "a" * 40, "headRefOid": "b" * 40, "body": "existing"},
            changed_state,
        )
    )
    cloc_report.require_tool = lambda _name: None
    def fake_conflicting_update_command(args, _root, *, timeout=None):
        conflict_calls.append(args)
        assert timeout == cloc_report.REMOTE_COMMAND_TIMEOUT_SECONDS
        if args[:3] != ("gh", "pr", "view"):
            raise AssertionError(f"{change_name} conflict must prevent PR edit")
        return subprocess.CompletedProcess(
            args,
            0,
            stdout=json.dumps(next(update_states)).encode(),
            stderr=b"",
        )
    cloc_report.run_command = fake_conflicting_update_command
    try:
        try:
            cloc_report.update_pull_request_body(repo, 2736, impact)
        except cloc_report.ReportError as error:
            assert "body, base, or head changed" in str(error)
        else:
            raise AssertionError(f"second-read {change_name} change must block body update")
        assert len(conflict_calls) == 2
    finally:
        cloc_report.require_tool = original_require_tool
        cloc_report.run_command = original_run_command

update_calls = []
cloc_report.require_tool = lambda _name: None
def fake_unchanged_body_command(args, _root, *, timeout=None):
    update_calls.append(args)
    assert timeout == cloc_report.REMOTE_COMMAND_TIMEOUT_SECONDS
    if args[:3] != ("gh", "pr", "view"):
        raise AssertionError("unchanged PR body must not be edited")
    return subprocess.CompletedProcess(
        args,
        0,
        stdout=json.dumps(
            {"baseRefOid": "a" * 40, "headRefOid": "b" * 40, "body": impact_snippet}
        ).encode(),
        stderr=b"",
    )
cloc_report.run_command = fake_unchanged_body_command
try:
    assert cloc_report.update_pull_request_body(repo, 2736, impact) is False
    assert len(update_calls) == 1
finally:
    cloc_report.require_tool = original_require_tool
    cloc_report.run_command = original_run_command

original_main_functions = (
    cloc_report.repository_root,
    cloc_report.build_pr_report,
    cloc_report.update_pull_request_body,
)
main_events = []
cloc_report.repository_root = lambda: repo


def fake_build_pr_report(_root, _number, _repository):
    main_events.append("build")
    return impact


cloc_report.build_pr_report = fake_build_pr_report
try:
    for extra_args in ([], ["--json"]):
        stdout = StringIO()
        stderr = StringIO()

        def failing_main_update(_root, _number, _report):
            assert stdout.getvalue()
            main_events.append("update")
            raise cloc_report.ReportError("remote update failed")

        cloc_report.update_pull_request_body = failing_main_update
        main_events.clear()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            status = cloc_report.main(["pr", "2736", "--update-pr", *extra_args])
        assert status == 1
        assert main_events == ["build", "update"]
        if extra_args:
            assert json.loads(stdout.getvalue()) == impact
        else:
            assert cloc_report.PR_REPORT_START in stdout.getvalue()
            assert cloc_report.PR_REPORT_END in stdout.getvalue()
        assert "cloc-report: remote update failed" in stderr.getvalue()
        assert "Updated the marked LOC section" not in stderr.getvalue()
finally:
    (
        cloc_report.repository_root,
        cloc_report.build_pr_report,
        cloc_report.update_pull_request_body,
    ) = original_main_functions

metadata_calls = []
original_require_tool = cloc_report.require_tool
original_run_command = cloc_report.run_command
cloc_report.require_tool = lambda _name: None
def fake_metadata_command(args, _root, *, timeout=None):
    metadata_calls.append((args, timeout))
    if args[:3] == ("git", "remote", "get-url"):
        assert timeout is None
        return subprocess.CompletedProcess(args, 0, stdout=b"git@github.com:example/example.git\n", stderr=b"")
    assert timeout == cloc_report.REMOTE_COMMAND_TIMEOUT_SECONDS
    return subprocess.CompletedProcess(
        args,
        0,
        stdout=json.dumps(
            {
                "baseRefName": "develop",
                "baseRefOid": "a" * 40,
                "headRefName": "feature/forked",
                "headRefOid": "b" * 40,
            }
        ).encode(),
        stderr=b"",
    )
cloc_report.run_command = fake_metadata_command
try:
    resolved_metadata = cloc_report.resolve_pull_request(repo, 2736, "example/example")
    assert resolved_metadata == mock_metadata.__class__(
        number=2736,
        repository="example/example",
        base_ref="develop",
        base_oid="a" * 40,
        head_ref="feature/forked",
        head_oid="b" * 40,
    )
    metadata_call = next(args for args, _timeout in metadata_calls if args[0] == "gh")
    assert "--repo" in metadata_call
    assert metadata_call[metadata_call.index("--json") + 1] == (
        "baseRefName,baseRefOid,headRefName,headRefOid"
    )
finally:
    cloc_report.require_tool = original_require_tool
    cloc_report.run_command = original_run_command

real_source = Path(os.environ["REAL_PR_SOURCE"])
real_caller = Path(os.environ["REAL_PR_CALLER"])

def git_revision(directory: Path, ref: str) -> str:
    return subprocess.check_output(
        ["git", "rev-parse", ref], cwd=directory, text=True
    ).strip()

real_base_oid = git_revision(real_caller, "origin/develop")
real_head_oid = git_revision(real_source, "feature")
real_merge_base = subprocess.check_output(
    ["git", "merge-base", real_base_oid, real_head_oid],
    cwd=real_source,
    text=True,
).strip()
real_metadata = cloc_report.PullRequestMetadata(
    number=2736,
    repository="example/example",
    base_ref="develop",
    base_oid=real_base_oid,
    head_ref="feature/forked",
    head_oid=real_head_oid,
)
original_resolve_pull_request = cloc_report.resolve_pull_request
cloc_report.resolve_pull_request = lambda _root, _number, _repository: real_metadata
dirty_file = real_caller / "dirty.py"
dirty_before = dirty_file.read_text()
status_before = subprocess.check_output(
    ["git", "status", "--porcelain=v1"], cwd=real_caller, text=True
)
try:
    real_impact = cloc_report.build_pr_report(real_caller, 2736, None)
    real_rows = {row["name"]: row for row in real_impact["sections"]}
    assert real_impact["base"]["merge_base"] == real_merge_base
    assert real_impact["base"]["oid"] == real_base_oid
    assert real_impact["head"]["oid"] == real_head_oid
    assert real_rows["repo"]["base"]["lines"] == 2
    assert real_rows["repo"]["head"]["lines"] == 3
    assert real_rows["repo"]["delta"]["lines"] == 1
    assert real_rows["tests"]["delta"]["lines"] == 0
    assert dirty_file.read_text() == dirty_before
    assert subprocess.check_output(
        ["git", "status", "--porcelain=v1"], cwd=real_caller, text=True
    ) == status_before
finally:
    cloc_report.resolve_pull_request = original_resolve_pull_request

outside_target = real_caller.parent / "outside-target.py"
outside_target.write_text('print("outside")\n')
escape_link = real_caller / "escape.py"
escape_link.symlink_to(outside_target)
try:
    cloc_report.scan_cloc(real_caller, ["escape.py"])
except cloc_report.ReportError as error:
    assert "escapes the snapshot through a symlink" in str(error)
else:
    raise AssertionError("snapshot symlink escape must fail closed")

help_env = os.environ.copy()
help_env["COLUMNS"] = "120"
help_output = subprocess.check_output(
    [*script, "--help"], cwd=repo, env=help_env, text=True
)
assert "{summary,scope,modules,diff,pr,classify}" in help_output
assert "summary" in help_output
assert "scope" in help_output
assert "modules" in help_output
assert "diff" in help_output
assert cloc_report.PR_COMMAND_DESCRIPTION in help_output
assert "classify" in help_output
pr_help_output = subprocess.check_output(
    [*script, "pr", "--help"], cwd=repo, env=help_env, text=True
)
assert cloc_report.PR_COMMAND_DESCRIPTION in pr_help_output
for pr_argument in ("number", "--repo", "--json", "--update-pr"):
    assert pr_argument in pr_help_output

print("cloc report contract checks passed")
PY
