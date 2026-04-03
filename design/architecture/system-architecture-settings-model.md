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
- The canonical layered ownership model is agreed and documented here.
- Game Session now also has the first bounded effective-settings read surface for `presentation`, `movement`, and `worldTopology`, merging operator defaults with scoped file/env overrides.
- Game Session now exposes that bounded read surface at `/actuator/settings/effective`, including the current `reconnection` defaults alongside the resolved Game Session-owned domains for operator/debug inspection.
- Game Logic now exposes the current effective `communication` defaults at `/actuator/settings/effective/communication`.
- `communication` and `reconnection` still resolve directly from their typed property classes rather than a shared cross-service settings authority.
- The full DB-backed tenant/game override persistence layer and shared cross-service settings authority are still future work.

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

## Current Domain Groupings

The currently surfaced subgroup names are:

- `reconnection.policy`
- `reconnection.buffer`
- `communication.defaults`
- `prompts.coalescing`
- `prompts.transportPresentation`
- `transcript.reconnectBuffer`
- `transcript.rendering`
- `transcript.overlayPolicy`
- `movement.postMoveView`
- `worldTopology.scopeModel`
- `worldTopology.regionBehavior`

These group names are the canonical behavior buckets even when the first live file/env-backed properties are still split across service-local configuration classes. Service-local property classes must map back into one shared settings model rather than becoming unrelated permanent config blobs.

## Supported Scopes

Each surfaced setting should declare one of these scopes:

- operator-only
- operator-runtime-overridable
- tenant/game-configurable
- tenant/game-configurable within operator-enforced caps

Internal transport/framework constants should not be promoted into this model unless they are deliberately meant to be operator- or game-facing.

## Current Practical Scope Examples

Today, all surfaced FireMUD settings are still operator-controlled. Some Game Session domains now support bounded scoped file/env overrides in addition to operator defaults. The agreed target scope for the current domains is:

- `reconnection.policy`
  - operator-only today
  - later tenant/game-configurable within operator caps for resume windows and stale-resume fallback
- `reconnection.buffer`
  - operator-only today
  - later tenant/game-configurable within operator caps for transcript retention bounds
- `communication.defaults`
  - operator-only today
  - later tenant/game-configurable within operator caps for built-in communication mode availability and whisper observer-metadata policy
- `prompts.coalescing` and `prompts.transportPresentation`
  - operator-only today
  - later tenant/game-configurable for game-defined prompt behavior and player-facing transport defaults
- `transcript.rendering`
  - operator-only today
  - later tenant/game-configurable for room-view and transcript presentation defaults such as briefness and color policy
- `movement.postMoveView`
  - operator-only today
  - later tenant/game-configurable within operator caps
- `worldTopology.scopeModel` and `worldTopology.regionBehavior`
  - operator-only today
  - later tenant/game-configurable when topology becomes part of per-game design state

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

The expected generated or generation-ready outputs are:

- Spring configuration metadata for surfaced keys
- service-level generated-facing configuration reference docs
- later machine-readable schema output
- later admin/creator form metadata

The repo should not grow a second hand-maintained settings encyclopedia that drifts away from the typed metadata and surfaced configuration docs.

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
- Game Session may merge bounded scoped file/env overrides for the first live gameplay-facing domains already inside the bounded read surface, namely `presentation`, `movement`, and `worldTopology`;
- Game Session exposes the resolved result of that bounded read surface through `/actuator/settings/effective`, while Game Logic exposes current `communication` defaults through `/actuator/settings/effective/communication`;
- `communication` and `reconnection` remain direct property-driven operator-default config for now;
- service docs and metadata should stay honest about what is operator-default today versus what is planned as tenant/game-configurable later;
- new gameplay slices should attach their first settings seams to this model rather than adding fresh hardcoded constants and documenting them after the fact;
- runtime services should use the documented effective-settings read surface where one already exists, rather than inventing overlapping merge layers;
- slice docs should record the intended future tenant/cap story for each surfaced domain even when the live implementation is still operator-only.
