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

The single `Admin / Operator Tools -> Gateway` line in the ASCII diagram visually collapses two different actors and entry paths: internal infrastructure automation/operator clients use the `infrastructure management plane` for Gateway gRPC control actions, while external operator and creator UIs use the `external admin/creator API plane` for HTTP(S) traffic. These are not interchangeable surfaces.

Infrastructure automation and internal operators connect to Spring Cloud Gateway over the `infrastructure management plane`, an internal gRPC surface for route configuration and health checks that is separate from the `player traffic plane` and follows its separate Gateway-management authentication contract. Operator and creator UIs use the `external admin/creator API plane`: HTTP(S) calls routed through Gateway only for the explicitly allowlisted edge-routable services of Logging & Admin, Account, Game Design, Game Session control APIs, and Social & Groups admin APIs. World Management, Entity Management, Game Logic, and Automation & Scripting remain internal-only by default. External admin and creator tools do not call Logging & Admin Service directly; they always go through the Gateway so routing, coarse route protections, and rate limiting are applied consistently, while JWT middleware applies only to these external admin/creator HTTP APIs; internal infrastructure-management traffic follows the separate Gateway-management authentication contract. Fine-grained authorization is performed by the consuming services. External domain gRPC is not part of the edge contract unless a dedicated design update adds it. Internal service-to-service calls (including gRPC) do not traverse the Gateway. For external mutating operator workflows such as moderation actions, quota overrides, runtime feature-flag overrides, and tick remediation, Logging & Admin is the mandatory ingress path; direct domain-admin routes are for reads and explicitly documented bypass-safe workflows only.

Context callouts:

- External operator writes do not bypass Logging & Admin even when the owning domain service is edge-routable for reads or other explicitly documented bypass-safe workflows.
- Canonical room views are composed only after World Management emits a room-read fence, Entity Management returns a matching same-scope entity fence, and Game Logic verifies the two fences before composing the room view for Game Session to render.

Auth contracts by route group are explicit: gameplay WebSocket routes (`/ws/game/**`) follow the connect-token plus `LOGIN`/`PLAY` flow in [Authentication & Authorization](./system-architecture-authentication.md#websocket-connect-token-contract-wsgame) and [Reconnection Strategy](./system-architecture-reconnection.md#gameplay-websocket-route-handshake-policy-normative); external admin/creator HTTP APIs follow JWT middleware application in [Authentication & Authorization](./system-architecture-authentication.md#auth-middleware-application-normative) and canonical route classification in the [Authorization Route Matrix](./system-architecture-authz-route-matrix.md#classification-rules); internal infrastructure-management Gateway gRPC does not use that external JWT middleware and follows its separate authentication contract.

For production-like control-plane constraints (including dynamic route override dev/test scope), see the canonical [Gateway Management Plane Capability Matrix](./system-architecture-overview.md#gateway-management-plane-capability-matrix-canonical).

Among application microservices, only the Account Service and Logging & Admin Service send email directly to the SMTP provider; other internal services surface email-worthy events through these owners rather than talking to SMTP themselves. Alertmanager, as part of the observability stack, may also send alerts via SMTP for infrastructure notifications. This matches the responsibilities defined in the Service Responsibility Matrix: Account Service owns account-centric and security-related emails (for example, verification, password reset, subscription and billing notifications), while Logging & Admin Service owns operational and moderation notifications (for example, alerts, escalations, moderation decisions, and admin digests).

For canonical room-state reads, World Management emits the read fence used to align room occupancy with Entity Management containment/presentation. Game Logic owns same-fence composition and must reject mixed or missing fences instead of allowing best-effort room assembly; Game Session initiates the read and renders/caches the resulting transcript.

## Related Documentation

- [Gateway Architecture](./system-architecture-gateway.md)
- [System Architecture Diagram](./system-architecture-diagram.md)
