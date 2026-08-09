# FireMUD System Context Diagram

This document gives a high-level view of how FireMUD's clients, gateways, internal services, and infrastructure fit together. Use it as an orientation map before diving into the more detailed architecture and microservice design documents.

This diagram shows the target traffic-plane shape, not proof of deployment readiness: internal infrastructure management, external admin/creator APIs through Gateway, and player HTTP/WebSocket/Telnet traffic remain separate planes. Detailed ownership and readiness rules stay in [Gateway Architecture](./system-architecture-gateway.md), [Authentication & Authorization](./system-architecture-authentication.md), and the linked trackers.

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

Plane definitions:

- `infrastructure management plane`: Internal automation and operator clients use Gateway's gRPC surface for approved development/test-only route configuration and health checks; it is separate from the `player traffic plane` and uses the separate Gateway-management authentication contract. Infrastructure-management route overrides must not mutate production player-facing routes.
- `external admin/creator API plane`: Operator and creator UIs use HTTP(S) through Gateway for route-matrix entries explicitly marked edge-routable; it is separate from the `infrastructure management plane`.
- `player traffic plane`: Player-facing HTTP, WebSocket, and Telnet traffic used for gameplay admission and live play; it is separate from the infrastructure-management gRPC surface.

## Implementation Status

Current Gateway/player readiness is partial: checked-in Gateway route/header-trust and WebSocket/Telnet bridge seams are bounded implementation, while route-catalog convergence, the public Telnet TLS boundary, and complete player-facing deployment proof remain gaps. See the [platform operations and delivery tracker](../project-management/implementation-tracking/platform-operations-and-delivery.md), [player access and session tracker](../project-management/implementation-tracking/player-access-and-session.md), and [realm-routing tracker](../project-management/implementation-tracking/realm-routing-and-playable-state.md) for current status.

Context callouts:

- External operator writes do not bypass Logging & Admin even when the owning domain service is edge-routable for reads or other explicitly documented bypass-safe workflows.
- External admin/creator HTTP(S) exposure is limited to route-matrix entries explicitly marked edge-routable; service-family prefixes and the diagram's domain-admin arrows are not blanket exposure. World Management, Entity Management, Game Logic, and Automation & Scripting remain internal-only by default.
- External admin/creator UIs call services through Gateway, not Logging & Admin directly, so Gateway applies routing, coarse route protections, and rate limiting. JWT middleware applies only to these external admin/creator HTTP APIs; fine-grained authorization remains with consuming services, while infrastructure-management traffic uses the separate Gateway-management authentication contract.
- Auth contracts are route-group-specific: gameplay WebSocket routes (`/ws/game/**`) follow the connect-token plus `LOGIN`/`PLAY` flow in [Authentication & Authorization](./system-architecture-authentication.md#websocket-connect-token-contract-wsgame) and [Reconnection Strategy](./system-architecture-reconnection.md#gameplay-websocket-route-handshake-policy-normative); external admin/creator HTTP APIs follow JWT middleware application in [Authentication & Authorization](./system-architecture-authentication.md#auth-middleware-application-normative) and canonical route classification in the [Authorization Route Matrix](./system-architecture-authz-route-matrix.md#classification-rules); internal infrastructure-management Gateway gRPC does not use that external JWT middleware and follows its separate authentication contract.
- External domain gRPC is not part of the edge contract unless a dedicated design update adds it. Internal service-to-service calls, including gRPC, do not traverse Gateway.
- External mutating operator workflows such as moderation actions, quota overrides, runtime feature-flag overrides, and tick remediation must enter through Logging & Admin. Direct domain-admin routes are limited to route-matrix-allowlisted reads and explicitly documented bypass-safe workflows.
- Canonical room-state reads: World Management emits the read fence used to align room occupancy with Entity Management containment/presentation. Game Logic owns same-fence composition and must reject mixed or missing fences instead of allowing best-effort room assembly; Game Session initiates the read and renders/caches the resulting transcript.
- For production-like control-plane constraints, including dynamic route override dev/test scope, see the canonical [Infrastructure Management Plane Capability Matrix](./system-architecture-overview.md#infrastructure-management-plane-capability-matrix-canonical).

Among application microservices, only the Account Service and Logging & Admin Service send email directly to the SMTP provider; other internal services surface email-worthy events through these owners rather than talking to SMTP themselves. Alertmanager, as part of the observability stack, may also send alerts via SMTP for infrastructure notifications. This matches the responsibilities defined in the Service Responsibility Matrix: Account Service owns account-centric and security-related emails (for example, verification, password reset, subscription and billing notifications), while Logging & Admin Service owns operational and moderation notifications (for example, alerts, escalations, moderation decisions, and admin digests).

## Related Documentation

- [Gateway Architecture](./system-architecture-gateway.md)
- [System Architecture Diagram](./system-architecture-diagram.md)
