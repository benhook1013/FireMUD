# FireMUD System Context Diagram

This document gives a high-level view of how FireMUD's clients, gateways, internal services, and infrastructure fit together. Use it as an orientation map before diving into the more detailed architecture and microservice design documents.

```plaintext
                +----------------------+                  +-------------------+
                |      Web Client      |                  |   Telnet Client   |
                +----------------------+                  +-------------------+
                            |                                       |
                            | HTTP and WebSocket                        | TCP
                            v                                       v
                +----------------------+                  +-------------------+
                | External Load        |                  | Telnet Edge Proxy |
                | Balancer / Ingress   |                  |   (HAProxy, etc.) |
                +----------------------+                  +-------------------+
                            |                                       |
                            | HTTP and WebSocket                        | TCP/PROXY
                            v                                       v
                +----------------------+                  +-------------------+
                | Spring Cloud Gateway | <--------------- | TCP Proxy Service |
                |         (DMZ)        |    wss (mTLS)    |       (DMZ)       |
                +----------------------+                  +-------------------+
                            ^                                       
                            | Internal gRPC mgmt + HTTPS admin APIs
                +----------------------+
                | Admin / Operator     |
                | Tools                |
                +----------------------+
      +-------------------------------------------+                                +-------------------------------------------+
      |             Internal Services             |                                |           Email / SMTP Provider           |
      |                                           |                                +-------------------------------------------+
      | - Game Session Service                    |                                                   ^
      | - Account Service                         |                                                   |
      | - Entity Management Service               |                                                   |
      | - Game Logic Service                      |                                                   |
      | - World Management Service                |                                                   |
      | - Automation & Scripting Service          |                                                   |
      | - Social & Groups Service                 |                                                   | Alerts
      | - Logging & Admin Service                 |                                                   |
      | - Game Design Service                     |                                                   |
      +-------------------------------------------+                                                   |
                            |                                                                         |
                            | DB/Cache/Logs                                                           |
                            v                                                                         |
      +-------------------------------------------+          +---------------------------------------------+
      |               Datastore Layer             |--------->|            Observability Stack              |
      |                                           | Metrics/ |                                             |
      | - PostgreSQL (shared cluster; per-service | Traces   | - Prometheus (metrics)                      |
      |   schemas, tenant-scoped tables)          |          |                                             |
      | - Redis (coordination: gameplay session   |          | - OpenTelemetry Collector (traces)          |
      |   bindings, ticks, locks, timers,         |          | - Jaeger (trace UI)                         |
      |   retries, leases; production: separate   |          | - Grafana (metrics dashboards)              |
      |   cluster)                                |          |                                             |
      | - Redis (cache/rate limits: caches,       |          | - Kibana (log UI)                           |
      |   quotas, rate limiting; production:      |          | - Alertmanager (alerts, email notifications)|
      |   separate cluster)                       |          |                                             |
      | - Elasticsearch (logs)                    |          +---------------------------------------------+
      | - S3-compatible object storage (assets)   |
      +-------------------------------------------+

      Account Service ---------------------------> Email / SMTP Provider
      Logging & Admin Service -------------------> Email / SMTP Provider
      Alertmanager ------------------------------> Email / SMTP Provider

      Spring Cloud Gateway ---------------------> Logging & Admin Service (admin APIs)
      Spring Cloud Gateway ---------------------> Account Service (admin APIs)
      Spring Cloud Gateway ---------------------> Game Session Service (gameplay route group: `/ws/game/**`)
      Spring Cloud Gateway ---------------------> Game Session Service (control-plane/admin APIs)
      Spring Cloud Gateway ---------------------> Social & Groups Service (admin APIs)
      Spring Cloud Gateway ---------------------> Game Design Service (admin APIs)
```

Admin and operations tools connect to Spring Cloud Gateway over an internal gRPC management API for route configuration and health checks; this **infrastructure control-plane API** is separate from player-facing HTTP and WebSocket traffic. Admin and creator UIs call domain-level admin APIs over HTTP(S) via the Gateway only for the explicitly allowlisted edge-routable services: Logging & Admin, Account, Game Design, Game Session control APIs, and Social & Groups admin APIs. World Management, Entity Management, Game Logic, and Automation & Scripting remain internal-only by default. External admin and creator tools do not call Logging & Admin Service directly; they always go through the Gateway so routing, coarse route protections, and rate limiting are applied consistently, while JWT validation and fine-grained authorization are performed by the consuming services. External domain gRPC is not part of the edge contract unless a dedicated design update adds it. Internal service-to-service calls (including gRPC) do not traverse the Gateway.

Auth contracts by route group are explicit: gameplay WebSocket routes (`/ws/game/**`) follow the connect-token plus `LOGIN`/`PLAY` flow in [Authentication & Authorization](./system-architecture-authentication.md#websocket-connect-token-contract-wsgame) and [Reconnection Strategy](./system-architecture-reconnection.md#gameplay-websocket-route-handshake-policy-normative), while control-plane/admin APIs follow JWT middleware and route classification in [Authentication & Authorization](./system-architecture-authentication.md#auth-middleware-algorithm-normative) and [Authorization Route Matrix](./system-architecture-authz-route-matrix.md).

For production-like control-plane constraints (including dynamic route override dev/test scope), see the canonical [Gateway Management Plane Capability Matrix](./system-architecture-overview.md#gateway-management-plane-capability-matrix-canonical).

Among application microservices, only the Account Service and Logging & Admin Service send email directly to the SMTP provider; other internal services surface email-worthy events through these owners rather than talking to SMTP themselves. Alertmanager, as part of the observability stack, may also send alerts via SMTP for infrastructure notifications. This matches the responsibilities defined in the Service Responsibility Matrix: Account Service owns account-centric and security-related emails (for example, verification, password reset, subscription and billing notifications), while Logging & Admin Service owns operational and moderation notifications (for example, alerts, escalations, moderation decisions, and admin digests).

## Related Documentation

- [Gateway Architecture](./system-architecture-gateway.md)
- [System Architecture Diagram](./system-architecture-diagram.md)
