# ⏰ Scheduled CI/CD Jobs

This document lists automated jobs that run on a schedule. Each entry links to the main design reference and the configuration that defines the schedule.

| Scheduled Item | Frequency (UTC) | Documentation | Configuration |
|----------------|-----------------|---------------|---------------|
| CI — Build and Security | Daily at 03:00 | [CI/CD Pipeline](../system-architecture-cicd.md) | [.github/workflows/ci.yml](../../../.github/workflows/ci.yml) |
| CodeQL Analysis | Weekly on Sundays at 00:00 | [CI/CD Pipeline](../system-architecture-cicd.md) | [.github/workflows/codeql.yml](../../../.github/workflows/codeql.yml) |
| Weekly Security Scan | Weekly on Sundays at 03:00 | [CI/CD Pipeline](../system-architecture-cicd.md) | [.github/workflows/weekly-security-scan.yml](../../../.github/workflows/weekly-security-scan.yml) |
| PostgreSQL pg_dump | Every 15 minutes | [Backup & Disaster Recovery](../system-architecture-backup-recovery.md) | [k8s/postgres/pg-dump-cronjob.yaml](../../../k8s/postgres/pg-dump-cronjob.yaml) |
| Velero backup verification | Daily at 04:00 | [Backup & Disaster Recovery](../system-architecture-backup-recovery.md) | [k8s/velero/verify-backups-cronjob.yaml](../../../k8s/velero/verify-backups-cronjob.yaml) |
| Velero manifest backups | 15 min, weekly, monthly | [Backup & Disaster Recovery](../system-architecture-backup-recovery.md) | [k8s/velero/schedule.yaml](../../../k8s/velero/schedule.yaml) |
