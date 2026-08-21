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
- `common-platform-core` now owns the first shared effective persisted-override resolver for those surfaced domains, merging tenant then game-instance overrides into one bounded read model for runtime consumers; the stable-namespace scope required by `reconnection.buffer` remains a documented convergence gap until that resolver accepts `playableStateNamespaceId`.
- Game Session and Game Logic now consume that shared merged persisted layer and apply their service-owned operator defaults on top.
- Game Session exposes the current effective result at `/actuator/settings/effective`, including resolved `presentation`, `prompts`, `reconnection`, `movement`, `worldTopology`, `commandHistory`, and `commandCapabilities`, plus normalized subgroup views for the live room-view/transcript seams (`transcriptRendering`, `reconnectionPolicy`, and `reconnectBuffer`), movement/topology seams (`movementPostMoveView`, `worldTopologyScopeModel`, and `worldTopologyRegionBehavior`), and the current scoped `communication` override layer it sees for the same session or synthesized scope.
- Game Logic exposes the current effective `communication` result at `/actuator/settings/effective/communication`.
- The shared authority reader now has explicit bounded local cache semantics: normal reads use a short TTL cache, callers may force refresh, and callers may evict one scope locally. Distributed push invalidation, full centralized operator-default/caps resolution, and preset-baseline expansion are still future work.
- The current reader/proof gap is explicit: short-TTL, force-refresh, and per-scope eviction do not yet prove monotonic revision handling, class-specific stale behavior, or the restrictive-setting fence. The reader applies the canonical per-key/domain freshness maximum once that typed metadata is present; it does not select a local alternative.
- The target reconnect-settings contract separates `reconnection.policy` resume eligibility from `reconnection.buffer` bounded semantic reconnect-context retention/resource controls. The latter never grants active-session, resume, or replay authority.
- The current reconnect-context implementation/proof gap is explicit: Game Session retains a single oversized entry above `hardMaxBytes`, and its current key/envelope accounting uses `gameInstanceId` rather than the canonical `playableStateNamespaceId` scope. Complete scope-bound schema-envelope accounting, stable-namespace persisted `reconnection.buffer` resolution, namespace migration, and omission/marker enforcement remain unproved.

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

Value precedence is separate from constraint evaluation. Schema and platform hard bounds always apply, and configured operator caps constrain the final candidate value from every source for keys that declare such caps. A later value source can never override a hard bound or operator cap; bootstrap and runtime-default candidates are not exempt merely because the operator supplied them.

- Platform hard bounds are release-owned typed schema metadata. Configured operator caps are stored as one environment-scoped, versioned operator-constraint snapshot in the shared Game Design settings authority. Bootstrap configuration may seed that snapshot; supported runtime mutation is operator-authorized, audited, and compare-and-set against its monotonic generation. Tenant/game routes cannot write operator constraints.
- Every capped key declares cap support in the generated settings schema. Effective reads expose the validated cap value, provenance, and snapshot generation. A consumer that cannot validate the required snapshot for a capped key fails closed for that setting rather than applying an uncapped candidate or inventing a service-local cap generation.
- New tenant or game-instance writes that violate an applicable bound or cap are rejected.
- If an operator later tightens a cap so persisted overrides become invalid, the resolver evaluates permitted candidates from most-specific to least-specific against one validated constraint-snapshot generation. It independently disregards each invalid game-instance or tenant layer, emits a diagnostic for every discarded layer, and selects the first remaining valid candidate; resolution fails closed when no required constrained candidate remains valid.
- Effective-setting responses report the value's provenance, including the winning source and every disregarded invalid override with its rejecting constraint and constraint-snapshot generation.

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
- apply supported higher-precedence runtime defaults and separately enforced caps where explicitly configured.

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
  - tenant/game-configurable today for resume windows and stale-resume fallback over service-local operator defaults; these settings govern resume eligibility, not retention
  - operator caps remain future work
- `reconnection.buffer`
  - tenant/game-configurable today for bounded semantic reconnect-context retention/resource bounds over service-local operator defaults; retention never grants resume or replay authority
  - because the soft and hard byte ceilings form one effective invariant over each complete scope-bound persisted envelope while operator defaults remain service-local, a tenant override that changes either ceiling must persist both values; the stable playable-state namespace override may set one ceiling only when the tenant layer supplies the complete pair, and tenant mutations validate existing namespace children against the prospective parent. The namespace override follows `{tenantId, playableStateNamespaceId}` across runtime replacement; it is not keyed by `gameInstanceId`.
  - min-message and min-line floors and the soft ceiling remain subordinate, best-effort retention preferences; none may override the target absolute hard ceiling, and a complete scope-bound persisted envelope exceeding it must be omitted or represented by a bounded marker rather than partially truncated. The current Game Session runtime can retain one oversized entry, so complete-envelope omission/marker enforcement and proof remain gaps.
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
- a recognized freshness class and one canonical, finite, non-negative maximum stale age for that key/domain; zero means that no stale last-known-good value may be used
- whether hot-reloadable
- whether advanced
- example value

Schema publication rejects a surfaced setting when its freshness class or maximum stale age is missing, unsupported, non-finite, or negative, or when its declared expiry behavior is incompatible with that class. Restrictive and authoritative settings fail closed or hold their authoritative fence once the canonical maximum is exceeded; only a setting explicitly classified as harmless presentation may retain bounded last-known-good behavior or use its documented safe fallback.

The current Spring configuration metadata is the first live step toward that schema. Later generated markdown/schema output and admin/creator tooling should read from the same typed metadata source rather than inventing their own setting definitions.

The expected generated or generation-ready outputs are:

- Spring configuration metadata for surfaced keys
- service-level generated-facing configuration reference docs
- machine-readable schema output
- generated Markdown settings reference
- later admin/creator form metadata

Later admin/operator or creator tooling should consume the generated machine-readable schema output and avoid inventing a second schema for the same surfaced keys. The repo should not grow a second hand-maintained settings encyclopedia that drifts away from the typed metadata and generated reference.

## Effective Config Resolution

The target state **must** provide one bounded shared resolver or read model that resolves effective configuration for gameplay-facing domains consistently across consuming services.

That resolver or read model must:

- read and resolve tenant/game behavior settings from their owning authorities
- read the Game Design-owned versioned environment-scoped operator-constraint snapshot while keeping its mutation surface operator-only
- apply the canonical value precedence and source eligibility for every surfaced key
- enforce schema/platform hard bounds and operator caps separately from value precedence
- reject invalid writes and handle cap-invalidated persisted overrides through the canonical fallback and diagnostic rule
- present resolved effective values and provenance to services without each service inventing its own merge logic
- present the validated operator-constraint generation with every capped effective value so cross-service consumers can prove convergence

This does not need to become a full distributed config platform. A bounded authoritative settings read model is enough.

### Distribution and Freshness

[ADR 0113](./decisions/adr-0113-bounded-pull-settings-distribution-with-freshness-classes.md) makes distribution a typed, revisioned pull contract rather than a generalized push fabric. Consumers may retain bounded local last-known-good snapshots, but each key or domain declares a freshness class and one canonical maximum stale age in its typed contract. Consumers apply that maximum; they do not select a competing local bound. Presentation-only settings may use an explicitly safe fallback when that bound is exceeded; restrictive or authoritative settings fail closed or hold an authoritative fence, and urgent revocation uses a separate immediate path. Notification plus pull may be an optimization, but notifications do not become the authority. Concrete freshness durations remain schema-policy work.

At expiry, fail-closed and fence-retention are two representations of the same restrictive direction, not competing fallback choices. An expired harmless-presentation key returns only its schema-declared safe fallback; an expired restrictive key returns no permissive effective value and denies the dependent action; and an expired fence-valued key retains the last authoritative restrictive fence without treating its stale payload as permission. The effective-result diagnostics retain the last accepted revision, provenance, and observation age, mark the key or domain expired, and identify which of those schema-declared dispositions was applied. Urgent revocation is not an expiry disposition and remains on its separate authoritative path.

## Current Practical Rule

Current practical rule:

- surfaced `firemud.*` typed properties remain the operator-default layer in each owning runtime service;
- Game Design owns persisted tenant/game overrides for the currently surfaced pre-`06` settings domains, including standard command capabilities;
- `common-platform-core` resolves one merged persisted override layer per `{tenantId, optional gameInstanceId}` by applying tenant overrides before game-instance overrides for ordinary domains; `reconnection.buffer` is the stable-namespace exception and must resolve tenant before `{tenantId, playableStateNamespaceId}` overrides so runtime replacement inherits its bounds. The current resolver has not completed that namespace migration;
- runtime services consume that shared merged persisted layer and perform only the final merge with their own typed operator defaults for now;
- resume eligibility and bounded semantic reconnect-context retention/resource controls remain separate; retention never grants active-session, resume, or replay authority;
- Game Session exposes the resolved `presentation`, `prompts`, `reconnection`, `movement`, `worldTopology`, `commandHistory`, and `commandCapabilities` result through `/actuator/settings/effective`, and also includes normalized subgroup payloads for transcript, movement, and world-topology seams plus the current scoped `communication` override view for the same session or synthesized scope, while Game Logic exposes the fully merged effective `communication` result through `/actuator/settings/effective/communication`;
- the first authority stays bounded and domain-oriented; it is not a general distributed config platform;
- cache invalidation remains bounded and local to each runtime process through explicit refresh/evict operations on the shared reader rather than a distributed push fabric;
- centralized operator-default/caps resolution ownership and preset expansion remain later slices rather than compatibility scaffolding in this one.
