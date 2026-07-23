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

python3 - <<'PY'
import json
import importlib.util
import subprocess
import sys
from pathlib import Path

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
assert cloc_report.render_scope([], "tests", by_file=True) == (
    "path  language  blank  comments  lines"
)


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
assert summary_nodes["markdown"]["overlaps"] == ["source"]

assert "scope / relationship" in summary_table
assert "files  lines  share of parent (lines)" in summary_table.splitlines()[0]
assert "|-- source (= prod + tests)" in summary_table
assert "|   |-- prod" in summary_table
assert "|   `-- tests" in summary_table
assert "`-- markdown (overlaps source)" in summary_table
assert "    `-- design (= sections below)" in summary_table
assert "        |-- architecture" in summary_table
assert "        |-- project management" in summary_table
assert "        |-- observability" in summary_table
assert "        |-- operations" in summary_table
assert "        `-- other design" in summary_table
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

help_output = subprocess.check_output([*script, "--help"], cwd=repo, text=True)
assert "summary" in help_output
assert "scope" in help_output
assert "modules" in help_output
assert "diff" in help_output
assert "classify" in help_output

print("cloc report contract checks passed")
PY
