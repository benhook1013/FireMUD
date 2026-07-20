# World Management Service Procedural Generation Control

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

## Procedural Generation Control APIs

Procedural-generation control surfaces are split by ownership and persistence scope:

- World Management owns one pure generator engine and the validation and persistence of every generated topology graph. Generator implementations do not persist data or invoke other services.
- Design-time generation ingress is a typed Draft-target API callable only through authenticated Game Design workflows. Game Design is the sole authority for creator intent and Draft revision orchestration; World Management validates and persists the World-owned Draft rows.
- Runtime generation ingress is a separate typed instance-target API callable only through approved world-lifecycle or gameplay command paths and persists only instance rows.
- Ingress identity and the target union derive generation namespace and behavior. A caller-supplied free mode enum is not trusted as authority, and Published template rows are immutable.
- Automation & Scripting may populate topology after it has been persisted through canonical binding or runtime-command paths, but it does not generate or persist topology.
- Design-time generation-input APIs mutate version-scoped generation design rows in World Management only for Draft versions.
- Operational runtime-default APIs are owned by World Management and mutate only tenant-scoped `generation_runtime_default` rows that are explicitly excluded from publish inputs and draft digests.

Operational runtime-default API:

- `POST /generation/runtime-defaults` – create or update runtime-only defaults for a tenant.
- `GET /generation/runtime-defaults?tenantId=...` – list runtime-only defaults for a tenant.

These endpoints are limited to live operational tuning for future runtime-only generation runs. They must not mutate `generation_rule_template`, any other version-scoped design rows, or any input that contributes to `generationConfigRevision`.

Ownership note:

- Publish-affecting generation inputs are stored in World Management but authored only through Game Design-controlled design workflows.
- World Management remains the schema owner and runtime executor, not the independent authority for publishable generation history.

Current implementation note:

- The current generator implementations and registry are located in Automation & Scripting, which contradicts the target ownership above.
- World Management accepts typed generated Draft mutation payloads but does not yet expose either a design-generation or runtime-generation API that invokes a World-owned engine.

## Destructive Regeneration Plans

Reconciliation of a historical generation revision replays that revision and all later manual revisions in order. It does not rerun the generator over later edits or infer new destructive authority from the historical `REPLACE_SCOPE` value.

`SEED_APPEND_ONLY` is the safe default where generation can add without rewriting or deleting existing content. A new `REPLACE_SCOPE` revision is an intentionally destructive operation and requires an exact preview before mutation:

- the plan identifies creates, retained objects, replacements, deletions, affected inbound and outbound references, stable identities, explicit mappings, and blockers;
- its canonical digest is bound to the exact generation inputs, target scope, relevant reference facts, and current Draft scope epoch;
- any epoch, input, or relevant reference change makes the plan stale and requires replanning; and
- cross-boundary references must remain valid, map explicitly, or block the operation.

Stable identifiers may survive only when the plan establishes that the output is the same logical object. Semantic replacements, splits, merges, and re-scopes use explicit durable mappings. World Management does not perform a generic old/local/new merge.

Current implementation proves scoped Draft epochs and applies `REPLACE_SCOPE` and `SEED_APPEND_ONLY` mutations. It does not yet implement or prove the destructive preview, plan-digest binding, complete cross-boundary reference analysis, or identity-mapping contract.

## Audit and Publish-Gating Notes

- Every publish-affecting generation-input update must persist provenance fields such as `changedBy`, `changedAt`, `changeReason`, `changeDigest`, and source `commitId` / `revisionId`.
- When draft generation inputs change for a version, that version’s `designSyncStatus` must transition to `OUT_OF_SYNC` until publish-gate digests are recomputed and converged.
- Operational runtime-default changes must never mutate or reinterpret already-published generation inputs and must never be read by publish, activation, or draft-digest workflows.
