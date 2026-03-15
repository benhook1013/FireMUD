# Observability Dashboards

The observability directory contains dashboards and saved objects used to monitor the FireMUD platform in production and non-production environments.

These assets complement the architecture logging and monitoring design by providing ready-to-import visualizations for Grafana and Kibana.

## Subdirectories

- [external-monitoring/](./external-monitoring/) – Contract for the authoritative external pager and probe path that must remain useful when Prometheus/Alertmanager are unavailable.
- [grafana/](./grafana/) – JSON exports of Grafana dashboards for service health, latency, and request/command volume.
- [kibana/](./kibana/) – JSON exports of Kibana index patterns, searches, and dashboards focused on log exploration and error investigation.

For a conceptual overview of the observability stack (Prometheus, Elasticsearch, Fluent Bit, OpenTelemetry, Alertmanager, and dashboarding tools), see [System Architecture – Logging & Monitoring](../architecture/system-architecture-logging-monitoring.md) and [Observability Components](../architecture/system-architecture-diagram.md#-observability-components).
For the authoritative external pager and blackbox-monitoring contract that must survive Prometheus outages, see [external-monitoring/](./external-monitoring/).

## Workflow

- Treat `design/architecture/system-architecture-logging-monitoring.md` and `design/architecture/system-architecture-redis-operations.md` as the contract for alert labels, metric naming/units, and required dimensions (for example `severity ∈ {P0,P1,P2}`, `owner`, `runbook`, and stable `service` labels on shared metrics).
- Treat `design/observability/external-monitoring/README.md` as the canonical source for what must be authoritative outside the Prometheus + Alertmanager failure domain versus what may be mirrored back into Prometheus.
- When updating Grafana dashboards or Alertmanager snippet templates, keep PromQL aligned with that contract (especially latency units for `_ms` metrics and per-tenant/region scoping where required).
- For prod-like environments, install the reference Prometheus recording rules and alerts from `k8s/monitoring/prometheus-rules-firemud.yaml` (or an overlay derived from it) so fallback conditions and SLO panels have stable, shared recording rules.
- Treat the external-signal contract in `design/architecture/system-architecture-logging-monitoring.md#external-probe-and-deadman-contract-normative` and the synthetic canary contract in `design/architecture/system-architecture-logging-monitoring.md#synthetic-player-flow-canaries-normative` as canonical for independent edge probes, meta-monitoring, and low-traffic player-path detection. By default, shared dashboards, snippets, and smoke tests should expect `entrypath_blackbox_probe_success{path,target}`, `observability_deadman_heartbeat_timestamp_seconds{source}`, and `playerflow_canary_success{flow,path,target}` / `playerflow_canary_latency_ms{flow,path,target}` unless an environment documents an explicit compatibility mapping.
- Contract validators and reviews should treat “Prometheus mirror only” external-signal implementations as non-compliant. If a dashboard, snippet, or smoke test references only mirrored Prometheus-series names for deadman, blackbox, or canary checks, it must also point to the authoritative external-monitoring contract or a documented compatibility mapping that proves where the independent paging source lives.
- In nightly/staging-gated observability smoke, validate rule presence through Prometheus rules API checks, not only by inspecting dashboards.
- Validate changes locally with `python3 dev-tools/observability/validate-observability-contract.py` and keep CI green.
- When contract validation fails, resolve the mismatch by aligning docs/snippets/dashboards with the normative architecture contracts; do not weaken validator rules unless the contract itself is intentionally changed.
