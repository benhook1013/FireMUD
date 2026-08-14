# ADR 0093: Game Design-Coordinated Digest-Attested Content Publication

## Status

Accepted

## Implementation Status

The coordinated digest-attested publication boundary is target state. Current documentation and implementation still include a tenant-wide mutable export path, incomplete cryptographic byte-digest coverage for ordinary assets, and no complete proof that clients swap cohesive manifests without mixed-version presentation. See the [Game Design version control contract](../microservices/game-design-service/version-control.md#design-time-synchronization), [asset storage contract](../microservices/game-design-service/asset-storage.md#asset-lifecycle-and-publish-workflow), and [versioning and runtime tracker](../../project-management/implementation-tracking/game-authoring-publishing-and-activation.md).

## Canonical Design

- [Game Design version control and publication](../microservices/game-design-service/version-control.md#design-time-synchronization)
- [Versioned publishing and runtime configuration](../system-architecture-versioning-runtime.md#game-version-publishing)
- [Theme and branding asset resolution](../system-architecture-game-customization.md#theme-and-branding)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `CONTENT-01`
- Primary capability: `AR-1.5` Game Design authoring and version authority
- Affected capabilities: `AR-1.4`, `AR-3.2`, `PO-3.3`, `GR-2.1`, `AA-3.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of publication coordination, domain-owned templates, digest-attested release bundles, runtime content dependencies, and runtime content-service alternatives
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `CONTENT-01`

## Context

FireMUD content publication spans Game Design version metadata, versioned templates owned by domain services, binary assets, and derived runtime artifacts. A published game version must name one cohesive set of these inputs without turning Game Design into a gameplay availability dependency or allowing runtime to observe mutable authoring state.

Describing Game Design as the authoring and version authority can incorrectly imply that it owns every underlying template or serves all content to runtime. Domain services need to retain authority for the versioned templates in their bounded contexts. Game Design instead needs to coordinate publication and attest exactly which immutable participant versions and artifacts form the release.

An attestation is useful only when it identifies actual content. Byte counts, mutable URLs, or a hash over URLs alone cannot prove that the referenced bytes are unchanged. The publication boundary also needs to distinguish optional presentation customization, where a stable platform fallback is acceptable, from required runtime content whose absence would make the launched world incomplete or non-reproducible.

## Decision

Game Design owns game-version metadata, publish coordination, and the final release attestation. It determines when a candidate version has collected every required participant and artifact, records the resulting immutable release bundle, and makes only a successfully published bundle eligible for launch.

Domain services retain authority for the versioned templates in their bounded contexts. Publication obtains immutable participant exports or version identities from those owners; it does not move their authoring or validation authority into Game Design. The release attestation binds each required participant to its actual content digest and relevant schema or contract version.

Binary assets and derived artifacts are published through object storage and delivered through the CDN or equivalent immutable artifact path. Every exported artifact is identified by a digest computed from its actual bytes. Manifests bind logical asset roles to those digests and immutable locations. A digest over URLs, object names, byte lengths, or other metadata alone is not content attestation.

Runtime services resolve content from the instance's pinned published release. They may read immutable domain-owned published data and object-storage or CDN artifacts according to that release, but they do not query mutable Game Design drafts, ask Game Design to construct content dynamically, or depend on Game Design being available during gameplay. World generation and other derivation use the pinned published inputs rather than mutable authoring defaults.

The publication contract classifies every release participant as required or optional. A missing, mismatched, unattested, or unreadable required runtime artifact fails publication or launch closed. Optional branding or presentation customization may use an explicitly versioned platform default when absent or unavailable, provided the fallback is declared by the release contract and does not change gameplay semantics. Runtime does not silently substitute a fallback for required content.

The release attestation records enough provenance to verify the complete published bundle: game version, participant identities and content digests, artifact manifest and object digests, applicable contract versions, and publication outcome. Runtime consumes that immutable attestation rather than independently selecting participant versions.

## Consequences

- Gameplay remains available when Game Design is offline because runtime consumes already published immutable content.
- Domain services retain their template invariants and do not surrender bounded-context authority to a central content database.
- Actual content digests make release verification, cache behavior, rollback, repair, and incident diagnosis meaningful.
- Required-content failure is visible before or at launch rather than appearing later as a partially rendered or semantically incomplete world.
- Optional branding can degrade to a declared platform presentation without making cosmetic asset availability a gameplay outage.
- Publication must coordinate multiple owners, collect immutable exports, classify required participants, compute digests, and retain provenance. This adds release-pipeline and storage work.
- A domain template change is not available to runtime until it participates in a new successfully attested publication.
- Runtime integrations must support immutable artifact retrieval and pinned domain-version reads instead of using Game Design as a convenient aggregate content API.

## Alternatives Considered

### Runtime Content Service Backed by Game Design

The strongest alternative is a dedicated runtime content service, potentially operated by Game Design, that dynamically assembles or serves the current content bundle. This could centralize content lookup, make hotfixes and mutable aliases easier, reduce publication coordination visible to callers, and give runtime one API instead of several immutable artifact paths.

It is rejected as the canonical model because it adds an availability and latency dependency to gameplay, permits content to change without a new release identity unless heavily constrained, and makes mixed participant versions easier to serve during partial updates or repair. Making that service safe would still require immutable snapshots, digest attestation, pinning, caching, and offline availability, largely recreating the chosen publication model behind another runtime hop.

### Copy Every Domain Template into a Game Design-Owned Store

Rejected because a central copy would either become a second authority for domain invariants or require continuous reconciliation with the true owners. Immutable publish exports may be assembled for a release, but their provenance and validation remain attributable to the owning domain service.

### Permit Runtime Fallback for Any Missing Artifact

Rejected because a fallback for required gameplay content could launch a different world from the one that was reviewed and published. Fallback remains limited to explicitly optional, presentation-only customization with a declared platform default.

## Implementation and Proof Obligations

Publication proof must show that every required domain participant and exported object is captured under an immutable identity and an actual content digest; the final release attestation binds the complete participant and artifact set; and only a complete published bundle becomes launchable. Mutation of a draft, source template, mutable URL target, or authoring default after publication must not change the published result.

Runtime proof must cover Game Design unavailability, pinned domain-version reads, immutable object/CDN reads, digest mismatch, missing required artifacts, optional-branding fallback, derived-world reconstruction from published inputs, rollback to an earlier bundle, and refusal to substitute mutable or latest content. Client proof must demonstrate an atomic or otherwise safe swap to the selected cohesive manifest rather than observing mixed old and new assets.

Current implementation and documentation do not yet prove this boundary. Identified gaps include a tenant-wide mutable export path, a digest represented by byte length rather than a cryptographic digest of content, a manifest hash that covers URL mappings without proving every referenced object's bytes, and missing proof that clients swap cohesive manifests without mixed-version presentation. These are implementation and proof gaps against the target decision; this record does not claim they are complete.

## Reversibility and Revisit Triggers

Manifest encoding, digest indexes, storage layout, optional-branding categories, and publication orchestration may evolve without changing the authority boundary. Revisit the decision if measured publication latency or retained-artifact cost materially prevents normal creator workflows, or if a concrete runtime content-service design can provide equal immutable pinning, digest proof, offline availability, rollback, and domain-owner provenance with materially less operational complexity.
