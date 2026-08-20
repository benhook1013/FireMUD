# ADR 0124: Materialized Starter Profiles with Conservative Draft Upgrades

## Status

Accepted

## Implementation Status

The profile catalog, composition engine, materialization path, exact baseline retention, per-object source mapping, deleted/detached lineage, upgrade planner, conflict-resolution UI, and upgrade-commit workflow are not implemented. The implementation and proof obligations below are target state, not a claim of current capability.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `CONTENT-04`
- Decision date: 2026-07-20
- Decision key: `CONTENT-04`
- Primary capability: `AR-2.2` starter profiles and reusable game-design baselines
- Affected capabilities: `AR-1.1`, `AR-1.5`, `AR-2.3`, `GR-4.1`, `GR-4.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of materialization, runtime inheritance, repeated profile upgrades, identity and deletion lineage, conservative merge behavior, settings, and plugin compatibility

## Context

Starter profiles let a creator begin with a coherent game without hand-authoring every common stat, condition, action, targeting policy, feedback declaration, and script. The imported content must remain fully creator-owned: editing or deleting it cannot reveal a hidden platform fallback or make running gameplay depend on a moving profile.

A one-time copy with only package-level provenance would preserve that ownership but could accidentally make safe profile upgrades impossible. Future upgrades need to distinguish unchanged imported content from creator changes, deliberate deletions, semantic replacements, and broken references. A general field-level or JSON merge, however, would need asset-specific semantics and could produce structurally valid but invalid gameplay declarations.

Plugin bundles have a separate trust and compatibility contract. They are immutable platform-attested artifacts bound to an exact base version and must not be rewritten as a side effect of a profile upgrade.

## Decision

A selected base profile and its ordered extension packs materialize ordinary game-owned DML and ordinary scripts into a Draft. The profile and packs never remain a runtime inheritance layer, never supply runtime fallback behavior, and never change an existing game merely because a newer profile revision is published.

The first materialization implementation must nevertheless preserve an explicit future upgrade path. For every application it records or makes permanently reconstructible:

- the immutable exact resolved baseline that was applied;
- base-profile and pack identities, revisions, hashes, composition order, explicit override relationships, and materialization schema/compiler version;
- stable namespaced source identities and their source-to-game authored-identifier mappings;
- the application commit and subsequent upgrade-commit lineage; and
- explicit state for locally deleted, detached, retained, or otherwise resolved imported objects.

A creator-initiated profile upgrade operates only on a Draft and compares:

- `O`: the exact old resolved profile composite used as the current upgrade baseline;
- `L`: the current game-owned Draft content; and
- `N`: the exact newly selected resolved profile composite.

The automatic plan is deliberately conservative:

- When a materialized object is canonically unchanged from `O`, the plan may replace it with its `N` representation or delete it when `N` removed it.
- A new `N` object may be added only after identity, collision, schema, and reference validation succeeds.
- A locally changed or locally deleted object requires explicit creator resolution.
- A broken reference, unsafe deletion, ambiguous pack override, or semantic replacement, split, merge, rename, or re-scope requires explicit typed resolution and, where identity changes, an explicit durable mapping.
- The upgrader does not promise a generic JSON merge or automatic field-level merge. Typed asset-specific assistance may be added later without weakening the conservative fallback.

The creator previews and resolves the complete plan before mutation. Game Design then applies it as one normal Draft commit guarded by the complete affected aggregate and scope epoch set. That commit follows [ADR 0129](adr-0129-durable-fenced-multi-owner-draft-commits.md)'s local consequences: it binds the target `tenantId`, `versionId`, canonical revision order, exact base commit, canonical digest of the complete input, and complete affected owner/aggregate/scope epoch set, and records durable per-owner outcomes plus a synchronized read fence; partial owner application cannot appear as a healthy Draft. Only an edit that advances an aggregate or scope in that affected set invalidates the preview; an edit confined to a disjoint scope does not. Normal cross-service Draft reconciliation, validation, digest gating, and publication apply. After publication, moving gameplay to the new game version uses the ordinary version migration, launch, playtest, and cutover contracts rather than any profile-specific runtime path.

After a successful upgrade, the exact source baseline advances to `N`. Retained local differences, deletions, detachments, identity mappings, and explicit resolutions remain recorded so a later `N -> N+1` upgrade can distinguish creator intent from unchanged imported content.

Profile setting values are suggestions outside the content upgrade transaction. An accepted suggestion is written through the ordinary typed settings authority and precedence contract. Applying or upgrading a profile never silently overwrites a tenant or game setting.

Profiles may materialize ordinary scripts governed by the normal Game Design and Automation contracts. A linked plugin remains a separate immutable release-tuple and dependency concern. A profile upgrade neither rewrites an attested plugin bundle nor implies that a plugin version compatible with the old game version is compatible with the new one.

## Consequences

- Games own one explicit Draft and published graph; routine authoring and gameplay do not evaluate profile layers.
- Profile publication cannot silently change, resurrect, or restore content in an existing game.
- Preserving exact baseline, source identity, mapping, composition, and resolution lineage adds storage and authoring-control-plane work from the first implementation.
- A future upgrader can safely automate unchanged objects and new validated objects without committing to universal semantic merge behavior.
- Creator modifications, deletions, and complex graph changes may require substantial manual resolution.
- Repeated upgrades remain explainable because each successful migration advances an exact baseline while retaining local intent.
- Settings and linked plugins cannot be smuggled into a content migration as hidden side effects.

## Alternatives Considered

### Live Runtime Inheritance

Keep a profile active below game overrides and resolve both during gameplay. This would make profile changes easy to propagate, but deletion could reveal inherited behavior, a moving profile could change a running game, and runtime services would acquire another resolution and availability dependency.

### Permanent Layered Authoring

Represent every Draft as an immutable profile base plus a game overlay and flatten the result at publication. This supports base swapping, but every editor, reference validator, delete operation, pack override, and provenance surface would permanently need overlay and tombstone semantics.

### One-Time Copy Without Upgrade Lineage

Copy profile content into the game and retain only package-level provenance. This is simplest initially, but it discards the evidence needed to distinguish unchanged imports from creator intent and can make later safe upgrades impossible or heuristic.

### General Three-Way JSON or Field Merge

Merge all differences automatically. This is rejected as the baseline because lists, references, optional values, typed declarations, and semantic identity changes require domain-specific rules. A syntactically successful merge is not proof of a valid gameplay graph.

## Implementation and Proof Obligations

The profile catalog, composition engine, materialization path, exact baseline retention, per-object source mapping, deleted/detached lineage, upgrade planner, conflict-resolution UI, and upgrade-commit workflow are not implemented.

Implementation must prove deterministic base-and-pack composition; immutable or permanently reconstructible baselines; stable source identity and collision-safe target mapping; repeated upgrades; stale-preview rejection through Draft epochs; unchanged-object replacement and deletion; validated additions; local edit and deletion preservation; reference-safe removals; explicit semantic replacement, split, and merge mappings; composition-order and override changes; schema-version migration or fail-closed unsupported-schema behavior; retry-safe single-commit application; and normal publish gating after the upgrade.

Separate proof must show that profile application and upgrade do not create runtime inheritance, do not silently mutate settings, do not rewrite attested plugin bundles, and do not treat an old plugin version as compatible with a newly published base version without satisfying the plugin compatibility contract.

## Reversibility and Revisit Triggers

Baseline storage format, planner presentation, and asset-specific merge assistance may evolve while retaining creator initiation, exact lineage, conservative conflict behavior, and ordinary Draft commits. Revisit whether richer typed merges justify their burden after real profile revisions and creator conflict patterns exist. Live runtime inheritance or automatic profile propagation would change the ownership model and requires a new decision.

## Required Documentation Alignment

- [design/architecture/microservices/game-design-service/game-templates.md](../microservices/game-design-service/game-templates.md)
- [design/architecture/microservices/game-design-service/version-control.md](../microservices/game-design-service/version-control.md)
- [design/architecture/microservices/game-design-service/world-editing-tools.md](../microservices/game-design-service/world-editing-tools.md)
- [design/architecture/system-architecture-versioning-runtime.md](../system-architecture-versioning-runtime.md)
- [design/architecture/decisions/adr-0020-scoped-domain-and-operational-identifiers.md](adr-0020-scoped-domain-and-operational-identifiers.md)
- [design/architecture/decisions/adr-0082-semantic-boundary-for-cross-service-identifier-migration.md](adr-0082-semantic-boundary-for-cross-service-identifier-migration.md)
