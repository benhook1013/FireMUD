# 🚀 Deployment Environments

This document outlines how FireMUD is deployed across different environments, focusing on **Docker Compose** for local development and **Kubernetes** for production. It includes discovery mechanisms, health check strategies, and environment-specific configurations.

---

## 🧪 Local Development: Docker Compose

FireMUD uses Docker Compose for local development and testing:

### 🔧 Docker Compose Characteristics

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

### 🩺 Docker Health Checks

- Services expose Spring Boot’s `/actuator/health` for basic health status.
- Docker Compose can monitor health using `healthcheck` blocks in `docker-compose.yml`.
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

## ☁️ Production: Kubernetes

In production, FireMUD is deployed into Kubernetes (e.g., AWS EKS, Google GKE, or self-managed clusters).

### 🔧 Kubernetes Characteristics

- Services are deployed as Pods and exposed via Kubernetes Services.
- DNS-based discovery is built into Kubernetes (e.g., `game-session-service.default.svc.cluster.local`).
- Route URIs in Spring Cloud Gateway use service names configured in the `prod`
  profile of `application.yml`.
- Internal microservices communicate directly over gRPC, bypassing the Spring Cloud Gateway.
- The **TCP Proxy Service** and **Spring Cloud Gateway** are typically exposed using Kubernetes `LoadBalancer` Services so external clients can connect directly.
- The external load balancer exposes only the Gateway and TCP Proxy Service, forming a DMZ that shields internal services.
- See [Security Architecture](../system-architecture-security.md) for TLS termination, mTLS certificates, and network policy details.
- Sample `NetworkPolicy` manifests to restrict internal traffic are provided in
  [`k8s/network-policies`](../../../k8s/network-policies) and can be applied after
  deploying the base manifests.
- Configuration and secrets are managed through ConfigMaps and Secrets.
- Certificates for TLS termination and mTLS are issued by **cert-manager** and mounted from Kubernetes Secrets.
- The cluster is planned to use **IPVS** (or a similar load-balancing mode) to route service traffic efficiently. This configuration is pending implementation. (TODO: Not yet implemented)
- Redis is planned to run as a clustered StatefulSet with automatic failover in production (see [Redis Architecture](../system-architecture-redis.md)). Local development instead runs a single Redis container configured via `config/redis.conf`, which disables RDB snapshots and relies on AOF. Both setups enable **AOF** persistence. (TODO: Not yet implemented)
- PostgreSQL is deployed within the cluster (or provided as a managed database service) to store persistent domain data. See [System Architecture Overview](../system-architecture-overview.md#📦-data-and-state-management). Backup and restore procedures are outlined in [Backup & Disaster Recovery](../system-architecture-backup-recovery.md) and the [Operational Runbooks](../system-architecture-runbooks.md#🔄-recovery).
- Deployments use Helm charts but are triggered manually via [manual-helm-deploy.yml](../../../.github/workflows/manual-helm-deploy.yml), which runs `helm upgrade` with `k8s/helm/values-local.yaml` by default. Use `values-dev.yaml` or other values files for non-local clusters. See [CI/CD Pipeline](../system-architecture-cicd.md#🚢-deploying-to-kubernetes) for details.

A sample Terraform module for a local Kind cluster is provided in [k8s/terraform](../../../k8s/terraform). This demo module creates a `firemud` namespace and optional Redis Helm release for quick testing. Use `helm install` with the example charts in [k8s/helm](../../../k8s/helm) to deploy services locally.

- All tenants share this cluster with data separated by `tenantId` per service. See [Multi-Tenancy](../system-architecture-multi-tenancy.md) for more.

### 🩺 Kubernetes Health Monitoring

- Kubernetes uses Spring Boot’s `/actuator/health` for both:
  - **Readiness probes** — to determine if a service is ready to handle requests.
  - **Liveness probes** — to detect and restart stuck or unresponsive containers.

### 🔄 Kubernetes Auto Recovery

- Kubernetes automatically:
  - Removes unready pods from Services
  - Restarts failing pods based on probe failures
  - Scales services up/down via deployments or Horizontal Pod Autoscalers (HPA). An example manifest is provided in `k8s/base/hpa-example.yaml` but is not installed by default. (TODO: Not yet implemented)
- Pod restarts are transparent to players; see [Reconnection Strategy](../system-architecture-reconnection.md) for cross-environment behavior.

---

## 📈 Monitoring & Logging

FireMUD relies on a consistent observability stack across environments. The full stack is deployed in Kubernetes, while the Docker Compose setup does not yet include these components. (TODO: Not yet implemented)

Docker Compose and Kubernetes rely on the following monitoring tools:

### 🔧 Monitoring Stack

- Prometheus scrapes metrics from all services.
- Grafana dashboards visualize performance metrics.
- Alertmanager notifies on failures or latency spikes.
- OpenTelemetry spans are emitted by services for distributed tracing.

### 📜 Log Aggregation

- **Fluent Bit** agents collect container logs from each pod.
- **Elasticsearch** stores structured log data for long-term retention.
- **Kibana** dashboards allow operators to query logs using identifiers such as `traceId` and `playerId`.
  Log indices are kept for **14 days** in development and **90 days** in production by default.

See [Logging & Monitoring](../system-architecture-logging-monitoring.md) for details on the observability stack.

---

## 🔁 Spring Profile Configuration

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

## 🎮 Staging Environment for Playtesting

A dedicated staging cluster is planned to mirror production using smaller node sizes. (TODO: Not yet implemented) Pull requests currently spin up a short-lived Docker Compose stack via [preview.yml](../../../.github/workflows/preview.yml) so playtesters can evaluate changes. Test data resets nightly once the staging cluster is available.
For details on collecting tester feedback see [Playtesting & Feedback Plan](../../project-management/playtesting-feedback.md).

---

## 📚 Related Documentation

- [Infrastructure Overview](./README.md)
- [Gateway Architecture](../system-architecture-gateway.md)
- [Protocol Bridging](../system-architecture-protocol-bridging.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)
