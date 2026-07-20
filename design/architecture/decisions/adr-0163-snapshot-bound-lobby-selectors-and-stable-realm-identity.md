# ADR 0163: Snapshot-Bound Lobby Selectors and Stable Realm Identity

## Status

Accepted

Supersedes [ADR 0005](./adr-0005-tenant-identifiers-in-gameplay-protocol.md).

## Decision Record

- Decision date: 2026-07-21
- Decision key: `AUTH-01`
- Disposition: `revised`
- Primary capability: `AA-3.1` tenant discovery and selection
- Affected capabilities: `AA-2.1`, `AA-3.3`, `SF-1.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of public selectors, UUID identity, realm routing, anonymous discovery, menu races, identifier exposure, and current implementation drift

## Context

ADR 0005 separated player-friendly selection from internal tenant identity, but predates multiple realms, admission pointers, UUID resource identities, and the current `worldSlug` vocabulary. Current documents use both `tenantSlug` and `worldSlug`, key durable routing by slugs, recalculate menu ordinals against changing catalogs, and disagree about anonymous `WORLDS` discovery.

## Decision

A tenant represents one hosted game. Its one canonical public selector is a globally unique, stable `worldSlug`, owned by Game Design and resolved to opaque UUID `tenantId`. The `tenantSlug` term is retired from player-facing and cross-service contracts. A future rename is an explicit audited alias/redirect lifecycle; it never changes tenant identity silently.

Each durable player-addressable realm has an opaque UUID `realmId` and a stable `realmSlug` unique within its tenant. The slug resolves to `realmId`; authoritative catalog, grant, audit, and admission-pointer state use `{tenantId, realmId}`. The pointer names one concrete UUID `gameInstanceId` when open. Character name/index resolves to UUID `characterId`.

Lobby menu ordinals are convenience selectors bound to the exact connection/list snapshot that produced them, including catalog revision and a bounded expiry. `PLAY 2`, `REALMS 2`, or a character ordinal never indexes a newly sorted catalog. An expired or changed snapshot asks the player to list/select again rather than risking a wrong target. Direct slugs remain convenient stable selectors and are re-resolved and reauthorized at use time.

The resulting gameplay binding is `{tenantId, realmId, gameInstanceId, characterId}` plus the applicable pointer/fence and playable-state namespace. Slugs and ordinals never become admission authority.

Opaque IDs may appear in authorized structured client metadata for correlation. They are not secrets and do not grant authority; text clients never need to type them. Every operation still validates kind, complete scope, visibility, membership/grant, entitlement, pointer version, and character ownership.

Anonymous `WORLDS` lists only explicitly public games and safe display metadata. Membership, entitlement, private realm, character, and account-specific projections require authentication. Hidden selectors return non-enumerating results regardless of slug predictability.

## Consequences

- World, realm, runtime-instance, and character identities no longer alias selector strings.
- One globally unique world namespace keeps hand-authored clients simple but creates naming, alias, and namespace-squatting operations.
- Realm UUIDs and snapshot-bound ordinal state add mapping/storage work outside the gameplay hot path.
- Structured clients may retain opaque metadata without being allowed to use it as authority.
- Current numeric identities, global world/realm schema uniqueness, live catalog grouping, ordinal resolution, and anonymous-route policy require convergence.

## Alternatives Considered

### Short-Lived Opaque Handle for Every Selection

This avoids global slug and ordinal races but adds more protocol/session state and less convenient manual clients. `connectScopeId` remains appropriate for first-party admission, but is not required for every text selector.

### Permanent Slugs as Durable Routing Keys

This is simpler but makes rename/alias/import behavior part of authority and conflicts with the accepted opaque-identity boundary.

### Recalculate Ordinals on Every Command

Authorization still prevents many leaks, but catalog changes can silently select the wrong visible target. It is rejected.

## Implementation and Proof Obligations

Current public/cross-service identifiers remain largely numeric. The Game Session catalog can merge duplicate tenant-qualified slugs, realm/character ordinal parity is incomplete, and world ordinals are not snapshot-bound. Implementation must migrate canonical identities, introduce `realmId`, define global `worldSlug` uniqueness and aliases, bind ordinals to catalog snapshots, and align public discovery authorization.

Proof must cover catalog mutation between list and selection, expiry, aliases, duplicate realm slugs across tenants, hidden selector probing, wrong-kind/scope IDs, structured-ID exposure without authority, public anonymous discovery, private projections, and UUID-to-private-key mapping.

## Reversibility and Revisit Triggers

Selector syntax and handle use can evolve without changing durable identities. Revisit global `worldSlug` uniqueness if namespace pressure or federation requires owner-qualified names; preserve `tenantId` and `realmId` across any change.

## Required Documentation Alignment

- `design/architecture/system-architecture-multi-tenancy.md`
- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-identifier-glossary.md`
- Game Session lobby, realm-catalog, and admission-pointer contracts
