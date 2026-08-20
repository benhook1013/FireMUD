# ADR 0129: Durable Fenced Multi-Owner Draft Commits

## Status

Accepted

## Implementation Status

The current implementation does not satisfy this decision. `SaveRevision` can call World Management before Game Design persists its local revision. World aggregate and scope epochs are read and then saved without the expected epoch in the database update predicate, and some mutation shapes can omit scope fencing. There is no Game Design-owned durable commit/proposal coordinator with exact base and digest binding, complete affected-scope capture, per-owner apply status, or a creator-visible synchronized read fence. Existing revision ledgers and publish digests are useful seams but do not prove this contract.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `MS-AR-DRAFT-CONCURRENCY`
- Decision date: 2026-07-20
- Decision key: `MS-AR-DRAFT-CONCURRENCY`
- Primary capability: `AR-1.1` world, entity, rule, and content authoring
- Affected capabilities: `AR-1.5`, `AR-1.2`, `AR-3.4`, `SF-2.1`, `EA-3.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of concurrent Draft editing, aggregate and scope epochs, multi-owner commits, partial application, proposal review, idempotency, conflict handling, and publication safety

## Context

One authored change may affect templates owned by Game Design, World Management, Entity Management, Game Logic, and Automation & Scripting. FireMUD cannot make those independent databases one ACID transaction, but it also cannot present a partially applied change as healthy shared Draft state.

A single Draft-wide epoch and serialized edit stream would be easy to reason about, but every small edit would conflict with unrelated work. Aggregate and scope epochs allow unrelated creator edits to proceed independently. Those epochs are insufficient by themselves, however, if an owner checks an epoch and later performs an unconditional write, if the complete affected scope can be omitted, or if no durable record explains which owners applied an exact proposal.

AI-assisted and external proposals make the same problem more visible but do not create a separate concurrency model. A reviewed diff must not be silently changed or merged when the Draft advances.

## Decision

### Game Design-Owned Commit and Proposal Records

Game Design owns the durable, creator-visible coordination record for every shared-Draft commit and isolated proposal. For every mutation, the record binds at least the target `tenantId` and `versionId`, plus:

- the exact `baseCommitId` from which the complete diff was produced;
- a stable request or proposal identity and canonical digest of the complete proposed input;
- the complete affected `(tenantId, versionId, owner, aggregateId, scopeId, epoch)` set;
- canonical commit and revision order; and
- each required owner's durable application status and exact applied commit/digest identity.

Reusing one request or proposal identity returns the existing result only when the complete binding matches: target `tenantId`/`versionId`, canonical mutation/input, exact `baseCommitId`, canonical revision order, complete affected set, and canonical digest. Reusing it with any changed or omitted binding field is rejected as changed-request reuse, even when the digest happens to match. The base commit remains immutable provenance for the reviewed diff. Freshness is evaluated over the complete affected aggregate and scope epoch set, so a newer synchronized commit that changed only disjoint scopes does not by itself create a conflict.

An isolated proposal is not shared Draft state. Accepting it creates or selects one exact commit application bound to the reviewed base, diff, affected epochs, and digest. External AI/tool clients use ordinary scoped public creator APIs; a first-party agent uses the trusted scoped tool broker and isolated proposal flow. Both use this same commit contract and receive no alternate merge or write authority.

The fencing unit is the owner-local tuple `(owner, aggregateId, scopeId, epoch)`. A scope identifier names the narrowest independently editable unit; an aggregate-wide invariant is represented by the canonical aggregate scope and therefore expands the affected set when required. Owner CAS checks every expected tuple in the complete affected set, and a mutation touching multiple scopes advances them atomically in that owner's transaction. Disjoint scope tuples may advance independently, while an operation that declares an incomplete or under-scoped set is rejected.

### Owner-Local Atomic Compare-and-Swap

Each authoritative owner validates the complete affected aggregate and scope set for its typed mutation. In one owner-local storage transaction it must:

1. compare every expected epoch required by the typed mutation, for every fencing tuple in the complete affected set, as a storage-level write predicate or equivalent lock-protected condition;
2. apply all of that owner's local mutations for the commit;
3. advance the affected aggregate and scope epochs; and
4. record the exact commit, canonical digest, and idempotent owner result.

A read followed by an unconditional epoch or content update is not sufficient compare-and-swap proof. A client also cannot avoid a conflict by omitting a containing affected scope: the owner derives or validates the required scope set from the typed mutation and rejects an incomplete declaration.

Exact replay is a no-op returning the recorded result. The same commit or request identity with a different or omitted tenant/version, canonical revision order, digest, base, affected set, or mutation is rejected. A stale affected epoch rejects that owner's application without silently overwriting or merging newer state.

### Durable Cross-Owner Coordination and Visibility

Cross-owner application is a durable coordinated workflow, not a distributed database transaction. Retries and recovery reuse the same commit and digest identities, and Game Design persists enough per-owner state to distinguish not yet attempted, in progress, applied, and rejected or failed work without relying on process memory.

An owner-local apply does not by itself make the commit accepted shared Draft truth. Normal authoring reads and subsequent edits bind to the Game Design-owned synchronized visibility fence, which records the exact commit, digest, and complete `(owner, aggregateId, scopeId, epoch)` set visible to ordinary Draft readers. Game Design advances that fence only after every required owner reports durable application of the exact commit and digest at those expected fencing units. Owner storage must preserve the ability to serve the synchronized fence while later partial work exists; the physical staging, visibility, or cleanup representation may vary by owner.

Partial application is creator-visible diagnostic workflow state. It cannot advance the normal Draft read fence, satisfy `IN_SYNC`, or become a publish target. Retry, repair, conflict, and abort handling may inspect the partial owner outcomes, but cannot relabel an incomplete commit as healthy Draft state. Publication additionally retains the existing participant-digest and release-attestation gates.

### Conflict Assistance

FireMUD may help a creator inspect conflicts and construct a rebased or otherwise revised diff. Assistance produces a new proposal with a new canonical digest, exact base commit, and affected epoch set. The creator reviews and explicitly accepts that new proposal. Neither Game Design, an owner service, an external client, nor an AI tool silently merges a stale proposal.

## Consequences

- Unrelated edits can proceed using narrow aggregate and scope epochs instead of one version-wide conflict boundary.
- Shared Draft truth, normal reads, later edits, and publication all use one explicit synchronized commit fence.
- Per-owner storage transactions provide real compare-and-swap enforcement rather than advisory prechecks.
- Durable per-owner status makes partial failure, retry, and repair observable without describing partial content as accepted.
- Multi-owner authoring requires coordination records, owner-local idempotency, fenced reads or staging, cleanup, and creator-facing status UX.
- AI-assisted and external authoring share the ordinary Draft authority and review model.

## Alternatives Considered

### One Version-Wide Epoch and Serialized Commit Stream

This is the strongest simpler alternative because one global compare-and-swap and one ordered stream are straightforward to prove. It is rejected as the canonical model because an unrelated room, item, script, or large generated proposal would conflict with every other edit. A deployment may serialize internal work for operational reasons, but it must preserve the scoped public concurrency contract.

### Best-Effort Eventual Application as Healthy Draft State

Record revisions and let owners converge independently while ordinary readers observe whichever changes have arrived. Rejected because creators, validators, and publishers could consume a combination that never represented one accepted commit.

### Distributed Transaction Across Owner Databases

Use one atomic transaction spanning Game Design and every domain owner. Rejected because it couples independent services and databases to a fragile coordination mechanism. Durable workflow coordination plus owner-local atomicity provides an explicit failure and recovery model.

### Silent Automatic Merge

Apply a stale proposal when a field-level or generated merge appears safe. Rejected because generic merging cannot prove domain semantics, cross-service references, or creator intent. Conflict assistance may generate a new reviewable proposal but cannot substitute for approval.

## Implementation and Proof Obligations

Implementation proof must cover two edits to the same aggregate; overlapping and disjoint scopes; omission of an owner-derived containing scope; concurrent first writes at epoch zero; exact replay; changed-digest identity reuse; process loss before and after each owner-local commit; owner timeout and unavailable/rejected outcomes; recovery from partial application; normal reads while partial work exists; later edits bound to the synchronized fence; unresolved cross-service references; publish attempted against partial, stale, or mismatched commits; AI/external proposal acceptance after unrelated and conflicting edits; and assisted conflict resolution that always creates a newly reviewed proposal.

Storage proof must demonstrate compare-and-swap in the mutation statement or equivalent owner-local locking transaction for every complete affected `(owner, aggregateId, scopeId, epoch)` tuple, not only a service-layer pre-read. Cross-owner proof must demonstrate restart-safe coordination and exact commit/digest convergence without claiming distributed atomicity. Read-fence proof must show that ordinary Draft visibility advances only through the Game Design-owned synchronized fence carrying that same complete set.

## Reversibility and Revisit Triggers

The workflow engine, staging representation, creator status vocabulary, diff encoding, and internal scheduling may evolve while retaining exact-base and digest binding, complete affected-scope fencing, owner-local atomic compare-and-swap, synchronized read visibility, and explicit review of any conflict resolution. Revisit the decision if measured authoring patterns show that scoped concurrency costs more than it saves and a version-wide serialized stream would meet creator needs, or before introducing collaborative live editing that cannot bind operations to reviewed commit proposals.

## Required Documentation Alignment

- [Game Design Service API Contracts](../microservices/game-design-service/api-contracts.md)
- [Version Control for Design Assets](../microservices/game-design-service/version-control.md)
- [World Editing & Customization Tools](../microservices/game-design-service/world-editing-tools.md)
