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
profile_alerts=(
  ObservabilityDeadmanHeartbeatStale
  ObservabilityDeadmanHeartbeatMissing
  WebSocketEntryPathBlackboxUnavailable
  TelnetEntryPathBlackboxUnavailable
)
for profile_alert in "${profile_alerts[@]}"; do
  if grep -Fqx -- "- alert: $profile_alert" <(awk '{ sub(/^[[:space:]]*/, ""); print }' <<<"$required_published_render"); then
    continue
  fi
  echo "published independent-required monitoring overlay is missing profile-dependent alert $profile_alert" >&2
  exit 1
done
shared_alert_declarations="$(sed -n '/^[[:space:]]*- alert:/p' "$ROOT_DIR/k8s/monitoring/prometheus-rules-firemud.yaml")"
if [[ -z "${shared_alert_declarations//[[:space:]]/}" ]]; then
  echo "shared Prometheus rules parsing yielded no alert names" >&2
  exit 1
fi
# Keep extraction strict: a declaration with trailing tokens must not become a
# silently accepted alert name. Count every declaration separately so the
# parser fails closed when its strict name extraction skips one.
shared_alerts="$(sed -n 's/^[[:space:]]*- alert: \([^[:space:]]\+\)[[:space:]]*$/\1/p' "$ROOT_DIR/k8s/monitoring/prometheus-rules-firemud.yaml")"
if [[ -z "${shared_alerts//[[:space:]]/}" ]]; then
  echo "shared Prometheus rules parsing yielded no alert names" >&2
  exit 1
fi
shared_alert_declaration_count="$(awk '/^[[:space:]]*- alert:/ { count += 1 } END { print count + 0 }' "$ROOT_DIR/k8s/monitoring/prometheus-rules-firemud.yaml")"
shared_alert_name_count="$(awk 'NF { count += 1 } END { print count + 0 }' <<<"$shared_alerts")"
if (( shared_alert_declaration_count != shared_alert_name_count )); then
  echo "shared Prometheus rules alert parser skipped one or more declared alert lines" >&2
  exit 1
fi
# Regression-proof the fail-closed boundary without mutating the source file:
# a trailing token must increase the declaration count but not the strict name
# count.
malformed_shared_alert_text="$(awk '
  !replaced && /^[[:space:]]*- alert:/ {
    print "        - alert: BackupPipelineNoRecentBackup trailing-token"
    replaced = 1
    next
  }
  { print }
' "$ROOT_DIR/k8s/monitoring/prometheus-rules-firemud.yaml")"
malformed_shared_alert_declaration_count="$(awk '/^[[:space:]]*- alert:/ { count += 1 } END { print count + 0 }' <<<"$malformed_shared_alert_text")"
malformed_shared_alerts="$(sed -n 's/^[[:space:]]*- alert: \([^[:space:]]\+\)[[:space:]]*$/\1/p' <<<"$malformed_shared_alert_text")"
malformed_shared_alert_name_count="$(awk 'NF { count += 1 } END { print count + 0 }' <<<"$malformed_shared_alerts")"
if (( malformed_shared_alert_declaration_count == malformed_shared_alert_name_count )); then
  echo "shared Prometheus rules parser accepted a malformed trailing-token alert declaration" >&2
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
  for profile_alert in "${profile_alerts[@]}"; do
    if grep -Fqx -- "- alert: $profile_alert" <(awk '{ sub(/^[[:space:]]*/, ""); print }' <<<"$render"); then
      echo "a non-published or independent-omitted monitoring overlay installed $profile_alert" >&2
      exit 1
    fi
  done
done
for profile_alert in "${profile_alerts[@]}"; do
  if grep -Fqx -- "- alert: $profile_alert" <(awk '{ sub(/^[[:space:]]*/, ""); print }' "$ROOT_DIR/k8s/monitoring/prometheus-rules-firemud.yaml"); then
    echo "shared Prometheus rules installed profile-dependent alert $profile_alert" >&2
    exit 1
  fi
done

python3 - "$ROOT_DIR" <<'PY'
import copy
import importlib.util
import json
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
for profile_alert in (
    "ObservabilityDeadmanHeartbeatMissing",
    "WebSocketEntryPathBlackboxUnavailable",
    "TelnetEntryPathBlackboxUnavailable",
):
    if profile_alert in valid_text:
        raise AssertionError(
            f"shared Prometheus rules must not install profile-dependent alert {profile_alert}"
        )

required_rules_path = (
    root
    / "k8s/overlays/monitoring/independent-required-prometheus-published/"
    / "prometheus-rules-firemud-independent-required.yaml"
)
required_rules_text = required_rules_path.read_text(encoding="utf-8")
published_overlay_findings = validator._validate_reference_prometheus_rules(
    required_rules_path,
    {
        "ObservabilityDeadmanHeartbeatMissing",
        "ObservabilityDeadmanHeartbeatStale",
        "WebSocketEntryPathBlackboxUnavailable",
        "TelnetEntryPathBlackboxUnavailable",
    },
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

for profile_alert in (
    "WebSocketEntryPathBlackboxUnavailable",
    "TelnetEntryPathBlackboxUnavailable",
):
    if profile_alert not in required_rules_text:
        raise AssertionError(
            f"published profile overlay is missing {profile_alert}"
        )
for expected_expression in (
    'expr: max_over_time(entrypath_blackbox_probe_success{path="websocket"}[2m]) == 0',
    'expr: max_over_time(entrypath_blackbox_probe_success{path="telnet"}[2m]) == 0',
):
    if expected_expression not in required_rules_text:
        raise AssertionError(
            f"published profile overlay is missing blackbox expression {expected_expression}"
        )

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
    "count by (profile)",
    "playerflow_canary_success",
    "or playerflow_canary_latency_ms",
    "or playerflow_canary_last_run_timestamp_seconds",
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


kibana_path = root / "design/observability/kibana/player-incident-drilldown.json"
kibana_payload = json.loads(kibana_path.read_text(encoding="utf-8"))
baseline_kibana_findings = validator._validate_kibana_saved_objects(kibana_path.parent)
if any(finding.path == kibana_path for finding in baseline_kibana_findings):
    raise AssertionError(
        "canonical player incident Kibana object must retain its environment sentinel/filter: "
        f"{baseline_kibana_findings!r}"
    )


def kibana_with_query(query):
    mutated_kibana = copy.deepcopy(kibana_payload)
    mutated_search_source = json.loads(
        mutated_kibana["attributes"]["kibanaSavedObjectMeta"]["searchSourceJSON"]
    )
    mutated_search_source["query"]["query"] = query
    mutated_kibana["attributes"]["kibanaSavedObjectMeta"]["searchSourceJSON"] = json.dumps(
        mutated_search_source, separators=(",", ":")
    )
    return mutated_kibana


def kibana_findings(mutated_kibana):
    with tempfile.TemporaryDirectory() as kibana_temp_dir:
        mutated_path = Path(kibana_temp_dir) / kibana_path.name
        mutated_path.write_text(json.dumps(mutated_kibana), encoding="utf-8")
        return validator._validate_kibana_saved_objects(Path(kibana_temp_dir))


mutated_findings = kibana_findings(
    kibana_with_query("service:* and traceId:*")
)
require_message(
    mutated_findings,
    "player incident Kibana saved object must apply an explicit environment filter before querying wildcard logs",
)
require_message(
    mutated_findings,
    "player incident Kibana saved object query must retain the __REQUIRED_ENVIRONMENT__ fail-closed sentinel",
)

require_message(
    mutated_findings,
    "player incident Kibana saved object query must bind __REQUIRED_ENVIRONMENT__ as an exact conjunctive environment filter with service and traceId bounds",
)

misplaced_sentinel_findings = kibana_findings(
    kibana_with_query(
        'environment:* and service:* and traceId:* and message:"__REQUIRED_ENVIRONMENT__"'
    )
)
require_message(
    misplaced_sentinel_findings,
    "player incident Kibana saved object query must bind __REQUIRED_ENVIRONMENT__ as an exact conjunctive environment filter with service and traceId bounds",
)

environment_wildcard_findings = kibana_findings(
    kibana_with_query(
        'environment:* and service:* and traceId:*'
    )
)
require_message(
    environment_wildcard_findings,
    "player incident Kibana saved object query must retain the __REQUIRED_ENVIRONMENT__ fail-closed sentinel",
)
require_message(
    environment_wildcard_findings,
    "player incident Kibana saved object query must bind __REQUIRED_ENVIRONMENT__ as an exact conjunctive environment filter with service and traceId bounds",
)

disjunctive_findings = kibana_findings(
    kibana_with_query(
        'environment:"__REQUIRED_ENVIRONMENT__" or service:* and traceId:*'
    )
)
require_message(
    disjunctive_findings,
    "player incident Kibana saved object query must keep environment, service, and traceId clauses conjunctive",
)

unrestricted_index = copy.deepcopy(kibana_payload)
unrestricted_index["references"][0]["id"] = "*"
unrestricted_index_findings = kibana_findings(unrestricted_index)
require_message(
    unrestricted_index_findings,
    "player incident Kibana saved object index reference must use firemud-logs-* or an explicit environment-scoped FireMUD log index",
)

explicit_environment_index = copy.deepcopy(kibana_payload)
explicit_environment_index["references"][0]["id"] = "firemud-logs-staging-eu-*"
if any(finding.path.name == kibana_path.name for finding in kibana_findings(explicit_environment_index)):
    raise AssertionError(
        "explicit environment-scoped FireMUD index reference must remain valid"
    )

missing_index_ref_name = copy.deepcopy(kibana_payload)
missing_search_source_ref = json.loads(
    missing_index_ref_name["attributes"]["kibanaSavedObjectMeta"]["searchSourceJSON"]
)
missing_search_source_ref.pop("indexRefName", None)
missing_index_ref_name["attributes"]["kibanaSavedObjectMeta"]["searchSourceJSON"] = json.dumps(
    missing_search_source_ref,
    separators=(",", ":"),
)
require_message(
    kibana_findings(missing_index_ref_name),
    "player incident Kibana saved object must have a non-empty searchSourceJSON.indexRefName",
)

wrong_index_ref_name = copy.deepcopy(kibana_payload)
wrong_search_source_ref = json.loads(
    wrong_index_ref_name["attributes"]["kibanaSavedObjectMeta"]["searchSourceJSON"]
)
wrong_search_source_ref["indexRefName"] = "unsafe.index"
wrong_index_ref_name["attributes"]["kibanaSavedObjectMeta"]["searchSourceJSON"] = json.dumps(
    wrong_search_source_ref,
    separators=(",", ":"),
)
require_message(
    kibana_findings(wrong_index_ref_name),
    "player incident Kibana saved object searchSourceJSON.indexRefName must exactly match the searchSourceJSON.index reference name",
)

alternate_broad_reference = copy.deepcopy(kibana_payload)
alternate_broad_reference["references"].append(
    {"name": "unsafe.index", "type": "index-pattern", "id": "*"}
)
require_message(
    kibana_findings(alternate_broad_reference),
    "player incident Kibana saved object must have exactly one searchSourceJSON.index index-pattern reference",
)

non_string_query_message = (
    "player incident Kibana saved object query must be a string before environment safety checks"
)
for invalid_query in (None, 123, ["environment", "service", "traceId"]):
    require_message(
        kibana_findings(kibana_with_query(invalid_query)),
        non_string_query_message,
    )


player_dashboard_path = (
    root / "design/observability/grafana/player-experience.json"
)
player_dashboard_text = player_dashboard_path.read_text(encoding="utf-8")
player_chat_selector = (
    'chat_delivery_latency_ms_bucket{completion_boundary=\\"recipient_dispatch\\"}'
)
if player_chat_selector not in player_dashboard_text:
    raise AssertionError(
        "canonical player-experience dashboard fixture is missing the "
        "recipient_dispatch chat selector"
    )
player_dashboard_message = (
    "canonical player-experience chat latency panels must select "
    'completion_boundary="recipient_dispatch" on every '
    "chat_delivery_latency_ms_bucket selector"
)
for replacement in (
    "chat_delivery_latency_ms_bucket",
    'chat_delivery_latency_ms_bucket{completion_boundary=\\"server_acceptance\\"}',
):
    mutated_dashboard = player_dashboard_text.replace(
        player_chat_selector, replacement, 1
    )
    require_message(
        findings_for(
            mutated_dashboard,
            validator._validate_player_experience_dashboard,
        ),
        player_dashboard_message,
    )

player_dashboard = json.loads(player_dashboard_text)
baseline_dashboard_findings = findings_for(
    player_dashboard_text,
    validator._validate_player_experience_dashboard,
)
if baseline_dashboard_findings:
    raise AssertionError(
        "valid player-experience dashboard was rejected: "
        f"{baseline_dashboard_findings!r}"
    )
if (
    "calibration" not in player_dashboard.get("description", "").lower()
    or "non-enforcing" not in player_dashboard.get("description", "").lower()
):
    raise AssertionError(
        "canonical player-experience dashboard must declare calibration "
        "views as non-enforcing"
    )
if any(
    re.search(r"\bslo\b", panel.get("title", ""), re.IGNORECASE)
    and not re.search(r"\bcalibration\b", panel.get("title", ""), re.IGNORECASE)
    for panel in player_dashboard["panels"]
):
    raise AssertionError(
        "canonical player-experience panels must not use unqualified SLO wording"
    )
player_chat_targets = [
    target
    for panel in player_dashboard["panels"]
    for target in panel.get("targets", [])
    if "chat_delivery_latency_ms_bucket" in target.get("expr", "")
]
if len(player_chat_targets) != 1:
    raise AssertionError(
        "canonical player-experience dashboard fixture must contain exactly one "
        "chat latency target for compound-selector mutation coverage"
    )
for additional_term in (
    " + rate(chat_delivery_latency_ms_bucket[5m])",
    ' + rate(chat_delivery_latency_ms_bucket{completion_boundary="server_acceptance"}[5m])',
):
    mutated_dashboard = copy.deepcopy(player_dashboard)
    mutated_chat_targets = [
        target
        for panel in mutated_dashboard["panels"]
        for target in panel.get("targets", [])
        if "chat_delivery_latency_ms_bucket" in target.get("expr", "")
    ]
    mutated_chat_targets[0]["expr"] += additional_term
    require_message(
        findings_for(
            json.dumps(mutated_dashboard),
            validator._validate_player_experience_dashboard,
        ),
        player_dashboard_message,
    )

dashboard_description_message = (
    "canonical player-experience dashboard must identify its views as calibration "
    "and non-enforcing until profile promotion"
)
for description_replacement in ("calibration", "non-enforcing"):
    mutated_dashboard = copy.deepcopy(player_dashboard)
    mutated_dashboard["description"] = mutated_dashboard["description"].replace(
        description_replacement,
        "unqualified",
        1,
    )
    require_message(
        findings_for(
            json.dumps(mutated_dashboard),
            validator._validate_player_experience_dashboard,
        ),
        dashboard_description_message,
    )


player_drilldown_path = (
    root / "design/observability/grafana/player-experience-drilldown.json"
)
player_drilldown_text = player_drilldown_path.read_text(encoding="utf-8")
baseline_drilldown_findings = findings_for(
    player_drilldown_text,
    validator._validate_player_experience_drilldown,
)
if baseline_drilldown_findings:
    raise AssertionError(
        "valid player-experience drilldown was rejected: "
        f"{baseline_drilldown_findings!r}"
    )
player_drilldown_chat_selector = (
    'chat_delivery_latency_ms_bucket{completion_boundary=\\"recipient_dispatch\\"}'
)
if player_drilldown_chat_selector not in player_drilldown_text:
    raise AssertionError(
        "player-experience drilldown fixture is missing the recipient_dispatch "
        "chat selector"
    )
player_drilldown_message = (
    "player-experience drilldown chat latency panels must select "
    'completion_boundary="recipient_dispatch" on every '
    "chat_delivery_latency_ms_bucket selector"
)
for replacement in (
    "chat_delivery_latency_ms_bucket",
    'chat_delivery_latency_ms_bucket{completion_boundary=\\"server_acceptance\\"}',
):
    mutated_drilldown = player_drilldown_text.replace(
        player_drilldown_chat_selector, replacement, 1
    )
    require_message(
        findings_for(
            mutated_drilldown,
            validator._validate_player_experience_drilldown,
        ),
        player_drilldown_message,
    )


def mutate_alert_rule(text, alert_name, old, new):
    rule_match = re.search(
        rf"(?ms)^[ \t]*- alert: {re.escape(alert_name)}\n"
        rf"(?P<body>.*?)(?=^[ \t]*- alert: |\Z)",
        text,
    )
    if rule_match is None:
        raise AssertionError(f"{alert_name} rule is missing from test fixture")
    body = rule_match.group("body")
    if old not in body:
        raise AssertionError(
            f"{alert_name} test fixture does not contain expected text {old!r}"
        )
    updated_body = body.replace(old, new, 1)
    return text[: rule_match.start("body")] + updated_body + text[rule_match.end("body") :]


def mutate_recording_rule(text, recording_name, old, new):
    rule_match = re.search(
        rf"(?ms)^[ \t]*- record: {re.escape(recording_name)}\n"
        rf"(?P<body>.*?)(?=^[ \t]*- (?:alert|record): |\Z)",
        text,
    )
    if rule_match is None:
        raise AssertionError(f"{recording_name} rule is missing from test fixture")
    body = rule_match.group("body")
    if old not in body:
        raise AssertionError(
            f"{recording_name} test fixture does not contain expected text {old!r}"
        )
    updated_body = body.replace(old, new, 1)
    return text[: rule_match.start("body")] + updated_body + text[rule_match.end("body") :]


canonical_indexed_log_runbook = (
    "design/architecture/system-architecture-observability-incident-runbook.md"
    "#indexed-log-query-path-down-or-ingest-stalled"
)
stale_indexed_log_runbook = (
    "design/architecture/system-architecture-observability-incident-runbook.md"
    "#elasticsearchkibana-down-or-indexing-stalled"
)
stale_runbook_rules = mutate_alert_rule(
    valid_text,
    "ElasticsearchClusterHealthRed",
    canonical_indexed_log_runbook,
    stale_indexed_log_runbook,
)
require_message(
    findings_for(
        stale_runbook_rules,
        validator._validate_reference_prometheus_rules,
    ),
    f"alert rule runbook anchor does not exist: {stale_indexed_log_runbook!r}",
)


without_missing = required_rules_text[:missing_start] + (
    "" if missing_next == -1 else required_rules_text[missing_next:]
)
require_message(
    findings_for(
        without_missing,
        lambda path: validator._validate_reference_prometheus_rules(
            path,
            {
                "ObservabilityDeadmanHeartbeatMissing",
                "ObservabilityDeadmanHeartbeatStale",
            },
            allow_profile_dependent_alerts=True,
        ),
    ),
    "reference rules are missing required alerts: ObservabilityDeadmanHeartbeatMissing",
)


profile_dependent_alert = """    - name: firemud.alerts.profile-dependent
      rules:
        - alert: ObservabilityDeadmanHeartbeatStale
          expr: observability_deadman_stale{profile="independent-required"} == 1
          labels:
            service: external-monitoring
            severity: P0
            owner: platform
            runbook: design/architecture/system-architecture-observability-incident-runbook.md#deadman-freshness-contract
        - alert: ObservabilityDeadmanHeartbeatMissing
          expr: absent(observability_deadman_stale{profile="independent-required"})
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
base_profile_findings = findings_for(
    base_with_profile_dependent_alert,
    validator._validate_reference_prometheus_rules,
)
for profile_alert in (
    "ObservabilityDeadmanHeartbeatStale",
    "ObservabilityDeadmanHeartbeatMissing",
):
    require_message(
        base_profile_findings,
        f"base Prometheus rules must not include profile-dependent alert {profile_alert}; install it only through the matching profile overlay",
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

player_dashboard_with_slo_title = copy.deepcopy(player_dashboard)
player_dashboard_with_slo_title["panels"][0]["title"] = (
    player_dashboard_with_slo_title["panels"][0]["title"]
    .replace("Calibration", "SLO")
)
require_message(
    findings_for(
        json.dumps(player_dashboard_with_slo_title),
        validator._validate_player_experience_dashboard,
    ),
    "canonical player-experience calibration panels must not use enforceable SLO wording before profile promotion",
)
player_dashboard_with_calibration_slo_title = copy.deepcopy(player_dashboard)
player_dashboard_with_calibration_slo_title["panels"][0]["title"] = (
    "Login Success Ratio by Service (15m SLO Calibration)"
)
allowed_slo_calibration_findings = findings_for(
    json.dumps(player_dashboard_with_calibration_slo_title),
    validator._validate_player_experience_dashboard,
)
if allowed_slo_calibration_findings:
    raise AssertionError(
        "explicit SLO Calibration dashboard wording should remain allowed: "
        f"{allowed_slo_calibration_findings!r}"
    )
command_scope_targets = [
    target
    for panel in player_dashboard["panels"]
    for target in panel.get("targets", [])
    if isinstance(target.get("expr"), str)
    and "command_end_to_end_latency_ms_bucket" in target["expr"]
    and any(
        {"scope", "command"}.issubset(
            {label.strip() for label in grouping.group(1).split(",")}
        )
        for grouping in re.finditer(
            r"sum\s+by\s*\(([^)]*)\)", target["expr"], re.IGNORECASE
        )
    )
]
if not command_scope_targets:
    raise AssertionError(
        "canonical command latency dashboard query must retain bounded scope and command grouping"
    )
command_scope_query_message = (
    "canonical command latency by-scope panels must not group command latency "
    "expressions by raw region"
)
command_scope_grouping_message = (
    "canonical command latency by-scope panels must group each command latency "
    "expression by bounded scope and command"
)
for query_replacement, expected_message in (
    (
        "sum by (region, le, command)",
        command_scope_query_message,
    ),
    (
        "sum by (service, le, command)",
        command_scope_grouping_message,
    ),
):
    mutated_dashboard = copy.deepcopy(player_dashboard)
    for panel in mutated_dashboard["panels"]:
        if panel.get("title") == "Command Latency (p99) by Scope":
            panel["targets"][0]["expr"] = panel["targets"][0]["expr"].replace(
                "sum by (scope, le, command)",
                query_replacement,
                1,
            )
            break
    require_message(
        findings_for(
            json.dumps(mutated_dashboard),
            validator._validate_player_experience_dashboard,
        ),
        expected_message,
    )
player_dashboard_with_region_command_title = copy.deepcopy(player_dashboard)
for panel in player_dashboard_with_region_command_title["panels"]:
    if panel.get("title") == "Command Latency (p99) by Scope":
        panel["title"] = "Command Latency (p99) by Region"
        break
else:
    raise AssertionError("canonical command latency by-scope panel is missing")
require_message(
    findings_for(
        json.dumps(player_dashboard_with_region_command_title),
        validator._validate_player_experience_dashboard,
    ),
    "canonical command latency panels grouped by bounded scope must not claim a raw region grouping",
)

calibration_alerts = (
    "LoginSuccessRatioLowGateway",
    "LoginSuccessRatioLowTcpProxy",
    "CommandLatencyP99HighGateway",
    "CommandLatencyP99HighTcpProxy",
    "ChatDeliveryLatencyP99High",
    "EntryPathAvailabilityLowGateway",
    "EntryPathAvailabilityLowGatewayCompliance",
    "EntryPathAvailabilityLowTcpProxy",
    "EntryPathAvailabilityLowTcpProxyCompliance",
)
calibration_sources = (
    (valid_playerflow_snippet, validator._validate_alert_snippet),
    (valid_text, validator._validate_reference_prometheus_rules),
)
for source_text, source_validator in calibration_sources:
    baseline_findings = findings_for(source_text, source_validator)
    if baseline_findings:
        raise AssertionError(
            f"valid player calibration rules were rejected: {baseline_findings!r}"
        )
    for alert_name in calibration_alerts:
        severity_drift = mutate_alert_rule(
            source_text, alert_name, "severity: P2", "severity: P0"
        )
        require_message(
            findings_for(severity_drift, source_validator),
            f"{alert_name} calibration alert must use severity=P2",
        )
        wrong_slo_state = mutate_alert_rule(
            source_text,
            alert_name,
            "slo_state: calibration",
            "slo_state: enforceable",
        )
        require_message(
            findings_for(wrong_slo_state, source_validator),
            f"{alert_name} calibration alert must use slo_state=calibration",
        )
        missing_slo_state = mutate_alert_rule(
            source_text,
            alert_name,
            "slo_state: calibration\n",
            "",
        )
        require_message(
            findings_for(missing_slo_state, source_validator),
            f"{alert_name} calibration alert must use slo_state=calibration",
        )

raw_chat_selector = (
    'chat_delivery_latency_ms_bucket{completion_boundary="recipient_dispatch"}'
)
raw_chat_term = f"rate({raw_chat_selector}[5m])"
chat_recording_selector = (
    'chat_delivery_latency_ms_p99_5m{completion_boundary="recipient_dispatch"}'
)
recording_message = (
    "canonical chat delivery recording rule must select "
    'completion_boundary="recipient_dispatch" on every '
    "chat_delivery_latency_ms_bucket selector"
)
shipped_alert_message = (
    "shipped ChatDeliveryLatencyP99High alert must select "
    'completion_boundary="recipient_dispatch" on every '
    "chat_delivery_latency_ms_p99_5m selector"
)
snippet_alert_message = (
    "ChatDeliveryLatencyP99High alert snippet must select "
    'completion_boundary="recipient_dispatch" on every '
    "chat_delivery_latency_ms_bucket selector"
)

for old, replacement in (
    (
        raw_chat_selector,
        'chat_delivery_latency_ms_bucket{completion_boundary="server_acceptance"}',
    ),
    (raw_chat_selector, "chat_delivery_latency_ms_bucket"),
    (
        raw_chat_term,
        f"({raw_chat_term} + rate(chat_delivery_latency_ms_bucket[5m]))",
    ),
    (
        raw_chat_term,
        (
            f"({raw_chat_term} + rate(chat_delivery_latency_ms_bucket"
            '{completion_boundary="server_acceptance"}[5m]))'
        ),
    ),
):
    mutated_recording = mutate_recording_rule(
        valid_text,
        "chat_delivery_latency_ms_p99_5m",
        old,
        replacement,
    )
    require_message(
        findings_for(
            mutated_recording,
            validator._validate_reference_prometheus_recordings,
        ),
        recording_message,
    )

for replacement in (
    'chat_delivery_latency_ms_p99_5m{completion_boundary="server_acceptance"}',
    "chat_delivery_latency_ms_p99_5m",
    (
        "(" + chat_recording_selector
        + " + chat_delivery_latency_ms_p99_5m)"
    ),
    (
        "(" + chat_recording_selector
        + ' + chat_delivery_latency_ms_p99_5m{completion_boundary="server_acceptance"})'
    ),
):
    mutated_shipped_alert = mutate_alert_rule(
        valid_text,
        "ChatDeliveryLatencyP99High",
        chat_recording_selector,
        replacement,
    )
    require_message(
        findings_for(
            mutated_shipped_alert,
            validator._validate_reference_prometheus_rules,
        ),
        shipped_alert_message,
    )

for old, replacement in (
    (
        raw_chat_selector,
        'chat_delivery_latency_ms_bucket{completion_boundary="server_acceptance"}',
    ),
    (raw_chat_selector, "chat_delivery_latency_ms_bucket"),
    (
        raw_chat_term,
        f"({raw_chat_term} + rate(chat_delivery_latency_ms_bucket[5m]))",
    ),
    (
        raw_chat_term,
        (
            f"({raw_chat_term} + rate(chat_delivery_latency_ms_bucket"
            '{completion_boundary="server_acceptance"}[5m]))'
        ),
    ),
):
    mutated_snippet_alert = mutate_alert_rule(
        valid_playerflow_snippet,
        "ChatDeliveryLatencyP99High",
        old,
        replacement,
    )
    require_message(
        findings_for(
            mutated_snippet_alert,
            validator._validate_alert_snippet,
        ),
        snippet_alert_message,
    )

entry_path_contracts = {
    "WebSocketEntryPathBlackboxUnavailable": {
        "path": "websocket",
        "other_path": "telnet",
        "service": "spring-cloud-gateway",
    },
    "TelnetEntryPathBlackboxUnavailable": {
        "path": "telnet",
        "other_path": "websocket",
        "service": "tcp-proxy-service",
    },
}
entry_path_sources = (
    (
        required_rules_text,
        lambda path: validator._validate_reference_prometheus_rules(
            path,
            set(entry_path_contracts),
            allow_profile_dependent_alerts=True,
        ),
    ),
    (valid_playerflow_snippet, validator._validate_alert_snippet),
)
for source_text, check in entry_path_sources:
    baseline_findings = findings_for(source_text, check)
    if baseline_findings:
        raise AssertionError(
            f"valid entry-path blackbox rules were rejected: {baseline_findings!r}"
        )
    for alert_name, contract in entry_path_contracts.items():
        mutations = (
            (
                "severity: P0",
                "severity: P1",
                f"{alert_name} must use labels.severity=P0",
            ),
            (
                "component: entrypath",
                "component: blackbox",
                f"{alert_name} must use labels.component=entrypath",
            ),
            (
                f'service: {contract["service"]}',
                "service: prometheus",
                f'{alert_name} must use labels.service={contract["service"]}',
            ),
            (
                "for: 2m",
                "for: 3m",
                f"{alert_name} must use for=2m",
            ),
            (
                f'entrypath_blackbox_probe_success{{path="{contract["path"]}"}}',
                f'entrypath_blackbox_probe_success{{path="{contract["other_path"]}"}}',
                f'{alert_name} must use only the exact path="{contract["path"]}" entrypath_blackbox_probe_success selector over 2m and compare it to zero',
            ),
            (
                f'entrypath_blackbox_probe_success{{path="{contract["path"]}"}}',
                f'entrypath_blackbox_probe_success{{path="{contract["path"]}",profile="independent-required"}}',
                f'{alert_name} must use only the exact path="{contract["path"]}" entrypath_blackbox_probe_success selector over 2m and compare it to zero',
            ),
        )
        for old, new, expected_message in mutations:
            mutated = mutate_alert_rule(source_text, alert_name, old, new)
            require_message(findings_for(mutated, check), expected_message)

expected_budget_missing_expr = validator._compact_promql(
    """
    count by (profile) (
      playerflow_canary_success
      or playerflow_canary_latency_ms
      or playerflow_canary_last_run_timestamp_seconds
    )
    unless on (profile)
    count by (profile) (playerflow_canary_freshness_budget_seconds)
    """
)
for source_text in (valid_playerflow_snippet, valid_text):
    yaml_blocks = validator._extract_fenced_blocks(source_text, "yaml")
    parsed_rules = [
        entry
        for block in (yaml_blocks or [source_text])
        for entry in validator._split_alert_rules(block)
    ]
    budget_entries = [
        entry
        for entry in parsed_rules
        if entry.name == "PlayerFlowCanaryFreshnessBudgetMissing"
    ]
    if len(budget_entries) != 1:
        raise AssertionError(
            "PlayerFlowCanaryFreshnessBudgetMissing must have exactly one parsed rule"
        )
    actual_budget_missing_expr = validator._compact_promql(
        validator._parse_expr(budget_entries[0].lines) or ""
    )
    if actual_budget_missing_expr != expected_budget_missing_expr:
        raise AssertionError(
            "PlayerFlowCanaryFreshnessBudgetMissing must union all canary families before profile-level budget matching"
        )


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
        "playerflow_canary_success",
        "unless on (flow, path, target, profile)",
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
    if "absent(" in stale_body:
        raise AssertionError(
            "PlayerFlowCanaryEvidenceStale must not claim total tuple-absence detection"
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
