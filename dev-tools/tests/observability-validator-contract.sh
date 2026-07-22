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

empty_expressions = (
    'expr: |',
    'expr: |+',
    'expr: >+',
    'expr: |2',
    'expr: | # empty expression',
    'expr: |2- # empty expression',
    'expr: ""',
    'expr: null',
    'expr: null # empty expression',
    'expr: # empty expression',
    'expr: ~',
    'expr: !!null',
    'expr: !!null ""',
    'expr: !!str # empty expression',
    'expr: !!str ""',
    'expr: !!str "" # empty expression',
    'expr: &empty # empty expression',
    'expr: !<tag:yaml.org,2002:null> null',
    'expr: {}',
    'expr: []',
)
for empty_expression in empty_expressions:
    empty_backup_expr = valid_text.replace(
        "expr: backup_pipeline_recent_backup_slo_breached > 0",
        empty_expression,
        1,
    )
    require_message(
        findings_for(empty_backup_expr, validator._validate_reference_prometheus_rules),
        "BackupPipelineNoRecentBackup is missing expr",
    )

snippet_path = root / "design/observability/grafana/backup-alerts-snippets.md"
valid_snippet = snippet_path.read_text(encoding="utf-8")
invalid_snippet = valid_snippet.replace(
    "expr: backup_pipeline_recent_backup_slo_breached > 0",
    "expr: null",
    1,
)
tilde_fenced_snippet = invalid_snippet.replace("```yaml", "~~~yaml", 1).replace(
    "```", "~~~", 1
)
require_message(
    findings_for(tilde_fenced_snippet, validator._validate_alert_snippet),
    "alert rule is missing expr",
)
info_fenced_snippet = invalid_snippet.replace(
    "```yaml", '```yaml title="alerts"', 1
)
require_message(
    findings_for(info_fenced_snippet, validator._validate_alert_snippet),
    "alert rule is missing expr",
)

for empty_expression in empty_expressions:
    empty_snippet_expr = valid_snippet.replace(
        "expr: backup_pipeline_recent_backup_slo_breached > 0",
        empty_expression,
        1,
    )
    require_message(
        findings_for(empty_snippet_expr, validator._validate_alert_snippet),
        "alert rule is missing expr",
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

lineage_rule = """        - record: backup_artifact_lineage_invalid
          expr: |
            1 - backup_artifact_lineage_valid"""
if lineage_rule not in valid_text:
    raise AssertionError("canonical backup_artifact_lineage_invalid recording was not found")

invalid_lineage_rules = (
    """        - record: backup_artifact_lineage_invalid""",
    """        - record: backup_artifact_lineage_invalid
          expr: !<tag:yaml.org,2002:null> null""",
    """        - record: backup_artifact_lineage_invalid
          expr:
            !!null""",
    """        - record: backup_artifact_lineage_invalid
          expr:
            # empty expression""",
    """        - record: backup_artifact_lineage_invalid
          expr:
            &empty""",
    """        - record: backup_artifact_lineage_invalid
          expr:
            *empty""",
    """        - record: backup_artifact_lineage_invalid
          expr: {}""",
    """        - record: backup_artifact_lineage_invalid
          expr: []""",
    """        - record: backup_artifact_lineage_invalid
          expr:
            query: backup_artifact_lineage_valid""",
    """        - record: backup_artifact_lineage_invalid
          expr:
            - backup_artifact_lineage_valid""",
)
for invalid_lineage_rule in invalid_lineage_rules:
    invalid_lineage_expr = valid_text.replace(lineage_rule, invalid_lineage_rule, 1)
    require_message(
        findings_for(
            invalid_lineage_expr,
            validator._validate_reference_prometheus_recordings,
        ),
        "required backup recordings are missing expr: backup_artifact_lineage_invalid",
    )

duplicate_lineage_expr = valid_text.replace(
    lineage_rule,
    invalid_lineage_rules[1] + "\n" + lineage_rule,
    1,
)
duplicate_findings = findings_for(
    duplicate_lineage_expr,
    validator._validate_reference_prometheus_recordings,
)
require_message(
    duplicate_findings,
    "required backup recordings must be declared exactly once: backup_artifact_lineage_invalid",
)
require_message(
    duplicate_findings,
    "required backup recordings are missing expr: backup_artifact_lineage_invalid",
)

print("observability validator contract checks passed")
PY
