# Grafana Dashboards

This directory stores Grafana dashboard exports that visualize key FireMUD service metrics.

These JSON files can be imported into a Grafana instance connected to the project’s Prometheus data source to bootstrap consistent dashboards across environments.

## Implementation Status

The checked-in `player-experience.json` currently focuses on login success ratio, command end-to-end latency, Telnet/WebSocket path availability from in-service attempt counters, and chat delivery latency. Mirrored synthetic canary, external blackbox, and deadman panels remain follow-up dashboard work once those signals are implemented or mapped for a prod-like environment.

## Dashboards

- [backups.json](./backups.json) – Backup pipeline dashboard with backup/verification/restore-drill freshness, artifact lineage/readability, and current recovery-participant state. Tick-pause views belong to maintenance/reset dashboards.
- [service-overview.json](./service-overview.json) – High-level service overview with status panels and charts for request rates, error rates, and latency broken down by microservice.
- [tcp-proxy-alerts-snippets.md](./tcp-proxy-alerts-snippets.md) – Reference PromQL and Alertmanager rule snippets for TCP Proxy ingress metrics (Telnet connection limits, discarded input, TLS/mTLS and WebSocket reconnect behaviour). Import these into your environment-specific dashboards and rulesets as needed.
- [tcp-proxy.json](./tcp-proxy.json) – TCP Proxy service dashboard (Telnet connection limits, discarded Telnet input, WebSocket reconnects, and NotifyDisconnect transport/app errors).
- [core-alerts-snippets.md](./core-alerts-snippets.md) – Index for the core alert snippet split. See the sibling files below for Redis, tick, backup, player-experience, and observability-stack alert families.
- [redis-alerts-snippets.md](./redis-alerts-snippets.md) – Redis tail-loss and coordination health alerts.
- [tick-alerts-snippets.md](./tick-alerts-snippets.md) – Tick execution and ledger backlog alerts.
- [backup-alerts-snippets.md](./backup-alerts-snippets.md) – Backup pipeline, restore-drill, artifact-lineage/readability, recovery-participant convergence, and blocked-reopen alerts.
- [player-experience-alerts-snippets.md](./player-experience-alerts-snippets.md) – Player-centric SLO alerts for login, command latency, chat delivery, and entry-path availability.
- [observability-stack-alerts-snippets.md](./observability-stack-alerts-snippets.md) – Alertmanager, Prometheus, tracing, logging, and Grafana health alerts plus the smoke test rule.
- [scripting-execution-policy-alerts-snippets.md](./scripting-execution-policy-alerts-snippets.md) – Script execution, policy, retry, and quarantine alert references.
- [player-experience.json](./player-experience.json) – Reference player experience SLO dashboard for the target-state live-traffic SLI surface.
- [player-experience-drilldown.json](./player-experience-drilldown.json) – Player incident drilldown dashboard with outcome breakdowns and per-command/per-channel latency views for triage after an SLO breach.
- [redis-coordination-health.json](./redis-coordination-health.json) – Redis and coordination health dashboard, including tail-loss vs dynamic budget, AOF size/restart, coordination memory and key counts, and per-prefix coordination metrics.
- [tick-health-ledger.json](./tick-health-ledger.json) – Tick health and ledger dashboard, including tick status, execution-time histograms and ratios, retry and command queue depths, ledger pending/applied/abandoned metrics, replay-convergence budget/breach signals, replay fairness signals, and the canonical replay alert states `TickEffectsReplaySloBreached` / `TickEffectsReplayStarved`.

To import a dashboard, use Grafana’s “Import dashboard” feature and either upload the JSON file directly or paste its contents into the “Import via panel JSON” field, then bind it to the correct Prometheus data source.

Routed-alert authority and degradation follow [ADR 0158](../../architecture/decisions/adr-0158-simplified-observability-degradation-without-fallback-alert-authority.md); external blackbox/deadman applicability follows [ADR 0159](../../architecture/decisions/adr-0159-profile-dependent-independent-deadman-and-public-path-monitoring.md).

Reference alert rules and diagnostic registry:

- The split alert snippet files in this directory provide shared PromQL, Alertmanager evaluation rule names, and severity expectations for dashboards and diagnostics.
- They are not a Prometheus fallback active-alert registry, cross-source alert-family equivalence table, or Logging & Admin deduplication authority.

## Conventions (Contract)

- Shared alert snippet templates must follow the alert label contract in `design/architecture/system-architecture-logging-monitoring.md` (especially `severity ∈ {P0,P1,P2}`, plus `owner` and `runbook` labels).
- Shared PromQL expressions must respect metric units. If a metric name contains `_ms`, comparisons must use millisecond thresholds (for example `> 250`, not `> 0.25`).
- Player experience SLIs/SLOs are evaluated per approved bounded `scope` once the target-state producers exist. Dashboards may include global rollups, but they should preserve enough scoped visibility for operators to see blast radius quickly without adding raw high-cardinality identifiers to ordinary gameplay/session metrics.
- Entry-path availability panels should be computed from explicit attempts counters (for example `entrypath_connection_attempts_total{service,scope,path,outcome}` once the target-state SLI producers exist), retaining the bounded emitting `service` label rather than aggregating it away, not from a single failure-mode proxy like “connection limits exceeded”.
- When `player-experience.json` adds external edge/canary panels, it should preserve the distinction between authoritative externally hosted checks and their mirrored Prometheus views. This applies to `entrypath_blackbox_probe_success{...}`, `playerflow_canary_success{...}`, and `playerflow_canary_latency_ms{...}`; dashboard wording should not imply that Grafana or Prometheus is the only paging path for those signals.
- For player-entry-path dashboards, external blackbox reachability (`entrypath_blackbox_probe_success{path,target}` or a documented equivalent mapping) is the primary outage-detection signal for total edge-path failures. Attempts-based availability panels remain required as the in-service diagnostic and compliance view after traffic is reaching Gateway/TCP Proxy.
- External blackbox and deadman signals shown in Grafana are mirrored views of the authoritative external monitoring system. Dashboard authors should preserve that distinction and should not imply Grafana or Prometheus is the only paging path for those checks.
