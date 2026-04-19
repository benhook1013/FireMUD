# System Cohesion Review Tracker

Purpose: working review notes for checking whether recent multi-tenant/bootstrap/lobby work is converging on one canonical system or merely composing several parallel shortcuts. This is a tracking artifact, not a canonical design doc.

## Review Lens

- Prefer one authoritative substrate per concern.
- Prefer world/realm resolution to flow through one canonical seam.
- Prefer explicit membership/grant storage over inferred authority from unrelated account fields.
- Treat “works for the current happy path” as insufficient if the seam blocks the intended architecture.

## Confirmed Cohesion Faults

### 1. Tenant membership and gameplay admission are still encoded as account ownership

Status: implemented correction in progress review.

Why it does not gel:

- The architecture says platform accounts are global and tenant participation is many-to-many with explicit membership/grant authority.
- The current code still answers runtime admission by comparing `accounts.tenant_id` to the requested tenant.
- That means `09.2` public-production onboarding is not just “unfinished”; the underlying authority substrate is still the wrong one.

Original evidence:

- [Multi-Tenancy architecture](../architecture/system-architecture-multi-tenancy.md) defines `accountId` as global identity and membership as many-to-many.
- [Account entity](../../services/account-service/src/main/java/net/firedevops/firemud/accountservice/entity/Account.java) stores `tenantId` directly on the account row.
- [Account repository](../../services/account-service/src/main/java/net/firedevops/firemud/accountservice/repository/AccountRepository.java) authenticates by tenant-scoped account lookup.
- [Account service implementation](../../services/account-service/src/main/java/net/firedevops/firemud/accountservice/service/impl/AccountServiceImpl.java) implements `getTenantMembershipForRuntime(...)` as `Objects.equals(account.getTenantId(), tenantId)`.

What changed:

- `account-service` now has an explicit `account_tenant_membership` substrate instead of answering gameplay admission from `accounts.tenant_id`.
- Authentication, bootstrap admission, connect-token issuance, password reset eligibility, email verification, external linking, payment, and virtual-currency ownership checks were moved onto membership reads.
- `CreateAccount` now creates an initial membership row for the requested tenant instead of making the account itself tenant-scoped.

Residual follow-through:

- The broader `09.2` first-join writer boundary (`EnsurePublicProductionPlayerMembership(...)`) still needs to be added.
- Some tenant-scoped data like profiles and payment rows remain tenant-keyed by design; those are not the same shortcut as tenant-bound account identity.

Why it mattered:

- Public-production first join, explicit non-production grants, and true multi-tenant participation cannot become cleanly real until account identity and tenant membership are separated in storage and read paths.

Suggested correction:

- Introduce a dedicated account-to-tenant membership/grant substrate and move runtime admission reads plus first-join creation onto it.

### 2. World/realm routing is duplicated in two incompatible local catalogs

Status: implemented correction in progress review.

Why it does not gel:

- `account-service` owns one bootstrap catalog with `tenantId`, `gameInstanceId`, and `pointerVersion`.
- `game-session` owns a second lobby catalog with only `gameInstanceId` plus presentation flags.
- These are not the same model and are not reading from one shared routing substrate.

Original evidence:

- The old `account-service` bootstrap catalog defined worlds/realms with `tenantId`, `gameInstanceId`, and `pointerVersion`.
- The old `game-session` world list defined a separate model without `tenantId` or `pointerVersion`.
- [Gameplay world catalog](../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/command/text/GameplayWorldCatalog.java) resolves lobby selection entirely from `game-session` local config.
- [Account service implementation](../../services/account-service/src/main/java/net/firedevops/firemud/accountservice/service/impl/AccountServiceImpl.java) serves bootstrap discovery from the `account-service` local catalog.

What changed:

- The separate `BootstrapCatalogProperties` / `GameSessionProperties.WorldOption` split was replaced with one shared `GameplayCatalogProperties` model in `common-platform-core`.
- `account-service` bootstrap discovery and `game-session` lobby discovery now read the same world/realm shape, including `tenantId`, `gameInstanceId`, `pointerVersion`, visibility, and character-selection policy.
- Both service `application.yml` files now bind the same `firemud.gameplay.catalog` structure.

Residual follow-through:

- The routing authority is still configuration-backed rather than a dynamic control-plane read, so the final `09.1` target is not complete yet.
- Bootstrap and in-band lobby still consume the shared model locally rather than via one central routing service.

Why it mattered:

- Bootstrap discovery and text-lobby discovery can drift even when both “work.”
- Any future admission-pointer cutover or realm visibility change has to be mirrored in two different configs/models.

Suggested correction:

- Replace both local catalogs with one shared routing authority/read model consumed by both bootstrap and in-band lobby flows.

### 3. `PLAY` still carries tenant authority from login context instead of resolving it from world/realm selection

Status: implemented correction in progress review.

Why it does not gel:

- The user-facing selector is now world/realm aware.
- The runtime authority check is still tenant-context aware, not selector-resolution aware.
- `PLAY` resolves a world/realm to a `gameInstanceId`, but it never resolves or switches tenant authority from the selected target.

Original evidence:

- [Play command handler](../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/command/text/PlayCommandHandler.java) opens gameplay logging and follow-on work under `context.tenantId()`.
- [Play command handler](../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/command/text/PlayCommandHandler.java) resolves only `selectedRealm.getGameInstanceId()`.
- [Play command handler](../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/command/text/PlayCommandHandler.java) calls membership/entitlement runtime reads using `context.tenantId()`, not tenant resolved from the selected world/realm.
- The old `game-session` realm options did not carry tenant identity at all.

What changed:

- `PLAY` now resolves tenant authority from the selected realm, not from the login session tenant.
- Runtime membership/entitlement checks now execute against `selectedRealm.getTenantId()`.
- Gameplay binding, reconnect identity, and character lookup now use the resolved realm tenant together with `gameInstanceId`.

Residual follow-through:

- The text login path still authenticates through an initial tenant selector before gameplay routing takes over.
- Full admission-pointer authority and realm cutover freshness still belong to later `09.1` / `09.2` work.

Why it mattered:

- The current realm-aware `PLAY` flow is a better command surface, but not yet the intended cross-tenant/canonical-routing substrate.
- It is still fundamentally “login tenant + selected gameInstance” rather than “selected world/realm resolves canonical `{tenantId, gameInstanceId}`.”

Suggested correction:

- Make world/realm resolution produce authoritative tenant + game-instance routing together, then route all downstream validation and binding off that resolved tuple.

### 4. Realm-scoped character discovery is still tenant-scoped character discovery wearing a realm label

Status: partially corrected; still incomplete.

Why it does not gel:

- `CHARS` and bootstrap character discovery now accept world/realm inputs.
- The actual character read is still `listCharactersByAccount(tenantId, accountId)` with no realm/game-instance filter.
- That means the UI can show a realm-specific command shape while still reading one tenant-wide roster.

Original evidence:

- [Worlds command handler](../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/command/text/WorldsCommandHandler.java) calls `listCharactersByAccount(sessionContext.tenantId(), sessionContext.accountId())`.
- [Account service implementation](../../services/account-service/src/main/java/net/firedevops/firemud/accountservice/service/impl/AccountServiceImpl.java) bootstrap character discovery does the same with `realm.getTenantId(), bootstrapContext.accountId()`.
- [Multi-Tenancy architecture](../architecture/system-architecture-multi-tenancy.md) says `CHARS` must be valid for resolved `{tenantId, gameInstanceId}` target, not a tenant-wide superset.

What changed:

- `CHARS` in `game-session` now at least reads characters through the selected realm tenant rather than the login-session tenant.
- Bootstrap character discovery already used realm tenant and now shares the same catalog substrate as the text lobby.

Residual follow-through:

- Character reads are still `listCharactersByAccount(tenantId, accountId)` with no realm/game-instance filter.
- Shared-state versus isolated-state realm policy is still not expressed in the runtime character query contract.

Why it mattered:

- `09.3` realm-state policy is not yet represented in runtime reads.
- The current implementation is acceptable only as a bounded shared-state placeholder, not as the final seam.

Suggested correction:

- Add a realm-/instance-aware character discovery/read contract before treating `CHARS` as complete.

## Areas That Currently Do Look Cohesive

### 1. First-party connect-token to gateway to game-session propagation

This part is reasonably coherent:

- `account-service` mints a scoped connect token with world/realm/game-instance claims.
- `gateway` validates it, enforces replay protection, and re-signs a narrower connect context.
- `game-session` parses that context and validates `PLAY` against it.

Primary references:

- [Account service implementation](../../services/account-service/src/main/java/net/firedevops/firemud/accountservice/service/impl/AccountServiceImpl.java)
- [Gameplay handshake filter](../../services/spring-cloud-gateway/src/main/java/net/firedevops/firemud/springcloudgateway/filter/GameplayHandshakeFilter.java)
- [First-party connect-context service](../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/FirstPartyConnectContextService.java)

Assessment:

- This is one of the better examples of implementing the intended end-state seam instead of adding transitional glue.

### 2. Lobby command modeling after the `REALMS` / `CHARS` work

This part is also materially better than the earlier shortcut shape:

- `PLAY` now preserves `world`, `realm`, and `character` as separate dimensions.
- Built-in dispatch, browse handlers, text rendering, and first-party structured output all share the same command/result path.

Primary references:

- [Text command payload](../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/command/text/TextCommandPayload.java)
- [Worlds dispatch handler](../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/command/text/WorldsTextCommandDispatchHandler.java)
- [WebSocket output projector](../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/websocket/WebSocketOutputProjector.java)

Assessment:

- The command surface is no longer the main architectural problem; the underlying authority substrate is.

## Follow-Up Queue

1. Extend the first `09.2` writer-boundary cut beyond bootstrap/connect-token so text-client `PLAY` and future non-browser admission flows reuse the same first-join authority instead of adding parallel shortcuts.
2. Replace config-backed routing with the final admission-pointer/control-plane authority for `09.1`.
3. Add realm-/instance-aware character discovery so `09.3` stops presenting a realm label on a tenant-wide roster.
4. Revisit whether gateway should keep `X-Tenant-Id` / `X-Game-Instance-Id` as a duplicate side channel once routing becomes fully canonical.

## Working Conclusion

This pass converted the two biggest substrate shortcuts into real code changes:

- explicit account-to-tenant membership authority, and
- one shared world/realm routing model used by both bootstrap and text-lobby flows.

The main remaining cohesion gap is now narrower and more honest:

- realm-/instance-aware character discovery still is not real, and
- dynamic admission-pointer authority still is not the final control-plane source.

That is a much better place to continue from because the current implementation no longer rests on the old “account owns one tenant and gameplay just inherits it” assumption.
