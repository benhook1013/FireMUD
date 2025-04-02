# 🔧 FireMUD Microservices Architecture Summary

## ✅ Gateway Pattern
You will always use **Spring Cloud Gateway** as the single entry point to your system:
- Built as a Spring Boot microservice
- Handles routing, filtering, rate limiting, CORS, authentication, etc.
- Runs in **both development and production**

---

## 🧪 Development Environment

- **Platform**: Docker Compose
- **Builds**: All microservices (including the gateway) are built via local `Dockerfile`s.
- **Service Discovery**: Uses **Docker Compose DNS** (e.g., `http://game-server:8080`)
- **Gateway Routes**: Use static hostnames/ports in `application-dev.yml`
- **Health Checks**:
  - Spring Boot’s `/actuator/health` is used for functional health reporting.
  - Docker can monitor this with `healthcheck` in `docker-compose.yml`.
  - Health status shows up in `docker ps` (e.g., `healthy`, `unhealthy`), but Docker doesn’t auto-restart based on it.

---

## ☁️ Production Environment

- **Platform**: Kubernetes
- **Service Discovery**: Done via **Kubernetes Services and internal DNS** (e.g., `game-server.default.svc.cluster.local`)
- **Gateway Routes**: Use service names or DNS in `application-prod.yml`
- **Health Checks**:
  - Kubernetes uses Spring Boot's `/actuator/health` endpoint for **readiness and liveness probes**.
  - Unhealthy pods are automatically removed from traffic or restarted.

---

## 🔁 Profile Separation

Use Spring profiles to cleanly switch config:
- `application-dev.yml` (Docker static routing)
- `application-prod.yml` (K8s DNS routing)

---

## 🧠 Key Benefits of This Setup

| Feature                     | Dev (Docker)                 | Prod (Kubernetes)             |
|----------------------------|------------------------------|-------------------------------|
| Gateway-based routing      | ✅ Always                    | ✅ Always                     |
| Discovery mechanism        | Docker DNS                  | K8s Service DNS              |
| Health checks              | `/actuator/health` via Docker | `/actuator/health` via probes |
| Auto service restarts      | ❌ Manual unless scripted     | ✅ Handled by K8s             |
| Load balancing             | Basic DNS                   | Handled by K8s services       |
