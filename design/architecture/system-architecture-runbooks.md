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
   - For local development, treat AOF restore as a debugging tool, not a normal recovery path:
     - Prefer restarting the Docker Compose stack and letting Coordination Redis rebuild transient state from PostgreSQL and new activity.
     - If you need to inspect coordination history, restore the AOF into an **isolated, throwaway Redis instance** (not the live dev coordination container).
     - Only restore an AOF into your live dev stack when the services and Lua registry match the AOF’s originating version; otherwise use a coordination reset instead of importing old coordination history.

   - **Coordination Redis recovery behavior**
     - When Coordination Redis recovers after an outage or severe degradation:
       - Tick executors:
         - Do **not** attempt to resume in-flight locks or leases based on in-memory state.
         - Rely solely on surviving Redis keys (`tick:{tenantRegionTag}:pending`, `tick-executor-lease:{tenantRegionTag}`, and lock keys) plus PostgreSQL idempotency guards to decide what work needs replay.
         - If `pending` survives for a region, the next executor for that `{tenantId, regionId}` replays the tick as described in the tick system design. If `pending` is missing (for example due to AOF tail loss), the scheduler treats partially executed work as lost and advances to the next `tickId`, relying on monitoring to surface inconsistencies.
       - Leases:
         - Discard any in-memory lease tokens; executors must reacquire `tick-executor-lease:{tenantRegionTag}` in Redis and treat previously held leases as invalid.
     - Sessions:
       - If `session:<tenantId>:<sessionId>` keys survive, reconnect flows behave normally.
       - If session keys are lost while game instances remain `RUNNING` in PostgreSQL, treat reconnect attempts as “no active binding” (clients may need to perform a fresh `LOGIN` or be rebound to the existing instance depending on ownership rules).

### Redis Session Schema and TTL Cleanup

When session-related metrics indicate schema or TTL problems, use this scoped cleanup procedure instead of ad-hoc `DEL` commands:

1. **Detect the issue**
   - Watch `session.cas_unsupported_schema_total` and reconnect error rates for non-zero values outside brief rollout windows.
   - Interpretation: services and Lua scripts are out of sync on the highest `schemaVersion` in use for `session:<tenantId>:<sessionId>` keys, session payloads have been corrupted, or a major TTL reduction has left an undesirable tail of long-lived sessions.
2. **Align deployments**
   - Verify and correct deployments so all Game Session Service instances run a version whose CAS script understands the highest `schemaVersion` currently present in Redis (follow the “scripts first, writers second” rule from the Redis Architecture docs).
3. **Run the session cleanup Job**
   - Use the session schema/TTL cleanup Job described in [Session Schema Cleanup and Large Keyspaces](./system-architecture-redis.md#session-schema-cleanup-and-large-keyspaces):
     - Scope the Job to one tenant at a time by prefix (for example `session:<tenantId>:*`).
     - Configure it to delete keys with unsupported `schemaVersion` values or aggressively reduce their TTL so they expire quickly when performing a TTL cut-over.
4. **Verify recovery**
   - Monitor `session.cas_unsupported_schema_total`, reconnect error rates, and Redis key counts for the affected tenant(s) to confirm the issue has cleared.
   - Affected players may need to log in again; no authoritative PostgreSQL data is lost.

3. **Full Cluster Restore**
   - Recreate the cluster using Terraform modules in `k8s/terraform`. See
     [`k8s/terraform/README.md`](../../k8s/terraform/README.md) for usage.
   - Run `velero restore` to recreate Kubernetes manifests.

See [Backup & Disaster Recovery](./system-architecture-backup-recovery.md) for backup schedules and retention policies.

### Redis Incident Scenarios

The following Redis-focused incident flows build on the general recovery steps above.

1. **Coordination AOF tail-loss SLO breach**
   - **Detect**
     - `tail_loss_ms` or `tail_loss_ticks` regularly exceed the **1–2 second** envelope (or **2×** `tick_interval_ms`) for one or more `{tenantId, regionId}` shards.
     - Region health shows `DEGRADED` or `COORDINATION_UNTRUSTWORTHY` for those shards.
   - **Decide**
     - For short-lived degradations where gameplay impact is minimal, investigate disk/replication performance, but keep serving traffic.
     - For sustained violations or `COORDINATION_UNTRUSTWORTHY` regions, plan a **region- or tenant-scoped coordination reset**.
   - **Act**
     1. Pause tick scheduling for affected `{tenantId, regionId}` scopes.
     2. Run the corresponding coordination reset Job (region or tenant scope) as described in [Coordination Reset Model](./system-architecture-redis.md#coordination-reset-model).
     3. Verify region health returns to `HEALTHY` and `tail_loss_ms` drops back into the SLO envelope before resuming ticks.

2. **Mis-sharded or mis-keyed tick/coordination keys**
   - **Detect**
     - CI or observability flags keys with unexpected hash tags (for example, multiple `{}` segments or missing `{tenantRegionTag}`).
     - Redis key inspections show coordination prefixes that do not match the documented patterns.
   - **Decide**
     - If mis-keyed data is purely coordination state (no unique business data), prefer a **reset** over in-place fixes.
     - If the mistake involves non-coordination prefixes that cannot be safely discarded, plan a one-off migration tool.
   - **Act**
     1. For coordination prefixes: follow the region/tenant/cluster reset flow from [Coordination Reset Model](./system-architecture-redis.md#coordination-reset-model) and rely on PostgreSQL/idempotent ticks to rebuild state.
     2. For non-coordination prefixes: write a small migration Job that:
        - Iterates the affected prefix (for example `automation_queue:{tenantId}:*`).
        - Writes corrected keys using shared builders.
        - Deletes or expires the old keys once consumers have been updated.

3. **Automation queue schema mistakes**
   - **Detect**
     - Automation consumers log deserialization errors or unknown `schemaVersion` values for `automation_queue:{tenantId}:*` keys.
     - Metrics show sustained failures processing automation work items.
   - **Decide**
     - If automation queues are purely best-effort, consider treating affected items as lost and flushing the prefix.
     - If work items must be preserved, prefer a migration Job over ad-hoc edits.
   - **Act**
     1. Pause automation processing for the affected tenants or globally, depending on blast radius.
     2. Implement and run a migration Job that:
        - Reads each automation work item.
        - Rewrites it into the corrected schema shape under the same or a new key prefix.
        - Drops or quarantines items that cannot be safely translated.
     3. Resume automation processing and monitor error rates and queue depths until they stabilize.

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

## Telnet Path Degraded or Failing

When Telnet clients report intermittent disconnects, missing output, or complete inability to log in while WebSocket clients behave normally, treat it as a **Telnet path** incident and follow this checklist:

1. **Confirm scope and symptoms**
   - Verify whether the issue is limited to Telnet clients (TCP Proxy path) or affects WebSocket clients as well.
   - Check user reports and logs for phrases like “Telnet,” “mudlet,” or “tin tin” to confirm it is a legacy-client issue.
2. **Check TCP Proxy metrics**
   - Open the **TCP Proxy** Grafana dashboard and inspect:
     - `tcpproxy.connections.active` / `tcpproxy.connections.total` for unusual spikes or drops.
     - `tcpproxy.connections.limit.exceeded` for sustained non-zero values, which indicate global or per-IP caps are rejecting new connections.
     - `tcpproxy.telnet.discarded` for spikes that may reflect malformed Telnet sequences, buffer overflows, or repeated malformed `SESSION` envelopes.
     - `tcpproxy.websocket.reconnects` and `tcpproxy.websocket.reconnect.delay` for repeated reconnection attempts to Spring Cloud Gateway.
     - `tcpproxy.tls.misconfig` and `tcpproxy.gateway.handshake.failures{reason=...}` for TLS/mTLS configuration issues.
3. **Compare Telnet vs WebSocket flows**
   - Pick a specific `{sessionId, tenantId}` (or user) and:
     - Use Logging & Admin Service / Kibana to find the Telnet-side logs (from the TCP Proxy) and confirm that `LOGIN`/`LOOK` commands are received, with credentials redacted.
     - Find the corresponding WebSocket session in Spring Cloud Gateway logs and the downstream Game Session logs to verify whether the commands reach the backend and whether responses are emitted.
   - If WebSocket flows succeed while Telnet flows stall or drop, the problem is likely in the TCP Proxy, Gateway WebSocket route, or mTLS between them.
4. **Evaluate connection caps vs abusive clients**
   - If `tcpproxy.connections.limit.exceeded` is elevated and many IPs are affected:
     - Consider temporarily raising `TCP_PROXY_MAX_CONNECTIONS` and/or `TCP_PROXY_MAX_CONNECTIONS_PER_IP` for the affected environment and redeploying the proxy.
     - Watch the same metrics after the change to confirm the limits are no longer frequently hit.
   - If the metric is dominated by a small number of IPs:
     - Treat those IPs as abusive or misconfigured clients; prefer blocking or throttling them via firewall rules, ingress rules, or specific rate-limiter policies rather than raising global limits.
5. **Check WebSocket bridge and TLS configuration**
   - If `tcpproxy.websocket.reconnects` and `tcpproxy.gateway.handshake.failures{reason="cert_validation"}` increase:
     - Confirm `GATEWAY_WS_URL` points to a hostname that matches the Gateway certificate SANs.
     - Verify `FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, and `FIREMUD_GRPC_CA_CERT_PATH` are valid and mounted in the proxy deployment.
     - If needed, roll back recent TLS or gateway changes and reapply them with correct hostnames and certificate bundles.
6. **Run Telnet smoke tests**
   - Use the Telnet smoke script described in the TCP Proxy README (or the `dev-echo-loop.sh` flow) to:
     - Connect to the proxy with `telnet` or a test client.
     - Send `SESSION` + `LOGIN` + `LOOK` and confirm that responses match the WebSocket path for the same account.
     - Capture the raw transcript and include it in incident notes.
7. **Escalation and mitigation**
   - If Telnet is degraded but WebSocket is healthy and the root cause is not immediately fixable:
     - Communicate to players that Telnet may be unreliable and recommend the Web client as a temporary workaround.
     - Track the incident and any config changes in the Logging & Admin Service / runbook history so future investigations can correlate behavioral changes with deployment events.

---

These runbooks provide a starting point for operators. Update them as new tooling or workflows evolve.

## Related Documentation

- [Logging & Monitoring Overview](./system-architecture-logging-monitoring.md)
- [Backup & Disaster Recovery](./system-architecture-backup-recovery.md)
- [CI/CD Pipeline](./system-architecture-cicd.md)
