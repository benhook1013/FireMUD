# 🛠️ FireMUD Operational Runbooks

This document summarizes routine procedures for deploying, scaling, and recovering FireMUD environments. Each step references existing architecture docs so operators can quickly locate details.

---

## 🚀 Deployment

1. **CI Pipeline** builds Docker images and pushes them to GHCR. Kubernetes
   rollouts are triggered manually via the
   [`manual-helm-deploy.yml`](../../.github/workflows/manual-helm-deploy.yml)
   workflow until pipeline automation is complete. (TODO: Not yet implemented)
   See [CI/CD Pipeline](./system-architecture-cicd.md) for workflow details.
2. **Helm Charts** deploy each microservice. Install the umbrella chart with:

   ```bash
   helm install firemud ./k8s/helm/firemud -f k8s/helm/values-dev.yaml
   ```

3. Verify pods are running with `kubectl get pods -n firemud`.
4. Monitor rollout progress in the CI job summary. Planned Grafana dashboards
   will provide additional visibility. (TODO: Not yet implemented)

For local development, use `./gradlew devUp` to start Docker Compose and
`./gradlew devDown` when finished.

## 📈 Scaling

1. Adjust replica counts in the Helm chart or use `kubectl scale`:

   ```bash
   kubectl scale deploy account-service --replicas=3 -n firemud
   ```

2. Update Horizontal Pod Autoscaler settings if enabled (TODO: Not yet implemented).
3. Review Prometheus metrics to ensure CPU and memory usage remain healthy (TODO: Not yet implemented).
4. For database or Redis clusters, scale StatefulSets according to their
   respective runbooks. (TODO: Not yet implemented)

## 🔄 Recovery

1. **Database Failure**
   - Run `dev-tools/restore-cluster.sh <backup-name>` to restore from the latest `pg_dump` and restart services.
     Set `FIREMUD_K8S_NAMESPACE` to restore into a custom namespace.
   - Alternatively, restore manually:

   ```bash
   kubectl cp <namespace>/<pg-pod>:/backups/latest.sql.gz ./latest.sql.gz
   gunzip -c latest.sql.gz | kubectl exec -i <postgres-pod> -- psql -U "$FIREMUD_POSTGRES_USER" "$FIREMUD_POSTGRES_DB"
   kubectl rollout restart deployment -n firemud
   kubectl rollout restart statefulset -n firemud
   ```

   - If dumps are stored in an object bucket set via `PG_DUMP_BUCKET`, download
      the desired file with `aws s3 cp s3://$PG_DUMP_BUCKET/<path> ./dump.sql.gz`
      (add `--endpoint-url` for MinIO) before running the above restore steps.

   - For local development, run `dev-tools/restore-db.sh <backup-file>` and then
     restart containers with `docker compose restart`.
   - Scheduled backups are created automatically by the Terraform modules using
     `k8s/velero/schedule.yaml`.
   - Daily backup checks are handled by the `verify-backups` CronJob deployed by Terraform (`k8s/velero/verify-backups-cronjob.yaml`).
   - A manual workflow `manual-backup-restore.yml` can verify backups and
     perform an optional restore test in a temporary namespace. Trigger it
     from the GitHub Actions UI when needed.
   - **Maintain dump volume**: the [`firemud-pg-dump` CronJob](k8s/postgres/pg-dump-cronjob.yaml) rotates files automatically,
    but long-lived persistent volumes can still fill up. The Docker Compose
    stack includes a `pg-dump-cron` service that runs the same rotation script
    every 15 minutes. Periodically check the `firemud-pg-dumps` PVC and prune
    old `*.sql.gz` files or run `dev-tools/pg-dump-rotate.sh` manually if
    additional cleanup is required.
2. **Redis Failure**
   - Redis nodes automatically resync using AOF and replication. (TODO: Not yet implemented)
     Services reconnect on restart. See [Redis Architecture](./system-architecture-redis.md)
     for persistence and recovery details.
3. **Full Cluster Restore**
   - Recreate the cluster using Terraform modules in `k8s/terraform`. See
     [`k8s/terraform/README.md`](../../k8s/terraform/README.md) for usage.
   - Run `velero restore` to recreate Kubernetes manifests.

See [Backup & Disaster Recovery](./system-architecture-backup-recovery.md) for backup schedules and retention policies.

## 🩹 Hotfix Procedure

1. Identify the offending service via logs or alerts.
2. Commit the fix to `main` and trigger the CI pipeline.
3. Use `helm upgrade --install` with the new image tag to deploy only the affected service.
4. Monitor metrics and logs to ensure the issue is resolved.

---

These runbooks provide a starting point for operators. Update them as new tooling or workflows evolve.

## 📚 Related Documentation

- [Logging & Monitoring Overview](./system-architecture-logging-monitoring.md)
- [Backup & Disaster Recovery](./system-architecture-backup-recovery.md)
- [CI/CD Pipeline](./system-architecture-cicd.md)
