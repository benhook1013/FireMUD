# ADR 0012: Settings Value Precedence and Constraints

## Status

Accepted

## Implementation Status

The decision is accepted; implementation and proof remain partial. The live path currently merges service-owned operator defaults, tenant overrides, and game-instance overrides, while preset expansion, supported runtime defaults, caps, complete provenance/diagnostics, and cross-service precedence proof remain outstanding. Acceptance records the target decision, not completion; the obligations below define the remaining proof.

## Decision Record

- Decision date: 2026-07-18
- Primary capability: `AR-2.1` Typed settings, defaults, scopes, and precedence
- Affected capabilities: `AR-2.2`, `AR-2.3`, `EA-1.2`, `GR-1.1`, `SF-2.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `SET-01`

## Context

FireMUD needs strong platform defaults, convenient operator deployment profiles, explicit deployment configuration, narrowly supported live operator tuning, and increasingly specific tenant and game-instance customization. The implemented settings path currently merges service-owned operator defaults, tenant overrides, and game-instance overrides. Selected presets, a centralized operator-runtime layer, operator caps, and complete cross-service precedence proof are not implemented yet.

The previous target-state wording placed operator caps in the value-precedence list immediately before tenant/game overrides while also saying tenant/game overrides could never exceed those caps. Treating a cap as an ordinary value layer would allow a later override to replace it, which does not express the intended operator safety authority.

The decision must also prevent every typed key from accidentally acquiring every source. Infrastructure wiring and secrets remain operator-owned, while gameplay-facing keys may deliberately permit tenant or game-instance customization.

## Decision

FireMUD separates effective-setting value precedence from invariant constraint evaluation.

### Value Precedence

For a key that permits all sources, values resolve in increasing order of specificity:

1. hardcoded safe default
2. selected operator preset
3. explicit operator bootstrap configuration
4. supported operator runtime default
5. tenant override
6. game-instance override

Each surfaced key declares which of these sources and scopes may configure it. Sources that are not permitted for the key do not participate in resolution.

Presets are bundles of ordinary typed operator values. They are not a separate settings authority. Explicit operator bootstrap configuration overrides a selected preset value for the same key.

Tenant overrides may replace operator defaults only for tenant-configurable keys. Game-instance overrides may replace tenant-wide values only for keys that permit game-instance configuration. Infrastructure wiring, connection targets, secrets, and other operator-only bootstrap values cannot be overridden by tenant or game-instance data.

### Constraints

Schema/platform hard bounds and configured operator caps are constraints, not value sources.

- Applicable bounds and caps constrain the final candidate value regardless of which value source supplied it.
- No tenant or game-instance override may weaken or bypass them.
- Platform hard bounds are release-owned typed schema metadata. Configured operator caps are held in one environment-scoped, versioned operator-constraint snapshot owned by the shared Game Design settings authority; tenant/game mutation surfaces cannot write that snapshot.
- Bootstrap configuration may seed the initial snapshot. A supported runtime cap change uses an operator-authorized, audited compare-and-set against its monotonic generation; it does not create a second local cap authority in each consumer.
- Every capped key declares its cap support and owning metadata in the generated settings schema. Runtime consumers resolve the same published snapshot generation, report that generation and cap provenance with the effective value, and fail closed for a capped key when the required snapshot cannot be validated rather than silently applying an uncapped value.
- New tenant or game-instance writes that violate an applicable bound or cap are rejected.
- If a later cap change makes an existing persisted override invalid, resolution disregards that override, falls back to the highest-precedence earlier valid value, and exposes a clear diagnostic for explicit remediation.

### Resolution and Provenance

Effective-setting responses identify the winning value source, such as `operatorBootstrap`, `tenantOverride:<tenantId>`, or `gameInstanceOverride:<gameInstanceId>`. They also identify a disregarded invalid override and the constraint that rejected it.

One bounded shared resolver or authoritative read model must eventually apply this contract consistently for all consuming services. The resolver may read values owned by different authorities; this decision does not require all setting sources to move into one database or service. It also does not create a general-purpose distributed configuration platform.

## Consequences

- Operators can select convenient deployment baselines and still override individual values explicitly.
- Creators retain increasingly specific tenant and game-instance control where a key permits it.
- Operator safety authority is explicit because bounds and caps cannot be replaced by later value layers.
- Per-key metadata becomes part of the contract: allowed sources, scopes, bounds, cap support, and provenance behavior must be declared and tested.
- Cap tightening can change an effective value without deleting creator intent. The fallback and diagnostic make that behavior deterministic and visible.
- Runtime consumers must converge on one bounded resolution contract instead of maintaining permanently divergent merge logic.
- Implementing presets, runtime defaults, caps, provenance, invalid-override diagnostics, and complete cross-service proof adds complexity beyond the current three-layer implementation.

## Alternatives Considered

### Make the Current Three-Layer Implementation Canonical

Keep only service/operator defaults, tenant overrides, and game-instance overrides, with fixed schema bounds and no selected presets, runtime operator defaults, or configurable caps. This is simpler and close to the implemented behavior, but it weakens deployment-specific safety controls and makes later introduction of caps a behavioral migration.

### Give Operator Runtime Overrides Final Precedence

Apply operator runtime overrides after tenant and game-instance values. This provides direct emergency control but conflates ordinary operator defaults with forced policy, can silently suppress creator choices, and can reactivate stale values when removed. Explicit constraints are the clearer non-negotiable control.

### Build a General Centralized Configuration Platform

Store, resolve, version, and push every setting from one service. This could improve convergence and auditability but creates an unnecessary availability and operations dependency for the currently bounded typed domains. FireMUD retains bounded cached reads and explicit refresh/eviction unless future evidence justifies revisiting distribution.

## Implementation and Proof Obligations

- Extend typed per-key metadata to declare allowed value sources, scopes, hard bounds, and operator-cap support.
- Implement the environment-scoped operator-constraint snapshot, monotonic generation, bootstrap seeding, operator-authorized compare-and-set mutation, and immutable audit record in the Game Design settings authority.
- Implement preset expansion before explicit bootstrap values without creating a second authority.
- Implement only explicitly supported operator runtime defaults with appropriate persistence and audit semantics.
- Reject invalid tenant/game-instance writes against current hard bounds and caps.
- Prove deterministic fallback and diagnostics when cap changes invalidate persisted overrides.
- Prove every consumer observes the same cap generation and fails closed rather than accepting an uncapped candidate during missing, stale, or conflicting snapshot state.
- Report complete winning-source and invalid-override provenance on effective-setting inspection surfaces.
- Prove the full applicable precedence matrix and equivalent effective results across every consuming service.
- Keep infrastructure-only keys outside tenant and game-instance mutation surfaces.

## Reversibility and Revisit Triggers

Individual keys may omit unused sources, which keeps the model incrementally adoptable. Removing an already exposed source or changing precedence after operators or creators depend on it is a behavioral migration and requires a new decision review.

Revisit this decision if bounded cached reads cannot meet measured consistency requirements, if a final-precedence emergency operator mechanism is required beyond constraints, or if preset and cap complexity provides insufficient product value to justify its implementation and proof burden.

## Required Documentation Alignment

The following sources must remain aligned with this decision:

- `design/architecture/system-architecture-settings-model.md`
- `design/architecture/generated/platform-settings-schema.json`
- `design/architecture/generated/platform-settings-reference.md`
- `design/architecture/microservices/game-session-service/configuration.md`
- `design/architecture/microservices/game-logic-service/configuration.md`
