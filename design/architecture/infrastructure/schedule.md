# Scheduled Jobs

This document lists automated jobs that run on a schedule. Each entry links to the main design reference and the configuration that defines the schedule. Jobs are grouped by the environment where they run.

## GitHub CI

| Scheduled Item | Frequency (UTC) | Documentation | Configuration |
| --- | --- | --- | --- |
| CI — Build and Security | Daily at 03:00 | [CI/CD Pipeline](../system-architecture-cicd.md) | [.github/workflows/ci.yml](../../../.github/workflows/ci.yml) |
| CodeQL Analysis | Weekly on Sundays at 00:00 | [CI/CD Pipeline](../system-architecture-cicd.md) | [.github/workflows/codeql.yml](../../../.github/workflows/codeql.yml) |
| Weekly Security Scan | Weekly on Sundays at 03:00 | [CI/CD Pipeline](../system-architecture-cicd.md) | [.github/workflows/weekly-security-scan.yml](../../../.github/workflows/weekly-security-scan.yml) |
| Dependabot dependency updates | Weekly on Saturdays at 16:00 (Sunday 04:00 Pacific/Auckland) | [CI/CD Pipeline](../system-architecture-cicd.md) | [.github/dependabot.yml](../../../.github/dependabot.yml) |

## Kubernetes Cluster (Production)

The following CronJobs run in the **production** Kubernetes cluster. Development and staging clusters rely on ad hoc backup and restore scripts instead of scheduled jobs unless explicitly configured otherwise.

| Scheduled Item | Frequency (UTC) | Documentation | Configuration |
| --- | --- | --- | --- |
| PostgreSQL pg_dump | Every 15 minutes | [Backup & Disaster Recovery](../system-architecture-backup-recovery.md) | [k8s/postgres/pg-dump-cronjob.yaml](../../../k8s/postgres/pg-dump-cronjob.yaml) |
| Velero backup verification | Daily at 04:00 | [Backup & Disaster Recovery](../system-architecture-backup-recovery.md) | [k8s/velero/verify-backups-cronjob.yaml](../../../k8s/velero/verify-backups-cronjob.yaml) |
| Velero manifest backups | Every 15 minutes, weekly on Sundays at 02:00, monthly on the 1st at 03:00 | [Backup & Disaster Recovery](../system-architecture-backup-recovery.md) | [k8s/velero/schedule.yaml](../../../k8s/velero/schedule.yaml) |

## Kubernetes Cluster (Staging)

By default, staging is treated as **disposable** and does not run the production backup CronJobs. Operators may install staging-specific schedules later, but the default stance is:

- No scheduled `pg_dump` or Velero schedules in staging.
- Staging rebuilds from manifests and fresh data as needed.
- When staging is temporarily restored from production backups (for example for disaster recovery rehearsals), operators must run post-restore secret hardening before opening the environment to playtests (see `../system-architecture-backup-recovery.md#post-restore-secret-hardening`).
- PRs that modify `k8s/` run [`.github/workflows/validate-kustomize-overlays.yml`](../../../.github/workflows/validate-kustomize-overlays.yml), which blocks staging backup schedule resources unless `k8s/overlays/stage/STAGING_BACKUPS_ENABLED` is present.
- If staging schedules are intentionally enabled, the PR should include the marker file, an explicit operator rationale, and a rollback plan to return staging to disposable defaults.
