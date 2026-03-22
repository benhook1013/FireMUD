# Core Alertmanager Snippets

This file contains reference PromQL expressions and Alertmanager rule snippets for core FireMUD alerts. These complement the TCP Proxy–specific rules in `tcp-proxy-alerts-snippets.md` and are intended to be imported or adapted into environment-specific rulesets.

## Redis Tail-Loss and Coordination Health

Example alert for Coordination Redis tail-loss SLO breaches:

```yaml
- alert: RedisCoordinationTailLossSLOBreached
  expr: redis_coordination_tail_loss_slo_breached > 0
  for: 5m
  labels:
    service: redis-coordination
    severity: P0
    owner: infra
    runbook: design/architecture/system-architecture-redis-incident-runbook.md#coordination-aof-tail-loss-slo-breach
  annotations:
    summary: Coordination Redis tail-loss SLO breached
    description: Tail-loss exceeds the 1–2s envelope for one or more regions. See the Redis incident runbook for reset guidance.
```

This assumes that the canonical recording rules expose:

- `redis_coordination_tail_loss_budget_ms{tenantId,regionId}` – dynamic budget computed as `max(2000, 2 * tick_interval_ms)`.
- `redis_coordination_tail_loss_slo_breached{tenantId,regionId}` – derived breach indicator based on the dynamic budget.

Example alerts for additional Coordination Redis core red lines from the Redis metrics contract:

```yaml
- alert: RedisLuaScriptLoadFailures
  expr: increase(redis_lua_script_load_failures_total[5m]) >= 1
  for: 5m
  labels:
    service: redis-coordination
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-redis-incident-runbook.md#coordination-redis-outage-or-degradation
  annotations:
    summary: Redis Lua script load failures detected
    description: One or more coordination shards are failing to load required Lua scripts.

- alert: RedisLuaScriptMissingForRegion
  expr: redis_lua_script_missing_for_region_total > 0
  for: 1m
  labels:
    service: redis-coordination
    severity: P0
    owner: infra
    runbook: design/architecture/system-architecture-redis-incident-runbook.md#coordination-redis-outage-or-degradation
  annotations:
    summary: Redis Lua script missing for active region
    description: Tick scheduling should halt for affected regions until script preload is healthy.

- alert: RedisLuaScriptRuntimeHigh
  expr: redis_lua_script_runtime_ms_p99 > (2 * tick_interval_ms)
  for: 3m
  labels:
    service: redis-coordination
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-redis-incident-runbook.md#coordination-redis-outage-or-degradation
  annotations:
    summary: Redis Lua script runtime exceeds budget
    description: Coordination script latency is beyond the runtime envelope and can degrade tick health.

- alert: RedisCoordinationOomErrors
  expr: increase(redis_coordination_oom_errors_total[1m]) >= 1
  for: 1m
  labels:
    service: redis-coordination
    severity: P0
    owner: infra
    runbook: design/architecture/system-architecture-redis-incident-runbook.md#coordination-redis-outage-or-degradation
  annotations:
    summary: Coordination Redis OOM errors detected
    description: Coordination writes are failing due to memory pressure; halt affected ticks until headroom is restored.

- alert: RedisTickPendingStuck
  expr: redis_tick_pending_stuck_total > 0
  for: 2m
  labels:
    service: redis-coordination
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#stalled-tick-region
  annotations:
    summary: Stuck tick pending state detected
    description: One or more regions have pending entries that are not converging and may need replay/reset action.
```

## Tick Execution Health

Example alert for tick execution time approaching unsafe ratios relative to lock TTL:

```yaml
- alert: TickExecutionUnsafeRatio
  expr: (tick_execution_time_ms_p99 / tick_lock_ttl_ms) > 0.75
  for: 10m
  labels:
    service: game-session-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#stalled-tick-region
  annotations:
    summary: Tick execution time approaching unsafe fraction of lock TTL
    description: Tick p99 execution time is nearing or exceeding the configured lock TTL for one or more regions. Investigate tick health and region density before adjusting tick cadence.
```

This rule assumes the **canonical metric contract** from:

- `design/architecture/system-architecture-redis-operations.md` (tick + Redis metrics catalog)
- `design/architecture/system-architecture-tick-concepts-and-invariants.md` (ratio thresholds and interpretation)

Concretely:

- `tick_execution_time_ms_p99` is a recording rule derived from `tick_execution_time_ms_bucket{tenantId,regionId,le}`.
- `tick_lock_ttl_ms` is emitted (or recorded) per `<tenantId, regionId>` and represents the lock/lease TTL budget used by tick executors.

Do not use “Timer-in-seconds” histograms under `_ms` names; producers must either emit millisecond-valued histograms/summaries or publish explicit `_seconds` metrics and define separate `_ms` recording rules with unambiguous unit conversions.

## Tick Effect Ledger Backlog

Example alert for stuck `SCHEDULED` rows in the tick effect ledger:

```yaml
- alert: TickEffectLedgerBacklog
  expr: tick_effects_pending_total > 0 and (time() - tick_effects_pending_oldest_scheduled_timestamp_seconds) > 300
  for: 10m
  labels:
    service: game-session-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#stuck-tick-effect-ledger-entries
  annotations:
    summary: Tick effect ledger has pending rows beyond grace window
    description: One or more regions have SCHEDULED tick effects that have not converged to APPLIED or ABANDONED within the expected grace window.

- alert: TickCleanupLagHigh
  expr: tick_cleanup_lag_ms > 15000
  for: 10m
  labels:
    service: game-session-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#durable-commitcoordination-cleanup-divergence
  annotations:
    summary: Tick durable commit and coordination cleanup are diverging
    description: Cleanup lag from durable commit to coordination-cleared is elevated for one or more regions; investigate replay pressure and coordination cleanup behavior.

- alert: TickReplayFairnessStarved
  expr: tick_effects_pending_total > 0 and increase(tick_effects_replay_batches_total[15m]) == 0
  for: 15m
  labels:
    service: game-session-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#stuck-tick-effect-ledger-entries
  annotations:
    summary: Tick replay controller is not servicing pending regions fairly
    description: One or more regions still have pending ledger work, but replay batches are not being executed for those regions. Investigate replay-controller fairness and starvation.

- alert: TickReplayScanLagHigh
  expr: tick_effects_replay_scan_lag_ms > 300000
  for: 15m
  labels:
    service: game-session-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#stuck-tick-effect-ledger-entries
  annotations:
    summary: Tick replay scan lag indicates controller starvation
    description: Replay scan lag is growing for one or more regions even though the replay controller remains active elsewhere.
```

This assumes a helper metric such as `tick_effects_pending_oldest_scheduled_timestamp_seconds` that tracks the oldest `SCHEDULED` entry per region.

## Backup Pipeline Health

Example alerts for missed backups and verification runs:

```yaml
- alert: BackupPipelineNoRecentBackup
  expr: backup_pipeline_recent_backup_slo_breached > 0
  for: 5m
  labels:
    service: postgres-backup
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-backup-recovery.md#backup-verification-restoration-testing
  annotations:
    summary: PostgreSQL backups have not succeeded recently
    description: No successful pg_dump backup has been recorded in the last 90 minutes. Investigate backup Jobs and storage endpoints.

- alert: BackupPipelineNoRecentVerification
  expr: backup_pipeline_recent_verification_slo_breached > 0
  for: 30m
  labels:
    service: postgres-backup
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-backup-recovery.md#backup-verification-restoration-testing
  annotations:
    summary: Backup verification has not succeeded recently
    description: No successful backup verification run has been recorded in the last 24 hours. Investigate the verify-backups CronJob and storage configuration.

- alert: BackupPipelineNoRecentRestoreDrill
  expr: backup_pipeline_recent_restore_drill_slo_breached > 0
  for: 30m
  labels:
    service: postgres-backup
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-backup-recovery.md#backup-verification-restoration-testing
  annotations:
    summary: Restore drill proof is stale
    description: No successful restore drill has been recorded within the required restore-proof freshness window. Investigate drill cadence and recovery evidence before traffic reopen decisions.

- alert: BackupTickPauseTooLongScoped
  expr: backup_tick_pause_duration_budget_breached > 0
  for: 5m
  labels:
    service: postgres-backup
    severity: P0
    owner: infra
    runbook: design/architecture/system-architecture-backup-recovery.md#backup-verification-restoration-testing
  annotations:
    summary: Tick pause window too long during scoped backup
    description: One or more backup scopes have exceeded the pause-duration budget. Investigate pause/resume controls and scope-specific backlog growth.

- alert: BackupTickPauseWaitTooLongScoped
  expr: backup_tick_pause_wait_budget_breached > 0
  for: 5m
  labels:
    service: postgres-backup
    severity: P0
    owner: infra
    runbook: design/architecture/system-architecture-backup-recovery.md#backup-verification-restoration-testing
  annotations:
    summary: Tick pause wait exceeded budget during scoped backup
    description: One or more backup scopes are taking too long to reach PAUSED. Investigate in-flight tick drain time and pause control health.

- alert: BackupTicksPausedTooLong
  expr: backup_ticks_paused_budget_breached > 0
  for: 5m
  labels:
    service: postgres-backup
    severity: P0
    owner: infra
    runbook: design/architecture/system-architecture-backup-recovery.md#backup-verification-restoration-testing
  annotations:
    summary: Backup scope remains paused unexpectedly
    description: A backup scope has remained in paused state beyond the expected window. Check pause/resume API calls and backup job completion state.

- alert: BackupPauseAliasScopeStillUsed
  expr: increase(backup_pause_scope_alias_requests_total[24h]) > 0
  for: 0m
  labels:
    service: postgres-backup
    severity: P2
    owner: infra
    runbook: design/architecture/system-architecture-backup-recovery.md#tick-pause-scope-migration-plan
  annotations:
    summary: Backup controls still use alias scope
    description: One or more backup pause/resume requests still relied on game_instance_id alias scope during the last 24 hours. Migrate automation to canonical region scope.
```

This assumes backup tooling emits scoped budget gauges directly:

- `backup_tick_pause_wait_budget_seconds{scope_type,tenantId?,regionId?}`
- `backup_tick_pause_duration_budget_seconds{scope_type,tenantId?,regionId?}`

and that Prometheus exposes derived fallback recordings:

- `backup_pipeline_recent_backup_slo_breached`
- `backup_pipeline_recent_verification_slo_breached`
- `backup_tick_pause_wait_budget_breached{scope_type,tenantId?,regionId?}`
- `backup_tick_pause_duration_budget_breached{scope_type,tenantId?,regionId?}`
- `backup_ticks_paused_budget_breached{scope_type,tenantId?,regionId?}`

Environment-specific rulesets may tune thresholds, severities, and label values, but should preserve the `owner` and `runbook` annotations so alerts always point back to the relevant documentation.

## Player Experience SLO Alerts

These example rules enforce the player-centric SLOs defined in the Logging & Monitoring architecture doc. Thresholds and severities may be tuned per environment, but the underlying metric shapes should remain consistent.

```yaml
- alert: LoginSuccessRatioLowGateway
  expr: (
    sum by (tenantId) (rate(login_requests_total{service="spring-cloud-gateway", outcome="success"}[15m]))
      /
    sum by (tenantId) (rate(login_requests_total{service="spring-cloud-gateway"}[15m]))
  ) < 0.995
  for: 15m
  labels:
    service: spring-cloud-gateway
    severity: P0
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#login-success-ratio-below-slo
  annotations:
    summary: Login success ratio below SLO
    description: Gateway login success ratio has fallen below 99.5% over the last 15 minutes.

- alert: LoginSuccessRatioLowTcpProxy
  expr: (
    sum by (tenantId) (rate(login_requests_total{service="tcp-proxy-service", outcome="success"}[15m]))
      /
    sum by (tenantId) (rate(login_requests_total{service="tcp-proxy-service"}[15m]))
  ) < 0.995
  for: 15m
  labels:
    service: tcp-proxy-service
    severity: P0
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#login-success-ratio-below-slo
  annotations:
    summary: Login success ratio below SLO (TCP Proxy)
    description: TCP Proxy login success ratio has fallen below 99.5% over the last 15 minutes.

- alert: CommandLatencyP99HighGateway
  expr: histogram_quantile(
          0.99,
          sum by (tenantId, regionId, command, le) (
            rate(command_end_to_end_latency_ms_bucket{service="spring-cloud-gateway", command=~"move|look|combat"}[5m])
          )
        ) > 250
  for: 10m
  labels:
    service: spring-cloud-gateway
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#command-latency-above-slo
  annotations:
    summary: Command p99 latency above SLO
    description: Gateway command end-to-end p99 latency has exceeded 250ms for at least one bounded core command. Preserve the `command` label so single-command regressions are not hidden by healthy higher-volume commands.

- alert: CommandLatencyP99HighTcpProxy
  expr: histogram_quantile(
          0.99,
          sum by (tenantId, regionId, command, le) (
            rate(command_end_to_end_latency_ms_bucket{service="tcp-proxy-service", command=~"move|look|combat"}[5m])
          )
        ) > 250
  for: 10m
  labels:
    service: tcp-proxy-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#command-latency-above-slo
  annotations:
    summary: Command p99 latency above SLO (TCP Proxy)
    description: TCP Proxy command end-to-end p99 latency has exceeded 250ms for at least one bounded core command. Preserve the `command` label so single-command regressions are not hidden by healthy higher-volume commands.

- alert: ChatDeliveryLatencyP99High
  expr: histogram_quantile(
          0.99,
          sum by (tenantId, channel_type, le) (rate(chat_delivery_latency_ms_bucket[5m]))
        ) > 1000
  for: 10m
  labels:
    service: social-groups-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#chat-delivery-latency-above-slo
  annotations:
    summary: Chat delivery latency above SLO
    description: Chat delivery p99 latency has exceeded 1s over the last 5 minutes for active regions.

- alert: EntryPathAvailabilityLowGateway
  expr: (
    sum by (tenantId, path) (increase(entrypath_connection_attempts_total{service="spring-cloud-gateway", outcome="success"}[5m]))
      /
    sum by (tenantId, path) (increase(entrypath_connection_attempts_total{service="spring-cloud-gateway"}[5m]))
  ) < 0.995
  for: 10m
  labels:
    service: spring-cloud-gateway
    component: entrypath
    severity: P0
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#telnet-and-websocket-path-availability-below-slo
  annotations:
    summary: Gateway entry-path availability degraded
    description: One or more tenants have acute connection failures on a gateway-owned entry path. Use the short-window view for incident response and the 1-day view for compliance.

- alert: EntryPathAvailabilityLowGatewayCompliance
  expr: (
    sum by (tenantId, path) (increase(entrypath_connection_attempts_total{service="spring-cloud-gateway", outcome="success"}[1d]))
      /
    sum by (tenantId, path) (increase(entrypath_connection_attempts_total{service="spring-cloud-gateway"}[1d]))
  ) < 0.999
  for: 30m
  labels:
    service: spring-cloud-gateway
    component: entrypath
    severity: P2
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#telnet-and-websocket-path-availability-below-slo
  annotations:
    summary: Gateway entry-path availability below 1-day SLO
    description: One or more tenants have sustained connection failures on a gateway-owned entry path over the compliance window. Inspect entrypath_connection_attempts_total and follow the player experience runbook.

- alert: EntryPathAvailabilityLowTcpProxy
  expr: (
    sum by (tenantId, path) (increase(entrypath_connection_attempts_total{service="tcp-proxy-service", outcome="success"}[5m]))
      /
    sum by (tenantId, path) (increase(entrypath_connection_attempts_total{service="tcp-proxy-service"}[5m]))
  ) < 0.995
  for: 10m
  labels:
    service: tcp-proxy-service
    component: entrypath
    severity: P0
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#telnet-and-websocket-path-availability-below-slo
  annotations:
    summary: TCP Proxy entry-path availability degraded
    description: One or more tenants have acute connection failures on TCP Proxy entry paths. Use the short-window view for incident response and the 1-day view for compliance.

- alert: EntryPathAvailabilityLowTcpProxyCompliance
  expr: (
    sum by (tenantId, path) (increase(entrypath_connection_attempts_total{service="tcp-proxy-service", outcome="success"}[1d]))
      /
    sum by (tenantId, path) (increase(entrypath_connection_attempts_total{service="tcp-proxy-service"}[1d]))
  ) < 0.999
  for: 30m
  labels:
    service: tcp-proxy-service
    component: entrypath
    severity: P2
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#telnet-and-websocket-path-availability-below-slo
  annotations:
    summary: TCP Proxy entry-path availability below 1-day SLO
    description: One or more tenants have sustained connection failures on TCP Proxy entry paths over the compliance window. Inspect entrypath_connection_attempts_total and follow the player experience runbook.

- alert: WebSocketEntryPathBlackboxUnavailable
  expr: max_over_time(entrypath_blackbox_probe_success{path="websocket"}[2m]) == 0
  for: 2m
  labels:
    service: spring-cloud-gateway
    component: entrypath
    severity: P0
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#telnet-and-websocket-path-availability-below-slo
  annotations:
    summary: WebSocket entry path unreachable from external probe
    description: Independent blackbox probes cannot reach the public WebSocket gameplay path; this catches LB, DNS, TLS, and ingress failures before traffic reaches Gateway.

- alert: TelnetEntryPathBlackboxUnavailable
  expr: max_over_time(entrypath_blackbox_probe_success{path="telnet"}[2m]) == 0
  for: 2m
  labels:
    service: tcp-proxy-service
    component: entrypath
    severity: P0
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#telnet-and-websocket-path-availability-below-slo
  annotations:
    summary: Telnet entry path unreachable from external probe
    description: Independent blackbox probes cannot reach the public Telnet gameplay path; this catches LB, DNS, TLS, and ingress failures before traffic reaches TCP Proxy.
```

## Observability Stack Health

Example alerts for the observability stack itself:

```yaml
- alert: AlertmanagerServiceUnavailable
  expr: up{job="alertmanager"} == 0
  for: 5m
  labels:
    service: alertmanager
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#alertmanager-down-or-not-routing
  annotations:
    summary: Alertmanager service unavailable
    description: Alertmanager is unreachable from Prometheus, so notifications and alert-state visibility are impaired even if rule evaluation continues.

- alert: AlertmanagerNotificationsFailing
  expr: rate(alertmanager_notifications_failed_total[5m]) > 0
  for: 10m
  labels:
    service: alertmanager
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#alertmanager-down-or-not-routing
  annotations:
    summary: Alertmanager notifications are failing
    description: Alertmanager is evaluating alerts but cannot deliver notifications reliably.

- alert: AlertmanagerConfigReloadFailed
  expr: alertmanager_config_last_reload_successful == 0
  for: 5m
  labels:
    service: alertmanager
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#alertmanager-down-or-not-routing
  annotations:
    summary: Alertmanager configuration reload failed
    description: Alertmanager is running with stale or invalid routing configuration.

- alert: PrometheusRuleEvaluationsFailing
  expr: increase(prometheus_rule_evaluation_failures_total[5m]) > 0
  for: 10m
  labels:
    service: prometheus
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#prometheus-down-or-stale
  annotations:
    summary: Prometheus rule evaluations are failing
    description: Prometheus cannot evaluate one or more rules; alerting and fallback recordings may be stale.

- alert: OTelCollectorExportFailures
  expr: rate(otelcol_exporter_send_failed_spans[5m]) > 0
  for: 10m
  labels:
    service: otel-collector
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#jaeger-opentelemetry-collector-down
  annotations:
    summary: OpenTelemetry Collector is failing to export spans
    description: Distributed tracing data is being dropped before it reaches Jaeger or the configured backend.

- alert: OTelCollectorUnavailable
  expr: up{job="otel-collector"} == 0
  for: 5m
  labels:
    service: otel-collector
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#jaeger-opentelemetry-collector-down
  annotations:
    summary: OpenTelemetry Collector unavailable
    description: The collector is unreachable, so new traces cannot be received even before downstream export or storage is considered.

- alert: PrometheusServiceDiscoveryFailures
  expr: increase(prometheus_sd_refresh_failures_total[5m]) > 0
  for: 10m
  labels:
    service: prometheus
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#prometheus-down-or-stale
  annotations:
    summary: Prometheus service discovery or scrape refresh is failing
    description: Prometheus cannot refresh one or more scrape target pools, so metrics may go stale without the server being fully down.

- alert: ElasticsearchClusterHealthRed
  expr: elasticsearch_cluster_health_status{color="red"} == 1
  for: 10m
  labels:
    service: elasticsearch
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#elasticsearchkibana-down-or-indexing-stalled
  annotations:
    summary: Elasticsearch cluster health is red
    description: Elasticsearch cluster health is red, which can break log ingest, search, and Kibana-backed incident triage.

- alert: ElasticsearchIndexingFailuresHigh
  expr: rate(elasticsearch_indices_indexing_index_failed_total[5m]) > 0
  for: 10m
  labels:
    service: elasticsearch
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#elasticsearchkibana-down-or-indexing-stalled
  annotations:
    summary: Elasticsearch indexing failures detected
    description: Elasticsearch is failing to index a non-zero stream of documents, so recent logs may be missing or incomplete.

- alert: JaegerQueryUnavailable
  expr: up{job="jaeger-query"} == 0
  for: 10m
  labels:
    service: jaeger
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#jaeger-opentelemetry-collector-down
  annotations:
    summary: Jaeger query service unavailable
    description: Jaeger query is unavailable, so operators cannot search or inspect traces even if spans are still being ingested.

- alert: JaegerStorageFailuresHigh
  expr: increase(jaeger_collector_spans_dropped_total[5m]) > 0
  for: 10m
  labels:
    service: jaeger
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#jaeger-opentelemetry-collector-down
  annotations:
    summary: Jaeger is dropping spans
    description: Jaeger storage or collector paths are dropping spans, so trace data is incomplete even when services still export successfully.

- alert: FluentBitOutputErrorsHigh
  expr: rate(fluentbit_output_errors_total[5m]) > 0
  for: 10m
  labels:
    service: fluent-bit
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#elasticsearchkibana-down-or-indexing-stalled
  annotations:
    summary: Fluent Bit output errors detected
    description: Log shipping is failing or backpressured; Kibana may lose recent log visibility.

- alert: GrafanaDatasourceUnavailable
  expr: grafana_datasource_up == 0
  for: 10m
  labels:
    service: grafana
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#grafana-down
  annotations:
    summary: Grafana datasource unavailable
    description: Grafana cannot query one or more configured datasources, so dashboards may render incomplete or misleading incident views.

- alert: GrafanaServiceUnavailable
  expr: up{job="grafana"} == 0
  for: 10m
  labels:
    service: grafana
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#grafana-down
  annotations:
    summary: Grafana service unavailable
    description: Grafana itself is unreachable, so dashboard-based triage is unavailable even if Prometheus and other backends are healthy.
```

Environment overlays may replace metric expressions for Elasticsearch, Grafana, or Jaeger service-health checks based on the exporters they deploy, but they should preserve the alert names, ownership, and runbook routing.

## Observability Smoke Test (Non-Production)

In non-production environments, it is often useful to verify alert routing end-to-end without triggering real P0/P1 alerts. A dedicated, test-only rule can be used for this purpose:

```yaml
- alert: ObservabilitySmokeTestAlert
  expr: observability_smoke_test_metric > 0
  for: 1m
  labels:
    service: observability-smoke-test
    severity: P2
    alert_class: test
    owner: platform
    runbook: design/architecture/system-architecture-testing.md#observability-tests
  annotations:
    summary: Observability smoke test alert
    description: This test-only alert is triggered by CI or a synthetic probe to verify Alertmanager routing. It must not be enabled in production.
```

CI jobs or manual probes should temporarily set `observability_smoke_test_metric` above zero in a non-production environment to confirm that Alertmanager receives and routes this alert with the expected labels, without paging on-call engineers.
