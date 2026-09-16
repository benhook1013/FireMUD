#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
README="$ROOT_DIR/dev-tools/README.md"

README_PATH="$README" ROOT_PATH="$ROOT_DIR" python3 - <<'PY'
import os
import re
from pathlib import Path

root = Path(os.environ["ROOT_PATH"])
readme = Path(os.environ["README_PATH"])
text = readme.read_text(encoding="utf-8")

for target in re.findall(r"\[[^]]+\]\(([^)]+)\)", text):
    if target.startswith(("http://", "https://", "#")):
        continue
    path = (readme.parent / target.split("#", 1)[0]).resolve()
    if not path.is_file() or root not in path.parents:
        raise SystemExit(f"README link does not resolve to a tracked file: {target}")

for token in re.findall(r"`([^`]+)`", text):
    if (
        token.startswith(("validation/", "maintenance/", "tests/"))
        and " " not in token
        and not token.endswith("/")
    ):
        path = readme.parent / token
        if not path.is_file():
            raise SystemExit(f"documented dev-tools path does not exist: {token}")

expected_contracts = {
    "validation/report-pr-status.py": "validation/test_report_pr_status.py",
    "validation/report-pr-review-checkpoints.py": "validation/test_report_pr_review_checkpoints.py",
    "validation/check-coderabbit-review.py": "tests/coderabbit-review-contract.sh",
    "validation/report-worktree-pr-topology.sh": "tests/worktree-topology-contract.sh",
    "maintenance/cloc-report.py": "tests/cloc-report-contract.sh",
}
for entrypoint, contract in expected_contracts.items():
    if f"`{entrypoint}`" not in text:
        raise SystemExit(f"supported entrypoint is missing from the README map: {entrypoint}")
    if f"]({contract})" not in text:
        raise SystemExit(f"README entrypoint lacks its claimed contract reference: {entrypoint}")

print("dev-tools README contract checks passed")
PY
