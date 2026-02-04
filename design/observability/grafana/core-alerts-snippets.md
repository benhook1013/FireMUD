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
    owner: platform
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
    service: game-session
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#stalled-tick-region
  annotations:
    summary: Tick execution time approaching unsafe fraction of lock TTL
    description: Tick p99 execution time is nearing or exceeding the configured lock TTL for one or more regions. Investigate tick health and region density before adjusting tick cadence.
```

Exact metric names may differ; align them with the histogram and ratio metrics defined in the tick and Redis operations docs.

## Tick Effect Ledger Backlog

Example alert for stuck `SCHEDULED` rows in the tick effect ledger:

```yaml
- alert: TickEffectLedgerBacklog
  expr: tick_effects_pending_total > 0 and on() (time() - tick_effects_pending_oldest_scheduled_timestamp_seconds) > 300
  for: 10m
  labels:
    service: game-session
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#stuck-tick-effect-ledger-entries
  annotations:
    summary: Tick effect ledger has pending rows beyond grace window
    description: One or more regions have SCHEDULED tick effects that have not converged to APPLIED or ABANDONED within the expected grace window.
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
    owner: platform
    runbook: design/architecture/system-architecture-backup-recovery.md#backup-verification--restoration-testing
  annotations:
    summary: PostgreSQL backups have not succeeded recently
    description: No successful pg_dump backup has been recorded in the last 90 minutes. Investigate backup Jobs and storage endpoints.

- alert: BackupPipelineNoRecentVerification
  expr: time() - backup_verify_last_success_timestamp_seconds > 24 * 60 * 60
  for: 30m
  labels:
    service: postgres-backup
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-backup-recovery.md#backup-verification--restoration-testing
  annotations:
    summary: Backup verification has not succeeded recently
    description: No successful backup verification run has been recorded in the last 24 hours. Investigate the verify-backups CronJob and storage configuration.
```

Environment-specific rulesets may tune thresholds, severities, and label values, but should preserve the `owner` and `runbook` annotations so alerts always point back to the relevant documentation.

