# ADR 0005: Tenant Identifiers in Gameplay Protocol

## Status

Accepted

## Implementation Status

The current Game Session catalog groups and resolves worlds by bare `worldSlug` and does not yet
carry `tenantSlug` in its runtime catalog projection. That is implementation drift from the
tenant-qualified selector contract below. Until the projection is converged, deployments must
not contain duplicate visible `worldSlug` values across tenants; this temporary constraint is not
the target uniqueness contract and must not become a public API guarantee.

## Context

Some documents described gameplay commands using `tenantIdOrSlug` (for example `ENTER_GAME <tenantIdOrSlug>`), but the Multi-Tenancy model defines `tenantId` as the authoritative internal tenant identifier and does not define:

- What a “tenant slug” is,
- Who owns slug generation and uniqueness,
- How slugs resolve to `tenantId`,
- Whether slugs are stable across renames or exports,
- How authorization and audit should treat slug inputs.

Leaving the gameplay protocol ambiguous creates interoperability problems for clients and makes error codes (`TENANT_NOT_FOUND`) underspecified.

## Decision

FireMUD uses a single shared gameplay entrypoint for many worlds. The gameplay text protocol must therefore support a player-friendly world selection flow without requiring humans to type opaque internal identifiers.

The gameplay protocol uses distinct identifier layers:

- **Internal identifiers (authoritative):** `tenantId` and `characterId`.
- **Tenant selector (lobby only):** `tenantSlug`, the stable human-friendly selector that resolves to one `tenantId`.
- **Tenant-scoped world and realm selectors:** `worldSlug` identifies one authored world only inside the resolved tenant, and `realmSlug` identifies one realm in that world. They are routing selectors rather than tenant identity.
- **Menu selectors:** numbered indices returned by `WORLDS`/`REALMS`/`CHARS`.

The canonical lobby flow after `LOGIN` is:

- `WORLDS` returns a numbered list of visible authored worlds. Each entry carries its stable
  `tenantSlug` and `worldSlug`; the client does not reconstruct either value from display text.
- The canonical textual `<world>` selector is `tenantSlug/worldSlug`. A bare `tenantSlug` is a
  convenience shorthand only when exactly one visible authored world exists for that tenant.
  Otherwise the server returns `WORLD_SELECTION_REQUIRED` and never guesses. A bare `worldSlug`
  is not resolved globally because its uniqueness scope is one tenant.
- `REALMS <world>` lists realms only after `<world>` resolves to exactly one
  `{tenantId, worldSlug}` pair.
- `CHARS <world> [realm]` and `PLAY <world> [realm] [character]` accept the same world selector.
  `[realm]` accepts a `realmSlug` only after the world is resolved. The server resolves the
  resulting selectors to the authoritative `{tenantId, worldSlug, realmSlug, gameInstanceId}`
  routing target before character lookup or gameplay binding.
- A numbered world, realm, or character selector is valid only against the exact ordered response
  and browse snapshot that issued it. Indices are session-local conveniences, not durable
  identifiers, and stale or out-of-range indices fail closed rather than selecting a reordered
  entry.

Outside of lobby selection, services and persistence models use `tenantId` exclusively. No gameplay command other than the lobby selection commands may accept `tenantSlug` as a substitute for `tenantId`.

### Slug Ownership and Stability (Required)

- Game Design owns `tenantSlug` generation and platform-wide uniqueness at tenant creation time.
- Game Design owns authored `worldSlug` allocation and uniqueness within one `tenantId`.
- Game Session owns the player-addressable realm catalog and enforces `realmSlug` uniqueness within
  one `{tenantId, worldSlug}` scope. Realm lifecycle workflows may propose catalog values, but a
  slug becomes selectable only through the authoritative catalog.
- All three slugs are stable for the lifetime of the identity they select. Display-name changes do
  not change a slug, and a deleted identity's slug is not silently reused.
- A slug change requires an explicit, auditable alias/redirect record. An alias is scoped exactly
  like its canonical slug, resolves to the same authoritative identity, has a bounded operator-set
  lifetime, and cannot be introduced or retained when it would make resolution ambiguous. Silent
  changes and heuristic redirects are not permitted.

### Resolution and Carriage

- `WORLDS` carries `{tenantSlug, worldSlug}` for each entry; `REALMS` carries `realmSlug` under the
  already resolved `{tenantId, worldSlug}`; `CHARS` carries character selectors under the already
  resolved realm target.
- `REALMS`, `CHARS`, `PLAY`, bootstrap discovery, connect-token issuance, and reconnect validation
  resolve selectors through the same authoritative realm-catalog and admission-pointer contract.
  No surface may infer tenant or realm identity from display metadata, `gameInstanceId`, ordering,
  or a stale local cache.
- Selector resolution precedes authorization and routing, but successful parsing is not authority.
  Every operation still applies caller visibility, membership, realm-grant, entitlement, and
  current admission-pointer checks. Hidden and nonexistent selectors use the canonical
  non-disclosing failure contract.
- Internal APIs and durable records carry the resolved identifiers, never a menu index. They use
  `tenantId` as tenant authority and qualify routing with `{tenantId, worldSlug, realmSlug}`.

## Consequences

- Docs and examples must not describe humans typing raw `tenantId` values into the gameplay protocol.
- Lobby commands (`WORLDS`/`REALMS`/`CHARS`/`PLAY` and explicit `JOIN`) are the only places where
  player-facing slug selectors are accepted.
- All non-lobby gameplay commands continue to rely on server-side session bindings and do not accept tenant identifiers in their arguments.

## References

- `design/architecture/system-architecture-multi-tenancy.md`
- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-frontend.md`
- `design/architecture/microservices/game-session-service/README.md`
