# ADR 0005: Tenant Identifiers in Gameplay Protocol

## Status

Accepted

## Context

Some documents described gameplay commands using `tenantIdOrSlug` (for example `ENTER_GAME <tenantIdOrSlug>`), but the Multi-Tenancy model defines tenants as GUID-like `tenantId` strings and does not define:

- What a “tenant slug” is,
- Who owns slug generation and uniqueness,
- How slugs resolve to `tenantId`,
- Whether slugs are stable across renames or exports,
- How authorization and audit should treat slug inputs.

Leaving the gameplay protocol ambiguous creates interoperability problems for clients and makes error codes (`TENANT_NOT_FOUND`) underspecified.

## Decision

FireMUD uses a single shared gameplay entrypoint for many worlds. The gameplay text protocol must therefore support a player-friendly world selection flow without requiring humans to type opaque GUIDs.

The gameplay protocol uses two distinct identifier layers:

- **Internal identifiers (authoritative):** `tenantId` (opaque GUID) and `characterId` (opaque).
- **Player-facing selectors (lobby only):** `tenantSlug` (stable, human-friendly) plus numbered menu indices returned by `WORLDS`/`CHARS`.

The canonical lobby flow after `LOGIN` is:

- `WORLDS` returns a numbered list of worlds, including each world’s stable `tenantSlug`.
- `CHARS <world>` accepts either a world menu index or a `tenantSlug`.
- `PLAY <world> [character]` accepts either a world menu index or a `tenantSlug`, and an optional character menu index/name, then resolves those selectors to `{tenantId, characterId}` server-side and binds the gameplay session.

Outside of lobby selection, services and persistence models use `tenantId` exclusively. No gameplay command other than the lobby selection commands may accept `tenantSlug` as a substitute for `tenantId`.

### Slug Ownership and Stability (Required)

- The Game Design Service owns `tenantSlug` generation and uniqueness at tenant creation time.
- `tenantSlug` is stable for the lifetime of the tenant; renaming a world changes display name, not slug.
- If operators ever need to change a slug, it must be implemented as an explicit alias/redirect contract (old slug continues to resolve for a bounded period) and must be auditable. Silent slug changes are not permitted.

## Consequences

- Docs and examples must not describe humans typing `tenantId` GUIDs into the gameplay protocol.
- Lobby commands (`WORLDS`/`CHARS`/`PLAY`) are the only place where `tenantSlug` is accepted.
- All non-lobby gameplay commands continue to rely on server-side session bindings and do not accept tenant identifiers in their arguments.

## References

- `design/architecture/system-architecture-multi-tenancy.md`
- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-frontend.md`
- `design/architecture/microservices/game-session-service/README.md`
