# 💾 FireMUD System Architecture: Backup & Disaster Recovery

This document defines the backup schedule and disaster recovery procedures for FireMUD. Backups are taken only for **production**. Development and staging environments rely on ad hoc snapshots as needed.

---

## 📦 PostgreSQL Snapshots

- Snapshots are taken **every 15 minutes**.
- Retention policy:
  - **24 hours** of 15‑minute snapshots
  - **3 weekly** snapshots
  - **3 monthly** snapshots
- Velero backup schedules are installed automatically by the
  production Terraform modules using `k8s/velero/schedule.yaml`.
  Operators must configure Velero with access to an object storage bucket
    (AWS S3, GCS, or MinIO). Copy `k8s/velero/values.example.yaml` to `values.yaml`
    and adjust the provider and bucket. Example `values.yaml` snippet:

    ```yaml
    configuration:
      provider: aws
      backupStorageLocation:
        bucket: firemud-backups
        prefix: postgres
      ```

    For local clusters without cloud storage, deploy the `k8s/velero/minio.yaml`
    manifest and configure Velero with a local backup location:

    ```yaml
    configuration:
      provider: aws
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

      Create the `firemud-backups` bucket in MinIO prior to installing Velero. Example using the MinIO client:

      ```bash
      mc alias set local http://minio.minio.svc.cluster.local:9000 myaccesskey mysecretkey

      mc mb local/firemud-backups
      ```

      Then install Velero using the local values file:

      ```bash
      helm repo add vmware-tanzu https://vmware-tanzu.github.io/helm-charts
      helm install velero vmware-tanzu/velero \
        -n velero --create-namespace -f k8s/velero/values-minio.yaml
      ```

  - If the database service fails completely:
  1. Restore the latest snapshot.
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

- **Velero** backs up StatefulSets, PersistentVolumeClaims, ConfigMaps, and Secrets.
  - Restoration process:
    1. Use Velero to rehydrate the PostgreSQL volume from the latest snapshot.
    2. Restore other resources (StatefulSets, ConfigMaps, Secrets).
    3. Restart the affected pods; Redis starts empty and fills itself from PostgreSQL.
    4. Operators can run `dev-tools/restore-cluster.sh <backup-name>` to automate these steps in production.
       Set `FIREMUD_K8S_NAMESPACE` if restoring to a different namespace.

## 🐳 Local Development

- Backups are restored using `dev-tools/restore-db.sh` with a snapshot file.
- Create ad hoc snapshots with `dev-tools/backup-db.sh` before restoring.
- Services are restarted with **Docker Compose**.
- Redis starts empty and repopulates when services access the database.

---

## ✅ Backup Verification & Restoration Testing

- The `k8s/velero/verify-backups-cronjob.yaml` CronJob runs
  `dev-tools/verify-backups.sh` daily to ensure recent snapshots are present in
  the object store. This CronJob is installed automatically by the production
  Terraform modules.
- Operators should periodically test recovery by restoring a snapshot into a
  throwaway namespace with `dev-tools/restore-cluster.sh <backup-name>` and
  verifying services start successfully.

---

## 🔄 Restore Workflow Summary

| Environment      | Steps |
|------------------|-------------------------------------------------------------|
| **Kubernetes**   | Restore PostgreSQL via Velero → restore other resources → restart pods → allow Redis to repopulate |
| **Docker Compose** | `dev-tools/restore-db.sh` snapshot → restart containers → Redis repopulates automatically |

Redis always uses AOF for crash recovery during runtime but is **never** restored from backup images. Gameplay resumes after services restart and Redis repopulates from PostgreSQL.

## 📚 Related Documentation

- [CI/CD Pipeline](./system-architecture-cicd.md)
- [Database Migrations](./system-architecture-database-migrations.md)
