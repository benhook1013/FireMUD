#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT_SOURCE="$ROOT_DIR/dev-tools/maintenance/cloc-report.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

TEST_REPO="$TMP_DIR/repo"
mkdir -p "$TEST_REPO/dev-tools/maintenance"
cp "$SCRIPT_SOURCE" "$TEST_REPO/dev-tools/maintenance/cloc-report.sh"
chmod +x "$TEST_REPO/dev-tools/maintenance/cloc-report.sh"

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
  services/bar/src/main/java/example \
  services/bar/src/testFixtures/java/example \
  services/bar/design \
  web-client/src

cat >README.md <<'EOF'
# Example Repo
Tracked markdown root file.
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

cat >services/foo/src/main/java/example/Foo.java <<'EOF'
package example;
class Foo {}
EOF

cat >services/foo/src/test/java/example/FooTest.java <<'EOF'
package example;
class FooTest {}
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

git add .
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

python3 - <<'PY'
import json
import subprocess
from pathlib import Path

repo = Path.cwd()
script = ["bash", "dev-tools/maintenance/cloc-report.sh"]


def run_json(*args: str) -> dict:
    out = subprocess.check_output([*script, *args], cwd=repo, stderr=subprocess.DEVNULL, text=True)
    return json.loads(out)


source = run_json("source", "--json")
prod = run_json("prod", "--json")
tests = run_json("tests", "--json")
summary = run_json("summary", "--json")
by_module = run_json("by-module", "--json")
design = run_json("design", "--json")
architecture = run_json("architecture", "--json")
service_docs = run_json("service-docs", "--json")
diff_summary = run_json("diff", "HEAD~1...HEAD", "--json")
diff_by_module = run_json("diff", "HEAD~1...HEAD", "--by-module", "--json")

source_sum = source["SUM"]
prod_sum = prod["SUM"]
tests_sum = tests["SUM"]

assert source_sum["nFiles"] == prod_sum["nFiles"] + tests_sum["nFiles"], (source_sum, prod_sum, tests_sum)
assert source_sum["code"] == prod_sum["code"] + tests_sum["code"], (source_sum, prod_sum, tests_sum)

summary_scopes = {row["scope"]: row for row in summary["scopes"]}
assert summary_scopes["source"]["files"] == source_sum["nFiles"]
assert summary_scopes["source"]["code"] == source_sum["code"]
assert summary_scopes["prod"]["files"] == prod_sum["nFiles"]
assert summary_scopes["prod"]["code"] == prod_sum["code"]
assert summary_scopes["tests"]["files"] == tests_sum["nFiles"]
assert summary_scopes["tests"]["code"] == tests_sum["code"]
assert summary_scopes["service_docs"]["files"] == 4
assert summary_scopes["service_docs"]["code"] > 0

assert design["SUM"]["nFiles"] >= architecture["SUM"]["nFiles"]
assert design["SUM"]["code"] >= architecture["SUM"]["code"]
assert service_docs["SUM"]["nFiles"] == 4

debug_output = subprocess.check_output([*script, "debug"], cwd=repo, stderr=subprocess.DEVNULL, text=True)
assert "tests\tdev_tools_contract_tests\tdev-tools/tests/contract.sh" in debug_output
assert "tests\tdev_tools_validation_test\tdev-tools/validation/test_helper.py" in debug_output
assert "tests\tgradle_src_test\tservices/foo/src/test/java/example/FooTest.java" in debug_output

module_summary = by_module["summary"]
assert module_summary["total_files"] == source_sum["nFiles"]
assert module_summary["total_code"] == source_sum["code"]
assert module_summary["prod_files"] == prod_sum["nFiles"]
assert module_summary["prod_code"] == prod_sum["code"]
assert module_summary["tests_files"] == tests_sum["nFiles"]
assert module_summary["tests_code"] == tests_sum["code"]

module_rows = {row["module"]: row for row in by_module["modules"]}
assert module_rows["services/foo"]["tests_files"] > 0
assert module_rows["dev-tools"]["tests_files"] > 0
assert module_rows["repo-root"]["prod_files"] > 0

diff_scopes = {row["scope"]: row for row in diff_summary["scopes"]}
assert diff_scopes["source"]["files"] == diff_by_module["summary"]["total_files"]
assert diff_scopes["source"]["code"] == diff_by_module["summary"]["total_code"]
assert diff_scopes["prod"]["files"] == diff_by_module["summary"]["prod_files"]
assert diff_scopes["prod"]["code"] == diff_by_module["summary"]["prod_code"]
assert diff_scopes["tests"]["files"] == diff_by_module["summary"]["tests_files"]
assert diff_scopes["tests"]["code"] == diff_by_module["summary"]["tests_code"]
assert diff_scopes["design"]["files"] > 0
assert diff_scopes["service_docs"]["files"] > 0

print("cloc report contract checks passed")
PY
