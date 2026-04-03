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
- Game Design now owns the first shared persisted tenant/game settings authority for `reconnection`, `communication`, `presentation`, `movement`, and `worldTopology`.
- `common-platform-core` now owns the first shared effective persisted-override resolver for those surfaced domains, merging tenant then game-instance overrides into one bounded read model for runtime consumers.
- Game Session and Game Logic now consume that shared merged persisted layer and apply their service-owned operator defaults on top.
- Game Session exposes the current effective result at `/actuator/settings/effective`, including resolved `reconnection`, `presentation`, `movement`, and `worldTopology`.
- Game Logic exposes the current effective `communication` result at `/actuator/settings/effective/communication`.
- The shared authority reader now has explicit bounded local cache semantics: normal reads use a short TTL cache, callers may force refresh, and callers may evict one scope locally. Distributed push invalidation, full centralized operator-default/caps resolution, and preset-baseline expansion are still future work.

## Canonical Decisions

- Operator/bootstrap and infrastructure settings live in file/env-backed service configuration.
- Some operator-scoped settings may also support deliberate runtime override through operator/admin tooling without becoming ordinary tenant/game settings.
- Tenant/game behavior settings should eventually live in a database-backed design/settings authority rather than being scattered across service-local tables.
- Effective configuration is a validated merge, not a single source.
- FireMUD should prefer strong defaults and sparse override usage. Most operators should not need to tune many individual knobs to reach a sane deployment baseline.
- Generated docs and later admin/creator tooling should come from one typed schema/metadata source of truth rather than hand-maintained parallel references.

## Ownership and Precedence

FireMUD should resolve settings in this order:

1. hardcoded safe defaults
2. selected preset baseline
3. operator bootstrap file/env settings
4. operator runtime overrides where explicitly supported
5. operator caps and bounds
6. tenant/game overrides

Tenant/game overrides are always constrained by operator caps where those caps exist.

This means:

- most deployments should start from a small number of named preset baselines rather than requiring manual tuning of many individual settings;
- infrastructure wiring, connection targets, and service bootstrap values stay operator-owned;
- live operational tuning may be operator-overridable at runtime when there is a real need;
- player-visible game behavior should not remain buried in code or raw Spring properties forever.

## Preset Baselines

FireMUD should support a small number of operator-facing preset baselines for common deployment shapes. A preset is a named bundle of predefined setting values that overrides the hardcoded safe defaults before normal operator overrides apply.

The important rule is that presets are a convenience baseline, not a second parallel settings system. Operators should still be able to:

- start from a preset that matches the deployment shape;
- override only the specific settings that need to differ;
- apply higher-precedence runtime overrides and caps where explicitly supported.

This keeps the operator experience defaults-first:

- most operators should rarely need to touch most settings;
- advanced knobs remain available for exceptional cases rather than mandatory setup;
- FireMUD avoids a model where hobby or SaaS operators must hand-tune dozens of unrelated values before the system feels sane.

The first preset model should stay small and operator-facing. It does not require a full preset-management UI before the underlying settings authority exists.

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

Today, operator defaults still come from service-local typed properties, while tenant/game overrides for the surfaced pre-`06` domains are persisted in the shared Game Design authority. The agreed target scope for the current domains is:

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

Current practical rule:

- surfaced `firemud.*` typed properties remain the operator-default layer in each owning runtime service;
- Game Design owns persisted tenant/game overrides for the currently surfaced pre-`06` settings domains;
- `common-platform-core` resolves one merged persisted override layer per `{tenantId, optional gameInstanceId}` by applying tenant overrides before game-instance overrides;
- runtime services consume that shared merged persisted layer and perform only the final merge with their own typed operator defaults for now;
- Game Session exposes the resolved result through `/actuator/settings/effective`, while Game Logic exposes current effective `communication` through `/actuator/settings/effective/communication`;
- the first authority stays bounded and domain-oriented; it is not a general distributed config platform;
- cache invalidation remains bounded and local to each runtime process through explicit refresh/evict operations on the shared reader rather than a distributed push fabric;
- centralized operator-default/caps resolution ownership and preset expansion remain later slices rather than compatibility scaffolding in this one.
