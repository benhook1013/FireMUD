# 🌐 Environment Overview: Gateway and Orchestration

This document outlines the shared infrastructure used across all microservices in the FireMUD platform, including API routing, service orchestration, health monitoring, and environment-specific behavior.

---

## ✅ Gateway

All traffic to microservices flows through **Spring Cloud Gateway**, which acts as the central API gateway:

- **Routing**: Path-based and predicate-based routing to backend services
- **Filters**: Authentication, CORS, logging, retries, etc.
- **Rate Limiting**: (Optional) via Redis or token bucket filters
- **WebSocket Support**: For real-time features such as live game updates

Each microservice defines route entries within the Gateway's config using either static URIs (in dev) or service DNS (in prod).

---

## 🧪 Development Environment: Docker Compose

- **Service Discovery**: Handled via Docker's internal DNS (e.g., `game-server:8080`)
- **Build Strategy**: Each microservice, including the gateway, is built via local Dockerfiles
- **Routing**: Gateway uses static routing based on container hostnames
- **Health Checks**:
  - Spring Boot `/actuator/health` is used for service status
  - Docker Compose health checks reflect in `docker ps` output
  - Docker does not auto-restart unhealthy containers by default

---

## ☁️ Production Environment: Kubernetes

- **Service Discovery**: Kubernetes Services provide DNS-based discovery (`<service>.<namespace>.svc.cluster.local`)
- **Routing**: Gateway uses service names in `application-prod.yml`
- **Health Monitoring**:
  - Spring Boot’s `/actuator/health` is used for readiness and liveness probes
  - Kubernetes auto-removes unready pods from service endpoints
  - Unhealthy pods can be restarted automatically

---

## 🔁 Config Profile Strategy

Each service uses Spring Profiles to adapt to environment differences:

- `application-dev.yml`: Static routing for Docker Compose
- `application-prod.yml`: DNS-based routing for Kubernetes

---

## 🧠 Notes

- Services should **not use `depends_on` for every dependency** in Compose — instead, use Gateway retries or startup delay scripts if needed.
- Redis, databases, and messaging systems may also be orchestrated alongside the microservices in both environments.
