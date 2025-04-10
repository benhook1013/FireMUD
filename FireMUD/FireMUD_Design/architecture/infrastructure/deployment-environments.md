# 🚀 Deployment Environments

This document outlines how FireMUD is deployed across different environments, focusing on **Docker Compose** for local development and **Kubernetes** for production. It includes discovery mechanisms, health check strategies, and environment-specific configurations.

---

## 🧪 Local Development: Docker Compose

FireMUD uses Docker Compose for local development and testing:

### 🔧 Docker Compose Characteristics

- All services, including the gateway, are built locally via `Dockerfile`s.
- Service discovery is handled by Docker's internal DNS (e.g., `game-server:8080`).
- Route URIs in Spring Cloud Gateway use static hostnames defined in `application-dev.yml`.
- Docker Compose orchestrates container startup, but not readiness.

### 🩺 Docker Health Checks

- Services expose Spring Boot’s `/actuator/health` for basic health status.
- Docker Compose can monitor health using `healthcheck` blocks in `docker-compose.yml`.
- Health status is visible via `docker ps` (e.g., `healthy`, `unhealthy`), but:
  - Docker does **not** automatically restart unhealthy containers by default.
  - Docker’s `depends_on` only controls startup order, not service readiness.

💡 **Tip**: For more reliable startup coordination, use **Gateway retry filters** or utilities like `wait-for-it.sh`.

---

## ☁️ Production: Kubernetes

In production, FireMUD is deployed into Kubernetes (e.g., AWS EKS, Google GKE, or self-managed clusters).

### 🔧 Kubernetes Characteristics

- Services are deployed as Pods and exposed via Kubernetes Services.
- DNS-based discovery is built into Kubernetes (e.g., `game-server.default.svc.cluster.local`).
- Route URIs in Spring Cloud Gateway use service names configured in `application-prod.yml`.
- Configuration and secrets are managed through ConfigMaps and Secrets.

### 🩺 Kubernetes Health Monitoring

- Kubernetes uses Spring Boot’s `/actuator/health` for both:
  - **Readiness probes** — to determine if a service is ready to handle requests.
  - **Liveness probes** — to detect and restart stuck or unresponsive containers.

### 🔄 Kubernetes Auto Recovery

- Kubernetes automatically:
  - Removes unready pods from Services
  - Restarts failing pods based on probe failures
  - Scales services up/down via deployments or Horizontal Pod Autoscalers (HPA)

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
  - Integration with persistent infrastructure (e.g., cloud-hosted databases)

---

## 📚 Related Docs

- [Infrastructure Overview](./_index.md)
- [Gateway Architecture](./gateway-architecture.md)
- [Protocol Bridging](./protocol-bridging.md)
