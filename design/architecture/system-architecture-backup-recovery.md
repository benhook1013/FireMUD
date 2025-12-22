# FireMUD System Architecture: Backup & Disaster Recovery

This document defines the backup schedule and disaster recovery procedures for FireMUD. Backups are taken only for **production**. Development and staging environments rely on ad hoc snapshots as needed.

---

## PostgreSQL Logical Backups

- A `firemud-pg-dump` CronJob (defined in `k8s/postgres/pg-dump-cronjob.yaml`) runs **every 15 minutes** and stores compressed SQL dumps.
- The production Terraform modules automatically deploy this CronJob. See [`k8s/terraform-production`](../../k8s/terraform-production).
- Retention policy:
  - **24 hours** of 15‑minute dumps
  - **10 days** of daily dumps
  - **3 weekly** dumps
  - **3 monthly** dumps
- The CronJob writes to a persistent volume claim `firemud-pg-dumps` and runs
  a script (`pg-dump.sh`) that enforces the retention policy. Dumps are stored under `15min`, `daily`, `weekly`, and `monthly` directories. The environment
  variables `PG_DUMP_BUCKET` **and** `PG_DUMP_ENDPOINT` must both be set;
  otherwise uploads are skipped. When defined, the script also uploads each
  dump to the specified S3/MinIO bucket. The same script is available for local use as `dev-tools/backups/pg-dump-rotate.sh`.
- Velero schedules defined in `k8s/velero/schedule.yaml` back up only Kubernetes manifests (`snapshotVolumes: false`). See [k8s/velero/README.md](../../k8s/velero/README.md) for installation details.
- Copy `k8s/velero/values.example.yaml` to `values.yaml` and configure your object storage bucket. Example:

```yaml
configuration:
  provider: aws
  defaultVolumesToFsBackup: false
  backupStorageLocation:
    bucket: firemud-backups
```

Always leave `defaultVolumesToFsBackup` set to `false` so Velero backs up only Kubernetes manifests and not persistent volume contents.

### Coordinated Tick Pausing

PostgreSQL dumps must capture a consistent view of gameplay state. Before a `pg_dump` begins, the Game Session Service exposes `PauseTicks` and `ResumeTicks` gRPC commands. The backup workflow:

1. Calls `PauseTicks` with a reason string. This sets a `pause_requested` flag so the tick scheduler stops launching new ticks while allowing any in‑flight ticks to finish normally.
2. Polls `GetTickStatus` until the service reports `PAUSED`, which indicates all in‑flight ticks have completed. Command queues continue accepting actions during this pause, but they execute only after ticks resume.
3. Starts `pg_dump` immediately. Ticks may resume as soon as the dump command starts because PostgreSQL snapshots the data at launch time.
4. Invokes `ResumeTicks` so queued commands continue processing.

For convenience, `dev-tools/backups/firemud-backup.sh` automates these steps by pausing ticks, waiting until the service is paused, running `pg_dump`, and then calling `ResumeTicks`.

Velero continues backing up Kubernetes manifests only and does **not** pause any services. Tick pausing is required only at the start of `pg_dump`, not for its entire runtime.

For local clusters without cloud storage, deploy the `k8s/velero/minio.yaml` manifest and configure Velero with a local backup location:

```yaml
configuration:
  provider: aws
  defaultVolumesToFsBackup: false
  backupStorageLocation:
    name: local
    provider: aws
    bucket: firemud-backups
    config:
      region: minio
      s3Url: http://minio.minio.svc.cluster.local:9000
      insecureSkipTLSVerify: true
credentials:
  useSecret: true
  existingSecret: velero-minio-creds
```

Run `dev-tools/backups/setup-local-backup.sh` to deploy MinIO, create the `firemud-backups` bucket, and install Velero automatically. Keep `defaultVolumesToFsBackup` disabled to avoid saving PVC data. Refer to [Developer Setup](../../DEVELOPER_SETUP.md#backing-up-the-local-database) for local database backup tips.

- If the database service fails completely:
  1. Restore the most recent `pg_dump` file from the `firemud-pg-dumps` volume or object store.
  2. Restart services to resume operation.
  3. Redis repopulates transient state from PostgreSQL on access.

## Redis Persistence

- Redis stores only **transient gameplay state**.
- **AOF (Append‑Only File)** is enabled for crash recovery while the cluster is running.
- Redis is **not restored** from backup during a cold start; it is repopulated from PostgreSQL after recovery.
  In development a `redis-data` volume can persist the AOF between container restarts. Restore an AOF file with `dev-tools/restores/restore-redis-aof.sh`.

### Redis AOF Reset on Deployment

Redis is treated as a **transient coordination layer** in terms of data authority (PostgreSQL owns canonical game data), but in staging and production it is also a **long-lived availability dependency**. The lifecycle of Redis and its AOF differs by environment:

- **Development and ephemeral test environments**
  - A Kubernetes Job (`k8s/helm/firemud/templates/redis-aof-reset-job.yaml`) may be enabled to delete the Redis Append‑Only File (AOF) on each Helm install or upgrade.
  - This guarantees a clean slate between runs so stale gameplay state, tick locks, or timers do not leak across test cycles.
  - This behavior is appropriate for local/dev stacks and short‑lived preview environments where all games are disposable and no replay or uptime guarantees are made.

- **Staging and production-equivalent environments**
  - Redis AOF files and volumes are **not wiped as part of normal Helm upgrades**. Application deployments roll out while Redis keeps its in‑memory state and AOF, so active sessions, tick queues, and timers survive app releases.
  - Resetting Redis (by deleting its AOF and/or volumes) is treated as an explicit **operational maintenance action** equivalent to a “world restart”. Operators must expect:
    - All active sessions to terminate.
    - All volatile tick state, timers, and queues to be discarded.
    - Games to restart from authoritative PostgreSQL state on next login.
  - Any workflow that resets Redis in staging/production must be documented as a runbook with clear player‑impact notes; it is not part of the default CI/CD pipeline.
  - The **scope** of any reset (single region, single tenant, or entire cluster) must follow the guidelines in the [Coordination Reset Model](./system-architecture-redis.md#coordination-reset-model) so that player impact and recovery behavior are predictable.

This behavior is distinct from **failover**:

- **Failover (node crash or leader change):** Redis pods restart or leadership moves, but AOF files and replication state are preserved. Tick locks and `tick:{tenantId}:{regionId}:pending` entries survive so the Game Session Service can safely **replay or complete** in-flight ticks using idempotent domain logic.
- **Deployment (Helm upgrade) in staging/production:** Application pods roll forward while Redis keeps its AOF and in‑memory state. In‑flight ticks and sessions remain active across deployment.

## Kubernetes Production

- **Velero** backs up Deployments, StatefulSets, ConfigMaps, and Secrets (but not volume snapshots).
  - Restoration process:
    1. Restore the latest `pg_dump` file onto the PostgreSQL volume.
    2. Use Velero to restore Kubernetes manifests.
    3. Restart the affected pods; Redis starts empty and fills itself from PostgreSQL.
    4. Operators can run `dev-tools/restores/restore-cluster.sh <backup-name>` to automate these steps in production.
       Set `FIREMUD_K8S_NAMESPACE` if restoring to a different namespace.

## Local Development

- Backups are restored using `dev-tools/restores/restore-db.sh` with a snapshot file.
- `dev-tools/restores/restore-latest-db.sh` can fetch the newest dump from the object
  store and restore it automatically when `PG_DUMP_BUCKET` and
  `PG_DUMP_ENDPOINT` are configured.
- Create ad hoc snapshots with `dev-tools/backups/backup-db.sh` before restoring.
- Services are restarted with **Docker Compose**.
- Redis starts empty and repopulates when services access the database.
- The compose stack includes a `pg-dump-cron` service running
  `dev-tools/backups/pg-dump-rotate.sh` every 15 minutes to mirror the production
  backup schedule.

---

## Backup Verification & Restoration Testing

- The `k8s/velero/verify-backups-cronjob.yaml` CronJob runs nightly at **04:00**
  and executes `dev-tools/backups/verify-backups.sh` to ensure recent snapshots are present in
  the object store. The script also verifies that the latest PostgreSQL dump
  exists in `PG_DUMP_BUCKET`, failing the job if no dumps are found. This
  CronJob is installed automatically by the production Terraform modules. See [`k8s/terraform-production`](../../k8s/terraform-production) for the deployment configuration.
- Operators should periodically test recovery by restoring a snapshot into a
  throwaway namespace with `dev-tools/restores/restore-cluster.sh <backup-name>
  <namespace>` (or by setting `FIREMUD_K8S_NAMESPACE`) and verifying
  services start successfully. A manual workflow
  `.github/workflows/manual-backup-restore.yml` can run these checks on
  demand from the GitHub Actions UI. See [Operational Runbooks](./system-architecture-runbooks.md#recovery) for step-by-step instructions.

---

## Restore Workflow Summary

| Environment | Steps |
| --- | --- |
| **Kubernetes** | Restore PostgreSQL from `pg_dump` → restore other resources with Velero → restart pods → allow Redis to repopulate |
| **Docker Compose** | `dev-tools/restores/restore-db.sh` snapshot → restart containers → Redis repopulates automatically |

Redis always uses AOF for crash recovery during runtime but is **never** restored from backup images. Gameplay resumes after services restart and Redis repopulates from PostgreSQL.

For **diagnostic purposes**, operators may take ad hoc copies of Coordination Redis AOF files or RDB exports and load them into **isolated, throwaway Redis instances** to inspect keys and coordination history during incident analysis. These diagnostic snapshots are strictly read-only tools:

- They must **not** be restored into live Coordination Redis clusters or used to overwrite existing AOF/volumes.
- They are never treated as rollback images for gameplay; recovery for player-visible environments always follows the pattern above (restore PostgreSQL, let Coordination Redis repopulate, and, when needed, apply the [Coordination Reset Model](./system-architecture-redis.md#coordination-reset-model)). Severe logical bugs that corrupt coordination state are remediated via scoped coordination resets, not by rolling Redis back to older snapshots.

## Related Documentation

- [CI/CD Pipeline](./system-architecture-cicd.md)
- [Database Migrations](./system-architecture-database-migrations.md)
