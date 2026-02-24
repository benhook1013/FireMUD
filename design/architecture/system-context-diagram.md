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
                            |
                            | WebSocket (gameplay)
                            v
      +-------------------------------------------+          +-------------------------------------------+
      |             Internal Services             |--------->|           Email / SMTP Provider           |
      |                                           |  Email   +-------------------------------------------+
      | - Game Session Service                    |                                ^
      | - Account Service                         |                                |
      | - Entity Management Service               |                                |
      | - Game Logic Service                      |                                |
      | - World Management Service                |                                |
      | - Automation & Scripting Service          |                                |
      | - Social & Groups Service                 |                                | Alerts
      | - Logging & Admin Service                 |                                |
      | - Game Design Service                     |                                |
      +-------------------------------------------+                                |
                            |                                                      |
                            | DB/Cache/Logs                                        |
                            v                                                      |
      +-------------------------------------------+          +---------------------------------------------+
      |               Datastore Layer             |--------->|            Observability Stack              |
      |                                           | Metrics/ |                                             |
      | - PostgreSQL (shared cluster; per-service | Traces   | - Prometheus (metrics)                      |
      |   schemas, tenant-scoped tables)          |          |                                             |
      | - Redis (coordination: sessions, ticks,   |          | - OpenTelemetry Collector (traces)          |
      |   locks, timers; production: separate     |          | - Jaeger (trace UI)                         |
      |   cluster)                                |          | - Grafana (metrics dashboards)              |
      | - Redis (cache/rate limits: caches,       |          | - Kibana (log UI)                           |
      |   quotas, rate limiting; production:      |          | - Alertmanager (alerts, email notifications)|
      |   separate cluster)                       |          |                                             |
      | - Elasticsearch (logs)                    |          +---------------------------------------------+
      | - S3-compatible object storage (assets)   |
      +-------------------------------------------+

      Account Service ---------------------------> Email / SMTP Provider
      Logging & Admin Service -------------------> Email / SMTP Provider
      Alertmanager ------------------------------> Email / SMTP Provider
```

Admin and operations tools connect to Spring Cloud Gateway over an internal gRPC management API for route configuration and health checks; this **infrastructure control-plane API** is separate from player-facing HTTP and WebSocket traffic. Admin and creator UIs call domain-level admin APIs (for example, moderation actions, feature-flag toggles, and dashboards) over HTTP(S) via the Gateway, which routes those requests to the owning domain services (for example, Game Session, Account, Social & Groups, Logging & Admin) following the Service Responsibility Matrix. External admin and creator tools do not call Logging & Admin Service directly; they always go through the Gateway so routing, coarse route protections, and rate limiting are applied consistently, while JWT validation and fine-grained authorization are performed by the consuming services. Internal service-to-service calls (including gRPC) do not traverse the Gateway.

Among application microservices, only the Account Service and Logging & Admin Service send email directly to the SMTP provider; other internal services surface email-worthy events through these owners rather than talking to SMTP themselves. Alertmanager, as part of the observability stack, may also send alerts via SMTP for infrastructure notifications. This matches the responsibilities defined in the Service Responsibility Matrix: Account Service owns account-centric and security-related emails (for example, verification, password reset, subscription and billing notifications), while Logging & Admin Service owns operational and moderation notifications (for example, alerts, escalations, moderation decisions, and admin digests).

## Related Documentation

- [Gateway Architecture](./system-architecture-gateway.md)
- [System Architecture Diagram](./system-architecture-diagram.md)
