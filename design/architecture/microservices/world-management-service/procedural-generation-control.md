# World Management Service Procedural Generation Control

## Implementation Status

The target design places the generation engine and typed design-time/runtime ingress in World Management. Current `SimpleDungeonGenerator` and registry implementations remain in Automation & Scripting, and World Management lacks typed APIs that invoke a World-owned engine; its current typed Draft mutation path for generated subtrees is not such an invocation API.

## Derived World Artifact Publication

Derived world artifacts such as navmesh/path graph bundles follow one canonical publication path:

- World Management owns derivation, validation, and semantic versioning of these artifacts for `(tenantId, versionId)`.
- Game Design remains the sole writer to the shared object store. World Management must not publish derived world artifacts by writing directly to object storage.
- If derived artifacts are exported outside World Management storage, they must be handed to the Game Design publish workflow as explicit publish inputs so Game Design can write them, attest them in `published_release_bundle` through typed `artifactDigests[]`, declare launch-required manifest usage keys through `requiredManifestAssetKeys[]`, and expose them through the same release bundle used by activation and repair tooling.
- If a deployment keeps these artifacts only in World Management storage, the read path must remain World-owned via gRPC or database-backed APIs and must not rely on unpublished object-store conventions.
- Implementations must choose one of those paths per artifact family and document the consumer read path. Mixing direct World writes to object storage with Game Design-managed asset publication for the same artifact family is not allowed.

Initial-slice decision:

- Navmesh/path graph artifacts use the Game Design-managed object-store publication path.
- World Management derives and validates the artifact payload for `(tenantId, versionId)`, then hands it to the publish workflow as an explicit publish input.
- Game Design writes the artifact under the version-scoped published prefix, records it in the attested version `manifest.json`, and attests that manifest through the immutable `published_release_bundle`.
- Game Logic and any other runtime consumer must discover the artifact through the attested version manifest for that release rather than reconstructing object-store paths from digest records alone.

## Draft Digest Manifest Notes

World Management is a required full-publish digest participant, so `GetDraftDesignDigest` must attest one explicit and testable input set for `(tenantId, versionId)`.

Initial-slice `contentDigest` participation includes world-owned version-scoped semantic rows such as:

- `region_template`, `zone_template`, `room_template`, and normalized exit/topology relations;
- `terrain_template` and other version-scoped generated or authored topology rows;
- version-scoped declarative population/spawn bindings owned by World Management; and
- `generation_rule_template`, or equivalent version-scoped generation-input rows, that affect published topology or activation-time generated instance topology for that version.

Current implementation note:

- The live first-slice tables are named `region`, `zone`, `room`, `room_exit`, and `generation_rule` rather than the target-state `*_template` names. They are already version-scoped by `(tenantId, versionId)` and are the concrete rows hashed by `GetDraftDesignDigest(versionId)`.
- The current `region` digest includes generation-affecting fields `generationSeed`, `generatorType`, `generatorParams`, and `spacingMultiplier` in addition to identity and presentation fields.
- The current `generation_rule` digest includes `name`, `scopeType`, `scopeId`, and `value`. Scoped generation-rule writes must use the same `REGION_SUBTREE` / `ZONE_SUBTREE` request scope and Draft scope-epoch guard as topology and spawn-binding generation writes.
- The current `WORLD_GENERATION_SUBTREE` mutation applies generated rooms, room exits, generation rules, and spawn bindings as one scoped Draft write. `REPLACE_SCOPE` clears prior generated rows in that declared subtree before reapplying the payload so digest inputs converge from the same scoped mutation contract instead of from ad hoc table-specific editor paths.
- Later replacement with `*_template` table names must preserve the same semantic digest boundary or bump `digestSchemaVersion` through the documented migration path.

Excluded rows and fields include:

- all runtime/instance-scoped rows keyed by `gameInstanceId`;
- audit/provenance/history rows such as applied-revision ledgers and `generation_run` artifacts;
- non-semantic timestamps and write-time metadata; and
- derived world artifacts stored outside template tables, including navmesh/path graph payload bytes and object-store metadata.

Canonicalization rules:

- serialize included object families in fixed table/type order;
- within each family, order by full primary key; and
- serialize only documented semantic fields in deterministic encoding.

`digestSchemaVersion` must be bumped whenever the included row families, selected semantic fields, canonical ordering, or artifact-attestation rules change.

Attestation rule for derived world artifacts:

- in the initial slice, navmesh/path graph bundles are not folded into World Management’s `contentDigest`;
- the publish workflow instead persists explicit per-artifact digests in `artifactDigests[]` plus any required `requiredManifestAssetKeys[]`; and
- each artifact entry must be bound to the same `(tenantId, versionId, commitId, publishWorkflowId)` release identity as the World participant digest.

## Target Procedural Generation Control APIs

The cross-service generation ownership and ingress contract is canonical in [Procedural Generation](../../system-architecture-procedural-generation.md), including its service-responsibility and generation-pipeline sections. This document records World Management’s local endpoint and persistence consequences.

Procedural-generation control surfaces are split by authorization and persistence scope:

- Design-time generation requests are accepted only through the authenticated Game Design workflow’s typed Draft target. World Management validates the target, version, and scope and persists only World-owned Draft topology.
- Runtime generation requests are accepted only through approved world-lifecycle or gameplay command paths with a typed instance target. World Management validates instance lifecycle and identity and persists only instance topology; Published template rows remain immutable.
- The typed target and authorized endpoint determine namespace and persistence behavior. Automation & Scripting may populate runtime entities or bindings only after World Management has persisted topology, and only through canonical bindings or runtime commands. World Management remains the sole topology writer; Automation & Scripting cannot invoke World generation or persist or mutate topology.
- Design-time generation-input APIs mutate version-scoped generation design rows in World Management only for Draft versions.
- Operational runtime-default APIs are owned by World Management and mutate only tenant-scoped `generation_runtime_default` rows that are explicitly excluded from publish inputs and draft digests.

Operational runtime-default API:

- `POST /generation/runtime-defaults` – create or update runtime-only defaults for a tenant.
- `GET /generation/runtime-defaults?tenantId=...` – list runtime-only defaults for a tenant.

These endpoints are limited to live operational tuning for future runtime-only generation runs. They must not mutate `generation_rule_template`, any other version-scoped design rows, or any input that contributes to `generationConfigRevision`.

## Destructive Regeneration Plans

The canonical replay, replacement, preview, reference, identity, and no-merge contract is [Explicit Destructive Regeneration with Previewed Scope](../../decisions/adr-0101-explicit-destructive-regeneration-with-previewed-scope.md) and the [Procedural Generation](../../system-architecture-procedural-generation.md#request-bounded-replay-and-explicit-regeneration) system contract.

World Management local consequences:

- Apply accepts either a new approved `REPLACE_SCOPE` revision or a `SEED_APPEND_ONLY` revision. A `REPLACE_SCOPE` revision carries the exact target scope, expected Draft scope epoch, exact generation inputs, canonical plan digest, and required mappings and reference facts.
- For `REPLACE_SCOPE`, World Management checks the digest, epoch, and reference facts in the same storage-level CAS and persistence transaction as the scoped topology mutation. Any mismatch fails closed with `DRAFT_WRITE_CONFLICT` or a stale-plan error and leaves prior topology unchanged.
- `SEED_APPEND_ONLY` remains the local safe path when no rewrite or deletion is required. Apply validates its target scope, expected Draft scope epoch, exact generation inputs, and accepted idempotency identity; generated topology and its ledger must be idempotent, and it fails closed if deterministic replay would rewrite or delete existing authored rows.
- Multi-row generated topology continues through the typed `WORLD_GENERATION_SUBTREE` payload and World-owned mutation path, not opaque JSON. World Management persists the graph and applies target-specific replacement semantics.

Current implementation proves scoped Draft epochs and applies `REPLACE_SCOPE` and `SEED_APPEND_ONLY` mutations, but it does not yet implement or prove destructive preview, plan-digest binding, complete cross-boundary reference analysis, identity mappings, or owner-atomic CAS of all plan facts.

## Audit and Publish-Gating Notes

- Every publish-affecting generation-input update must persist provenance fields such as `changedBy`, `changedAt`, `changeReason`, `changeDigest`, and source `commitId` / `revisionId`.
- When draft generation inputs change for a version, that version’s `designSyncStatus` must transition to `OUT_OF_SYNC` until publish-gate digests are recomputed and converged.
- Operational runtime-default changes must never mutate or reinterpret already-published generation inputs and must never be read by publish, activation, or draft-digest workflows.
