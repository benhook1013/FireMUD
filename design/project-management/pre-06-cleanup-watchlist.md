# Pre-06 Cleanup Watchlist

## Purpose

This is a temporary consolidation doc for cross-cutting cleanup and architecture-hardening issues noticed during implementation and review. The intent is to record these items as soon as they are seen, keep the current status honest, and resolve them before work meaningfully advances into `06`.

This is not the canonical slice plan. It is a short-lived coordination list for issues that cut across slices or were discovered after the main slice docs had already moved forward.

## Usage Rules

- Add new bad-pattern or consistency findings here as soon as they are noticed during implementation or review.
- Prefer direct replacement over migration scaffolding.
- Remove items from this doc once they are fixed and reflected in the canonical slice or architecture docs.

## Fix Now

- [x] Game Session ingress logging no longer records raw command text, including `LOGIN` credentials, in [CommandServiceImpl](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/CommandServiceImpl.java).
- [x] Command queue partitioning after `PLAY` now uses explicit tenant and queue-target ids instead of re-inferring ownership from a bare target id.

## Fix Soon

- [x] Gateway admin route mutation no longer blocks the reactive path. Dynamic route upsert and removal now stay reactive through [GatewayRouteServiceImpl](/home/ben/src/FireMUD-wsl-copy/services/spring-cloud-gateway/src/main/java/net/firedevops/firemud/springcloudgateway/service/impl/GatewayRouteServiceImpl.java), [GatewayController](/home/ben/src/FireMUD-wsl-copy/services/spring-cloud-gateway/src/main/java/net/firedevops/firemud/springcloudgateway/controller/GatewayController.java), and [GatewayManagementGrpcService](/home/ben/src/FireMUD-wsl-copy/services/spring-cloud-gateway/src/main/java/net/firedevops/firemud/springcloudgateway/service/impl/GatewayManagementGrpcService.java).
- [ ] First-party connect-token replay protection is still process-local in [GameplayHandshakeFilter](/home/ben/src/FireMUD-wsl-copy/services/spring-cloud-gateway/src/main/java/net/firedevops/firemud/springcloudgateway/filter/GameplayHandshakeFilter.java).
- [x] The misleading `gateway.connections.*` filter is gone. Gateway now records explicit HTTP request metrics in [RequestMetricsFilter](/home/ben/src/FireMUD-wsl-copy/services/spring-cloud-gateway/src/main/java/net/firedevops/firemud/springcloudgateway/filter/RequestMetricsFilter.java) instead of claiming to measure active gameplay sockets.
- [x] Hot-path blocking gRPC calls now carry bounded deadlines where the gameplay clients block on downstream gRPC calls.

## Refactor And Hygiene

- [ ] Session resume and takeover bookkeeping in Redis is still multi-key and non-atomic in [RedisSessionContextService](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/RedisSessionContextService.java).
- [ ] `GameInstanceServiceImpl` still holds database transactions open across Redis and remote side effects.
- [ ] `GuildServiceImpl` still uses `findAll().stream().filter(...)` instead of repository-level lookup methods for member updates and removal.
- [ ] `SAY` history in [ChatServiceImpl](/home/ben/src/FireMUD-wsl-copy/services/social-groups-service/src/main/java/net/firedevops/firemud/socialgroups/service/impl/ChatServiceImpl.java) is still keyed by `recipientAccountId`, which is not the right shape for room speech history.

## No Longer Active Concerns

- [x] Runtime services no longer default to `spring.profiles.active: dev` in their base `application.yml` files. Explicit profile activation is now required outside local/dev flows.
- [x] The `LOOK` path now returns normal gRPC responses with `ErrorDetail` for application-level failures instead of surfacing them as transport errors.
- [x] The older `LOOK` cache read/write key mismatch is no longer present. Current logic uses one effective key: `gameInstanceId` when available, otherwise `sessionId`.
- [x] The gRPC `StartSession` path now carries a real owner account id into Game Session instead of hard-coding `0`, so gRPC-started sessions can line up with later `LOGIN` ownership checks.
- [x] Session ownership and running-session lookup are now tenant-scoped in persistence and repository queries. Current start-session handling looks up and stops only an existing running session for the same `(tenant, owner)` pair, and the DB now enforces one running instance per `(tenant_id, owner_account_id)`.
- [x] Command queue partitioning now uses explicit tenant and queue-target ids after `PLAY`, so the tick path no longer re-derives tenant ownership from a bare target id.
- [x] Hot-path blocking gRPC clients now use bounded deadlines on gameplay calls instead of relying only on readiness deadlines.
- [x] World Management now distinguishes malformed `GetRoom` / `GetRoomSnapshot` requests from missing rooms.
- [x] [GlobalExceptionHandler](/home/ben/src/FireMUD-wsl-copy/services/common-web-support/src/main/java/net/firedevops/firemud/common/GlobalExceptionHandler.java) now returns a generic internal-error message instead of raw exception text for 500 responses.
- [x] The earlier concern that WebSocket transport session ids could easily become non-numeric is weaker now. Current handshake derivation hashes proxy/connect context into numeric strings and falls back more safely. This still deserves malformed-input coverage, but it is no longer a primary correctness concern.
- [x] The first-party WebSocket trust-boundary concern is narrower than it first appeared. Current first-party flow goes through gateway handshake validation and internal context minting rather than blindly trusting arbitrary browser headers. It should still be hardened, but it is not currently classified as a confirmed live exploit path.

## PR Note

The current PR body should be refreshed after preview proof succeeds so that the final description includes the later pre-`06` settings, presentation, logging, preview, and CI work that is not yet summarized there.
