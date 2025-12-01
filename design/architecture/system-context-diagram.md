# 📊 FireMUD System Context Diagram

This document gives a high-level view of how FireMUD's clients, gateways, internal services, and infrastructure fit together. Use it as an orientation map before diving into the more detailed architecture and microservice design documents.

```plaintext
                +----------------------+                  +-------------------+
                |      Web Client      |                  |   Telnet Client   |
                +----------------------+                  +-------------------+
                            |                                       |
                            | HTTP/WebSocket                        | TCP
                            v                                       v
                +----------------------+                  +-------------------+
                | Spring Cloud Gateway | <--------------- | TCP Proxy Service |
                |         (DMZ)        |  HTTP/WebSocket  |       (DMZ)       |
                +----------------------+                  +-------------------+
                            |
                            | gRPC/WebSocket
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
      | - PostgreSQL (per service)                | Traces   | - Prometheus (metrics)                    |
      | - Redis (sessions, ticks)                 |          | - OpenTelemetry Collector (traces)        |
      | - Elasticsearch (logs)                    |          | - Jaeger (trace UI)                       |
      | - S3-compatible object storage (assets)   |          | - Grafana (metrics dashboards)            |
      +-------------------------------------------+          | - Kibana (log UI)                         |
                                                             | - Alertmanager (alerts)                   |
                                                             +-------------------------------------------+
```

## 📚 Related Documentation

- [System Architecture Diagram](./system-architecture-diagram.md)
- [Gateway Architecture](./system-architecture-gateway.md)
