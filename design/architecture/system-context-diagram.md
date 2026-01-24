# FireMUD System Context Diagram

This document gives a high-level view of how FireMUD's clients, gateways, internal services, and infrastructure fit together. Use it as an orientation map before diving into the more detailed architecture and microservice design documents.

```plaintext
                +----------------------+                  +-------------------+
                |      Web Client      |                  |   Telnet Client   |
                +----------------------+                  +-------------------+
                            |                                       |
                            | HTTP/WebSocket                        | TCP
                            v                                       v
                +----------------------+                  +-------------------+
                | External Load        |                  | Telnet Edge Proxy |
                | Balancer / Ingress   |                  |   (HAProxy, etc.) |
                +----------------------+                  +-------------------+
                            |                                       |
                            | HTTP/WebSocket                        | TCP/PROXY
                            v                                       v
                +----------------------+                  +-------------------+
                | Spring Cloud Gateway | <--------------- | TCP Proxy Service |
                |         (DMZ)        |  HTTP/WebSocket  |       (DMZ)       |
                +----------------------+                  +-------------------+
                            |
                            | WebSocket (gameplay)
                            v
      +-------------------------------------------+          +-------------------------------------------+
      |             Internal Services             |          |           Email / SMTP Provider           |
      |                                           |          +-------------------------------------------+
      | - Game Session Service                    |                                ^
      | - Account Service                         |                                |
      | - Entity Management Service               |                                |
      | - Game Logic Service                      |                                |
      | - World Management Service                |                                |
      | - Automation & Scripting Service          |                                |
      | - Social & Groups Service                 |                                | Alerts → Email /
      | - Logging & Admin Service                 |                                |          SMTP Provider
      | - Game Design Service                     |                                |
      +-------------------------------------------+                                |
                            |                                                      |
                            | DB/Cache/Logs                                        |
                            v                                                      |
      +-------------------------------------------+          +-------------------------------------------+
      |               Datastore Layer             |--------->|            Observability Stack            |
      |                                           | Metrics/ |                                           |
      | - PostgreSQL (shared cluster; per-service | Traces   | - Prometheus (metrics)                    |
      |   schemas, tenant-scoped tables)          |          |                                           |
      | - Redis (sessions, ticks, caches,         |          | - OpenTelemetry Collector (traces)        |
      |   rate limits; coordination vs cache      |          | - Jaeger (trace UI)                       |
      |   keyspaces)                              |          | - Grafana (metrics dashboards)            |
      | - Elasticsearch (logs)                    |          | - Kibana (log UI)                         |
      | - S3-compatible object storage (assets)   |          | - Alertmanager (alerts)                   |
      +-------------------------------------------+          +-------------------------------------------+

      Account Service ---------------------------> Email / SMTP Provider
      Logging & Admin Service -------------------> Email / SMTP Provider
```

Admin and operations tools connect to Spring Cloud Gateway over an internal gRPC management API for route configuration and health checks, separate from player-facing HTTP/WebSocket traffic. From there, admin commands and moderation actions are forwarded to domain services (for example, Game Session, Account, Social & Groups) over gRPC, following the ownership boundaries in the Service Responsibility Matrix.

Only the Account Service and Logging & Admin Service send email directly to the SMTP provider; other internal services surface email-worthy events through these owners rather than talking to SMTP themselves. This matches the responsibilities defined in the Service Responsibility Matrix.

## Related Documentation

- [Gateway Architecture](./system-architecture-gateway.md)
- [System Architecture Diagram](./system-architecture-diagram.md)
