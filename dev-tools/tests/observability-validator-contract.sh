#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

python3 - "$ROOT_DIR" <<'PY'
import importlib.util
import re
import sys
import tempfile
from pathlib import Path


root = Path(sys.argv[1])
validator_path = root / "dev-tools/observability/validate-observability-contract.py"
spec = importlib.util.spec_from_file_location("observability_validator", validator_path)
if spec is None or spec.loader is None:
    raise SystemExit(f"could not load {validator_path}")
validator = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = validator
spec.loader.exec_module(validator)

rules_path = root / "k8s/monitoring/prometheus-rules-firemud.yaml"
valid_text = rules_path.read_text(encoding="utf-8")


def findings_for(text, check):
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".yaml") as temp_file:
        temp_file.write(text)
        temp_file.flush()
        return check(Path(temp_file.name))


def require_message(findings, expected):
    messages = [finding.message for finding in findings]
    if expected not in messages:
        raise AssertionError(f"expected {expected!r}, got {messages!r}")


backup_rule = """        - alert: BackupPipelineNoRecentBackup
          expr: backup_pipeline_recent_backup_slo_breached > 0
"""
if backup_rule not in valid_text:
    raise AssertionError("canonical BackupPipelineNoRecentBackup expression was not found")
missing_backup_expr = valid_text.replace(
    backup_rule,
    """        - alert: BackupPipelineNoRecentBackup
""",
    1,
)
require_message(
    findings_for(missing_backup_expr, validator._validate_reference_prometheus_rules),
    "BackupPipelineNoRecentBackup is missing expr",
)

for empty_expression in ('expr: |', 'expr: ""'):
    empty_backup_expr = valid_text.replace(
        "expr: backup_pipeline_recent_backup_slo_breached > 0",
        empty_expression,
        1,
    )
    require_message(
        findings_for(empty_backup_expr, validator._validate_reference_prometheus_rules),
        "BackupPipelineNoRecentBackup is missing expr",
    )


owner_invalid = re.sub(
    r"(- alert: RecoveryReopenAttemptBlocked\b[\s\S]*?\n            owner:) infra",
    r"\1 platform",
    valid_text,
    count=1,
)
if owner_invalid == valid_text:
    raise AssertionError("failed to prepare Recovery owner negative case")
require_message(
    findings_for(owner_invalid, validator._validate_reference_prometheus_rules),
    "RecoveryReopenAttemptBlocked must use owner=infra for recovery incidents",
)

complete_reopen_expr = 'increase(recovery_reopen_attempt_total{result="blocked",reason="incomplete_convergence"}[5m]) > 0'
if complete_reopen_expr not in valid_text:
    raise AssertionError("canonical blocked reopen expression was not found")
bare_selector_invalid = valid_text.replace(
    complete_reopen_expr,
    'recovery_reopen_attempt_total{result="blocked",reason="incomplete_convergence"}',
    1,
)
require_message(
    findings_for(bare_selector_invalid, validator._validate_reference_prometheus_rules),
    "RecoveryReopenAttemptBlocked must query blocked recovery reopen attempts with reason=incomplete_convergence",
)

environment_record = """        - record: recovery_environment_convergence_blocked
          expr: |
            max by (environment) (recovery_participant_convergence_blocked)"""
if environment_record not in valid_text:
    raise AssertionError("canonical environment recovery recording was not found")
recording_scope_invalid = valid_text.replace(
    environment_record,
    """        - record: recovery_environment_convergence_blocked
          expr: recovery_participant_convergence_blocked""",
    1,
)
recording_scope_invalid += """
        - record: unrelated_recovery_record
          expr: |
            max by (environment) (recovery_participant_convergence_blocked)
"""
require_message(
    findings_for(recording_scope_invalid, validator._validate_reference_prometheus_recordings),
    "environment blocked-convergence recording must aggregate recovery_participant_convergence_blocked with max by (environment)",
)

print("observability validator contract checks passed")
PY
