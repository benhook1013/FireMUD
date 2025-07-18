# 💾 FireMUD System Architecture: Backup & Disaster Recovery

This document defines the backup schedule and disaster recovery procedures for FireMUD. Backups are taken only for **production**. Development and staging environments rely on ad hoc snapshots as needed.

---

## 📦 PostgreSQL Logical Backups

- A `firemud-pg-dump` CronJob (defined in `k8s/postgres/pg-dump-cronjob.yaml`) runs **every 15 minutes** and stores compressed SQL dumps.
- Retention policy:
  - **24 hours** of 15‑minute dumps
  - **3 weekly** dumps
  - **3 monthly** dumps
- The CronJob writes to a persistent volume claim `firemud-pg-dumps` and runs
  a script (`pg-dump.sh`) that enforces the retention policy. When the
  environment variable `PG_DUMP_BUCKET` is set, the script also uploads each
  dump to the specified S3/MinIO bucket.
- Velero schedules defined in `k8s/velero/schedule.yaml` back up only Kubernetes manifests. See [k8s/velero/README.md](../../k8s/velero/README.md) for installation details.
- Copy `k8s/velero/values.example.yaml` to `values.yaml` and configure your object storage bucket. Example:

```yaml
configuration:
  provider: aws
  defaultVolumesToFsBackup: false
  backupStorageLocation:
    bucket: firemud-backups
```

Always leave `defaultVolumesToFsBackup` set to `false` so Velero backs up only Kubernetes manifests and not persistent volume contents.

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

Run `dev-tools/setup-local-backup.sh` to deploy MinIO, create the `firemud-backups` bucket, and install Velero automatically. Keep `defaultVolumesToFsBackup` disabled to avoid saving PVC data. Refer to [Developer Setup](../../DEVELOPER_SETUP.md#backing-up-the-local-database) for local database backup tips.

- If the database service fails completely:
  1. Restore the most recent `pg_dump` file from the `firemud-pg-dumps` volume or object store.
  2. Restart services to resume operation.
  3. Redis repopulates transient state from PostgreSQL on access.

## 🗃️ Redis Persistence

- Redis stores only **transient gameplay state**.
- **AOF (Append‑Only File)** is enabled for crash recovery while the cluster is running.
- Redis is **not restored** from backup during a cold start; it is repopulated from PostgreSQL after recovery.
  In development a `redis-data` volume can persist the AOF between container restarts. Restore an AOF file with `dev-tools/restore-redis-aof.sh`.

### Redis AOF Reset on Deployment

FireMUD wipes Redis state on every deployment. Redis is treated as a transient coordination layer and must always start fresh to ensure consistency with authoritative PostgreSQL data.

During each Helm install or upgrade, a Kubernetes Job automatically deletes the Redis Append‑Only File (AOF) before the application starts:

- AOF wipe is triggered via a Helm hook
- Ensures no stale gameplay state or tick locks remain
- Does not affect Redis crash recovery during runtime

Because Redis is not a source of truth, this strategy guarantees a clean, deterministic runtime state on every deployment.

## ☁️ Kubernetes Production

- **Velero** backs up Deployments, StatefulSets, ConfigMaps, and Secrets (but not volume snapshots).
  - Restoration process:
    1. Restore the latest `pg_dump` file onto the PostgreSQL volume.
    2. Use Velero to restore Kubernetes manifests.
    3. Restart the affected pods; Redis starts empty and fills itself from PostgreSQL.
    4. Operators can run `dev-tools/restore-cluster.sh <backup-name>` to automate these steps in production.
       Set `FIREMUD_K8S_NAMESPACE` if restoring to a different namespace.

## 🐳 Local Development

- Backups are restored using `dev-tools/restore-db.sh` with a snapshot file.
- `dev-tools/restore-latest-db.sh` can fetch the newest dump from the object
  store and restore it automatically when `PG_DUMP_BUCKET` and
  `PG_DUMP_ENDPOINT` are configured.
- Create ad hoc snapshots with `dev-tools/backup-db.sh` before restoring.
- Services are restarted with **Docker Compose**.
- Redis starts empty and repopulates when services access the database.
- The compose stack includes a `pg-dump-cron` service running
  `dev-tools/pg-dump-rotate.sh` every 15 minutes to mirror the production
  backup schedule.

---

## ✅ Backup Verification & Restoration Testing

- The `k8s/velero/verify-backups-cronjob.yaml` CronJob runs
  `dev-tools/verify-backups.sh` daily to ensure recent snapshots are present in
  the object store. The script now also verifies that the latest PostgreSQL dump
  exists in `PG_DUMP_BUCKET`, failing the job if no dumps are found. This
  CronJob is installed automatically by the production Terraform modules. See [`k8s/terraform-production`](../../k8s/terraform-production) for the deployment configuration.
- Operators should periodically test recovery by restoring a snapshot into a
  throwaway namespace with `dev-tools/restore-cluster.sh <backup-name>
  <namespace>` (or by setting `FIREMUD_K8S_NAMESPACE`) and verifying
  services start successfully. A manual workflow
  `.github/workflows/manual-backup-restore.yml` can run these checks on
  demand from the GitHub Actions UI. See [Operational Runbooks](./system-architecture-runbooks.md#recovery) for step-by-step instructions.

---

## 🔄 Restore Workflow Summary

| Environment      | Steps |
|------------------|-------------------------------------------------------------|
| **Kubernetes**   | Restore PostgreSQL from `pg_dump` → restore other resources with Velero → restart pods → allow Redis to repopulate |
| **Docker Compose** | `dev-tools/restore-db.sh` snapshot → restart containers → Redis repopulates automatically |

Redis always uses AOF for crash recovery during runtime but is **never** restored from backup images. Gameplay resumes after services restart and Redis repopulates from PostgreSQL.

## 📚 Related Documentation

- [CI/CD Pipeline](./system-architecture-cicd.md)
- [Database Migrations](./system-architecture-database-migrations.md)
