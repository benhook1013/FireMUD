# FireMUD Observability Stack Incident Runbook

This runbook covers operational scenarios where observability backends (Prometheus, Alertmanager, Elasticsearch/Kibana, Grafana, Jaeger/OpenTelemetry Collector) are degraded or unavailable.

It complements the degraded-mode expectations in `design/architecture/system-architecture-logging-monitoring.md` and focuses on what operators should do when the tooling used to diagnose incidents is itself failing.

## Objectives

- Preserve player-facing operation using authoritative systems (services, PostgreSQL, coordination rules) even when observability backends are impaired.
- Provide predictable fallbacks so operators can still answer “is the game healthy?” without Kibana/Grafana/Jaeger.
- Restore observability backends with minimal risk and clear verification steps.

## Independent Detection Contract

- Prod-like environments must not rely solely on Prometheus + Alertmanager to detect failure of that same observability stack.
- Required independent detection:
  - An authoritative externally hosted deadman/heartbeat pager for the in-cluster monitoring stack.
  - Authoritative externally hosted blackbox checks for public observability entrypoints and public gameplay entrypaths.
- During an incident, treat these external checks as the source of truth for “is the monitoring stack itself alive?” and “is the public edge reachable at all?” when in-cluster telemetry is missing.
- Mirrored Prometheus metrics for those checks are useful for dashboards and smoke tests, but they do not satisfy the independent detection requirement by themselves. See `design/observability/external-monitoring/README.md`.

### Deadman Freshness Contract

- The canonical independent heartbeat signal is `observability_deadman_heartbeat_timestamp_seconds{source}` as defined in `design/architecture/system-architecture-logging-monitoring.md#external-probe-and-deadman-contract-normative`.
- `source` should identify the emitting environment or monitor instance and remain low-cardinality.
- Deadman paging should treat the signal as stale when the externally observed timestamp is older than `3 * heartbeat_interval_seconds`.
- Default target-state guidance for prod-like environments:
  - `heartbeat_interval_seconds = 60`
  - page when the heartbeat age exceeds `180` seconds
- If an environment uses a hosted monitoring product that cannot expose the canonical metric name directly, it must document an equivalent query and threshold that preserves the same semantics: “page when the independently observed in-cluster heartbeat has been missing for more than three expected heartbeat intervals.”
- The authoritative external monitor must also retain its own native check definition and paging rule; the Prometheus mirror is only a secondary representation of that state.

## Common Fallbacks (When Dashboards Are Unavailable)

- **Service health**
  - Use `/actuator/health/readiness` and `/actuator/health/liveness` endpoints (and Kubernetes readiness/liveness) as the first source of truth for whether pods are healthy.
  - Prefer querying the owning service directly (Gateway, Game Session, Account, etc.) rather than relying on a missing dashboard.
- **Kubernetes signals**
  - Check pod restarts, crash loops, and events for the affected namespace(s).
  - Confirm resource pressure (CPU/memory) and node-level issues that can explain observability loss.
- **Direct dependency checks**
  - Validate PostgreSQL connectivity and basic query latency from service logs or health endpoints.
  - Validate Redis coordination availability via service-level health checks and the tick controls that depend on it.

## Prometheus Down or Stale

### Prometheus symptoms

- Grafana panels show “no data” for most metrics, or series stop updating.
- Alerts stop firing or stop resolving even when services are healthy/unhealthy.

### Prometheus triage

1. Confirm whether Prometheus is down vs scraping is broken:
   - If Prometheus is reachable but targets are down, treat it as a scrape/config issue.
2. Validate at least one service metrics endpoint:
   - Confirm `/actuator/prometheus` responds for a known service.
3. Check cluster health:
   - Verify storage pressure or OOM conditions on Prometheus pods.

### Prometheus operator fallback

- Treat Alertmanager state as unreliable if Prometheus is stale.
- Use service health endpoints, Kubernetes events, and logs to determine whether player-facing SLOs are likely being violated.
- If tick safety is in question, prefer defensive actions (pause affected regions/tenants) based on authoritative tick controls and error logs rather than waiting for metrics to recover.
  - If your deployment does not yet expose `region_id`-scoped pause controls end-to-end, apply the closest available scope (for example `tenant_id` + `game_instance_id` alias) and record the scope substitution in the incident timeline.

### Prometheus recovery and verification

1. Restore Prometheus availability.
2. Verify that scrape targets return to `UP` and series timestamps advance.
3. Confirm a known “heartbeat” metric updates (for example a service uptime gauge).
4. Confirm alerts re-evaluate and resolve/firing states change as expected.
5. Confirm the canonical tail-loss and entry-path recording rules are evaluating again (`redis_coordination_tail_loss_budget_ms`, `redis_coordination_tail_loss_slo_breached`, short-window entry-path availability, and 1-day entry-path availability), since operator fallback depends on them.
6. Confirm the independent deadman/heartbeat monitor recovers so future total-Prometheus outages will page again.
   - Verify that `observability_deadman_heartbeat_timestamp_seconds{source=...}` (or the documented equivalent external signal) advances again.
   - Verify that the external monitor’s staleness threshold still matches the `3 * heartbeat_interval_seconds` contract for the environment.

## Alertmanager Down or Not Routing

### Alertmanager symptoms

- No notifications despite obvious SLO violations.
- Alerts appear in Prometheus but never reach notification channels.

### Alertmanager triage

1. Confirm whether Prometheus is evaluating alert rules.
2. Validate Alertmanager health and configuration reload status.
3. Check routing expectations:
   - Ensure alert label contract is present (`service`, `severity`, `owner`, `runbook`, and optional `alert_class`).

### Alertmanager operator fallback

- Use the fallback recording-rule approach documented in `design/architecture/system-architecture-logging-monitoring.md` only for a small, explicitly supported set of critical conditions.
- During fallback, explicitly check all supported player SLO conditions (login success ratio, command p99 latency, entry-path availability, and chat delivery latency) so edge and chat incidents are not hidden when Alertmanager is unavailable.
- Also check the supported backup fallback conditions (`backup_pipeline_recent_backup_slo_breached`, `backup_pipeline_recent_verification_slo_breached`, `backup_pipeline_recent_restore_drill_slo_breached`, `backup_tick_pause_wait_budget_breached`, `backup_tick_pause_duration_budget_breached`, and `backup_ticks_paused_budget_breached`) so backup pipeline incidents do not disappear when Alertmanager routing is impaired.
- If Logging & Admin consumes Alertmanager notifications, ensure the UI clearly shows “Alertmanager unavailable” and does not present fallback conditions as canonical alerts.

### Alertmanager recovery and verification

- Trigger a non-paging smoke-test alert (`alert_class="test"`, `severity="P2"`) in a non-production environment and verify routing end-to-end.

## Elasticsearch/Kibana Down or Indexing Stalled

### Elasticsearch/Kibana symptoms

- Kibana dashboards and searches fail or show no recent logs.
- Operators cannot drill down by `tenantId`/`regionId`/`traceId`.

### Elasticsearch/Kibana triage

1. Distinguish “Kibana UI down” vs “Elasticsearch ingest/indexing down”.
2. Confirm Fluent Bit is running and shipping logs (pod health, errors).
3. Check Elasticsearch cluster health for disk pressure, shard allocation failures, or OOM.

### Elasticsearch/Kibana operator fallback

- Use Kubernetes pod logs and service logs directly for the affected service(s).
- Prefer structured log fields (`service`, `tenantId`, `regionId`, `correlationId`, `traceId`) when manually filtering logs.
- If logs are unavailable, treat tracing as unreliable as well (trace-log correlation will fail) and pivot to metrics/health endpoints.

### Elasticsearch/Kibana recovery and verification

1. Restore Elasticsearch cluster health.
2. Verify recent logs appear for a known active service.
3. Verify Kibana saved searches return results when filtering by `tenantId` and `service`.

## Grafana Down

### Grafana symptoms

- Dashboards unavailable even though Prometheus is healthy.

### Grafana triage

1. Confirm Prometheus is healthy and has data (direct query or API).
2. Confirm Grafana datasource connectivity.

### Grafana operator fallback

- Query Prometheus directly for a small set of critical “is it healthy?” checks:
  - login success ratio (`login_success_ratio_gateway_15m`, `login_success_ratio_tcpproxy_15m` or equivalent expressions),
  - command latency (`command_latency_ms_p99_gateway_5m`, `command_latency_ms_p99_tcpproxy_5m`) broken down by the bounded core-command label set (`move`, `look`, `combat`),
  - synthetic player-flow canaries (`playerflow_canary_success{flow="login",path=...}`, `playerflow_canary_success{flow="command",path=...}`, `playerflow_canary_latency_ms{flow="command",path=...}`),
  - entry-path availability (`entrypath_availability_gateway_5m`, `entrypath_availability_tcpproxy_5m`, plus `entrypath_availability_gateway_1d` / `entrypath_availability_tcpproxy_1d` for compliance context),
  - entry-path blackbox reachability (`entrypath_blackbox_probe_success{path=...}` or the environment-equivalent external probe metric) so total edge failures that never reached Gateway/TCP Proxy are still visible,
  - chat latency (`chat_delivery_latency_ms_p99_5m`),
  - backup fallback conditions (`backup_pipeline_recent_backup_slo_breached`, `backup_pipeline_recent_verification_slo_breached`, `backup_pipeline_recent_restore_drill_slo_breached`, `backup_tick_pause_wait_budget_breached`, `backup_tick_pause_duration_budget_breached`, `backup_ticks_paused_budget_breached`),
  - tick safety ratio (`tick_execution_safety_ratio_p99`),
  - coordination tail-loss (`redis_coordination_tail_loss_ms`, `redis_coordination_tail_loss_budget_ms`, and `redis_coordination_tail_loss_slo_breached`).
- Prefer recorded rules where available so operators do not hand-craft complex PromQL during an incident.

## Jaeger / OpenTelemetry Collector Down

### Jaeger/collector symptoms

- Jaeger UI has no traces or trace search fails.
- Services log OTLP export errors or collector is unreachable.

### Jaeger/collector triage

1. Confirm whether the collector is down or Jaeger storage/query is down.
2. Validate that services still run normally without tracing (tracing is best-effort).

### Jaeger/collector operator fallback

- Pivot to metrics and logs:
  - Use SLI/SLO panels and alert conditions to identify impacted tenants/regions.
  - Use logs filtered by `tenantId`, `regionId`, and `correlationId` to follow the flow.

### Jaeger/collector recovery and verification

1. Restore collector + Jaeger.
2. Verify at least one trace appears for a known request path (login or a representative command).
3. Verify that traces include required attributes (`tenantId`, `regionId`, and when applicable `characterId`).

## Post-Incident Checklist

- Document the root cause and whether the observability stack failure masked a player-visible incident.
- Add or tighten alerts on observability backend health (Prometheus target availability, Alertmanager routing errors, Elasticsearch disk pressure, collector export failures).
- If detection depended on an external deadman or edge blackbox path, confirm that path is documented and tested as part of the prod-like monitoring contract rather than left as environment-specific tribal knowledge.
- If the incident required manual fallback steps, encode them into a small, repeatable operator checklist or one-shot script rather than leaving them as tribal knowledge.

## Fallback Query Cheat Sheet

When Grafana is unavailable but Prometheus is healthy, operators should start with the canonical recording-rule names already referenced in this runbook.

Recording rules:

- `login_success_ratio_gateway_15m`
- `login_success_ratio_tcpproxy_15m`
- `command_latency_ms_p99_gateway_5m`
- `command_latency_ms_p99_tcpproxy_5m`
- `entrypath_availability_gateway_5m`
- `entrypath_availability_tcpproxy_5m`
- `entrypath_availability_gateway_1d`
- `entrypath_availability_tcpproxy_1d`
- `chat_delivery_latency_ms_p99_5m`
- `tick_execution_safety_ratio_p99`
- `redis_coordination_tail_loss_budget_ms`
- `redis_coordination_tail_loss_slo_breached`
- `backup_pipeline_recent_backup_slo_breached`
- `backup_pipeline_recent_verification_slo_breached`
- `backup_pipeline_recent_restore_drill_slo_breached`
- `backup_tick_pause_wait_budget_breached`
- `backup_tick_pause_duration_budget_breached`
- `backup_ticks_paused_budget_breached`
- `tick_effects_pending_oldest_age_seconds`
- `tick_effects_replay_convergence_budget_seconds`
- `tick_effects_replay_slo_breached`
- `tick_effects_replay_starved`

Alert names:

The following are canonical alert names. Use them when checking alert state in Prometheus or Alertmanager, not when querying for the underlying time-series values:

- `TickEffectsReplaySloBreached`
- `TickEffectsReplayStarved`

Mirrored external signals:

When external reachability or total monitoring-stack failure is in question, also check the mirrored external signals:

- `entrypath_blackbox_probe_success{path="websocket",target=...}`
- `entrypath_blackbox_probe_success{path="telnet",target=...}`
- `observability_deadman_heartbeat_timestamp_seconds{source=...}`
- `playerflow_canary_success{flow="login",path=...,target=...}`
- `playerflow_canary_success{flow="command",path=...,target=...}`
- `playerflow_canary_latency_ms{flow="command",path=...,target=...}`
