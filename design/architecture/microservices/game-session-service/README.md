# Game Session Service

## Overview

Orchestrates live game sessions, including tick execution, player input validation, and runtime feature toggles. It is the gameplay session front door for both Telnet and `/ws/game/**` clients and the lease-owning tick executor for each active `<tenantId, gameInstanceId, regionId>` region scope.

Meaningful gameplay-session and tick-coordination state is externalized into Redis and PostgreSQL rather than kept as authoritative process-local memory. The target state therefore treats Game Session instances as replaceable workers: a new instance of the same service type should be able to resume session-front-end or lease-owner responsibility from shared state. Hidden same-type recovery is not a current availability guarantee; its implementation and proof remain a gap, and any user-visible reconnect caused solely by a non-edge Game Session restart remains implementation/proof debt rather than target behavior.

This doc set is the authoritative source for:

- gameplay session ownership and front-door responsibilities;
- the split between session front-end pods and region lease owners;
- Game Session's ownership of tick execution, reconnectable gameplay bindings, and pinned runtime/script state;
- the minimal text command protocol and world-selection flow used by the initial gameplay slice; and
- the service's control-plane, runtime, configuration, and operator contracts.

## Terminology

- **Tenant** – a hosted game world or project, identified by `tenantId`. All database rows and Redis keys include this prefix so data is isolated between games.
- **Game instance** – a specific running instance of a tenant’s world, identified by an opaque internal `gameInstanceId` in the database and runtime APIs as described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md#version-activation--rollback). Even if a deployment runs at most one instance per tenant, APIs and persistence models still carry `gameInstanceId` explicitly so multi-instance support does not require rewriting identifiers later; clients must treat the identifier as server-issued and opaque rather than inferring special values.
- **Character identity** – gameplay identity keyed by `characterId`.
- **Player gameplay session** – a single player’s live connection and gameplay context bound to a specific game instance and character identity. Gameplay sessions are stored in Redis under `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` and are purged when the session ends.
- **Region / region shard** – a subdivision of a running game instance's world used for tick execution and scaling. Tick coordination keys are scoped per `<tenantId, gameInstanceId, regionId>` and do not follow individual player session lifecycles.

## Responsibilities

- Maintain gameplay session state, reconnection bindings, and tick timing in Redis.
- Persist Game Session control-plane metadata in PostgreSQL, including game-instance rows, pinned runtime-version/script-patch selections, active runtime feature-flag overrides, and operator/audit-relevant disconnect/remediation metadata.
- Queue player commands, validate front-door session state, and dispatch gameplay work to Game Logic Service.
- Own the canonical live gameplay-presence and recent-presence substrate for active sessions, first `WHO`, AFK/activity resolution, and disconnect disposition handoff into later social surfaces.
- Broadcast lifecycle events and world updates to other services.
- Support reconnection and recovery of running games.
- Own the authoritative, pinned `scriptPatchVersion` for each running game instance and enforce version fencing for script-generated work.
- Publish coordination and tick-health metrics per `<tenantId, gameInstanceId, regionId>` and expose control APIs that allow authorized services to pause/resume tick execution and participate in scoped coordination resets. The shipped pause/resume path is currently instance-scoped at `{tenantId, gameInstanceId}`; the target-state `GetRegionTickStatus` and regional pause/status APIs remain the broader regional control surface and are not yet fully implemented.
- Front gameplay login commands and session binding, calling Account Service to verify credentials and obtain JWTs/tokens while enforcing single-session control for each character.
- Accept bootstrap-backed bare `LOGIN` for first-party `/ws/game/**` after Gateway connect-token validation and signed connect-context verification; this path is intentionally credentialless and must not prompt the browser to replay username/password/OTP.
- Attach typed unsigned `PlayerExecutionContext` to player-delegated gameplay RPCs; expose a concrete mTLS workload identity and rely on exact method caller allowlists, context/domain validation, and mutation idempotency rather than per-action signing or a generic cross-service replay cache. Command, effect, and request idempotency records remain mandatory in their owning services.
- Fail readiness for new gameplay traffic when the currently exposed `LOGIN` plus first-command path is not safe.

## Architecture Summary

Game Session is both a session front-end and a tick executor:

- Connected sockets bind to a stable session front-end pod.
- Region-scoped command execution belongs to the current lease owner for the target `<tenantId, gameInstanceId, regionId>`.
- Session front-ends may authenticate, normalize input, manage connection-local state, and stream results back to the client.
- Only the lease owner may mutate region-scoped coordination state or commit tick-owned Redis changes.
- Front-ends may forward execution requests to lease owners over internal gRPC, but forwarded work is still fenced by the current region lease/epoch and must reject stale or missing fences.

This split keeps `/ws/game/**` and Telnet gameplay sessions stable at the edge while allowing in-cluster lease rebalancing without forcing reconnects solely because a region moved.

The target-state replaceability rule applies to ordinary non-edge failure handling under [ADR 0013](../../decisions/adr-0013-bounded-invisible-non-edge-restart-recovery.md): if a session front-end pod or lease owner fails while the edge socket, healthy replacement capacity, and shared authority remain available, another Game Session instance should recover ownership without making that backend restart itself the player-visible event. This hidden same-type recovery remains an implementation/proof gap today; see [runtime and data implementation status](./runtime-and-data.md#implementation-status). Ordinary recovery targets 10 seconds. Hidden recovery terminates immediately if continuation authority cannot be established safely; otherwise 30 seconds is the hard maximum before the canonical `1013/backend_unavailable` fallback.

Game Session front-end instances and region lease owners must externalize meaningful live state into Redis/PostgreSQL-backed coordination so another same-type instance can take over after restart. Under this target-state contract, successful upstream rebind continues current server-side session authority and stable edge transport identity without another `LOGIN` or `PLAY`; it is not fresh public admission using the original connect token. The complete hidden same-type recovery guarantee remains an implementation/proof gap. Membership, entitlement, revocation, current authorization, tenant/game scope, and fencing remain authoritative. Loss of only the internal Game Session hop must not publish a false player transport disconnect or permanently remove presence when replacement succeeds.

## Documentation Map

- [`api-contracts.md`](./api-contracts.md)
  - gRPC and REST endpoints, control-plane APIs, forwarding/fence rules, session-front-door ownership, and proto references.
- [`runtime-and-data.md`](./runtime-and-data.md)
  - Redis/PostgreSQL ownership, session indexes, tick coordination, script/version fences, and runtime feature-flag handling.
- [`operations.md`](./operations.md)
  - readiness/liveness, scaling and rebalancing, failure behavior, and operator-facing notes.
- [`configuration.md`](./configuration.md)
  - environment variables, service discovery, TLS, and deployment-specific configuration invariants.
- [`protocols.md`](./protocols.md)
  - minimal text command protocol, login/play flow, command response format, and LOOK/SAY request behavior.

## Quick Canonical Links

- [`api-contracts.md#grpc-apis`](./api-contracts.md#grpc-apis)
- [`api-contracts.md#session-front-end-and-lease-owner-routing`](./api-contracts.md#session-front-end-and-lease-owner-routing)
- [`runtime-and-data.md#redis-ownership-and-coordination-rules`](./runtime-and-data.md#redis-ownership-and-coordination-rules)
- [`operations.md#readiness-and-liveness`](./operations.md#readiness-and-liveness)
- [`protocols.md#login-and-play-flow`](./protocols.md#login-and-play-flow)
- [`protocols.md#minimal-text-command-protocol`](./protocols.md#minimal-text-command-protocol)
- [`configuration.md#environment-variables`](./configuration.md#environment-variables)

## Dependencies

- **Internal:** Account Service, Entity Management Service, Game Logic Service, World Management Service.
- **Internal consumers:** Logging & Admin Service receives session lifecycle events and drives some control-plane workflows.
- **External:** PostgreSQL for control-plane metadata and Redis for gameplay session state and coordination.
- gRPC clients discover endpoints via `ServiceEndpointsProperties` and secure connections with mTLS certificates issued by cert-manager.

> See [Gateway Architecture](../../system-architecture-gateway.md), [Deployment Environments](../../infrastructure/deployment-environments.md), and [Protocol Bridging](../../system-architecture-protocol-bridging.md) for the shared infrastructure around gameplay admission and transport bridging.

## Related Documentation

- [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md)
- [Reconnection Strategy](../../system-architecture-reconnection.md)
- [Authentication & Authorization](../../system-architecture-authentication.md)
- [Tick System and Runtime Design](../../system-architecture-ticks.md)
- [Redis Architecture](../../system-architecture-redis.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [gRPC API Style & Versioning Guidelines](../../system-architecture-grpc.md)
- [Shared Libraries Overview](../../system-architecture-shared-libraries.md)
- [Testing Strategy](../../system-architecture-testing.md)
- [CI/CD Pipeline](../../system-architecture-cicd.md)
- [Logging & Monitoring](../../system-architecture-logging-monitoring.md)
- [Backup & Disaster Recovery](../../system-architecture-backup-recovery.md)
- [System Architecture Overview](../../system-architecture-overview.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
- [User Journeys – Publish and Start a Game Instance](../../../product/user-journeys/creators.md#4-publish-and-start-a-game-instance)
- [User Journeys – Player Login and Gameplay](../../../product/user-journeys/players.md#3-player-login-and-gameplay)
- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)
