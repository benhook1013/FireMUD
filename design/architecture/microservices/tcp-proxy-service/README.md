# TCP Proxy Service

## Overview

Bridges legacy Telnet clients into the platform by converting raw TCP traffic into WebSocket connections for Spring Cloud Gateway. The OpenAPI specification for the `/ping` health endpoint lives in `services/tcp-proxy-service/src/main/resources/openapi.yaml`.

This service exposes an internal-only gRPC health check (`Ping`) for operators and tooling, and it uses an internal-only gRPC client to call the Game Session Service’s `NotifyDisconnect` event sink when Telnet connections close; neither surface is ever published through Spring Cloud Gateway.

For TCP Proxy’s position in the overall system (DMZ, Telnet edge, and WebSocket bridge to Spring Cloud Gateway), see the [System Architecture Diagram](../../system-architecture-diagram.md) and [System Context Diagram](../../system-context-diagram.md).

> **Canonical ownership:** This TCP Proxy doc set is the authoritative source for:
>
> - Telnet bridge metadata and header propagation.
> - `NotifyDisconnect` event semantics and layering guarantees.
> - Proxy metrics naming, bounded label taxonomies, and cardinality rules.
>
> Other docs should summarize behavior and link back here instead of redefining protocol details.

## Implementation Status

This document describes the behaviour of the TCP Proxy Service in its target architecture. Where implementation is still catching up, treat the design below and the linked subdocs as the source of truth and reconcile code/tests accordingly. A high-level implementation summary for this service lives in [`design/project-management/service-status-tcp-proxy-service.md`](../../../project-management/service-status-tcp-proxy-service.md).

When code, tests, and docs diverge, align implementation to this doc set and the canonical cross-service architecture docs unless the design itself is being updated in the same change.

| Area | Target behaviour | Current status | Tracked in |
| --- | --- | --- | --- |
| Telnet login-first flow | All Telnet clients may optionally browse `WORLDS` before login, then issue `LOGIN`, then `PLAY`. Telnet shares the same admission pipeline as WebSocket clients. Hidden smart-client attach hints may return later through MCP metadata only; they are not player-facing commands. `LOGIN` / `LOGON` semantics remain canonical in the Authentication & Authorization doc; this row only describes how Telnet traffic is forwarded into that flow. | Implemented. | `design/project-management/vertical-slices/02.2-task-list-gameplay-admission-ux-vertical-slice.md` |
| Proxy -> Gateway WebSocket mTLS | Telnet -> Gateway WebSocket client connects over `wss://` using mutual TLS and the dedicated `FIREMUD_GATEWAY_WS_*` client certificate paths (separate from the proxy’s gRPC server mTLS identity). | Implemented. Player-facing environments fail closed if client-certificate identity verification is unavailable. | `design/project-management/service-status-tcp-proxy-service.md` |
| MCP control-line handling and Telnet heuristics | MCP 2.1 control lines, Telnet heuristics, and connection throttling are enforced at the proxy edge while keeping MCP payloads intact. | Implemented. | `design/project-management/service-status-tcp-proxy-service.md` |
| Connection limits and abuse protection | Connection caps, idle timeouts, input size limits, and MCP/Telnet safety budgets protect the DMZ boundary. | Core limit handling is implemented; tuning and additional metrics may evolve as production behaviour is observed. | `design/project-management/service-status-tcp-proxy-service.md` |
| Telnet client IP preservation via PROXY protocol | Telnet client IPs are preserved by terminating public TCP on a Telnet edge proxy and forwarding to the TCP Proxy Service using PROXY protocol on an internal-only listener/port. | Implemented. In player-facing environments, PROXY protocol on the internal listener is required and the raw Telnet listener is never exposed directly to the Internet. | `design/project-management/service-status-tcp-proxy-service.md` |

## Responsibilities

- Accept Telnet connections and perform protocol negotiation.
- Proxy buffered input to Spring Cloud Gateway as WebSocket frames while the Telnet connection remains open.
- Provide graceful disconnect and reconnection handling.
- Refuse new user-facing traffic while the downstream gameplay path is not yet ready for first-session admission.

Plaintext/raw Telnet support in production is an intentional, non-removable requirement so that classic MUD clients which cannot speak TLS can connect. Reviews should treat that as an accepted tradeoff with documented hardening, not as a defect to remove.

## Readiness and Liveness

- `liveness` is process-local only: the Spring Boot process is alive, the Netty event loops are not wedged, and the service can continue running.
- `readiness` is traffic-admission safety for new Telnet sessions. The service is ready only when:
  - the Telnet listener is bound;
  - the proxy can reach Spring Cloud Gateway’s readiness surface for the gameplay route; and
  - the current downstream gameplay admission path is safe for `connect -> LOGIN -> first LOOK`.
- While unready, the proxy must reject new Telnet sessions immediately with an explicit startup/unavailable message and close the connection. It must not silently accept the socket and let the first gameplay command discover startup races later.
- Loss of downstream readiness after a session is already established blocks new sessions but does not by itself imply that the proxy process is dead.
- Readiness transition observability uses the shared contract from [Deployment Environments](../../infrastructure/deployment-environments.md): `firemud.readiness.current`, `firemud.readiness.transitions`, and structured logs keyed by the curated dependency names `telnetListener` and `gatewayGameplayPath`.

## Documentation Map

Canonical TCP Proxy documentation is now split by concern:

- [`protocols.md`](./protocols.md)
  - Telnet login flow, hidden bridge metadata, bridge data flow, Telnet command handling, and MCP budgets.
- [`api-contracts.md`](./api-contracts.md)
  - `NotifyDisconnect` semantics, failure handling, correlation rules, REST/gRPC endpoints, and proto ownership.
- [`runtime-and-data.md`](./runtime-and-data.md)
  - Redis/non-Redis ownership, statelessness, and reconnection behaviour at the proxy layer.
- [`operations.md`](./operations.md)
  - Metrics, runbook hooks, local development flows, and observability behavior.
- [`configuration.md`](./configuration.md)
  - TLS/trust surfaces, environment variables, production invariants, and tuning guidance.

## Quick Canonical Links

These are the primary canonical references for proxy behaviour:

- [`protocols.md#hidden-attach-metadata`](./protocols.md#hidden-attach-metadata)
- [`api-contracts.md#service-interactions`](./api-contracts.md#service-interactions)
- [`runtime-and-data.md#reconnection-behaviour-at-the-proxy-layer`](./runtime-and-data.md#reconnection-behaviour-at-the-proxy-layer)
- [`operations.md#metrics-summary`](./operations.md#metrics-summary)
- [`configuration.md#websocket-mtls-to-spring-cloud-gateway`](./configuration.md#websocket-mtls-to-spring-cloud-gateway)
- [`configuration.md#environment-variables`](./configuration.md#environment-variables)

## Related Documentation

- [System Architecture Overview](../../system-architecture-overview.md)
- [Reconnection Strategy](../../system-architecture-reconnection.md)
- [Security Architecture](../../system-architecture-security.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
- [User Journeys – Player Login and Gameplay](../../user-journeys-players.md#3-player-login-and-gameplay)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [gRPC API Style & Versioning Guidelines](../../system-architecture-grpc.md)
- [Shared Libraries Overview](../../system-architecture-shared-libraries.md)
- [Testing Strategy](../../system-architecture-testing.md)
- [CI/CD Pipeline](../../system-architecture-cicd.md)
