# Microservices Overview

This directory is the service-boundary hub for FireMUD. Each service subdirectory owns the canonical service-level design for its API contracts, runtime/data boundaries, operations, configuration, and service-specific appendices.

Use this overview alongside the top-level [Architecture Overview](../README.md), [System Architecture Overview](../system-architecture-overview.md), and [Service Responsibility Matrix](../service-responsibility-matrix.md):

- top-level architecture docs define cross-service contracts, platform invariants, and traffic-plane rules;
- this directory explains how each individual service participates in those contracts; and
- shared Gradle/library modules remain documented in the repository and shared-library architecture docs rather than pretending to be standalone microservices.

## Service Map

| Service | Primary role | External traffic surface | Service docs |
| --- | --- | --- | --- |
| Account Service | Account lifecycle, authentication, bootstrap discovery, gameplay connect-token issuance, billing-safe membership and entitlement reads | `/api/account/**` for documented read/admin/bootstrap contracts | [account-service/README.md](./account-service/README.md) |
| Automation & Scripting Service | Script patch readiness, event ingress, timer scheduling, automation outbox, plugin activation/runtime control | No direct public edge route in the base architecture | [automation-scripting-service/README.md](./automation-scripting-service/README.md) |
| Entity Management Service | Entity definitions, containment/presentation ownership, canonical entity snapshots | No direct public edge route in the base architecture | [entity-management-service/README.md](./entity-management-service/README.md) |
| Game Design Service | Creator design-time content, revisions, assets, script/plugin publication inputs, customization authoring | `/api/design/**` for documented creator/admin routes and asset-adjacent control surfaces | [game-design-service/README.md](./game-design-service/README.md) |
| Game Logic Service | Cross-entity gameplay rules, combat/effect evaluation, game-rule orchestration | No direct public edge route in the base architecture | [game-logic-service/README.md](./game-logic-service/README.md) |
| Game Session Service | Gameplay admission, session front-end behavior, tick/runtime control plane, live gameplay execution | `/ws/game/**` gameplay WebSocket surface and `/api/session/**` documented HTTP admin/control routes | [game-session-service/README.md](./game-session-service/README.md) |
| Logging & Admin Service | Operator ingress, moderation, reports, saga inspection, audit-backed admin orchestration, observability-backed views | `/api/admin/**` for operator-facing HTTP APIs via Gateway | [logging-admin-service/README.md](./logging-admin-service/README.md) |
| Social & Groups Service | Chat, social graph, parties/guilds, social moderation | `/api/social/**` for documented read/admin/social routes | [social-groups-service/README.md](./social-groups-service/README.md) |
| Spring Cloud Gateway | External HTTP/WebSocket edge routing, header trust, coarse protections, handshake classification | Owns the public edge prefixes and gameplay handshake path | [spring-cloud-gateway/README.md](./spring-cloud-gateway/README.md) |
| TCP Proxy Service | Telnet ingress, protocol bridging, trusted gameplay bridge into Gateway | Raw TCP/TLS Telnet ingress; forwards into `/ws/game/**` | [tcp-proxy-service/README.md](./tcp-proxy-service/README.md) |
| World Management Service | World graph, room/zone/region design ownership, world mutation APIs, canonical room-read substrate participation | No direct public edge route in the base architecture | [world-management-service/README.md](./world-management-service/README.md) |

## Traffic-Surface Rules

- A service appearing behind a public Gateway prefix does not mean every service-local path is externally reachable.
- The canonical public edge is a curated route inventory, not a blanket service fan-out. Internal-only service-local paths such as `/internal/**` remain non-edge contracts even when the service owns an external prefix.
- External mutating operator workflows for moderation, quota overrides, runtime feature flags, and tick remediation enter through Logging & Admin unless an owning service contract explicitly marks a route as bypass-safe.
- Services without a listed public surface here remain internal-only until the architecture docs and service contracts are updated in the same change.

## Shared Modules Are Not Services

The repository also contains shared Gradle modules under `services/` such as `common-platform-core`, `common-security`, `common-saga`, `common-test-support`, and `common-web-support`. These are shared libraries, not separately deployed microservices. Document their cross-service contracts in:

- [Shared Libraries Overview](../system-architecture-shared-libraries.md)
- [Repository Structure](../repository-structure.md)

## Service-Doc Conventions

- [service-documentation-structure.md](./service-documentation-structure.md) defines the preferred shape for service doc sets as they grow.
- [service-template.md](./service-template.md) is the starter template for a new or heavily refactored service doc set.
