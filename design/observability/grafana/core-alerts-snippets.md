# Core Alertmanager Snippets

This file contains reference PromQL expressions and Alertmanager rule snippets for core FireMUD alerts. These complement the TCP Proxy–specific rules in `tcp-proxy-alerts-snippets.md` and are intended to be imported or adapted into environment-specific rulesets.

## Redis Tail-Loss and Coordination Health

Example alert for Coordination Redis tail-loss SLO breaches:

```yaml
- alert: RedisCoordinationTailLossSLOBreached
  expr: redis_coordination_tail_loss_ms > 2000
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

This assumes that `redis_coordination_tail_loss_ms` is a per-`tenantId`/`regionId` gauge or recording rule derived from the raw tail-loss metrics described in `system-architecture-redis-operations.md`.

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
```

This assumes a helper metric such as `tick_effects_pending_oldest_scheduled_timestamp_seconds` that tracks the oldest `SCHEDULED` entry per region.

## Backup Pipeline Health

Example alerts for missed backups and verification runs:

```yaml
- alert: BackupPipelineNoRecentBackup
  expr: time() - backup_last_success_timestamp_seconds > 90 * 60
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
  expr: time() - backup_verify_last_success_timestamp_seconds > 24 * 60 * 60
  for: 30m
  labels:
    service: postgres-backup
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-backup-recovery.md#backup-verification-restoration-testing
  annotations:
    summary: Backup verification has not succeeded recently
    description: No successful backup verification run has been recorded in the last 24 hours. Investigate the verify-backups CronJob and storage configuration.

- alert: BackupTickPauseTooLongScoped
  expr: max by (scope) (backup_tick_pause_duration_seconds) > 30
  for: 5m
  labels:
    service: postgres-backup
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-backup-recovery.md#backup-verification-restoration-testing
  annotations:
    summary: Tick pause window too long during scoped backup
    description: One or more backup scopes have exceeded the pause-duration budget. Investigate pause/resume controls and scope-specific backlog growth.

- alert: BackupTicksPausedTooLong
  expr: backup_ticks_paused == 1
  for: 2m
  labels:
    service: postgres-backup
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-backup-recovery.md#backup-verification-restoration-testing
  annotations:
    summary: Backup scope remains paused unexpectedly
    description: A backup scope has remained in paused state beyond the expected window. Check pause/resume API calls and backup job completion state.
```

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
          sum by (tenantId, regionId, le) (
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
    description: Gateway command end-to-end p99 latency has exceeded 250ms for core commands. Align the `command` label values and the core command regex with the bounded command set in the Logging & Monitoring contract.

- alert: CommandLatencyP99HighTcpProxy
  expr: histogram_quantile(
          0.99,
          sum by (tenantId, regionId, le) (
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
    description: TCP Proxy command end-to-end p99 latency has exceeded 250ms for core commands. Align the `command` label values and the core command regex with the bounded command set in the Logging & Monitoring contract.

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
    sum by (tenantId, path) (increase(entrypath_connection_attempts_total{service="spring-cloud-gateway", outcome="success"}[1d]))
      /
    sum by (tenantId, path) (increase(entrypath_connection_attempts_total{service="spring-cloud-gateway"}[1d]))
  ) < 0.999
  for: 30m
  labels:
    service: spring-cloud-gateway
    component: entrypath
    severity: P0
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#telnet-and-websocket-path-availability-below-slo
  annotations:
    summary: Gateway entry-path availability below SLO
    description: One or more tenants have sustained connection failures on a gateway-owned entry path. Inspect entrypath_connection_attempts_total and follow the player experience runbook.

- alert: EntryPathAvailabilityLowTcpProxy
  expr: (
    sum by (tenantId, path) (increase(entrypath_connection_attempts_total{service="tcp-proxy-service", outcome="success"}[1d]))
      /
    sum by (tenantId, path) (increase(entrypath_connection_attempts_total{service="tcp-proxy-service"}[1d]))
  ) < 0.999
  for: 30m
  labels:
    service: tcp-proxy-service
    component: entrypath
    severity: P0
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#telnet-and-websocket-path-availability-below-slo
  annotations:
    summary: TCP Proxy entry-path availability below SLO
    description: One or more tenants have sustained connection failures on TCP Proxy entry paths. Inspect entrypath_connection_attempts_total and follow the player experience runbook.
```

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
