# ADR 0096: Attested Publication Gate and Quarantined Failed Assets

## Status

Accepted

## Implementation Status

Attested launch gating and private quarantine are target state. Current launch admission checks release-bundle and artifact state, supported `published_release_bundle.attestationSchemaVersion`, `manifestHash`, and required keys, but it does not yet fetch and validate the manifest's own `schemaVersion`. Ordinary asset bytes are not completely attested; export can leave a partial final prefix; and the publish failure path does not consistently retain the designed terminal state. See [asset lifecycle and publish workflow](../microservices/game-design-service/asset-storage.md#asset-lifecycle-and-publish-workflow), [failed publish handling](../system-architecture-asset-store-runbook.md#handling-failed-publish-versions), and [launch preflight](../system-architecture-versioning-runtime.md#launch-descriptor-version-resolution-rules).

## Canonical Design

- [Asset lifecycle and publish workflow](../microservices/game-design-service/asset-storage.md#asset-lifecycle-and-publish-workflow)
- [Failed publish handling](../system-architecture-asset-store-runbook.md#handling-failed-publish-versions)
- [Launch descriptor version-resolution rules](../system-architecture-versioning-runtime.md#launch-descriptor-version-resolution-rules)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `ASSET-02`
- Primary capability: `AR-3.2` release readiness, compatibility, and propagation
- Affected capabilities: `AR-1.5`, `AR-1.4`, `PO-3.3`, `SF-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of partial publish visibility, launch admission, quarantine, retry, diagnostic retention, and availability tradeoffs
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `ASSET-02`

## Context

Asset export spans database state and object storage and can fail after writing only some candidate objects. The mere existence of a prefix or manifest-shaped object cannot prove that every participant, required artifact, lifecycle transition, and release attestation completed. Allowing runtime admission while operators repair an incomplete release would let different players or services observe different content and would make rollback evidence unreliable.

The previous fail-closed choice is correct, but it needs a publication topology and retention rule that prevent failed candidates from becoming an accidental runtime surface. Unbounded retention of every failed prefix is also unnecessary operational cost.

## Decision

Only an artifact in authoritative `PUBLISHED` state with a supported immutable release attestation is launchable. Launch and cutover require matching release identity and version-state epoch, a supported `published_release_bundle.attestationSchemaVersion`, a fetched manifest whose own `schemaVersion` is supported, matching `manifestHash`, mandatory per-object digests, and every declared required artifact key. `STAGED`, `EXPORTED_UNATTESTED`, `FAILED`, `TOMBSTONED`, `PURGE_IN_PROGRESS`, `PURGE_FAILED`, missing, unsupported, stale, or mismatched evidence fails before gameplay admission or admission-pointer change.

Candidate export occurs in a private staging or quarantine namespace under ADR 0095. Runtime realm admission returns only the content-addressed manifest belonging to the completed attested release. A failed or unattested candidate is never used as fallback, never becomes the manifest for a running realm, and is not repaired in place while runtime admission continues.

Failed candidates retain terminal diagnostic metadata, including workflow identity, state epoch, known object or manifest digests, and structured failure details. Candidate bytes remain privately quarantined for a configurable operator retention period sufficient for incident diagnosis and supported retry. After that period, an explicitly abandoned candidate may enter the CAS-guarded reachability and purge workflow. Physical bytes may be removed while terminal lifecycle metadata remains available for audit.

Retry or repair uses a new approved workflow attempt with stable idempotency identity and the immutable sources and digest rules in ADR 0095. It either produces the complete attested Published release or remains non-launchable; operators do not make a prefix launchable by manually editing bucket contents or database flags.

Availability is deliberately subordinate to release consistency. If the required attestation, manifest, object, schema support, or fresh control-plane evidence is unavailable, new launch or cutover waits or fails with a deterministic operator-visible result rather than admitting a partial release. Existing instances may continue only when they already hold a valid pinned immutable release and their required bytes remain available.

## Consequences

- Players cannot enter a version whose content is partial, mixed, unattested, or unsupported by the consumer.
- Failed publication remains diagnosable without exposing candidate manifests to runtime clients.
- Configurable quarantine prevents diagnostic retention from becoming unbounded object-storage growth.
- Publish or storage faults can delay launches and cutovers even when some candidate bytes are usable.
- Operators need explicit retry, abandonment, retention, and purge surfaces instead of manual bucket repair.

## Alternatives Considered

### Launch the Complete-Looking Portion and Repair in Place

Rejected because object presence cannot prove cross-service release cohesion, and clients can cache a partial manifest or changed bytes before repair finishes.

### Publish Directly to a Public Version Prefix but Gate Only Database Admission

Rejected because guessed or leaked paths may expose incomplete or unpublished content, and an exporter can leave untracked public objects after failing before its database transition.

### Retain Every Failed Candidate Indefinitely

Rejected because terminal metadata and a bounded diagnostic window preserve useful evidence without making abandoned bytes an unlimited storage obligation.

## Implementation and Proof Obligations

Proof must cover failure before any object, after one object, before and after manifest creation, before attestation, and between attestation and lifecycle finalization; private candidate access; missing and mismatched object digests; unsupported manifest or attestation schema; fresh launch reads; retry identity; configurable retention; abandonment; purge races; and an existing pinned instance during later storage degradation.

Current launch admission substantively checks release-bundle and artifact state, supported `published_release_bundle.attestationSchemaVersion`, `manifestHash`, and required keys, but it does not yet fetch and validate the manifest's own `schemaVersion`, and ordinary asset bytes are not completely attested. Export can leave a partial final prefix when it throws before returning a manifest, the current manifest omits the documented schema shape, and the publish failure path does not consistently retain the designed terminal state. These are implementation and proof gaps; this decision does not claim they are resolved.

## Reversibility and Revisit Triggers

Quarantine duration and diagnostic payloads are operational policy and may be tuned. Revisit the availability boundary only if FireMUD introduces a formally defined degraded release mode whose independently complete optional components can be admitted without weakening the cohesive required bundle.
