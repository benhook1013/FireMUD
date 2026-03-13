# Observability Dashboards

The observability directory contains dashboards and saved objects used to monitor the FireMUD platform in production and non-production environments.

These assets complement the architecture logging and monitoring design by providing ready-to-import visualizations for Grafana and Kibana.

## Subdirectories

- [grafana/](./grafana/) – JSON exports of Grafana dashboards for service health, latency, and request/command volume.
- [kibana/](./kibana/) – JSON exports of Kibana index patterns, searches, and dashboards focused on log exploration and error investigation.

For a conceptual overview of the observability stack (Prometheus, Elasticsearch, Fluent Bit, OpenTelemetry, Alertmanager, and dashboarding tools), see [System Architecture – Logging & Monitoring](../architecture/system-architecture-logging-monitoring.md) and [Observability Components](../architecture/system-architecture-diagram.md#-observability-components).

## Workflow

- Treat `design/architecture/system-architecture-logging-monitoring.md` and `design/architecture/system-architecture-redis-operations.md` as the contract for alert labels, metric naming/units, and required dimensions (for example `severity ∈ {P0,P1,P2}`, `owner`, `runbook`, and stable `service` labels on shared metrics).
- When updating Grafana dashboards or Alertmanager snippet templates, keep PromQL aligned with that contract (especially latency units for `_ms` metrics and per-tenant/region scoping where required).
- For prod-like environments, install the reference Prometheus recording rules and alerts from `k8s/monitoring/prometheus-rules-firemud.yaml` (or an overlay derived from it) so fallback conditions and SLO panels have stable, shared recording rules.
- Treat the external-signal contract in `design/architecture/system-architecture-logging-monitoring.md#external-probe-and-deadman-contract-normative` as canonical for independent edge probes and meta-monitoring. By default, shared dashboards, snippets, and smoke tests should expect `entrypath_blackbox_probe_success{path,target}` and `observability_deadman_heartbeat_timestamp_seconds{source}` unless an environment documents an explicit compatibility mapping.
- In nightly/staging-gated observability smoke, validate rule presence through Prometheus rules API checks, not only by inspecting dashboards.
- Validate changes locally with `python3 dev-tools/observability/validate-observability-contract.py` and keep CI green.
- When contract validation fails, resolve the mismatch by aligning docs/snippets/dashboards with the normative architecture contracts; do not weaken validator rules unless the contract itself is intentionally changed.
