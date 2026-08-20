# ADR 0128: Game Design Plugin Trust Provenance

## Status

Accepted

## Implementation Status

This is a service-local provenance record under [ADR 0111](./adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md). The current Game Design path accepts allowlisted Ed25519-signed bundles and persists immutable plugin metadata, but the target unsigned package path and complete provenance proof remain unimplemented. This record does not create a competing plugin trust or runtime lifecycle contract.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Accepted
- Review source: `MS-AS-PLUGIN-TRUST`
- Decision date: 2026-07-20
- Decision key: `MS-AS-PLUGIN-TRUST`
- Primary capability: `AR-1.3` plugin and extension authoring
- Affected capabilities: `AS-1.2`, `AS-1.6`, `SF-1.3`, `AR-3.3`
- Decision owner: FireMUD human product and architecture owner

## Service-Local Provenance

Game Design records the provenance evidence needed for its immutable plugin publication boundary under the technical authority of [ADR 0111](./adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md):

- the exact immutable package and `bundleDigest`;
- complete validation and compatibility evidence for the published package;
- signed intake evidence as applicable; operator-permitted unsigned intake requires both explicit scoped approval and platform acceptance attestation under ADR 0111;
- declared and granted exact-version capabilities; and
- signer, publication, and revocation evidence needed for later eligibility checks.

These records support indexed authoring reads, publication decisions, audit, and runtime handoff. They do not grant capabilities, make package origin an automatic trust tier, define a second DSL, or own instance activation. The complete package, signing, capability, revocation, sandbox, and runtime-fence contract remains canonical in ADR 0111 and its linked scripting contracts. Current hosted policy remains signed-only; operator-permitted unsigned intake, when implemented, must use ADR 0111's exact-digest, validation, scoped-approval, and platform-attestation path.

## Consequences

Game Design can explain why a plugin version was accepted or made ineligible without duplicating runtime authority. Plugin metadata and provenance remain immutable and queryable, while Automation & Scripting owns readiness and activation and Game Session owns its independent gameplay fences.

## Reversibility and Revisit Triggers

Evidence storage, approval presentation, signer policy distribution, and publication workflow mechanics may evolve while retaining ADR 0111's authority boundary. A change to plugin trust, capability admission, package semantics, or runtime lifecycle requires an update to ADR 0111 or a new superseding decision rather than expanding this service-local record.

## Required Documentation Alignment

- [In-Game Modding and Plugin Framework](../microservices/game-design-service/modding-framework.md)
- [ADR 0111](./adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md)
