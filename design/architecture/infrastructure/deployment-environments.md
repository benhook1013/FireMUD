# 🚀 Deployment Environments

This document outlines how FireMUD is deployed across different environments, focusing on **Docker Compose** for local development and **Kubernetes** for production. It includes discovery mechanisms, health check strategies, and environment-specific configurations.

---

## 🧪 Local Development: Docker Compose

FireMUD uses Docker Compose for local development and testing:

### 🔧 Docker Compose Characteristics

- All services, including the gateway, are built locally via `Dockerfile`s.
- Docker Compose orchestrates container startup, but not readiness.
- Service discovery is handled by Docker's internal DNS (e.g., `game-session-service:8080`).
- Route URIs in Spring Cloud Gateway use static hostnames defined in `application-dev.yml`.
- Connection settings for PostgreSQL and Redis are loaded from a `.env` file.
  A sample `.env.sample` is provided with default credentials.

### 🩺 Docker Health Checks

- Services expose Spring Boot’s `/actuator/health` for basic health status.
- Docker Compose can monitor health using `healthcheck` blocks in `docker-compose.yml`.
- Health status is visible via `docker ps` (e.g., `healthy`, `unhealthy`), but:
  - Docker does **not** automatically restart unhealthy containers by default.
  - Docker’s `depends_on` only controls startup order, not service readiness.
  - See [Reconnection Strategy](../system-architecture-reconnection.md) for how sessions survive service restarts in Docker Compose.

💡 **Tip**: For more reliable startup coordination, use **Gateway retry filters** or utilities like `wait-for-it.sh`.

---

## ☁️ Production: Kubernetes

In production, FireMUD is deployed into Kubernetes (e.g., AWS EKS, Google GKE, or self-managed clusters).

### 🔧 Kubernetes Characteristics

- Services are deployed as Pods and exposed via Kubernetes Services.
- DNS-based discovery is built into Kubernetes (e.g., `game-session-service.default.svc.cluster.local`).
- Route URIs in Spring Cloud Gateway use service names configured in `application-prod.yml`.
- Internal microservices communicate directly over gRPC, bypassing the Spring Cloud Gateway.
- The **TCP Proxy Service** and **Spring Cloud Gateway** are typically exposed using Kubernetes `LoadBalancer` Services so external clients can connect directly.
  - The TCP Proxy Service buffers active Telnet input but clears it when the TCP connection closes. Sticky TCP sessions terminate here. See [Gateway Architecture](../system-architecture-gateway.md) for how the stateless Gateway handles reconnects.
- The external load balancer exposes only the Gateway and TCP Proxy Service, forming a DMZ that shields internal services.
- See [Security Architecture](../system-architecture-security.md) for TLS termination, mTLS certificates, and network policy details.
- Configuration and secrets are managed through ConfigMaps and Secrets.
- Certificates for TLS termination and mTLS are issued by **cert-manager** and mounted from Kubernetes Secrets.
- The cluster uses **IPVS** (or a similar load-balancing mode) to route service traffic efficiently.
- Redis runs as a clustered StatefulSet with automatic failover. Details are in [Redis Architecture](../system-architecture-redis.md). Redis persistence uses **AOF**, as described there.
- PostgreSQL is deployed within the cluster (or provided as a managed database service) to store persistent domain data. See [System Architecture Overview](../system-architecture-overview.md#📦-data-and-state-management). Backup and restore procedures are outlined in [Backup & Disaster Recovery](../system-architecture-backup-recovery.md).
- Deployments are managed via Helm charts in the CI/CD pipeline. See [CI/CD Pipeline](../system-architecture-cicd.md#🚢-deploying-to-kubernetes) for details.

A sample Terraform module for a local Kind cluster is provided in [k8s/terraform](../../k8s/terraform). This demo module creates a `firemud` namespace and optional Redis Helm release for quick testing. Use `helm install` with the example charts in [k8s/helm](../../k8s/helm) to deploy services locally.

- All tenants share this cluster with data separated by `tenantId` per service. See [Multi-Tenancy](../system-architecture-multi-tenancy.md) for more.

### 🩺 Kubernetes Health Monitoring

- Kubernetes uses Spring Boot’s `/actuator/health` for both:
  - **Readiness probes** — to determine if a service is ready to handle requests.
  - **Liveness probes** — to detect and restart stuck or unresponsive containers.

### 🔄 Kubernetes Auto Recovery

- Kubernetes automatically:
  - Removes unready pods from Services
  - Restarts failing pods based on probe failures
  - Scales services up/down via deployments or Horizontal Pod Autoscalers (HPA)
- Pod restarts are transparent to players; see [Reconnection Strategy](../system-architecture-reconnection.md) for cross-environment behavior.

### 📈 Monitoring Stack

- Prometheus scrapes metrics from all services.
- Grafana dashboards visualize performance metrics.
- Alertmanager notifies on failures or latency spikes.
- OpenTelemetry spans are emitted by services for distributed tracing.

See [Logging & Monitoring](../system-architecture-logging-monitoring.md) for details on the monitoring stack.

### 📜 Log Aggregation

- **Fluent Bit** agents collect container logs from each pod.
- **Elasticsearch** stores structured log data for long-term retention.
- **Kibana** dashboards allow operators to query logs using identifiers such as `traceId` and `playerId`.

See [Logging & Monitoring](../system-architecture-logging-monitoring.md) for details on the logging stack.

---

## 🔁 Spring Profile Configuration

Spring Boot services use environment-specific profiles:

- `application-dev.yml`:
  - Used with Docker Compose
  - Static URI-based routing
  - Dev-mode databases or in-memory stores

- `application-prod.yml`:
  - Used in Kubernetes
  - DNS-based routing to Kubernetes Services
  - Integration with persistent infrastructure such as the PostgreSQL cluster

## 🎮 Staging Environment for Playtesting

A minimal staging cluster mirrors production but uses smaller node sizes. Pull requests can deploy preview versions so playtesters can experiment without affecting live games. Test data resets nightly.

---

## 📚 Related Documentation

- [Infrastructure Overview](./README.md)
- [Gateway Architecture](../system-architecture-gateway.md)
- [Protocol Bridging](../system-architecture-protocol-bridging.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)
