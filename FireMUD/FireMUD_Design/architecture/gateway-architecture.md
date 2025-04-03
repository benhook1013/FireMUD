# 🔀 Gateway Architecture

This document describes the role and configuration of **Spring Cloud Gateway** in the FireMUD platform, including routing, filtering, WebSocket support, and how it integrates with both modern and legacy clients.

---

## 🚪 Gateway Pattern

**Spring Cloud Gateway** serves as the **single entry point** into the FireMUD system:

- Built as a Spring Boot microservice
- Handles routing, filtering, CORS, authentication, rate limiting, retries, and monitoring
- Supports both HTTP and WebSocket protocols
- Deployed in both development and production environments

Each backend microservice is registered as a route, either using:
- Static URIs in `application-dev.yml` (for Docker Compose)
- Kubernetes DNS-based service names in `application-prod.yml` (for production)

---

## 🔷 WebSocket Support

- WebSocket is used by modern clients (e.g., browser-based interfaces) for real-time interaction
- Spring Cloud Gateway supports **WebSocket proxying**, allowing connections to be routed to backend services (e.g., `game-session-service`)
- WebSocket connections benefit from:
  - Centralized authentication
  - Logging and metrics
  - Route-based filtering
  - Consistent handling across all clients

Example WebSocket route config:
```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: game-session
          uri: ws://game-session-service:8080
          predicates:
            - Path=/ws/game/**
```

---

## 🔌 Telnet / TCP Bridging

- Traditional MUD clients connect via **raw TCP** (Telnet protocol)
- These are handled by a **dedicated TCP gateway service** (outside of Spring Cloud Gateway)
- That service acts as a **bridge**, creating a WebSocket connection through the Gateway to normalize legacy TCP traffic

This pattern ensures all real-time gameplay is unified through WebSocket on the backend, regardless of client type.

---

## 🔐 Centralized Gateway Benefits

Spring Cloud Gateway allows:
- Centralized authentication (e.g., OAuth2, JWT)
- Cross-cutting filters (e.g., rate limiting, logging, CORS)
- Service isolation through route-based access control
- Easy expansion of routes for new microservices

---

## 🔧 Dev vs. Prod Configuration

| Environment | Route Target Format      | Discovery Mechanism        |
|-------------|--------------------------|-----------------------------|
| Dev         | `http://service:8080`    | Docker Compose DNS         |
| Prod        | `http://service.namespace.svc.cluster.local:8080` | Kubernetes DNS |

Spring profiles (`application-dev.yml`, `application-prod.yml`) are used to configure routing targets based on environment.

---

## 📚 Related Docs

- [Infrastructure Overview](./infrastructure-overview.md)
- [Deployment Environments](./deployment-environments.md)
- [Protocol Bridging](./protocol-bridging.md)
