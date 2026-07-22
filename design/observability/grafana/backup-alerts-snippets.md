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
    runbook: design/architecture/system-architecture-backup-recovery.md#postgresql-logical-backups
  annotations:
    summary: PostgreSQL backups have not succeeded recently
    description: No successful pg_dump backup has been recorded in the last 90 minutes. Investigate backup Jobs and storage endpoints.

- alert: BackupLastSuccessMetricsAbsent
  expr: absent(backup_last_success_timestamp_seconds)
  for: 5m
  labels:
    service: postgres-backup
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md#backup-observability-and-alerts
  annotations:
    summary: Backup success-timestamp metrics are absent
    description: No backup_last_success_timestamp_seconds series are present. This is a global monitoring gap; keep backup-readiness decisions blocked until the source is restored.

- alert: BackupPipelineNoRecentVerification
  expr: backup_pipeline_recent_verification_slo_breached > 0
  for: 30m
  labels:
    service: postgres-backup
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-backup-recovery.md#restore-workflow-summary
  annotations:
    summary: Backup verification has not succeeded recently
    description: No successful backup verification run has been recorded in the last 24 hours. Investigate the verify-backups CronJob and storage configuration.

- alert: BackupVerificationLastSuccessMetricsAbsent
  expr: absent(backup_verify_last_success_timestamp_seconds)
  for: 5m
  labels:
    service: postgres-backup
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md#backup-observability-and-alerts
  annotations:
    summary: Backup verification success-timestamp metrics are absent
    description: No backup_verify_last_success_timestamp_seconds series are present. This is a global monitoring gap; keep backup-readiness decisions blocked until the source is restored.

- alert: BackupPipelineNoRecentRestoreDrill
  expr: backup_pipeline_recent_restore_drill_slo_breached > 0
  for: 30m
  labels:
    service: postgres-backup
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-backup-recovery.md#restore-workflow-summary
  annotations:
    summary: Restore drill proof is stale
    description: No successful restore drill has been recorded within the required restore-proof freshness window. Investigate drill cadence and recovery evidence before traffic reopen decisions.

- alert: BackupRestoreDrillLastSuccessMetricsAbsent
  expr: absent(backup_restore_drill_last_success_timestamp_seconds)
  for: 5m
  labels:
    service: postgres-backup
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md#backup-observability-and-alerts
  annotations:
    summary: Restore-drill success-timestamp metrics are absent
    description: No backup_restore_drill_last_success_timestamp_seconds series are present. This is a global monitoring gap; keep recovery-readiness decisions blocked until the source is restored.

- alert: BackupArtifactLineageInvalid
  expr: backup_artifact_lineage_invalid > 0
  for: 5m
  labels:
    service: postgres-backup
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-backup-recovery.md#restore-workflow-summary
  annotations:
    summary: Backup artifact lineage is invalid
    description: The latest backup artifact cannot prove the expected environment, database, schema, service, tool, or object-storage lineage. Keep recovery quarantined and inspect the authoritative artifact record.

- alert: BackupArtifactLineageMetricsAbsent
  expr: absent(backup_artifact_lineage_valid)
  for: 5m
  labels:
    service: postgres-backup
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md#backup-observability-and-alerts
  annotations:
    summary: Backup artifact lineage metrics are absent
    description: No backup_artifact_lineage_valid series are present. This is a global monitoring gap, not environment-specific readiness evidence.

- alert: BackupArtifactRestoreUnreadable
  expr: backup_artifact_restore_unreadable > 0
  for: 5m
  labels:
    service: postgres-backup
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-backup-recovery.md#restore-workflow-summary
  annotations:
    summary: Backup artifact failed restore-readability validation
    description: The latest backup artifact exists but could not be read through the restore validation path. Keep recovery quarantined until a readable artifact is proven.

- alert: BackupArtifactRestoreReadabilityMetricsAbsent
  expr: absent(backup_artifact_restore_readable)
  for: 5m
  labels:
    service: postgres-backup
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md#backup-observability-and-alerts
  annotations:
    summary: Backup artifact restore-readability metrics are absent
    description: No backup_artifact_restore_readable series are present. This is a global monitoring gap, not environment-specific readiness evidence.

- alert: RecoveryParticipantConvergenceBlocked
  expr: recovery_participant_convergence_state{state="blocked"} == 1
  for: 5m
  labels:
    service: postgres-backup
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-backup-recovery.md#restore-workflow-summary
  annotations:
    summary: Recovery participant convergence remains blocked
    description: A current recovery participant state has no safe disposition. The alert clears when the participant state converges; the cumulative convergence event counter is historical evidence only.

- alert: RecoveryParticipantConvergenceMetricsAbsent
  expr: absent(recovery_participant_convergence_state)
  for: 5m
  labels:
    service: postgres-backup
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md#backup-observability-and-alerts
  annotations:
    summary: Recovery participant convergence metrics are absent
    description: No recovery_participant_convergence_state series are present. This is an observability failure, not evidence that recovery participants are converged or blocked; keep recovery-readiness decisions blocked until a reliable emitter and current state are proven.

- alert: RecoveryReopenAttemptBlocked
  expr: increase(recovery_reopen_attempt_total{result="blocked",reason="incomplete_convergence"}[5m]) > 0
  for: 0m
  labels:
    service: postgres-backup
    severity: P0
    owner: infra
    runbook: design/architecture/system-architecture-backup-recovery.md#recovery-controller-continuation
  annotations:
    summary: Player-facing reopen was attempted without complete convergence
    description: A controller-authoritative recovery release was attempted before cold-start convergence completed. Keep traffic quarantined and investigate immediately.
```

Routine backup alerts use these current artifact and recovery recordings:

- `backup_pipeline_recent_backup_slo_breached`
- `backup_pipeline_recent_verification_slo_breached`
- `backup_pipeline_recent_restore_drill_slo_breached`
- `backup_artifact_lineage_invalid`
- `backup_artifact_restore_unreadable`
- `recovery_participant_convergence_blocked`
- `recovery_environment_convergence_blocked`

`recovery_participant_convergence_state{environment,participant,state}` is the source gauge for the two convergence recordings, not another recording rule. `RecoveryParticipantConvergenceMetricsAbsent` is a fail-safe monitoring-gap alert. It cannot provide `environment` or `participant` labels when the source family is absent and must not be treated as recovery state or readiness evidence. The current repository has no reliable recovery-participant metric emitter, so the recovery convergence recording and blocked-state alert remain unproved until that producer and its end-to-end proof exist.

Environment-specific rulesets may tune thresholds, severities, and label values, but should preserve the `owner` and `runbook` annotations so alerts always point back to the relevant documentation. Tick-pause metrics belong to maintenance/reset dashboards and must not be used as routine backup health or traffic-reopen proof.
