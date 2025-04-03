# 🔧 FireMUD Gateway and Orchestration Overview

This document outlines the shared infrastructure and environment setup for all FireMUD microservices, including API routing via Spring Cloud Gateway, and service orchestration via Docker Compose (for development) and Kubernetes (for production).

---

## 🚪 Gateway Pattern

**Spring Cloud Gateway** is used as the single entry point into the FireMUD system:

- Built as a Spring Boot microservice
- Handles routing, filtering, rate limiting, CORS, authentication, and more
- Provides **WebSocket support** for real-time communication with modern clients (e.g., browsers), but **does not handle traditional MUD clients**, which connect over raw TCP (Telnet)
- Deployed alongside other services in both development and production environments

Each microservice defines route entries in the gateway configuration, using static URIs in development and Kubernetes DNS in production.

---

## 🧪 Development Environment: Docker Compose

- **Platform**: Docker Compose
- **Build Strategy**: All services (including the gateway) are built locally using `Dockerfile`s
- **Service Discovery**: Uses Docker’s internal DNS (`<service-name>:<port>`) for resolving containers
- **Gateway Routing**: Static URIs are defined in `application-dev.yml`
- **Health Checks**:
  - Services expose Spring Boot's `/actuator/health` for functional checks
  - Docker Compose can monitor these using `healthcheck` blocks per service
  - Health status is visible in `docker ps` (e.g., `healthy`, `unhealthy`)
  - Docker **does not** automatically restart unhealthy containers by default

💡 **Note**: `depends_on` in Compose only controls startup order, not readiness. Use **Gateway retry filters** or startup scripts like `wait-for-it.sh` for improved reliability.

---

## ☁️ Production Environment: Kubernetes

- **Platform**: Kubernetes (e.g., EKS, GKE, or self-hosted clusters)
- **Service Discovery**: Kubernetes Services expose DNS-based endpoints (e.g., `game-server.default.svc.cluster.local`)
- **Gateway Routing**: Uses internal service names or DNS entries defined in `application-prod.yml`
- **Health Monitoring**:
  - Each service exposes Spring Boot's `/actuator/health` endpoint
  - Kubernetes uses this for:
    - **Readiness probes** to determine if the service should receive traffic
    - **Liveness probes** to restart services that are stuck or failing

---

## 🔁 Spring Profile Separation

Services use Spring profiles to separate configuration for each environment:

- `application-dev.yml` — for local development with Docker Compose
- `application-prod.yml` — for production deployment in Kubernetes

These profiles define routing URIs, environment-specific service endpoints, and other behavioral toggles (e.g., database modes).

---

## 🧠 Environment Behavior Summary

| Feature                    | Development (Docker Compose)     | Production (Kubernetes)          |
|----------------------------|----------------------------------|----------------------------------|
| Gateway-based routing      | ✅ Always                        | ✅ Always                        |
| Service discovery          | Docker internal DNS             | Kubernetes Service DNS          |
| Health checks              | `/actuator/health` via Docker   | `/actuator/health` via probes   |
| Auto service restarts      | ❌ Manual unless scripted        | ✅ Handled automatically by K8s  |
| Load balancing             | Basic DNS-based                 | Native K8s Service-level        |
| Build strategy             | Local Dockerfiles               | CI/CD pipeline or K8s-native    |
| Route configuration        | Static hostnames                | DNS-based service names         |

---

## 🔌 Protocol Support: WebSocket vs. Telnet (TCP)

FireMUD supports two protocols to serve both modern and traditional MUD clients:

### 🔷 WebSocket (via Gateway)

- Modern clients (e.g., web-based UIs) connect using **WebSocket**
- Spring Cloud Gateway supports **WebSocket proxying**, enabling real-time communication
- Connections are routed to backend services (e.g., `game-session-service`) which handle game logic over WebSocket
- This allows modern clients to benefit from **centralized routing, authentication, logging**, and **rate limiting**

### 🔶 Telnet / Raw TCP

- Legacy MUD clients (e.g., MUDlet, TinTin++) connect using **raw TCP** via the Telnet protocol
- A dedicated **TCP gateway service** handles these connections
- This service internally **establishes a WebSocket connection** to the game backend (via Spring Cloud Gateway)
- Telnet clients are thus normalized into the same system as WebSocket clients, allowing shared backend logic

> ✅ This hybrid design allows FireMUD to support both traditional and modern clients with a unified backend, preserving compatibility and maximizing code reuse.

---

## 📌 Usage in Service Design Files

Rather than repeating infrastructure and environment setup in every service document, services should reference this overview:

> See [**Gateway and Orchestration Overview**](../env/gateway_and_orchestration.md) for details on shared infrastructure, including **Spring Cloud Gateway**, **Kubernetes**, and **Docker Compose**, as well as routing strategy and environment-specific runtime behavior.

Each service design file should only document **service-specific dependencies**, such as Redis, PostgreSQL, or Kafka.
