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
```

Routine backup alerts use these current artifact and recovery recordings:

- `backup_pipeline_recent_backup_slo_breached`
- `backup_pipeline_recent_verification_slo_breached`
- `backup_pipeline_recent_restore_drill_slo_breached`
- `backup_artifact_lineage_invalid`
- `backup_artifact_restore_unreadable`
- `recovery_participant_convergence_state{environment,participant,state}`

Environment-specific rulesets may tune thresholds, severities, and label values, but should preserve the `owner` and `runbook` annotations so alerts always point back to the relevant documentation. Tick-pause metrics belong to maintenance/reset dashboards and must not be used as routine backup health or traffic-reopen proof.
