# Grafana Dashboards

This directory stores Grafana dashboard exports that visualize key FireMUD service metrics.

These JSON files can be imported into a Grafana instance connected to the project’s Prometheus data source to bootstrap consistent dashboards across environments.

## Dashboards

- [service-overview.json](./service-overview.json) – High-level service overview with status panels and charts for request rates, error rates, and latency broken down by microservice.
- [tcp-proxy-alerts-snippets.md](./tcp-proxy-alerts-snippets.md) – Reference PromQL and Alertmanager rule snippets for TCP Proxy ingress metrics (Telnet connection limits, discarded input, TLS/mTLS and WebSocket reconnect behaviour). Import these into your environment-specific dashboards and rulesets as needed.
- [core-alerts-snippets.md](./core-alerts-snippets.md) – Reference Alertmanager rule snippets for core Redis, tick, and backup pipeline alerts (tail-loss SLO breaches, unsafe tick runtime ratios, tick ledger backlogs, and backup/verification health).
- [player-experience.json](./player-experience.json) – Canonical player experience dashboard with SLIs for login success ratio, command end-to-end latency, Telnet/WebSocket path availability, and chat delivery latency.
- [redis-coordination-health.json](./redis-coordination-health.json) – Redis and coordination health dashboard, including tail-loss, AOF size/restart, coordination memory and key counts, and per-prefix coordination metrics.
- [tick-health-ledger.json](./tick-health-ledger.json) – Tick health and ledger dashboard, including tick status, execution-time histograms and ratios, retry and command queue depths, and ledger pending/applied/abandoned metrics.

To import a dashboard, use Grafana’s “Import dashboard” feature and either upload the JSON file directly or paste its contents into the “Import via panel JSON” field, then bind it to the correct Prometheus data source.

## Conventions (Contract)

- Shared alert snippet templates must follow the alert label contract in `design/architecture/system-architecture-logging-monitoring.md` (especially `severity ∈ {P0,P1,P2}`, plus `owner` and `runbook` labels).
- Shared PromQL expressions must respect metric units. If a metric name contains `_ms`, comparisons must use millisecond thresholds (for example `> 250`, not `> 0.25`).
- Player experience SLIs/SLOs are evaluated per `tenantId` (and `regionId` where applicable). Dashboards may include global rollups, but they should preserve per-tenant visibility so operators can see blast radius quickly.
