# FireMUD Operational Runbooks

This document serves as an **index** to operational runbooks for FireMUD. It provides a high-level map of operator workflows and links to detailed, per-topic runbooks.

---

## Deployment

High-level steps and operator responsibilities for rolling out new versions of FireMUD to Kubernetes-backed environments.

See: `design/architecture/system-architecture-deployment-runbook.md`.

## Scaling

Guidance on scaling services and infrastructure (Gateway, Game Session, Redis, PostgreSQL) in response to increased load.

See: `design/architecture/system-architecture-scaling-runbook.md`.

## Recovery

This section covers recovery scenarios at a high level; detailed, per-topic runbooks live in their own files.

### Database Failure and Cluster Restore

- For PostgreSQL backup and restore procedures (including `dev-tools/restores/restore-cluster.sh` and manual `kubectl cp`/`psql` flows), see:
  - `design/architecture/system-architecture-backup-recovery.md`

### Redis Session Schema and TTL Cleanup

- For the scoped session cleanup Job (metrics, per-tenant prefixes such as `session:game:<tenantId>:*`, and large-keyspace safety guidelines), see:
  - `design/architecture/system-architecture-redis-incident-runbook.md#session-schema-and-ttl-cleanup`

### Redis Incident Scenarios

- For Coordination/Cache Redis outages, AOF problems, tail-loss SLO breaches, mis-sharded keys, and automation queue schema issues, see:
  - `design/architecture/system-architecture-redis-incident-runbook.md#redis-incident-scenarios`

## Asset Store

Operational guidance for the asset store used by the Game Design Service, including health checks and incident handling.

See: `design/architecture/system-architecture-asset-store-runbook.md`.

## Hotfix Procedure

- Use standard deployment workflows with a minimal, well-scoped change set.
- Ensure tests and smoke checks are run for the hotfix branch.
- Coordinate any emergency schema or configuration changes with the relevant architecture owners.

## Telnet Path Degraded or Failing

Operator guidance for incidents affecting the Telnet path through the TCP Proxy Service.

See: `design/architecture/system-architecture-telnet-degraded-runbook.md`.

## Related Documentation

- `design/architecture/system-architecture-overview.md`
- `design/architecture/system-architecture-redis.md`
- `design/architecture/system-architecture-ticks.md`
- `design/architecture/system-architecture-gateway.md`
- `design/architecture/system-architecture-logging-monitoring.md`
- `design/architecture/system-architecture-backup-recovery.md`
- `design/architecture/system-architecture-cicd.md`
