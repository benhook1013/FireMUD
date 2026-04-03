# FireMUD System Architecture: Settings Model

This document defines the canonical FireMUD settings model for operator/bootstrap configuration, operator runtime overrides, and future tenant/game behavior configuration.

## Implemented Status

- The first typed operator-default settings seams are live:
  - `firemud.reconnection`
  - `firemud.communication`
  - `firemud.presentation`
  - `firemud.movement`
  - `firemud.world-topology`
- Game Session and Game Logic now publish generation-ready configuration metadata and service-level configuration reference docs for the surfaced domains above.
- The canonical layered ownership model is agreed and documented here, but the full tenant/game override persistence layer and shared effective-config resolver are still future work.

## Canonical Decisions

- Operator/bootstrap and infrastructure settings live in file/env-backed service configuration.
- Some operator-scoped settings may also support deliberate runtime override through operator/admin tooling without becoming ordinary tenant/game settings.
- Tenant/game behavior settings should eventually live in a database-backed design/settings authority rather than being scattered across service-local tables.
- Effective configuration is a validated merge, not a single source.
- Generated docs and later admin/creator tooling should come from one typed schema/metadata source of truth rather than hand-maintained parallel references.

## Ownership and Precedence

FireMUD should resolve settings in this order:

1. hardcoded safe defaults
2. operator bootstrap file/env settings
3. operator runtime overrides where explicitly supported
4. operator caps and bounds
5. tenant/game overrides

Tenant/game overrides are always constrained by operator caps where those caps exist.

This means:

- infrastructure wiring, connection targets, and service bootstrap values stay operator-owned;
- live operational tuning may be operator-overridable at runtime when there is a real need;
- player-visible game behavior should not remain buried in code or raw Spring properties forever.

## Canonical Domains

The current settings domains are:

- `reconnection`
- `communication`
- `presentation`
- `movement`
- `worldTopology`

The next expected gameplay-facing domains are:

- `inventory`
- `equipment`
- later combat or transcript-overlay policy if those become explicit platform-level settings

The important rule is that domains represent stable behavior areas, not individual commands. For example, `LOOK`, `QUICKLOOK`, reconnect redraw, and movement-triggered room refresh all belong under presentation and transcript policy rather than separate action-specific settings trees.

## Supported Scopes

Each surfaced setting should declare one of these scopes:

- operator-only
- operator-runtime-overridable
- tenant/game-configurable
- tenant/game-configurable within operator-enforced caps

Internal transport/framework constants should not be promoted into this model unless they are deliberately meant to be operator- or game-facing.

## Schema Metadata

Every surfaced setting should carry at least:

- stable key/path
- description
- default
- valid range or enum
- scope/owner
- whether hot-reloadable
- whether advanced
- example value

The current Spring configuration metadata is the first live step toward that schema. Later generated markdown/schema output and admin/creator tooling should read from the same typed metadata source rather than inventing their own setting definitions.

## Effective Config Resolution

The eventual target state is one shared settings authority or read model that resolves effective configuration for gameplay-facing domains.

That later authority should:

- own tenant/game behavior settings
- apply operator caps and defaults consistently
- present resolved effective values to services without each service inventing its own merge logic

This does not need to become a full distributed config platform. A bounded authoritative settings read model is enough.

## Current Practical Rule

Until the shared settings authority exists:

- surfaced file/env-backed settings in Game Session and Game Logic are the operator-default layer;
- service docs and metadata should stay honest about what is operator-default today versus what is planned as tenant/game-configurable later;
- new gameplay slices should attach their first settings seams to this model rather than adding fresh hardcoded constants and documenting them after the fact.
