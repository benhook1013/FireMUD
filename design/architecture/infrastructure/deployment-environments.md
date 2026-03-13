# Deployment Environments

This document outlines how FireMUD is deployed across environments including local Docker Compose, ephemeral CI/preview stacks, self-hosted hobby deployments, and Kubernetes-backed shared environments (dev/demo, staging, production). It includes discovery mechanisms, health check strategies, and environment-specific control expectations.

## Table of Contents

- [Canonical Environment Classes](#canonical-environment-classes)
- [Local Development: Docker Compose](#local-development-docker-compose)
- [Production: Kubernetes](#production-kubernetes)
- [Telnet Edge Deployment](#telnet-edge-deployment)
- [Monitoring & Logging](#monitoring--logging)
- [Spring Profile Configuration](#spring-profile-configuration)
- [Staging Environment for Playtesting](#staging-environment-for-playtesting)
- [Related Documentation](#related-documentation)

---

## Quick Environment Decision Guide

- Use **Docker Compose** when developing locally or running short-lived preview stacks from pull requests.
- Use **Kubernetes (dev/stage/prod clusters)** for any shared or player-facing environment where autoscaling, high availability, and full observability are required.
- Prefer **staging** for playtests that should mirror production routing, TLS, and Redis/Postgres topologies before promoting changes. Treat PR preview stacks as fast functional review environments, not as a substitute for prod-like validation.

---

## Canonical Environment Classes

Use these classes as the source of truth for environment roles and control expectations across architecture docs:

| Class | Typical Topology | Secret Source | Rotation/Hardening | Backup/Restore Posture | Deploy Path |
| --- | --- | --- | --- | --- | --- |
| `local-dev` | Docker Compose on a developer machine | `.env` plus local files; generated certs/keys allowed | Convenience-first; manual | Local snapshots/ad hoc restore | `./gradlew devUp` / `devDown` |
| `ci-preview` | Short-lived CI runner stacks, usually Docker Compose | Ephemeral CI secrets and sample defaults | Short-lived; no long-term rotation guarantees | Disposable | GitHub Actions preview workflows |
| `dev-demo-cluster` | Shared but non-player-facing Kubernetes cluster | Kubernetes Secrets/ConfigMaps | Basic hardening; can prioritize iteration speed | Ad hoc unless explicitly scheduled | Helm/manual workflows (`manual-helm-deploy.yml`) |
| `hobby-self-hosted` | Small player-facing deployment with production-like roles at low scale | Kubernetes Secrets/ConfigMaps | Production-like for Tier A credentials; simplified ops acceptable | Operator-managed backups expected | Operator-applied manifests/charts |
| `staging` | Prod-like Kubernetes cluster with smaller sizing | Kubernetes Secrets/ConfigMaps | Production-like controls; required post-restore secret hardening before playtests | Disposable by default unless explicitly enabling schedules | Git-tracked Kustomize overlays + operator `kubectl apply -k` |
| `production` | Player-facing Kubernetes cluster | Kubernetes Secrets/ConfigMaps | Strictest controls and change gates | Scheduled backups + verification + mandatory post-restore hardening | Git-tracked Kustomize overlays + operator `kubectl apply -k` |

Cross-document rules:

- Canonical class names are exactly: `local-dev`, `ci-preview`, `dev-demo-cluster`, `hobby-self-hosted`, `staging`, `production`. Terms such as `qa` are aliases only and must be mapped explicitly to one of these classes in environment documentation and automation.
- Staging and production overlay updates must use immutable image digests and follow the promotion/attestation model defined in `system-architecture-cicd.md`.
- Player-facing classes (`hobby-self-hosted`, `staging`, `production`) must treat JWT secret files (`FIREMUD_AUTH_JWT_SECRET_PATH`) and TCP Proxy `GATEWAY_WS_URL` listener alignment as required preflight invariants.
- Player-facing classes (`hobby-self-hosted`, `staging`, `production`) must complete environment bootstrap before first deploy: baseline secrets, JWT/JWKS resources, certificate issuer bindings, registry pull credentials, and environment-specific external integration credentials must exist and pass preflight before workloads are applied.
- Classes documented as disposable (`ci-preview`, default `staging`) must not be used as evidence for backup/SLO guarantees unless their controls are explicitly upgraded.
- Staging and production deployments must run the canonical preflight policy gate defined in `system-architecture-deploy-preflight-policy.md`; production promotions must satisfy the attestation contract in `system-architecture-promotion-attestation.md`.
- `hobby-self-hosted` deployments are also player-facing and must run equivalent checks for player-facing invariants (JWT file-path contract, JWKS resource contract, Redis role split, and `GATEWAY_WS_URL` alignment) before opening traffic, even when not using the staging/production Kustomize overlay workflow. In this class, operator preflight is mandatory while overlay PR CI checks are optional/recommended.
- `dev-demo-cluster` is explicitly **non-promotable** and **non-attestable**. It must not be used as the source of production promotion evidence, rollback evidence, or DR-readiness sign-off. Any validation performed there is informative only.

---

## Local Development: Docker Compose

FireMUD uses Docker Compose for local development and testing:

### Docker Compose Characteristics

- All services, including the gateway, are built locally via `Dockerfile`s.
- Docker Compose orchestrates container startup. `depends_on` is configured with
  `condition: service_healthy` so services wait for PostgreSQL and Redis to pass
  health checks before starting.
- Service discovery is handled by Docker's internal DNS (e.g., `game-session-service:8080`).
- Route URIs in Spring Cloud Gateway use static hostnames defined in the `dev`
  profile of `application.yml`.
- Connection settings for PostgreSQL and Redis are loaded from a `.env` file.
  A sample `.env.sample` is provided with default credentials. Additional variables are described in [Environment & Secrets Management](./environment-and-secrets.md).
- Start the stack with `./gradlew devUp` and shut it down with `./gradlew devDown` (see [Developer Setup](../../../DEVELOPER_SETUP.md)).
- The stack also runs a `pg-dump-cron` container that creates and rotates PostgreSQL dumps under `./backups`.
- For details on all configuration variables, see [Environment Variables & Secrets Management](./environment-and-secrets.md).
  Standard ports include **8080** for HTTP, **6565** for gRPC, and **2323** for
  the TCP proxy.

### Docker Health Checks

- Services expose Spring Boot’s `/actuator/health` for basic health status.
- Docker Compose can monitor health using `healthcheck` blocks in `docker/docker-compose.yml`.
- Health status is visible via `docker ps` (e.g., `healthy`, `unhealthy`), but:
  - Docker does **not** automatically restart containers that become `unhealthy`.
      Even with `restart: unless-stopped` configured, services remain running
      until manually restarted.
    - `depends_on` waits for initial health checks, but ongoing readiness still
      requires manual monitoring.
    - See [Reconnection Strategy](../system-architecture-reconnection.md) for how sessions survive service restarts in Docker Compose.

💡 **Tip**: For more reliable startup coordination, use **Gateway retry filters** or utilities like `wait-for-it.sh`.
The gateway now includes a default *Retry* filter in `application.yml` so failed
requests to services are retried automatically during startup. Each service's
Dockerfile runs `docker/start-service.sh`, which invokes `wait-for-it.sh` to
pause startup until PostgreSQL and Redis are reachable.

---

## Production: Kubernetes

In production, FireMUD is deployed into Kubernetes (e.g., AWS EKS, Google GKE, or self-managed clusters).

### Kubernetes Characteristics

- Services are deployed as Pods and exposed via Kubernetes Services.
- DNS-based discovery is built into Kubernetes (e.g., `game-session-service.default.svc.cluster.local`).
- Route URIs in Spring Cloud Gateway use service names configured in the `prod`
  profile of `application.yml`.
- Internal microservices communicate directly over gRPC, bypassing the Spring Cloud Gateway.
- The **TCP Proxy Service** and **Spring Cloud Gateway** are typically exposed using Kubernetes `LoadBalancer` Services so external clients can connect directly. See [Telnet Edge Deployment](#telnet-edge-deployment) for details on client IP preservation and PROXY protocol.
- See [Security Architecture](../system-architecture-security.md#tls-termination-for-gateway) and [Gateway Architecture](../system-architecture-gateway.md#tls-termination-for-gateway) for the full TLS termination chain (browser/Telnet clients → load balancer → Spring Cloud Gateway → backend services) and DMZ boundary details; this document avoids duplicating those rules.
- Sample `NetworkPolicy` manifests to restrict internal traffic are provided in
  [`k8s/network-policies`](../../../k8s/network-policies) and can be applied after
  deploying the base manifests.
- Configuration and secrets are managed through ConfigMaps and Secrets.
- Certificates for TLS termination and mTLS are issued by **cert-manager** and mounted from Kubernetes Secrets.
- The cluster uses **IPVS** (or a similar load-balancing mode) to route service traffic efficiently.
- Redis is deployed as **two logical roles** in both Kubernetes and Docker Compose (see [Redis Architecture](../system-architecture-redis.md)):
  - A **Coordination Redis** deployment runs as a clustered StatefulSet with automatic failover in production and smaller clusters in non-production. It uses AOF and persistent volumes so it behaves as a long‑lived coordination log across normal rollouts and pod restarts.
  - A **Cache/Rate-Limit Redis** deployment runs as a separate StatefulSet or Deployment tuned for eviction-driven workloads (for example using `allkeys-lru`), with sizing independent of Coordination Redis. It may use lighter durability (RDB snapshots or even ephemeral volumes) because its keys are best-effort and recomputable.
  - Local development runs **two Redis containers** under Docker Compose with the same role split: a Coordination Redis service and a Cache/Rate-Limit Redis service, with Coordination Redis durable via AOF and Cache/Rate-Limit Redis configured for eviction-driven workloads. When operators want to **reset** coordination state (for example, to test reset-tolerant behavior or remediate mis-keyed data), they run an explicit coordination-reset Job or script that wipes the Coordination Redis AOF volume as described in the Redis Operations runbook, rather than relying on Helm to clear data automatically on every deploy.
- PostgreSQL is deployed within the cluster (or provided as a managed database service) to store persistent domain data. See [System Architecture Overview](../system-architecture-overview.md#data-and-state-management). Backup and restore procedures are outlined in [Backup & Disaster Recovery](../system-architecture-backup-recovery.md) and the [Operational Runbooks](../system-architecture-runbooks.md#recovery).
- **Dev/demo Kubernetes clusters** may be deployed via Helm using [manual-helm-deploy.yml](../../../.github/workflows/manual-helm-deploy.yml) (for example with `k8s/helm/values-local.yaml` or `values-dev.yaml`). These clusters are intentionally excluded from the staging → production promotion chain and must not emit attestation artifacts.
- **Staging and production** deployments are applied from a secure operator environment using **Kustomize overlays** (for example `kubectl apply -k k8s/overlays/stage` and `kubectl apply -k k8s/overlays/prod`). Immutable image digest changes for these overlays are tracked in Git so promotion and rollback are auditable. See [Deployment Runbook](../system-architecture-deployment-runbook.md) and [CI/CD Pipeline](../system-architecture-cicd.md#promotion--rollback-model).

A sample Terraform module for a local Kind cluster is provided in [k8s/terraform](../../../k8s/terraform). This demo module creates a `firemud` namespace and optional Redis Helm release for quick testing. Use `helm install` with the example charts in [k8s/helm](../../../k8s/helm) to deploy services locally.

- All tenants share this cluster with data separated by `tenantId` per service. See [Multi-Tenancy](../system-architecture-multi-tenancy.md) for more.

### Kubernetes Health Monitoring

- Kubernetes uses Spring Boot’s `/actuator/health` for both:
  - **Readiness probes** — to determine if a service is ready to handle requests.
  - **Liveness probes** — to detect and restart stuck or unresponsive containers.

### Kubernetes Auto Recovery

- Kubernetes automatically:
  - Removes unready pods from Services
  - Restarts failing pods based on probe failures
  - Scales services up/down via deployments or Horizontal Pod Autoscalers (HPA). An example manifest is provided in `k8s/base/hpa-example.yaml` and serves as the default configuration.
- Pod restarts are transparent to players; see [Reconnection Strategy](../system-architecture-reconnection.md) for cross-environment behavior.

---

## Telnet Edge Deployment

Kubernetes load balancing commonly SNATs raw TCP traffic, so the TCP Proxy Service may not see the true client address on the TCP socket. To preserve client IPs and keep the DMZ boundary explicit:

- Expose a small **Telnet edge proxy** (for example, HAProxy) as the public `LoadBalancer` for port `2323`.
- Forward Telnet traffic from the edge proxy to the TCP Proxy Service using **PROXY protocol** on a dedicated, internal-only TCP Proxy listener/port (see `TCP_PROXY_PROXY_PROTOCOL_PORT` in the TCP Proxy design).
- Restrict that PROXY-enabled listener so it is reachable only from the edge proxy (separate `Service` + `NetworkPolicy`).
- Keep the public Telnet listener PROXY-disabled to prevent client-IP spoofing attempts.
- Have the TCP Proxy Service parse the PROXY header, forward the recovered client IP to Spring Cloud Gateway as `X-Proxy-Client-IP`, and let the gateway standardize it as `X-Client-IP` for downstream services.

If PROXY protocol is not enabled (or source IP is not preserved), treat per-IP limits as best-effort and size them conservatively. See [Security Architecture](../system-architecture-security.md#tls-termination-for-gateway) and [Gateway Architecture](../system-architecture-gateway.md#tls-termination-for-gateway) for the full TLS termination chain.

---

## Monitoring & Logging

FireMUD relies on a consistent observability stack across environments. Expectations for log fields, metric naming, and alert labels live in [Logging & Monitoring](../system-architecture-logging-monitoring.md).

### Kubernetes (Default)

The full observability stack is deployed in Kubernetes. Example manifests for the collector, Jaeger, and exporters live under [`k8s/monitoring`](../../../k8s/monitoring).

Typical components:

- Prometheus scrapes metrics from all services.
- Grafana dashboards visualize performance metrics.
- Alertmanager notifies on failures or latency spikes.
- OpenTelemetry spans are emitted by services for distributed tracing.
- Jaeger stores these traces for debugging and analysis.
- Fluent Bit ships logs to Elasticsearch; Kibana is used for log queries.

### Docker Compose (Optional)

The Docker Compose environment omits the full observability stack by default. Operators may run a small local observability stack for debugging when needed, but Docker Compose is not treated as the canonical, prod-like observability deployment.

See [Logging & Monitoring](../system-architecture-logging-monitoring.md) for the signal conventions that apply regardless of environment.

---

## Spring Profile Configuration

Spring Boot services define `dev` and `prod` profiles inside `application.yml`.
Select the desired profile via the `SPRING_PROFILES_ACTIVE` environment variable.

- **dev** profile:
  - Used with Docker Compose
  - Static URI-based routing
  - Dev-mode databases or in-memory stores

- **prod** profile:
  - Used in Kubernetes
  - DNS-based routing to Kubernetes Services
  - Integration with persistent infrastructure such as the PostgreSQL cluster

## Staging Environment for Playtesting

A dedicated staging cluster mirrors production using smaller node sizes. Pull requests spin up a short-lived Docker Compose stack via [preview.yml](../../../.github/workflows/preview.yml) so reviewers can do fast functional checks; staging is the intended environment for prod-like playtests and routing/TLS validation.
Staging test data may be reset on a schedule once operators explicitly install staging-specific automation; by default staging is not scheduled (see `schedule.md`).
For details on collecting tester feedback see [Playtesting & Feedback](../../project-management/playtesting-feedback.md).

Environment-boundary contract: staging and production are separate environment boundaries with separate cluster credentials and per-environment secret sources. Shared namespace defaults (`firemud`) apply within each environment boundary and must not be interpreted as permission to share credentials, buckets, or control-plane trust roots across staging and production.
Normal deployments, not only restores, must validate that backup storage, asset storage, outbound communications, and operator credential bindings point at the intended environment boundary before player traffic is opened.

By default, staging is treated as **disposable**: it is not protected by the production backup schedule and can be rebuilt from manifests and fresh data as needed.

Operators may temporarily restore staging from **production** backups for disaster recovery rehearsals or investigations. When doing so, staging must follow the same post-restore secret hardening steps as production (see `system-architecture-backup-recovery.md#post-restore-secret-hardening`) so JWT keys and database credentials are rotated before opening the environment to playtests.
When staging is restored from production-origin snapshots, operators must also run mandatory staging data sanitization and record evidence before playtests reopen (see `system-architecture-backup-recovery.md#post-restore-secret-hardening` for the restore hardening sequence).

Staging does not run the production backup CronJobs listed in `schedule.md` unless staging-specific schedules are explicitly installed.
PRs that modify `k8s/` are checked by [`.github/workflows/validate-kustomize-overlays.yml`](../../../.github/workflows/validate-kustomize-overlays.yml), which blocks staging backup schedules unless operators intentionally add `k8s/overlays/stage/STAGING_BACKUPS_ENABLED`.

---

## Related Documentation

- [Infrastructure Overview](./README.md)
- [Gateway Architecture](../system-architecture-gateway.md)
- [Protocol Bridging](../system-architecture-protocol-bridging.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)
