# FireMUD Operational Runbooks

This document summarizes routine procedures for deploying, scaling, and recovering FireMUD environments. Each step references existing architecture docs so operators can quickly locate details.

---

## Deployment

1. **CI Pipeline** builds Docker images and pushes them to GHCR. Kubernetes
   rollouts are triggered manually via the
   [`manual-helm-deploy.yml`](../../.github/workflows/manual-helm-deploy.yml)
   workflow until pipeline automation is complete.
   See [CI/CD Pipeline](./system-architecture-cicd.md) for workflow details.
2. **Helm Charts** deploy each microservice. Install the umbrella chart with:

   ```bash
   helm install firemud ./k8s/helm/firemud -f k8s/helm/values-dev.yaml
   ```

   See the [Helm Charts guide](../../k8s/helm/README.md) for environment-specific values and the production `values-prod.yaml`.

3. Verify pods are running with `kubectl get pods -n firemud`.
4. Monitor rollout progress in the CI job summary. Grafana dashboards
   provide additional visibility.

For local development, use `./gradlew devUp` to start Docker Compose and
`./gradlew devDown` when finished.

## Scaling

1. Adjust replica counts in the Helm chart or use `kubectl scale`:

   ```bash
   kubectl scale deploy account-service --replicas=3 -n firemud
   ```

2. Update Horizontal Pod Autoscaler settings if enabled. An example
   manifest lives at `k8s/base/hpa-example.yaml`.
3. Review Prometheus metrics to ensure CPU and memory usage remain healthy.
   Monitoring manifests are provided under `k8s/monitoring/`. Grafana
   dashboards visualize these metrics.
   See [Logging & Monitoring](./system-architecture-logging-monitoring.md) for
   the observability stack configuration.
4. For database or Redis clusters, scale StatefulSets according to their
   respective runbooks.
5. Production rollouts should reuse `k8s/helm/values-prod.yaml` or the per-service overrides defined in [Helm Charts guide](../../k8s/helm/README.md) instead of the dev values referenced above.

## Recovery

1. **Database Failure**
   - **Primary recovery**:
     1. Run `dev-tools/restores/restore-cluster.sh <backup-name>` to restore from the latest `pg_dump` and restart services.
     2. Export to a custom namespace by setting `FIREMUD_K8S_NAMESPACE` before restoring.
   - **Manual recovery** (if the helper script is unavailable):

     ```bash
     kubectl cp <namespace>/<pg-pod>:/backups/latest.sql.gz ./latest.sql.gz
     gunzip -c latest.sql.gz | kubectl exec -i <postgres-pod> -- psql -U "$FIREMUD_POSTGRES_USER" "$FIREMUD_POSTGRES_DB"
     kubectl rollout restart deployment -n firemud
     kubectl rollout restart statefulset -n firemud
     ```

   - **Object bucket restore**: If dumps live in `PG_DUMP_BUCKET`, download with `aws s3 cp s3://$PG_DUMP_BUCKET/<path> ./dump.sql.gz` (add `--endpoint-url` for MinIO) before running the restore steps above.
   - **Local dev**: run `dev-tools/restores/restore-db.sh <backup-file>` and then `docker compose restart`.
   - **Automation**:
     - Terraform schedules backups via `k8s/velero/schedule.yaml`.
     - The `verify-backups` CronJob (`k8s/velero/verify-backups-cronjob.yaml`) validates daily.
     - Use `manual-backup-restore.yml` from GitHub Actions to test restores in a temporary namespace.
   - **Maintain dump volume**: The [`firemud-pg-dump` CronJob](../../k8s/postgres/pg-dump-cronjob.yaml) rotates dumps, but PVCs can still fill. The Docker Compose stack runs `pg-dump-cron` every 15 minutes—periodically inspect the `firemud-pg-dumps` PVC and prune stale `*.sql.gz` files, or run `dev-tools/backups/pg-dump-rotate.sh` manually.
2. **Redis Failure**
   - Redis nodes automatically resync using AOF and replication.
     Services reconnect on restart. See
     [Redis Architecture](./system-architecture-redis.md)
     for persistence and recovery details.
   - For local development, restore an AOF file with
     `dev-tools/restores/restore-redis-aof.sh <file>` if you need to recover transient
     state.

   - **Coordination Redis recovery behavior**
     - When Coordination Redis recovers after an outage or severe degradation:
       - Tick executors:
         - Do **not** attempt to resume in-flight locks or leases based on in-memory state.
         - Rely solely on surviving Redis keys (`tick:{tenantRegionTag}:pending`, `tick-executor-lease:{tenantRegionTag}`, and lock keys) plus PostgreSQL idempotency guards to decide what work needs replay.
         - If `pending` survives for a region, the next executor for that `{tenantId, regionId}` replays the tick as described in the tick system design. If `pending` is missing (for example due to AOF tail loss), the scheduler treats partially executed work as lost and advances to the next `tickId`, relying on monitoring to surface inconsistencies.
       - Leases:
         - Discard any in-memory lease tokens; executors must reacquire `tick-executor-lease:{tenantRegionTag}` in Redis and treat previously held leases as invalid.
       - Sessions:
         - If `session:{tenantId}:{sessionId}` keys survive, reconnect flows behave normally.
         - If session keys are lost while game instances remain `RUNNING` in PostgreSQL, treat reconnect attempts as “no active binding” (clients may need to perform a fresh `LOGIN` or be rebound to the existing instance depending on ownership rules).

   - **Session schema cleanup (deployment mismatch)**
     - Symptom: the `session.cas_unsupported_schema_total` metric (or logs mentioning `UNSUPPORTED_SCHEMA_VERSION` from the session CAS script) is non-zero outside of brief rollout windows.
     - Interpretation: services and Lua scripts are out of sync on the highest `schemaVersion` in use for `session:{tenantId}:{sessionId}` keys, or session payloads have been corrupted.
     - Remediation:
       1. Verify and correct deployments so all Game Session Service instances run a version whose CAS script understands the highest `schemaVersion` currently present in Redis (follow the “scripts first, writers second” rule from the Redis Architecture docs).
       2. Run the session schema cleanup tool/Job (once implemented) that:
          - Scans `session:{tenantId}:*` keys for `schemaVersion` values not supported by the current CAS script, and
          - Deletes those keys or reduces their TTL so they expire quickly.
       3. Monitor `session.cas_unsupported_schema_total` and reconnect error rates to confirm the issue has cleared. Affected players may need to log in again; no authoritative PostgreSQL data is lost.

3. **Full Cluster Restore**
   - Recreate the cluster using Terraform modules in `k8s/terraform`. See
     [`k8s/terraform/README.md`](../../k8s/terraform/README.md) for usage.
   - Run `velero restore` to recreate Kubernetes manifests.

See [Backup & Disaster Recovery](./system-architecture-backup-recovery.md) for backup schedules and retention policies.

## Asset Store

1. A self-hosted MinIO cluster stores published game assets when an external CDN
   is unavailable. Deploy the manifests under `k8s/minio/` and create a
   `minio-credentials` Secret with `accessKey` and `secretKey` keys.
   Rotate these credentials via the MinIO credentials manifest (`k8s/minio/credentials.yaml`) and follow the [Environment & Secrets Management](./infrastructure/environment-and-secrets.md#minio-credentials) guidelines when updating keys.
2. Create the `firemud-assets` bucket with the MinIO client:

   ```bash
   kubectl run mc --rm -it --image=minio/mc --command -- \
     sh -c "mc alias set local http://minio:9000 $ACCESS $SECRET && mc mb local/firemud-assets"
   ```

3. Allow public reads and CORS from the gateway domain:

   ```bash
   kubectl run mc --rm -it --image=minio/mc --command -- \
     sh -c "mc alias set local http://minio:9000 $ACCESS $SECRET && \
            mc anonymous set download local/firemud-assets && \
            printf '[{\"AllowedMethods\":[\"GET\"],\"AllowedOrigins\":[\"https://your-gateway-domain\"],\"AllowedHeaders\":[\"*\"]}]' > /tmp/cors.json && \
            mc cors set local/firemud-assets /tmp/cors.json"
   ```

4. Services access the bucket using the `ASSET_STORE_*` environment variables. When the
   gateway proxies `/assets/**` to MinIO, set `ASSET_STORE_ENDPOINT` to
   `https://<gateway-domain>/assets` so published manifests generate public URLs.
5. To remove a published tenant version, delete the corresponding prefix:

   ```bash
   kubectl run mc --rm -it --image=minio/mc --command -- \
     sh -c "mc rm -r --force local/firemud-assets/<tenant>/<version>/"
   ```

## Hotfix Procedure

1. Identify the offending service via logs or alerts.
2. Commit the fix to `main` and trigger the CI pipeline.
3. Use `helm upgrade --install` with the new image tag to deploy only the affected service.
4. Monitor metrics and logs to ensure the issue is resolved; if instability persists, run `helm rollback <release> <revision>` to revert.

---

These runbooks provide a starting point for operators. Update them as new tooling or workflows evolve.

## Related Documentation

- [Logging & Monitoring Overview](./system-architecture-logging-monitoring.md)
- [Backup & Disaster Recovery](./system-architecture-backup-recovery.md)
- [CI/CD Pipeline](./system-architecture-cicd.md)
