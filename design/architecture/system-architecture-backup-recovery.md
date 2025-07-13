# 💾 FireMUD System Architecture: Backup & Disaster Recovery

This document defines the backup schedule and disaster recovery procedures for FireMUD. Backups are taken only for **production**. Development and staging environments rely on ad hoc snapshots as needed.

---

## 📦 PostgreSQL Snapshots

- Snapshots are taken **every 15 minutes**.
- Retention policy:
  - **24 hours** of 15‑minute snapshots
  - **3 weekly** snapshots
  - **3 monthly** snapshots
- Example Velero schedules are provided in `k8s/velero/schedule.yaml`.
- If the database service fails completely:
  1. Restore the latest snapshot.
  2. Restart services to resume operation.
  3. Redis repopulates transient state from PostgreSQL on access.

## 🗃️ Redis Persistence

- Redis stores only **transient gameplay state**.
- **AOF (Append‑Only File)** is enabled for crash recovery while the cluster is running.
- Redis is **not restored** from backup during a cold start; it is repopulated from PostgreSQL after recovery.

## ☁️ Kubernetes Production

- **Velero** backs up StatefulSets, PersistentVolumeClaims, ConfigMaps, and Secrets.
- Restoration process:
  1. Use Velero to rehydrate the PostgreSQL volume from the latest snapshot.
  2. Restore other resources (StatefulSets, ConfigMaps, Secrets).
  3. Restart the affected pods; Redis starts empty and fills itself from PostgreSQL.

## 🐳 Local Development

- Backups are restored using `dev-tools/restore-db.sh` with a snapshot file.
- Services are restarted with **Docker Compose**.
- Redis starts empty and repopulates when services access the database.

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
