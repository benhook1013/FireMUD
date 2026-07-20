# FireMUD System Architecture: Settings Model

This document defines the canonical FireMUD settings model for operator/bootstrap configuration, operator runtime overrides, and future tenant/game behavior configuration.

## Implemented Status

- The first typed operator-default settings seams are live:
  - `firemud.reconnection`
  - `firemud.communication`
  - `firemud.presentation`
  - `firemud.movement`
  - `firemud.world-topology`
  - `firemud.command-history`
  - `firemud.command-capabilities`
- Game Session and Game Logic now publish generation-ready configuration metadata and service-level configuration reference docs for the surfaced domains above.
- The first consolidated generated publication outputs are now checked in at `design/architecture/generated/platform-settings-schema.json` and `design/architecture/generated/platform-settings-reference.md`, both produced from the surfaced Spring metadata plus one machine-readable publication spec for the extra operator/admin fields.
- The canonical layered ownership model is agreed and documented here.
- Game Design now owns the first shared persisted tenant/game settings authority for `reconnection`, `communication`, `presentation`, `movement`, `worldTopology`, `commandHistory`, and `commandCapabilities`.
- `common-platform-core` now owns the first shared effective persisted-override resolver for those surfaced domains, merging tenant then game-instance overrides into one bounded read model for runtime consumers.
- Game Session and Game Logic now consume that shared merged persisted layer and apply their service-owned operator defaults on top.
- Game Session exposes the current effective result at `/actuator/settings/effective`, including resolved `presentation`, `prompts`, `reconnection`, `movement`, `worldTopology`, `commandHistory`, and `commandCapabilities`, plus normalized subgroup views for the live room-view/transcript seams (`transcriptRendering`, `reconnectionPolicy`, and `reconnectBuffer`), movement/topology seams (`movementPostMoveView`, `worldTopologyScopeModel`, and `worldTopologyRegionBehavior`), and the current scoped `communication` override layer it sees for the same session or synthesized scope.
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

FireMUD resolves each effective setting from the applicable value sources below in increasing order of specificity:

1. hardcoded safe defaults
2. selected operator preset baseline
3. explicit operator bootstrap file/env configuration
4. supported operator runtime defaults
5. tenant overrides
6. game-instance overrides

Not every setting participates in every source. Each surfaced key must declare which sources and scopes may configure it. Infrastructure wiring, connection targets, secrets, and service bootstrap values remain operator-owned and cannot acquire tenant or game-instance overrides merely because they use the same typed settings machinery.

Presets and bootstrap configuration use the same typed setting key space. A preset expands into a bundle of ordinary operator baseline values; it is not a second settings authority. An explicit bootstrap value overrides the selected preset value for the same key.

Value precedence is separate from constraint evaluation. Schema and platform hard bounds always apply, and configured operator caps constrain tenant and game-instance values for keys that declare such caps. A later value source can never override a hard bound or operator cap.

- New tenant or game-instance writes that violate an applicable bound or cap are rejected.
- If an operator later tightens a cap so an existing persisted override becomes invalid, the resolver disregards that invalid override, falls back to the highest-precedence earlier valid value, and exposes a clear diagnostic suitable for operator and creator remediation.
- Effective-setting responses report the value's provenance, including the winning source and any disregarded invalid override.

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
- `commandHistory`
- `commandCapabilities`

The next expected gameplay-facing domains are:

- `actorState`
- `inventory`
- `equipment`
- later combat or transcript-overlay policy if those become explicit platform-level settings

The important rule is that domains represent stable behavior areas, not individual commands. For example, `LOOK`, `QUICKLOOK`, reconnect redraw, and movement-triggered room refresh all belong under presentation and transcript policy rather than separate action-specific settings trees.

## Current Domain Groupings

The currently surfaced subgroup names are:

- `reconnection.policy`
- `reconnection.buffer`
- `communication.behavior`
- `prompts.coalescing`
- `prompts.transportPresentation`
- `transcript.reconnectBuffer`
- `transcript.rendering`
- `transcript.overlayPolicy`
- `movement.postMoveView`
- `worldTopology.scopeModel`
- `worldTopology.regionBehavior`
- `commandHistory.retention`
- `commandCapabilities.availability`

These group names are the canonical behavior buckets even when the first live file/env-backed properties are still split across service-local configuration classes. Service-local property classes must map back into one shared settings model rather than becoming unrelated permanent config blobs.

The next locked actor-state grouping is `actorState.capacityChangePolicy`. It is not surfaced by the current implementation yet, so it must not be represented as a live generated setting until its owning runtime implementation exists.

## Supported Scopes

Each surfaced setting should declare one of these scopes:

- operator-only
- operator-runtime-overridable
- tenant/game-configurable
- tenant/game-configurable within operator-enforced caps

Internal transport/framework constants should not be promoted into this model unless they are deliberately meant to be operator- or game-facing.

## Current and Planned Scope Examples

Today, operator defaults still come from service-local typed properties, while tenant/game overrides for the surfaced pre-`06` domains are persisted in the shared Game Design authority. The agreed scope for live domains and the locked target scope for the next domains is:

- `reconnection.policy`
  - tenant/game-configurable today for resume windows and stale-resume fallback over service-local operator defaults
  - operator caps remain future work
- `reconnection.buffer`
  - tenant/game-configurable today for durable transcript retention bounds over service-local operator defaults
  - operator caps remain future work
- `communication.behavior`
  - tenant/game-configurable today for message limits and whisper observer-metadata policy
  - standard communication availability is owned by `commandCapabilities.availability`, not mode-specific communication settings
- `prompts.coalescing` and `prompts.transportPresentation`
  - tenant/game-configurable today through shared persisted presentation/prompt overrides over Game Session defaults
  - richer game-defined prompt composition remains future work
- `transcript.rendering`
  - tenant/game-configurable today for room-view and transcript presentation defaults such as briefness and color policy
- `movement.postMoveView`
  - tenant/game-configurable today over service-local operator defaults
  - operator caps remain future work
- `worldTopology.scopeModel` and `worldTopology.regionBehavior`
  - tenant/game-configurable today over service-local operator defaults
- `commandHistory.retention`
  - operator defaults provide the initial bounded maximum
  - tenant/game overrides control the retained/displayable accepted-command bound within the platform maximum
- `commandCapabilities.availability`
  - operator defaults seed standard social, presence, inventory, and command-history availability
  - tenant/game overrides control those standard command families through one persisted DML-backed policy
- `actorState.capacityChangePolicy`
  - operator defaults establish the initial safe capacity-normalization policy
  - tenant/game overrides select the default policy used when a continuous source changes a bounded resource maximum
  - a published continuous effect declaration may carry a more-specific override for the maximum change caused by that source; it does not replace the effective setting for other sources

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
- machine-readable schema output
- generated Markdown settings reference
- later admin/creator form metadata

Later admin/operator or creator tooling should consume the generated machine-readable schema output and avoid inventing a second schema for the same surfaced keys. The repo should not grow a second hand-maintained settings encyclopedia that drifts away from the typed metadata and generated reference.

## Effective Config Resolution

The eventual target state is one bounded shared resolver or read model that resolves effective configuration for gameplay-facing domains consistently across consuming services.

That later resolver or read model should:

- own tenant/game behavior settings
- apply the canonical value precedence and source eligibility for every surfaced key
- enforce schema/platform hard bounds and operator caps separately from value precedence
- reject invalid writes and handle cap-invalidated persisted overrides through the canonical fallback and diagnostic rule
- present resolved effective values and provenance to services without each service inventing its own merge logic

This does not need to become a full distributed config platform. A bounded authoritative settings read model is enough.

### Distribution and Freshness

The bounded read model uses pull-based authoritative snapshots and process-local caches. Every returned scope snapshot carries a monotonic revision, and every surfaced domain or key declares a freshness class and maximum stale interval. A successful settings write is therefore guaranteed to become visible within its declared bound; it is not described as globally instantaneous.

During an authority outage, a consumer may retain its last-known-good snapshot only within the applicable stale interval. After that interval, ordinary presentation preferences may use a declared safe fallback, while admission, tenant-isolation, resource-safety, and other restrictive policy must fail closed or consult a dedicated authoritative fence. A consumer must never preserve an indefinitely stale permissive value merely to maintain availability.

Immediate revocation and emergency fencing are separate control-plane concerns rather than ordinary cached settings. A future notification-plus-pull optimization may reduce refresh latency when measurements justify it, but notifications do not carry authoritative values and missed notifications cannot invalidate the correctness of revisioned pull and bounded staleness.

## Current Practical Rule

Current practical rule:

- surfaced `firemud.*` typed properties remain the operator-default layer in each owning runtime service;
- Game Design owns persisted tenant/game overrides for the currently surfaced pre-`06` settings domains, including standard command capabilities;
- `common-platform-core` resolves one merged persisted override layer per `{tenantId, optional gameInstanceId}` by applying tenant overrides before game-instance overrides;
- runtime services consume that shared merged persisted layer and perform only the final merge with their own typed operator defaults for now;
- Game Session exposes the resolved `presentation`, `prompts`, `reconnection`, `movement`, `worldTopology`, `commandHistory`, and `commandCapabilities` result through `/actuator/settings/effective`, and also includes normalized subgroup payloads for transcript, movement, and world-topology seams plus the current scoped `communication` override view for the same session or synthesized scope, while Game Logic exposes the fully merged effective `communication` result through `/actuator/settings/effective/communication`;
- the first authority stays bounded and domain-oriented; it is not a general distributed config platform;
- cache invalidation remains bounded and local to each runtime process through explicit refresh/evict operations on the shared reader rather than a distributed push fabric;
- authoritative snapshots must eventually expose monotonic scope revisions and schema-declared freshness classes; the current reader does not yet implement class-specific stale expiry or safe fallback/fail-closed behavior;
- centralized operator-default/caps resolution ownership and preset expansion remain later slices rather than compatibility scaffolding in this one.
