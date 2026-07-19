# ADR 0098: Request-Bounded Generation Replay and Explicit Regeneration

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `PROC-02`
- Primary capability: `AR-1.5` generation provenance and reconciliation
- Affected capabilities: `AR-3.2`, `GR-2.1`, `SF-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of generator compatibility lifetime, retry identity, rolling deployment behavior, topology authority, recovery, and intentional regeneration

## Context

Procedural generation must remain safe across retries, service deployments, and rolling nodes. Allowing one logical request to switch generator implementation, model, or configuration after an interruption could produce a different graph, duplicate effects, or content different from an accepted preview.

That retry requirement does not justify keeping every historical generator binary executable forever. Once generated topology has been committed, FireMUD can preserve and recover the accepted result as stored data. New generation can then benefit from a newer implementation without silently changing content that was already accepted.

## Decision

Generator replay compatibility is bounded to one admitted generation request, not to the lifetime of the platform.

At request admission, FireMUD assigns a stable business `generationRequestId` and records the exact generator or model version, immutable semantic configuration snapshot and schema version, seed, target scope, and applicable game or operator generation policy. The resolved implementation and inputs are immutable for that request. All nodes participating in a rolling deployment must honor the same admitted selection; routing or retry on another node must not select a different implementation.

A retry of the same in-flight request reuses its recorded or staged output. It must not silently rerun with a newer generator, model, mutable default, or changed configuration. If the recorded output cannot be reused and the admitted implementation is unavailable, the request fails closed until it can resume with that selection or is explicitly abandoned. Continuing with a newer implementation requires a new request identity and, where authored content is affected, a new revision.

After finalize, the committed template or instance topology is authoritative. FireMUD retains generator version, model identity where applicable, configuration snapshot, schema version, seed, and output identity as provenance for audit and diagnosis. That provenance is not a promise that the historical generator binary will remain stored, executable, or capable of reconstructing the output indefinitely.

An intentional regeneration or generation of a new chunk is a new revision or request. It may select the newest generator or model permitted by an explicit game or operator policy. Regeneration that replaces existing authored or generated content must use the declared scope and replacement semantics of the authoring contract; a deployment alone never regenerates accepted content.

Recovery of persistent content uses committed topology, immutable releases or retained finalized artifacts, and backups. It does not depend on re-executing an old generator from seed and configuration. Ephemeral content may be discarded and generated again as a new request when its lifecycle permits that loss.

## Consequences

- Retries converge on the output admitted for that request even while deployments and model versions change.
- New chunks and explicit regeneration can use improved generators without preserving obsolete implementations forever.
- Stored topology and retained outputs require durable lifecycle, backup, and integrity controls because provenance alone is not a reconstruction guarantee.
- Operators can identify how content was generated, but cannot assume a seed and provenance record are sufficient to recreate it after authoritative data and backups are lost.
- An in-flight request can remain unavailable or fail closed if neither its recorded output nor its admitted implementation is usable; silently changing the result is not an availability fallback.
- Published or persistent worlds are not implicitly rewritten when generator policy or deployed code changes.

## Alternatives Considered

### Retain and Re-execute Every Historical Generator Indefinitely

Rejected because permanent binary, dependency, model, and configuration compatibility creates unbounded retention and maintenance obligations. It also makes recovery depend on old execution environments when the accepted topology can instead be retained directly. Historical executability may be added for a narrower regulated or archival need without making it the default platform contract.

### Retry with Whatever Generator Is Current

Rejected because a deployment or node change could alter one logical request's output, break preview and idempotency expectations, or mix incompatible partial results under one request identity.

### Retain Only Seed and Configuration and Regenerate During Recovery

Rejected because those inputs do not guarantee equivalent output across implementation, model, dependency, or runtime changes. Persistent recovery must restore authoritative data rather than infer it through historical computation.

## Implementation and Proof Obligations

Admission must persist a stable request identity and immutable resolved generator or model version, semantic configuration snapshot, schema version, seed, target scope, and policy decision before generation can finalize. Staged and finalized outputs must be durably associated with that identity, and duplicate delivery must converge on the same run and output.

Proof must cover interruption before and after output staging, retry after a newer deployment, retry on a differently versioned rolling node, duplicate delivery, mutable default or policy changes after admission, missing staged output and unavailable admitted implementation, and atomic visibility of committed topology. It must show that none of those paths substitutes a newer implementation under the original request identity.

Proof must also show that a new chunk or explicit regeneration receives a new request identity, can select the newest policy-permitted implementation, and cannot overwrite existing content without the required revision, scope, and replacement authorization. Recovery proof for persistent content must restore committed topology or a retained finalized artifact without invoking the historical generator. Provenance reads must remain available even when the historical binary is absent.

Current implementation and runtime proof are not claimed by this ADR. It records the target contract; implementation trackers must separately identify which admission, staging, rolling-deployment, regeneration, and recovery obligations are implemented and proven.

## Reversibility and Revisit Triggers

Provenance schemas, retention periods, generator selection policies, and staged-output storage may evolve while preserving request-bounded identity and committed-topology authority. Revisit historical executable retention if regulation, forensic requirements, external content exchange, or a contractual seed-only reconstruction guarantee requires it. Revisit active-request artifact retention if generation requests routinely outlive the deployable lifetime of their selected implementation, but do not allow an existing request to change implementation silently.
