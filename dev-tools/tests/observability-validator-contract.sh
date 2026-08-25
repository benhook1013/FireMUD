#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if ! command -v kubectl >/dev/null 2>&1; then
  echo "kubectl is required to render the observability monitoring overlays" >&2
  exit 2
fi

required_published_render="$(kubectl kustomize "$ROOT_DIR/k8s/overlays/monitoring/independent-required-prometheus-published")"
required_omitted_render="$(kubectl kustomize "$ROOT_DIR/k8s/overlays/monitoring/independent-required-prometheus-omitted")"
independent_omitted_render="$(kubectl kustomize "$ROOT_DIR/k8s/overlays/monitoring/independent-omitted")"
if ! grep -Fqx -- "- alert: ObservabilityDeadmanHeartbeatStale" <(awk '{ sub(/^[[:space:]]*/, ""); print }' <<<"$required_published_render"); then
  echo "published independent-required monitoring overlay is missing the required ObservabilityDeadmanHeartbeatStale alert" >&2
  exit 1
fi
if ! grep -Fqx -- "- alert: ObservabilityDeadmanHeartbeatMissing" <(awk '{ sub(/^[[:space:]]*/, ""); print }' <<<"$required_published_render"); then
  echo "published independent-required monitoring overlay is missing the required ObservabilityDeadmanHeartbeatMissing alert" >&2
  exit 1
fi
shared_alerts="$(sed -n 's/^[[:space:]]*- alert: \([^[:space:]]\+\)[[:space:]]*$/\1/p' "$ROOT_DIR/k8s/monitoring/prometheus-rules-firemud.yaml")"
if [[ -z "${shared_alerts//[[:space:]]/}" ]]; then
  echo "shared Prometheus rules parsing yielded no alert names" >&2
  exit 1
fi
for render in "$required_published_render" "$required_omitted_render" "$independent_omitted_render"; do
  while IFS= read -r alert_name; do
    if ! grep -Fqx -- "- alert: $alert_name" <(awk '{ sub(/^[[:space:]]*/, ""); print }' <<<"$render"); then
      echo "monitoring overlay render is missing shared alert $alert_name" >&2
      exit 1
    fi
  done <<<"$shared_alerts"
done
for render in "$required_omitted_render" "$independent_omitted_render"; do
  for profile_alert in ObservabilityDeadmanHeartbeatStale ObservabilityDeadmanHeartbeatMissing; do
    if grep -Fqx -- "- alert: $profile_alert" <(awk '{ sub(/^[[:space:]]*/, ""); print }' <<<"$render"); then
      echo "a non-published or independent-omitted monitoring overlay installed $profile_alert" >&2
      exit 1
    fi
  done
done

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

expected_ampersand_anchor = "backup-verification--restoration-testing"
actual_ampersand_anchor = validator._github_anchor_from_heading(
    "Backup Verification & Restoration Testing"
)
if actual_ampersand_anchor != expected_ampersand_anchor:
    raise AssertionError(
        f"GitHub ampersand anchor mismatch: {actual_ampersand_anchor!r}"
    )

expected_slash_anchor = "jaeger--opentelemetry-collector-down"
actual_slash_anchor = validator._github_anchor_from_heading(
    "Jaeger / OpenTelemetry Collector Down"
)
if actual_slash_anchor != expected_slash_anchor:
    raise AssertionError(
        f"GitHub slash anchor mismatch: {actual_slash_anchor!r}"
    )

rules_path = root / "k8s/monitoring/prometheus-rules-firemud.yaml"
valid_text = rules_path.read_text(encoding="utf-8")
if "ObservabilityDeadmanHeartbeatStale" in valid_text:
    raise AssertionError(
        "shared Prometheus rules must not install the profile-dependent deadman alert"
    )

required_rules_path = (
    root
    / "k8s/overlays/monitoring/independent-required-prometheus-published/"
    / "prometheus-rules-firemud-independent-required.yaml"
)
required_rules_text = required_rules_path.read_text(encoding="utf-8")
published_overlay_findings = validator._validate_reference_prometheus_rules(
    required_rules_path,
    {"ObservabilityDeadmanHeartbeatStale"},
    allow_profile_dependent_alerts=True,
)
if published_overlay_findings:
    raise AssertionError(
        "published profile overlay was rejected when profile-dependent alerts were allowed: "
        f"{published_overlay_findings!r}"
    )
deadman_start = required_rules_text.find(
    "        - alert: ObservabilityDeadmanHeartbeatStale"
)
if deadman_start == -1:
    raise AssertionError("ObservabilityDeadmanHeartbeatStale alert is missing")
deadman_next = required_rules_text.find("        - alert:", deadman_start + 1)
deadman_rule = (
    required_rules_text[deadman_start:]
    if deadman_next == -1
    else required_rules_text[deadman_start:deadman_next]
)
if (
    'expr: observability_deadman_stale{profile="independent-required"} == 1'
    not in deadman_rule
):
    raise AssertionError("deadman stale alert must fire on a published stale value of 1")
if "for: 0m" not in deadman_rule:
    raise AssertionError("deadman stale alert must retain its zero-minute hold")
if "for: 2m" in deadman_rule or "> 180" in deadman_rule:
    raise AssertionError("deadman alert must not hard-code the legacy 180s/2m timing")
missing_start = required_rules_text.find(
    "        - alert: ObservabilityDeadmanHeartbeatMissing"
)
if missing_start == -1:
    raise AssertionError("ObservabilityDeadmanHeartbeatMissing alert is missing")
missing_next = required_rules_text.find("        - alert:", missing_start + 1)
missing_rule = (
    required_rules_text[missing_start:]
    if missing_next == -1
    else required_rules_text[missing_start:missing_next]
)
if 'expr: absent(observability_deadman_stale{profile="independent-required"})' not in missing_rule:
    raise AssertionError("deadman missing alert must fail closed on an absent required-profile stale mirror")
if "for: 1m" not in missing_rule:
    raise AssertionError("deadman missing alert must retain its one-minute hold")

for alert_name in (
    "PlayerFlowCanaryLoginFailed",
    "PlayerFlowCanaryCommandFailed",
    "PlayerFlowCanaryLatencyHigh",
):
    start = valid_text.find(f"        - alert: {alert_name}")
    if start == -1:
        raise AssertionError(f"{alert_name} alert is missing")
    next_rule = valid_text.find("        - alert:", start + 1)
    block = valid_text[start:] if next_rule == -1 else valid_text[start:next_rule]
    if "\n            service:" in block:
        raise AssertionError(f"{alert_name} must not hard-code one service across public paths")
    if "path: '{{ $labels.path }}'" not in block or "target: '{{ $labels.target }}'" not in block:
        raise AssertionError(f"{alert_name} must retain failing path and target labels")
    if "playerflow_canary_last_run_timestamp_seconds" not in block:
        raise AssertionError(f"{alert_name} must gate on canary run freshness")
    if "playerflow_canary_freshness_budget_seconds" not in block:
        raise AssertionError(f"{alert_name} must use the profile-derived freshness budget")

budget_missing_start = valid_text.find(
    "        - alert: PlayerFlowCanaryFreshnessBudgetMissing"
)
if budget_missing_start == -1:
    raise AssertionError("PlayerFlowCanaryFreshnessBudgetMissing fixture is missing")
budget_missing_next = valid_text.find("        - alert:", budget_missing_start + 1)
budget_missing_rule = (
    valid_text[budget_missing_start:]
    if budget_missing_next == -1
    else valid_text[budget_missing_start:budget_missing_next]
)
for required_text in (
    "count by (profile) (playerflow_canary_success)",
    "unless on (profile)",
    "count by (profile) (playerflow_canary_freshness_budget_seconds)",
    "profile: '{{ $labels.profile }}'",
    "for: 2m",
):
    if required_text not in budget_missing_rule:
        raise AssertionError(
            "PlayerFlowCanaryFreshnessBudgetMissing is missing "
            + repr(required_text)
        )

stale_start = valid_text.find("        - alert: PlayerFlowCanaryEvidenceStale")
if stale_start == -1:
    raise AssertionError("PlayerFlowCanaryEvidenceStale fixture is missing")
stale_next = valid_text.find("        - alert:", stale_start + 1)
without_stale = (
    valid_text[:stale_start]
    + ("" if stale_next == -1 else valid_text[stale_next:])
)


def findings_for(text, check):
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".yaml") as temp_file:
        temp_file.write(text)
        temp_file.flush()
        return check(Path(temp_file.name))


def require_message(findings, expected):
    messages = [finding.message for finding in findings]
    if expected not in messages:
        raise AssertionError(f"expected {expected!r}, got {messages!r}")


profile_dependent_alert = """    - name: firemud.alerts.profile-dependent
      rules:
        - alert: ObservabilityDeadmanHeartbeatStale
          expr: observability_deadman_stale{profile="independent-required"} == 1
          labels:
            service: external-monitoring
            severity: P0
            owner: platform
            runbook: design/architecture/system-architecture-observability-incident-runbook.md#deadman-freshness-contract
"""
base_with_profile_dependent_alert = valid_text.replace(
    "    - name: firemud.alerts.observability\n",
    profile_dependent_alert + "    - name: firemud.alerts.observability\n",
    1,
)
require_message(
    findings_for(
        base_with_profile_dependent_alert,
        validator._validate_reference_prometheus_rules,
    ),
    "base Prometheus rules must not include profile-dependent alert ObservabilityDeadmanHeartbeatStale; install it only through the matching profile overlay",
)


require_message(
    findings_for(without_stale, validator._validate_reference_prometheus_rules),
    "reference rules are missing required alerts: PlayerFlowCanaryEvidenceStale",
)
empty_required_findings = findings_for(
    without_stale,
    lambda path: validator._validate_reference_prometheus_rules(path, set()),
)
if empty_required_findings:
    raise AssertionError(
        "an explicit empty required-alert set must not restore default requirements: "
        f"{empty_required_findings!r}"
    )


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

quoted_alert_key = valid_text.replace(
    "        - alert: BackupPipelineNoRecentBackup",
    '        - "alert": BackupPipelineNoRecentBackup',
    1,
)
quoted_alert_findings = findings_for(
    quoted_alert_key,
    validator._validate_reference_prometheus_rules,
)
if quoted_alert_findings:
    raise AssertionError(f"quoted alert key was not canonically validated: {quoted_alert_findings!r}")

quoted_record_key = valid_text.replace(
    "        - record: backup_artifact_lineage_invalid",
    '        - "record": backup_artifact_lineage_invalid',
    1,
)
quoted_record_findings = findings_for(
    quoted_record_key,
    validator._validate_reference_prometheus_recordings,
)
if quoted_record_findings:
    raise AssertionError(f"quoted record key was not canonically validated: {quoted_record_findings!r}")

quoted_rules_key = valid_text.replace(
    "      rules:",
    '      "rules":',
    1,
)
quoted_rules_findings = findings_for(
    quoted_rules_key,
    validator._validate_reference_prometheus_rules,
)
if quoted_rules_findings:
    raise AssertionError(f"quoted rules key was not canonically validated: {quoted_rules_findings!r}")

unsupported_rules_key_shapes = (
    (
        "explicit rules key",
        valid_text.replace(
            "      rules:",
            "      ? rules\n      :",
            1,
        ),
        "unsupported explicit rules key shape; the dependency-free validator cannot safely inspect this YAML shape",
    ),
    (
        "sequence explicit rules key",
        valid_text.replace(
            "    - name: firemud.recording.tick\n      rules:",
            "    - ? rules\n      : []\n    - name: firemud.recording.tick\n      rules:",
            1,
        ),
        "unsupported explicit rules key shape; the dependency-free validator cannot safely inspect this YAML shape",
    ),
    (
        "inline flow rules mapping",
        valid_text.replace(
            "    - name: firemud.recording.tick\n      rules:",
            "    - {name: firemud.invalid, rules: []}\n    - name: firemud.recording.tick\n      rules:",
            1,
        ),
        "unsupported flow rules key shape; the dependency-free validator cannot safely inspect this YAML shape",
    ),
    (
        "multiline flow rules mapping",
        valid_text.replace(
            "    - name: firemud.recording.tick\n      rules:",
            "    - {\n        name: firemud.invalid,\n        rules: []\n      }\n    - name: firemud.recording.tick\n      rules:",
            1,
        ),
        "unsupported flow rules key shape; the dependency-free validator cannot safely inspect this YAML shape",
    ),
)
for _, unsupported_rules_shape, expected_message in unsupported_rules_key_shapes:
    require_message(
        findings_for(unsupported_rules_shape, validator._validate_reference_prometheus_rules),
        expected_message,
    )

unrecognized_rule_starts = (
    (
        "flow mapping",
        valid_text.replace(
            "        - alert: BackupPipelineNoRecentBackup",
            "        - {alert: BackupPipelineNoRecentBackup}",
            1,
        ),
    ),
    (
        "anchor",
        valid_text.replace(
            "        - alert: BackupPipelineNoRecentBackup",
            "        - &backup_rule\n          alert: BackupPipelineNoRecentBackup",
            1,
        ),
    ),
    (
        "alias",
        valid_text.replace(
            "        - alert: BackupPipelineNoRecentBackup",
            "        - *backup_rule",
            1,
        ),
    ),
    (
        "explicit mapping",
        valid_text.replace(
            "        - alert: BackupPipelineNoRecentBackup",
            "        - ? alert\n          : BackupPipelineNoRecentBackup",
            1,
        ),
    ),
    (
        "unrecognized mapping",
        valid_text.replace(
            "        - alert: BackupPipelineNoRecentBackup",
            "        - name: BackupPipelineNoRecentBackup",
            1,
        ),
    ),
)
for _, invalid_rule_start in unrecognized_rule_starts:
    require_message(
        findings_for(invalid_rule_start, validator._validate_reference_prometheus_rules),
        "unrecognized alert rule sequence entry; the dependency-free validator cannot safely inspect this YAML shape",
    )

unrecognized_rules_collections = (
    valid_text + "\n    - name: invalid-inline-rules\n      rules: []\n",
    valid_text
    + "\n    - name: invalid-block-rules\n      rules:\n        unexpected: true\n",
)
for invalid_rules_collection in unrecognized_rules_collections:
    require_message(
        findings_for(invalid_rules_collection, validator._validate_reference_prometheus_rules),
        "unrecognized alert rule sequence entry; the dependency-free validator cannot safely inspect this YAML shape",
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

nested_collection_expressions = (
    "expr:\n            -",
    "expr:\n            ? query\n            : backup_pipeline_recent_backup_slo_breached",
)
for collection_expression in nested_collection_expressions:
    invalid_backup_expr = valid_text.replace(
        "expr: backup_pipeline_recent_backup_slo_breached > 0",
        collection_expression,
        1,
    )
    require_message(
        findings_for(invalid_backup_expr, validator._validate_reference_prometheus_rules),
        "BackupPipelineNoRecentBackup is missing expr",
    )

snippet_path = root / "design/observability/grafana/backup-alerts-snippets.md"
valid_snippet = snippet_path.read_text(encoding="utf-8")

playerflow_snippet_path = root / "design/observability/grafana/player-experience-alerts-snippets.md"
valid_playerflow_snippet = playerflow_snippet_path.read_text(encoding="utf-8")


def replace_canary_label(text, alert_name, label, replacement):
    rule_match = re.search(
        rf"(?ms)^[ \t]*- alert: {re.escape(alert_name)}\n"
        rf"(?P<body>.*?)(?=^[ \t]*- alert: |\Z)",
        text,
    )
    if rule_match is None:
        raise AssertionError(f"{alert_name} rule is missing from test fixture")
    body = rule_match.group("body")
    label_match = re.search(
        rf"(?m)^(?P<indent>[ \t]+){re.escape(label)}:.*$",
        body,
    )
    if label_match is None:
        if replacement is None:
            raise AssertionError(f"{alert_name} label {label} is missing from test fixture")
        labels_header = re.search(r"(?m)^(?P<indent>[ \t]+)labels:\s*$", body)
        if labels_header is None:
            raise AssertionError(f"{alert_name} labels block is missing from test fixture")
        label_indent = labels_header.group("indent") + "  "
        updated_body = (
            body[: labels_header.end()]
            + f"\n{label_indent}{label}: {replacement}"
            + body[labels_header.end() :]
        )
    elif replacement is None:
        updated_body = body[: label_match.start()] + body[label_match.end() :]
    else:
        updated_body = (
            body[: label_match.start()]
            + f"{label_match.group('indent')}{label}: {replacement}"
            + body[label_match.end() :]
        )
    return text[: rule_match.start("body")] + updated_body + text[rule_match.end("body") :]


canary_mutations = (
    ("component", None, "PlayerFlowCanaryLoginFailed must use labels.component=playerflow-canary"),
    ("component", "entrypath", "PlayerFlowCanaryLoginFailed must use labels.component=playerflow-canary"),
    ("path", None, "PlayerFlowCanaryLoginFailed must use labels.path={{ $labels.path }}"),
    ("path", "'{{ $labels.other_path }}'", "PlayerFlowCanaryLoginFailed must use labels.path={{ $labels.path }}"),
    ("target", None, "PlayerFlowCanaryLoginFailed must use labels.target={{ $labels.target }}"),
    ("target", "'{{ $labels.other_target }}'", "PlayerFlowCanaryLoginFailed must use labels.target={{ $labels.target }}"),
)

for source_text, check in (
    (valid_playerflow_snippet, validator._validate_alert_snippet),
    (valid_text, validator._validate_reference_prometheus_rules),
):
    baseline_findings = findings_for(source_text, check)
    if baseline_findings:
        raise AssertionError(f"valid canary rules were rejected: {baseline_findings!r}")
    for label, replacement, expected_message in canary_mutations:
        mutated = replace_canary_label(
            source_text,
            "PlayerFlowCanaryLoginFailed",
            label,
            replacement,
        )
        mutated_findings = findings_for(mutated, check)
        require_message(mutated_findings, expected_message)

for source_text, check in (
    (valid_playerflow_snippet, validator._validate_alert_snippet),
    (valid_text, validator._validate_reference_prometheus_rules),
):
    for alert_name in (
        "PlayerFlowCanaryLoginFailed",
        "PlayerFlowCanaryCommandFailed",
        "PlayerFlowCanaryLatencyHigh",
    ):
        mutated = replace_canary_label(
            source_text,
            alert_name,
            "service",
            "'prometheus'",
        )
        mutated_findings = findings_for(mutated, check)
        require_message(
            mutated_findings,
            f"{alert_name} must not set labels.service on a cross-path canary alert",
        )

latest_canary_expressions = (
    (
        "PlayerFlowCanaryLoginFailed",
        'playerflow_canary_success{flow="login"} == 0',
    ),
    (
        "PlayerFlowCanaryCommandFailed",
        'playerflow_canary_success{flow="command"} == 0',
    ),
    (
        "PlayerFlowCanaryLatencyHigh",
        'playerflow_canary_latency_ms{flow="command"} > 1000',
    ),
)
for source_text in (valid_playerflow_snippet, valid_text):
    for alert_name, expected_expression in latest_canary_expressions:
        rule_match = re.search(
            rf"(?ms)^[ \t]*- alert: {re.escape(alert_name)}\n"
            rf"(?P<body>.*?)(?=^[ \t]*- alert: |\Z)",
            source_text,
        )
        if rule_match is None:
            raise AssertionError(f"{alert_name} latest-result block is missing")
        block = rule_match.group("body")
        if expected_expression not in block:
            raise AssertionError(
                f"{alert_name} must evaluate the latest canary result directly"
            )
        if "max_over_time(" in block:
            raise AssertionError(
                f"{alert_name} must not retain historical canary samples"
            )

stale_canary_mutations = (
    (
        "component",
        None,
        "PlayerFlowCanaryEvidenceStale must use labels.component=playerflow-canary",
    ),
    (
        "path",
        None,
        "PlayerFlowCanaryEvidenceStale must use labels.path={{ $labels.path }}",
    ),
    (
        "target",
        None,
        "PlayerFlowCanaryEvidenceStale must use labels.target={{ $labels.target }}",
    ),
    (
        "service",
        "'alertmanager'",
        "PlayerFlowCanaryEvidenceStale must use labels.service=prometheus",
    ),
)
for source_text, check in (
    (valid_playerflow_snippet, validator._validate_alert_snippet),
    (valid_text, validator._validate_reference_prometheus_rules),
):
    for label, replacement, expected_message in stale_canary_mutations:
        mutated = replace_canary_label(
            source_text,
            "PlayerFlowCanaryEvidenceStale",
            label,
            replacement,
        )
        mutated_findings = findings_for(mutated, check)
        require_message(mutated_findings, expected_message)
    missing_service = replace_canary_label(
        source_text,
        "PlayerFlowCanaryEvidenceStale",
        "service",
        None,
    )
    missing_service_findings = findings_for(missing_service, check)
    missing_service_message = (
        "alert rule is missing required labels: service"
        if check == validator._validate_alert_snippet
        else "PlayerFlowCanaryEvidenceStale is missing required labels: service"
    )
    require_message(missing_service_findings, missing_service_message)

for source_text in (valid_playerflow_snippet, valid_text):
    for alert_name in (
        "PlayerFlowCanaryLoginFailed",
        "PlayerFlowCanaryCommandFailed",
        "PlayerFlowCanaryLatencyHigh",
        "PlayerFlowCanaryEvidenceStale",
    ):
        start = source_text.find(f"alert: {alert_name}")
        if start == -1:
            raise AssertionError(f"{alert_name} profile-matching block is missing")
        next_alert = source_text.find("alert:", start + len(f"alert: {alert_name}"))
        block = source_text[start:] if next_alert == -1 else source_text[start:next_alert]
        if "profile: '{{ $labels.profile }}'" not in block:
            raise AssertionError(f"{alert_name} must preserve the bounded profile label")
        if "on (profile) group_left()" not in block:
            raise AssertionError(f"{alert_name} must match freshness by profile")
        unsafe_scalar = "scalar(" + "playerflow_canary_freshness_budget_seconds" + ")"
        if unsafe_scalar in block:
            raise AssertionError(f"{alert_name} must not use an unscoped scalar freshness budget")
        if alert_name != "PlayerFlowCanaryEvidenceStale":
            if not re.search(
                r"time\(\)\s*-\s*playerflow_canary_last_run_timestamp_seconds"
                r"(?:\{[^}]*\})?\s*>=\s*0",
                block,
            ):
                raise AssertionError(
                    f"{alert_name} must reject future canary timestamps"
                )

    if "playerflow_canary_last_run_timestamp_seconds" not in source_text:
        raise AssertionError("canary alert source is missing the run timestamp metric")
    if "playerflow_canary_freshness_budget_seconds" not in source_text:
        raise AssertionError("canary alert source is missing the freshness budget metric")
    stale_match = re.search(
        rf"(?ms)^[ \t]*- alert: PlayerFlowCanaryEvidenceStale\n"
        rf"(?P<body>.*?)(?=^[ \t]*- alert: |\Z)",
        source_text,
    )
    if stale_match is None:
        raise AssertionError("canary alert source is missing PlayerFlowCanaryEvidenceStale")
    stale_body = stale_match.group("body")
    for required_text in (
        "service: prometheus",
        "flow: '{{ $labels.flow }}'",
        "path: '{{ $labels.path }}'",
        "target: '{{ $labels.target }}'",
        "time() - playerflow_canary_last_run_timestamp_seconds",
        "playerflow_canary_freshness_budget_seconds",
    ):
        if required_text not in stale_body:
            raise AssertionError(
                f"PlayerFlowCanaryEvidenceStale is missing {required_text!r}"
            )
    if not re.search(
        r"time\(\)\s*-\s*playerflow_canary_last_run_timestamp_seconds\s*<\s*0",
        stale_body,
    ):
        raise AssertionError(
            "PlayerFlowCanaryEvidenceStale must fire for future canary timestamps"
        )
    if "or on (flow, path, target, profile)" not in stale_body:
        raise AssertionError(
            "PlayerFlowCanaryEvidenceStale must preserve full canary label matching"
        )

standalone_alert = """alert: StandaloneBackupAlert
expr: backup_pipeline_recent_backup_slo_breached > 0
labels:
  service: postgres-backup
  severity: P1
  owner: infra
  runbook: design/architecture/system-architecture-backup-recovery.md#restore-workflow-summary
"""
standalone_alert_entries = validator._split_alert_rules(standalone_alert)
if len(standalone_alert_entries) != 1:
    raise AssertionError(f"standalone alert mapping was not parsed as one entry: {standalone_alert_entries!r}")
standalone_alert_entry = standalone_alert_entries[0]
if standalone_alert_entry.key != "alert" or standalone_alert_entry.name != "StandaloneBackupAlert":
    raise AssertionError(f"standalone alert mapping was parsed incorrectly: {standalone_alert_entry!r}")

standalone_record = """record: standalone_recording
expr: backup_artifact_lineage_valid
"""
standalone_record_entries = validator._split_recording_rules(standalone_record)
if len(standalone_record_entries) != 1:
    raise AssertionError(f"standalone recording mapping was not parsed as one entry: {standalone_record_entries!r}")
standalone_record_entry = standalone_record_entries[0]
if standalone_record_entry.key != "record" or standalone_record_entry.name != "standalone_recording":
    raise AssertionError(f"standalone recording mapping was parsed incorrectly: {standalone_record_entry!r}")

standalone_document_markers = "---\n" + standalone_alert + "...\n"
document_marker_entries = validator._split_alert_rules(standalone_document_markers)
if len(document_marker_entries) != 1 or document_marker_entries[0].name != "StandaloneBackupAlert":
    raise AssertionError(f"valid standalone alert document was not preserved: {document_marker_entries!r}")

standalone_snippet = "```yaml\n" + standalone_alert + "```\n"
standalone_findings = findings_for(standalone_snippet, validator._validate_alert_snippet)
if standalone_findings:
    raise AssertionError(f"valid standalone alert mapping was rejected: {standalone_findings!r}")

standalone_missing_labels = standalone_snippet.replace(
    "  runbook: design/architecture/system-architecture-backup-recovery.md#restore-workflow-summary\n",
    "",
    1,
)
require_message(
    findings_for(standalone_missing_labels, validator._validate_alert_snippet),
    "alert rule is missing required labels: runbook",
)

standalone_missing_expr = standalone_snippet.replace(
    "expr: backup_pipeline_recent_backup_slo_breached > 0\n",
    "",
    1,
)
require_message(
    findings_for(standalone_missing_expr, validator._validate_alert_snippet),
    "alert rule is missing expr",
)

standalone_invalid_expression = standalone_snippet.replace(
    "expr: backup_pipeline_recent_backup_slo_breached > 0",
    "expr: tick_execution_time_ms_p99 > 5",
    1,
)
require_message(
    findings_for(standalone_invalid_expression, validator._validate_alert_snippet),
    "expression compares an `_ms` metric against 5.0; this looks like seconds, but `_ms` metrics are milliseconds",
)

standalone_invalid_severity = standalone_snippet.replace(
    "severity: P1",
    "severity: P3",
    1,
)
require_message(
    findings_for(standalone_invalid_severity, validator._validate_alert_snippet),
    "alert rule has invalid severity='P3'; expected one of ['P0', 'P1', 'P2']",
)

standalone_invalid_runbook = standalone_snippet.replace(
    "runbook: design/architecture/system-architecture-backup-recovery.md#restore-workflow-summary",
    "runbook: not-a-runbook",
    1,
)
require_message(
    findings_for(standalone_invalid_runbook, validator._validate_alert_snippet),
    "alert rule runbook label must be a design doc anchor (design/...md#section); got 'not-a-runbook'",
)

standalone_structure_issue = (
    "standalone YAML rule form must contain exactly one supported document/root rule; "
    "the dependency-free validator cannot safely inspect this YAML shape"
)
invalid_standalone_structures = (
    standalone_alert + "alert: TrailingAlert\n",
    standalone_alert + "record: trailing_record\n",
    standalone_alert + "---\nrecord:\n  hidden: malformed\n",
    standalone_alert + "...\nalert:\n",
    "---\n---\n" + standalone_alert,
    standalone_alert + "name: trailing_mapping\n",
    standalone_alert + "rules:\n  - alert:\n",
)
for invalid_structure in invalid_standalone_structures:
    require_message(
        findings_for("```yaml\n" + invalid_structure + "```\n", validator._validate_alert_snippet),
        standalone_structure_issue,
    )

invalid_standalone_alert_names = (
    ("missing", "alert:", "alert rule is missing name"),
    ("null", "alert: null", "alert rule is missing name"),
    ("tilde null", "alert: ~", "alert rule is missing name"),
    ("blank quoted", "alert: \"\"", "alert rule is missing name"),
    ("whitespace quoted", 'alert: "   "', "alert rule is missing name"),
    (
        "mapping",
        "alert: {name: HiddenAlert}",
        "unrecognized alert rule sequence entry; the dependency-free validator cannot safely inspect this YAML shape",
    ),
    (
        "sequence",
        "alert: [HiddenAlert]",
        "unrecognized alert rule sequence entry; the dependency-free validator cannot safely inspect this YAML shape",
    ),
)
for _, invalid_name, expected_message in invalid_standalone_alert_names:
    invalid_name_snippet = standalone_snippet.replace(
        "alert: StandaloneBackupAlert",
        invalid_name,
        1,
    )
    require_message(
        findings_for(invalid_name_snippet, validator._validate_alert_snippet),
        expected_message,
    )

invalid_standalone_record_structures = (
    standalone_record + "record: trailing_record\n",
    standalone_record + "alert: trailing_alert\n",
    standalone_record + "---\nrecord:\n  hidden: malformed\n",
    standalone_record + "name: trailing_mapping\n",
    standalone_record + "rules:\n  - record:\n",
)
for invalid_structure in invalid_standalone_record_structures:
    require_message(
        findings_for(invalid_structure, validator._validate_reference_prometheus_recordings),
        standalone_structure_issue,
    )

invalid_standalone_record_names = (
    ("missing", "record:", "recording rule is missing name"),
    ("null", "record: null", "recording rule is missing name"),
    ("tilde null", "record: ~", "recording rule is missing name"),
    ("blank quoted", "record: \"\"", "recording rule is missing name"),
    ("whitespace quoted", 'record: "   "', "recording rule is missing name"),
    (
        "mapping",
        "record: {name: hidden_record}",
        "unrecognized record rule sequence entry; the dependency-free validator cannot safely inspect this YAML shape",
    ),
    (
        "sequence",
        "record: [hidden_record]",
        "unrecognized record rule sequence entry; the dependency-free validator cannot safely inspect this YAML shape",
    ),
)
for _, invalid_name, expected_message in invalid_standalone_record_names:
    invalid_record_name = standalone_record.replace(
        "record: standalone_recording",
        invalid_name,
        1,
    )
    require_message(
        findings_for(invalid_record_name, validator._validate_reference_prometheus_recordings),
        expected_message,
    )

unsupported_standalone_roots = (
    (
        "flow mapping",
        "{alert: StandaloneBackupAlert, expr: backup_pipeline_recent_backup_slo_breached > 0}",
    ),
    ("anchor", "&standalone_alert\nalert: StandaloneBackupAlert"),
    ("alias", "*standalone_alert"),
    ("explicit mapping", "? alert\n: StandaloneBackupAlert"),
    ("malformed header", "alert StandaloneBackupAlert"),
    ("inline alias", "alert: *standalone_alert"),
    ("unrecognized mapping", "name: StandaloneBackupAlert\nexpr: backup_pipeline_recent_backup_slo_breached > 0"),
)
for _, unsupported_root in unsupported_standalone_roots:
    unsupported_snippet = "```yaml\n" + unsupported_root + "\n```\n"
    require_message(
        findings_for(unsupported_snippet, validator._validate_alert_snippet),
        "unrecognized alert rule sequence entry; the dependency-free validator cannot safely inspect this YAML shape",
    )

snippet_rule_shapes = (
    ("flow mapping", "- {alert: BackupPipelineNoRecentBackup}"),
    ("anchor", "- &backup_rule\n  alert: BackupPipelineNoRecentBackup"),
    ("alias", "- *backup_rule"),
    ("explicit mapping", "- ? alert\n  : BackupPipelineNoRecentBackup"),
    ("unrecognized mapping", "- name: BackupPipelineNoRecentBackup"),
)
for _, rule_start in snippet_rule_shapes:
    invalid_shape_snippet = valid_snippet.replace(
        "- alert: BackupPipelineNoRecentBackup",
        rule_start,
        1,
    )
    require_message(
        findings_for(invalid_shape_snippet, validator._validate_alert_snippet),
        "unrecognized alert rule sequence entry; the dependency-free validator cannot safely inspect this YAML shape",
    )

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

for collection_expression in nested_collection_expressions:
    invalid_snippet_expr = valid_snippet.replace(
        "expr: backup_pipeline_recent_backup_slo_breached > 0",
        collection_expression,
        1,
    )
    require_message(
        findings_for(invalid_snippet_expr, validator._validate_alert_snippet),
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

blocked_record = """        - record: recovery_participant_convergence_blocked
          expr: |
            (
              recovery_participant_convergence_state{state="blocked"} == 1
              and on (environment)
              recovery_required_participant_inventory_complete == 1
            )
            or on (environment, participant)
            (
              recovery_participant_convergence_coverage_missing > 0
            )"""
if blocked_record not in valid_text:
    raise AssertionError("canonical participant blocked-convergence recording was not found")
unguarded_blocked_record = valid_text.replace(
    blocked_record,
    """        - record: recovery_participant_convergence_blocked
          expr: |
            recovery_participant_convergence_state{state="blocked"} == 1""",
    1,
)
require_message(
    findings_for(unguarded_blocked_record, validator._validate_reference_prometheus_recordings),
    "blocked convergence recording must combine current blocked participant state under a complete inventory with fail-closed coverage-missing state",
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

coverage_record = """        - record: recovery_participant_convergence_coverage_missing
          expr: |
            (
              (
                recovery_required_participant_inventory == 1
                and on (environment)
                recovery_required_participant_inventory_complete == 1
              )
              unless on (environment, participant)
              (
                count by (environment, participant) (
                  recovery_participant_convergence_coverage
                ) > 0
              )
            )
            or
            label_replace(
              recovery_required_participant_inventory_complete != bool 1,
              "participant", "__environment__", "", ""
            )
            or
            label_replace(
              (
                count by (environment) (
                  recovery_required_participant_inventory
                )
                unless on (environment)
                (
                  recovery_required_participant_inventory_complete == 1
                )
              ),
              "participant", "__environment__", "", ""
            )
            or
            label_replace(
              (
                recovery_required_participant_inventory_complete == 1
                unless on (environment)
                (
                  count by (environment) (
                    recovery_required_participant_inventory
                  ) > 0
                )
              ),
              "participant", "__environment__", "", ""
            )"""
if coverage_record not in valid_text:
    raise AssertionError("canonical participant coverage recording was not found")
source_missing_record = """        - record: recovery_participant_convergence_source_missing
          expr: |
            label_replace(
              absent(recovery_required_participant_inventory_complete),
              "source_family", "inventory_complete", "", ""
            )
            or
            label_replace(
              absent(recovery_required_participant_inventory),
              "source_family", "participant_inventory", "", ""
            )
            or
            label_replace(
              absent(recovery_participant_convergence_coverage),
              "source_family", "participant_coverage", "", ""
            )"""
if source_missing_record not in valid_text:
    raise AssertionError("canonical participant source-missing recording was not found")
if validator._validate_reference_prometheus_recordings(rules_path):
    raise AssertionError("canonical participant coverage recordings were rejected")

invalid_coverage = valid_text.replace(
    coverage_record,
    """        - record: recovery_participant_convergence_coverage_missing
          expr: absent(recovery_participant_convergence_state)""",
    1,
)
require_message(
    findings_for(invalid_coverage, validator._validate_reference_prometheus_recordings),
    "participant coverage recording must compare authoritative required-participant inventory with the current participant coverage projection while preserving environment scope",
)

unguarded_inventory_coverage = valid_text.replace(
    """(
                recovery_required_participant_inventory == 1
                and on (environment)
                recovery_required_participant_inventory_complete == 1
              )""",
    "recovery_required_participant_inventory == 1",
    1,
)
if unguarded_inventory_coverage == valid_text:
    raise AssertionError("participant inventory completeness guard fixture did not mutate")
require_message(
    findings_for(unguarded_inventory_coverage, validator._validate_reference_prometheus_recordings),
    "participant coverage recording must compare authoritative required-participant inventory with the current participant coverage projection while preserving environment scope",
)

state_backed_coverage = valid_text.replace(
    "recovery_participant_convergence_coverage\n                ) > 0",
    "recovery_participant_convergence_state\n                ) > 0",
    1,
)
if state_backed_coverage == valid_text:
    raise AssertionError("state-backed participant coverage fixture did not mutate")
require_message(
    findings_for(state_backed_coverage, validator._validate_reference_prometheus_recordings),
    "participant coverage recording must compare authoritative required-participant inventory with the current participant coverage projection while preserving environment scope",
)

extra_coverage_branch = valid_text.replace(
    coverage_record,
    coverage_record + "\n            or\n            vector(1)",
    1,
)
require_message(
    findings_for(extra_coverage_branch, validator._validate_reference_prometheus_recordings),
    "participant coverage recording must compare authoritative required-participant inventory with the current participant coverage projection while preserving environment scope",
)

invalid_source_missing = valid_text.replace(
    source_missing_record,
    """        - record: recovery_participant_convergence_source_missing
          expr: absent(recovery_required_participant_inventory)""",
    1,
)
require_message(
    findings_for(invalid_source_missing, validator._validate_reference_prometheus_recordings),
    "participant source-missing recording must report globally absent inventory and coverage families with a stable source_family label",
)

invalid_coverage_alert = valid_text.replace(
    "expr: recovery_participant_convergence_coverage_missing > 0",
    "expr: absent(recovery_participant_convergence_state)",
    1,
)
require_message(
    findings_for(invalid_coverage_alert, validator._validate_reference_prometheus_rules),
    "RecoveryParticipantConvergenceCoverageMissing must use recovery_participant_convergence_coverage_missing > 0",
)

invalid_source_alert = valid_text.replace(
    "expr: recovery_participant_convergence_source_missing > 0",
    "expr: absent(recovery_required_participant_inventory)",
    1,
)
require_message(
    findings_for(invalid_source_alert, validator._validate_reference_prometheus_rules),
    "RecoveryParticipantConvergenceMetricsAbsent must use recovery_participant_convergence_source_missing > 0",
)

invalid_snippet_coverage = valid_snippet.replace(
    "expr: recovery_participant_convergence_coverage_missing > 0",
    "expr: absent(recovery_participant_convergence_state)",
    1,
)
require_message(
    findings_for(invalid_snippet_coverage, validator._validate_alert_snippet),
    "RecoveryParticipantConvergenceCoverageMissing must use recovery_participant_convergence_coverage_missing > 0",
)

invalid_snippet_source = valid_snippet.replace(
    "expr: recovery_participant_convergence_source_missing > 0",
    "expr: absent(recovery_required_participant_inventory)",
    1,
)
require_message(
    findings_for(invalid_snippet_source, validator._validate_alert_snippet),
    "RecoveryParticipantConvergenceMetricsAbsent must use recovery_participant_convergence_source_missing > 0",
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
    """        - record: backup_artifact_lineage_invalid
          expr:
            -""",
    """        - record: backup_artifact_lineage_invalid
          expr:
            ? query
            : backup_artifact_lineage_valid""",
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
