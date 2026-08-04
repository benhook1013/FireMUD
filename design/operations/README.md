# Operations Documentation

This section routes operators to FireMUD's deployment, recovery, incident-response, credential, and compliance procedures. Runbooks explain how to operate the platform; linked architecture documents remain authoritative for the technical contracts those procedures protect.

## Deployment And Capacity

- [Deployment runbook](../architecture/system-architecture-deployment-runbook.md) - Kubernetes rollout workflow, readiness evidence, and rollback boundaries.
- [Scaling runbook](../architecture/system-architecture-scaling-runbook.md) - Capacity and service-scaling guidance.
- [CI/CD architecture](../architecture/system-architecture-cicd.md) - Build, promotion, and deployment-evidence contracts.

## Access And Credentials

- [Operator credentials runbook](../architecture/system-architecture-operator-credentials-runbook.md) - mTLS client certificates and control-plane credentials.
- [Environment and secrets](../architecture/infrastructure/environment-and-secrets.md) - Canonical configuration and secret-delivery index.
- [Secret compliance evidence](./secret-compliance/README.md) - Environment provisioning-status records used by compliance checks.

## Recovery

- [Backup and disaster recovery](../architecture/system-architecture-backup-recovery.md) - PostgreSQL backup, restore, quarantine, and controlled reopen procedures.
- [Post-restore hardening](../architecture/system-architecture-post-restore-hardening.md) - Security and consistency checks after a restore.
- [Redis incident runbook](../architecture/system-architecture-redis-incident-runbook.md) - Coordination/Cache Redis outages, AOF problems, tail-loss breaches, key ownership, and session cleanup.
- [Redis reset and recovery](../architecture/system-architecture-redis-reset-and-recovery.md) - Fenced region, tenant, and cluster reset procedures.
- [Tick incident runbook](../architecture/system-architecture-tick-incident-runbook.md) - Stalled regions, replay pressure, and durable/coordination divergence.

## Service Incidents

- **Player Experience Incidents:** use the [player-experience incident runbook](../architecture/system-architecture-player-experience-incident-runbook.md) for login, command latency, chat delivery, or Telnet/WebSocket path availability incidents.
- [Observability-stack incident runbook](../architecture/system-architecture-observability-incident-runbook.md) - Prometheus, Alertmanager, Elasticsearch/Kibana, Grafana, and tracing failures.
- [Telnet degraded runbook](../architecture/system-architecture-telnet-degraded-runbook.md) - TCP Proxy and Telnet-path failures.
- [Asset-store runbook](../architecture/system-architecture-asset-store-runbook.md) - Game Design asset-store health and recovery.

## Emergency Hotfix Procedure

- Use the standard deployment workflow with a minimal, well-scoped change set.
- Run the required checks and smoke proof for the affected boundary.
- Coordinate emergency schema or configuration changes with the owners of the relevant architecture contracts.

## Related Sources

- [Architecture documentation](../architecture/README.md)
- [Infrastructure documentation](../architecture/infrastructure/README.md)
- [Operator user journeys](../product/user-journeys/operators.md)
- [Observability dashboards](../observability/grafana/README.md)
