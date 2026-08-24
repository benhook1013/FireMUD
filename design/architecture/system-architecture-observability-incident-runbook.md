# FireMUD Observability Stack Incident Runbook

This runbook covers operational scenarios where observability backends (Prometheus, Alertmanager, Elasticsearch/Kibana, Grafana, Jaeger/OpenTelemetry Collector) are degraded or unavailable.

It complements the degraded-mode expectations in `design/architecture/system-architecture-logging-monitoring.md` and focuses on what operators should do when the tooling used to diagnose incidents is itself failing.

## Objectives

- Preserve player-facing operation using authoritative systems (services, PostgreSQL, coordination rules) even when observability backends are impaired.
- Provide bounded diagnostic paths so operators can still investigate “is the game healthy?” without Kibana/Grafana/Jaeger, while keeping authoritative domain and Alertmanager state distinct.
- Restore observability backends with minimal risk and clear verification steps.

## Independent Detection Contract

- Deployment profiles must declare whether independent external detection is `required` or `omitted`.
- Profiles declaring independent monitoring `required` (hosted production profiles claiming externally verified availability or monitoring-resilient readiness) must not rely solely on Prometheus + Alertmanager to detect failure of that same observability stack.
- Required independent detection for those profiles:
  - An authoritative externally hosted deadman/heartbeat pager for the in-cluster monitoring stack.
  - Authoritative externally hosted blackbox checks for every path in the profile's complete `exposedPublicPlayerPaths` set (`websocket` and/or `telnet`). Non-exposed paths are `not_applicable`; an exposed path without current evidence means the independent claim is incomplete.
- Profiles declaring independent monitoring `omitted` may use local or operator-dependent detection and must retain an explicit degraded-detection posture; omission does not block player traffic.
- During an incident, treat these external checks as the source of truth for “is the monitoring stack itself alive?” and “is the public gameplay edge reachable at all?” when in-cluster telemetry is missing. Prometheus, Alertmanager, Grafana, Kibana, Jaeger, and collector interfaces may remain private; local or provider-native checks diagnose those components.
- Mirrored Prometheus metrics for those checks are useful for dashboards and smoke tests, but they do not satisfy the independent detection requirement by themselves. See `design/observability/external-monitoring/README.md`.

### Deadman Freshness Contract

- The canonical independent heartbeat signal for profiles declaring independent monitoring `required` is `observability_deadman_heartbeat_timestamp_seconds{source}` as defined in `design/architecture/system-architecture-logging-monitoring.md#external-probe-and-deadman-contract-normative`.
- `source` should identify the emitting environment or bounded heartbeat source and remain low-cardinality; retain the exact external monitor identity in evidence rather than this label.
- For profiles declaring independent monitoring `required`, deadman paging must use the profile's configured heartbeat interval, stale threshold, probe cadence, and derived maximum detection budget. The configured values—not a universal `3 * heartbeat_interval_seconds` rule—are the authority for stale/unknown classification.
- Default target-state guidance for profiles declaring independent monitoring `required`:
  - `heartbeat_interval_seconds = 60`
  - page when the heartbeat age exceeds `180` seconds
- If a profile declaring independent monitoring `required` uses a hosted monitoring product that cannot expose the canonical metric name directly, it must document an equivalent query and threshold that preserves the profile's configured stale-threshold and detection-budget semantics, including the retained configuration and evidence used to evaluate freshness.
- For those profiles, the authoritative external monitor must also retain its own native check definition and paging rule; the Prometheus mirror is only a secondary representation of that state.

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

### Prometheus operator diagnostics

- Treat Alertmanager state as unreliable if Prometheus is stale.
- Use service health endpoints, Kubernetes events, and logs to determine whether player-facing SLOs are likely being violated.
- If tick safety is in question, prefer defensive actions (pause affected regions/tenants) based on authoritative tick controls and error logs rather than waiting for metrics to recover.
  - If your deployment does not yet expose `region_id`-scoped pause controls end-to-end, apply the closest available scope (for example `tenant_id` + `game_instance_id` alias) and record the scope substitution in the incident timeline.

For an `independent-required` profile, use the [player-experience direct external-monitor retrieval procedure](system-architecture-player-experience-incident-runbook.md#direct-external-monitor-retrieval-when-prometheus-is-unavailable) when Prometheus is unavailable. Retrieve the authoritative deadman/public-path result from the off-cluster monitor's native API or console, or its retained evidence store outside the monitored failure domain. Validate the profile, complete `exposedPublicPlayerPaths` coverage, status, `evidenceObservedAt`, observed age against the configured `detectionBudgetSeconds`, and opaque evidence references before using it. Missing, stale, unavailable, or invalid evidence remains `unknown`/degraded; a Prometheus mirror is not a substitute. Optional player-flow canary metrics remain a separate Prometheus-facing capability and are not made externally available by this fallback. Profiles with independent monitoring omitted retain their operator-dependent degraded posture.

### Prometheus recovery and verification

1. Restore Prometheus availability.
2. Verify that scrape targets return to `UP` and series timestamps advance.
3. Confirm a known “heartbeat” metric updates (for example a service uptime gauge).
4. Confirm alerts re-evaluate and resolve/firing states change as expected.
5. Confirm the cadence-derived compatibility diagnostics `redis_coordination_tail_loss_budget_ms` and `redis_coordination_tail_loss_slo_breached` plus the short-window and 1-day entry-path availability rules are evaluating again. Do not treat the compatibility pair as measured-SLO proof. When implemented, verify the target measured `redis_unreplicated_write_window_slo_ms` and `redis_unreplicated_write_window_slo_breached{scope}` series separately.
6. For a profile declaring independent monitoring `required`, confirm the independent deadman/heartbeat monitor recovers so future total-Prometheus outages will page again.
   - Verify that `observability_deadman_heartbeat_timestamp_seconds{source=...}` (or the documented equivalent external signal) advances again.
   - Verify the profile's configured heartbeat interval, stale threshold, probe cadence, and derived maximum detection budget against the retained external-monitor evidence. Profiles declaring independent monitoring `omitted` retain their documented degraded-detection posture instead.
7. For a profile declaring independent monitoring `required`, verify fresh authoritative off-cluster synthetic-probe evidence and successful page delivery for every path in `exposedPublicPlayerPaths`, including the externally routed WebSocket and Telnet detectors when exposed. Keep recovery and independent-assurance status `unknown`/degraded until every exposed-path check and its page-delivery evidence is current and successful; Prometheus mirrors are optional and do not substitute for this external evidence.

## Alertmanager Down or Not Routing

### Alertmanager symptoms

- No notifications despite obvious SLO violations.
- Alerts appear in Prometheus but never reach notification channels.

### Alertmanager triage

1. Confirm whether Prometheus is evaluating alert rules.
2. Validate Alertmanager health and configuration reload status.
3. Check routing expectations:
   - Ensure alert label contract is present (`service`, `severity`, `owner`, `runbook`, and optional `alert_class`).

### Alertmanager unavailable diagnostics

- If Prometheus remains reachable, query a small, explicitly supported set of canonical recording-rule values for operator diagnosis. Label every result with `observed_at` and freshness, apply the configured diagnostic freshness budget, and change stale values to `unknown`.
- Diagnostic values are not routed alerts, do not carry Alertmanager grouping, inhibition, silence, notification, or duplicate-suppression semantics, and must never be merged into a second active-alert list or used as readiness/recovery authority.
- For player triage, inspect login success ratio, command p99 latency, entry-path availability, and chat delivery latency so edge and chat incidents are not hidden when Alertmanager is unavailable. Use the installed backup recording rules (`backup_pipeline_recent_backup_slo_breached`, `backup_pipeline_recent_verification_slo_breached`, `backup_pipeline_recent_restore_drill_slo_breached`, `backup_artifact_lineage_invalid`, and `backup_artifact_restore_unreadable`) as diagnostic evidence only. The `recovery_participant_convergence_*` recordings remain unavailable target state until the durable controller and exporter are implemented and proved; until then, treat their absence as `unknown` and follow the owning recovery-evidence path.
- Inspect source-absence and convergence-coverage metrics as evidence pointers, but do not treat a diagnostic snapshot as the readiness decision. Readiness remains determined by the owning backup/recovery evidence and durable controller state; Prometheus never becomes recovery authority.
- In the target state, `recovery_participant_convergence_coverage_missing{environment,participant}` compares the current coverage projection with the authoritative complete inventory, while `recovery_participant_convergence_source_missing{source_family}` identifies a global monitoring gap. Missing, stale, or unavailable diagnostic evidence is unknown and requires the owner-defined evidence path.
- If Logging & Admin consumes Alertmanager notifications, ensure the UI clearly shows “Alertmanager unavailable” and does not present diagnostic conditions as canonical alerts.

### Alertmanager recovery and verification

- Trigger a non-paging smoke-test alert (`alert_class="test"`, `severity="P2"`) in a non-production environment and verify routing end-to-end.

## Elasticsearch/Kibana Down or Indexing Stalled

### Elasticsearch/Kibana symptoms

- Kibana dashboards and searches fail or show no recent logs.
- Operators cannot drill down by `tenantId`/`gameInstanceId`/`regionId`/`traceId`.

### Elasticsearch/Kibana triage

1. Distinguish “Kibana UI down” vs “Elasticsearch ingest/indexing down”.
2. Confirm Fluent Bit is running and shipping logs (pod health, errors).
3. Check Elasticsearch cluster health for disk pressure, shard allocation failures, or OOM.

### Elasticsearch/Kibana operator fallback

- Use Kubernetes pod logs and service logs directly for the affected service(s).
- Prefer structured log fields (`service`, `traceId`, `correlationId`) when manually filtering logs, adding `tenantId`, `gameInstanceId`, `regionId`, and `characterId` only when those fields are present and expected by the affected record's logging contract.
- Loss of Elasticsearch/Kibana indexing removes trace-to-log correlation and log-based drilldown, but does not by itself make healthy Jaeger/OpenTelemetry tracing unreliable. Continue using Jaeger traces when the collector and Jaeger query path are healthy; pivot to metrics/health endpoints only when tracing is also unavailable or insufficient for the incident.

### Elasticsearch/Kibana recovery and verification

1. Restore Elasticsearch cluster health.
2. Verify recent logs appear for a known active service.
3. Emit or identify a recovery smoke record carrying `service` and `traceId`; include gameplay identity fields (`tenantId`, `gameInstanceId`, `regionId`, and `characterId` when applicable) only when the exercised record's canonical logging schema requires them.
4. Verify Kibana saved searches return that record when filtering by `service` and `traceId`, then apply the gameplay identity filters only when those fields are expected by the logging contract for that record.

## Grafana Down

### Grafana symptoms

- Dashboards unavailable even though Prometheus is healthy.

### Grafana triage

1. Confirm Prometheus is healthy and has data (direct query or API).
2. Confirm Grafana datasource connectivity.

### Grafana operator diagnostics

- If Prometheus is reachable, query it directly for a small set of critical “is it healthy?” diagnostics. Each result is a bounded, freshness-labelled snapshot rather than routed alert state; stale values become `unknown` and do not authorize readiness or recovery:
  - login success ratio (`login_success_ratio_gateway_15m`, `login_success_ratio_tcpproxy_15m` or equivalent expressions),
  - command latency (`command_latency_ms_p99_gateway_5m`, `command_latency_ms_p99_tcpproxy_5m`) broken down by the bounded core-command label set (`move`, `look`, `combat`),
  - synthetic player-flow canaries (`playerflow_canary_success{flow="login",path=...,target=...,profile=...}`, `playerflow_canary_success{flow="command",path=...,target=...,profile=...}`, `playerflow_canary_latency_ms{flow="command",path=...,target=...,profile=...}`, `playerflow_canary_last_run_timestamp_seconds{flow=...,path=...,target=...,profile=...}`, and `playerflow_canary_freshness_budget_seconds{profile=...}`) only when the profile advertises the canary capability and the path is in its complete `exposedPublicPlayerPaths` set; before consuming success or latency, compare each matching last-run age with the same profile budget. `PlayerFlowCanaryEvidenceStale` marks that evidence as unknown/degraded, not as a player-flow failure. Omitted capability or non-exposed paths are `not_applicable`, while missing or unavailable advertised evidence is also `unknown`/degraded,
  - entry-path availability (`entrypath_availability_gateway_5m`, `entrypath_availability_tcpproxy_5m`, plus `entrypath_availability_gateway_1d` / `entrypath_availability_tcpproxy_1d` for compliance context),
  - for profiles declaring independent monitoring `required`, public gameplay entry-path blackbox reachability (`entrypath_blackbox_probe_success{path=...,target=...}` or the environment-equivalent external probe metric) for each path in `exposedPublicPlayerPaths`, so total edge failures that never reached Gateway/TCP Proxy are still visible; non-exposed paths are `not_applicable`. Preserve per-path and per-target identity before any aggregation. This independent deadman/blackbox requirement is separate from optional player-flow canary capability,
  - chat latency (`chat_delivery_latency_ms_p99_5m`),
  - installed backup diagnostic recording rules (`backup_pipeline_recent_backup_slo_breached`, `backup_pipeline_recent_verification_slo_breached`, `backup_pipeline_recent_restore_drill_slo_breached`, `backup_artifact_lineage_invalid`, `backup_artifact_restore_unreadable`),
  - target-state recovery-controller recordings (`recovery_participant_convergence_blocked`, `recovery_environment_convergence_blocked`, `recovery_participant_convergence_coverage_missing`, `recovery_participant_convergence_source_missing`) only after the durable controller and exporter are implemented and proved; until then, their absence is `unknown` and operators use the owning recovery-evidence path,
  - the five backup source-absence alerts (`BackupLastSuccessMetricsAbsent`, `BackupVerificationLastSuccessMetricsAbsent`, `BackupRestoreDrillLastSuccessMetricsAbsent`, `BackupArtifactLineageMetricsAbsent`, `BackupArtifactRestoreReadabilityMetricsAbsent`), which diagnose missing evidence; the owning backup/readiness control blocks on authoritative evidence state rather than alert state, and missing or unavailable diagnostics are `unknown`,
  - target-state `RecoveryParticipantConvergenceCoverageMissing` and `RecoveryParticipantConvergenceMetricsAbsent`, which diagnose affected-environment coverage and global monitoring gaps without replacing or mutating durable controller state; the recovery controller owns any readiness block, and missing or unavailable diagnostics are `unknown`, so operators follow the owning recovery-evidence path,
  - tick safety ratio (`tick_execution_safety_ratio_p99`),
  - coordination tail-loss (`redis_coordination_tail_loss_ms`, `redis_coordination_tail_loss_budget_ms`, and `redis_coordination_tail_loss_slo_breached`).
- Prefer recorded rules where available so operators do not hand-craft complex PromQL during an incident. These diagnostics never become a second Alertmanager authority, replace owner evidence, or establish, clear, or mutate readiness and recovery state; the owning authority or control plane makes those decisions.

## Jaeger / OpenTelemetry Collector Down

### Jaeger/collector symptoms

- Jaeger UI has no traces or trace search fails.
- Services log OTLP export errors or collector is unreachable.

### Jaeger/collector triage

1. Confirm whether the collector is down or Jaeger storage/query is down.
2. Validate that services still run normally without tracing (tracing is best-effort).

### Jaeger/collector operator fallback

- Pivot to metrics and logs:
  - Use SLI/SLO panels and alert conditions only to identify an impacted deployment or approved bounded `scope` bucket.
  - Resolve the exact `<tenantId, gameInstanceId, regionId>` runtime scope through control-plane/runtime-health reads and structured logs before taking scope-specific action.
  - Use logs filtered by `service`, `traceId`, and `correlationId`, adding gameplay identity fields (`tenantId`, `gameInstanceId`, `regionId`, `characterId`) only when those fields are present in the affected record to follow the flow.

### Jaeger/collector recovery and verification

1. Restore collector + Jaeger and verify their health and export/query paths.
2. Branch on the environment's advertised and independently proved ADR 0017 capability and covered workflow. At level 1, confirm metrics and structured logs remain usable and treat trace arrival as best-effort rather than a recovery gate.
3. At levels 2-3, verify only a workflow explicitly proved at that level and confirm its required bounded attributes. Treat tenant/game-instance/region-scoped sampling or verification as available only when ADR 0017 level 4 is both advertised and independently proved for the affected workflow; do not require login, command, or scoped trace evidence otherwise.

## Post-Incident Checklist

- Document the root cause and whether the observability stack failure masked a player-visible incident.
- Add or tighten alerts on observability backend health (Prometheus target availability, Alertmanager routing errors, Elasticsearch disk pressure, collector export failures).
- For every profile declaring independent monitoring `required`, confirm that its external deadman and every applicable exposed public gameplay edge blackbox path are documented and tested as part of that profile's monitoring contract rather than left as environment-specific tribal knowledge; non-exposed paths remain `not_applicable`. Profiles declaring independent monitoring `omitted` must retain the documented degraded/operator-dependent posture.
- If the incident required manual fallback steps, encode them into a small, repeatable operator checklist or one-shot script rather than leaving them as tribal knowledge.

## Diagnostic Query Cheat Sheet

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
- `backup_artifact_lineage_invalid`
- `backup_artifact_restore_unreadable`
- `recovery_participant_convergence_blocked`
- `recovery_environment_convergence_blocked`
- `recovery_participant_convergence_coverage_missing`
- `recovery_participant_convergence_source_missing`
- `tick_effects_pending_oldest_age_seconds`
- `tick_effects_replay_convergence_budget_seconds`
- `tick_effects_replay_slo_breached`
- `tick_effects_replay_starved`

Alert names:

The following are canonical alert names. Use them when checking alert state in Prometheus or Alertmanager, not when querying for the underlying time-series values:

- `TickEffectsReplaySloBreached`
- `TickEffectsReplayStarved`
- `BackupLastSuccessMetricsAbsent`
- `BackupVerificationLastSuccessMetricsAbsent`
- `BackupRestoreDrillLastSuccessMetricsAbsent`
- `BackupArtifactLineageMetricsAbsent`
- `BackupArtifactRestoreReadabilityMetricsAbsent`
- `RecoveryParticipantConvergenceMetricsAbsent`
- `RecoveryParticipantConvergenceCoverageMissing`

Mirrored external signals:

When external reachability or total monitoring-stack failure is in question for a profile declaring independent monitoring `required`, use the authoritative off-cluster result for every path in `exposedPublicPlayerPaths` and check a corresponding Prometheus mirror only when that mirror is published. A missing optional mirror does not invalidate otherwise current authoritative external evidence. Non-exposed paths are `not_applicable`; profiles declaring independent monitoring `omitted` use their documented local/operator-dependent checks instead. Player-flow canary metrics remain separately gated by the advertised canary capability and exposed-path set:

- `entrypath_blackbox_probe_success{path="websocket",target="gateway"}` when the mirror is published and `websocket` is exposed, otherwise absent or `not_applicable` as appropriate
- `entrypath_blackbox_probe_success{path="telnet",target="tcp_proxy"}` when the mirror is published and `telnet` is exposed, otherwise absent or `not_applicable` as appropriate
- `observability_deadman_heartbeat_timestamp_seconds{source=...}` when the external-monitoring owner publishes that mirror
- `playerflow_canary_success{flow="login",path=...,target=...,profile=...}` only for an advertised canary capability and exposed path
- `playerflow_canary_success{flow="command",path=...,target=...,profile=...}` only for an advertised canary capability and exposed path
- `playerflow_canary_latency_ms{flow="command",path=...,target=...,profile=...}` only for an advertised canary capability and exposed path
- `playerflow_canary_last_run_timestamp_seconds{flow=...,path=...,target=...,profile=...}` and the matching `playerflow_canary_freshness_budget_seconds{profile=...}` establish freshness; stale available evidence raises `PlayerFlowCanaryEvidenceStale` and remains unknown/degraded until refreshed.
