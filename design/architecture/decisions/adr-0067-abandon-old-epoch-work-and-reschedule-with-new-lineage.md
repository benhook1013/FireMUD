# ADR 0067: Reconcile Old-Epoch Work and Reschedule with New Lineage

## Status

Accepted

## Implementation Status

Evidence-qualified old-epoch reconciliation and lineage-linked reconstruction are target-state reset rules; complete durable enumeration, authority-fenced attestation, authorization, revalidation, and focused recovery proof remain incomplete.

## Canonical Design

- [Tick Failure and Operations](../system-architecture-tick-failures-and-operations.md)
- [Redis Reset and Recovery](../system-architecture-redis-reset-and-recovery.md)

## Decision Record

- Decision date: 2026-07-19
- Decision key: `TICK-14`
- Primary capability: `GR-1.4` tick failure recovery and epoch reset
- Affected capabilities: `SF-2.3`, `GR-2.1`, `AS-1.4`, `PO-4.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of old-epoch terminalization, reconstruction lineage, and reset recovery alternatives
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `TICK-14`
- Post-review consolidation: the later accepted old-epoch evidence policy merged with ADRs 0051-0054 qualifies blanket abandonment; reset scope or epoch age alone never proves that an effect was unapplied.

## Context

A region epoch change invalidates the ownership, location, version, and topology assumptions under which earlier tick effects and cross-region follow-ups were admitted. Rewriting an old durable row onto the new epoch would preserve its identifier while changing its meaning and preconditions. Leaving old work pending indefinitely would avoid that false identity but prevent bounded convergence and make reopening a region depend on ambiguous backlog state.

Redis hints cannot enumerate the authoritative backlog because they are disposable coordination projections. Recovery must derive its scope from durable old-epoch work.

## Decision

The complete old-epoch nonterminal set is every durable effect or follow-up not already `APPLIED` or `ABANDONED`, including `SCHEDULED` and any durably claimed or staged substate. Every item in that set is completely enumerated and reconciled under an authority-fenced evidence policy. Durable evidence that the effect was applied produces `APPLIED`; durable evidence that it was unapplied and cannot safely remain valid produces `ABANDONED` with an explicit reset or topology reason. Inconclusive work remains explicitly non-terminal and reconciliation-required under its original identity, keeps the affected scope fenced, and blocks reopen until a terminal decision or explicitly approved auditable exception exists. Reset scope, epoch age, missing responses, and retry exhaustion do not prove `ABANDONED`.

The old identity, original epoch, request, and any terminal outcome remain immutable. An old row is never rewritten, rebound, or adopted into a new epoch, and current-epoch executors never re-drive it as ordinary work.

Before the affected scope reopens, recovery completely enumerates the complete old-epoch nonterminal set of durable effects and follow-ups and obtains the authority-fenced terminal evidence or exception required for each row. Redis hints may accelerate discovery but are irrelevant to the completeness of that enumeration.

If abandoned work remains semantically required, an explicitly authorized feature or maintenance flow creates a new request with a new identity on the current epoch. Before creating it, that flow revalidates current scope, ownership, location, aggregate versions, and the governing feature policy. The new request records lineage to the abandoned identity, and audit evidence preserves both the old terminal outcome and the new request.

Correctness-bearing intent may be reconstructed only through this explicit new request and revalidation path. It is not silently carried across the epoch boundary.

## Consequences

- Epoch identity remains truthful: one effect or follow-up never changes the ownership generation under which it was admitted.
- Reset recovery has a complete evidence and reconciliation gate before the region or affected scope reopens; inconclusive work remains visible and fenced rather than being bulk-terminalized.
- Semantically required work may survive a reset only through visible, authorized reconstruction with fresh validation and lineage.
- Operators and support tooling can distinguish abandoned historical work from newly requested work.
- Reconstruction adds durable rows, audit records, and feature-specific policy work instead of reusing the old request.
- Work that is no longer valid is abandoned rather than executed against changed topology or ownership.

## Alternatives Considered

### Silently Rebind Old Work to the New Epoch

Rejected because the same identity would acquire different scope, ownership, location, version, or topology assumptions. Replay and audit could no longer distinguish the originally admitted operation from a newly authorized request.

### Leave Old-Epoch Work Silently Pending or Bulk-Terminalize It

Both are rejected. Silent or unfenced pending work obscures whether the effect may still execute and cannot support safe reopening. Bulk abandonment fabricates certainty when durable evidence is missing. Complete enumeration, authority-fenced reconciliation, and explicit `APPLIED`, evidence-qualified `ABANDONED`, or visible reconciliation-required state preserve the real outcome.

## Implementation and Proof Obligations

Proof must cover complete durable enumeration of the old-epoch nonterminal set—including `SCHEDULED` and durably claimed or staged substates—before reopen; `APPLIED` only with durable application evidence; `ABANDONED` only with durable proof that the effect was unapplied and cannot safely remain valid; explicit non-terminal reconciliation state for inconclusive rows; fenced reopen while any required terminal decision remains unresolved; immutability of old identities, epochs, requests, and terminal outcomes; rejection of rebinding, rewriting, or current-epoch replay; independence from missing or stale Redis hints; authorized post-abandon reconstruction with a new current-epoch identity; lineage linking old and new requests; revalidation of scope, ownership, location, versions, and feature policy; audit visibility for both identities; and idempotent recovery reruns.

The current implementation and runtime proof are not claimed by this decision.

## Reversibility and Revisit Triggers

Reason codes and reconstruction policies may evolve without changing the identity rule. Revisit only if FireMUD adopts a durable cross-epoch workflow whose identity explicitly spans ownership generations and whose migration, replay, authorization, and audit semantics are proven. Until then, evidence-qualified old-epoch reconciliation and post-abandon new-identity reconstruction remain mandatory.
