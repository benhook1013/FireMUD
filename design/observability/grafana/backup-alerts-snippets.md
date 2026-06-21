# Backup Alertmanager Snippets

This file contains reference PromQL expressions and Alertmanager rule snippets for backup pipeline health. These complement the TCP Proxy-specific rules in `tcp-proxy-alerts-snippets.md` and are intended to be imported or adapted into environment-specific rulesets.

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

- `backup_tick_pause_wait_budget_seconds{scope_type,scope}`
- `backup_tick_pause_duration_budget_seconds{scope_type,scope}`

and that Prometheus exposes derived fallback recordings:

- `backup_pipeline_recent_backup_slo_breached`
- `backup_pipeline_recent_verification_slo_breached`
- `backup_pipeline_recent_restore_drill_slo_breached`
- `backup_tick_pause_wait_budget_breached{scope_type,scope}`
- `backup_tick_pause_duration_budget_breached{scope_type,scope}`
- `backup_ticks_paused_budget_breached{scope_type,scope}`

Environment-specific rulesets may tune thresholds, severities, and label values, but should preserve the `owner` and `runbook` annotations so alerts always point back to the relevant documentation.
