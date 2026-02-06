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

The gameplay text protocol uses `tenantId` only:

- Canonical command: `ENTER_GAME <tenantId> [characterId]`.
- Any UX-friendly identifier (slug/name) must be resolved to `tenantId` by first-party tools using explicit APIs before issuing gameplay commands.

If a slug/alias is introduced in the future, it must be defined as a separate, explicit contract (owner service, stability rules, resolution API, audit rules) and must not silently change gameplay command parsing.

## Consequences

- All docs and examples must use `tenantId`, not `tenantIdOrSlug`.
- Clients that want human-friendly selection must call a control-plane API to list/resolve tenants and then use the returned `tenantId` in gameplay commands.

## References

- `design/architecture/system-architecture-multi-tenancy.md`
- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-frontend.md`
- `design/architecture/microservices/game-session-service/README.md`

