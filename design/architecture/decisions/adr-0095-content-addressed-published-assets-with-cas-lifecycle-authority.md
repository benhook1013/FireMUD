# ADR 0095: Content-Addressed Published Assets With CAS Lifecycle Authority

## Status

Accepted

## Implementation Status

Content-addressed publication and storage-level CAS are target state. The current implementation still exports a mutable tenant-wide asset collection, lacks enforced version mappings, does not attest every ordinary asset’s bytes, and does not provide the complete atomic lifecycle predicate. See [asset lifecycle and publish workflow](../microservices/game-design-service/asset-storage.md#asset-lifecycle-and-publish-workflow) and the [asset-store runbook](../system-architecture-asset-store-runbook.md).

## Canonical Design

- [Asset lifecycle and publish workflow](../microservices/game-design-service/asset-storage.md#asset-lifecycle-and-publish-workflow)
- [Asset store operational contract](../system-architecture-asset-store-runbook.md)
- [Versioned publishing and runtime configuration](../system-architecture-versioning-runtime.md#game-version-publishing)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `ASSET-01`
- Primary capability: `AR-1.5` revisions, versions, publishing, validation, and attestation
- Affected capabilities: `AR-1.4`, `AR-3.2`, `AR-3.3`, `PO-3.3`, `SF-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of immutable asset identity, publish visibility, exact-byte repair, CDN behavior, lifecycle authority, and irreversible purge
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `ASSET-01`

## Context

The existing design correctly makes a persisted Game Design artifact row and its epoch authoritative for publish, repair, and purge instead of inferring lifecycle from object-store listings. It also requires Published and Active repair to reproduce attested bytes. The specified integrity evidence is not strong enough to prove that contract, however: a manifest hash can cover only a filename-to-URL document while the bytes served at those URLs change independently. An exporter or repair process that writes the final version prefix before verifying its candidate can corrupt a live release and discover the mismatch only afterward.

Overwritable version-scoped keys also make CDN freshness, partial multi-object publication, and exact repair depend on careful invalidation and deterministic regeneration. The storage layout should make immutable-byte identity natural while retaining the database lifecycle and reachability authority needed for launch and deletion safety.

## Decision

Every published binary or derived artifact has a mandatory SHA-256 content digest. The attested manifest identifies each artifact's stable usage key, content-addressed immutable object key, content digest, content type, schema information, and public delivery location. The release bundle attests the manifest digest and the complete required artifact-digest set; hashing only URLs, names, or byte lengths is insufficient.

Publication constructs candidates in a private staging or quarantine namespace. It obtains version-scoped ordinary assets and producer-owned derived artifacts from immutable repair sources, computes every object digest, creates the deterministic candidate manifest, and verifies the complete candidate before exposing it. Final public object and manifest keys are content-addressed and immutable. Uploading the same verified bytes again is idempotent; changed bytes require a different key and, for an attested release, a new version rather than mutation in place. Object-store versioning or object lock may add defense in depth but is not required for the canonical contract.

The persisted `version_asset_artifact` row and `stateEpoch` remain the lifecycle authority. State transitions that admit publication, repair, retirement, or purge use real compare-and-set persistence rather than an unlocked read followed by an unconditional update. Object-store presence, prefix listings, or CDN responses never establish launch or deletion eligibility.

Repair reads the immutable release attestation and artifact proof, materializes or retrieves candidate bytes away from the published location, and verifies every object and manifest digest before writing any published object key. It may restore only the exact attested content-addressed objects. If an immutable repair source is unavailable or any digest differs, repair fails closed and recovery requires a new version. Repair never overwrites live keys with unverified candidates.

Purge begins only after Game Design proves that the version is retired or otherwise purge-eligible and that no Published or Active release, launch descriptor, retained design-history reference, normalized asset mapping, template dependency, or approved remap still reaches the objects. Purge start atomically claims the artifact lifecycle state/epoch fence with that eligibility proof. Any concurrent commit that would acquire a new reachability reference must compare the same state/epoch fence and fail or retry if the artifact is tombstoned, purging, or epoch-changed; an acquisition that loses the CAS cannot commit, and a purge that loses it deletes nothing. Failure remains durably diagnosable, terminal metadata remains after physical deletion, and content-addressed objects shared by multiple releases are deleted only when globally unreachable within the supported storage authority.

## Consequences

- Manifest and release evidence prove the bytes actually consumed, not merely their URLs or sizes.
- Immutable keys make CDN caching safe and turn repeated publication or repair of identical bytes into harmless idempotent writes.
- Private candidate construction prevents an incomplete manifest from becoming the runtime discovery surface.
- Database reachability and CAS state retain safe launch, retirement, and purge coordination across services.
- Content digests, candidate storage, reference tracking, and garbage collection add metadata and operational work.
- Immutable bytes consume storage until retention and reachability rules permit collection, although content-addressing may deduplicate identical artifacts.

## Alternatives Considered

### Overwrite One Version Prefix and Verify After Regeneration

Rejected because multi-object writes are not atomically visible, CDN caches can retain a mixture, and a failed post-write comparison can discover corruption only after live bytes changed.

### Treat Object-Store Listings or Versioning as the Lifecycle Authority

Rejected because storage contents cannot prove launch descriptors, design-history reachability, remap dependencies, or concurrent control-plane transitions. Object-store versioning remains optional defense in depth, not the release-state database.

### Allow Manual Repair and Purge After Operator Inspection

Rejected as the normal path because a check-then-mutate sequence races activation, retirement, and other workflows. Last-resort recovery remains an explicitly audited incident action outside normal supported lifecycle behavior.

## Implementation and Proof Obligations

Proof must cover mandatory per-object hashing, deterministic manifest creation, version-scoped asset selection, private candidate isolation, content-addressed final keys, retry idempotency, no public write before verification, manifest and object corruption, unavailable repair sources, CAS races, shared-object reachability, retirement and history references, purge failure persistence, and CDN delivery of immutable keys.

The current implementation exports the mutable tenant-wide asset collection rather than enforced version mappings, incorporates byte length rather than asset content into one control-plane digest, and hashes a URL map without attesting ordinary asset bytes. Repair writes the live prefix before comparing its result. Artifact epoch checks are not yet implemented as an atomic storage predicate, normal Published-to-purge lifecycle coverage is incomplete, and failure writes need transaction-boundary proof. Existing unit seams therefore do not prove this decision.

## Reversibility and Revisit Triggers

Digest algorithms, manifest schema versions, retention periods, and garbage-collection batching may evolve while preserving immutable content identity and CAS lifecycle authority. Revisit if a future storage platform cannot support immutable content-addressed keys, if authenticated per-request transformation makes stored-byte hashes insufficient, or if measured scale requires a dedicated global artifact-reachability service.
