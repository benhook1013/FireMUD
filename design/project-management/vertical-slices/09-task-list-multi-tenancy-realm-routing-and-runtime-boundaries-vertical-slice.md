# `09` Multi-Tenancy, Realm Routing, and Runtime Boundaries

Goal: translate FireMUD's multi-tenancy and realm-routing architecture into one explicit slice family so tenant identity, player-addressable realms, admission-pointer resolution, public-production access, and realm-scoped runtime-state policy do not keep leaking across login, reconnect, bootstrap, and activation work as implicit assumptions. Status: in progress.

## Implementation Notes

This domain is already materially designed:

- `tenantId`, `tenantSlug`, `gameInstanceId`, realm selection, and player-addressable routing rules are defined in the multi-tenancy architecture.
- public-production admission versus explicitly granted non-production realms is already called out.
- the runtime contracts for `GetAdmissionPointer`, `EnsurePublicProductionPlayerMembership`, bootstrap discovery, and connect-token issuance are already described.
- the distinction between tenant-scoped identity and realm- or instance-scoped playable state is already locked, including shared-state versus isolated-state realms.

The first implementation cut is now real:

- account-to-tenant membership is now an explicit runtime substrate instead of piggybacking on `accounts.tenant_id`;
- bootstrap discovery and in-band lobby discovery now share one canonical gameplay world/realm catalog model backed by persisted Game Session admission-pointer state;
- first-party connect-token issuance and text-client `PLAY` now resolve tenant authority from the selected realm rather than from the initial login tenant;
- active gameplay session context now carries the admitted `worldSlug`, `realmSlug`, `pointerVersion`, and resolved playable-state scope instead of forcing later consumers to reconstruct that bundle from only `{tenantId, gameInstanceId}`;
- public-production first join now exists as a concrete `EnsurePublicProductionPlayerMembership(...)` boundary in `account-service`;
- public-production membership checks now consume the same Game Session routing authority as bootstrap/connect-token issuance instead of local config copies;
- `CHARS`, `PLAY`, bootstrap character discovery, and `TELL` now resolve character lookup through a scope-aware gameplay roster contract, with shared-state realms reusing one tenant-live namespace and isolated-state realms using an instance-local roster namespace;
- live gameplay presence now also preserves admitted world/realm slugs, so account-presence and related reads do not have to reverse-map that identity from runtime ids alone when the session already knows the canonical routing choice.

The remaining work is to finish the deeper runtime/control-plane follow-through instead of leaving the new family as design-only.

## Why This Slice Exists

Without a dedicated family here:

- tenant membership and gameplay admission look flatter than they really are;
- realm routing risks being treated as a UI concern instead of a control-plane/runtime contract;
- shared-state versus isolated-state realm policy can leak into character, inventory, and progression work without one canonical planning home;
- bootstrap discovery and connect-token resolution can drift away from the same realm-routing contract used by text-clients and reconnect flows.

This family makes the multi-tenant gameplay boundary explicit before more systems grow against local assumptions.

## Target State

- player-visible world and realm discovery resolves through one canonical tenant and realm catalog contract.
- each visible realm resolves to exactly one admissible `gameInstanceId` at a time through one authoritative admission-pointer contract.
- public-production admission, explicit non-production access, and membership creation rules are bounded and auditable.
- runtime systems consistently distinguish tenant-scoped identity from realm- or instance-scoped playable state.
- first-party bootstrap and connect-token issuance use the same realm-routing truth as text-client `PLAY` and reconnect flows.

## Locked Direction

- Canonical gameplay admission/routing identity remains an explicit bundle rather than collapsing into a two-slot shortcut payload.
- Where routing freshness matters, callers should preserve `worldSlug`, `realmSlug`, resolved `gameInstanceId`, and `pointerVersion` together instead of replacing them with a narrower local surrogate.
- Downstream gameplay APIs may derive narrower scoped identities after admission, but they must derive them from the canonical routing bundle rather than redefining admission truth.
- Realm/world discovery and admission-pointer resolution must come from one canonical authority; no service may grow an independent local catalog as authority for player-facing routing decisions.
- Read-through or cached copies of routing data are acceptable only as explicit caches of the canonical Game Session authority and must fail closed when stale or unavailable.

## Child Slices

- [09.1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice.md](./09.1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice.md)
- [09.2-task-list-public-production-admission-and-membership-creation-vertical-slice.md](./09.2-task-list-public-production-admission-and-membership-creation-vertical-slice.md)
- [09.3-task-list-realm-scoped-character-and-playable-state-policy-vertical-slice.md](./09.3-task-list-realm-scoped-character-and-playable-state-policy-vertical-slice.md)
- [09.4-task-list-bootstrap-discovery-and-connect-scope-resolution-vertical-slice.md](./09.4-task-list-bootstrap-discovery-and-connect-scope-resolution-vertical-slice.md)

## Validation

- [ ] `./gradlew linkCheck lintMarkdown`
