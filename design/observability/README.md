# Observability Dashboards

The observability directory contains dashboards and saved objects used to monitor the FireMUD platform in production and non-production environments.

These assets complement the architecture logging and monitoring design by providing ready-to-import visualizations for Grafana and Kibana.

## Subdirectories

- [external-monitoring/](./external-monitoring/) – Contract for the authoritative external pager and probe path that must remain useful when Prometheus/Alertmanager are unavailable.
- [grafana/](./grafana/) – JSON exports of Grafana dashboards for service health, latency, and request/command volume.
- [kibana/](./kibana/) – JSON exports of Kibana index patterns, searches, and dashboards focused on log exploration and error investigation.

For a conceptual overview of the observability stack (Prometheus, Elasticsearch, Fluent Bit, OpenTelemetry, Alertmanager, and dashboarding tools), see [System Architecture – Logging & Monitoring](../architecture/system-architecture-logging-monitoring.md) and [Observability Components](../architecture/system-architecture-diagram.md#-observability-components).
For the authoritative external pager and blackbox-monitoring contract that must survive Prometheus outages, see [external-monitoring/](./external-monitoring/).
Alertmanager-only routed-alert authority and diagnostic degradation follow [ADR 0158](../architecture/decisions/adr-0158-simplified-observability-degradation-without-fallback-alert-authority.md); whether the external pager and public-path monitor are required follows [ADR 0159](../architecture/decisions/adr-0159-profile-dependent-independent-deadman-and-public-path-monitoring.md).

## Implementation Status

Current repository proof is static: `dev-tools/observability/validate-observability-contract.py` checks dashboard/snippet/saved-object/reference-rule consistency, and CI also runs the metric-cardinality guardrail. This does not prove that the authoritative external pager, mirrored canary/deadman metrics, Jaeger queries, or Kibana log-query path work in a live environment. Profile-dependent environment-backed observability smoke is the runtime-proof boundary for profiles that advertise independent monitoring or player-flow canaries; the static checks do not substitute for that proof.

## Workflow

- Treat `design/architecture/system-architecture-logging-monitoring.md` and `design/architecture/system-architecture-redis-operations.md` as the contract for alert labels, metric naming/units, and required dimensions (for example `severity ∈ {P0,P1,P2}`, `owner`, `runbook`, and stable `service` labels on shared metrics).
- Treat `design/observability/external-monitoring/README.md` as the canonical source for what must be authoritative outside the Prometheus + Alertmanager failure domain versus what may be mirrored back into Prometheus.
- When updating Grafana dashboards or Alertmanager snippet templates, keep PromQL aligned with that contract (especially latency units for `_ms` metrics and per-tenant/region scoping where required).
- For environments that provide the relevant monitoring stack, install the reference Prometheus recording rules and Alertmanager evaluation rules from `k8s/monitoring/prometheus-rules-firemud.yaml` (or an overlay derived from it) so diagnostic calculations, dashboards, and routed-alert evaluation have stable, shared inputs. These rules do not create a Prometheus fallback active-alert authority or deduplication lifecycle.
- Treat the external-signal contract in `design/architecture/system-architecture-logging-monitoring.md#external-probe-and-deadman-contract-normative` and the synthetic canary contract in `design/architecture/system-architecture-logging-monitoring.md#synthetic-player-flow-canaries-target-state-profile-advertised-contract` as canonical for independent edge probes, meta-monitoring, and low-traffic player-path detection. Authoritative off-cluster evidence is required according to the selected profile; `entrypath_blackbox_probe_success{path,target}` and `observability_deadman_heartbeat_timestamp_seconds{source}` are optional Prometheus mirrors checked only when published. When advertised, `playerflow_canary_success{flow,path,target,profile}`, `playerflow_canary_latency_ms{flow,path,target,profile}`, and `playerflow_canary_last_run_timestamp_seconds{flow,path,target,profile}` are retained for every exposed path, with the matching `playerflow_canary_freshness_budget_seconds{profile}` used for freshness; `PlayerFlowCanaryEvidenceStale` means the canary is unknown/degraded until refreshed. An omitted canary capability omits that family. Environments may document an explicit compatibility mapping for any published mirror.
- Contract validators and reviews should treat “Prometheus mirror only” external-signal implementations as non-compliant. If a dashboard, snippet, or smoke test references only mirrored Prometheus-series names for deadman, blackbox, or canary checks, it must also point to the authoritative external-monitoring contract or a documented compatibility mapping that proves where the independent paging source lives.
- In nightly/staging-gated observability smoke, validate rule presence through Prometheus rules API checks, not only by inspecting dashboards.
- Validate changes with the shared [validation and runtime-proof workflow](../developer-workflows/validation-and-runtime-proof.md): run `python3 dev-tools/observability/validate-observability-contract.py` for static contract checks, and use the profile-dependent environment-backed observability smoke for runtime proof where the selected profile advertises it.
- When contract validation fails, resolve the mismatch by aligning docs/snippets/dashboards with the normative architecture contracts; do not weaken validator rules unless the contract itself is intentionally changed.
