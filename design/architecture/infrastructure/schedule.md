# Scheduled Jobs

This document lists automated jobs that run on a schedule. Each entry links to the main design reference and the configuration that defines the schedule. Jobs are grouped by the environment where they run.

## GitHub CI

| Scheduled Item | Frequency (UTC) | Documentation | Configuration |
| --- | --- | --- | --- |
| CI — Build and Security | Daily at 03:00 | [CI/CD Pipeline](../system-architecture-cicd.md) | [.github/workflows/ci.yml](../../../.github/workflows/ci.yml) |
| CodeQL Analysis | Weekly on Sundays at 00:00 | [CI/CD Pipeline](../system-architecture-cicd.md) | [.github/workflows/codeql.yml](../../../.github/workflows/codeql.yml) |
| OSSF Scorecard | Weekly on Saturdays at 01:30 | [CI/CD Pipeline](../system-architecture-cicd.md) | [.github/workflows/scorecards.yml](../../../.github/workflows/scorecards.yml) |
| Weekly Security Scan | Weekly on Sundays at 03:00 | [CI/CD Pipeline](../system-architecture-cicd.md) | [.github/workflows/weekly-security-scan.yml](../../../.github/workflows/weekly-security-scan.yml) |
| Weekly FireMUD Base Image Refresh | Weekly on Sundays at 02:00 | [CI/CD Pipeline](../system-architecture-cicd.md) | [.github/workflows/publish-base-image.yml](../../../.github/workflows/publish-base-image.yml) |
| Weekly ORT Advisory Scan | Weekly on Sundays at 06:00 | [CI/CD Pipeline](../system-architecture-cicd.md) | [.github/workflows/ort-advisory.yml](../../../.github/workflows/ort-advisory.yml) |
| Dependabot dependency updates | Weekly on Saturdays at 16:00 (Sunday 04:00 Pacific/Auckland) | [CI/CD Pipeline](../system-architecture-cicd.md) | [.github/dependabot.yml](../../../.github/dependabot.yml) |

Repository-app automation that is not driven by a GitHub Actions schedule:

- CodeRabbit automatically reviews eligible non-draft pull requests targeting `develop` and `main` when they are opened; later pushes do not trigger another automatic review. After all current and outdated findings are resolved, request a meaningful full review with `@coderabbitai full review` at the next checkpoint rather than spending the hourly allowance on an incremental request.
- Renovate evaluates dependency updates against `develop` as the hosted Mend app processes repository events and background jobs; it is intentionally not restricted by an in-repo schedule.

GitHub Actions schedule nuance:

- Scheduled workflows execute from the default branch workflow definition. In FireMUD, the weekly CodeQL and OSSF Scorecard schedules therefore run from `develop`, while `main` remains covered by its push-triggered analyses.

Publication guardrail:

- Scheduled and manually dispatched publication workflows may validate on other branches, but production-looking publication is allowed only from `develop` and `main`. This applies to GitHub Pages publishing and the shared base-image publication path.

## Kubernetes Cluster (Production)

The following CronJobs run in the **production** Kubernetes cluster. Production is the only environment class with mandatory scheduled backup jobs by default.

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

## Hobby / Self-Hosted Environments

`hobby-self-hosted` environments are player-facing and must run backups with a minimum baseline, even when schedules are operator-managed:

- Logical PostgreSQL backups at least every 24 hours.
- Retention of at least the latest 7 daily backups.
- At least one restore verification drill every 30 days.
- Documented post-restore secret-hardening steps before reopening player traffic.

Operators may choose tighter cadence or longer retention, but not lower than this baseline. Tooling can be local scripts, CronJobs, or managed services as long as the minimum baseline is met and recorded in deployment notes.

Required evidence record:

- `design/operations/deployments/hobby-self-hosted/backup-compliance.yaml` must be updated after backup cadence changes, successful restore drills, or retention-policy changes.
- Player-traffic reopen after a hobby restore requires this record to show baseline compliance (`>=1` backup/24h, `>=7` retained daily restore points, `>=1` restore drill/30d).
- First-live and post-restore reopen events must also record `design/operations/deployments/hobby-self-hosted/traffic-open/<deployment-ref>.json` and pass the hobby traffic-open preflight gate before player traffic opens.
- Post-restore reopen events must also complete the canonical recovery record at `design/operations/deployments/hobby-self-hosted/recovery/<recovery-ref>.json` before quarantine is lifted and player traffic reopens.
