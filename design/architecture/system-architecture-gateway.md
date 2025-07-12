# 🔀 Gateway Architecture

This document describes the role and configuration of **Spring Cloud Gateway** in the FireMUD platform, including routing, filtering, WebSocket support, and how it integrates with both modern and legacy clients.

---

## 🚪 Gateway Pattern

**Spring Cloud Gateway** serves as the **single entry point** into the FireMUD system for all **external client traffic**:

- Built as a Spring Boot microservice
- Handles **client** request routing, filtering, CORS, rate limiting, retries, and monitoring
- May validate JWTs for admin APIs (see [Authentication & Authorization](../system-architecture-authentication.md)). Gameplay login is processed by the **Game Session Service**; see [Authentication & Authorization](../system-architecture-authentication.md#-login-and-session-flow) for the detailed flow.
- Supports both HTTP and WebSocket protocols
- Deployed in both development and production environments
- **Stateless and horizontally scalable** – no sticky sessions required
- Telnet clients keep a **persistent TCP connection** to the TCP Proxy Service; the Gateway
  itself does not hold session state between reconnects

> **Important:**
> Spring Cloud Gateway is responsible for routing **only external client requests**.
> **Internal microservice-to-microservice communication does not pass through the Gateway**.
> Microservices use Kubernetes native service discovery and DNS for direct communication.
> Services communicate with each other over **gRPC**.
> See [System Architecture Overview](../system-architecture-overview.md) and [Authentication & Authorization](../system-architecture-authentication.md#-login-and-session-flow) for the complete login and gRPC flow.

- Static URIs in `application-dev.yml` (for Docker Compose)
- Kubernetes DNS-based service names in `application-prod.yml` (for production)

---

## 🔷 WebSocket Support

- WebSocket is used by modern clients (e.g., browser-based interfaces) for real-time interaction
- Spring Cloud Gateway supports **WebSocket proxying**, allowing connections to be routed to backend services (e.g., `game-session-service`)
- WebSocket connections benefit from:
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
- These connections are terminated by a **dedicated TCP Proxy Service** outside of Spring Cloud Gateway
- The TCP Proxy Service acts as a **bridge**, creating a WebSocket connection through the Gateway to normalize legacy TCP traffic
  - Spring Cloud Gateway itself only handles **HTTP** and **WebSocket** traffic

This pattern ensures all real-time gameplay is unified through WebSocket on the backend, regardless of client type.

---

## 🔐 Centralized Gateway Benefits

Spring Cloud Gateway provides centralized management of client traffic, offering:

- Optional JWT validation for admin or REST endpoints (gameplay clients do not
  provide JWTs)
- Cross-cutting filters (e.g., rate limiting, logging, CORS)
- Service isolation through route-based access control
- Easy expansion of routes for new microservices
- TLS termination and mTLS between services are described in [Security Architecture](../system-architecture-security.md).

## 🔗 Internal gRPC Communication

Internal services communicate directly with each other over **gRPC**.
Spring Cloud Gateway does not handle these calls. Each service discovers
its peers via Docker or Kubernetes DNS and connects using the service name.
This approach minimizes latency and matches the protocol table in the
[System Architecture Overview](../system-architecture-overview.md#🌐-communication-flows).

---

## 🔧 Dev vs. Prod Configuration

| Environment | Route Target Format      | Discovery Mechanism        |
|-------------|---------------------------|-----------------------------|
| Dev         | `http://service:8080`     | Docker Compose DNS          |
| Prod        | `http://service.namespace.svc.cluster.local:8080` | Kubernetes DNS |

Spring profiles (`application-dev.yml`, `application-prod.yml`) are used to configure routing targets based on environment.

---

## 📚 Related Documentation

- [Infrastructure Overview](./infrastructure/README.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Protocol Bridging](./system-architecture-protocol-bridging.md)
- [Reconnection Strategy](../system-architecture-reconnection.md)

---
