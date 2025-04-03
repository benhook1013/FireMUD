# 🔧 FireMUD Gateway and Orchestration Overview

This document outlines the shared infrastructure and environment setup for all FireMUD microservices, including API routing via Spring Cloud Gateway and orchestration via Docker Compose (for development) and Kubernetes (for production).

---

## ✅ Gateway Pattern

You will always use **Spring Cloud Gateway** as the single entry point to your system:

- Built as a Spring Boot microservice
- Handles routing, filtering, rate limiting, CORS, authentication, etc.
- Provides WebSocket support for real-time communication
- Deployed alongside other services in both development and production environments

Each microservice defines route entries within the Gateway's config using either static URIs (in dev) or Kubernetes DNS (in prod).

---

## 🧪 Development Environment: Docker Compose

- **Platform**: Docker Compose
- **Build Strategy**: All microservices (including the gateway) are built locally via `Dockerfile`s
- **Service Discovery**: Uses Docker's internal DNS (`<service-name>:<port>`) for inter-service communication
- **Gateway Routing**: Static URIs are defined in `application-dev.yml`
- **Health Checks**:
  - Spring Boot’s `/actuator/health` endpoint is used for functional health checks
  - Docker Compose can monitor this with a `healthcheck` section per service
  - Health statuses appear in `docker ps` (e.g., `healthy`, `unhealthy`)
  - Docker does **not** auto-restart unhealthy containers by default

💡 **Note**: Docker's `depends_on` only controls container start order and does not wait for services to become healthy. For better startup resilience, use **Gateway retry filters** or helper scripts like `wait-for-it.sh`.

---

## ☁️ Production Environment: Kubernetes

- **Platform**: Kubernetes (e.g., EKS, GKE, or self-hosted)
- **Service Discovery**: Kubernetes Services expose stable DNS-based endpoints (`<service>.<namespace>.svc.cluster.local`)
- **Gateway Routing**: Uses service names or full DNS entries in `application-prod.yml`
- **Health Monitoring**:
  - Spring Boot's `/actuator/health` is used for **readiness** and **liveness** probes
  - Kubernetes will:
    - Remove unready pods from Services (via readinessProbe)
    - Restart unhealthy pods automatically (via livenessProbe)

---

## 🔁 Spring Profile Separation

Services use Spring profiles to cleanly separate config by environment:

- `application-dev.yml` — For local development with Docker Compose
- `application-prod.yml` — For deployment in Kubernetes

These profiles define the correct routing URIs, environment-specific settings, and optional feature toggles (e.g., in-memory dev databases vs. cloud-hosted ones).

---

## 🧠 Summary Table of Environment Behavior

| Feature                    | Dev (Docker Compose)          | Prod (Kubernetes)              |
|----------------------------|-------------------------------|-------------------------------|
| Gateway-based routing      | ✅ Always                     | ✅ Always                     |
| Discovery mechanism        | Docker DNS                    | K8s Service DNS              |
| Health checks              | `/actuator/health` via Docker | `/actuator/health` via probes |
| Auto service restarts      | ❌ Manual unless scripted     | ✅ Handled by K8s             |
| Load balancing             | Basic DNS                     | Handled by K8s Services       |
| Build strategy             | Local Dockerfiles             | CI/CD or K8s-native images    |
| Route configuration        | Static hostnames              | DNS-based service names       |

---

## 🔌 Protocol Support: WebSocket vs. Telnet (TCP)

FireMUD supports two distinct client protocols, enabling both modern web clients and traditional MUD clients to interact with the platform:

### 🔷 WebSocket (via Gateway)

- Modern clients (e.g., browser-based UI) connect via **WebSocket**.
- Spring Cloud Gateway supports **WebSocket proxying**, allowing real-time connections to route through it just like HTTP.
- These connections are forwarded to backend services like `game-session-service`, which handle game logic over WebSocket.
- This allows WebSocket connections to benefit from **centralized auth, logging, and routing** provided by Gateway.

### 🔶 Telnet / Raw TCP

- Legacy MUD clients (e.g., MUDlet, TinTin++) connect using **raw TCP (typically Telnet protocol)**.
- These connections are accepted by a dedicated **TCP gateway service**, which acts as a translation layer.
- The TCP service **establishes a WebSocket connection** to the internal backend (e.g., via Spring Cloud Gateway), allowing Telnet clients to be normalized into the same architecture as WebSocket clients.
- This approach allows traditional clients to be supported **without duplicating backend logic**.

> ✅ With this hybrid design, FireMUD supports both modern and legacy clients through a unified backend session service, maintaining clean separation of protocols while maximizing code reuse.

---

## 📌 Usage in Service Design Files

Rather than duplicating environment details in every service’s design file, refer to this document:

> See [**Gateway and Orchestration Overview**](../env/gateway_and_orchestration.md) for shared infrastructure, routing, and runtime behavior across environments.

Each service should only document its **specific external dependencies** (e.g., Redis, a DB, Kafka, etc.), not shared environment details.
