# 🛠️ FireMUD Operational Runbooks

This document summarizes routine procedures for deploying, scaling, and recovering FireMUD environments. Each step references existing architecture docs so operators can quickly locate details.

---

## 🚀 Deployment

1. **CI Pipeline** builds Docker images and pushes them to GHCR.
2. **Helm Charts** deploy each microservice. Install with:

   ```bash
   helm install firemud ./k8s/charts/firemud -f k8s/charts/firemud/values-prod.yaml
   ```

3. Verify pods are running with `kubectl get pods -n firemud`.
4. Monitor rollout progress in the CI job summary and Grafana dashboards.

For local development, use `./gradlew devUp` to start Docker Compose.

## 📈 Scaling

1. Adjust replica counts in the Helm chart or use `kubectl scale`:

   ```bash
   kubectl scale deploy account-service --replicas=3 -n firemud
   ```

2. Update Horizontal Pod Autoscaler settings if enabled.
3. Review Prometheus metrics to ensure CPU and memory usage remain healthy.
4. For database or Redis clusters, scale StatefulSets according to their respective runbooks.

## 🔄 Recovery

1. **Database Failure**
   - Restore the latest PostgreSQL snapshot using Velero:

     ```bash
     velero restore create --from-backup firemud-postgres-latest
     ```

   - Restart dependent services with `kubectl rollout restart`.
2. **Redis Failure**
   - Redis nodes automatically resync using AOF and replication. Services reconnect on restart.
3. **Full Cluster Restore**
   - Recreate the cluster using Terraform modules in `k8s/terraform`.
   - Run `velero restore` for persistent volumes.

See [Backup & Disaster Recovery](./system-architecture-backup-recovery.md) for snapshot schedules and retention policies.

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
