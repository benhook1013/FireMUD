# Spring Cloud Gateway

## Overview

This service exposes WebSocket and HTTP endpoints for all clients. It routes requests to backend services and integrates with the TCP Proxy Service for Telnet clients.

An OpenAPI specification for these REST endpoints lives in `services/spring-cloud-gateway/src/main/resources/openapi.yaml`.

> **Canonical ownership:** This Spring Cloud Gateway doc set is the authoritative source for:
>
> - Gameplay gateway client behavior at `/ws/game/**`, including trusted TCP Proxy bridge admission and handshake/close classification.
> - Gateway-owned diagnostics and dev/test-only route-mutation API contracts.
> - Gateway-local configuration sources, Redis role boundaries, and route-state expectations.
> - Gateway observability and readiness expectations for route admission.
>
> Other docs should summarize behavior and link back here instead of redefining gateway-specific contracts.

## Responsibilities

- Enforce the presence of an `Authorization` header for protected admin routes while leaving JWT parsing and validation to downstream services.
- Upgrade WebSocket connections and forward them to backend services.
- Keep the edge connection stable during [ADR 0013](../../decisions/adr-0013-bounded-invisible-non-edge-restart-recovery.md)'s qualifying non-edge failures, target ordinary upstream recovery within 10 seconds, and close with `1013/backend_unavailable` when safe recovery cannot complete within the 30-second hard window.
- Apply rate limits and basic abuse protections at the gateway boundary.
- Relay gameplay and admin traffic to the correct backend services.
- Expose internal-only diagnostic gRPC management endpoints such as `Ping` on port `6565` over mTLS-authenticated internal network surfaces; route-mutation methods remain dev/test-only and are absent or disabled in player-facing environments.
- Fail readiness for new gameplay traffic when the `/ws/game/**` route is not safe to admit.
- Translate typed upstream lifecycle outcomes into the canonical external close taxonomy; TCP Proxy applies the equivalent Telnet token and does not invent a second mapping.

## Readiness and Liveness

- `liveness` is local-only and indicates that the gateway process is alive and able to continue serving.
- `readiness` is route-admission safety. For the currently implemented gameplay slice, the gateway is ready only when:
  - baseline route configuration is loaded; and
  - the `/ws/game/**` gameplay path can pass the same admission filters, upgrade, and forward to Game Session successfully enough for new gameplay sockets to be admitted.
- The gameplay-route canary is bounded and operation-shaped. It validates the actual `/ws/game/**` upgrade path with an explicit short timeout rather than relying on unrelated ping-style checks or long retry budgets. In player-facing environments, connect-token replay protection is part of admission safety; if the replay-protection store is unavailable and the route would fail closed with `CONNECT_REPLAY_PROTECTION_UNAVAILABLE`, the gameplay route is not ready.
- Retry filters are resilience mechanisms, not readiness compensation. A gateway that still needs startup retries to survive ordinary new gameplay admission is not ready.
- Readiness transition observability uses the shared contract from [Deployment Environments](../../infrastructure/deployment-environments.md): `firemud.readiness.current`, `firemud.readiness.transitions`, and structured logs keyed by the curated dependency name `gameplayRoute`.

## Implementation Status

This document describes the behaviour of Spring Cloud Gateway in its target architecture. Where implementation is still catching up, treat this doc set and the linked cross-service architecture docs as the source of truth and reconcile code/tests accordingly.

| Area | Target behaviour | Current status |
| --- | --- | --- |
| Baseline route authority | The released declarative route catalog is the single player-facing route authority and is imported at startup. | Not converged: current baseline routes are Java-owned in `CanonicalGatewayRoutesConfiguration`; the target `routes.yml` resource and import do not exist yet. |
| Dynamic route management | REST and gRPC route mutation APIs are dev/test-only overrides on top of the released route catalog. Player-facing environments reject dynamic mutation configuration at startup; production operators have diagnostics only, and route changes use the separately accepted declarative deployment workflow. Any future production runtime route-control plane requires a new architecture decision and must remain consistent with the [canonical authorization route matrix](../../system-architecture-authz-route-matrix.md). | Partially converged: mutation is disabled by default; explicit enablement requires exclusively active `dev`/`test` profiles or startup fails; REST and gRPC writes are service-guarded, and REST is authenticated. Complete protected-route, destination, predicate, and filter allowlist validation remains unimplemented. |
| Rate limiting and Redis wiring | Gateway rate limiting uses Spring Cloud Gateway `RequestRateLimiter` backed by the Cache/Rate-Limit Redis role, with gameplay abuse policy split across Gateway, TCP Proxy, and Game Session. | Implemented. |
| TCP Proxy bridge admission | Traffic from the TCP Proxy Service always targets `/ws/game/**`; player-facing bridge authority requires the exclusive environment-bound mTLS identity contract in [ADR 0169](../../decisions/adr-0169-exclusive-environment-bound-tcp-proxy-trust.md). | Header canonicalization and certificate matchers exist, but target-state player-facing proof is incomplete: hosted values still use plaintext plus pod-CIDR trust, the nominal base mTLS Service does not demonstrate a distinct TLS listener, and configured identity modes are not yet exclusive. |
| WebSocket close and handshake observability | Target-only typed Game Session → Gateway lifecycle intent feeds Gateway’s sole external WebSocket close-translation owner: `logout`, `session_replaced`, `service_restart`, `idle_timeout`, `policy_violation`, `internal_error`, and `backend_unavailable` are bounded top-level classes; bridge closes also carry `bridge_shutdown_class=planned_drain\|valid_upstream_close\|unattributed_failure` and optional diagnostic subreason. | Partially implemented at the current bridge and first-party handshake boundary. The typed lifecycle-intent contract is not implemented or versioned; unknown or absent intent must fail closed as `internal_error` at the public WebSocket boundary. `session_replaced`/`service_restart` convergence, neutral valid-upstream attribution, ADR 0013's elapsed-time cutoff, bounded input stall/rebind path, and terminal-versus-rebindable upstream classification remain gaps. See [Gateway Architecture](../../system-architecture-gateway.md#canonical-close-translation-matrix). |

## Documentation Map

Canonical Spring Cloud Gateway documentation is split by concern:

- [client-behavior.md](./client-behavior.md)
  - Gameplay WebSocket admission, trusted TCP Proxy bridge behavior, reconnect and restart expectations, route allowlist, and filter-chain behavior visible to clients and backend services.
- [api-contracts.md](./api-contracts.md)
  - Gateway-owned REST and gRPC management APIs, management plane security, endpoint examples, and proto ownership.
- [configuration.md](./configuration.md)
  - Configuration sources, route-state model, Redis role boundaries, TLS/config invariants, and service dependencies.
- [operations.md](./operations.md)
  - Operational expectations, readiness/observability hooks, and gateway metrics/logging contracts.

## Quick Canonical Links

These are the primary canonical references for gateway behaviour:

- [client-behavior.md#gameplay-route-behavior](./client-behavior.md#gameplay-route-behavior)
- [client-behavior.md#websocket-close-and-handshake-classification](./client-behavior.md#websocket-close-and-handshake-classification)
- [api-contracts.md#dynamic-route-management-target-state](./api-contracts.md#dynamic-route-management-target-state)
- [configuration.md#configuration-sources](./configuration.md#configuration-sources)
- [operations.md#metrics-and-observability-contract](./operations.md#metrics-and-observability-contract)

## Related Documentation

- [System Architecture Overview](../../system-architecture-overview.md)
- [Gateway Architecture](../../system-architecture-gateway.md)
- [Reconnection Strategy](../../system-architecture-reconnection.md)
- [Security Architecture](../../system-architecture-security.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
- [User Journeys – Player Login and Gameplay](../../../product/user-journeys/players.md#4-player-login-and-gameplay)
- [gRPC API Style & Versioning Guidelines](../../system-architecture-grpc.md)
- [Shared Libraries Overview](../../system-architecture-shared-libraries.md)
- [Testing Strategy](../../system-architecture-testing.md)
- [CI/CD Pipeline](../../system-architecture-cicd.md)
- [Logging & Monitoring](../../system-architecture-logging-monitoring.md)
- [Backup & Disaster Recovery](../../system-architecture-backup-recovery.md)
- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)
